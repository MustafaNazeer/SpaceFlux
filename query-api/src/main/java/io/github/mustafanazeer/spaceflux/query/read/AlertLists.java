package io.github.mustafanazeer.spaceflux.query.read;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recent alerts list and an object's close approaches (docs/data/mysql-schema.md, Q3 and Q6). GraphQL serves both,
 * and REST serves Q3 at {@code GET /api/alerts/recent}. Each part of a UNION reads at most {@code fetch} rows from its own index, and {@code fetch} is the page size
 * plus one, so a full extra row says another page exists. Q3 filtered by kind runs only that kind's part. The index
 * each part uses is proven in AlertListPlansIntegrationTest.
 */
@Component
class AlertLists {

    static final int TOP_LEVEL_MAX = 200;
    static final int NESTED_MAX = 50;
    static final String CLOSE_APPROACH = "close_approach";
    static final String SPACE_WEATHER_LEVEL = "space_weather_level";

    static final String ALERT_COLUMNS = "SELECT e.alert_seq, e.event_id, e.kind, e.payload, e.received_at, "
            + "k.action, k.acted_at, k.principal, k.note";
    static final String JOIN_EVENT_AND_LATEST_ACK = " JOIN alert_event e ON e.alert_seq = p.alert_seq "
            + "LEFT JOIN alert_acknowledgement k ON k.ack_id = (SELECT MAX(x.ack_id) FROM alert_acknowledgement x "
            + "WHERE x.event_id = e.event_id)";

    /** Q3, first page: fetch, fetch, fetch. */
    static final String RECENT = recent(false);
    /** Q3, after a cursor: before, fetch, before, fetch, fetch; before is the last row's alert_seq. */
    static final String RECENT_AFTER = recent(true);
    /** Q3 for close approaches only, first page: fetch. */
    static final String RECENT_CLOSE_APPROACH = recentOfKind(CLOSE_APPROACH, false);
    /** Q3 for close approaches only, after a cursor: before, fetch. */
    static final String RECENT_CLOSE_APPROACH_AFTER = recentOfKind(CLOSE_APPROACH, true);
    /** Q3 for listed space weather events only, first page: fetch. */
    static final String RECENT_SPACE_WEATHER = recentOfKind(SPACE_WEATHER_LEVEL, false);
    /** Q3 for listed space weather events only, after a cursor: before, fetch. */
    static final String RECENT_SPACE_WEATHER_AFTER = recentOfKind(SPACE_WEATHER_LEVEL, true);
    /** Q6, first page: object, fetch, object, fetch, fetch. */
    static final String OBJECT_APPROACHES = objectApproaches(false);
    /** Q6, after a cursor: object, tca, tca, seq, fetch, then the same for the other role, then fetch. */
    static final String OBJECT_APPROACHES_AFTER = objectApproaches(true);

    private final JdbcClient api;
    private final JsonMapper json;

    AlertLists(@Qualifier("apiJdbcClient") JdbcClient api, JsonMapper json) {
        this.api = api;
        this.json = json;
    }

    static String recent(boolean cursor) {
        String before = cursor ? " WHERE c.alert_seq < ?" : "";
        String listedBefore = cursor ? " AND w.alert_seq < ?" : "";
        return ALERT_COLUMNS + " FROM ((SELECT c.alert_seq FROM close_approach c" + before
                + " ORDER BY c.alert_seq DESC LIMIT ?) UNION ALL (SELECT w.alert_seq FROM space_weather_event w "
                + "WHERE w.listed = 1" + listedBefore + " ORDER BY w.alert_seq DESC LIMIT ?)) p"
                + JOIN_EVENT_AND_LATEST_ACK + " ORDER BY p.alert_seq DESC LIMIT ?";
    }

    /** One half of Q3 alone, read backward from its own index in page order, so nothing is sorted. */
    static String recentOfKind(String kind, boolean cursor) {
        boolean approaches = CLOSE_APPROACH.equals(kind);
        String t = approaches ? "c" : "w";
        String where = approaches ? (cursor ? " WHERE c.alert_seq < ?" : "")
                : " WHERE w.listed = 1" + (cursor ? " AND w.alert_seq < ?" : "");
        return ALERT_COLUMNS + " FROM " + (approaches ? "close_approach c" : "space_weather_event w")
                + " JOIN alert_event e ON e.alert_seq = " + t + ".alert_seq LEFT JOIN alert_acknowledgement k ON "
                + "k.ack_id = (SELECT MAX(x.ack_id) FROM alert_acknowledgement x WHERE x.event_id = e.event_id)"
                + where + " ORDER BY " + t + ".alert_seq DESC LIMIT ?";
    }

    static String objectApproaches(boolean cursor) {
        String after = cursor ? " AND c.time_of_closest_approach <= ? AND (c.time_of_closest_approach < ? "
                + "OR c.alert_seq < ?)" : "";
        String half = " ORDER BY c.time_of_closest_approach DESC, c.alert_seq DESC LIMIT ?)";
        return "SELECT e.alert_seq, e.event_id, p.time_of_closest_approach, e.payload, e.received_at, k.action, "
                + "k.acted_at, k.principal, k.note FROM ((SELECT c.alert_seq, c.time_of_closest_approach FROM "
                + "close_approach c WHERE c.watchlist_number = ?" + after + half
                + " UNION (SELECT c.alert_seq, c.time_of_closest_approach FROM close_approach c "
                + "WHERE c.other_number = ?" + after + half + ") p"
                + JOIN_EVENT_AND_LATEST_ACK + " ORDER BY p.time_of_closest_approach DESC, p.alert_seq DESC LIMIT ?";
    }

