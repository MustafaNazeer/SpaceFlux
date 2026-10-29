package io.github.mustafanazeer.spaceflux.mcp;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools, one for each read endpoint of query-api (ADR 0015, decision 4; the list is in mcp-server/README.md).
 * Every name, description and schema is a constant here; nothing about a tool comes from data. No tool takes a URL,
 * host, path or free form query: a tool's path is fixed, and its arguments only fill checked query values or a
 * number in the path.
 */
@Configuration(proxyBeanMethods = false)
class ReadTools {

    private static final Logger LOG = LoggerFactory.getLogger(ReadTools.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** query-api's own bounds (docs/api/rest.md). */
    static final long LIMIT_MAX = 200;
    static final long LIMIT_DEFAULT = 50;
    static final long NUMBER_MAX = 999_999_999L;
    static final int EVENT_ID_MAX = 512;
    static final Duration HISTORY_MAX = Duration.ofDays(7);
    /** query-api's cursors are base64url of a short key, far below this. */
    static final int CURSOR_MAX = 256;
    static final int TIME_MAX = 35;

    static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]+");
    static final Pattern NO_CONTROL = Pattern.compile("\\P{Cc}+");
    static final Pattern TIME = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})");

    /** The one GET a call makes: a fixed path and its query values. */
    record Read(String path, Map<String, String> query) {
    }

    interface Route {
        Read read(Arguments a);
    }

    record Definition(String name, String title, String description, Map<String, Object> inputSchema, Route route) {

        Set<String> arguments() {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
            return properties.keySet();
        }
    }

    static final List<Definition> TOOLS = List.of(
            new Definition("space_weather_current", "Current space weather",
                    "The current NOAA scale state for geomagnetic storms (G), radio blackouts (R) and solar "
                            + "radiation storms (S), as SpaceFlux derived it from SWPC data, with the time each "
                            + "state was last fresh. A state older than its age limit reads no_data.",
                    object(Map.of(), List.of()),
                    a -> new Read("/api/space-weather/current", Map.of())),
            new Definition("space_weather_history", "Space weather history",
                    "The state of each Kp interval (scale G) or GOES sample (scales R and S) in a time range of at "
                            + "most 7 days, oldest first, paged.",
                    object(props(
                            "scale", enumOf("G for geomagnetic storms, R for radio blackouts, S for solar "
                                    + "radiation storms.", "G", "R", "S"),
                            "satellite", integer("The GOES satellite number. Required for R and S; not taken for G.",
                                    1, Integer.MAX_VALUE),
                            "from", text("Start of the range, inclusive, an RFC 3339 time such as "
                                    + "2026-10-29T00:00:00Z.", TIME_MAX, TIME),
                            "to", text("End of the range, exclusive, after from and at most 7 days after it.",
                                    TIME_MAX, TIME),
                            "limit", integer("Items per page, from 1 to 200; 50 when left out.", 1, LIMIT_MAX),
                            "after", text("The next value of the previous page, to read the page after it.",
                                    CURSOR_MAX, CURSOR)),
                            List.of("scale", "from", "to")),
                    ReadTools::history),
            new Definition("screening_current", "Current screening run",
                    "The newest complete conjunction screening run: its summary and every close approach it found "
                            + "between a watchlist object and the rest of the catalog, each with its latest "
                            + "acknowledgement action. Computed from public element sets; not a collision "
                            + "avoidance product.",
                    object(Map.of(), List.of()),
                    a -> new Read("/api/screening/current", Map.of())),
            new Definition("recent_alerts", "Recent alerts",
                    "Close approaches and space weather level changes, newest first, paged; kind keeps one kind.",
                    object(props(
                            "limit", integer("Alerts per page, from 1 to 200; 50 when left out.", 1, LIMIT_MAX),
                            "after", text("The next value of the previous page, to read the page after it. A "
                                    + "cursor pages only the kind it was returned for.", CURSOR_MAX, CURSOR),
                            "kind", enumOf("Keep only this kind of alert.", "close_approach",
                                    "space_weather_level")),
                            List.of()),
                    ReadTools::recentAlerts),
            new Definition("alert", "One alert",
                    "One stored alert exactly as received, with the time SpaceFlux received it and its latest "
                            + "acknowledgement action.",
                    object(props("event_id", text("The alert's event_id, as the alert lists return it.",
                            EVENT_ID_MAX, null)), List.of("event_id")),
                    a -> new Read("/api/alerts/by-id", Map.of("event_id",
                            a.text("event_id", EVENT_ID_MAX, NO_CONTROL, true)))),
            new Definition("catalog_object", "One catalog object",
                    "The newest element set SpaceFlux holds for one object in the public CelesTrak catalog, with "
                            + "its name and when it was fetched.",
                    object(props("norad_cat_id", integer("The object's NORAD catalog number.", 0, NUMBER_MAX)),
                            List.of("norad_cat_id")),
                    a -> new Read("/api/catalog/" + a.integer("norad_cat_id", 0, NUMBER_MAX, null), Map.of())),
            new Definition("watchlist", "The watchlist",
                    "Every object SpaceFlux screens for close approaches, with its catalog row when it has one.",
                    object(Map.of(), List.of()),
                    a -> new Read("/api/watchlist", Map.of())),
            new Definition("watchlist_passes", "Passes of a watchlist object",
                    "When a watchlist object is above 10 degrees of elevation over the next 24 hours, seen from a "
                            + "fixed point at NASA Johnson Space Center. Geometric passes only: not whether it can be "
                            + "seen, and not an operational prediction.",
                    object(props("catalog_number", integer("The watchlist object's NORAD catalog number.", 0,
                            NUMBER_MAX)), List.of("catalog_number")),
                    a -> new Read("/api/watchlist/" + a.integer("catalog_number", 0, NUMBER_MAX, null) + "/passes",
                            Map.of())));

    @Bean
    List<SyncToolSpecification> readToolSpecifications(QueryApiClient client, CallLimiter limiter) {
        return TOOLS.stream().map(d -> SyncToolSpecification.builder().tool(tool(d))
                .callHandler((context, request) -> call(d, request.arguments(), client, limiter)).build()).toList();
    }

    static Tool tool(Definition d) {
        return Tool.builder(d.name(), d.inputSchema()).title(d.title()).description(d.description())
                .annotations(ToolAnnotations.builder().title(d.title()).readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .build();
    }

    static CallToolResult call(Definition d, Map<String, Object> arguments, QueryApiClient client,
            CallLimiter limiter) {
        Instant until = limiter.refusedUntil(d.name());
        if (until != null) {
            return error("This tool takes at most " + limiter.perMinute() + " calls a minute; try again at "
                    + until + ".");
        }
        Read read;
        try {
            read = d.route().read(new Arguments(arguments, d.arguments()));
        } catch (Arguments.Refused e) {
            return error("Refused before calling query-api: " + e.getMessage());
        }
        QueryApiClient.Answer answer;
        try {
            answer = client.get(read.path(), read.query());
        } catch (IOException e) {
            LOG.warn("query-api did not answer for tool {}: {}", d.name(), e.toString());
            return error("query-api did not answer.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("query-api did not answer.");
        }
        if (answer.status() == 200) {
            return CallToolResult.builder().addTextContent(answer.body()).isError(false).build();
        }
        if (answer.status() >= 400 && answer.status() < 500) {
            return error("query-api refused the call (" + answer.status() + "): " + detail(answer.body()));
        }
        LOG.warn("query-api answered {} for tool {}", answer.status(), d.name());
        return error("query-api failed (" + answer.status() + ").");
    }

    static Read history(Arguments a) {
        String scale = a.oneOf("scale", Set.of("G", "R", "S"), true);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("scale", scale);
        if ("G".equals(scale)) {
            if (a.has("satellite")) {
                throw new Arguments.Refused("satellite is not taken for G.");
            }
        } else {
            query.put("satellite", Long.toString(a.integer("satellite", 1, Integer.MAX_VALUE, null)));
        }
        Instant from = time(a, "from");
        Instant to = time(a, "to");
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(HISTORY_MAX) > 0) {
            throw new Arguments.Refused("to is after from, and at most 7 days after it.");
        }
        query.put("from", from.toString());
        query.put("to", to.toString());
        query.put("limit", Long.toString(a.integer("limit", 1, LIMIT_MAX, LIMIT_DEFAULT)));
        String after = a.text("after", CURSOR_MAX, CURSOR, false);
        if (after != null) {
            query.put("after", after);
        }
        return new Read("/api/space-weather/history", query);
    }

    static Read recentAlerts(Arguments a) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("limit", Long.toString(a.integer("limit", 1, LIMIT_MAX, LIMIT_DEFAULT)));
        String after = a.text("after", CURSOR_MAX, CURSOR, false);
        if (after != null) {
            query.put("after", after);
        }
        String kind = a.oneOf("kind", Set.of("close_approach", "space_weather_level"), false);
        if (kind != null) {
            query.put("kind", kind);
        }
        return new Read("/api/alerts/recent", query);
    }

    /** An RFC 3339 time with a UTC year from 1000 to 9999, the years query-api takes. */
    static Instant time(Arguments a, String name) {
        String text = a.text(name, TIME_MAX, TIME, true);
        try {
            Instant t = OffsetDateTime.parse(text).toInstant();
            int year = t.atOffset(ZoneOffset.UTC).getYear();
            if (year >= 1000 && year <= 9999) {
                return t;
            }
        } catch (DateTimeException e) {
            // Refused below.
        }
        throw new Arguments.Refused(name + " is an RFC 3339 time with a year from 1000 to 9999.");
    }

    /** The detail of query-api's problem body, which query-api writes and never fills from data. */
    static String detail(String body) {
        try {
            JsonNode d = JSON.readTree(body).get("detail");
            if (d != null && d.isString() && d.asString().length() <= 300) {
                return d.asString();
            }
        } catch (RuntimeException e) {
            // No readable detail.
        }
        return "no detail.";
    }

    static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> props(Object... nameThenSchema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < nameThenSchema.length; i += 2) {
            out.put((String) nameThenSchema[i], nameThenSchema[i + 1]);
        }
        return out;
    }

    static Map<String, Object> integer(String description, long min, long max) {
        return Map.of("type", "integer", "description", description, "minimum", min, "maximum", max);
    }

    /** A pattern goes into the schema only when it is plain ASCII classes, read alike by every regex dialect. */
    static Map<String, Object> text(String description, int maxLength, Pattern pattern) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        schema.put("minLength", 1);
        schema.put("maxLength", maxLength);
        if (pattern != null) {
            schema.put("pattern", "^(?:" + pattern.pattern() + ")$");
        }
        return schema;
    }

    static Map<String, Object> enumOf(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }
}
