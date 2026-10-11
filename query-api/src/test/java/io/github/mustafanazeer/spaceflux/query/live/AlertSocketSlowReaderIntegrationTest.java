package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.OperatorApp;

/**
 * A client that stops reading is closed once a send blocks past the send timeout (ADR 0014, decision 7), lowered
 * here, and a client that reads keeps receiving. The buffers are made large enough that only the send timeout can
 * close it; AlertNoticesTest covers the buffer.
 */
class AlertSocketSlowReaderIntegrationTest {

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp("--SPACEFLUX_DASHBOARD_ORIGIN=" + TestSocket.DASHBOARD,
                "--spaceflux.websocket.send-timeout=1s",
                "--spaceflux.websocket.subscriber-buffer=65536",
                "--spaceflux.websocket.notice-queue=65536");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static Connections connections() {
        return app.context.getBean(Connections.class);
    }

    @Test
    void aClientThatStopsReadingIsClosedByTheSendTimeoutAndDoesNotHoldUpOneThatReads() throws Exception {
        TestSocket reading = TestSocket.open(app.base, TestSocket.DASHBOARD).init();
        reading.subscribe("1", AlertSocketIntegrationTest.NOTICE);
        RawHandshake stuck = RawHandshake.send(app.base, Map.of("Origin", TestSocket.DASHBOARD), 4_096);
        assertThat(stuck.status()).isEqualTo(101);
        stuck.sendText("{\"type\":\"connection_init\"}");
        stuck.sendText("{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\""
                + AlertSocketIntegrationTest.NOTICE + "\"}}");
        await().atMost(Duration.ofSeconds(5)).until(() -> app.context.getBean(AlertNotices.class).subscribers() == 2);
        String eventId = Alerts.store(app.context, Alerts.approach());
        AlertNotices notices = app.context.getBean(AlertNotices.class);

        int sent = 0;
        while (connections().open() == 2 && sent < 20_000) {
            notices.stored(eventId);
            sent++;
            if (sent % 200 == 0) {
                Thread.sleep(50);
            }
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> connections().open() == 1);
        assertThat(reading.closeCode).isNotDone();
        int received = 0;
        while (!reading.quiet(Duration.ofMillis(500))) {
            reading.next();
            received++;
        }
        assertThat(received).isGreaterThan(0);
        String after = Alerts.store(app.context, Alerts.approach());
        notices.stored(after);
        assertThat(reading.next(Duration.ofSeconds(5)).get("payload").get("data").get("alert_stored").get("event_id")
                .asString()).isEqualTo(after);
        System.out.println("Notices published before the stuck client was closed: " + sent + ", received by the "
                + "reading client: " + received);
        stuck.close();
        reading.close();
    }
}