    private record Row(long alertSeq, LocalDateTime tca, Map<String, Object> alert) {
    }

    /**
     * Q3, or one of its halves when {@code kind} is set. A cursor names the kind it was returned for, so it pages
     * only the list it came from: the whole list's cursor is the bare alert_seq, as it always was, and one kind's is
     * that kind, a bar, and the alert_seq.
     */
    Map<String, Object> recent(int limit, String after, String kind) {
        if (kind != null && !CLOSE_APPROACH.equals(kind) && !SPACE_WEATHER_LEVEL.equals(kind)) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST, "kind is close_approach or space_weather_level.");
        }
        check(limit, TOP_LEVEL_MAX);
        int fetch = limit + 1;
        Long before = after == null ? null : recentCursor(after, kind);
        String sql;
        List<Object> params = new ArrayList<>();
        // Each part of the UNION takes the cursor, when there is one, and its own LIMIT; the merge takes the last.
        int parts = kind == null ? 2 : 1;
        for (int i = 0; i < parts; i++) {
            if (before != null) {
                params.add(before);
            }
            params.add(fetch);
        }
        if (kind == null) {
            sql = before == null ? RECENT : RECENT_AFTER;
            params.add(fetch);
        } else if (CLOSE_APPROACH.equals(kind)) {
            sql = before == null ? RECENT_CLOSE_APPROACH : RECENT_CLOSE_APPROACH_AFTER;
        } else {
            sql = before == null ? RECENT_SPACE_WEATHER : RECENT_SPACE_WEATHER_AFTER;
        }
        List<Row> rows = api.sql(sql).params(params).query((rs, n) -> new Row(rs.getLong(1), null,
                alert(rs.getString(4), rs.getObject(5, LocalDateTime.class), Acknowledgement.from(rs, 6)))).list();
        String prefix = kind == null ? "" : kind + "|";
        return page(rows, limit, last -> prefix + last.alertSeq());
    }

    private static long recentCursor(String after, String kind) {
        String[] parts = decode(after).split("\\|", -1);
        String issued;
        long seq;
        if (parts.length == 1) {
            issued = null;
            seq = parseSeq(parts[0]);
        } else if (parts.length == 2 && (CLOSE_APPROACH.equals(parts[0]) || SPACE_WEATHER_LEVEL.equals(parts[0]))) {
            issued = parts[0];
            seq = parseSeq(parts[1]);
        } else {
            throw badCursor();
        }
        if (!Objects.equals(issued, kind)) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST,
                    "after was returned for a different kind; ask for the first page again.");
        }
        return seq;
    }

    Map<String, Object> objectApproaches(long noradCatId, int limit, String after) {
        check(limit, NESTED_MAX);
        int fetch = limit + 1;
        List<Row> rows;
        if (after == null) {
            rows = api.sql(OBJECT_APPROACHES).params(noradCatId, fetch, noradCatId, fetch, fetch)
                    .query((rs, n) -> new Row(rs.getLong(1), rs.getObject(3, LocalDateTime.class),
                            alert(rs.getString(4), rs.getObject(5, LocalDateTime.class), Acknowledgement.from(rs, 6))))
                    .list();
        } else {
            String[] parts = decode(after).split("\\|", -1);
            if (parts.length != 2) {
                throw badCursor();
            }
            LocalDateTime tca;
            try {
                tca = LocalDateTime.parse(parts[0]);
            } catch (RuntimeException e) {
                throw badCursor();
            }
            // Outside DATETIME's years MySQL fails the statement instead of matching nothing.
            if (tca.getYear() < 1000 || tca.getYear() > 9999) {
                throw badCursor();
            }
            long seq = parseSeq(parts[1]);
            rows = api.sql(OBJECT_APPROACHES_AFTER)
                    .params(noradCatId, tca, tca, seq, fetch, noradCatId, tca, tca, seq, fetch, fetch)
                    .query((rs, n) -> new Row(rs.getLong(1), rs.getObject(3, LocalDateTime.class),
                            alert(rs.getString(4), rs.getObject(5, LocalDateTime.class), Acknowledgement.from(rs, 6))))
                    .list();
        }
        return page(rows, limit, last -> last.tca() + "|" + last.alertSeq());
    }

    private Map<String, Object> page(List<Row> rows, int limit, java.util.function.Function<Row, String> cursor) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Row row : rows.subList(0, Math.min(limit, rows.size()))) {
            items.add(row.alert());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next", rows.size() > limit ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursor.apply(rows.get(limit - 1)).getBytes(StandardCharsets.US_ASCII)) : null);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> alert(String payload, LocalDateTime receivedAt, Acknowledgement acknowledgement) {
        Map<String, Object> out = json.readValue(payload, LinkedHashMap.class);
        out.put("received_at", ApiTimes.format(receivedAt));
        out.put("acknowledgement", acknowledgement == null ? null : json.convertValue(acknowledgement, Map.class));
        return out;
    }

    private static void check(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST, "limit is from 1 to " + max + ".");
        }
    }

    private static String decode(String after) {
        try {
            return new String(Base64.getUrlDecoder().decode(after), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw badCursor();
        }
    }

    private static long parseSeq(String text) {
        try {
            long seq = Long.parseLong(text);
            if (seq < 1) {
                throw badCursor();
            }
            return seq;
        } catch (NumberFormatException e) {
            throw badCursor();
        }
    }

    private static ApiErrors.Refused badCursor() {
        return new ApiErrors.Refused(HttpStatus.BAD_REQUEST, "after is not a cursor this API returned.");
    }
}
