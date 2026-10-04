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
import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
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

        record Stored(EventRows rows, String payload, int partition, long offset) {
        }

        @Override
        public boolean store(EventRows stored, String payload, int partition, long offset) {
            if (failWith != null) {
                throw failWith;
            }
            if (!ids.add(stored.envelope().eventId())) {
                return false;
            }
            rows.add(new Stored(stored, payload, partition, offset));
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
    void aRecordWithAnythingAfterItsEventIsDeadLetteredNotStored() throws Exception {
        // The API returns a stored event as its text, so a stored value must be exactly one JSON object.
        String event = new String(example("valid-close-approach.json"), StandardCharsets.UTF_8).strip();
        String forged = event.substring(0, event.length() - 1) + "},\"acknowledgement\":{\"action\":\"acknowledge\"}}";
        for (String value : List.of(event + " {}", forged)) {
            deadLetter(processor.process(in("k", value.getBytes(StandardCharsets.UTF_8)), NOW));
        }

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

    /** The example's text with one field's raw JSON value replaced, so a number keeps exactly the digits written. */
    static byte[] withRaw(String file, String field, String raw) throws Exception {
        String text = new String(example(file), StandardCharsets.UTF_8);
        String changed = text.replaceFirst("\"" + field + "\":\\s*(\"[^\"]*\"|[^,}\\s]+)",
                java.util.regex.Matcher.quoteReplacement("\"" + field + "\": " + raw));
        assertThat(changed).as("field %s present", field).isNotEqualTo(text);
        return changed.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aNumberThatADoubleWouldRoundIsDeadLetteredNotStoredRounded() throws Exception {
        for (String raw : List.of("1e-400", "0.1000000000000000000001", "123456789012345678901")) {
            JsonNode d = deadLetter(processor.process(in("k", withRaw("valid-r-level.json", "value", raw)), NOW));

            assertThat(d.has("check")).as(raw).isFalse();
            assertThat(d.get("reason").asString()).as(raw).contains("cannot be stored exactly as a DOUBLE");
        }
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aNumberADoubleHoldsExactlyIsStored() throws Exception {
        for (String raw : List.of("4.9E-324", "1.7976931348623157E308", "5", "7.670")) {
            assertThat(processor.process(in("k", withRaw("valid-r-level.json", "value", raw)), NOW))
                    .as(raw).isEqualTo(AlertsProcessor.Outcome.STORED);
            store.ids.clear();
        }
    }

    @Test
    void aRulesVersionThatIsNotAWholeNumberIsDeadLettered() throws Exception {
        JsonNode d = deadLetter(processor.process(
                in("k", withRaw("valid-g-level.json", "rules_version", "1.0000000000000000001")), NOW));

        assertThat(d.has("check")).isFalse();
        assertThat(d.get("reason").asString()).contains("is not a whole number");
    }

    @Test
    void aSeriesTimeMoreThanAnHourAfterTheClockIsDeadLetteredByRule() throws Exception {
        String late = "\"2026-10-04T13:00:00.000001Z\"";
        for (String[] c : List.of(new String[] {"valid-g-level.json", "freshness_reference"},
                new String[] {"valid-g-level.json", "interval_start"},
                new String[] {"valid-r-level.json", "sample_time"},
                new String[] {"valid-r-no-data.json", "no_data_since"})) {
            JsonNode d = deadLetter(processor.process(in("k", withRaw(c[0], c[1], late)), NOW));

            assertThat(d.get("check").asString()).as(c[1]).isEqualTo("rule");
            assertThat(d.get("reason").asString()).as(c[1]).isEqualTo(c[1]
                    + " 2026-10-04T13:00:00.000001Z is more than 1 hour after 2026-10-04T12:00:00Z, when it was read");
        }
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aRunWindowOrInputTimeMoreThanAnHourAfterTheClockIsDeadLetteredByRule() throws Exception {
        String late = "\"2026-10-04T13:00:00.000001Z\"";
        for (String[] c : List.of(new String[] {"valid-screening-run.json", "window_start"},
                new String[] {"valid-screening-run.json", "input_fetched_at"},
                new String[] {"valid-close-approach.json", "window_start"})) {
            JsonNode d = deadLetter(processor.process(in("k", withRaw(c[0], c[1], late)), NOW));

            assertThat(d.get("check").asString()).as(c[0] + " " + c[1]).isEqualTo("rule");
            assertThat(d.get("reason").asString()).as(c[1]).startsWith(c[1] + " 2026-10-04T13:00:00.000001Z is more");
        }
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aCloseApproachTimeDaysAheadIsStored() throws Exception {
        assertThat(processor.process(in("k", withRaw("valid-close-approach.json", "time_of_closest_approach",
                "\"2026-10-10T00:00:00Z\"")), NOW)).isEqualTo(AlertsProcessor.Outcome.STORED);
    }

    @Test
    void aSeriesTimeExactlyAnHourAfterTheClockIsStored() throws Exception {
        assertThat(processor.process(in("k", withRaw("valid-r-level.json", "freshness_reference",
                "\"2026-10-04T13:00:00Z\"")), NOW)).isEqualTo(AlertsProcessor.Outcome.STORED);
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
