package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.OperatorApp;

/** The service wide connection cap, lowered below the per address one so a single test address reaches it. */
class AlertSocketCapIntegrationTest {

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp("--SPACEFLUX_DASHBOARD_ORIGIN=" + TestSocket.DASHBOARD,
                "--spaceflux.websocket.connections=2");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @Test
    void theConnectionOverTheServiceCapGets503WithRetryAfterBeforeTheUpgrade() throws Exception {
        List<TestSocket> two = new ArrayList<>();
        two.add(TestSocket.open(app.base, TestSocket.DASHBOARD));
        two.add(TestSocket.open(app.base, TestSocket.DASHBOARD));

        try (RawHandshake h = RawHandshake.send(app.base, Map.of("Origin", TestSocket.DASHBOARD))) {
            assertThat(h.status()).isEqualTo(503);
            assertThat(h.header("Retry-After")).isEqualTo("30");
            assertThat(h.header("Content-Type")).isEqualTo("application/problem+json");
        }

        two.forEach(TestSocket::close);
    }
}
