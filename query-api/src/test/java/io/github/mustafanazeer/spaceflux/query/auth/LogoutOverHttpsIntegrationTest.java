package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;

/**
 * Logout over HTTPS, as the cloud ingress will deliver it: the service trusts the forwarded protocol from loopback here,
 * standing in for the ingress (ADR 0009, decision 8).
 */
class LogoutOverHttpsIntegrationTest {

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp("--server.forward-headers-strategy=native");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static HttpResponse<String> overHttps(HttpRequest.Builder request) throws Exception {
        return Browser.HTTP.send(request.header("X-Forwarded-Proto", "https").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> logout(Browser b) throws Exception {
        return overHttps(HttpRequest.newBuilder(URI.create(app.base + "/api/auth/logout"))
                .header("Cookie", "SPACEFLUX_SESSION=" + b.cookies.get("SPACEFLUX_SESSION") + "; XSRF-TOKEN="
                        + b.cookies.get("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", b.cookies.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    static String value(String setCookie) {
        String pair = setCookie.split(";", 2)[0];
        return pair.substring(pair.indexOf('=') + 1);
    }

    @Test
    void logoutOverHttpsSendsClearSiteData() throws Exception {
        HttpResponse<String> r = logout(app.signedIn());

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.headers().firstValue("Clear-Site-Data")).hasValue("\"cookies\"");
    }

    /** Clear-Site-Data leaves the browser no cookies at all, so the next requests carry none until the session sets one. */
    @Test
    void afterClearSiteDataTheSessionBringsATokenThatALoginOverHttpsIsAcceptedWith() throws Exception {
        assertThat(logout(app.signedIn()).headers().firstValue("Clear-Site-Data")).hasValue("\"cookies\"");

        HttpResponse<String> session = overHttps(
                HttpRequest.newBuilder(URI.create(app.base + "/api/auth/session")).GET());

        assertThat(session.statusCode()).isEqualTo(401);
        String setCookie = Browser.setCookie(session, "XSRF-TOKEN");
        assertThat(setCookie).isNotNull().doesNotStartWith("XSRF-TOKEN=;").contains("Secure");
        String token = value(setCookie);

        String form = "username=" + URLEncoder.encode(OperatorApp.OPERATOR, StandardCharsets.UTF_8) + "&password="
                + URLEncoder.encode(app.password, StandardCharsets.UTF_8);
        HttpResponse<String> login = overHttps(HttpRequest.newBuilder(URI.create(app.base + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(form)));

        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(Browser.setCookie(login, "SPACEFLUX_SESSION")).isNotNull();
    }
}
