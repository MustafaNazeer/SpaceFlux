package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;

/**
 * Logout over HTTPS, as the cloud ingress will deliver it: the service trusts the forwarded protocol from loopback here,
 * standing in for the ingress (ADR 0009, decision 8).
 */
class LogoutOverHttpsIntegrationTest {

    @Test
    void logoutOverHttpsSendsClearSiteData() throws Exception {
        try (OperatorApp app = new OperatorApp("--server.forward-headers-strategy=native")) {
            Browser b = app.signedIn();

            HttpRequest.Builder logout = HttpRequest.newBuilder(URI.create(app.base + "/api/auth/logout"))
                    .header("X-Forwarded-Proto", "https")
                    .header("Cookie", "SPACEFLUX_SESSION=" + b.cookies.get("SPACEFLUX_SESSION") + "; XSRF-TOKEN="
                            + b.cookies.get("XSRF-TOKEN"))
                    .header("X-XSRF-TOKEN", b.cookies.get("XSRF-TOKEN"))
                    .POST(HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = Browser.HTTP.send(logout.build(), HttpResponse.BodyHandlers.ofString());

            assertThat(r.statusCode()).isEqualTo(204);
            assertThat(r.headers().firstValue("Clear-Site-Data")).hasValue("\"cookies\"");
        }
    }
}
