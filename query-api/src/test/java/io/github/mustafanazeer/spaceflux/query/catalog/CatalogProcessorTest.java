package io.github.mustafanazeer.spaceflux.query.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CatalogProcessorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    static final class MemoryStore implements CatalogStore {
        final List<CatalogRow> applied = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void apply(CatalogRow row) {
            if (failWith != null) {
                throw failWith;
            }
            applied.add(row);
        }
    }

    final MemoryStore store = new MemoryStore();
    final CatalogProcessor processor = new CatalogProcessor(TopicSchemas.fromClasspath(), store);

    static byte[] iss() throws Exception {
        return Files.readAllBytes(CatalogRowTest.EXAMPLE);
    }

    static CatalogProcessor.In in(byte[] value) {
        return new CatalogProcessor.In("25544", value);
    }

    static JsonNode deadLetter(CatalogProcessor.Outcome outcome) {
        assertThat(outcome).isInstanceOf(CatalogProcessor.Outcome.DeadLetter.class);
        var m = ((CatalogProcessor.Outcome.DeadLetter) outcome).message();
        assertThat(m.topic()).isEqualTo("raw.gp.dlq");
        assertThat(m.key()).isEqualTo("25544");
        return JSON.readTree(m.value());
    }

    @Test
    void aValidElementSetIsApplied() throws Exception {
        assertThat(processor.process(in(iss()), NOW)).isEqualTo(CatalogProcessor.Outcome.APPLIED);

        assertThat(store.applied).singleElement().satisfies(r -> assertThat(r.noradCatId()).isEqualTo(25544));
    }

    @Test
    void anElementSetThatFailsTheSchemaIsDeadLetteredWithItsSourceUrlWhenItHasOne() throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(iss());
        ((ObjectNode) e.get("gp")).put("INCLINATION", 181.0);

        JsonNode d = deadLetter(processor.process(in(JSON.writeValueAsBytes(e)), NOW));

        assertThat(d.get("service").asString()).isEqualTo("query-api");
        assertThat(d.get("source_topic").asString()).isEqualTo("raw.gp");
        assertThat(d.get("check").asString()).isEqualTo("schema");
        assertThat(d.has("source_url")).isFalse();
        assertThat(store.applied).isEmpty();
    }

    @Test
    void bytesThatAreNotUtf8AreDeadLetteredAsASchemaFailure() throws Exception {
        String text = new String(iss(), StandardCharsets.UTF_8);
        int at = text.indexOf("ZARYA");
        byte[] head = text.substring(0, at).getBytes(StandardCharsets.UTF_8);
        byte[] tail = text.substring(at).getBytes(StandardCharsets.UTF_8);
        byte[] value = new byte[head.length + 2 + tail.length];
        System.arraycopy(head, 0, value, 0, head.length);
        value[head.length] = (byte) 0xC0;
        value[head.length + 1] = (byte) 0x80;
        System.arraycopy(tail, 0, value, head.length + 2, tail.length);

        JsonNode d = deadLetter(processor.process(in(value), NOW));

        assertThat(d.get("check").asString()).isEqualTo("schema");
        assertThat(d.get("reason").asString()).isEqualTo("not UTF-8 text");
    }

    @Test
    void aValueThatDoesNotFitIsDeadLetteredWithNoCheckAndTheSourceUrl() throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(iss());
        ((ObjectNode) e.get("gp")).put("OBJECT_NAME", "ISS \uD800");

        JsonNode d = deadLetter(processor.process(in(JSON.writeValueAsBytes(e)), NOW));

        assertThat(d.has("check")).isFalse();
        assertThat(d.get("reason").asString()).contains("OBJECT_NAME is not well formed Unicode");
        assertThat(d.get("source_url").asString()).startsWith("https://celestrak.org/");
    }

    @Test
    void anEpochPastTheRuleIsDeadLetteredWithCheckRule() throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(iss());
        ((ObjectNode) e.get("gp")).put("EPOCH", "2030-01-01T00:00:00");

        JsonNode d = deadLetter(processor.process(in(JSON.writeValueAsBytes(e)), NOW));

        assertThat(d.get("check").asString()).isEqualTo("rule");
        assertThat(d.get("reason").asString()).startsWith("EPOCH 2030-01-01T00:00:00Z is more than 5 minutes");
    }

    @Test
    void aHugeSourceUrlStillGivesADeadLetterTheProducerAccepts() throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(iss());
        e.put("source_url", "https://celestrak.org/" + "u".repeat(950_000));

        CatalogProcessor.Outcome outcome = processor.process(in(JSON.writeValueAsBytes(e)), NOW);

        var m = ((CatalogProcessor.Outcome.DeadLetter) outcome).message();
        assertThat(m.value().length).isLessThan(1_048_576);
        JsonNode d = JSON.readTree(m.value());
        assertThat(d.has("source_url")).isFalse();
        assertThat(d.get("reason").asString()).contains("source_url left out");
    }

    @Test
    void aHugeKeyStillGivesADeadLetterThatFitsTheRequestLimitWithItsKey() throws Exception {
        String key = "9".repeat(900_000);
        byte[] value = "\u0001".repeat(30_000).getBytes(StandardCharsets.UTF_8);

        var m = ((CatalogProcessor.Outcome.DeadLetter) processor.process(new CatalogProcessor.In(key, value), NOW))
                .message();

        int keyBytes = m.key() == null ? 0 : m.key().getBytes(StandardCharsets.UTF_8).length;
        assertThat(keyBytes + m.value().length).isLessThanOrEqualTo(900_000);
        assertThat(JSON.readTree(m.value()).get("reason").asString()).contains("key left out: 900000 bytes");
    }

    @Test
    void aDatabaseOutageIsThrownSoTheRecordIsRetried() throws Exception {
        store.failWith = new DataAccessResourceFailureException("connection refused");

        assertThatThrownBy(() -> processor.process(in(iss()), NOW))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void aValueTheDatabaseRefusesIsDeadLettered() throws Exception {
        store.failWith = new NotStorable("the database refused a value: error 1406");

        JsonNode d = deadLetter(processor.process(in(iss()), NOW));

        assertThat(d.get("reason").asString()).isEqualTo("the database refused a value: error 1406");
    }
}
