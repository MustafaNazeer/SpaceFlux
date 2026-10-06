package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;

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
