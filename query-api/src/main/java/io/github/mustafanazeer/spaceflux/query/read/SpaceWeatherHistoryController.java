package io.github.mustafanazeer.spaceflux.query.read;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/** {@code GET /api/space-weather/history} (docs/api/rest.md, section 2). */
@RestController
class SpaceWeatherHistoryController {

    static final Duration MAX_RANGE = Duration.ofDays(7);
    static final int LIMIT_MAX = 200;
    /** The years a DATETIME column holds. */
    static final int YEAR_MIN = 1000;
    static final int YEAR_MAX = 9999;

    private static final DateTimeFormatter CURSOR = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcClient api;

    record History(String scale, Integer satellite, List<Item> items, String next) {
    }

    record Item(String intervalStart, String intervalEnd, String sampleTime, String timeTag, String state,
            Integer derivedLevel, String derivedLabel, Double value, String unit, String xrayClass,
            String noDataReason, String noDataSince, String trigger, String eventId) {
    }

    SpaceWeatherHistoryController(@Qualifier("apiJdbcClient") JdbcClient api) {
        this.api = api;
    }

    @GetMapping("/api/space-weather/history")
    History history(@RequestParam("scale") String scale,
            @RequestParam(name = "satellite", required = false) Integer satellite,
            @RequestParam("from") String from, @RequestParam("to") String to,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "after", required = false) String after) {
        if (!Set.of("G", "R", "S").contains(scale)) {
            throw refused("scale is G, R, or S.");
        }
        boolean g = "G".equals(scale);
        if (g && satellite != null) {
            throw refused("satellite is not taken for G.");
        }
        if (!g && (satellite == null || satellite < 1)) {
            throw refused("satellite is required for R and S, and is 1 or more.");
        }
        LocalDateTime start = time(from);
        LocalDateTime end = time(to);
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw refused("to is after from, and at most 7 days after it.");
        }
        if (limit < 1 || limit > LIMIT_MAX) {
            throw refused("limit is from 1 to 200.");
        }
        LocalDateTime cursor = after == null ? null : cursor(after);

        String sql = sql(g, cursor != null);
        List<Object> params = new ArrayList<>();
        params.add(scale);
        if (!g) {
            params.add(satellite);
        }
        params.add(start);
        params.add(end);
        if (cursor != null) {
            params.add(cursor);
        }
        params.add(limit + 1);
        List<LocalDateTime> keys = new ArrayList<>();
        List<Item> items = api.sql(sql).params(params).query((rs, n) -> {
            LocalDateTime k = rs.getObject(1, LocalDateTime.class);
            keys.add(k);
            String keyText = ApiTimes.format(k);
            return new Item(g ? keyText : null, ApiTimes.format(rs.getObject(2, LocalDateTime.class)),
                    g ? null : keyText, rs.getString(3), rs.getString(4), rs.getObject(5, Integer.class),
                    rs.getString(6), rs.getObject(7, Double.class), rs.getString(8), rs.getString(9), rs.getString(10),
                    ApiTimes.format(rs.getObject(11, LocalDateTime.class)), rs.getString(12), rs.getString(13));
        }).list();
        String next = null;
        if (items.size() > limit) {
            items = items.subList(0, limit);
            next = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(CURSOR.format(keys.get(limit - 1)).getBytes(StandardCharsets.US_ASCII));
        }
        return new History(scale, satellite, items, next);
    }

    /**
     * Each key's state is the event with the highest alert_seq among those that carry the key. Parameters: scale,
     * the satellite unless G, from, to, the cursor when there is one, and the row limit.
     */
    static String sql(boolean g, boolean cursor) {
        String key = g ? "interval_start" : "sample_time";
        return "SELECT e." + key + ", e.interval_end, e.time_tag, e.state, e.derived_level, e.derived_label, "
                + "e.value, e.unit, e.xray_class, e.no_data_reason, e.no_data_since, e.trigger_kind, a.event_id "
                + "FROM space_weather_event e JOIN alert_event a ON a.alert_seq = e.alert_seq WHERE e.scale = ? AND "
                + (g ? "e.satellite IS NULL" : "e.satellite = ?") + " AND e." + key + " >= ? AND e." + key + " < ?"
                + (cursor ? " AND e." + key + " > ?" : "") + " AND e.alert_seq = (SELECT MAX(x.alert_seq) FROM "
                + "space_weather_event x WHERE x.scale = e.scale AND "
                + (g ? "x.satellite IS NULL" : "x.satellite = e.satellite") + " AND x." + key + " = e." + key
                + ") ORDER BY e." + key + " LIMIT ?";
    }

    private static ApiErrors.Refused refused(String detail) {
        return new ApiErrors.Refused(HttpStatus.BAD_REQUEST, detail);
    }

    private static LocalDateTime time(String text) {
        try {
            return inRange(LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.MICROS));
        } catch (DateTimeException e) {
            throw refused("from and to are RFC 3339 times with a year from 1000 to 9999.");
        }
    }

    private static LocalDateTime cursor(String text) {
        try {
            return inRange(LocalDateTime.parse(new String(Base64.getUrlDecoder().decode(text),
                    StandardCharsets.US_ASCII), CURSOR));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw refused("after is not a cursor this API returned.");
        }
    }

    private static LocalDateTime inRange(LocalDateTime time) {
        if (time.getYear() < YEAR_MIN || time.getYear() > YEAR_MAX) {
            throw new DateTimeException("year out of range");
        }
        return time;
    }
}
