package io.github.mustafanazeer.spaceflux.query.read;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonRawValue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/** {@code GET /api/alerts/by-id} (docs/api/rest.md, section 4). */
@RestController
class AlertController {

    static final int EVENT_ID_MAX = 512;

    private final JdbcClient api;

    /** {@code event} is the stored text, written into the response as it is, so it is the event as received. */
    record Alert(@JsonRawValue String event, String receivedAt, Acknowledgement acknowledgement) {
    }

    AlertController(@Qualifier("apiJdbcClient") JdbcClient api) {
        this.api = api;
    }

    @GetMapping("/api/alerts/by-id")
    Alert byId(@RequestParam("event_id") String eventId) {
        if (eventId.codePointCount(0, eventId.length()) > EVENT_ID_MAX) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST, "An event_id is at most 512 characters.");
        }
        record Row(String payload, LocalDateTime receivedAt) {
        }
        Row row = api.sql("SELECT payload, received_at FROM alert_event WHERE event_id = ?").param(eventId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getObject(2, LocalDateTime.class)))
                .optional()
                .orElseThrow(() -> new ApiErrors.Refused(HttpStatus.NOT_FOUND, "No alert has this event_id."));
        return new Alert(row.payload(), ApiTimes.format(row.receivedAt()),
                Acknowledgement.latest(api, eventId).orElse(null));
    }
}
