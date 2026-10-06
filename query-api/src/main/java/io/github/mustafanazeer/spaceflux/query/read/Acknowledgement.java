package io.github.mustafanazeer.spaceflux.query.read;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.mustafanazeer.spaceflux.query.auth.Viewer;

/**
 * An acknowledgement row as the caller may see it: the action and its time for everyone, and the principal and note
 * for the signed in operator only. For anyone else they are left out of the response, never sent to be hidden (ADR
 * 0009, decision 5).
 */
public record Acknowledgement(String action, String actedAt, String principal, String note) {

    static final String LATEST = "SELECT action, acted_at, principal, note FROM alert_acknowledgement "
            + "WHERE event_id = ? ORDER BY ack_id DESC LIMIT 1";

    /**
     * Reads action, acted_at, principal and note from four columns starting at {@code first}; null when the row is
     * absent, as a LEFT JOIN with no acknowledgement gives.
     */
    public static Acknowledgement from(ResultSet rs, int first) throws SQLException {
        String action = rs.getString(first);
        if (action == null) {
            return null;
        }
        String actedAt = ApiTimes.format(rs.getObject(first + 1, LocalDateTime.class));
        return Viewer.isOperator() ? new Acknowledgement(action, actedAt, rs.getString(first + 2),
                rs.getString(first + 3)) : new Acknowledgement(action, actedAt, null, null);
    }

    static Optional<Acknowledgement> latest(JdbcClient api, String eventId) {
        return api.sql(LATEST).param(eventId).query((rs, n) -> from(rs, 1)).optional();
    }
}
