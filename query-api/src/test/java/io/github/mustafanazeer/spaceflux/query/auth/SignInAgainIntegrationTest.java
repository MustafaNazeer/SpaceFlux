package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;

/**
 * A sign in right after a sign out on the same page. A logout leaves no XSRF-TOKEN cookie, so the dashboard fetches the
 * session before an unsafe request whenever it holds none (docs/api/rest.md, section 7); these pin the server side of
 * that.
 */
class SignInAgainIntegrationTest {

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static HttpResponse<String> login(Browser b) throws Exception {
        return b.postForm("/api/auth/login", Map.of("username", OperatorApp.OPERATOR, "password", app.password));
    }

    @Test
    void theLogoutExpiresTheXsrfCookieAndSetsNoNewOne() throws Exception {
        Browser b = app.signedIn();
        HttpResponse<String> session = b.get("/api/auth/session");
        assertThat(session.statusCode()).isEqualTo(200);

        HttpResponse<String> out = b.postForm("/api/auth/logout", Map.of());

        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(Browser.setCookies(out).stream().filter(c -> c.startsWith("XSRF-TOKEN=")))
                .singleElement()
                .satisfies(c -> assertThat(c).startsWith("XSRF-TOKEN=;").contains("Expires=Thu, 01 Jan 1970"));
        assertThat(b.cookies).doesNotContainKey("XSRF-TOKEN");
    }

    @Test
    void aGraphQlReadSentWithTheCookiesALogoutLeftIsRefusedAndBringsANewToken() throws Exception {
        Browser b = app.signedIn();
        b.postForm("/api/auth/logout", Map.of());

        HttpResponse<String> read = b.postJson("/api/graphql", "{\"query\": \"{ __typename }\"}");

        assertThat(read.statusCode()).isEqualTo(403);
        assertThat(Browser.setCookie(read, "XSRF-TOKEN")).isNotNull().doesNotStartWith("XSRF-TOKEN=;");
    }

    @Test
    void afterALogoutTheSessionBringsANewTokenThatALoginIsAcceptedWith() throws Exception {
        Browser b = app.signedIn();
        assertThat(b.postForm("/api/auth/logout", Map.of()).statusCode()).isEqualTo(204);
        assertThat(b.cookies).doesNotContainKey("XSRF-TOKEN");

        HttpResponse<String> session = b.get("/api/auth/session");

        assertThat(session.statusCode()).isEqualTo(401);
        assertThat(Browser.setCookie(session, "XSRF-TOKEN")).isNotNull().doesNotStartWith("XSRF-TOKEN=;");
        String before = b.cookies.get("XSRF-TOKEN");
        assertThat(before).isNotNull();

        HttpResponse<String> again = login(b);

        assertThat(again.statusCode()).isEqualTo(204);
        assertThat(b.cookies.get("XSRF-TOKEN")).isNotNull().isNotEqualTo(before);
        assertThat(b.get("/api/auth/session").statusCode()).isEqualTo(200);
    }
}
