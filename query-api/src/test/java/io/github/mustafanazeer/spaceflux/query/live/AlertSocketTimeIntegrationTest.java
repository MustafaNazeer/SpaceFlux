package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.tomcat.websocket.server.WsServerContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;

/** The time limits of ADR 0014 decision 7, with every value lowered so the tests run in seconds. */
class AlertSocketTimeIntegrationTest {

    static OperatorApp app;
    static final List<TestSocket> opened = new ArrayList<>();

    @BeforeAll
    static void start() {
        app = new OperatorApp("--SPACEFLUX_DASHBOARD_ORIGIN=" + TestSocket.DASHBOARD,
                "--spring.graphql.websocket.connection-init-timeout=500ms",
                "--spring.graphql.websocket.keep-alive=1s",
                "--spaceflux.websocket.read-idle-timeout=3s",
                "--spaceflux.websocket.max-lifetime=15s",
                "--spaceflux.websocket.messages-per-window=1000");
        // Tomcat checks idle sessions every tenth background pass by default; every pass keeps the test short.
        ServletContext servlet = ((org.springframework.web.context.WebApplicationContext) app.context)
                .getServletContext();
        ((WsServerContainer) servlet.getAttribute(ServerContainer.class.getName())).setProcessPeriod(1);
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
        TestSocket s = TestSocket.open(app.base, TestSocket.DASHBOARD);
        opened.add(s);
        return s;
    }

    @Test
    void aSocketThatNeverSendsConnectionInitIsClosedWith4408AndGivesItsSlotBack() throws Exception {
        TestSocket s = open();

        assertThat(s.closed(Duration.ofSeconds(5))).isEqualTo(4408);
        await().atMost(Duration.ofSeconds(5)).until(() -> connections().open() == 0);
    }

    @Test
    void aClientThatNeverAnswersPingsIsClosedWith1001AndOneThatAnswersStaysOpen() throws Exception {
        TestSocket silent = open();
        silent.answerPings = false;
        silent.init();
        TestSocket healthy = open().init();
        long start = System.nanoTime();

        int code = silent.closed(Duration.ofSeconds(10));

        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(code).isEqualTo(1001);
        assertThat(ms).isBetween(2_500L, 7_000L);
        assertThat(silent.pings).isGreaterThanOrEqualTo(2);
        Thread.sleep(2_000);
        assertThat(healthy.closeCode).isNotDone();
        assertThat(connections().open()).isEqualTo(1);
    }

    @Test
    void aConnectionIsClosedWith1001AtItsMaximumLifetime() throws Exception {
        TestSocket s = open().init();
        long start = System.nanoTime();

        int code = s.closed(Duration.ofSeconds(25));

        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(code).isEqualTo(1001);
        assertThat(ms).isBetween(14_000L, 18_000L);
    }
}
