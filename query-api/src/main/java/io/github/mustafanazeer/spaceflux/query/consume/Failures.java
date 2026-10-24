package io.github.mustafanazeer.spaceflux.query.consume;

import java.sql.SQLException;

/** How a failure, or any text that can echo stored or client data, is written into the log. */
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

    /**
     * Escapes a backslash, every control and format character but tab (C0 and C1 controls, bidi marks, zero width and
     * tag characters), U+2028 and U+2029, and a lone surrogate, so a message can neither shape the log nor read as an
     * escape it is not. A character outside the Basic Multilingual Plane is escaped as its two UTF-16 units.
     */
    public static String oneLine(String message) {
        String text = String.valueOf(message);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int c = text.codePointAt(i);
            int units = Character.charCount(c);
            int type = Character.getType(c);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c != '\t' && (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE)) {
                for (int k = i; k < i + units; k++) {
                    out.append(String.format("\\u%04x", (int) text.charAt(k)));
                }
            } else {
                out.appendCodePoint(c);
            }
            i += units;
        }
        return out.toString();
    }
}
