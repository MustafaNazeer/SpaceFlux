package io.github.mustafanazeer.spaceflux.query.alerts;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores alerts events through the consumer pool, one transaction per event: the {@code alert_event} row, the row
 * of its kind, and for a space weather event the series projection, so a crash never leaves one without the other.
 * A repeat is recognized by error 1062 on the {@code event_id} key, never by {@code INSERT IGNORE}, which would also
 * hide a value the column cannot hold (docs/data/mysql-schema.md, Idempotent consumption).
 */
@Component
class MysqlAlertStore implements AlertStore {

    static final int DUPLICATE_KEY = 1062;
    static final String EVENT_ID_KEY = "uk_alert_event_event_id";
    static final String RUN_ID_KEY = "uk_screening_run_run_id";
    // Errors a stored value causes, as opposed to the database being unreachable or failing: data too long, out of
    // range, an incorrect date or time, an incorrect string, a failed check constraint, a null in a NOT NULL column.
    // After the checks in AlertRow, 1048 and 1292 can only come from a fault in this code; they are dead lettered by
    // design too, so the record is kept in alerts.dlq and the partition does not stop.
    static final Set<Integer> VALUE_REFUSED = Set.of(1406, 1264, 1292, 1366, 3819, 1048);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    MysqlAlertStore(@Qualifier("consumerJdbcClient") JdbcClient jdbc,
            @Qualifier("consumerTransactionManager") JdbcTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public boolean store(EventRows rows, String payload, int partition, long offset) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                long alertSeq;
                try {
                    alertSeq = insertEvent(rows.envelope(), payload, partition, offset);
                } catch (DataAccessException e) {
                    if (isRepeat(e)) {
                        status.setRollbackOnly();
                        return false;
                    }
                    throw e;
                }
                if (rows.spaceWeather() != null) {
                    insertSpaceWeather(alertSeq, rows.spaceWeather());
                    applyToSeries(alertSeq, rows.spaceWeather());
                } else if (rows.closeApproach() != null) {
                    insertCloseApproach(alertSeq, rows.closeApproach());
                } else if (rows.screeningRun() != null) {
                    insertScreeningRun(alertSeq, rows.screeningRun());
                }
                return true;
            }));
        } catch (DataAccessException e) {
            SQLException sql = sqlCause(e);
            if (sql != null && sql.getErrorCode() == DUPLICATE_KEY && String.valueOf(sql.getMessage())
                    .contains(RUN_ID_KEY)) {
                // A screening_run event_id is built from its run_id, so this is a second event_id claiming a run
                // already stored: retrying would never succeed.
                throw new NotStorable("a screening run with this run_id is already stored under another event_id");
            }
            if (sql != null && VALUE_REFUSED.contains(sql.getErrorCode())) {
                throw new NotStorable("the database refused a value: error " + sql.getErrorCode() + ", "
                        + sql.getMessage());
            }
            throw e;
        }
    }

    /** Returns the {@code alert_seq} the database assigned. */
    private long insertEvent(AlertRow row, String payload, int partition, long offset) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO alert_event (event_id, kind, schema_version, rules_version, produced_at, "
                + "source_partition, source_offset, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(row.eventId(), row.kind(), row.schemaVersion(), row.rulesVersion(), row.producedAt(),
                        partition, offset, payload)
                .update(key);
        return key.getKeyAs(Number.class).longValue();
    }

    private void insertSpaceWeather(long alertSeq, SpaceWeatherRow r) {
        jdbc.sql("INSERT INTO space_weather_event (alert_seq, rules_version, scale, satellite, product, state, "
                + "derived_level, derived_label, previous_state, previous_derived_level, trigger_kind, derived_from, "
                + "estimated, band, channel, unit, value, xray_class, time_tag, interval_start, interval_end, "
                + "sample_time, averaging_period_s, fetched_at, source_url, freshness_reference, timer_refresh_at, "
                + "no_data_reason, no_data_since, restated_by_time_tag, ended_by_satellite) VALUES (?, ?, ?, ?, ?, "
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(alertSeq, r.rulesVersion(), r.scale(), r.satellite(), r.product(), r.state(),
                        r.derivedLevel(), r.derivedLabel(), r.previousState(), r.previousDerivedLevel(),
                        r.triggerKind(), r.derivedFrom(), r.estimated(), r.band(), r.channel(), r.unit(), r.value(),
                        r.xrayClass(), r.timeTag(), r.intervalStart(), r.intervalEnd(), r.sampleTime(),
                        r.averagingPeriodS(), r.fetchedAt(), r.sourceUrl(), r.freshnessReference(),
                        r.timerRefreshAt(), r.noDataReason(), r.noDataSince(), r.restatedByTimeTag(),
                        r.endedBySatellite())
                .update();
    }

    private void insertCloseApproach(long alertSeq, CloseApproachRow r) {
        jdbc.sql("INSERT INTO close_approach (alert_seq, rules_version, run_id, window_start, window_end, "
                + "watchlist_number, watchlist_name, watchlist_element_age_days, other_number, other_name, "
                + "other_element_age_days, time_of_closest_approach, miss_distance_m, relative_speed_m_per_s) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(alertSeq, r.rulesVersion(), r.runId(), r.windowStart(), r.windowEnd(), r.watchlistNumber(),
                        r.watchlistName(), r.watchlistElementAgeDays(), r.otherNumber(), r.otherName(),
                        r.otherElementAgeDays(), r.timeOfClosestApproach(), r.missDistanceM(),
                        r.relativeSpeedMPerS())
                .update();
    }

    /** The run row, then each list's rows with their 0 based position, since list order carries meaning. */
    private void insertScreeningRun(long alertSeq, ScreeningRunRow r) {
        jdbc.sql("INSERT INTO screening_run (alert_seq, rules_version, run_id, window_start, window_end, "
                + "input_fetched_at, report_distance_m, watchlist_accepted, catalog_admitted, pairs, "
                + "pairs_not_screenable, pairs_removed_by_prefilter, pairs_searched, approach_count, "
                + "omitted_approach_event_ids, omitted_suppressed, omitted_rejected, omitted_not_screened, "
                + "omitted_epoch_after_start, omitted_differing_copies, omitted_differing_copies_over_cap) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(alertSeq, r.rulesVersion(), r.runId(), r.windowStart(), r.windowEnd(), r.inputFetchedAt(),
                        r.reportDistanceM(), r.watchlistAccepted(), r.catalogAdmitted(), r.pairs(),
                        r.pairsNotScreenable(), r.pairsRemovedByPrefilter(), r.pairsSearched(), r.approachCount(),
                        r.omittedApproachEventIds(), r.omittedSuppressed(), r.omittedRejected(),
                        r.omittedNotScreened(), r.omittedEpochAfterStart(), r.omittedDifferingCopies(),
                        r.omittedDifferingCopiesOverCap())
                .update();
        for (int i = 0; i < r.approachEventIds().size(); i++) {
            jdbc.sql("INSERT INTO screening_run_approach (run_alert_seq, position, approach_event_id) "
                    + "VALUES (?, ?, ?)").params(alertSeq, i, r.approachEventIds().get(i)).update();
        }
        for (int i = 0; i < r.suppressed().size(); i++) {
            ScreeningRunRow.Suppressed s = r.suppressed().get(i);
            jdbc.sql("INSERT INTO screening_run_suppressed (run_alert_seq, position, watchlist_number, "
                    + "other_number, watchlist_name, other_name, mechanism, detail, min_separation_m, "
                    + "max_separation_m, min_separation_at, stack_entry_may_be_stale) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(alertSeq, i, s.watchlistNumber(), s.otherNumber(), s.watchlistName(), s.otherName(),
                            s.mechanism(), s.detail(), s.minSeparationM(), s.maxSeparationM(), s.minSeparationAt(),
                            s.stackEntryMayBeStale())
                    .update();
        }
        for (int i = 0; i < r.rejected().size(); i++) {
            ScreeningRunRow.Rejected x = r.rejected().get(i);
            jdbc.sql("INSERT INTO screening_run_rejected (run_alert_seq, position, catalog_number, name, role, "
                    + "code, reason) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(alertSeq, i, x.catalogNumber(), x.name(), x.role(), x.code(), x.reason()).update();
        }
        for (int i = 0; i < r.notScreened().size(); i++) {
            ScreeningRunRow.NotScreened n = r.notScreened().get(i);
            jdbc.sql("INSERT INTO screening_run_not_screened (run_alert_seq, position, catalog_number, name, role, "
                    + "kind, reason, screened_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(alertSeq, i, n.catalogNumber(), n.name(), n.role(), n.kind(), n.reason(),
                            n.screenedUntil())
                    .update();
        }
    }

    /** Reads the series row under a lock, applies the event, and writes the row back if it changed. */
    private void applyToSeries(long alertSeq, SpaceWeatherRow event) {
        SeriesState current = jdbc.sql("SELECT scale, series_satellite, rules_version, state, derived_level, "
                + "derived_label, value, unit, xray_class, time_tag, interval_start, sample_time, no_data_reason, "
                + "no_data_since, ended_by_satellite, freshness_reference, state_alert_seq, last_alert_seq "
                + "FROM space_weather_series WHERE scale = ? AND series_satellite = ? FOR UPDATE")
                .params(event.scale(), event.seriesSatellite())
                .query((rs, n) -> new SeriesState(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getString(4),
                        rs.getObject(5, Integer.class), rs.getString(6), rs.getObject(7, Double.class),
                        rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getObject(11, LocalDateTime.class), rs.getObject(12, LocalDateTime.class),
                        rs.getString(13), rs.getObject(14, LocalDateTime.class), rs.getObject(15, Integer.class),
                        rs.getObject(16, LocalDateTime.class), rs.getLong(17), rs.getLong(18)))
                .optional().orElse(null);
        SeriesState next = SeriesProjection.apply(current, event, alertSeq);
        if (next == null || next == current) {
            return;
        }
        Object[] values = {next.rulesVersion(), next.state(), next.derivedLevel(), next.derivedLabel(), next.value(),
                next.unit(), next.xrayClass(), next.timeTag(), next.intervalStart(), next.sampleTime(),
                next.noDataReason(), next.noDataSince(), next.endedBySatellite(), next.freshnessReference(),
                next.stateAlertSeq(), next.lastAlertSeq(), next.scale(), next.seriesSatellite()};
        if (current == null) {
            jdbc.sql("INSERT INTO space_weather_series (rules_version, state, derived_level, derived_label, value, "
                    + "unit, xray_class, time_tag, interval_start, sample_time, no_data_reason, no_data_since, "
                    + "ended_by_satellite, freshness_reference, state_alert_seq, last_alert_seq, scale, "
                    + "series_satellite) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(values).update();
        } else {
            jdbc.sql("UPDATE space_weather_series SET rules_version = ?, state = ?, derived_level = ?, "
                    + "derived_label = ?, value = ?, unit = ?, xray_class = ?, time_tag = ?, interval_start = ?, "
                    + "sample_time = ?, no_data_reason = ?, no_data_since = ?, ended_by_satellite = ?, "
                    + "freshness_reference = ?, state_alert_seq = ?, last_alert_seq = ? "
                    + "WHERE scale = ? AND series_satellite = ?")
                    .params(values).update();
        }
    }

    static boolean isRepeat(DataAccessException e) {
        SQLException sql = sqlCause(e);
        return sql != null && sql.getErrorCode() == DUPLICATE_KEY && String.valueOf(sql.getMessage())
                .contains(EVENT_ID_KEY);
    }

    private static SQLException sqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
