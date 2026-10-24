package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlRequest;

import io.github.mustafanazeer.spaceflux.query.Browser;
import jakarta.servlet.http.HttpSession;
import org.apache.catalina.Context;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import io.github.mustafanazeer.spaceflux.query.auth.Viewer;
import tools.jackson.databind.JsonNode;

/**
 * The live alert subscription over WebSocket (ADR 0014): the handshake checks, the caps, one operation type per
 * transport, the message limits, the ADR 0012 limits for subscriptions, and what a subscriber receives.
 */
@ExtendWith(OutputCaptureExtension.class)
class AlertSocketIntegrationTest {

    static final String NOTICE = "subscription { alert_stored { kind rules_version event_id produced_at received_at "
            + "space_weather_level { scale state } close_approach { run_id miss_distance_m } } }";
    static final String NOTICE_WITH_OBJECT = "subscription { alert_stored { event_id close_approach { run_id "
            + "watchlist_object { catalog_number } } } }";

    static OperatorApp app;
    static final List<TestSocket> opened = new ArrayList<>();
    static final AtomicReference<Boolean> operatorOnSocket = new AtomicReference<>();

    /** Records whether a socket operation runs as the operator. */
    @Configuration(proxyBeanMethods = false)
    static class ViewerProbe {
        @Bean
        WebGraphQlInterceptor viewerProbe() {
            return (request, chain) -> {
                if (request instanceof WebSocketGraphQlRequest) {
                    operatorOnSocket.set(Viewer.isOperator());
                }
                return chain.next(request);
            };
        }
    }

    @BeforeAll
    static void start() {
        app = new OperatorApp(new Class<?>[] {ViewerProbe.class},
                "--SPACEFLUX_DASHBOARD_ORIGIN=" + TestSocket.DASHBOARD,
                "--spaceflux.graphql.request-timeout=500ms",
                // Short, so the thousand connection loop can include connections closed by it.
                "--spring.graphql.websocket.connection-init-timeout=500ms");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @AfterEach
    void closeAll() {
        opened.forEach(TestSocket::close);
        opened.clear();
        await().atMost(Duration.ofSeconds(10)).until(() -> connections().open() == 0);
    }

    static Connections connections() {
        return app.context.getBean(Connections.class);
    }

    static TestSocket open() throws Exception {
        return open(TestSocket.DASHBOARD, Map.of());
    }

    static TestSocket open(String origin, Map<String, String> headers) throws Exception {
        TestSocket s = TestSocket.open(app.base, origin, headers);
        opened.add(s);
        return s;
    }

    static int refusal(String origin) {
        try {
            open(origin, Map.of());
        } catch (TestSocket.Refused r) {
            return r.status;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return 101;
    }

    static void publish(String eventId) {
        app.context.getBean(AlertNotices.class).stored(eventId);
    }

    static String messages(JsonNode body) {
        StringBuilder out = new StringBuilder();
        JsonNode errors = body.has("payload") && body.get("payload").has("errors") ? body.get("payload").get("errors")
                : body.has("errors") ? body.get("errors") : null;
        if (errors != null) {
            errors.forEach(e -> out.append(e.get("message").asString()).append('\n'));
        }
        return out.toString();
    }

    static void assertNoInternals(String text) {
        assertThat(text).doesNotContain("Exception").doesNotContain("at io.").doesNotContain("at graphql.")
                .doesNotContain("java.").doesNotContain("SELECT").doesNotContain("org.springframework");
    }

    // Origin (decision 5)

    @Test
    void theDashboardOriginUpgradesAndAnAnonymousHandshakeCreatesNoSession() throws Exception {
        try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", TestSocket.DASHBOARD))) {
            assertThat(h.status()).isEqualTo(101);
            assertThat(h.head).doesNotContain("SPACEFLUX_SESSION");
        }
    }

    @Test
    void everyOtherOriginAndAMissingOneAreRefusedWith403AndHoldNoSlot() throws Exception {
        for (String origin : List.of("http://evil.example", "https://localhost:4200", "http://localhost:4201",
                "http://localhost:4200/", "HTTP://LOCALHOST:4200", "null")) {
            assertThat(refusal(origin)).as(origin).isEqualTo(403);
        }
        try (RawHandshake h = RawHandshake.send(app.base, Map.of())) {
            assertThat(h.status()).isEqualTo(403);
            assertThat(h.header("Content-Type")).isEqualTo("application/problem+json");
            assertNoInternals(h.body);
        }
        try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", "http://evil.example"))) {
            assertThat(h.status()).isEqualTo(403);
        }

        assertThat(connections().open()).isZero();
    }

