package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.mustafanazeer.spaceflux.risk.RiskEngineApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A restart of the risk engine reads raw.gp from the beginning into a fresh process and publishes the newest run again
 * under the same run_id and event_id (ADR 0008 decision 6). Its own broker, and one process at a time, so no other
 * member of the screening group can hold raw.gp while the restarted one starts. Before the restart the screening
 * group is given a committed offset at the end of raw.gp, so the run comes back only if the consumer seeks to the
 * beginning itself.
 */
class GpRestartIntegrationTest {

    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(RiskEngineKafkaIntegrationTest.IMAGE).asCompatibleSubstituteFor("apache/kafka"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();
        try (Admin admin = Admin.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of("raw.swpc", "raw.swpc.dlq", "raw.gp", "raw.gp.dlq", "alerts", "alerts.dlq")
                    .stream().map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get();
        }
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    /** Command line arguments, so they override application.yml (default properties would not). */
    private static ConfigurableApplicationContext riskEngine() {
        return new SpringApplicationBuilder(RiskEngineApplication.class).run(
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(), "--spaceflux.swpc.enabled=false",
                "--spaceflux.screening.enabled=true");
    }

    private static void produceStations(Instant fetched) throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, byte[]> producer =
                new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
                var in = GpRestartIntegrationTest.class.getResourceAsStream("/celestrak/gp-stations.json")) {
            for (JsonNode gp : JSON.readTree(in)) {
                ObjectNode e = JSON.createObjectNode();
                e.put("schema_version", 1);
                e.put("source", "celestrak");
                e.put("fetched_at", fetched.toString());
                e.put("source_url", "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json");
                e.set("gp", gp);
                producer.send(new ProducerRecord<>("raw.gp", gp.get("NORAD_CAT_ID").asString(),
                        JSON.writeValueAsBytes(e))).get();
            }
        }
    }

    /** Every alerts record matching {@code wanted}, read from the start, waiting up to 120 s for {@code count}. */
    private static List<ConsumerRecord<String, byte[]>> alerts(int count,
            Predicate<ConsumerRecord<String, byte[]>> wanted) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> c =
                new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of("alerts"));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
            while (out.size() < count && Instant.now().isBefore(deadline)) {
                c.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (wanted.test(r)) {
                        out.add(r);
                    }
                });
            }
        }
        return out;
    }

    @Test
    void aRestartRepublishesTheNewestRunUnderTheSameIdentity() throws Exception {
        Instant fetched = Instant.parse("2026-09-27T05:00:00Z");
        produceStations(fetched);
        String runId = fetched + "/1";
        Predicate<ConsumerRecord<String, byte[]>> isRun = r -> runId.equals(r.key())
                && JSON.readTree(r.value()).get("kind").asString().equals("screening_run");

        try (ConfigurableApplicationContext first = riskEngine()) {
            assertThat(alerts(1, isRun)).hasSize(1);
        }
        try (Admin admin = Admin.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            TopicPartition rawGp = new TopicPartition("raw.gp", 0);
            long end = admin.listOffsets(Map.of(rawGp, OffsetSpec.latest())).partitionResult(rawGp).get().offset();
            admin.alterConsumerGroupOffsets("risk-engine-screening", Map.of(rawGp, new OffsetAndMetadata(end)))
                    .all().get();
        }
        try (ConfigurableApplicationContext restarted = riskEngine()) {
            List<ConsumerRecord<String, byte[]>> runs = alerts(2, isRun);

            assertThat(runs).hasSize(2);
            assertThat(runs).extracting(r -> JSON.readTree(r.value()).get("event_id").asString())
                    .containsOnly("screening_run/1/" + runId);
        }
    }
}
