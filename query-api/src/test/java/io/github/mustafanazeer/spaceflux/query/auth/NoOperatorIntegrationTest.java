package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;

/** The service started with no operator configured (ADR 0009, decision 2: fail closed). */
@ExtendWith(OutputCaptureExtension.class)
class NoOperatorIntegrationTest {

    @Test
    void noDefaultUserIsCreatedNoPasswordIsLoggedAndOnlyAcknowledgementIsForbidden(CapturedOutput output)
            throws Exception {
        TestMysql.start();
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(TestMysql.args("--server.port=0", "--spaceflux.alerts.enabled=false",
                        "--spaceflux.catalog.enabled=false"))) {
            assertThat(output.getAll()).contains("Tomcat started on port")
                    .doesNotContain("generated security password");
            Browser b = new Browser("http://127.0.0.1:"
                    + ((WebServerApplicationContext) app).getWebServer().getPort());

            assertThat(b.get("/api/watchlist").statusCode()).isEqualTo(200);
            b.sendXsrfHeader = false;
            HttpResponse<String> noToken = b.postJson("/api/alerts/acknowledgements?event_id=x", "{}");
            b.sendXsrfHeader = true;
            HttpResponse<String> withToken = b.postJson("/api/alerts/acknowledgements?event_id=x", "{}");
            HttpResponse<String> encoded = b.postJson("/api/alerts/%61cknowledgements?event_id=x", "{}");
            HttpResponse<String> login = b.login("operator", "anything");

            for (HttpResponse<String> r : java.util.List.of(noToken, withToken, encoded)) {
                assertThat(r.statusCode()).isEqualTo(403);
                assertThat(r.headers().firstValue("Content-Type").orElseThrow())
                        .startsWith("application/problem+json");
            }
            assertThat(login.statusCode()).isEqualTo(401);
        }
    }
}
