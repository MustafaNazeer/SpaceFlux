package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The alerts consumer against a real broker and the migrated database. */
class AlertsConsumerIntegrationTest {

    static final String KAFKA_IMAGE =
            "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final TopicPartition ALERTS = new TopicPartition("alerts", 0);

    static ConfigurableApplicationContext app;
    static JdbcClient db;

    @BeforeAll
    static void start() throws Exception {
        TestMysql.start();
        KAFKA.start();
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic("alerts", 1, (short) 1), new NewTopic("alerts.dlq", 1, (short) 1)))
                    .all().get();
        }
        app = new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE)
                .run(TestMysql.args("--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--spaceflux.alerts.enabled=true", "--spaceflux.catalog.enabled=false"));
        db = app.getBean("apiJdbcClient", JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        KAFKA.stop();
    }

    static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    /** An example with a unique event_id, so each test sees only its own rows. */
    static byte[] event(String file, String tag) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
        e.put("event_id", e.get("event_id").asString() + "/" + tag);
        return JSON.writeValueAsBytes(e);
    }

    static RecordMetadata send(String key, byte[] value) throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, byte[]> producer =
                new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer())) {
            return producer.send(new ProducerRecord<>("alerts", key, value)).get();
        }
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(200);
        }
    }

    static long committed() throws Exception {
        try (Admin admin = admin()) {
            OffsetAndMetadata o = admin.listConsumerGroupOffsets("query-api-alerts").partitionsToOffsetAndMetadata()
                    .get().get(ALERTS);
            return o == null ? -1 : o.offset();
        }
    }

    static int rows(String eventId) {
        return db.sql("SELECT COUNT(*) FROM alert_event WHERE event_id = ?").param(eventId).query(Integer.class)
                .single();
    }

    static List<JsonNode> deadLetters(Predicate<JsonNode> wanted, int count) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> c =
                new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of("alerts.dlq"));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            while (out.size() < count && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(500))) {
                    JsonNode d = JSON.readTree(r.value());
                    if (wanted.test(d)) {
                        out.add(d);
                    }
                }
            }
        }
        return out;
    }

    static String id(byte[] event) {
        return JSON.readTree(event).get("event_id").asString();
    }

    @Test
    void eachEventIsStoredOnceAsReceivedAndItsOffsetIsCommittedAfterwards() throws Exception {
        byte[] level = event("valid-g-level.json", "stored");
        byte[] approach = event("valid-close-approach.json", "stored");

        RecordMetadata first = send("space_weather.G", level);
        send("2026-09-29T05:20:09Z/1", approach);
        RecordMetadata repeat = send("space_weather.G", level);

        await("the repeat's offset is committed", () -> {
            try {
                return committed() > repeat.offset();
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(rows(id(level))).isEqualTo(1);
        assertThat(rows(id(approach))).isEqualTo(1);
        var row = db.sql("SELECT kind, rules_version, source_partition, source_offset, payload, received_at "
                + "FROM alert_event WHERE event_id = ?").param(id(level)).query().singleRow();
        assertThat(row.get("kind")).isEqualTo("space_weather_level");
        assertThat(((Number) row.get("rules_version")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("source_partition")).intValue()).isZero();
        assertThat(((Number) row.get("source_offset")).longValue()).isEqualTo(first.offset());
        assertThat(row.get("payload")).isEqualTo(new String(level, StandardCharsets.UTF_8));
        assertThat(row.get("received_at")).isNotNull();
    }

    @Test
    void aSchemaFailureAndAValueThatDoesNotFitGoToTheDeadLetterTopicAndAreNotStored() throws Exception {
        ObjectNode bad = (ObjectNode) JSON.readTree(event("valid-g-level.json", "schema-fail"));
        bad.put("kind", "solar_flare");
        ObjectNode tooLong = (ObjectNode) JSON.readTree(event("valid-g-level.json", "too-long"));
        tooLong.put("event_id", "space_weather_level/1/" + "x".repeat(600));

        send("space_weather.G", JSON.writeValueAsBytes(bad));
        RecordMetadata last = send("space_weather.G", JSON.writeValueAsBytes(tooLong));

        List<JsonNode> dead = deadLetters(d -> d.get("payload").asString().contains("schema-fail")
                || d.get("payload").asString().contains("xxxxxxxxxx"), 2);
        assertThat(dead).hasSize(2).allSatisfy(d -> {
            assertThat(d.get("service").asString()).isEqualTo("query-api");
            assertThat(d.get("source_topic").asString()).isEqualTo("alerts");
        });
        assertThat(dead).anySatisfy(d -> assertThat(d.get("check").asString()).isEqualTo("schema"));
        assertThat(dead).anySatisfy(d -> {
            assertThat(d.has("check")).isFalse();
            assertThat(d.get("reason").asString()).contains("longer than the 512 its column holds");
        });
        await("the offset passes both", () -> {
            try {
                return committed() > last.offset();
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(db.sql("SELECT COUNT(*) FROM alert_event WHERE event_id LIKE '%schema-fail' OR "
                + "CHAR_LENGTH(event_id) > 512").query(Integer.class).single()).isZero();
    }

    @Test
    void aValueTheDatabaseRefusesIsReportedAsNotStorableAndLeavesNoRow() {
        AlertStore store = app.getBean(AlertStore.class);
        java.time.LocalDateTime at = java.time.LocalDateTime.of(2026, 10, 4, 0, 0);

        // Neither can come from a schema valid event; they stand in for any value MySQL refuses.
        assertThatThrownBy(() -> store.store(
                new EventRows(new AlertRow("x/1/check", "solar_flare", 1, 1, at), null, null, null), "{}", 0, 0))
                .isInstanceOf(NotStorable.class).hasMessageContaining("error 3819");
        assertThatThrownBy(() -> store.store(new EventRows(new AlertRow("x/1/" + "y".repeat(600), "screening_run", 1, 1,
                at), null, null, null), "{}", 0, 0)).isInstanceOf(NotStorable.class).hasMessageContaining("error 1406");
        assertThat(rows("x/1/check")).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aRefusedInsertIsRetriedUntilTheDatabaseAcceptsItAndIsNeverDeadLettered(CapturedOutput output)
            throws Exception {
        byte[] level = event("valid-g-level.json", "retried");
        String columns = "event_id, kind, schema_version, rules_version, produced_at, source_partition, "
                + "source_offset, payload";
        TestMysql.rootSql("REVOKE INSERT (" + columns + ") ON spaceflux.alert_event FROM 'spaceflux_consumer'@'%'");
        try {
            RecordMetadata sent = send("space_weather.G", level);
            Thread.sleep(5_000);
            assertThat(rows(id(level))).isZero();
            // The committed offset is the next one to read, so the refused record is still ahead of it.
            assertThat(committed()).isLessThanOrEqualTo(sent.offset());
        } finally {
            TestMysql.rootSql("GRANT INSERT (" + columns + ") ON spaceflux.alert_event TO 'spaceflux_consumer'@'%'");
        }

        await("the retried event is stored", () -> rows(id(level)) == 1);
        assertThat(output.getAll()).contains("alerts record 0@").contains("not stored, delivered again")
                .contains("INSERT command denied");
        assertThat(deadLetters(d -> d.get("payload").asString().contains("retried"), 1)).isEmpty();
    }
}
