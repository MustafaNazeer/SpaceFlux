package io.github.mustafanazeer.spaceflux.query.alerts;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/** Converts contract values to what MySQL columns hold, refusing what a column would change or reject. */
final class UtcColumns {

    private static final Pattern UTC =
            Pattern.compile("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?Z$");
    static final BigDecimal UNSIGNED_INT_MAX = new BigDecimal("4294967295");
    private static final int MIN_DATETIME_YEAR = 1000;

    private UtcColumns() {
    }

    /**
     * A {@code DATETIME(6)} value: truncated to microseconds before binding, so MySQL never rounds, and a leap second
     * read as second 59 of the same minute, keeping its fraction (docs/data/mysql-schema.md, Times).
     */
    static LocalDateTime datetime(String field, String text) {
        Matcher m = text == null ? null : UTC.matcher(text);
        if (m == null || !m.matches()) {
            throw new NotStorable(field + " is not a UTC time");
        }
        int year = Integer.parseInt(m.group(1));
        if (year < MIN_DATETIME_YEAR) {
            throw new NotStorable(field + " " + text + " is before 1000-01-01, the earliest DATETIME");
        }
        int second = Math.min(Integer.parseInt(m.group(6)), 59);
        String fraction = m.group(7) == null ? "" : m.group(7);
        int micros = Integer.parseInt((fraction + "000000").substring(0, 6));
        try {
            return LocalDateTime.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), second, micros * 1_000);
        } catch (DateTimeException e) {
            throw new NotStorable(field + " " + text + " is not a real UTC time");
        }
    }

    /** An {@code INT UNSIGNED} value, refused when it is not a whole number or is above the column's maximum. */
    static long unsignedInt(String field, JsonNode node) {
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 0) {
            throw new NotStorable(field + " " + node + " is not a whole number at or above 0");
        }
        if (value.compareTo(UNSIGNED_INT_MAX) > 0) {
            throw new NotStorable(field + " " + node + " is above 4294967295, the largest its column holds");
        }
        return value.longValueExact();
    }

    /**
     * A {@code VARCHAR(max)} value, refused when it has more characters than the column holds or is not well formed
     * Unicode. Connector/J sends an unpaired surrogate as '?', which would store another string than the one received
     * and could make two identities collide.
     */
    static String varchar(String field, String text, int max) {
        requireWellFormed(field, text);
        int characters = text.codePointCount(0, text.length());
        if (characters > max) {
            throw new NotStorable(field + " is " + characters + " characters, longer than the " + max
                    + " its column holds");
        }
        return text;
    }

    static void requireWellFormed(String field, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new NotStorable(field + " is not well formed Unicode: it holds an unpaired surrogate");
            }
        }
    }
}
