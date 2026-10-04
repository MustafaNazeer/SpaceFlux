package io.github.mustafanazeer.spaceflux.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Dead letters a service writes: dlq v1 events built the same way ingest builds them (docs/data/topics.md). */
class DeadLettersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-30T21:00:00Z");
    private static final String URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";
    private final DeadLetters dlq = new DeadLetters(TopicSchemas.fromClasspath(), "risk-engine");

    private static JsonNode value(DeadLetters.Message m) {
        return JSON.readTree(m.value());
    }

    @Test
    void theDeadLetterNamesTheServiceThatBuiltIt() {
        DeadLetters queryApi = new DeadLetters(TopicSchemas.fromClasspath(), "query-api");

        DeadLetters.Message m = queryApi.build("alerts", null, "space_weather.G", "schema", "kind: not allowed",
                "{}".getBytes(StandardCharsets.UTF_8), NOW);

        assertThat(value(m).get("service").asString()).isEqualTo("query-api");
        assertThat(value(m).has("source_url")).isFalse();
    }

    @Test
    void anEmptyServiceNameIsRefused() {
        assertThatThrownBy(() -> new DeadLetters(TopicSchemas.fromClasspath(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRuleRejectionIsAValidateDeadLetterOnTheSourceTopic() {
        byte[] payload = "{\"record\":{\"Kp\":12.0}}".getBytes(StandardCharsets.UTF_8);

        DeadLetters.Message m =
                dlq.build("raw.swpc", URL, "swpc.kp", "rule", "\"Kp\" 12.0 is above 9.00", payload, NOW);

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
        byte[] big = ("{\"a\":\"" + "\u00e9".repeat(200_000) + "\"}").getBytes(StandardCharsets.UTF_8);

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
    void aSourceUrlThatWouldPushTheDeadLetterOverBudgetIsLeftOutAndNamed() {
        String url = "https://celestrak.org/" + "u".repeat(950_000);
        byte[] payload = "{\"gp\":{}}".getBytes(StandardCharsets.UTF_8);

        DeadLetters.Message m = dlq.build("raw.gp", url, "25544", null, "source_url is too long", payload, NOW);

        assertThat(m.value().length).isLessThanOrEqualTo(900_000);
        JsonNode v = value(m);
        assertThat(v.has("source_url")).isFalse();
        assertThat(v.get("reason").asString())
                .isEqualTo("source_url is too long; source_url left out: " + url.length() + " bytes");
        assertThat(v.get("payload").asString()).isEqualTo("{\"gp\":{}}");
    }

    @Test
    void aSourceUrlWithinBudgetIsKeptEvenWhenLong() {
        String url = "https://celestrak.org/" + "u".repeat(100_000);

        DeadLetters.Message m = dlq.build("raw.gp", url, "25544", null, "r", "{}".getBytes(StandardCharsets.UTF_8),
                NOW);

        assertThat(value(m).get("source_url").asString()).isEqualTo(url);
    }

    @Test
    void theLargestPayloadAndALongUrlStillFitTheBudget() {
        byte[] payload = new byte[400_000];
        java.util.Arrays.fill(payload, (byte) 0xFF);
        String url = "https://celestrak.org/" + "u".repeat(700_000);

        DeadLetters.Message m = dlq.build("raw.gp", url, "25544", "schema", "x", payload, NOW);

        assertThat(m.value().length).isLessThanOrEqualTo(900_000);
        assertThat(value(m).has("source_url")).isFalse();
        assertThat(value(m).get("payload_encoding").asString()).isEqualTo("base64");
    }

    @Test
    void aKeyThatWouldPushKeyAndValuePastTheBudgetIsLeftOutAndNamed() {
        String key = "k".repeat(900_000);
        // Control characters escape to six bytes each, so the value grows without needing the base64 fallback.
        byte[] payload = "\u0001".repeat(30_000).getBytes(StandardCharsets.UTF_8);

        DeadLetters.Message m = dlq.build("raw.gp", null, key, "schema", "not JSON", payload, NOW);

        assertThat(m.key()).isNull();
        assertThat(m.value().length).isLessThanOrEqualTo(900_000);
        assertThat(value(m).get("reason").asString()).isEqualTo("not JSON; key left out: 900000 bytes");
    }

    @Test
    void aLongKeyWithinBudgetIsKept() {
        String key = "k".repeat(100_000);

        DeadLetters.Message m = dlq.build("raw.gp", null, key, "schema", "r", "{}".getBytes(StandardCharsets.UTF_8),
                NOW);

        assertThat(m.key()).isEqualTo(key);
    }

    @Test
    void theReasonIsCappedAtFourKibibytes() {
        JsonNode v = value(dlq.build("raw.swpc", URL, "swpc.kp", "rule", "r".repeat(10_000), new byte[] {'x'}, NOW));

        assertThat(v.get("reason").asString().length()).isEqualTo(4096);
    }
}
