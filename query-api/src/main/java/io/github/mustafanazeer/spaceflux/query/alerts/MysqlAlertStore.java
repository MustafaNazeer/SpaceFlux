package io.github.mustafanazeer.spaceflux.query.alerts;

import java.sql.SQLException;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores alerts events through the consumer pool, one transaction per event. A repeat is recognized by error 1062 on
 * the {@code event_id} key, never by {@code INSERT IGNORE}, which would also hide a value the column cannot hold
 * (docs/data/mysql-schema.md, Idempotent consumption).
 */
@Component
class MysqlAlertStore implements AlertStore {

    static final int DUPLICATE_KEY = 1062;
    static final String EVENT_ID_KEY = "uk_alert_event_event_id";
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
    public boolean store(AlertRow row, String payload, int partition, long offset) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                try {
                    insertEvent(row, payload, partition, offset);
                    return true;
                } catch (DataAccessException e) {
                    if (isRepeat(e)) {
                        status.setRollbackOnly();
                        return false;
                    }
                    throw e;
                }
            }));
        } catch (DataAccessException e) {
            SQLException sql = sqlCause(e);
            if (sql != null && VALUE_REFUSED.contains(sql.getErrorCode())) {
                throw new NotStorable("the database refused a value: error " + sql.getErrorCode() + ", "
                        + sql.getMessage());
            }
            throw e;
        }
    }

    private void insertEvent(AlertRow row, String payload, int partition, long offset) {
        jdbc.sql("INSERT INTO alert_event (event_id, kind, schema_version, rules_version, produced_at, "
                + "source_partition, source_offset, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(row.eventId(), row.kind(), row.schemaVersion(), row.rulesVersion(), row.producedAt(),
                        partition, offset, payload)
                .update();
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
