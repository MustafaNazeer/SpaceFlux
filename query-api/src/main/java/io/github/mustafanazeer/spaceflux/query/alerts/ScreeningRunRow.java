package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.UtcColumns;
import tools.jackson.databind.JsonNode;

/**
 * One {@code screening_run} row and the rows of its four list tables, in list order, read from a schema valid
 * {@code screening_run} event. The bookkeeping lists ({@code epoch_after_start}, {@code differing_copies},
 * {@code differing_copies_over_cap}) stay in the stored payload (docs/data/mysql-schema.md).
 */
record ScreeningRunRow(long rulesVersion, String runId, LocalDateTime windowStart, LocalDateTime windowEnd,
        LocalDateTime inputFetchedAt, double reportDistanceM, long watchlistAccepted, long catalogAdmitted, long pairs,
        long pairsNotScreenable, long pairsRemovedByPrefilter, long pairsSearched, long approachCount,
        Long omittedApproachEventIds, Long omittedSuppressed, Long omittedRejected, Long omittedNotScreened,
        Long omittedEpochAfterStart, Long omittedDifferingCopies, Long omittedDifferingCopiesOverCap,
        List<String> approachEventIds, List<Suppressed> suppressed, List<Rejected> rejected,
        List<NotScreened> notScreened) {

    static final int CODE_MAX = 64;
    static final int ROLE_MAX = 16;

    record Suppressed(long watchlistNumber, long otherNumber, String watchlistName, String otherName,
            String mechanism, String detail, double minSeparationM, double maxSeparationM,
            LocalDateTime minSeparationAt, boolean stackEntryMayBeStale) {
    }

    record Rejected(long catalogNumber, String name, String role, String code, String reason) {
    }

    record NotScreened(long catalogNumber, String name, String role, String kind, String reason,
            LocalDateTime screenedUntil) {
    }

    /** Throws {@link NotStorable} when a value cannot be stored as received. */
    static ScreeningRunRow of(JsonNode event) {
        JsonNode p = event.get("screening_run");
        JsonNode c = p.get("coverage");
        JsonNode omitted = p.get("omitted");
        return new ScreeningRunRow(
                UtcColumns.unsignedInt("rules_version", event.get("rules_version")),
                UtcColumns.varchar("run_id", p.get("run_id").asString(), CloseApproachRow.RUN_ID_MAX),
                UtcColumns.datetime("window_start", p.get("window_start").asString()),
                UtcColumns.datetime("window_end", p.get("window_end").asString()),
                UtcColumns.datetime("input_fetched_at", p.get("input_fetched_at").asString()),
                UtcColumns.requiredDouble("report_distance_m", p, "report_distance_m"),
                count(c, "coverage.", "watchlist_accepted"),
                count(c, "coverage.", "catalog_admitted"),
                count(c, "coverage.", "pairs"),
                count(c, "coverage.", "pairs_not_screenable"),
                count(c, "coverage.", "pairs_removed_by_prefilter"),
                count(c, "coverage.", "pairs_searched"),
                count(p, "", "approach_count"),
                omitted(omitted, "approach_event_ids"),
                omitted(omitted, "suppressed"),
                omitted(omitted, "rejected"),
                omitted(omitted, "not_screened"),
                omitted(omitted, "epoch_after_start"),
                omitted(omitted, "differing_copies"),
                omitted(omitted, "differing_copies_over_cap"),
                approachIds(p.get("approach_event_ids")),
                suppressed(p.get("suppressed")),
                rejected(p.get("rejected")),
                notScreened(p.get("not_screened")));
    }

    /** The run's window start picks the current run, and its input fetch time is the window start as written. */
    void requireNotLaterThan(LocalDateTime limit, LocalDateTime readAt) {
        UtcColumns.requireNotLaterThan("window_start", windowStart, limit, readAt);
        UtcColumns.requireNotLaterThan("input_fetched_at", inputFetchedAt, limit, readAt);
    }

    private static long count(JsonNode parent, String prefix, String field) {
        return UtcColumns.unsignedInt(prefix + field, parent.get(field));
    }

    private static Long omitted(JsonNode omitted, String field) {
        return omitted == null || omitted.get(field) == null ? null
                : UtcColumns.unsignedInt("omitted." + field, omitted.get(field));
    }

    private static List<String> approachIds(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(UtcColumns.varchar("approach_event_ids[" + i + "]", list.get(i).asString(),
                    AlertRow.EVENT_ID_MAX));
        }
        return out;
    }

    private static List<Suppressed> suppressed(JsonNode list) {
        List<Suppressed> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode s = list.get(i);
            String at = "suppressed[" + i + "].";
            out.add(new Suppressed(
                    UtcColumns.unsignedInt(at + "watchlist_number", s.get("watchlist_number")),
                    UtcColumns.unsignedInt(at + "other_number", s.get("other_number")),
                    UtcColumns.optionalVarchar(at + "watchlist_name", s, "watchlist_name", CloseApproachRow.NAME_MAX),
                    UtcColumns.optionalVarchar(at + "other_name", s, "other_name", CloseApproachRow.NAME_MAX),
                    UtcColumns.varchar(at + "mechanism", s.get("mechanism").asString(), CODE_MAX),
                    UtcColumns.text(at + "detail", s.get("detail").asString()),
                    UtcColumns.requiredDouble(at + "min_separation_m", s, "min_separation_m"),
                    UtcColumns.requiredDouble(at + "max_separation_m", s, "max_separation_m"),
                    UtcColumns.datetime(at + "min_separation_at", s.get("min_separation_at").asString()),
                    s.get("stack_entry_may_be_stale").asBoolean()));
        }
        return out;
    }

    private static List<Rejected> rejected(JsonNode list) {
        List<Rejected> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode r = list.get(i);
            String at = "rejected[" + i + "].";
            out.add(new Rejected(
                    UtcColumns.unsignedInt(at + "catalog_number", r.get("catalog_number")),
                    UtcColumns.optionalVarchar(at + "name", r, "name", CloseApproachRow.NAME_MAX),
                    UtcColumns.varchar(at + "role", r.get("role").asString(), ROLE_MAX),
                    UtcColumns.varchar(at + "code", r.get("code").asString(), CODE_MAX),
                    UtcColumns.text(at + "reason", r.get("reason").asString())));
        }
        return out;
    }

    private static List<NotScreened> notScreened(JsonNode list) {
        List<NotScreened> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode n = list.get(i);
            String at = "not_screened[" + i + "].";
            JsonNode until = n.get("screened_until");
            out.add(new NotScreened(
                    UtcColumns.unsignedInt(at + "catalog_number", n.get("catalog_number")),
                    UtcColumns.optionalVarchar(at + "name", n, "name", CloseApproachRow.NAME_MAX),
                    UtcColumns.varchar(at + "role", n.get("role").asString(), ROLE_MAX),
                    UtcColumns.varchar(at + "kind", n.get("kind").asString(), CODE_MAX),
                    UtcColumns.text(at + "reason", n.get("reason").asString()),
                    until == null || until.isNull() ? null
                            : UtcColumns.datetime(at + "screened_until", until.asString())));
        }
        return out;
    }
}
