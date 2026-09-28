package io.github.mustafanazeer.spaceflux.risk.screening;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The hand maintained static exclusion list: every pair within a named stack is never screened
 * (docs/risk/orbital-conventions.md 3.7).
 */
public final class StationStacks {

    private static final String RESOURCE = "/screening/stacks.json";

    private final Map<Integer, String> stackOf;

    private StationStacks(Map<Integer, String> stackOf) {
        this.stackOf = Map.copyOf(stackOf);
    }

    public static StationStacks none() {
        return new StationStacks(Map.of());
    }

    public static StationStacks load() {
        try (InputStream in = StationStacks.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            Map<Integer, String> stackOf = new HashMap<>();
            for (JsonNode stack : new ObjectMapper().readTree(in).get("stacks")) {
                String name = stack.get("name").asString();
                for (JsonNode member : stack.get("members")) {
                    String previous = stackOf.put(member.get("norad").asInt(), name);
                    if (previous != null) {
                        throw new IllegalStateException(member.get("norad").asInt() + " is in both " + previous + " and " + name);
                    }
                }
            }
            return new StationStacks(stackOf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<String> sharedStack(int a, int b) {
        String stack = stackOf.get(a);
        return stack != null && stack.equals(stackOf.get(b)) ? Optional.of(stack) : Optional.empty();
    }
}
