package io.github.mustafanazeer.spaceflux.query.consume;

import java.sql.SQLException;
import java.util.Set;

/** Tells MySQL errors a stored value causes from the database being unreachable or failing. */
public final class SqlErrors {

    public static final int DUPLICATE_KEY = 1062;
    // Data too long, out of range, an incorrect date or time, an incorrect string, a failed check constraint, a null in
    // a NOT NULL column. After the column checks, 1048 and 1292 can only come from a fault in this code; they are dead
    // lettered by design too, so the record is kept and the partition does not stop.
    static final Set<Integer> VALUE_REFUSED = Set.of(1406, 1264, 1292, 1366, 3819, 1048);

    private SqlErrors() {
    }

    /** A {@link NotStorable} for a value the database refused, or null when the error is not about a value. */
    public static NotStorable refusedValue(Throwable e) {
        SQLException sql = cause(e);
        if (sql != null && VALUE_REFUSED.contains(sql.getErrorCode())) {
            return new NotStorable("the database refused a value: error " + sql.getErrorCode() + ", "
                    + sql.getMessage());
        }
        return null;
    }

    /** True when {@code e} is a duplicate entry on the named key. */
    public static boolean isDuplicateOn(Throwable e, String key) {
        SQLException sql = cause(e);
        return sql != null && sql.getErrorCode() == DUPLICATE_KEY && String.valueOf(sql.getMessage()).contains(key);
    }

    public static SQLException cause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
