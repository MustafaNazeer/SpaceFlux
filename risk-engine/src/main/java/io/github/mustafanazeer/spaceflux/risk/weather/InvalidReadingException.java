package io.github.mustafanazeer.spaceflux.risk.weather;

/** A record whose value cannot set a level; the message is the reason (docs/risk/space-weather-scales.md Section 5). */
public class InvalidReadingException extends RuntimeException {

    public InvalidReadingException(String message) {
        super(message);
    }
}
