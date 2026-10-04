package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.LocalDateTime;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.UtcColumns;
import tools.jackson.databind.JsonNode;

/** The envelope columns of one {@code alert_event} row, read from a schema valid alerts event. */
record AlertRow(String eventId, String kind, int schemaVersion, long rulesVersion, LocalDateTime producedAt) {

    static final int EVENT_ID_MAX = 512;

    /** Throws {@link NotStorable} when a value cannot be stored as received. */
    static AlertRow of(JsonNode event) {
        return new AlertRow(
                UtcColumns.varchar("event_id", event.get("event_id").asString(), EVENT_ID_MAX),
                event.get("kind").asString(),
                event.get("schema_version").asInt(),
                UtcColumns.unsignedInt("rules_version", event.get("rules_version")),
                UtcColumns.datetime("produced_at", event.get("produced_at").asString()));
    }
}
