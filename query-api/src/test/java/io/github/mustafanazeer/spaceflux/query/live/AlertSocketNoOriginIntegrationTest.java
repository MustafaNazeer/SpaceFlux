package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;

/** With no dashboard origin configured, every handshake is refused and the rest of the API serves normally. */
class AlertSocketNoOriginIntegrationTest {

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @Test
    void everyHandshakeIsRefusedAndAQueryOverHttpStillAnswers() throws Exception {
        for (String origin : new String[] {TestSocket.DASHBOARD, "http://127.0.0.1", "null"}) {
            try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", origin))) {
                assertThat(h.status()).as(origin).isEqualTo(403);
            }
        }
        try (RawHandshake h = RawHandshake.send(app.base, Map.of())) {
            assertThat(h.status()).isEqualTo(403);
        }
        Browser b = app.browser();
        b.get("/api/auth/session");

        HttpResponse<String> r = b.postJson("/api/graphql", "{\"query\": \"{ watchlist { catalog_number } }\"}");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"watchlist\"").doesNotContain("errors");
        assertThat(app.context.getBean(Connections.class).open()).isZero();
    }
}
