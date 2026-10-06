package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Login backoff over HTTP (ADR 0009, decision 8); a fresh service per test, so no counter carries over. */
class LoginThrottleIntegrationTest {

    OperatorApp app;

    @BeforeEach
    void start() {
        app = new OperatorApp();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    static void assert429(HttpResponse<String> r, String retryAfter) {
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(r.headers().firstValue("Retry-After")).hasValue(retryAfter);
    }

    static void assertProblemBody(HttpResponse<String> r, String path) {
        JsonNode problem = new ObjectMapper().readTree(r.body());
        assertThat(problem.get("status").asInt()).isEqualTo(429);
        assertThat(problem.get("title").asString()).isNotBlank();
        assertThat(problem.get("instance").asString()).isEqualTo(path);
        assertThat(problem.get("correlation_id").asString())
                .isEqualTo(r.headers().firstValue("X-Correlation-Id").orElseThrow());
    }

    /** A browser whose requests come from this loopback address, so the service sees a different client. */
    Browser from(String address) throws Exception {
        return new Browser(app.base, HttpClient.newBuilder().localAddress(InetAddress.getByName(address)).build());
    }

    @Test
    void whileTwoPasswordChecksRunALoginIsRefusedWithAProblemBodyAndNoCheck() throws Exception {
        LoginThrottle throttle = app.context.getBean(LoginThrottle.class);
        Optional<LoginThrottle.Slot> first = throttle.check();
        Optional<LoginThrottle.Slot> second = throttle.check();
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        Browser b = app.browser();

        HttpResponse<String> r = b.login(OperatorApp.OPERATOR, app.password);

        assert429(r, "1");
        assertProblemBody(r, "/api/auth/login");
        assertThat(Browser.setCookie(r, "SPACEFLUX_SESSION")).isNull();
        first.get().close();
        second.get().close();
        assertThat(b.login(OperatorApp.OPERATOR, app.password).statusCode()).isEqualTo(204);
    }

    @Test
    void thirtyFailuresFromThirtyAddressesHoldOffEveryAddress() throws Exception {
        for (int i = 2; i <= 31; i++) {
            assertThat(from("127.0.0." + i).login(OperatorApp.OPERATOR, "wrong").statusCode()).isEqualTo(401);
        }

        HttpResponse<String> r = from("127.0.0.1").login(OperatorApp.OPERATOR, app.password);

        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(r.headers().firstValue("Retry-After").orElseThrow())).isBetween(540L, 600L);
        assertProblemBody(r, "/api/auth/login");
        assertThat(Browser.setCookie(r, "SPACEFLUX_SESSION")).isNull();
    }

    @Test
    void afterFourFailuresTheAddressWaitsEvenWithTheRightPassword() throws Exception {
        Browser b = app.browser();
        for (int i = 0; i < 4; i++) {
            assertThat(b.login(OperatorApp.OPERATOR, "wrong").statusCode()).isEqualTo(401);
        }

        assert429(b.login(OperatorApp.OPERATOR, "wrong"), "2");
        HttpResponse<String> right = b.login(OperatorApp.OPERATOR, app.password);
        assert429(right, "2");
        assertThat(Browser.setCookie(right, "SPACEFLUX_SESSION")).isNull();

        Thread.sleep(2_100);
        assertThat(b.login(OperatorApp.OPERATOR, app.password).statusCode()).isEqualTo(204);
    }

    @Test
    void aPercentEncodedLoginPathIsThrottledLikeThePlainOne() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        for (int i = 0; i < 4; i++) {
            assertThat(b.postForm("/api/auth/%6Cogin", java.util.Map.of("username", OperatorApp.OPERATOR,
                    "password", "wrong")).statusCode()).isEqualTo(401);
        }

        assert429(b.postForm("/api/auth/%6Cogin", java.util.Map.of("username", OperatorApp.OPERATOR, "password",
                "wrong")), "2");
        assert429(b.login(OperatorApp.OPERATOR, app.password), "2");
    }

    @Test
    void aLoginRefusedForItsXsrfHeaderIsNotCounted() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        b.sendXsrfHeader = false;
        for (int i = 0; i < 6; i++) {
            assertThat(b.login(OperatorApp.OPERATOR, "wrong").statusCode()).isEqualTo(403);
        }
        b.sendXsrfHeader = true;

        assertThat(b.login(OperatorApp.OPERATOR, app.password).statusCode()).isEqualTo(204);
    }
}
