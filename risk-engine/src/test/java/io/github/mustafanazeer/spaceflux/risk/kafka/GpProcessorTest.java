package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** From raw.gp element sets to screening runs on the alerts topic, without a broker. */
class GpProcessorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json";
    private static final Instant FETCHED = Instant.parse("2026-09-27T05:00:00Z");

    private final GpProcessor processor = new GpProcessor(TopicSchemas.fromClasspath(), Set.of(25544),
            OrekitData.utc());

    private static List<GpProcessor.In> stations(Instant fetchedAt) throws IOException {
        List<GpProcessor.In> out = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(
                GpProcessorTest.class.getResourceAsStream("/celestrak/gp-stations.json"))) {
            for (JsonNode gp : JSON.readTree(in)) {
                out.add(in(gp, fetchedAt));
            }
        }
        return out;
    }

    private static GpProcessor.In in(JsonNode gp, Instant fetchedAt) {
        ObjectNode e = JSON.createObjectNode();
        e.put("schema_version", 1);
        e.put("source", "celestrak");
        e.put("fetched_at", fetchedAt.toString());
        e.put("source_url", URL);
        e.set("gp", gp);
        return new GpProcessor.In(gp.get("NORAD_CAT_ID").asString(), JSON.writeValueAsBytes(e));
    }

    private static List<String> kinds(GpProcessor.Out out) {
        return out.alerts().stream().map(m -> JSON.readTree(m.value()).get("kind").asString()).toList();
    }

    @Test
    void aBatchIsScreenedOnceNoRecordHasArrivedForThirtySeconds() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        assertThat(processor.poll(FETCHED.plusSeconds(89)).alerts()).isEmpty();
        GpProcessor.Out out = processor.poll(FETCHED.plusSeconds(90));

        assertThat(kinds(out)).last().isEqualTo("screening_run");
        assertThat(out.alerts()).allSatisfy(m -> assertThat(m.key()).isEqualTo("2026-09-27T05:00:00Z/1"));
        JsonNode run = JSON.readTree(out.alerts().get(out.alerts().size() - 1).value()).get("screening_run");
        assertThat(run.get("window_start").asString()).isEqualTo("2026-09-27T05:00:00Z");
        assertThat(run.get("coverage").get("watchlist_accepted").asInt()).isEqualTo(1);
        assertThat(run.get("coverage").get("catalog_admitted").asInt()).isEqualTo(22);
    }

    @Test
    void aPublishedRunIsNotRepeatedButAnUnpublishedOneIs() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        GpProcessor.Out first = processor.poll(FETCHED.plusSeconds(90));

        GpProcessor.Out again = processor.poll(FETCHED.plusSeconds(120));
        assertThat(again.alerts()).extracting(GpProcessor.Message::value)
                .containsExactlyElementsOf(first.alerts().stream().map(GpProcessor.Message::value).toList());

        processor.published(again.runId());
        assertThat(processor.poll(FETCHED.plusSeconds(150)).alerts()).isEmpty();
    }

    @Test
    void recordsAlreadyHeldDoNotStartANewRun() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        processor.published(processor.poll(FETCHED.plusSeconds(90)).runId());

        processor.accept(stations(FETCHED), FETCHED.plusSeconds(200));

        assertThat(processor.poll(FETCHED.plusSeconds(300)).alerts()).isEmpty();
    }

    @Test
    void aRepeatedFetchWithNoNewerElementSetKeepsTheEarlierWindowStart() throws IOException {
        List<GpProcessor.In> replay = new ArrayList<>(stations(FETCHED));
        replay.addAll(stations(FETCHED.plusSeconds(7_800)));
        processor.accept(replay, FETCHED.plusSeconds(7_860));

        assertThat(processor.poll(FETCHED.plusSeconds(7_890)).alerts())
                .allSatisfy(m -> assertThat(m.key()).startsWith(FETCHED.toString()));
    }

    /** The later fetch carries a synthetic newer ISS epoch, since no recording holds two fetches. */
    @Test
    void aReplayOfSeveralBatchesScreensOnlyTheNewest() throws IOException {
        Instant later = FETCHED.plusSeconds(7_800);
        List<GpProcessor.In> replay = new ArrayList<>(stations(FETCHED));
        for (GpProcessor.In in : stations(later)) {
            ObjectNode e = (ObjectNode) JSON.readTree(in.value());
            if (e.get("gp").get("NORAD_CAT_ID").asInt() == 25544) {
                ((ObjectNode) e.get("gp")).put("EPOCH", "2026-09-27T06:00:00.000000");
                replay.add(new GpProcessor.In(in.key(), JSON.writeValueAsBytes(e)));
            }
        }
        processor.accept(replay, later.plusSeconds(60));

        GpProcessor.Out out = processor.poll(later.plusSeconds(90));

        assertThat(out.alerts()).isNotEmpty();
        assertThat(out.alerts()).allSatisfy(m -> assertThat(m.key()).startsWith(later.toString()));
    }

    @Test
    void aRecordThatFailsItsSchemaIsDeadLetteredWithCheckSchema() throws IOException {
        ObjectNode gp = (ObjectNode) JSON.readTree("{\"NORAD_CAT_ID\":25544}");

        GpProcessor.Out out = processor.accept(List.of(in(gp, FETCHED)), FETCHED.plusSeconds(60));

        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            assertThat(m.topic()).isEqualTo("raw.gp.dlq");
            assertThat(m.key()).isEqualTo("25544");
            assertThat(JSON.readTree(m.value()).get("check").asString()).isEqualTo("schema");
        });
    }

    @Test
    void anElementSetOrekitCannotUseIsDeadLetteredWithCheckRule() throws IOException {
        List<GpProcessor.In> batch = stations(FETCHED);
        ObjectNode e = (ObjectNode) JSON.readTree(batch.get(0).value());
        ((ObjectNode) e.get("gp")).put("ECCENTRICITY", 1.5);

        GpProcessor.Out out = processor.accept(List.of(new GpProcessor.In(batch.get(0).key(),
                JSON.writeValueAsBytes(e))), FETCHED.plusSeconds(60));

        assertThat(out.deadLetters()).singleElement()
                .satisfies(m -> assertThat(JSON.readTree(m.value()).get("check").asString()).isEqualTo("rule"));
    }

    @Test
    void anEmptyStateScreensNothing() {
        assertThat(processor.poll(FETCHED).alerts()).isEmpty();
    }
}
