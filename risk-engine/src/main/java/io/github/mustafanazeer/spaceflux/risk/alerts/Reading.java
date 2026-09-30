package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import io.github.mustafanazeer.spaceflux.risk.weather.BelowFloorException;
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
        /** Rejected, and part of a zero run (Section 5.2, eclipse edge rule 1). */
        BELOW_FLOOR,
        MISSING
    }

    /**
     * As {@link #of(String, JsonNode, Instant, String)}, and also rejects a record whose fetched_at or time_tag is more
     * than 5 minutes after the risk engine's own clock, which no fetch can produce (Section 5.1; threat model T3.5).
     * Such a record cannot be placed in a series.
     */
    public static Optional<Reading> of(String product, JsonNode record, Instant fetchedAt, String sourceUrl,
            Instant now) {
        Optional<Reading> read = of(product, record, fetchedAt, sourceUrl);
        if (read.isEmpty()) {
            return read;
        }
        Reading r = read.get();
        Instant latest = now.plus(FUTURE_TOLERANCE);
        if (fetchedAt.isAfter(latest) || r.time() != null && r.time().isAfter(latest)) {
            return Optional.of(new Reading(r.scale(), product, r.satellite(), r.timeTag(), null, Outcome.REJECTED, 0,
                    null, null, "fetched_at or \"time_tag\" is more than 5 minutes after the risk engine's clock "
                            + now, fetchedAt, sourceUrl));
        }
        return read;
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
            String unusable = unusable(scale, time, satellite, fetchedAt);
            if (unusable != null) {
                return Optional.of(new Reading(scale, product, satellite, timeTag, time, Outcome.REJECTED, 0, null,
                        null, unusable, fetchedAt, sourceUrl));
            }
            String xrayClass = scale == Scale.R ? StormRules.xrayClass(d.value(), d.satellite()).orElse(null) : null;
            return Optional.of(new Reading(scale, product, satellite, timeTag, time,
                    d.level() == 0 ? Outcome.NONE : Outcome.LEVEL, d.level(), d.value(), xrayClass, null, fetchedAt,
                    sourceUrl));
        } catch (MissingValueException e) {
            String unusable = unusable(scale, time, satellite, fetchedAt);
            return Optional.of(new Reading(scale, product, satellite, timeTag, time,
                    unusable == null ? Outcome.MISSING : Outcome.REJECTED, 0, null, null,
                    unusable == null ? e.getMessage() : unusable, fetchedAt, sourceUrl));
        } catch (BelowFloorException e) {
            String unusable = unusable(scale, time, satellite, fetchedAt);
            return Optional.of(new Reading(scale, product, satellite, timeTag, time,
                    unusable == null ? Outcome.BELOW_FLOOR : Outcome.REJECTED, 0, null, null,
                    unusable == null ? e.getMessage() : unusable, fetchedAt, sourceUrl));
        } catch (InvalidReadingException e) {
            if (scale != Scale.G && !readsScale(scale, record)) {
                return Optional.empty();
            }
            // Without its band a GOES record cannot be attributed to a series (Section 5.1), so it is not placed.
            Instant placedAt = scale != Scale.G && !record.has("energy") ? null : time;
            return Optional.of(new Reading(scale, product, satellite, timeTag, placedAt, Outcome.REJECTED, 0, null,
                    null, e.getMessage(), fetchedAt, sourceUrl));
        }
    }

    /** A time_tag may run at most this far past the fetch that brought it (Section 5.1). */
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /**
     * Why a record that has a value cannot be placed in a series: a time_tag that is not a real UTC time, is more than
     * 5 minutes after fetched_at, or for Kp is not the start of a 3 hour UTC interval; or a GOES satellite number below
     * 1 (docs/risk/space-weather-scales.md Section 5.1). Null when it can be placed.
     */
    private static String unusable(Scale scale, Instant time, Integer satellite, Instant fetchedAt) {
        if (time == null) {
            return "\"time_tag\" is not a valid UTC time";
        }
        if (time.isAfter(fetchedAt.plus(FUTURE_TOLERANCE))) {
            return "\"time_tag\" is more than 5 minutes after fetched_at " + fetchedAt;
        }
        if (scale == Scale.G && time.getEpochSecond() % 10_800 != 0) {
            return "\"time_tag\" is not the start of a 3 hour UTC interval";
        }
        if (scale != Scale.G && (satellite == null || satellite < 1)) {
            return "\"satellite\" is not a GOES satellite number";
        }
        return null;
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

    /** A 0 or a value below the floor: rejected for the level, and a member of a zero run. */
    boolean inZeroRun() {
        return outcome == Outcome.MISSING || outcome == Outcome.BELOW_FLOOR;
    }

    /** Dead lettered as a rule rejection; a 0 is SWPC's missing marker and is counted instead. */
    public boolean deadLettered() {
        return outcome == Outcome.REJECTED || outcome == Outcome.BELOW_FLOOR;
    }

    boolean placeable() {
        return unusable(scale, time, satellite, fetchedAt) == null;
    }
}
