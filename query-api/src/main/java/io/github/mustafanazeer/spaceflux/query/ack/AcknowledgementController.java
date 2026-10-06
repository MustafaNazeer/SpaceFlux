package io.github.mustafanazeer.spaceflux.query.ack;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.query.auth.SecurityConfig;
import io.github.mustafanazeer.spaceflux.query.read.Acknowledgement;
import io.github.mustafanazeer.spaceflux.query.read.ApiTimes;
import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST} and {@code GET /api/alerts/acknowledgements} (docs/api/rest.md, sections 8 and 9; ADR 0009). */
@RestController
class AcknowledgementController {

    static final int EVENT_ID_MAX = 512;
    static final int NOTE_MAX = 500;
    static final int LIMIT_MAX = 200;
    static final Set<String> FIELDS = Set.of("action", "note");
    static final Set<String> ACTIONS = Set.of("acknowledge", "unacknowledge");

    static final String TARGET = "SELECT e.kind, s.state, s.trigger_kind FROM alert_event e "
            + "LEFT JOIN space_weather_event s ON s.alert_seq = e.alert_seq WHERE e.event_id = ?";
    static final String CURRENT = "SELECT action FROM alert_acknowledgement WHERE event_id = ? "
            + "ORDER BY ack_id DESC LIMIT 1";
    static final String INSERT = "INSERT INTO alert_acknowledgement (event_id, action, principal, note) "
            + "VALUES (?, ?, ?, ?)";
    static final String WRITTEN = "SELECT event_id, action, acted_at, principal, note FROM alert_acknowledgement "
            + "WHERE ack_id = ?";
    static final String EXISTS = "SELECT 1 FROM alert_event WHERE event_id = ?";
    static final String HISTORY = "SELECT ack_id, action, acted_at, principal, note FROM alert_acknowledgement "
            + "WHERE event_id = ? ORDER BY ack_id DESC LIMIT ?";
    static final String HISTORY_AFTER = "SELECT ack_id, action, acted_at, principal, note FROM alert_acknowledgement "
            + "WHERE event_id = ? AND ack_id < ? ORDER BY ack_id DESC LIMIT ?";

    /**
     * Unknown fields and trailing content are refused by these settings, not left to the defaults (SEC-ACK-05); the
     * body is then checked field by field.
     */
    private static final JsonMapper BODY = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** The state check and the insert run under it, so two requests cannot both pass the check (one replica). */
    private final ReentrantLock writes = new ReentrantLock();
    private final JdbcClient api;

    record Written(String eventId, String action, String principal, String actedAt, String note) {
    }

    record History(String eventId, List<Acknowledgement> items, String next) {
    }

    private record Request(String action, String note) {
    }

    AcknowledgementController(@Qualifier("apiJdbcClient") JdbcClient api) {
        this.api = api;
    }

    @PostMapping(path = SecurityConfig.ACKNOWLEDGEMENTS, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Written acknowledge(@RequestParam("event_id") String eventId, @RequestBody(required = false) String body,
            Principal principal) {
        checkEventId(eventId);
        Request request = parse(body);
        record Target(String kind, String state, String trigger) {
        }
        Target target = api.sql(TARGET).param(eventId)
                .query((rs, n) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> new ApiErrors.Refused(HttpStatus.NOT_FOUND, "No alert has this event_id."));
        boolean acknowledgeable = "close_approach".equals(target.kind())
                || "space_weather_level".equals(target.kind()) && "level".equals(target.state())
                        && !"refresh".equals(target.trigger());
        if (!acknowledgeable) {
            throw new ApiErrors.Refused(HttpStatus.CONFLICT, "This alert cannot be acknowledged.");
        }
        writes.lock();
        try {
            String current = api.sql(CURRENT).param(eventId).query(String.class).optional().orElse("unacknowledge");
            if (current.equals(request.action())) {
                throw new ApiErrors.Refused(HttpStatus.CONFLICT, "The alert is already in that state.");
            }
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            api.sql(INSERT).params(eventId, request.action(), principal.getName(), request.note()).update(key);
            return api.sql(WRITTEN).param(key.getKey().longValue())
                    .query((rs, n) -> new Written(rs.getString(1), rs.getString(2), rs.getString(4),
                            ApiTimes.format(rs.getObject(3, LocalDateTime.class)), rs.getString(5)))
                    .single();
        } finally {
            writes.unlock();
        }
    }

    @GetMapping(SecurityConfig.ACKNOWLEDGEMENTS)
    History history(@RequestParam("event_id") String eventId,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "after", required = false) String after) {
        checkEventId(eventId);
        if (limit < 1 || limit > LIMIT_MAX) {
            throw refused("limit is from 1 to 200.");
        }
        Long cursor = after == null ? null : cursor(after);
        if (api.sql(EXISTS).param(eventId).query(Integer.class).optional().isEmpty()) {
            throw new ApiErrors.Refused(HttpStatus.NOT_FOUND, "No alert has this event_id.");
        }
        JdbcClient.StatementSpec sql = cursor == null ? api.sql(HISTORY).params(eventId, limit + 1)
                : api.sql(HISTORY_AFTER).params(eventId, cursor, limit + 1);
        List<Long> ids = new ArrayList<>();
        List<Acknowledgement> items = new ArrayList<>(sql.query((rs, n) -> {
            ids.add(rs.getLong(1));
            return Acknowledgement.from(rs, 2);
        }).list());
        String next = null;
        if (items.size() > limit) {
            items = items.subList(0, limit);
            next = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Long.toString(ids.get(limit - 1)).getBytes(StandardCharsets.US_ASCII));
        }
        return new History(eventId, items, next);
    }

    private static void checkEventId(String eventId) {
        if (eventId.codePointCount(0, eventId.length()) > EVENT_ID_MAX) {
            throw refused("An event_id is at most 512 characters.");
        }
    }

    private static Request parse(String body) {
        JsonNode tree;
        try {
            tree = body == null ? null : BODY.readTree(body);
        } catch (JacksonException e) {
            throw refused("The body is not one JSON object.");
        }
        if (tree == null || !tree.isObject()) {
            throw refused("The body is not one JSON object.");
        }
        for (String name : tree.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw refused("The body holds only action and note.");
            }
        }
        JsonNode action = tree.get("action");
        if (action == null || !action.isString() || !ACTIONS.contains(action.asString())) {
            throw refused("action is acknowledge or unacknowledge.");
        }
        JsonNode note = tree.get("note");
        if (note != null && (!note.isString()
                || note.asString().codePointCount(0, note.asString().length()) > NOTE_MAX)) {
            throw refused("note is text of at most 500 characters.");
        }
        return new Request(action.asString(), note == null ? null : note.asString());
    }

    private static Long cursor(String text) {
        try {
            String digits = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.US_ASCII);
            if (!digits.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(digits);
        } catch (IllegalArgumentException e) {
            throw refused("after is not a cursor this API returned.");
        }
    }

    private static ApiErrors.Refused refused(String detail) {
        return new ApiErrors.Refused(HttpStatus.BAD_REQUEST, detail);
    }
}
