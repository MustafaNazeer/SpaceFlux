package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AlertsProcessorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    /** Keeps the first row per event_id, as the unique key does. */
    static final class MemoryStore implements AlertStore {
        final List<Stored> rows = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        RuntimeException failWith;

        record Stored(AlertRow row, String payload, int partition, long offset) {
        }

        @Override
        public boolean store(AlertRow row, String payload, int partition, long offset) {
            if (failWith != null) {
                throw failWith;
            }
            if (!ids.add(row.eventId())) {
                return false;
            }
            rows.add(new Stored(row, payload, partition, offset));
            return true;
        }
    }

    final MemoryStore store = new MemoryStore();
    final AlertsProcessor processor = new AlertsProcessor(TopicSchemas.fromClasspath(), store);

    static byte[] example(String file) throws Exception {
        return Files.readAllBytes(EXAMPLES.resolve(file));
    }

    static AlertsProcessor.In in(String key, byte[] value) {
        return new AlertsProcessor.In(key, value, 0, 41);
    }

    static JsonNode deadLetter(AlertsProcessor.Outcome outcome) {
        assertThat(outcome).isInstanceOf(AlertsProcessor.Outcome.DeadLetter.class);
        var m = ((AlertsProcessor.Outcome.DeadLetter) outcome).message();
        assertThat(m.topic()).isEqualTo("alerts.dlq");
        return JSON.readTree(m.value());
    }

    @Test
    void everyExampleIsStoredWithItsExactBytesAndItsSourcePosition() throws Exception {
        for (String file : List.of("valid-g-level.json", "valid-close-approach.json", "valid-screening-run.json")) {
            byte[] value = example(file);

            assertThat(processor.process(in("k", value), NOW)).isEqualTo(AlertsProcessor.Outcome.STORED);
        }

        assertThat(store.rows).hasSize(3);
        assertThat(store.rows.get(0).payload()).isEqualTo(new String(example("valid-g-level.json"),
                StandardCharsets.UTF_8));
        assertThat(store.rows.get(0).partition()).isZero();
        assertThat(store.rows.get(0).offset()).isEqualTo(41);
    }

    @Test
    void aRepeatedEventIdIsDroppedAndNotDeadLettered() throws Exception {
        processor.process(in("space_weather.G", example("valid-g-level.json")), NOW);

        assertThat(processor.process(in("space_weather.G", example("valid-g-level.json")), NOW))
                .isEqualTo(AlertsProcessor.Outcome.REPEAT);
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void anEventThatFailsTheSchemaIsDeadLetteredWithCheckSchemaAndItsKey() throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(example("valid-g-level.json"));
        e.put("kind", "solar_flare");

        JsonNode d = deadLetter(processor.process(in("space_weather.G", JSON.writeValueAsBytes(e)), NOW));

        assertThat(d.get("service").asString()).isEqualTo("query-api");
        assertThat(d.get("source_topic").asString()).isEqualTo("alerts");
        assertThat(d.get("stage").asString()).isEqualTo("validate");
        assertThat(d.get("check").asString()).isEqualTo("schema");
        assertThat(d.get("failed_at").asString()).isEqualTo("2026-10-04T12:00:00Z");
        assertThat(d.has("source_url")).isFalse();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void theDeadLetterKeepsTheRecordKey() throws Exception {
        var outcome = processor.process(in("space_weather.R", "{".getBytes(StandardCharsets.UTF_8)), NOW);

        assertThat(((AlertsProcessor.Outcome.DeadLetter) outcome).message().key()).isEqualTo("space_weather.R");
    }

    @Test
    void bytesThatAreNotUtf8AreDeadLetteredAsASchemaFailureWithTheirBytesKept() throws Exception {
        String text = new String(example("valid-g-level.json"), StandardCharsets.UTF_8);
        int at = text.indexOf("SWPC estimated planetary Kp") + 4;
        byte[] head = text.substring(0, at).getBytes(StandardCharsets.UTF_8);
        byte[] tail = text.substring(at).getBytes(StandardCharsets.UTF_8);
        // An overlong NUL, and a sequence above U+10FFFF: both are malformed UTF-8 that the JSON parser accepts.
        for (byte[] bad : List.of(new byte[] {(byte) 0xC0, (byte) 0x80},
                new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80})) {
            byte[] value = new byte[head.length + bad.length + tail.length];
            System.arraycopy(head, 0, value, 0, head.length);
            System.arraycopy(bad, 0, value, head.length, bad.length);
            System.arraycopy(tail, 0, value, head.length + bad.length, tail.length);

            JsonNode d = deadLetter(processor.process(in("space_weather.G", value), NOW));

            assertThat(d.get("check").asString()).isEqualTo("schema");
            assertThat(d.get("reason").asString()).isEqualTo("not UTF-8 text");
            assertThat(d.get("payload_encoding").asString()).isEqualTo("base64");
            assertThat(java.util.Base64.getDecoder().decode(d.get("payload").asString())).isEqualTo(value);
        }
        assertThat(store.rows).isEmpty();
    }

    @Test
    void anEmptyOrMissingValueIsDeadLettered() {
        assertThat(deadLetter(processor.process(in("k", new byte[0]), NOW)).get("check").asString())
                .isEqualTo("schema");
        assertThat(deadLetter(processor.process(in("k", null), NOW)).get("payload_bytes").asInt()).isZero();
    }

    @Test
    void aValueThatDoesNotFitItsColumnIsDeadLetteredWithNoCheck() throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(example("valid-g-level.json"));
        e.put("rules_version", 4_294_967_296L);
        e.put("event_id", "space_weather_level/4294967296/G/x");

        JsonNode d = deadLetter(processor.process(in("k", JSON.writeValueAsBytes(e)), NOW));

        assertThat(d.has("check")).isFalse();
        assertThat(d.get("reason").asString()).contains("rules_version 4294967296 is above 4294967295");
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aValueTheDatabaseRefusesIsDeadLetteredWithNoCheck() throws Exception {
        store.failWith = new NotStorable("the database refused a value: error 1406");

        JsonNode d = deadLetter(processor.process(in("k", example("valid-g-level.json")), NOW));

        assertThat(d.has("check")).isFalse();
        assertThat(d.get("reason").asString()).isEqualTo("the database refused a value: error 1406");
    }

    @Test
    void aDatabaseOutageIsThrownSoTheRecordIsRetriedNotDeadLettered() throws Exception {
        store.failWith = new DataAccessResourceFailureException("connection refused");

        assertThatThrownBy(() -> processor.process(in("k", example("valid-g-level.json")), NOW))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
