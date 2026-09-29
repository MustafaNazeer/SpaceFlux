package io.github.mustafanazeer.spaceflux.risk.weather;

import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Maps one raw.swpc record to a level on the G, R, or S scale, with the thresholds, fields, and "≥" comparisons of
 * docs/risk/space-weather-scales.md (Summary table). Records a scale is not read from give no level.
 */
public final class StormRules {

    /** Kp in thirds, level 1 to 5: 5-, 6-, 7-, 8-, and 9o only (Section 1.2). */
    private static final double[] G_THRESHOLDS = {4.67, 5.67, 6.67, 7.67, 9.00};

    /** Kp carries two decimals, so 4.67 must compare equal to 4.67 whatever its binary form (Section 1.2). */
    static final double KP_TOLERANCE = 0.005;

    /** W m⁻², M1, M5, X1, X10, X20 (Section 2.1). */
    private static final double[] R_THRESHOLDS = {1e-5, 5e-5, 1e-4, 1e-3, 2e-3};

    /** pfu (Section 3.1). */
    private static final double[] S_THRESHOLDS = {10, 100, 1_000, 10_000, 100_000};

    static final String R_BAND = "0.1-0.8nm";
    static final String S_CHANNEL = ">=10 MeV";

    private StormRules() {
    }

    static int gLevel(double kp) {
        return level(kp, G_THRESHOLDS, KP_TOLERANCE);
    }

    static int rLevel(double flux) {
        return level(flux, R_THRESHOLDS, 0);
    }

    static int sLevel(double flux) {
        return level(flux, S_THRESHOLDS, 0);
    }

    /**
     * The level derived from the record, or empty when no scale is read from it (another X-ray band, another proton
     * channel, or the alerts product). Throws {@link InvalidReadingException} when the value cannot set a level.
     */
    public static Optional<DerivedLevel> derive(String product, JsonNode record) {
        return switch (product) {
            case "swpc.kp" -> Optional.of(new DerivedLevel(Scale.G, gLevel(value(record, "Kp")), product,
                    text(record, "time_tag"), null, value(record, "Kp")));
            case "swpc.goes.xrays" -> R_BAND.equals(text(record, "energy"))
                    ? Optional.of(goes(Scale.R, rLevel(value(record, "flux")), product, record))
                    : Optional.empty();
            case "swpc.goes.protons" -> S_CHANNEL.equals(text(record, "energy"))
                    ? Optional.of(goes(Scale.S, sLevel(value(record, "flux")), product, record))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static DerivedLevel goes(Scale scale, int level, String product, JsonNode record) {
        JsonNode satellite = record.get("satellite");
        if (satellite == null || !satellite.isIntegralNumber()) {
            throw new InvalidReadingException("\"satellite\" is missing or not a whole number");
        }
        return new DerivedLevel(scale, level, product, text(record, "time_tag"), satellite.asInt(),
                value(record, "flux"));
    }

    private static int level(double value, double[] thresholds, double tolerance) {
        if (!Double.isFinite(value)) {
            throw new InvalidReadingException("value " + value + " is not finite");
        }
        if (value < 0) {
            throw new InvalidReadingException("value " + value + " is negative");
        }
        int level = 0;
        while (level < thresholds.length && value >= thresholds[level] - tolerance) {
            level++;
        }
        return level;
    }

    private static double value(JsonNode record, String field) {
        JsonNode v = record.get(field);
        if (v == null || v.isNull()) {
            throw new InvalidReadingException("\"" + field + "\" is missing");
        }
        if (!v.isNumber()) {
            throw new InvalidReadingException("\"" + field + "\" is not a number: " + v);
        }
        return v.asDouble();
    }

    private static String text(JsonNode record, String field) {
        JsonNode v = record.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
