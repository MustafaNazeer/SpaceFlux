package io.github.mustafanazeer.spaceflux.query.consume;

import java.sql.SQLException;

/** How a consumer writes a failure it will retry into its log. */
public final class Failures {

    private Failures() {
    }

    /**
     * The database's own error when there is one (class, error code, SQLState, message), otherwise the innermost
     * cause. A message can echo a stored value, so line breaks and other control characters are escaped and cannot
     * start a line that reads as another log entry or change how a terminal shows it.
     */
    public static String describe(Throwable t) {
        Throwable root = t;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SQLException sql) {
                return "SQLException " + sql.getErrorCode() + " " + sql.getSQLState() + ": "
                        + oneLine(sql.getMessage());
            }
            root = c;
        }
        return root.getClass().getSimpleName() + ": " + oneLine(root.getMessage());
    }

    /** Escapes every control character but tab, and U+2028 and U+2029, so a message cannot shape the log. */
    private static String oneLine(String message) {
        String text = String.valueOf(message);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                out.append("\\r");
            } else if (c == '\n') {
                out.append("\\n");
            } else if ((c < 0x20 && c != '\t') || c == 0x7f || c == 0x2028 || c == 0x2029) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
