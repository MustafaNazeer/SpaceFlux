package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.LocalDateTime;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.UtcColumns;
import tools.jackson.databind.JsonNode;

/** One {@code close_approach} row, read from a schema valid {@code close_approach} event. */
record CloseApproachRow(long rulesVersion, String runId, LocalDateTime windowStart, LocalDateTime windowEnd,
        long watchlistNumber, String watchlistName, double watchlistElementAgeDays, long otherNumber,
        String otherName, double otherElementAgeDays, LocalDateTime timeOfClosestApproach, double missDistanceM,
        double relativeSpeedMPerS) {

    static final int RUN_ID_MAX = 64;
    static final int NAME_MAX = 64;

    /** Throws {@link NotStorable} when a value cannot be stored as received. */
    static CloseApproachRow of(JsonNode event) {
        JsonNode p = event.get("close_approach");
        JsonNode w = p.get("watchlist_object");
        JsonNode o = p.get("other_object");
        return new CloseApproachRow(
                UtcColumns.unsignedInt("rules_version", event.get("rules_version")),
                UtcColumns.varchar("run_id", p.get("run_id").asString(), RUN_ID_MAX),
                UtcColumns.datetime("window_start", p.get("window_start").asString()),
                UtcColumns.datetime("window_end", p.get("window_end").asString()),
                UtcColumns.unsignedInt("watchlist_object.catalog_number", w.get("catalog_number")),
                UtcColumns.optionalVarchar("watchlist_object.name", w, "name", NAME_MAX),
                UtcColumns.requiredDouble("watchlist_object.element_age_days", w, "element_age_days"),
                UtcColumns.unsignedInt("other_object.catalog_number", o.get("catalog_number")),
                UtcColumns.optionalVarchar("other_object.name", o, "name", NAME_MAX),
                UtcColumns.requiredDouble("other_object.element_age_days", o, "element_age_days"),
                UtcColumns.datetime("time_of_closest_approach", p.get("time_of_closest_approach").asString()),
                UtcColumns.requiredDouble("miss_distance_m", p, "miss_distance_m"),
                UtcColumns.requiredDouble("relative_speed_m_per_s", p, "relative_speed_m_per_s"));
    }

    /** The run's window start picks the current run, so it gets the same bound as a series time. */
    void requireNotLaterThan(LocalDateTime limit, LocalDateTime readAt) {
        UtcColumns.requireNotLaterThan("window_start", windowStart, limit, readAt);
    }
}
