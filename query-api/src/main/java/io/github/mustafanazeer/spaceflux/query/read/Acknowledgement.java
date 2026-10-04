package io.github.mustafanazeer.spaceflux.query.read;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The newest acknowledgement of an alert as an anonymous viewer sees it: the action and its time only, never the
 * note or the principal (ADR 0009, decision 5).
 */
record Acknowledgement(String action, String actedAt) {

    static Optional<Acknowledgement> latest(JdbcClient api, String eventId) {
        return api.sql("SELECT action, acted_at FROM alert_acknowledgement WHERE event_id = ? "
                + "ORDER BY ack_id DESC LIMIT 1").param(eventId)
                .query((rs, n) -> new Acknowledgement(rs.getString(1),
                        ApiTimes.format(rs.getObject(2, LocalDateTime.class))))
                .optional();
    }
}
