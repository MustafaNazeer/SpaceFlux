package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Feed times read as instants, with an hour of 24 refused (docs/risk/space-weather-scales.md Section 5.1). */
public final class UtcTimes {

    private UtcTimes() {
    }

    /**
     * Like {@link Instant#parse}, which reads 24:00:00 as 00:00 the next day; that would put a record under a second
     * spelling of the same instant, so any hour of 24 is refused.
     */
    public static Instant parse(String text) {
        // Instant.parse also takes a lowercase t.
        int t = Math.max(text.indexOf('T'), text.indexOf('t'));
        if (t >= 0 && text.startsWith("24", t + 1)) {
            throw new DateTimeParseException("an hour of 24 is not accepted", text, t + 1);
        }
        return Instant.parse(text);
    }
}
