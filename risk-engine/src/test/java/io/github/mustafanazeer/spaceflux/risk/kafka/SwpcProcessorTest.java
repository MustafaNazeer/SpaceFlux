package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** From raw.swpc records to alerts events and dead letters, without a broker. */
class SwpcProcessorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXAMPLES = Path.of("..", "schemas", "raw.swpc", "examples");
    private final SwpcProcessor processor = new SwpcProcessor(TopicSchemas.fromClasspath());

    private static SwpcProcessor.In in(String key, JsonNode event) {
        return new SwpcProcessor.In(key, JSON.writeValueAsBytes(event));
    }

    private static ObjectNode example(String name) throws IOException {
        return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(name)));
    }

    private static ObjectNode kp(String timeTag, double kp) throws IOException {
        ObjectNode e = example("valid-kp.json");
        ObjectNode r = (ObjectNode) e.get("record");
        r.put("time_tag", timeTag);
        r.put("Kp", kp);
        e.put("fetched_at", Instant.parse(timeTag + "Z").plusSeconds(3 * 3600 + 240).toString());
        return e;
    }

    private static ObjectNode xray(String timeTag, double flux) throws IOException {
        ObjectNode e = example("valid-goes-xrays.json");
        ObjectNode r = (ObjectNode) e.get("record");
        r.put("time_tag", timeTag);
        r.put("flux", flux);
        r.put("energy", "0.1-0.8nm");
        e.put("fetched_at", Instant.parse(timeTag).plusSeconds(180).toString());
        return e;
    }

    @Test
    void aKpRecordBecomesASchemaValidAlertKeyedByItsScale() throws IOException {
        ObjectNode event = kp("2026-09-20T00:00:00", 7.67);

        SwpcProcessor.Out out = processor.process(List.of(in("swpc.kp", event)),
                Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.deadLetters()).isEmpty();
        assertThat(out.alerts()).singleElement().satisfies(m -> {
            assertThat(m.topic()).isEqualTo("alerts");
            assertThat(m.key()).isEqualTo("space_weather.G");
            JsonNode v = JSON.readTree(m.value());
            assertThat(v.get("space_weather_level").get("derived_label").asString()).isEqualTo("G4");
            assertThat(v.get("space_weather_level").get("fetched_at").asString())
                    .isEqualTo(event.get("fetched_at").asString());
            assertThat(v.get("produced_at").asString()).isEqualTo("2026-09-20T03:10:00Z");
            assertThat(v.get("rules_version").asInt()).isEqualTo(SwpcProcessor.RULES_VERSION);
        });
    }

    @Test
    void aRecordThatFailsItsSchemaIsDeadLetteredWithCheckSchema() throws IOException {
        ObjectNode event = kp("2026-09-20T00:00:00", 2.0);
        ((ObjectNode) event.get("record")).remove("Kp");

        SwpcProcessor.Out out = processor.process(List.of(in("swpc.kp", event)), Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.alerts()).isEmpty();
        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            assertThat(m.topic()).isEqualTo("raw.swpc.dlq");
            assertThat(m.key()).isEqualTo("swpc.kp");
            JsonNode v = JSON.readTree(m.value());
            assertThat(v.get("check").asString()).isEqualTo("schema");
            assertThat(v.get("source_url").asString()).isEqualTo(event.get("source_url").asString());
        });
    }

    @Test
    void aMalformedUrlInAFailedRecordIsLeftOutOfItsDeadLetter() throws IOException {
        ObjectNode event = kp("2026-09-20T00:00:00", 2.0);
        ((ObjectNode) event.get("record")).remove("Kp");
        event.put("source_url", "https://exa mple.com/\u0000");

        SwpcProcessor.Out out = processor.process(List.of(in("swpc.kp", event)), Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.deadLetters()).singleElement()
                .satisfies(m -> assertThat(JSON.readTree(m.value()).has("source_url")).isFalse());
    }

    @Test
    void bytesThatAreNotJsonAreDeadLetteredWithoutAUrl() {
        SwpcProcessor.Out out = processor.process(
                List.of(new SwpcProcessor.In("swpc.kp", "not json".getBytes(StandardCharsets.UTF_8))),
                Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.deadLetters()).singleElement()
                .satisfies(m -> assertThat(JSON.readTree(m.value()).has("source_url")).isFalse());
    }

    @Test
    void aRuleRejectionIsDeadLetteredWithCheckRuleAndSetsNoData() throws IOException {
        List<SwpcProcessor.In> batch = List.of(in("swpc.goes.xrays", xray("2026-09-20T00:00:00Z", 2e-7)));
        processor.process(batch, Instant.parse("2026-09-20T00:03:00Z"));

        SwpcProcessor.Out out = processor.process(List.of(in("swpc.goes.xrays", xray("2026-09-20T00:01:00Z", 0.5))),
                Instant.parse("2026-09-20T00:04:00Z"));

        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            JsonNode v = JSON.readTree(m.value());
            assertThat(v.get("check").asString()).isEqualTo("rule");
            assertThat(v.get("reason").asString()).contains("above 0.2");
        });
        assertThat(out.alerts()).singleElement().satisfies(m -> assertThat(
                JSON.readTree(m.value()).get("space_weather_level").get("state").asString()).isEqualTo("no_data"));
    }

    @Test
    void aZeroFluxIsCountedNotDeadLettered() throws IOException {
        processor.process(List.of(in("swpc.goes.xrays", xray("2026-09-20T00:00:00Z", 2e-7))),
                Instant.parse("2026-09-20T00:03:00Z"));

        SwpcProcessor.Out out = processor.process(List.of(in("swpc.goes.xrays", xray("2026-09-20T00:01:00Z", 0.0))),
                Instant.parse("2026-09-20T00:04:00Z"));

        assertThat(out.deadLetters()).isEmpty();
        assertThat(out.missingValues()).isEqualTo(1);
        assertThat(out.alerts()).extracting(m -> JSON.readTree(m.value()).get("space_weather_level").get("trigger")
                .asString()).containsExactly("restatement", "level_change");
    }

    @Test
    void recordsNoScaleIsReadFromPublishNothing() throws IOException {
        SwpcProcessor.Out out = processor.process(List.of(in("swpc.alerts", example("valid-alert.json")),
                in("swpc.goes.protons", example("valid-goes-protons.json"))), Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.deadLetters()).isEmpty();
        assertThat(out.alerts()).extracting(SwpcProcessor.Message::key).doesNotContain("space_weather.G");
    }

    @Test
    void aRedeliveredBatchPublishesNothingNew() throws IOException {
        List<SwpcProcessor.In> batch = new ArrayList<>();
        batch.add(in("swpc.kp", kp("2026-09-20T00:00:00", 7.67)));
        processor.process(batch, Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(processor.process(batch, Instant.parse("2026-09-20T03:11:00Z")).alerts()).isEmpty();
    }

    @Test
    void afterARestoreARedeliveredBatchPublishesTheSameEventsAgain() throws IOException {
        processor.process(List.of(in("swpc.kp", kp("2026-09-20T00:00:00", 2.0))), Instant.parse("2026-09-20T03:10:00Z"));
        SwpcProcessor.Snapshot before = processor.snapshot();
        List<SwpcProcessor.In> batch = List.of(in("swpc.kp", kp("2026-09-20T03:00:00", 7.67)));
        List<SwpcProcessor.Message> first = processor.process(batch, Instant.parse("2026-09-20T06:10:00Z")).alerts();

        processor.restore(before);
        List<SwpcProcessor.Message> again = processor.process(batch, Instant.parse("2026-09-20T06:10:00Z")).alerts();

        assertThat(again).hasSize(1);
        assertThat(again.get(0).value()).isEqualTo(first.get(0).value());
    }

    @Test
    void theTimerPublishesNoDataWhenASeriesAges() throws IOException {
        processor.process(List.of(in("swpc.kp", kp("2026-09-20T00:00:00", 2.0))),
                Instant.parse("2026-09-20T03:10:00Z"));

        SwpcProcessor.Out out = processor.tick(Instant.parse("2026-09-20T06:31:00Z"));

        assertThat(out.alerts()).singleElement().satisfies(m -> assertThat(
                JSON.readTree(m.value()).get("space_weather_level").get("no_data_reason").asString())
                .isEqualTo("age_limit"));
    }

    @Test
    void anAlertThatFailsItsSchemaGoesToTheAlertsDeadLetterTopic() throws IOException {
        java.util.Map<String, String> files = new java.util.HashMap<>();
        for (String topic : List.of("raw.swpc", "dlq")) {
            files.put(topic, Files.readString(Path.of("..", "schemas", topic, "v1.schema.json")));
        }
        files.put("alerts", "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$id\":\""
                + TopicSchemas.ID_PREFIX + "alerts/v1.schema.json\",\"not\":{}}");
        SwpcProcessor strict = new SwpcProcessor(TopicSchemas.of(files));

        SwpcProcessor.Out out = strict.process(List.of(in("swpc.kp", kp("2026-09-20T00:00:00", 7.67))),
                Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.alerts()).isEmpty();
        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            assertThat(m.topic()).isEqualTo("alerts.dlq");
            assertThat(m.key()).isEqualTo("space_weather.G");
            JsonNode v = JSON.readTree(m.value());
            assertThat(v.get("source_topic").asString()).isEqualTo("alerts");
            assertThat(v.get("check").asString()).isEqualTo("schema");
            assertThat(v.has("source_url")).isFalse();
            assertThat(JSON.readTree(v.get("payload").asString()).get("kind").asString())
                    .isEqualTo("space_weather_level");
        });
    }

    /** The pattern allows any number of fraction digits and Java reads at most nine; such a record never vanishes. */
    @ParameterizedTest(name = "fetched_at {0}")
    @ValueSource(strings = {"2026-09-20T03:04:00.1234567890Z", "2026-09-20T24:00:00Z"})
    void aFetchedAtThatIsNotAPlainUtcTimeIsDeadLettered(String fetchedAt) throws IOException {
        ObjectNode event = kp("2026-09-20T00:00:00", 2.0);
        event.put("fetched_at", fetchedAt);

        SwpcProcessor.Out out = processor.process(List.of(in("swpc.kp", event)),
                Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.alerts()).isEmpty();
        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            JsonNode d = JSON.readTree(m.value());
            assertThat(d.get("check").asString()).isEqualTo("schema");
            assertThat(d.get("reason").asString()).contains("fetched_at");
        });
    }

    /** The parse check behind the schema's date-time format: a rule dead letter if the format check were ever lost. */
    @ParameterizedTest(name = "fetched_at {0}")
    @ValueSource(strings = {"2026-09-20T03:04:00.1234567890Z", "2026-09-20T24:00:00Z"})
    void withoutTheFormatCheckAnUnreadableFetchedAtIsARuleDeadLetter(String fetchedAt) throws IOException {
        SwpcProcessor p = new SwpcProcessor(GpProcessorTest.schemasWith("raw.swpc",
                t -> t.replace("\"format\": \"date-time\",", "")));
        ObjectNode event = kp("2026-09-20T00:00:00", 2.0);
        event.put("fetched_at", fetchedAt);

        SwpcProcessor.Out out = p.process(List.of(in("swpc.kp", event)), Instant.parse("2026-09-20T03:10:00Z"));

        assertThat(out.alerts()).isEmpty();
        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            JsonNode d = JSON.readTree(m.value());
            assertThat(d.get("check").asString()).isEqualTo("rule");
            assertThat(d.get("reason").asString()).contains("\"fetched_at\" is not a valid UTC time");
        });
    }
}
