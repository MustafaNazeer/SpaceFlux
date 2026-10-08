package io.github.mustafanazeer.spaceflux.orbit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hipparchus.util.FastMath;
import org.orekit.errors.OrekitException;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;

import tools.jackson.databind.JsonNode;

/**
 * Builds Orekit TLEs from CelesTrak GP JSON records. Unit conversions are the ones Orekit applies
 * when it parses TLE text, so both paths give the same object (docs/risk/orbital-conventions.md 2.2).
 */
public final class GpElementSets {

    private static final Pattern OBJECT_ID = Pattern.compile("(\\d{4})-(\\d{3})([A-Z]{1,3})");

    private GpElementSets() {
    }

    public static TLE toTle(JsonNode gp) {
        TimeScale utc = OrekitData.utc();

        int catalogNumber = integer(gp, "NORAD_CAT_ID");
        if (catalogNumber <= 0) {
            throw new InvalidElementSetException("NORAD_CAT_ID must be positive: " + catalogNumber);
        }
        double meanMotion = number(gp, "MEAN_MOTION");
        if (meanMotion <= 0) {
            throw new InvalidElementSetException("MEAN_MOTION must be positive: " + meanMotion);
        }
        double eccentricity = number(gp, "ECCENTRICITY");
        if (eccentricity < 0 || eccentricity >= 1) {
            throw new InvalidElementSetException("ECCENTRICITY must be in [0, 1): " + eccentricity);
        }

        int launchYear = 0;
        int launchNumber = 0;
        String launchPiece = "";
        JsonNode objectId = gp.get("OBJECT_ID");
        if (objectId != null && objectId.isString()) {
            Matcher m = OBJECT_ID.matcher(objectId.asString());
            if (m.matches()) {
                launchYear = Integer.parseInt(m.group(1));
                launchNumber = Integer.parseInt(m.group(2));
                launchPiece = m.group(3);
            }
        }

        return new TLE(catalogNumber,
                classification(gp),
                launchYear,
                launchNumber,
                launchPiece,
                integer(gp, "EPHEMERIS_TYPE"),
                integer(gp, "ELEMENT_SET_NO"),
                epoch(gp, utc),
                meanMotion * Math.PI / 43200,
                number(gp, "MEAN_MOTION_DOT") * Math.PI / 1.86624e9,
                number(gp, "MEAN_MOTION_DDOT") * Math.PI / 5.3747712e13,
                eccentricity,
                FastMath.toRadians(number(gp, "INCLINATION")),
                FastMath.toRadians(number(gp, "ARG_OF_PERICENTER")),
                FastMath.toRadians(number(gp, "RA_OF_ASC_NODE")),
                FastMath.toRadians(number(gp, "MEAN_ANOMALY")),
                integer(gp, "REV_AT_EPOCH"),
                number(gp, "BSTAR"),
                utc);
    }

    private static JsonNode required(JsonNode gp, String field) {
        JsonNode value = gp.get(field);
        if (value == null || value.isNull()) {
            throw new InvalidElementSetException("missing " + field);
        }
        return value;
    }

    private static double number(JsonNode gp, String field) {
        JsonNode value = required(gp, field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new InvalidElementSetException(field + " must be a finite number: " + value);
        }
        return value.asDouble();
    }

    private static int integer(JsonNode gp, String field) {
        JsonNode value = required(gp, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidElementSetException(field + " must be an integer: " + value);
        }
        return value.asInt();
    }

    private static char classification(JsonNode gp) {
        JsonNode value = required(gp, "CLASSIFICATION_TYPE");
        if (!value.isString() || value.asString().length() != 1) {
            throw new InvalidElementSetException("CLASSIFICATION_TYPE must be one character: " + value);
        }
        return value.asString().charAt(0);
    }

    private static AbsoluteDate epoch(JsonNode gp, TimeScale utc) {
        JsonNode value = required(gp, "EPOCH");
        if (!value.isString()) {
            throw new InvalidElementSetException("EPOCH must be a string: " + value);
        }
        try {
            return new AbsoluteDate(value.asString(), utc);
        } catch (OrekitException | IllegalArgumentException e) {
            throw new InvalidElementSetException("EPOCH is not a UTC date: " + value, e);
        }
    }
}
