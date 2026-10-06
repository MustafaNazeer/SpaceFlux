package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpSession;

import org.apache.catalina.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.WebApplicationContext;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The operator's session, the acknowledgement endpoint and the headers of docs/api/rest.md, over HTTP. */
class AuthIntegrationTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String OPERATOR = "operator";
    static final String PASSWORD = TestMysql.password();
    static final String SESSION = "SPACEFLUX_SESSION";

    static ConfigurableApplicationContext app;
    static String base;

    @BeforeAll
    static void start() {
        TestMysql.start();
        // Cost 4 keeps the tests fast; the cost of the real hash is chosen by measurement (ADR 0009, amendment).
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);
        app = new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(TestMysql.args("--server.port=0", "--spaceflux.alerts.enabled=false",
                        "--spaceflux.catalog.enabled=false", "--ACK_OPERATOR_USERNAME=" + OPERATOR,
                        "--ACK_OPERATOR_PASSWORD_HASH=" + hash, "--ACK_OPERATOR_BCRYPT_COST=4"));
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static Browser browser() {
        return new Browser(base);
    }

    static Browser signedIn() throws Exception {
        Browser b = browser();
        assertThat(b.login(OPERATOR, PASSWORD).statusCode()).isEqualTo(204);
        return b;
    }

    static void assertProblem(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode p = JSON.readTree(r.body());
        assertThat(p.get("status").asInt()).isEqualTo(status);
        assertThat(p.get("correlation_id").asString())
                .isEqualTo(r.headers().firstValue("X-Correlation-Id").orElseThrow());
    }

    /** A problem body without the fields that differ on every request. */
    static JsonNode sameParts(HttpResponse<String> r) {
        ObjectNode p = (ObjectNode) JSON.readTree(r.body());
        p.remove("correlation_id");
        return p;
    }

    @Test
    void everyResponseCarriesTheSecurityHeaders() throws Exception {
        for (String path : new String[] {"/api/watchlist", "/api/no-such-path", "/error", "/api/auth/session"}) {
            HttpResponse<String> r = get(path);

            assertThat(r.headers().firstValue("X-Content-Type-Options")).as(path).hasValue("nosniff");
            assertThat(r.headers().firstValue("X-Frame-Options")).as(path).hasValue("DENY");
            assertThat(r.headers().firstValue("Cache-Control")).as(path)
                    .hasValue("no-cache, no-store, max-age=0, must-revalidate");
        }
    }

    @Test
    void anAnonymousSessionCheckIsUnauthorizedCreatesNoSessionAndSetsTheXsrfCookie() throws Exception {
        HttpResponse<String> r = get("/api/auth/session");

        assertProblem(r, 401);
        assertThat(Browser.setCookie(r, SESSION)).isNull();
        assertThat(Browser.setCookie(r, "JSESSIONID")).isNull();
        assertThat(Browser.setCookie(r, "XSRF-TOKEN")).isNotNull();
    }

    @Test
    void theOperatorSignsInWithASecureHttpOnlyStrictSessionCookie() throws Exception {
        Browser b = browser();

        HttpResponse<String> r = b.login(OPERATOR, PASSWORD);

        assertThat(r.statusCode()).isEqualTo(204);
        String cookie = Browser.setCookie(r, SESSION);
        assertThat(cookie).isNotNull().contains("; Secure").contains("; HttpOnly").contains("; SameSite=Strict")
                .contains("; Path=/");
        HttpResponse<String> session = b.get("/api/auth/session");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(session.body()).get("username").asString()).isEqualTo(OPERATOR);
    }

    @Test
    void aWrongPasswordAndAnUnknownUserGetTheSameRefusalAndNoSession() throws Exception {
        HttpResponse<String> wrongPassword = browser().login(OPERATOR, PASSWORD + "x");
        HttpResponse<String> unknownUser = browser().login("someone", PASSWORD);

        assertProblem(wrongPassword, 401);
        assertProblem(unknownUser, 401);
        assertThat(sameParts(wrongPassword)).isEqualTo(sameParts(unknownUser));
        assertThat(Browser.setCookie(wrongPassword, SESSION)).isNull();
    }

    @Test
    void aLoginWithoutTheXsrfHeaderIsForbidden() throws Exception {
        Browser b = browser();
        b.get("/api/auth/session");
        b.sendXsrfHeader = false;

        HttpResponse<String> r = b.login(OPERATOR, PASSWORD);

        assertProblem(r, 403);
        assertThat(Browser.setCookie(r, SESSION)).isNull();
    }

    @Test
    void eachLoginGetsANewSessionIdAndTheOldOneStopsWorking() throws Exception {
        Browser b = signedIn();
        String first = b.cookies.get(SESSION);

        assertThat(b.login(OPERATOR, PASSWORD).statusCode()).isEqualTo(204);

        assertThat(b.cookies.get(SESSION)).isNotEqualTo(first);
        Browser old = browser();
        old.cookies.put(SESSION, first);
        assertThat(old.get("/api/auth/session").statusCode()).isEqualTo(401);
    }

    @Test
    void aSecondLoginEndsTheFirstSession() throws Exception {
        Browser first = signedIn();
        Browser second = signedIn();

        assertThat(second.get("/api/auth/session").statusCode()).isEqualTo(200);
        String token = first.cookies.get("XSRF-TOKEN");
        HttpResponse<String> replaced = first.get("/api/watchlist");

        // Answered exactly as a request without a session: its XSRF-TOKEN is kept, nothing is cleared.
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(Browser.setCookie(replaced, "XSRF-TOKEN")).isNull();
        assertThat(replaced.headers().firstValue("Clear-Site-Data")).isEmpty();
        assertThat(first.cookies.get("XSRF-TOKEN")).isEqualTo(token);
        assertThat(first.get("/api/auth/session").statusCode()).isEqualTo(401);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Browser b = signedIn();
        String id = b.cookies.get(SESSION);

        HttpResponse<String> r = b.postForm("/api/auth/logout", Map.of());

        assertThat(r.statusCode()).isEqualTo(204);
        // Clear-Site-Data is sent on HTTPS only; LogoutOverHttpsIntegrationTest covers it.
        assertThat(r.headers().firstValue("Clear-Site-Data")).isEmpty();
        Browser old = browser();
        old.cookies.put(SESSION, id);
        assertThat(old.get("/api/auth/session").statusCode()).isEqualTo(401);
    }

    @Test
    void aSessionIdInTheUrlGivesNoSession() throws Exception {
        String id = signedIn().cookies.get(SESSION);

        for (String param : new String[] {";jsessionid=", ";" + SESSION + "=", ";" + SESSION.toLowerCase() + "="}) {
            HttpResponse<String> r = get("/api/auth/session" + param + id);

            // Spring Security's firewall refuses any path holding ';' before the container reads the ID.
            assertProblem(r, 400);
            assertThat(r.body()).as(param).doesNotContain(OPERATOR);
        }
    }

    @Test
    void theContainerTracksSessionsByCookieOnly() {
        ServletContext servlet = ((WebApplicationContext) app).getServletContext();

        assertThat(servlet.getEffectiveSessionTrackingModes())
                .containsExactly(SessionTrackingMode.COOKIE);
    }

    static int liveSessions() {
        var tomcat = ((TomcatWebServer)
                ((WebServerApplicationContext) app).getWebServer()).getTomcat();
        return ((Context) tomcat.getHost().findChildren()[0]).getManager().getActiveSessions();
    }

    @Test
    void noAnonymousRequestToAnyEndpointCreatesASession() throws Exception {
        int before = liveSessions();
        Browser b = browser();
        b.get("/api/auth/session");
        String[] gets = {"/api/auth/session", "/api/space-weather/current", "/api/watchlist", "/api/screening/current",
            "/api/space-weather/history?scale=G&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z",
            "/api/alerts/by-id?event_id=x", "/api/alerts/acknowledgements?event_id=x", "/api/catalog/25544",
            "/api/no-such-path", "/error", "/login"};
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (String path : gets) {
            responses.add(b.get(path));
        }
        responses.add(b.postJson("/api/alerts/acknowledgements?event_id=x", "{\"action\": \"acknowledge\"}"));
        responses.add(b.postForm("/api/auth/logout", Map.of()));
        responses.add(b.login(OPERATOR, PASSWORD + "x"));
        responses.add(b.postJson("/api/watchlist", "{}"));

        for (HttpResponse<String> r : responses) {
            assertThat(Browser.setCookie(r, SESSION)).as(r.uri().toString()).isNull();
            assertThat(Browser.setCookie(r, "JSESSIONID")).as(r.uri().toString()).isNull();
            assertThat(r.body()).as(r.uri().toString()).doesNotContain("<html").doesNotContain("<form");
        }
        assertThat(liveSessions()).isEqualTo(before);
    }

    @Test
    void theXsrfCookieIsSecureAndStrictAndReadableByPageScript() throws Exception {
        String cookie = Browser.setCookie(get("/api/auth/session"), "XSRF-TOKEN");

        assertThat(cookie).contains("; Secure").contains("; SameSite=Strict").contains("; Path=/")
                .doesNotContain("HttpOnly");
    }

    static HttpSession serverSession(String id) throws Exception {
        var tomcat = ((TomcatWebServer)
                ((WebServerApplicationContext) app).getWebServer()).getTomcat();
        return ((Context) tomcat.getHost().findChildren()[0]).getManager().findSession(id)
                .getSession();
    }

    @Test
    void aSessionEndsEightHoursAfterItsLogin() throws Exception {
        Browser b = signedIn();
        HttpSession session = serverSession(b.cookies.get(SESSION));
        Instant at = (Instant) session.getAttribute(SessionLifetimeFilter.SIGNED_IN_AT);
        assertThat(at).isBetween(Instant.now().minusSeconds(60), Instant.now());

        session.setAttribute(SessionLifetimeFilter.SIGNED_IN_AT, at.minus(Duration.ofHours(8)));

        assertProblem(b.get("/api/auth/session"), 401);
    }

    @Test
    void anIdleSessionEndsAfterThirtyMinutes() throws Exception {
        assertThat(serverSession(signedIn().cookies.get(SESSION)).getMaxInactiveInterval()).isEqualTo(1800);
    }

    @Test
    void everyResponseToARequestWithoutTheXsrfCookieSetsIt() throws Exception {
        assertThat(Browser.setCookie(get("/api/watchlist"), "XSRF-TOKEN")).isNotNull();
        assertThat(Browser.setCookie(get("/api/auth/session"), "XSRF-TOKEN")).isNotNull();
    }

    @Test
    void aLoginWithAQueryStringIsRefusedBeforeThePasswordIsChecked() throws Exception {
        Browser b = browser();
        b.get("/api/auth/session");

        HttpResponse<String> r = b.postForm("/api/auth/login?password=" + PASSWORD,
                Map.of("username", OPERATOR));

        assertProblem(r, 400);
        assertThat(Browser.setCookie(r, SESSION)).isNull();
    }
}
