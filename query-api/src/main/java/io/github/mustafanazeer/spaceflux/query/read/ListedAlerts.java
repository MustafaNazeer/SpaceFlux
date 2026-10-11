package io.github.mustafanazeer.spaceflux.query.read;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * One stored alert as a live notice (ADR 0014, decision 3), read only when the recent alerts list shows it: a close
 * approach, or a space weather event whose generated listed column is set (AlertLists, Q3).
 */
@Component
public class ListedAlerts {

    static final String LISTED_BY_ID = "SELECT e.payload, e.received_at FROM alert_event e "
            + "LEFT JOIN close_approach c ON c.alert_seq = e.alert_seq "
            + "LEFT JOIN space_weather_event w ON w.alert_seq = e.alert_seq "
            + "WHERE e.event_id = ? AND (c.alert_seq IS NOT NULL OR w.listed = 1)";

    private static final String[] FIELDS = {"kind", "rules_version", "event_id", "produced_at"};

    private final JdbcClient api;
    private final JsonMapper json;

    ListedAlerts(@Qualifier("apiJdbcClient") JdbcClient api, JsonMapper json) {
        this.api = api;
        this.json = json;
    }

    /** The notice, or empty when the alert is not stored or the list does not show it. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> notice(String eventId) {
        record Row(String payload, LocalDateTime receivedAt) {
        }
        return api.sql(LISTED_BY_ID).param(eventId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getObject(2, LocalDateTime.class)))
                .optional().map(row -> {
                    Map<String, Object> event = json.readValue(row.payload(), LinkedHashMap.class);
                    Map<String, Object> notice = new LinkedHashMap<>();
                    for (String field : FIELDS) {
                        notice.put(field, event.get(field));
                    }
                    notice.put("received_at", ApiTimes.format(row.receivedAt()));
                    notice.put("space_weather_level", event.get("space_weather_level"));
                    notice.put("close_approach", event.get("close_approach"));
                    return notice;
                });
    }
}
