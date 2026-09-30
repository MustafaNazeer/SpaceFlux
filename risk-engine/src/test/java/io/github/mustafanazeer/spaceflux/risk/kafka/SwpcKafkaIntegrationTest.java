package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The risk engine against a real broker: raw.swpc records in, alerts events and dead letters out, and the consumer's
 * offset committed only after both were written. Same broker image as the ingest integration tests.
 */
@SpringBootTest(properties = "spaceflux.swpc.enabled=true")
class SwpcKafkaIntegrationTest {

    static final String IMAGE =
            "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("apache/kafka"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXAMPLES = Path.of("..", "schemas", "raw.swpc", "examples");

    static {
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of("raw.swpc", "raw.swpc.dlq", "alerts", "alerts.dlq").stream()
                    .map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static byte[] event(String example, String field, Object value) throws IOException {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(example)));
        ObjectNode r = (ObjectNode) e.get("record");
        if (value == null) {
            r.remove(field);
        } else if (value instanceof Double d) {
            r.put(field, d);
        } else {
            r.put(field, value.toString());
        }
        if (example.contains("xrays")) {
            r.put("energy", "0.1-0.8nm");
        }
        e.put("fetched_at", Instant.now().toString());
        return JSON.writeValueAsBytes(e);
    }

    private static List<ConsumerRecord<String, byte[]>> read(String topic, int count) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + topic);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> c =
                new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
            while (out.size() < count && Instant.now().isBefore(deadline)) {
                c.poll(Duration.ofMillis(500)).forEach(out::add);
            }
        }
        return out;
    }

    @Test
    void recordsInBecomeAlertsAndDeadLettersOutAndTheOffsetIsCommitted() throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, byte[]> producer =
                new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer())) {
            String now = Instant.now().minus(Duration.ofMinutes(3)).toString().substring(0, 17) + "00Z";
            producer.send(new ProducerRecord<>("raw.swpc", "swpc.goes.xrays",
                    event("valid-goes-xrays.json", "time_tag", now))).get();
            producer.send(new ProducerRecord<>("raw.swpc", "swpc.kp", event("valid-kp.json", "Kp", null))).get();
            producer.send(new ProducerRecord<>("raw.swpc", "swpc.goes.xrays",
                    event("valid-goes-xrays.json", "flux", 0.5))).get();
        }

        List<ConsumerRecord<String, byte[]>> alerts = read("alerts", 1);
        List<ConsumerRecord<String, byte[]>> dead = read("raw.swpc.dlq", 2);

        assertThat(alerts).isNotEmpty();
        assertThat(alerts.get(0).key()).isEqualTo("space_weather.R");
        JsonNode first = JSON.readTree(alerts.get(0).value());
        assertThat(first.get("kind").asString()).isEqualTo("space_weather_level");
        assertThat(dead).extracting(r -> JSON.readTree(r.value()).get("check").asString())
                .containsExactlyInAnyOrder("schema", "rule");
        assertThat(dead).allSatisfy(r -> assertThat(JSON.readTree(r.value()).get("service").asString())
                .isEqualTo("risk-engine"));

        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            long committed = -1;
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (committed < 3 && Instant.now().isBefore(deadline)) {
                var offsets = admin.listConsumerGroupOffsets("risk-engine").partitionsToOffsetAndMetadata().get();
                var o = offsets.get(new TopicPartition("raw.swpc", 0));
                committed = o == null ? -1 : o.offset();
                Thread.sleep(200);
            }
            assertThat(committed).isEqualTo(3);
        }
    }
}
