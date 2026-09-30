package io.github.mustafanazeer.spaceflux.risk.screening;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The objects screened against the catalog, from the committed file screening/watchlist.json. A missing, empty, or
 * malformed file fails startup, so the service never screens an empty watchlist by accident.
 */
public record Watchlist(Set<Integer> catalogNumbers) {

    private static final String RESOURCE = "/screening/watchlist.json";

    public Watchlist {
        catalogNumbers = Set.copyOf(catalogNumbers);
    }

    public static Watchlist load() {
        return load(RESOURCE);
    }

    static Watchlist load(String resource) {
        JsonNode root;
        try (InputStream in = Watchlist.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("watchlist file " + resource + " is missing");
            }
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read watchlist file " + resource, e);
        }
        JsonNode objects = root.get("objects");
        if (objects == null || !objects.isArray() || objects.isEmpty()) {
            throw new IllegalStateException("watchlist file " + resource + " lists no objects");
        }
        Set<Integer> numbers = new LinkedHashSet<>();
        for (JsonNode o : objects) {
            JsonNode n = o.get("catalog_number");
            if (n == null || !n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < 1) {
                throw new IllegalStateException("watchlist file " + resource + " has an entry without a catalog"
                        + " number of 1 or more: " + o);
            }
            numbers.add(n.asInt());
        }
        return new Watchlist(numbers);
    }
}
