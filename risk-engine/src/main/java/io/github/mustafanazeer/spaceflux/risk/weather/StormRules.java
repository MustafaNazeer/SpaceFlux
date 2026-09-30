package io.github.mustafanazeer.spaceflux.risk.weather;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** The corrected X-ray flux has a 1e-9 W m⁻² minimum; NCEI's valid_max is 0.2 (Sections 5.1 and 5.2). */
    private static final float R_FLOOR = 1e-9f;
    private static final float R_MAX = 0.2f;

    /** The first satellite whose irradiances are in physical units, so a class applies (Section 2.4). */
    private static final int FIRST_GOES_R = 16;

    static final String R_BAND = "0.1-0.8nm";
    static final String S_CHANNEL = ">=10 MeV";

    /** Kp tops out at 9o; anything above it is not a Kp value (Section 5). */
    static final double KP_MAX = 9.00;

    private StormRules() {
    }

    static int gLevel(double kp) {
        usable("Kp", kp);
        if (kp > KP_MAX + KP_TOLERANCE) {
            throw new InvalidReadingException("\"Kp\" " + kp + " is above 9.00, the largest Kp value");
        }
        int level = 0;
        while (level < G_THRESHOLDS.length && kp >= G_THRESHOLDS[level] - KP_TOLERANCE) {
            level++;
        }
        return level;
    }

    static int rLevel(double flux) {
        usable("flux", flux);
        if (flux == 0) {
            throw new MissingValueException("\"flux\" is 0, SWPC's marker for a missing X-ray measurement");
        }
        if ((float) flux < R_FLOOR) {
            throw new InvalidReadingException("\"flux\" " + flux + " is below the 1e-9 W m-2 minimum");
        }
        if ((float) flux > R_MAX) {
            throw new InvalidReadingException("\"flux\" " + flux + " is above 0.2 W m-2, the largest valid value");
        }
        return goesLevel(flux, R_THRESHOLDS);
    }

    /**
     * The X-ray class of one R1 or higher value from GOES-16 or later: M or X by decade, the number truncated to one
     * decimal from the shortest decimal of the 32 bit float, so class and level agree at every threshold (Section 2.4).
     */
    static Optional<String> xrayClass(double flux, int satellite) {
        if (satellite < FIRST_GOES_R || rLevel(flux) == 0) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(Float.toString((float) flux));
        boolean x = value.compareTo(new BigDecimal("1e-4")) >= 0;
        BigDecimal number = value.divide(new BigDecimal(x ? "1e-4" : "1e-5")).setScale(1, RoundingMode.DOWN);
        return Optional.of((x ? "X" : "M") + number.toPlainString());
    }

    static int sLevel(double flux) {
        return goesLevel(flux, S_THRESHOLDS);
    }

    /**
     * SWPC publishes GOES values in its JSON as 32 bit floats, so the value and the threshold are compared at that
     * precision: the float nearest 1e-5 lies below the double 1e-5 and would otherwise read one level low
     * (Section 2.1).
     */
    private static int goesLevel(double flux, double[] thresholds) {
        usable("flux", flux);
        int level = 0;
        while (level < thresholds.length && (float) flux >= (float) thresholds[level]) {
            level++;
        }
        return level;
    }

    /**
     * The level derived from the record, or empty when no scale is read from it (another X-ray band, another proton
     * channel, or the alerts product). Throws {@link InvalidReadingException} when the record cannot set a level.
     */
    public static Optional<DerivedLevel> derive(String product, JsonNode record) {
        return switch (product) {
            case "swpc.kp" -> {
                double kp = value(record, "Kp");
                yield Optional.of(new DerivedLevel(Scale.G, gLevel(kp), product, text(record, "time_tag"), null, kp));
            }
            case "swpc.goes.xrays" -> R_BAND.equals(text(record, "energy"))
                    ? Optional.of(goes(Scale.R, product, record))
                    : Optional.empty();
            case "swpc.goes.protons" -> S_CHANNEL.equals(text(record, "energy"))
                    ? Optional.of(goes(Scale.S, product, record))
                    : Optional.empty();
            case "swpc.alerts" -> Optional.empty();
            default -> throw new InvalidReadingException("unknown product " + product);
        };
    }

    private static DerivedLevel goes(Scale scale, String product, JsonNode record) {
        String timeTag = text(record, "time_tag");
        JsonNode satellite = record.get("satellite");
        if (satellite == null || !satellite.isIntegralNumber() || !satellite.canConvertToInt()) {
            throw new InvalidReadingException("\"satellite\" is missing or not a whole number");
        }
        double flux = value(record, "flux");
        int level = scale == Scale.R ? rLevel(flux) : sLevel(flux);
        return new DerivedLevel(scale, level, product, timeTag, satellite.asInt(), flux);
    }

    private static void usable(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new InvalidReadingException("\"" + field + "\" " + value + " is not finite");
        }
        if (value < 0) {
            throw new InvalidReadingException("\"" + field + "\" " + value + " is negative");
        }
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
        if (v == null || v.isNull()) {
            throw new InvalidReadingException("\"" + field + "\" is missing");
        }
        if (!v.isString()) {
            throw new InvalidReadingException("\"" + field + "\" is not text: " + v);
        }
        return v.asString();
    }
}
