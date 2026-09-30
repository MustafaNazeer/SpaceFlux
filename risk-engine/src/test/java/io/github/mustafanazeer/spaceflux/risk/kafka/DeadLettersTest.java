package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Dead letters the risk engine writes: dlq v1 events built the same way ingest builds them (docs/data/topics.md). */
class DeadLettersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-30T21:00:00Z");
    private static final String URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";
    private final DeadLetters dlq = new DeadLetters(TopicSchemas.fromClasspath());

    private static JsonNode value(DeadLetters.Message m) {
        return JSON.readTree(m.value());
    }

    @Test
    void aRuleRejectionIsAValidateDeadLetterOnTheSourceTopic() {
        byte[] payload = "{\"record\":{\"Kp\":12.0}}".getBytes(StandardCharsets.UTF_8);

        DeadLetters.Message m = dlq.build("raw.swpc", URL, "swpc.kp", "rule", "\"Kp\" 12.0 is above 9.00", payload, NOW);

        assertThat(m.topic()).isEqualTo("raw.swpc.dlq");
        assertThat(m.key()).isEqualTo("swpc.kp");
        JsonNode v = value(m);
        assertThat(v.get("schema_version").asInt()).isEqualTo(1);
        assertThat(v.get("service").asString()).isEqualTo("risk-engine");
        assertThat(v.get("stage").asString()).isEqualTo("validate");
        assertThat(v.get("check").asString()).isEqualTo("rule");
        assertThat(v.get("source_url").asString()).isEqualTo(URL);
        assertThat(v.get("failed_at").asString()).isEqualTo("2026-09-30T21:00:00Z");
        assertThat(v.get("payload").asString()).isEqualTo("{\"record\":{\"Kp\":12.0}}");
        assertThat(v.get("payload_encoding").asString()).isEqualTo("utf-8");
        assertThat(v.get("payload_bytes").asInt()).isEqualTo(payload.length);
        assertThat(v.has("payload_truncated")).isFalse();
    }

    @Test
    void aFailureWithNoUrlOrKeyLeavesThemOut() {
        DeadLetters.Message m = dlq.build("raw.swpc", null, null, "schema", "bad", new byte[] {'x'}, NOW);

        assertThat(m.key()).isNull();
        assertThat(value(m).has("source_url")).isFalse();
    }

    @Test
    void aLargePayloadKeepsItsFirst256KibibytesOnACharacterBoundary() {
        byte[] big = ("{\"a\":\"" + "é".repeat(200_000) + "\"}").getBytes(StandardCharsets.UTF_8);

        JsonNode v = value(dlq.build("raw.swpc", URL, "swpc.kp", "schema", "too big", big, NOW));

        assertThat(v.get("payload_truncated").asBoolean()).isTrue();
        assertThat(v.get("payload_bytes").asInt()).isEqualTo(big.length);
        byte[] kept = v.get("payload").asString().getBytes(StandardCharsets.UTF_8);
        assertThat(kept.length).isLessThanOrEqualTo(256 << 10);
        assertThat(kept).isEqualTo(Arrays.copyOf(big, kept.length));
    }

    @Test
    void bytesThatAreNotUtf8AreStoredAsBase64() {
        byte[] bad = {'{', (byte) 0xff, (byte) 0xfe, '}'};

        JsonNode v = value(dlq.build("raw.swpc", URL, "swpc.kp", "schema", "not UTF-8", bad, NOW));

        assertThat(v.get("payload_encoding").asString()).isEqualTo("base64");
        assertThat(Base64.getDecoder().decode(v.get("payload").asString())).isEqualTo(bad);
    }

    @Test
    void aPayloadThatEscapingWouldInflateFallsBackToBase64() {
        byte[] controls = "\u0001".repeat(250_000).getBytes(StandardCharsets.UTF_8);

        DeadLetters.Message m = dlq.build("raw.swpc", URL, "swpc.kp", "schema", "controls", controls, NOW);

        assertThat(value(m).get("payload_encoding").asString()).isEqualTo("base64");
        assertThat(m.value().length).isLessThan(900_000);
    }

    @Test
    void theReasonIsCappedAtFourKibibytes() {
        JsonNode v = value(dlq.build("raw.swpc", URL, "swpc.kp", "rule", "r".repeat(10_000), new byte[] {'x'}, NOW));

        assertThat(v.get("reason").asString().length()).isEqualTo(4096);
    }
}
