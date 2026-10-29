package io.github.mustafanazeer.spaceflux.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/** The tool server over HTTP, called with the MCP Java SDK's own client, in front of a stub query-api. */
class ToolServerIntegrationTest {

    static final String TOKEN = "t".repeat(20) + "0123456789abcdefghij";
    static final MutableClock CLOCK = new MutableClock();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    static StubQueryApi queryApi;
    static ConfigurableApplicationContext app;
    static String base;
    static McpSyncClient client;

    @Configuration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @BeforeAll
    static void start() throws Exception {
        queryApi = new StubQueryApi();
        Path token = Files.createTempFile("mcp-token", "");
        Files.writeString(token, TOKEN + "\n");
        app = new SpringApplicationBuilder(McpServerApplication.class, TestClock.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spaceflux.mcp.query-api-url=" + queryApi.base,
                        "--spaceflux.mcp.token-file=" + token);
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        client = client(TOKEN);
        client.initialize();
    }

    @AfterAll
    static void stop() {
        client.close();
        app.close();
        queryApi.close();
    }

    @BeforeEach
    void freshMinute() {
        // Each test starts in a minute no other test has used, so none spends another's calls.
        CLOCK.advance(Duration.ofMinutes(2));
        queryApi.seen.clear();
    }

    static McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint("/mcp").clientBuilder(HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token)).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
    }

    static CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    static String text(CallToolResult r) {
        return ((TextContent) r.content().get(0)).text();
    }

    static Map<String, Object> args(Object... nameThenValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < nameThenValue.length; i += 2) {
            out.put((String) nameThenValue[i], nameThenValue[i + 1]);
        }
        return out;
    }

    static HttpResponse<String> post(String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    @Test
    void theToolsAreTheEightReadToolsEachMarkedReadOnly() {
        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("space_weather_current",
                "space_weather_history", "screening_current", "recent_alerts", "alert", "catalog_object",
                "watchlist", "watchlist_passes");
        for (McpSchema.Tool t : tools) {
            assertThat(t.annotations().readOnlyHint()).as(t.name()).isTrue();
            assertThat(t.annotations().destructiveHint()).as(t.name()).isFalse();
            assertThat(t.annotations().openWorldHint()).as(t.name()).isFalse();
            assertThat(t.inputSchema().get("additionalProperties")).as(t.name()).isEqualTo(false);
            assertThat(t.name()).doesNotContain("ack").doesNotContain("session").doesNotContain("login");
        }
        assertThat(client.getServerCapabilities().resources()).isNull();
        assertThat(client.getServerCapabilities().prompts()).isNull();
    }

    @Test
    void theToolListIsTheOneInTheSourceOnEveryCall() {
        List<McpSchema.Tool> first = client.listTools().tools();
        List<McpSchema.Tool> again = client(TOKEN).listTools().tools();

        assertThat(again).isEqualTo(first);
        // Compared as JSON text read back, since the client reads the schemas' numbers as Integer, not Long.
        assertThat(JSON.readTree(JSON.writeValueAsString(first))).isEqualTo(JSON.readTree(JSON.writeValueAsString(
                ReadTools.TOOLS.stream().map(ReadTools::tool).toList())));
    }

    @Test
    void eachToolMakesExactlyItsOneGetWithNoCredential() {
        Map<String, Object[]> calls = new LinkedHashMap<>();
        calls.put("/api/space-weather/current", new Object[] {"space_weather_current", args()});
        calls.put("/api/space-weather/history?scale=R&satellite=18&from=2026-10-01T00%3A00%3A00Z"
                + "&to=2026-10-02T00%3A00%3A00Z&limit=50", new Object[] {"space_weather_history",
                    args("scale", "R", "satellite", 18, "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z")});
        calls.put("/api/space-weather/history?scale=G&from=2026-09-30T22%3A00%3A00Z&to=2026-10-07T22%3A00%3A00Z"
                + "&limit=200&after=MjAyNi0xMC0wMVQwMDowMDowMA", new Object[] {"space_weather_history",
                    args("scale", "G", "from", "2026-10-01T00:00:00+02:00", "to", "2026-10-08T00:00:00+02:00",
                            "limit", 200, "after", "MjAyNi0xMC0wMVQwMDowMDowMA")});
        calls.put("/api/screening/current", new Object[] {"screening_current", args()});
        calls.put("/api/alerts/recent?limit=50", new Object[] {"recent_alerts", args()});
        calls.put("/api/alerts/recent?limit=5&after=Y2xvc2VfYXBwcm9hY2h8MTI&kind=close_approach",
                new Object[] {"recent_alerts", args("limit", 5, "after", "Y2xvc2VfYXBwcm9hY2h8MTI", "kind",
                        "close_approach")});
        calls.put("/api/alerts/by-id?event_id=close_approach%2F1%2Fr%2F1+2", new Object[] {"alert",
            args("event_id", "close_approach/1/r/1 2")});
        calls.put("/api/catalog/25544", new Object[] {"catalog_object", args("norad_cat_id", 25544)});
        calls.put("/api/watchlist", new Object[] {"watchlist", args()});
        calls.put("/api/watchlist/25544/passes", new Object[] {"watchlist_passes", args("catalog_number", 25544)});

        for (Map.Entry<String, Object[]> c : calls.entrySet()) {
            queryApi.seen.clear();
            @SuppressWarnings("unchecked")
            CallToolResult r = call((String) c.getValue()[0], (Map<String, Object>) c.getValue()[1]);

            assertThat(r.isError()).as(c.getKey() + ": " + text(r)).isFalse();
            assertThat(text(r)).isEqualTo(StubQueryApi.body(c.getKey()));
            assertThat(queryApi.seen).hasSize(1);
            StubQueryApi.Seen seen = queryApi.seen.get(0);
            assertThat(seen.method()).isEqualTo("GET");
            assertThat(seen.pathAndQuery()).isEqualTo(c.getKey());
            // The caller's token never travels on, and the tool server holds no credential of its own.
            assertThat(seen.headers().keySet()).map(String::toLowerCase).doesNotContain("authorization", "cookie",
                    "x-xsrf-token");
        }
    }

    @Test
    void theClientToQueryApiHasNoWayToSendAnythingButAGet() {
        List<String> methods = new ArrayList<>();
        for (var m : QueryApiClient.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPrivate(m.getModifiers()) && !m.isSynthetic()) {
                methods.add(m.getName());
            }
        }

        assertThat(methods).containsExactly("get");
    }

    @Test
    void aValueOutOfBoundsIsRefusedBeforeAnyCall() {
        Object[][] cases = {
            {"recent_alerts", args("limit", 0)},
            {"recent_alerts", args("limit", 201)},
            {"recent_alerts", args("limit", 2.5)},
            {"recent_alerts", args("limit", "10")},
            {"recent_alerts", args("kind", "screening_run")},
            {"recent_alerts", args("after", "a/b")},
            {"recent_alerts", args("after", "a".repeat(257))},
            {"recent_alerts", args("url", "http://example.com")},
            {"alert", args()},
            {"alert", args("event_id", "x".repeat(513))},
            {"alert", args("event_id", "a\nb")},
            {"catalog_object", args("norad_cat_id", -1)},
            {"catalog_object", args("norad_cat_id", 1_000_000_000L)},
            {"watchlist_passes", args("catalog_number", "25544")},
            {"watchlist", args("path", "/api/alerts/acknowledgements")},
            {"space_weather_history", args("scale", "X", "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z")},
            {"space_weather_history", args("scale", "G", "satellite", 18, "from", "2026-10-01T00:00:00Z", "to",
                "2026-10-02T00:00:00Z")},
            {"space_weather_history", args("scale", "R", "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z")},
            {"space_weather_history", args("scale", "R", "satellite", 0, "from", "2026-10-01T00:00:00Z", "to",
                "2026-10-02T00:00:00Z")},
            {"space_weather_history", args("scale", "G", "from", "2026-10-02T00:00:00Z", "to", "2026-10-01T00:00:00Z")},
            {"space_weather_history", args("scale", "G", "from", "2026-10-01T00:00:00Z", "to", "2026-10-08T00:00:01Z")},
            {"space_weather_history", args("scale", "G", "from", "0999-12-31T00:00:00Z", "to", "1000-01-01T00:00:00Z")},
            {"space_weather_history", args("scale", "G", "from", "2026-02-30T00:00:00Z", "to", "2026-03-01T00:00:00Z")},
            {"space_weather_history", args("scale", "G", "from", "yesterday", "to", "2026-10-01T00:00:00Z")},
        };

        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            CallToolResult r = call((String) c[0], (Map<String, Object>) c[1]);

            assertThat(r.isError()).as(c[0] + " " + c[1]).isTrue();
        }
        assertThat(queryApi.seen).isEmpty();
    }

    @Test
    void theCodeRefusesOutOfBoundsValuesEvenWithoutTheSchemaCheck() {
        CallLimiter limiter = new CallLimiter(CLOCK, 1000);
        QueryApiClient unused = new QueryApiClient(queryApi.base);
        Object[][] cases = {
            {"recent_alerts", args("limit", 201)},
            {"alert", args("event_id", "x".repeat(513))},
            {"alert", args("event_id", "a\u0000b")},
            {"catalog_object", args("norad_cat_id", 1_000_000_000L)},
            {"space_weather_history", args("scale", "G", "from", "2026-10-01T00:00:00Z", "to", "2026-10-08T00:00:01Z")},
            {"watchlist", args("url", "http://example.com")},
        };

        for (Object[] c : cases) {
            ReadTools.Definition d = ReadTools.TOOLS.stream().filter(t -> t.name().equals(c[0])).findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            CallToolResult r = ReadTools.call(d, (Map<String, Object>) c[1], unused, limiter);

            assertThat(r.isError()).as(c[0] + " " + c[1]).isTrue();
            assertThat(text(r)).startsWith("Refused before calling query-api: ").doesNotContain("example.com");
        }
        assertThat(queryApi.seen).isEmpty();
    }

    @Test
    void theThirtyFirstCallOfAToolInAMinuteIsRefusedAndTheNextMinuteServesAgain() {
        for (int i = 0; i < 30; i++) {
            assertThat(call("watchlist", args()).isError()).as("call " + (i + 1)).isFalse();
        }

        CallToolResult refused = call("watchlist", args());
        CallToolResult otherTool = call("screening_current", args());

        assertThat(refused.isError()).isTrue();
        assertThat(text(refused)).startsWith("This tool takes at most 30 calls a minute");
        assertThat(otherTool.isError()).isFalse();
        assertThat(queryApi.seen).hasSize(31);

        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(call("watchlist", args()).isError()).isFalse();
    }

    @Test
    void queryApisAnswersOtherThanOkReachTheCallerAsToolErrors() {
        CallToolResult missing = call("catalog_object", args("norad_cat_id", StubQueryApi.NOT_FOUND));
        CallToolResult failing = call("catalog_object", args("norad_cat_id", StubQueryApi.FAILING));

        assertThat(missing.isError()).isTrue();
        assertThat(text(missing)).isEqualTo("query-api refused the call (404): No catalog object has this number.");
        assertThat(failing.isError()).isTrue();
        assertThat(text(failing)).isEqualTo("query-api failed (500).");
    }

    @Test
    void anUnknownToolIsAnErrorAndCallsNothing() throws Exception {
        HttpResponse<String> r = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"acknowledge_alert\",\"arguments\":{}}}", "Authorization", "Bearer " + TOKEN);

        assertThat(r.body()).contains("\"error\"").contains("Unknown tool");
        assertThat(queryApi.seen).isEmpty();
    }

    @Test
    void everyRequestWithoutTheTokenIsUnauthorized() throws Exception {
        for (String[] auth : new String[][] {{}, {"Authorization", "Bearer " + TOKEN + "x"},
            {"Authorization", "Bearer " + TOKEN.substring(1)}, {"Authorization", TOKEN},
            {"Authorization", "Basic " + TOKEN}}) {
            HttpResponse<String> r = post("/mcp", LIST, auth);

            assertThat(r.statusCode()).as(String.join(" ", auth)).isEqualTo(401);
            assertThat(r.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
            assertThat(r.body()).doesNotContain("space_weather");
        }
        assertThat(post("/no-such-path", "{}").statusCode()).isEqualTo(401);
        assertThat(post("/mcp", LIST, "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(200);
        assertThat(queryApi.seen).isEmpty();
    }

    @Test
    void aRequestFromABrowserIsRefusedEvenWithTheToken() throws Exception {
        HttpResponse<String> r = post("/mcp", LIST, "Authorization", "Bearer " + TOKEN, "Origin",
                "http://localhost:4200");

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).doesNotContain("space_weather");
    }

    @Test
    void nothingIsKeptBetweenRequests() throws Exception {
        HttpResponse<String> get = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header("Authorization", "Bearer " + TOKEN).header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> list = post("/mcp", LIST, "Authorization", "Bearer " + TOKEN);

        // No stream to open, and a call needs no initialize first and gets no session.
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(list.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void theServerDoesNotStartWithoutAUsableToken() throws Exception {
        Path empty = Files.createTempFile("mcp-token", "");
        Path shortToken = Files.createTempFile("mcp-token", "");
        Files.writeString(shortToken, "x".repeat(BearerTokenFilter.MIN_LENGTH - 1));
        Path spaced = Files.createTempFile("mcp-token", "");
        Files.writeString(spaced, "x".repeat(20) + " " + "y".repeat(20));

        for (Path file : new Path[] {empty, shortToken, spaced, empty.resolveSibling("no-such-token-file")}) {
            assertThatThrownBy(() -> new BearerTokenFilter(file)).as(file.toString())
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("xxxx");
        }
    }
}
