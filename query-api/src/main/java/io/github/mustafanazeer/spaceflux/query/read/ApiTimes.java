package io.github.mustafanazeer.spaceflux.query.read;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Writes a stored UTC time as RFC 3339 with the six fraction digits the database holds. */
final class ApiTimes {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private ApiTimes() {
    }

    static String format(LocalDateTime time) {
        return time == null ? null : UTC.format(time);
    }
}
