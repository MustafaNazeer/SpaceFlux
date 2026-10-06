package io.github.mustafanazeer.spaceflux.query.read;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonRawValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/** {@code GET /api/screening/current} (docs/api/rest.md, section 3). */
@RestController
class ScreeningController {

    /** Runs read per step of the newest first scan; the runs still incomplete ahead of the current one are few. */
    static final int SCAN_BATCH = 8;
    /** The most runs one request checks, so runs that never complete cannot make every request read them all. */
    static final int SCAN_LIMIT = 32;

    private static final Logger LOG = LoggerFactory.getLogger(ScreeningController.class);

    private static final String APPROACH_COLUMNS = "e.event_id, e.payload, k.action, k.acted_at, k.principal, k.note";
    private static final String LATEST_ACK = " LEFT JOIN alert_acknowledgement k ON k.ack_id = (SELECT MAX(x.ack_id) "
            + "FROM alert_acknowledgement x WHERE x.event_id = e.event_id)";
    private static final String CANDIDATE_COLUMNS = "SELECT r.alert_seq, r.run_id, r.window_start, r.window_end, "
            + "r.rules_version, r.approach_count, r.omitted_approach_event_ids FROM screening_run r";
    private static final String NEWEST_FIRST = " ORDER BY r.window_start DESC, r.rules_version DESC, r.alert_seq DESC "
            + "LIMIT " + SCAN_BATCH;

    /** The first batch of runs, newest window first, the higher rules_version first on a tie. */
    static final String CANDIDATES = CANDIDATE_COLUMNS + NEWEST_FIRST;
    /** The next batch after a run: window_start, window_start, rules_version, rules_version, alert_seq. */
    static final String CANDIDATES_AFTER = CANDIDATE_COLUMNS + " WHERE r.window_start < ? OR (r.window_start = ? "
            + "AND (r.rules_version < ? OR (r.rules_version = ? AND r.alert_seq < ?)))" + NEWEST_FIRST;
    /** How many approaches a run lists that are not stored yet. */
    static final String LISTED_MISSING = "SELECT COUNT(*) FROM screening_run_approach a LEFT JOIN alert_event e "
            + "ON e.event_id = a.approach_event_id WHERE a.run_alert_seq = ? AND e.alert_seq IS NULL";
    static final String CUT_COUNT = "SELECT COUNT(*) FROM close_approach WHERE run_id = ?";
    static final String SUMMARY = "SELECT payload FROM alert_event WHERE alert_seq = ?";
    static final String LISTED_APPROACHES = "SELECT " + APPROACH_COLUMNS + " FROM screening_run_approach a "
            + "JOIN alert_event e ON e.event_id = a.approach_event_id" + LATEST_ACK + " WHERE a.run_alert_seq = ? "
            + "ORDER BY a.position";
    static final String CUT_APPROACHES = "SELECT " + APPROACH_COLUMNS + " FROM close_approach c JOIN alert_event e "
            + "ON e.alert_seq = c.alert_seq" + LATEST_ACK + " WHERE c.run_id = ? ORDER BY c.alert_seq";

    private final JdbcClient api;
    private final Clock clock;

    /** {@code summary} is the stored summary text, so it is the summary as received. */
    record Current(boolean stale, @JsonRawValue String summary, List<Approach> approaches) {
    }

    record Approach(String eventId, @JsonRawValue String closeApproach, Acknowledgement acknowledgement) {
    }

    private record Run(long alertSeq, String runId, LocalDateTime windowStart, LocalDateTime windowEnd,
            long rulesVersion, long approachCount, Long omittedApproachEventIds) {

        /** When ids were cut, the run is counted by run_id; otherwise it is exactly the ids its summary lists. */
        boolean listsEveryApproach() {
            return omittedApproachEventIds == null || omittedApproachEventIds == 0;
        }
    }

    ScreeningController(@Qualifier("apiJdbcClient") JdbcClient api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @GetMapping("/api/screening/current")
    Current current() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Run run = newestComplete().orElseThrow(() -> new ApiErrors.Refused(HttpStatus.NOT_FOUND,
                "No complete screening run is stored."));
        String payload = api.sql(SUMMARY).param(run.alertSeq())
                .query(String.class).single();
        boolean stale = now.isAfter(run.windowStart().plusHours(24)) || now.isAfter(run.windowEnd());
        return new Current(stale, RawJson.member(payload, "screening_run"), approaches(run));
    }

    private Optional<Run> newestComplete() {
        Run last = null;
        for (int checked = 0; checked < SCAN_LIMIT; checked += SCAN_BATCH) {
            List<Run> batch = candidates(last);
            for (Run run : batch) {
                if (complete(run)) {
                    return Optional.of(run);
                }
            }
            if (batch.size() < SCAN_BATCH) {
                return Optional.empty();
            }
            last = batch.getLast();
        }
        LOG.warn("none of the {} newest screening runs is complete; the oldest checked is run_id={}", SCAN_LIMIT,
                last.runId());
        return Optional.empty();
    }

    private List<Run> candidates(Run last) {
        JdbcClient.StatementSpec sql = last == null ? api.sql(CANDIDATES)
                : api.sql(CANDIDATES_AFTER).params(last.windowStart(), last.windowStart(), last.rulesVersion(),
                        last.rulesVersion(), last.alertSeq());
        return sql.query((rs, n) -> new Run(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDateTime.class),
                rs.getObject(4, LocalDateTime.class), rs.getLong(5), rs.getLong(6), rs.getObject(7, Long.class)))
                .list();
    }

    private boolean complete(Run run) {
        if (run.listsEveryApproach()) {
            return api.sql(LISTED_MISSING).param(run.alertSeq()).query(Long.class).single() == 0;
        }
        return api.sql(CUT_COUNT).param(run.runId())
                .query(Long.class).single() >= run.approachCount();
    }

    private List<Approach> approaches(Run run) {
        JdbcClient.StatementSpec sql = run.listsEveryApproach()
                ? api.sql(LISTED_APPROACHES).param(run.alertSeq())
                : api.sql(CUT_APPROACHES).param(run.runId());
        return sql.query((rs, n) -> new Approach(rs.getString(1), RawJson.member(rs.getString(2), "close_approach"),
                Acknowledgement.from(rs, 3))).list();
    }
}
