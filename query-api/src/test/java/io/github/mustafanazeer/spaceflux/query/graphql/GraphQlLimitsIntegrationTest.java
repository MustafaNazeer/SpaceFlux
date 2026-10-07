package io.github.mustafanazeer.spaceflux.query.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import graphql.introspection.IntrospectionQuery;
import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The limits of ADR 0012 over HTTP: depth, field count, cost, parser limits, and one request per POST. */
class GraphQlLimitsIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static OperatorApp app;

    @BeforeAll
    static void start() {
        // Another class may have started a context with introspection off, which turns it off for the whole JVM.
        graphql.introspection.Introspection.enabledJvmWide(true);
        app = new OperatorApp("--spring.graphql.schema.introspection.enabled=true");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static HttpResponse<String> post(String body) throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        return b.postJson("/api/graphql", body);
    }

    static JsonNode query(String query) throws Exception {
        HttpResponse<String> r = post(JSON.writeValueAsString(Map.of("query", query)));
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("at io.").doesNotContain("at graphql.");
        return JSON.readTree(r.body());
    }

    static String messages(JsonNode body) {
        StringBuilder out = new StringBuilder();
        if (body.has("errors")) {
            body.get("errors").forEach(e -> out.append(e.get("message").asString()).append('\n'));
        }
        return out.toString();
    }

    /** 1 + the number of nested levels: watchlist, catalog, close_approaches, items, close_approach, then a leaf. */
    static final String DEPTH_6 = "{ watchlist { catalog { close_approaches(limit: 1) { items { close_approach { "
            + "miss_distance_m } } } } } }";
    static final String DEPTH_7 = "{ watchlist { catalog { close_approaches(limit: 1) { items { close_approach { "
            + "watchlist_object { name } } } } } } }";

    @Test
    void aQueryOfDepthSixRunsAndDepthSevenIsRefusedBeforeAnyFieldRuns() throws Exception {
        assertThat(messages(query(DEPTH_6))).doesNotContain("depth");

        JsonNode refused = query(DEPTH_7);

        assertThat(messages(refused)).contains("maximum query depth exceeded 7 > 6");
        assertThat(refused.has("data") && !refused.get("data").isNull()).isFalse();
    }

    @Test
    void whileIntrospectionIsOnTheStandardIntrospectionQueryIsNotHeldToTheDepthLimit() throws Exception {
        JsonNode r = query(IntrospectionQuery.INTROSPECTION_QUERY);

        assertThat(messages(r)).isEmpty();
        assertThat(r.get("data").get("__schema").get("queryType").get("name").asString()).isEqualTo("Query");
    }

    @Test
    void anIntrospectionFieldNextToAnOrdinaryOneDoesNotExemptTheQuery() throws Exception {
        JsonNode r = query("{ __schema { queryType { name } } " + DEPTH_7.substring(1));

        assertThat(messages(r)).contains("maximum query depth exceeded");
    }

    /** n aliases of the same small selection; each alias adds 4 fields to the normalized operation. */
    static String aliases(int n, boolean namedFragment) {
        StringBuilder q = new StringBuilder("{ ");
        for (int i = 0; i < n; i++) {
            q.append("a").append(i).append(": space_weather_current { ")
                    .append(namedFragment ? "...f" : "as_of scales { scale }").append(" } ");
        }
        q.append("}");
        if (namedFragment) {
            q.append(" fragment f on SpaceWeatherCurrent { as_of scales { scale } }");
        }
        return q.toString();
    }

    @Test
    void theFieldCountLimitCountsAliasesAndBothKindsOfFragmentTheSame() throws Exception {
        // 50 aliases are 50 x 4 = 200 fields, the limit; 51 are 204.
        assertThat(messages(query(aliases(50, false)))).isEmpty();
        assertThat(messages(query(aliases(50, true)))).isEmpty();

        assertThat(messages(query(aliases(51, false)))).contains("Maximum field count exceeded");
        assertThat(messages(query(aliases(51, true)))).contains("Maximum field count exceeded");
        String inline = aliases(51, false).replace("as_of scales { scale }", "... on SpaceWeatherCurrent { as_of scales "
                + "{ scale } }");
        assertThat(inline).contains("... on SpaceWeatherCurrent");
        assertThat(messages(query(inline))).contains("Maximum field count exceeded");
    }

    @Test
    void aPagedListCostsItsLimitTimesWhatItSelects() throws Exception {
        // 200 x (1 + (1 + 6)) = 1,600, under 2,000.
        String under = "{ alerts(limit: 200) { items { event_id kind received_at produced_at schema_version "
                + "rules_version } } }";
        // 200 x (1 + (1 + 6 + (1 + 4))) = 2,600.
        String over = "{ alerts(limit: 200) { items { event_id kind received_at produced_at schema_version "
                + "rules_version space_weather_level { scale state derived_label value } } } }";

        assertThat(messages(query(under))).doesNotContain("complexity");
        assertThat(messages(query(over))).contains("maximum query complexity exceeded 2600 > 2000");
    }

    @Test
    void aListWithNoLimitArgumentCostsItsDefaultPage() throws Exception {
        // Default 50: 50 x (1 + (1 + 1)) = 150 per alias; 14 aliases are 2,100.
        StringBuilder q = new StringBuilder("{ ");
        for (int i = 0; i < 14; i++) {
            q.append("a").append(i).append(": alerts { items { event_id } } ");
        }

        assertThat(messages(query(q.append("}").toString()))).contains("maximum query complexity exceeded");
    }

    @Test
    void aDocumentOverTheParserLimitsIsRefusedBeforeValidation() throws Exception {
        String padded = "{ watchlist { catalog_number } }" + " ".repeat(16_400);
        StringBuilder tokens = new StringBuilder("{ ");
        for (int i = 0; i < 700; i++) {
            tokens.append("a").append(i).append(": __typename ");
        }

        assertThat(messages(query(padded))).contains("More than 16,384 characters");
        assertThat(messages(query(tokens.append("}").toString()))).contains("More than 2,000 'grammar' tokens");
    }

    @Test
    void aBatchOfRequestsInOneArrayIsRefused() throws Exception {
        HttpResponse<String> r = post("[{\"query\": \"{ watchlist { catalog_number } }\"}, "
                + "{\"query\": \"{ watchlist { catalog_number } }\"}]");

        assertThat(r.statusCode()).isBetween(400, 499);
        assertThat(r.body()).doesNotContain("catalog_number").doesNotContain("Exception");
    }

    @Test
    void theApiPoolStopsAStatementAfterThreeSeconds() throws Exception {
        org.springframework.jdbc.core.simple.JdbcClient api = app.context.getBean("apiJdbcClient",
                org.springframework.jdbc.core.simple.JdbcClient.class);
        long start = System.nanoTime();

        Throwable failure = null;
        try {
            api.sql("SELECT SLEEP(10)").query(Integer.class).single();
        } catch (RuntimeException e) {
            failure = e;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // MySQL answers SLEEP interrupted by a query timeout with 1, not an error, so either outcome is accepted;
        // what matters is that the statement did not run for 10 seconds.
        assertThat(elapsedMs).isBetween(2_500L, 6_000L);
        if (failure != null) {
            assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    static final String HEAVY = "alerts(limit: 200) { items { event_id kind received_at produced_at schema_version "
            + "rules_version space_weather_level { scale state derived_label value } } }";

    @Test
    void aHugeLimitCannotOverflowTheCostOfTheRestOfTheQuery() throws Exception {
        String under = "x: catalog_object(norad_cat_id: 1) { close_approaches(limit: 1073741824) { items { event_id } "
                + "} } a: " + HEAVY;

        assertThat(messages(query("{ " + under + " }"))).contains("maximum query complexity exceeded");
        assertThat(messages(query("{ big: alerts(limit: 1073741824) { next } a: " + HEAVY + " }")))
                .contains("maximum query complexity exceeded");
        // A negative limit must not make a negative cost that cancels the rest.
        assertThat(messages(query("{ x: catalog_object(norad_cat_id: 1) { close_approaches(limit: -1000000) { items "
                + "{ event_id } } } a: " + HEAVY + " }"))).contains("maximum query complexity exceeded");
        HttpResponse<String> r = post(JSON.writeValueAsString(Map.of("query", "query($l: Int) { x: catalog_object("
                + "norad_cat_id: 1) { close_approaches(limit: $l) { items { event_id } } } a: " + HEAVY + " }",
                "variables", Map.of("l", 1073741824))));
        assertThat(messages(JSON.readTree(r.body()))).contains("maximum query complexity exceeded");
    }

    @Test
    void theWatchlistCostsItsSizeBoundSoANestedListUnderItIsMultiplied() throws Exception {
        // 10 x (1 + (1 + 50 x (1 + (1 + 1)))) = 1,520.
        String under = "{ watchlist { catalog { close_approaches(limit: 50) { items { event_id } } } } }";
        // 10 x (1 + (1 + 50 x (1 + (1 + 3)))) = 2,520.
        String over = "{ watchlist { catalog { close_approaches(limit: 50) { items { event_id kind received_at } } } "
                + "} }";

        assertThat(messages(query(under))).doesNotContain("complexity");
        assertThat(messages(query(over))).contains("maximum query complexity exceeded 2520 > 2000");
    }

    @Test
    void theSeededWatchlistStaysWithinTheBoundTheCostAssumes() throws Exception {
        int size = Integer.parseInt(io.github.mustafanazeer.spaceflux.query.TestMysql.rootQuery(
                "SELECT COUNT(*) FROM spaceflux.watchlist_object"));

        assertThat(size).isPositive().isLessThanOrEqualTo(GraphQlLimits.WATCHLIST_MAX);
    }

    @Test
    void aNestedListWithNoLimitCostsTheNestedDefaultPage() throws Exception {
        // Per alias 1 + 20 x (1 + (1 + 3)) = 101; 20 aliases are 2,020.
        StringBuilder q = new StringBuilder("{ ");
        for (int i = 0; i < 20; i++) {
            q.append("a").append(i).append(": catalog_object(norad_cat_id: 1) { close_approaches { items { event_id "
                    + "kind received_at } } } ");
        }

        assertThat(messages(query(q.append("}").toString()))).contains("maximum query complexity exceeded 2020");
    }

    @Test
    void depthIsMeasuredThroughAliasesAndFragments() throws Exception {
        String aliased = "{ w: watchlist { c: catalog { a: close_approaches(limit: 1) { i: items { ca: close_approach "
                + "{ o: watchlist_object { n: name } } } } } } }";
        String named = "{ watchlist { ...w } } fragment w on WatchlistObject { catalog { close_approaches(limit: 1) "
                + "{ items { close_approach { watchlist_object { name } } } } } }";
        String inline = "{ watchlist { ... on WatchlistObject { catalog { close_approaches(limit: 1) { items { "
                + "close_approach { watchlist_object { name } } } } } } } }";

        for (String q : new String[] {aliased, named, inline}) {
            assertThat(messages(query(q))).as(q).contains("maximum query depth exceeded 7 > 6");
        }
    }

    @Test
    void costIsMeasuredThroughInlineAndNamedFragments() throws Exception {
        String inline = "{ alerts(limit: 200) { ... on AlertPage { items { event_id kind received_at produced_at "
                + "schema_version rules_version space_weather_level { scale state derived_label value } } } } }";
        String named = "{ alerts(limit: 200) { ...p } } fragment p on AlertPage { items { event_id kind received_at "
                + "produced_at schema_version rules_version space_weather_level { scale state derived_label value } } }";

        for (String q : new String[] {inline, named}) {
            assertThat(messages(query(q))).as(q).contains("maximum query complexity exceeded 2600 > 2000");
        }
    }

    @Test
    void documentsJustUnderTheParserLimitsAreParsed() throws Exception {
        // One comment is one ignored token, so this reaches the character limit without the whitespace one.
        String padded = "{ watchlist { catalog_number } }\n#";
        padded = padded + "x".repeat(16_384 - padded.length());
        StringBuilder tokens = new StringBuilder("{ ");
        for (int i = 0; i < 660; i++) {
            tokens.append("a").append(i).append(": __typename ");
        }

        assertThat(messages(query(padded))).isEmpty();
        assertThat(messages(query(tokens.append("}").toString()))).doesNotContain("More than");
    }

    @Test
    void tooManyWhitespaceTokensOrTooDeepARuleIsRefused() throws Exception {
        String commas = "{ watchlist { catalog_number " + ",".repeat(10_100) + " } }";
        String deep = "{ " + "a { ".repeat(60) + "b" + " }".repeat(60) + " }";

        assertThat(messages(query(commas))).contains("More than 10,000 'whitespace' tokens");
        assertThat(messages(query(deep))).contains("More than 100 deep 'grammar' rules");
    }

    @Test
    void limitsAreMeasuredOnTheOperationNamedByOperationName() throws Exception {
        String document = "query A { watchlist { catalog_number } } query B " + DEPTH_7.substring(0);

        JsonNode a = JSON.readTree(post(JSON.writeValueAsString(Map.of("query", document, "operationName", "A")))
                .body());
        JsonNode b = JSON.readTree(post(JSON.writeValueAsString(Map.of("query", document, "operationName", "B")))
                .body());
        JsonNode none = JSON.readTree(post(JSON.writeValueAsString(Map.of("query", document))).body());

        assertThat(messages(a)).isEmpty();
        assertThat(messages(b)).contains("maximum query depth exceeded 7 > 6");
        assertThat(messages(none)).isNotEmpty();
    }

    @Test
    void malformedRequestsGetAGenericRefusal() throws Exception {
        for (String body : new String[] {"{not json", "", "{}", "{\"query\": \"{ __typename }\", \"variables\": 5}"}) {
            HttpResponse<String> r = post(body);
            assertThat(r.statusCode()).as(body).isEqualTo(400);
            assertThat(r.body()).as(body).doesNotContain("Exception").doesNotContain("at io.")
                    .doesNotContain("at org.").doesNotContain("at graphql.");
        }
        Browser b = app.browser();
        b.get("/api/auth/session");
        HttpResponse<String> text = b.post("/api/graphql", "text/plain", "{ __typename }");
        assertThat(text.statusCode()).isEqualTo(415);
    }

    static String bigBody(int bytes) throws Exception {
        String head = JSON.writeValueAsString(Map.of("query", "{ __typename }", "variables", Map.of("pad", "")));
        return head.replace("\"pad\":\"\"", "\"pad\":\"" + "x".repeat(bytes - head.length()) + "\"");
    }

    @Test
    void aBodyOverSixtyFourKibibytesIsRefusedWith413() throws Exception {
        String under = bigBody(64 * 1024);
        String over = bigBody(64 * 1024 + 1);
        assertThat(under.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(64 * 1024);

        assertThat(post(under).statusCode()).isEqualTo(200);
        HttpResponse<String> refused = post(over);
        assertThat(refused.statusCode()).isEqualTo(413);
        assertThat(refused.body()).doesNotContain("Exception").doesNotContain("at org.");
    }

    @Test
    void aChunkedBodyOverTheCapIsRefusedWhileItIsRead() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        byte[] over = bigBody(64 * 1024 + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(app.base + "/api/graphql"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + b.cookies.get("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", b.cookies.get("XSRF-TOKEN"))
                // A publisher of unknown length makes the client send the body chunked, with no Content-Length.
                .POST(java.net.http.HttpRequest.BodyPublishers.fromPublisher(
                        java.net.http.HttpRequest.BodyPublishers.ofByteArray(over)))
                .build();

        HttpResponse<String> r = Browser.HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(request.headers().firstValue("Content-Length")).isEmpty();
        assertThat(r.statusCode()).isEqualTo(413);
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedWithoutWaitingForTheBody() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        String token = b.cookies.get("XSRF-TOKEN");
        java.net.URI uri = java.net.URI.create(app.base);
        try (java.net.Socket socket = new java.net.Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(5_000);
            String head = "POST /api/graphql HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nContent-Type: application/json"
                    + "\r\nCookie: XSRF-TOKEN=" + token + "\r\nX-XSRF-TOKEN: " + token
                    + "\r\nContent-Length: 10000000\r\n\r\n{\"query\":";
            socket.getOutputStream().write(head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            // Only the start of the promised body was sent; without the length check the server would wait for
            // the rest and this read would time out.
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();

            assertThat(status).startsWith("HTTP/1.1 413");
        }
    }

    @Test
    void thePathCannotBeSpelledToSlipPastTheBodyCap() throws Exception {
        String over = bigBody(64 * 1024 + 1);
        Browser b = app.browser();
        b.get("/api/auth/session");

        for (String path : new String[] {"/api/%67raphql", "/api/graph%71l", "/%61pi/graphql"}) {
            HttpResponse<String> r = b.postJson(path, over);
            assertThat(r.statusCode()).as(path).isNotEqualTo(200);
            assertThat(r.body()).as(path).doesNotContain("__typename").doesNotContain("\"data\"");
        }
    }
}
