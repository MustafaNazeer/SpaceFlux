package io.github.mustafanazeer.spaceflux.query.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The catalog consumer against a real broker and the migrated database. */
class CatalogConsumerIntegrationTest {

    static final String KAFKA_IMAGE =
            "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final TopicPartition RAW_GP = new TopicPartition("raw.gp", 0);

    static ConfigurableApplicationContext app;
    static JdbcClient db;

    @BeforeAll
    static void start() throws Exception {
        TestMysql.start();
        KAFKA.start();
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic("raw.gp", 1, (short) 1), new NewTopic("raw.gp.dlq", 1, (short) 1)))
                    .all().get();
        }
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args("--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=true"));
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

    static ObjectNode iss(long norad) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(CatalogRowTest.EXAMPLE));
        ((ObjectNode) e.get("gp")).put("NORAD_CAT_ID", norad);
        return e;
    }

    static RecordMetadata send(String key, byte[] value) throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, byte[]> producer =
                new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer())) {
            return producer.send(new ProducerRecord<>("raw.gp", key, value)).get();
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

    static long committed() {
        try (Admin admin = admin()) {
            OffsetAndMetadata o = admin.listConsumerGroupOffsets("query-api-catalog").partitionsToOffsetAndMetadata()
                    .get().get(RAW_GP);
            return o == null ? -1 : o.offset();
        } catch (Exception e) {
            return -1;
        }
    }

    static List<JsonNode> deadLetters() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> c =
                new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of("raw.gp.dlq"));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(500))) {
                    out.add(JSON.readTree(r.value()));
                }
            }
        }
        return out;
    }

    @Test
    void anElementSetIsCatalogedAndABadOneDeadLetteredBeforeTheOffsetMovesOn() throws Exception {
        long good = 920_000_001L;
        ObjectNode bad = iss(920_000_002L);
        ((ObjectNode) bad.get("gp")).put("INCLINATION", 181.0);

        send(Long.toString(good), JSON.writeValueAsBytes(iss(good)));
        RecordMetadata last = send("920000002", JSON.writeValueAsBytes(bad));

        await("both offsets are committed", () -> committed() > last.offset());
        assertThat(db.sql("SELECT object_name FROM catalog_object WHERE norad_cat_id = ?").param(good)
                .query(String.class).single()).isEqualTo("ISS (ZARYA)");
        assertThat(db.sql("SELECT COUNT(*) FROM catalog_object WHERE norad_cat_id = 920000002").query(Integer.class)
                .single()).isZero();
        assertThat(deadLetters()).anySatisfy(d -> {
            assertThat(d.get("service").asString()).isEqualTo("query-api");
            assertThat(d.get("source_topic").asString()).isEqualTo("raw.gp");
            assertThat(d.get("check").asString()).isEqualTo("schema");
        });
    }
}
