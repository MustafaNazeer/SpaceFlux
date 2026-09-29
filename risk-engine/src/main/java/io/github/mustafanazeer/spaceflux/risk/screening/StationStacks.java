package io.github.mustafanazeer.spaceflux.risk.screening;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The hand maintained static exclusion list: every pair within a named stack is never screened
 * (docs/risk/orbital-conventions.md 3.7).
 */
public final class StationStacks {

    private static final String RESOURCE = "/screening/stacks.json";

    private final Map<Integer, String> stackOf;
    private final Map<Integer, String> addedOn;

    private StationStacks(Map<Integer, String> stackOf, Map<Integer, String> addedOn) {
        this.stackOf = Map.copyOf(stackOf);
        this.addedOn = Map.copyOf(addedOn);
    }

    public static StationStacks none() {
        return new StationStacks(Map.of(), Map.of());
    }

    public static StationStacks load() {
        return load(RESOURCE);
    }

    static StationStacks load(String resource) {
        try (InputStream in = StationStacks.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            Map<Integer, String> stackOf = new HashMap<>();
            Map<Integer, String> addedOn = new HashMap<>();
            Set<String> names = new HashSet<>();
            for (JsonNode stack : required(new ObjectMapper().readTree(in), "stacks", resource)) {
                String name = required(stack, "name", resource).asString();
                if (!names.add(name)) {
                    throw new IllegalStateException(name + " is listed twice in " + resource);
                }
                for (JsonNode member : required(stack, "members", resource + " stack " + name)) {
                    String where = resource + " stack " + name;
                    int norad = wholeNumber(required(member, "norad", where), where);
                    String previous = stackOf.put(norad, name);
                    if (name.equals(previous)) {
                        throw new IllegalStateException(norad + " is listed twice in " + name + " in " + resource);
                    }
                    if (previous != null) {
                        throw new IllegalStateException(norad + " is in both " + previous + " and " + name + " in " + resource);
                    }
                    addedOn.put(norad, isoDate(required(member, "added", where + " member " + norad), where + " member " + norad));
                }
            }
            return new StationStacks(stackOf, addedOn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode required(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("missing \"" + field + "\" in " + where);
        }
        return value;
    }

    /** Screening compares these dates as text, which orders ISO dates correctly and nothing else. */
    private static String isoDate(JsonNode value, String where) {
        try {
            return LocalDate.parse(value.asString()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("\"added\" " + value.asString() + " is not a date (YYYY-MM-DD) in " + where);
        }
    }

    private static int wholeNumber(JsonNode value, String where) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("\"norad\" " + value + " is not a whole number in " + where);
        }
        return value.asInt();
    }

    public Optional<String> sharedStack(int a, int b) {
        String stack = stackOf.get(a);
        return stack != null && stack.equals(stackOf.get(b)) ? Optional.of(stack) : Optional.empty();
    }

    /** The date a member was put on the list. */
    public String added(int member) {
        return addedOn.get(member);
    }
}
