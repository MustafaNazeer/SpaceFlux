package io.github.mustafanazeer.spaceflux.query.consume;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/** Converts contract values to what MySQL columns hold, refusing what a column would change or reject. */
public final class UtcColumns {

    private static final Pattern UTC =
            Pattern.compile("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?Z$");
    public static final BigDecimal UNSIGNED_INT_MAX = new BigDecimal("4294967295");
    private static final Pattern CALENDAR =
            Pattern.compile("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?$");
    static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    static final int TEXT_MAX_BYTES = 65_535;
    private static final int MIN_DATETIME_YEAR = 1000;

    private UtcColumns() {
    }

    /**
     * A {@code DATETIME(6)} value: truncated to microseconds before binding, so MySQL never rounds, and a leap second
     * read as second 59 of the same minute, keeping its fraction (docs/data/mysql-schema.md, Times).
     */
    public static LocalDateTime datetime(String field, String text) {
        return parse(field, text, UTC);
    }

    /**
     * A CCSDS calendar time with no zone designator, such as a CelesTrak {@code EPOCH}, read as UTC under the same
     * rules as {@link #datetime(String, String)}.
     */
    public static LocalDateTime calendarDatetime(String field, String text) {
        return parse(field, text, CALENDAR);
    }

    private static LocalDateTime parse(String field, String text, Pattern pattern) {
        Matcher m = text == null ? null : pattern.matcher(text);
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
    public static long unsignedInt(String field, JsonNode node) {
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
     * A {@code BIGINT UNSIGNED} value, refused when it is not a whole number at or above 0, or above the largest Java
     * long, which is as far as this code binds it.
     */
    public static long unsignedBigint(String field, JsonNode node) {
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 0) {
            throw new NotStorable(field + " " + node + " is not a whole number at or above 0");
        }
        if (value.compareTo(LONG_MAX) > 0) {
            throw new NotStorable(field + " " + node + " is above 9223372036854775807, the largest this code stores");
        }
        return value.longValueExact();
    }

    /**
     * A {@code VARCHAR(max)} value, refused when it has more characters than the column holds or is not well formed
     * Unicode. Connector/J sends an unpaired surrogate as '?', which would store another string than the one received
     * and could make two identities collide.
     */
    public static String varchar(String field, String text, int max) {
        requireWellFormed(field, text);
        int characters = text.codePointCount(0, text.length());
        if (characters > max) {
            throw new NotStorable(field + " is " + characters + " characters, longer than the " + max
                    + " its column holds");
        }
        return text;
    }

    /** A nullable {@code DATETIME(6)} value: null when the field is absent. */
    public static LocalDateTime optionalDatetime(String field, JsonNode parent) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : datetime(field, node.asString());
    }

    /** A nullable signed {@code INT} value, refused when it is not a whole number or is outside the column's range. */
    public static Integer optionalInt(String field, JsonNode parent) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.stripTrailingZeros().scale() > 0) {
            throw new NotStorable(field + " " + node + " is not a whole number");
        }
        if (value.compareTo(INT_MIN) < 0 || value.compareTo(INT_MAX) > 0) {
            throw new NotStorable(field + " " + node + " is outside the range its INT column holds");
        }
        return value.intValueExact();
    }

    /**
     * A nullable {@code DOUBLE} value, refused unless the double reads back as exactly the number written: one beyond
     * a double's range, one below its smallest that would read as 0, and one with more digits than a double keeps
     * would all be stored changed. The event must be parsed with decimals kept, or the digits are already lost.
     */
    public static Double optionalDouble(String field, JsonNode parent) {
        return optionalDouble(field, parent, field);
    }

    /** As {@link #optionalDouble(String, JsonNode)}, for a field the schema requires. */
    public static double requiredDouble(String field, JsonNode parent, String key) {
        return optionalDouble(field, parent, key);
    }

    /** {@code field} names the value in a refusal, {@code key} is its name in {@code parent}. */
    public static Double optionalDouble(String field, JsonNode parent, String key) {
        JsonNode node = parent.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        BigDecimal written = node.decimalValue();
        double value = written.doubleValue();
        if (!Double.isFinite(value) || new BigDecimal(Double.toString(value)).compareTo(written) != 0) {
            throw new NotStorable(field + " " + node + " cannot be stored exactly as a DOUBLE");
        }
        return value;
    }

    /** A nullable {@code VARCHAR(max)} value. */
    public static String optionalVarchar(String field, JsonNode parent, int max) {
        return optionalVarchar(field, parent, field, max);
    }

    /** {@code label} names the field in a refusal, {@code key} is its name in {@code parent}. */
    public static String optionalVarchar(String label, JsonNode parent, String key, int max) {
        JsonNode node = parent.get(key);
        return node == null || node.isNull() ? null : varchar(label, node.asString(), max);
    }

    /** A {@code TEXT} value, whose limit is 65,535 bytes of UTF-8, not characters. */
    public static String text(String field, String text) {
        requireWellFormed(field, text);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > TEXT_MAX_BYTES) {
            throw new NotStorable(field + " is " + bytes + " bytes, longer than the 65535 its TEXT column holds");
        }
        return text;
    }

    /** A nullable {@code TEXT} value. */
    public static String optionalText(String field, JsonNode parent) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : text(field, node.asString());
    }

    /**
     * Refuses a time that orders a series or picks the current run when it is later than {@code limit}: a far future
     * time would keep its series or run the newest forever, so a stale or false result would read as current.
     */
    public static void requireNotLaterThan(String field, LocalDateTime time, LocalDateTime limit,
            LocalDateTime readAt) {
        if (time != null && time.isAfter(limit)) {
            throw new RuleRejected(field + " " + time.atOffset(ZoneOffset.UTC).toInstant()
                    + " is more than 1 hour after " + readAt.atOffset(ZoneOffset.UTC).toInstant()
                    + ", when it was read");
        }
    }

    public static void requireWellFormed(String field, String text) {
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
