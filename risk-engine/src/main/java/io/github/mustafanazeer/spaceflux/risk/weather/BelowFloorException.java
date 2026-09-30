package io.github.mustafanazeer.spaceflux.risk.weather;

/**
 * An X-ray flux above 0 but below the 1e-9 W m⁻² floor (docs/risk/space-weather-scales.md Section 5.1). It is
 * rejected like any invalid value, and it counts as part of a zero run like a 0 (Section 5.2, eclipse edge rule 1).
 */
public class BelowFloorException extends InvalidReadingException {

    public BelowFloorException(String message) {
        super(message);
    }
}
