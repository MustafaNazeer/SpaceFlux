package io.github.mustafanazeer.spaceflux.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A tool call's arguments, checked against query-api's own bounds before any call is made (ADR 0015, decision 6).
 * The SDK has already checked them against the tool's input schema; this repeats the checks in code, so a bound
 * holds even if the schema check is turned off, and adds the rules a schema cannot state.
 */
final class Arguments {

    /** A call refused here, with a detail written by the tool server, never echoing the value it refused. */
    static final class Refused extends RuntimeException {

        Refused(String detail) {
            super(detail, null, false, false);
        }
    }

    private final Map<String, Object> values;

    Arguments(Map<String, Object> values, Set<String> names) {
        this.values = values == null ? Map.of() : values;
        for (String name : this.values.keySet()) {
            if (!names.contains(name)) {
                throw new Refused("This tool takes no argument named " + printable(name) + ".");
            }
        }
    }

    boolean has(String name) {
        return values.get(name) != null;
    }

    /** A whole number from {@code min} to {@code max}, or {@code fallback} when absent. */
    long integer(String name, long min, long max, Long fallback) {
        Object v = values.get(name);
        if (v == null) {
            if (fallback == null) {
                throw new Refused(name + " is required.");
            }
            return fallback;
        }
        BigInteger n;
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            n = BigInteger.valueOf(((Number) v).longValue());
        } else if (v instanceof BigInteger b) {
            n = b;
        } else if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
            BigDecimal d = v instanceof BigDecimal b ? b : BigDecimal.valueOf(((Number) v).doubleValue());
            try {
                n = d.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new Refused(name + " is a whole number.");
            }
        } else {
            throw new Refused(name + " is a whole number.");
        }
        if (n.compareTo(BigInteger.valueOf(min)) < 0 || n.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new Refused(name + " is from " + min + " to " + max + ".");
        }
        return n.longValue();
    }

    /** A string of at most {@code max} code points that matches {@code pattern}, or null when absent and optional. */
    String text(String name, int max, Pattern pattern, boolean required) {
        Object v = values.get(name);
        if (v == null) {
            if (required) {
                throw new Refused(name + " is required.");
            }
            return null;
        }
        if (!(v instanceof String s) || s.isEmpty() || s.codePointCount(0, s.length()) > max
                || !pattern.matcher(s).matches()) {
            throw new Refused(name + " is not in the form this tool takes.");
        }
        return s;
    }

    /** One of {@code allowed}, or null when absent and optional. */
    String oneOf(String name, Set<String> allowed, boolean required) {
        Object v = values.get(name);
        if (v == null) {
            if (required) {
                throw new Refused(name + " is required.");
            }
            return null;
        }
        if (!(v instanceof String s) || !allowed.contains(s)) {
            throw new Refused(name + " is one of " + String.join(", ", allowed.stream().sorted().toList()) + ".");
        }
        return s;
    }

    /** A name a caller sent, repeated only when it is short and plain. */
    private static String printable(String name) {
        return name.matches("[a-z_]{1,32}") ? name : "that";
    }
}
