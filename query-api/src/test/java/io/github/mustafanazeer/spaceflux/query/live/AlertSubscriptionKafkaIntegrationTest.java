package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * From the alerts topic to a subscriber: only an alert that was stored, that the recent list shows, once, however
 * often its record arrives.
 */
class AlertSubscriptionKafkaIntegrationTest {

    static final String KAFKA_IMAGE =
            "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));

    static ConfigurableApplicationContext app;
    static String base;

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("alerts", 1, (short) 1), new NewTopic("alerts.dlq", 1, (short) 1)))
                    .all().get();
        }
        TestMysql.start();
        // OperatorApp turns the consumers off, and a repeated argument would not turn this one back on.
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.SERVLET)
                .run(TestMysql.args("--server.port=0", "--SPACEFLUX_DASHBOARD_ORIGIN=" + TestSocket.DASHBOARD,
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--spaceflux.alerts.enabled=true", "--spaceflux.catalog.enabled=false"));
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        KAFKA.stop();
    }

    static void send(byte[] value) throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, byte[]> producer =
                new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>("alerts", "k", value)).get();
        }
    }

    static byte[] bytes(ObjectNode event) {
        return Alerts.JSON.writeValueAsBytes(event);
    }

    @Test
    void onlyStoredListedAlertsArriveEachOnceInTheOrderTheyWereStored() throws Exception {
        TestSocket s = TestSocket.open(base, TestSocket.DASHBOARD).init();
        s.subscribe("1", AlertSocketIntegrationTest.NOTICE);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> app.getBean(AlertNotices.class).subscribers() == 1);
        ObjectNode first = Alerts.approach();
        ObjectNode second = Alerts.approach();
        ObjectNode broken = Alerts.approach();
        broken.put("kind", "solar_flare");

        send(bytes(first));
        send(bytes(first));
        send(bytes(Alerts.run()));
        send(bytes(broken));
        send("{".getBytes());
        send(bytes(second));

        JsonNode a = s.next(Duration.ofSeconds(60));
        JsonNode b = s.next(Duration.ofSeconds(60));
        assertThat(a.get("payload").get("data").get("alert_stored").get("event_id").asString())
                .isEqualTo(first.get("event_id").asString());
        assertThat(b.get("payload").get("data").get("alert_stored").get("event_id").asString())
                .isEqualTo(second.get("event_id").asString());
        assertThat(s.quiet(Duration.ofSeconds(2))).isTrue();
        s.close();
    }
}
