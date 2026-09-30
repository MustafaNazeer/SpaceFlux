package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import io.github.mustafanazeer.spaceflux.risk.weather.DerivedLevel;
import io.github.mustafanazeer.spaceflux.risk.weather.InvalidReadingException;
import io.github.mustafanazeer.spaceflux.risk.weather.MissingValueException;
import io.github.mustafanazeer.spaceflux.risk.weather.Scale;
import io.github.mustafanazeer.spaceflux.risk.weather.StormRules;
import tools.jackson.databind.JsonNode;

/**
 * One raw.swpc record a scale is read from, classified by the storm rules. {@code time} is null when the record has
 * no usable time_tag, and {@code satellite} is null for Kp and for a GOES record without one; such a record cannot be
 * placed in a series (docs/risk/space-weather-scales.md Section 5.3).
 */
public record Reading(Scale scale, String product, Integer satellite, String timeTag, Instant time, Outcome outcome,
        int level, Double value, String xrayClass, String reason, Instant fetchedAt, String sourceUrl) {

    public enum Outcome {
        LEVEL,
        NONE,
        REJECTED,
        MISSING
    }

    /** Empty when no scale is read from the record: another band or channel, or the alerts product. */
    public static Optional<Reading> of(String product, JsonNode record, Instant fetchedAt, String sourceUrl) {
        Scale scale = switch (product) {
            case "swpc.kp" -> Scale.G;
            case "swpc.goes.xrays" -> Scale.R;
            case "swpc.goes.protons" -> Scale.S;
            default -> null;
        };
        if (scale == null) {
            return Optional.empty();
        }
        String timeTag = text(record, "time_tag");
        Instant time = parse(scale, timeTag);
        Integer satellite = satellite(record);
        try {
            Optional<DerivedLevel> derived = StormRules.derive(product, record);
            if (derived.isEmpty()) {
                return Optional.empty();
            }
            DerivedLevel d = derived.get();
            String xrayClass = scale == Scale.R ? StormRules.xrayClass(d.value(), d.satellite()).orElse(null) : null;
            return Optional.of(new Reading(scale, product, satellite, timeTag, time,
                    d.level() == 0 ? Outcome.NONE : Outcome.LEVEL, d.level(), d.value(), xrayClass, null, fetchedAt,
                    sourceUrl));
        } catch (MissingValueException e) {
            return Optional.of(new Reading(scale, product, satellite, timeTag, time, Outcome.MISSING, 0, null, null,
                    e.getMessage(), fetchedAt, sourceUrl));
        } catch (InvalidReadingException e) {
            if (scale != Scale.G && !readsScale(scale, record)) {
                return Optional.empty();
            }
            return Optional.of(new Reading(scale, product, satellite, timeTag, time, Outcome.REJECTED, 0, null, null,
                    e.getMessage(), fetchedAt, sourceUrl));
        }
    }

    /** A rejected GOES record belongs to the scale's band or channel only when its energy says so, or is missing. */
    private static boolean readsScale(Scale scale, JsonNode record) {
        JsonNode energy = record.get("energy");
        if (energy == null || !energy.isString()) {
            return true;
        }
        return energy.asString().equals(scale == Scale.R ? "0.1-0.8nm" : ">=10 MeV");
    }

    private static String text(JsonNode record, String field) {
        JsonNode v = record.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static Integer satellite(JsonNode record) {
        JsonNode v = record.get("satellite");
        return v != null && v.isIntegralNumber() && v.canConvertToInt() ? v.asInt() : null;
    }

    /** Kp time_tag has no zone designator and is UTC (Section 1.3); GOES time_tag ends in Z. */
    private static Instant parse(Scale scale, String timeTag) {
        if (timeTag == null) {
            return null;
        }
        try {
            return Instant.parse(scale == Scale.G ? timeTag + "Z" : timeTag);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    boolean placeable() {
        return time != null && (scale == Scale.G || satellite != null);
    }
}
