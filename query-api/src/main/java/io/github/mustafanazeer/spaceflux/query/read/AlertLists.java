package io.github.mustafanazeer.spaceflux.query.read;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recent alerts list and an object's close approaches (docs/data/mysql-schema.md, Q3 and Q6), served by GraphQL
 * only. Each part of a UNION reads at most {@code fetch} rows from its own index, and {@code fetch} is the page size
 * plus one, so a full extra row says another page exists. The index each part uses is proven in
 * AlertListPlansIntegrationTest.
 */
@Component
class AlertLists {

    static final int TOP_LEVEL_MAX = 200;
    static final int NESTED_MAX = 50;

    static final String ALERT_COLUMNS = "SELECT e.alert_seq, e.event_id, e.kind, e.payload, e.received_at, "
            + "k.action, k.acted_at, k.principal, k.note";
    static final String JOIN_EVENT_AND_LATEST_ACK = " JOIN alert_event e ON e.alert_seq = p.alert_seq "
            + "LEFT JOIN alert_acknowledgement k ON k.ack_id = (SELECT MAX(x.ack_id) FROM alert_acknowledgement x "
            + "WHERE x.event_id = e.event_id)";

    /** Q3, first page: fetch, fetch, fetch. */
    static final String RECENT = recent(false);
    /** Q3, after a cursor: before, fetch, before, fetch, fetch; before is the last row's alert_seq. */
    static final String RECENT_AFTER = recent(true);
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

    Map<String, Object> recent(int limit, String after) {
        check(limit, TOP_LEVEL_MAX);
        int fetch = limit + 1;
        List<Row> rows;
        if (after == null) {
            rows = api.sql(RECENT).params(fetch, fetch, fetch).query((rs, n) -> new Row(rs.getLong(1), null,
                    alert(rs.getString(4), rs.getObject(5, LocalDateTime.class), Acknowledgement.from(rs, 6))))
                    .list();
        } else {
            long before = parseSeq(decode(after));
            rows = api.sql(RECENT_AFTER).params(before, fetch, before, fetch, fetch).query((rs, n) -> new Row(
                    rs.getLong(1), null, alert(rs.getString(4), rs.getObject(5, LocalDateTime.class),
                            Acknowledgement.from(rs, 6)))).list();
        }
        return page(rows, limit, last -> Long.toString(last.alertSeq()));
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