    @Test
    void twoOriginHeadersAreRefusedEvenWhenOneMatches() throws Exception {
        try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", TestSocket.DASHBOARD + "\r\nOrigin: "
                + "http://evil.example"))) {
            assertThat(h.status()).isEqualTo(403);
        }
    }

    @Test
    void theHttpEndpointStillHasNoCorsWhileTheSocketHasAnOrigin() throws Exception {
        HttpResponse<String> r = Browser.HTTP.send(HttpRequest.newBuilder(URI.create(app.base + "/api/graphql"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", TestSocket.DASHBOARD)
                .header("Access-Control-Request-Method", "POST").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    // Caps (decision 6)

    @Test
    void theFifthConnectionFromOneAddressGets429WithRetryAfterAndClosingOneLetsANewOneIn() throws Exception {
        List<TestSocket> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            four.add(open().init());
        }

        TestSocket.Refused refused = null;
        try {
            open();
        } catch (TestSocket.Refused r) {
            refused = r;
        }
        assertThat(refused).isNotNull();
        assertThat(refused.status).isEqualTo(429);
        assertThat(refused.headers.firstValue("Retry-After")).hasValue("30");
        try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", TestSocket.DASHBOARD))) {
            assertThat(h.status()).isEqualTo(429);
            assertThat(h.header("Content-Type")).isEqualTo("application/problem+json");
        }

        four.get(0).close();
        await().atMost(Duration.ofSeconds(5)).until(() -> connections().open() == 3);
        open().init();
        assertThat(connections().open()).isEqualTo(4);
    }

    @Test
    void aHandshakeRefusedAfterItsSlotWasReservedGivesTheSlotBack() throws Exception {
        try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", TestSocket.DASHBOARD,
                "Sec-WebSocket-Version", "12"))) {
            assertThat(h.status()).isNotEqualTo(101);
        }

        assertThat(connections().open()).isZero();
    }

    @Test
    void openingAndClosingAThousandConnectionsLeavesTheCountersAtZero() throws Exception {
        for (int i = 0; i < 1_000; i++) {
            await().atMost(Duration.ofSeconds(5)).until(() -> connections().open() < 4);
            switch (i % 5) {
                case 0 -> open().close();
                case 1 -> open().init().close();
                case 2 -> assertThat(refusal("http://evil.example")).isEqualTo(403);
                // Left without connection_init, closed by the server's init timeout.
                case 3 -> open();
                default -> {
                    try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", TestSocket.DASHBOARD,
                            "Sec-WebSocket-Version", "12"))) {
                        assertThat(h.status()).isNotEqualTo(101);
                    }
                }
            }
            if (i % 100 == 99) {
                opened.clear();
            }
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> connections().open() == 0);
        assertThat(connections().addresses()).isZero();
    }

    @Test
    void aSocketLeftWithoutConnectionInitIsClosedWith4408AndGivesItsSlotBack() throws Exception {
        TestSocket s = open();

        assertThat(s.closed(Duration.ofSeconds(5))).isEqualTo(4408);
        await().atMost(Duration.ofSeconds(5)).until(() -> connections().open() == 0);
    }

    /** The handler's timer, which must not keep anything for a connection that has closed. */
    static int scheduled() {
        return ((AlertSocketHandler) app.context.getBean(
                org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler.class)).scheduledTasks();
    }

    @Test
    void closedConnectionsLeaveNothingOnTheTimer() throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(() -> scheduled() == 0);
        for (int i = 0; i < 40; i++) {
            open().init().close();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> connections().open() == 0);

        // At most the fallback that gives back a slot whose upgrade never opened, one per handshake, until the
        // init timeout passes; no lifetime task of a closed connection.
        assertThat(scheduled()).isLessThanOrEqualTo(40);
        await().atMost(Duration.ofSeconds(5)).until(() -> scheduled() == 0);
    }

    @Test
    void theContainerTextLimitIsSetExplicitly() {
        assertThat(app.context.getBean(org.springframework.web.socket.server.standard
                .ServletServerContainerFactoryBean.class).getMaxTextMessageBufferSize()).isEqualTo(8_192);
    }

    @Test
    void aSecondSubscriptionOnOneConnectionGetsAnErrorAndTheFirstKeepsReceiving() throws Exception {
        TestSocket s = open().init();
        s.subscribe("1", NOTICE);
        assertThat(s.quiet(Duration.ofMillis(300))).isTrue();

        s.subscribe("2", NOTICE);
        JsonNode second = s.next();
        String eventId = Alerts.store(app.context, Alerts.approach());
        publish(eventId);
        JsonNode first = s.next();

        assertThat(second.get("id").asString()).as(second.toString()).isEqualTo("2");
        assertThat(second.get("type").asString()).isEqualTo("error");
        assertThat(second.get("payload").get(0).get("message").asString())
                .isEqualTo("One subscription per connection.");
        assertThat(first.get("id").asString()).isEqualTo("1");
        assertThat(first.get("payload").get("data").get("alert_stored").get("event_id").asString())
                .isEqualTo(eventId);
    }

    @Test
    void afterTheSubscriptionCompletesTheConnectionMaySubscribeAgain() throws Exception {
        TestSocket s = open().init();
        s.subscribe("1", NOTICE);
        s.send("{\"id\":\"1\",\"type\":\"complete\"}");
        Thread.sleep(200);

        s.subscribe("2", NOTICE);
        String eventId = Alerts.store(app.context, Alerts.approach());
        publish(eventId);

        JsonNode next = s.next();
        assertThat(next.get("id").asString()).isEqualTo("2");
        assertThat(messages(next)).isEmpty();
    }

    // One operation type per transport (decision 1)

    @Test
    void aQueryOverTheSocketIsRefusedWithFixedTextAndNothingRuns() throws Exception {
        TestSocket s = open().init();

        s.subscribe("q", "{ watchlist { catalog_number } }");
        JsonNode r = s.next();

        assertThat(messages(r)).isEqualTo("Only subscriptions are served over WebSocket.\n");
        assertThat(r.get("payload").get("errors").get(0).has("path")).isFalse();
        assertThat(r.get("payload").has("data") && !r.get("payload").get("data").isNull()).isFalse();
        assertThat(s.next().get("type").asString()).isEqualTo("complete");
    }

    @Test
    void aSubscriptionSentAsJsonOverHttpIsRefusedBeforeExecution() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");

        HttpResponse<String> r = b.postJson("/api/graphql", TestSocket.JSON.writeValueAsString(Map.of("query",
                NOTICE)));

        JsonNode body = TestSocket.JSON.readTree(r.body());
        assertThat(messages(body)).isEqualTo("Subscriptions are served over WebSocket only.\n");
        // Refused before execution: no field was reached, so the error has no path.
        assertThat(body.get("errors").get(0).has("path")).isFalse();
        assertThat(body.get("errors").get(0).get("extensions").get("classification").asString())
                .isEqualTo("ExecutionAborted");
        assertThat(body.has("data") && !body.get("data").isNull()).isFalse();
        assertThat(app.context.getBean(AlertNotices.class).subscribers()).isZero();
    }

    @Test
    void theServerSentEventsRouteIsOff() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(app.base + "/api/graphql"))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("Cookie", "XSRF-TOKEN=" + b.cookies.get("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", b.cookies.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(TestSocket.JSON.writeValueAsString(Map.of("query",
                        NOTICE))));

        HttpResponse<String> r = Browser.HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(406);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(r.body()).doesNotContain("alert_stored");
        assertThat(app.context.getBean(AlertNotices.class).subscribers()).isZero();
    }

    // What a subscriber receives (decisions 2, 3, 4)

    @Test
    void aStoredCloseApproachArrivesOnceWithItsStoredTimeAndNullsForTheOtherKind() throws Exception {
        TestSocket s = open().init();
        s.subscribe("1", NOTICE);
        Thread.sleep(200);
        String eventId = Alerts.store(app.context, Alerts.approach());

        publish(eventId);
        String text = s.nextText();

        JsonNode notice = TestSocket.JSON.readTree(text).get("payload").get("data").get("alert_stored");
        assertThat(text).contains("\"space_weather_level\":null");
        assertThat(notice.get("event_id").asString()).isEqualTo(eventId);
        assertThat(notice.get("kind").asString()).isEqualTo("close_approach");
        Browser b = app.browser();
        b.get("/api/auth/session");
        JsonNode stored = TestSocket.JSON.readTree(b.postJson("/api/graphql", TestSocket.JSON.writeValueAsString(
                Map.of("query", "query($id: String!) { alert(event_id: $id) { received_at produced_at "
                        + "rules_version } }", "variables", Map.of("id", eventId)))).body()).get("data").get("alert");
        assertThat(notice.get("received_at").asString()).isEqualTo(stored.get("received_at").asString());
        assertThat(notice.get("produced_at").asString()).isEqualTo(stored.get("produced_at").asString());
        assertThat(notice.get("rules_version").asLong()).isEqualTo(stored.get("rules_version").asLong());
        assertThat(s.quiet(Duration.ofMillis(300))).isTrue();
    }

    @Test
    void anAlertTheRecentListDoesNotShowIsNotPushed() throws Exception {
        TestSocket s = open().init();
        s.subscribe("1", NOTICE);
        Thread.sleep(200);
        String run = Alerts.store(app.context, Alerts.run());
        String approach = Alerts.store(app.context, Alerts.approach());

        publish(run);
        publish(approach);

        assertThat(s.next().get("payload").get("data").get("alert_stored").get("event_id").asString())
                .isEqualTo(approach);
        assertThat(s.quiet(Duration.ofMillis(300))).isTrue();
    }

    @Test
    void aStreamOutlivesTheRequestTimeout() throws Exception {
        TestSocket s = open().init();
        s.subscribe("1", NOTICE);
        Thread.sleep(1_500);
        String eventId = Alerts.store(app.context, Alerts.approach());

        publish(eventId);

        JsonNode next = s.next();
        assertThat(messages(next)).isEmpty();
        assertThat(next.get("payload").get("data").get("alert_stored").get("event_id").asString())
                .isEqualTo(eventId);
    }

    /** Stores a close approach, then edits its stored payload to hold a value the store would refuse. */
    static String storeEdited(String from, String to) throws Exception {
        String eventId = Alerts.store(app.context, Alerts.approach());
        TestMysql.rootSql("UPDATE spaceflux.alert_event SET payload = REPLACE(payload, '" + from + "', '" + to
                + "') WHERE event_id = '" + eventId + "'");
        return eventId;
    }

    /** Removes an edited row, so no later test class lists it. */
    static void forget(String eventId) throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.close_approach WHERE alert_seq IN (SELECT alert_seq FROM (SELECT "
                + "alert_seq FROM spaceflux.alert_event WHERE event_id = '" + eventId + "') t)");
        TestMysql.rootSql("DELETE FROM spaceflux.alert_event WHERE event_id = '" + eventId + "'");
    }

    /** graphql-java 25.0 fails an event whose null reaches alert_stored, which may not be null. */
    @Test
    void aValueOutOfRangeWhoseNullReachesTheRootEndsTheSubscriptionWithTheGenericError(CapturedOutput output)
            throws Exception {
        TestSocket s = open().init();
        s.subscribe("1", NOTICE);
        Thread.sleep(200);
        String eventId = storeEdited("\"rules_version\":1,", "\"rules_version\":4294967296,");
        try {
            publish(eventId);
            String text = s.nextText();

            assertThat(text).isEqualTo("{\"id\":\"1\",\"payload\":[{\"message\":\"Subscription error\","
                    + "\"locations\":[],\"extensions\":{\"classification\":\"INTERNAL_ERROR\"}}],\"type\":\"error\"}");
            assertThat(output.getAll()).contains("Unresolved CompletionException for request id 1")
                    .doesNotContain("4294967296");

            s.subscribe("2", NOTICE);
            Thread.sleep(200);
            String after = Alerts.store(app.context, Alerts.approach());
            publish(after);
            JsonNode next = s.next();
            assertThat(next.get("id").asString()).isEqualTo("2");
            assertThat(next.get("payload").get("data").get("alert_stored").get("event_id").asString())
                    .isEqualTo(after);
        } finally {
            forget(eventId);
        }
    }

    @Test
    void aValueOutOfRangeUnderANullableFieldArrivesWithTheGenericInternalError(CapturedOutput output)
            throws Exception {
        TestSocket s = open().init();
        s.subscribe("1", NOTICE_WITH_OBJECT);
        Thread.sleep(200);
        String eventId = storeEdited("\"catalog_number\":57036,", "\"catalog_number\":2147483648,");
        try {
            publish(eventId);
            String text = s.nextText();

            JsonNode next = TestSocket.JSON.readTree(text);
            assertThat(next.get("type").asString()).as(text).isEqualTo("next");
            JsonNode notice = next.get("payload").get("data").get("alert_stored");
            assertThat(notice.get("event_id").asString()).isEqualTo(eventId);
            assertThat(notice.get("close_approach").isNull()).isTrue();
            assertThat(next.get("payload").get("errors")).as(text).singleElement().satisfies(e -> {
                assertThat(e.get("extensions").get("classification").asString()).isEqualTo("INTERNAL_ERROR");
                assertThat(e.get("message").asString()).startsWith("INTERNAL_ERROR for ");
                assertThat(e.get("path").toString())
                        .isEqualTo("[\"alert_stored\",\"close_approach\",\"watchlist_object\",\"catalog_number\"]");
            });
            assertThat(text).doesNotContain("2147483648").doesNotContain("serialize").doesNotContain("Long")
                    .doesNotContain("'Int'");
            assertNoInternals(text);
            assertThat(output.getAll()).contains("Unserializable value for executionId");
        } finally {
            forget(eventId);
        }
    }

    @Test
    void aSocketOpenedWithTheOperatorsSessionIsServedAnonymously() throws Exception {
        Browser signedIn = app.signedIn();
        String cookie = "SPACEFLUX_SESSION=" + signedIn.cookies.get("SPACEFLUX_SESSION");
        HttpResponse<String> check = signedIn.get("/api/auth/session");
        assertThat(check.body()).contains("operator");
        operatorOnSocket.set(null);
        TestSocket s = open(TestSocket.DASHBOARD, Map.of("Cookie", cookie)).init();

        s.subscribe("1", NOTICE);
        String eventId = Alerts.store(app.context, Alerts.approach());
        publish(eventId);

        assertThat(s.next().get("payload").get("data").get("alert_stored").get("event_id").asString())
                .isEqualTo(eventId);
        assertThat(operatorOnSocket.get()).isFalse();
    }

    static HttpSession serverSession(String id) throws Exception {
        var tomcat = ((TomcatWebServer) ((WebServerApplicationContext) app.context).getWebServer()).getTomcat();
        return ((Context) tomcat.getHost().findChildren()[0]).getManager().findSession(id).getSession();
    }

    static final String SIGNED_IN_AT = "io.github.mustafanazeer.spaceflux.query.auth.SessionLifetimeFilter.SIGNED_IN_AT";

    /** Ends the operator's session one of three ways; the browser that held it is then signed out. */
    static void end(String how, Browser operator) throws Exception {
        switch (how) {
            case "logout" -> assertThat(operator.postForm("/api/auth/logout", Map.of()).statusCode())
                    .isEqualTo(204);
            case "expiry" -> {
                HttpSession session = serverSession(operator.cookies.get("SPACEFLUX_SESSION"));
                Instant at = (Instant) session.getAttribute(SIGNED_IN_AT);
                session.setAttribute(SIGNED_IN_AT, at.minus(Duration.ofHours(8)));
            }
            default -> app.signedIn();
        }
        assertThat(operator.get("/api/auth/session").statusCode()).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"logout", "expiry", "replaced"})
    void aSocketOpenedWithTheOperatorsSessionStaysAnonymousAfterTheSessionEnds(String how) throws Exception {
        Browser operator = app.signedIn();
        String cookie = "SPACEFLUX_SESSION=" + operator.cookies.get("SPACEFLUX_SESSION");
        TestSocket s = open(TestSocket.DASHBOARD, Map.of("Cookie", cookie)).init();
        s.subscribe("1", NOTICE_ALL);
        assertThat(s.quiet(Duration.ofMillis(200))).isTrue();

        end(how, operator);
        operatorOnSocket.set(null);
        String eventId = Alerts.store(app.context, Alerts.approach());
        publish(eventId);
        String text = s.nextText();
        s.send("{\"id\":\"1\",\"type\":\"complete\"}");
        s.subscribe("2", NOTICE);

        assertThat(text).contains(eventId).doesNotContain("\"note\"").doesNotContain("\"principal\"");
        await().atMost(Duration.ofSeconds(5)).until(() -> operatorOnSocket.get() != null);
        assertThat(operatorOnSocket.get()).isFalse();
    }

    /** Every field AlertNotice has. */
    static final String NOTICE_ALL = "subscription { alert_stored { __typename kind rules_version event_id "
            + "produced_at received_at space_weather_level { scale state } close_approach { run_id } } }";

    @Test
    void aMutationOverTheSocketIsRefusedAndNothingRuns() throws Exception {
        TestSocket s = open().init();

        s.subscribe("m", "mutation { acknowledge(event_id: \"x\") { action } }");
        JsonNode r = s.next();

        assertThat(messages(r)).isNotEmpty();
        assertNoInternals(messages(r));
        assertThat(r.get("payload").has("data") && !r.get("payload").get("data").isNull()).isFalse();
        assertThat(app.context.getBean(AlertNotices.class).subscribers()).isZero();
    }

    // The limits of ADR 0012 for subscription operations (decision 8)

    /** alert_stored and n aliases of one field, inline or in one named fragment: n + 1 fields. */
    static String aliases(int n, boolean namedFragment) {
        StringBuilder fields = new StringBuilder();
        for (int i = 0; i < n; i++) {
            fields.append("a").append(i).append(": kind ");
        }
        return namedFragment ? "subscription { alert_stored { ...f } } fragment f on AlertNotice { " + fields + "}"
                : "subscription { alert_stored { " + fields + "} }";
    }

    @Test
    void theFieldCountLimitHoldsForASubscriptionInAliasedAndNamedFragmentForms() throws Exception {
        // alert_stored plus n aliases: 200 fields at n = 199.
        for (boolean fragment : new boolean[] {false, true}) {
            TestSocket under = open().init();
            under.subscribe("1", aliases(199, fragment));
            assertThat(under.quiet(Duration.ofMillis(300))).as("fragment " + fragment).isTrue();
            under.close();

            TestSocket over = open().init();
            over.subscribe("1", aliases(200, fragment));
            JsonNode r = over.next();
            assertThat(messages(r)).as("fragment " + fragment).contains("Maximum field count exceeded");
            assertNoInternals(messages(r));
            over.close();
        }
    }

    @Test
    void theParserTokenLimitHoldsForASubscription() throws Exception {
        StringBuilder q = new StringBuilder("subscription{alert_stored{");
        for (int i = 0; i < 700; i++) {
            q.append('a').append(i).append(":kind ");
        }
        q.append("}}");
        assertThat(q.length()).isLessThan(8_000);
        TestSocket s = open().init();

        s.subscribe("1", q.toString());

        assertThat(messages(s.next())).contains("More than 2,000 'grammar' tokens");
    }

    // Message limits (decision 8)

    static String pingOfLength(int length) {
        String head = "{\"type\":\"ping\",\"payload\":{\"p\":\"";
        String tail = "\"}}";
        return head + "x".repeat(length - head.length() - tail.length()) + tail;
    }

    @Test
    void aTextMessageOf8192CharactersIsReadAndOneMoreClosesWith1009() throws Exception {
        TestSocket s = open().init();
        s.send(pingOfLength(8_192));
        assertThat(s.next().get("type").asString()).isEqualTo("pong");

        s.send(pingOfLength(8_193));

        assertThat(s.closed(Duration.ofSeconds(5))).isEqualTo(1009);
    }

    @Test
    void aBinaryMessageClosesWith1003() throws Exception {
        TestSocket s = open().init();

        s.sendBinary(new byte[] {1, 2, 3});

        assertThat(s.closed(Duration.ofSeconds(5))).isEqualTo(1003);
    }

    @Test
    void twentyMessagesAfterInitAreServedAndTheTwentyFirstClosesWith1008() throws Exception {
        TestSocket s = open().init();
        for (int i = 0; i < 20; i++) {
            s.send("{\"type\":\"ping\"}");
            assertThat(s.next().get("type").asString()).isEqualTo("pong");
        }

        s.send("{\"type\":\"ping\"}");

        assertThat(s.closed(Duration.ofSeconds(5))).isEqualTo(1008);
        assertNoInternals(String.valueOf(s.closeReason));
    }
}
