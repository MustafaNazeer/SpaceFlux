package io.github.mustafanazeer.spaceflux.risk.weather;

/**
 * A value SWPC writes where there is no measurement, an X-ray flux of exactly 0 (docs/risk/space-weather-scales.md
 * Section 5.1). The record is well formed: it sets no level and counts as "no data", but it is not corrupt data.
 */
public class MissingValueException extends InvalidReadingException {

    public MissingValueException(String message) {
        super(message);
    }
}
