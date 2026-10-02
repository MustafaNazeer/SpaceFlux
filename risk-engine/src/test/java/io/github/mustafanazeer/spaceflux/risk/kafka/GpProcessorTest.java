package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
    void anElementSetOrekitCannotUseIsDeadLetteredWithoutACheck() throws IOException {
        List<GpProcessor.In> batch = stations(FETCHED);
        ObjectNode e = (ObjectNode) JSON.readTree(batch.get(0).value());
        ((ObjectNode) e.get("gp")).put("ECCENTRICITY", 1.5);

        GpProcessor.Out out = processor.accept(List.of(new GpProcessor.In(batch.get(0).key(),
                JSON.writeValueAsBytes(e))), FETCHED.plusSeconds(60));

        assertThat(out.deadLetters()).singleElement()
                .satisfies(m -> assertThat(JSON.readTree(m.value()).has("check")).isFalse());
    }

    @Test
    void anEmptyStateScreensNothing() {
        assertThat(processor.poll(FETCHED).alerts()).isEmpty();
    }

    private static JsonNode runOf(GpProcessor.Out out) {
        return JSON.readTree(out.alerts().get(out.alerts().size() - 1).value()).get("screening_run");
    }

    private static List<GpProcessor.In> withIss(Instant fetchedAt, java.util.function.Consumer<ObjectNode> change)
            throws IOException {
        List<GpProcessor.In> out = new ArrayList<>();
        for (GpProcessor.In in : stations(fetchedAt)) {
            ObjectNode e = (ObjectNode) JSON.readTree(in.value());
            if (e.get("gp").get("NORAD_CAT_ID").asInt() == 25544) {
                change.accept((ObjectNode) e.get("gp"));
                out.add(new GpProcessor.In(in.key(), JSON.writeValueAsBytes(e)));
            }
        }
        return out;
    }

    @Test
    void anEventFetchedAfterTheEnginesClockIsRejectedAndChangesNothing() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        Instant future = Instant.parse("2300-01-01T00:00:00Z");

        GpProcessor.Out out = processor.accept(stations(future).subList(0, 1), FETCHED.plusSeconds(61));

        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            JsonNode v = JSON.readTree(m.value());
            assertThat(v.get("check").asString()).isEqualTo("rule");
            assertThat(v.get("reason").asString()).contains("clock");
        });
        assertThat(runOf(processor.poll(FETCHED.plusSeconds(91))).get("window_start").asString())
                .isEqualTo(FETCHED.toString());
    }

    /** Synthetic: the recorded ISS element set with its epoch moved into the future. */
    @Test
    void anElementSetDatedAfterItsFetchIsRejectedAndDoesNotReplaceTheHeldOne() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        GpProcessor.Out out = processor.accept(withIss(FETCHED.plusSeconds(3_600),
                gp -> gp.put("EPOCH", "2300-01-01T00:00:00.000000")), FETCHED.plusSeconds(3_660));

        assertThat(out.deadLetters()).singleElement().satisfies(m -> assertThat(
                JSON.readTree(m.value()).get("reason").asString()).contains("EPOCH").contains("after"));
        JsonNode run = runOf(processor.poll(FETCHED.plusSeconds(3_700)));
        assertThat(run.get("coverage").get("watchlist_accepted").asInt()).isEqualTo(1);
        assertThat(run.get("window_start").asString()).isEqualTo(FETCHED.toString());
    }

    /** Synthetic: a second copy of the recorded ISS element set with the same epoch and another mean motion. */
    @Test
    void aSameEpochCopyWithOtherElementsIsListedAsADifferingCopy() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        processor.accept(withIss(FETCHED.plusSeconds(60), gp -> gp.put("MEAN_MOTION", 15.4)),
                FETCHED.plusSeconds(120));

        JsonNode run = runOf(processor.poll(FETCHED.plusSeconds(150)));
        assertThat(run.get("differing_copies")).singleElement().satisfies(d -> {
            assertThat(d.get("catalog_number").asInt()).isEqualTo(25544);
            assertThat(d.get("elements_differ").asBoolean()).isTrue();
        });
        assertThat(run.get("window_start").asString()).isEqualTo(FETCHED.plusSeconds(60).toString());
    }

    @Test
    void anIdenticalCopyIsNotADifferingCopy() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        processor.published(processor.poll(FETCHED.plusSeconds(90)).runId());

        processor.accept(withIss(FETCHED.plusSeconds(600), gp -> { }), FETCHED.plusSeconds(660));

        assertThat(processor.poll(FETCHED.plusSeconds(700)).alerts()).isEmpty();
    }

    @Test
    void aWatchlistObjectMissingFromTheInputIsListedAsNotInInput() throws IOException {
        GpProcessor p = new GpProcessor(TopicSchemas.fromClasspath(), Set.of(25544, 99999), OrekitData.utc());
        p.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        JsonNode run = runOf(p.poll(FETCHED.plusSeconds(90)));

        assertThat(run.get("rejected")).anySatisfy(r -> {
            assertThat(r.get("catalog_number").asInt()).isEqualTo(99999);
            assertThat(r.get("role").asString()).isEqualTo("watchlist");
            assertThat(r.get("code").asString()).isEqualTo("not_in_input");
        });
    }

    @Test
    void elementSetsMoreThanThirtyDaysOlderThanTheWindowAreDropped() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        Instant muchLater = FETCHED.plusSeconds(40L * 86_400);

        processor.accept(withIss(muchLater, gp -> gp.put("EPOCH", muchLater.minusSeconds(3_600).toString()
                .replace("Z", ".000000"))), muchLater.plusSeconds(60));

        JsonNode run = runOf(processor.poll(muchLater.plusSeconds(90)));
        assertThat(run.get("coverage").get("catalog_admitted").asInt()).isEqualTo(1);
        assertThat(run.get("rejected")).isEmpty();
    }

    @Test
    void aWrittenRunIsNeverProducedAgainAndALateElementSetWaitsForTheNextFetch() throws IOException {
        List<GpProcessor.In> all = stations(FETCHED);
        processor.accept(all.subList(0, 21), FETCHED.plusSeconds(60));
        String runId = processor.poll(FETCHED.plusSeconds(90)).runId();
        processor.published(runId);

        processor.accept(all.subList(21, 22), FETCHED.plusSeconds(200));
        assertThat(processor.poll(FETCHED.plusSeconds(300)).alerts()).isEmpty();

        processor.accept(withIss(FETCHED.plusSeconds(7_800), gp -> gp.put("EPOCH", "2026-09-27T06:00:00.000000")),
                FETCHED.plusSeconds(7_860));
        GpProcessor.Out next = processor.poll(FETCHED.plusSeconds(7_900));
        assertThat(next.runId()).isNotEqualTo(runId).startsWith(FETCHED.plusSeconds(7_800).toString());
        assertThat(runOf(next).get("coverage").get("catalog_admitted").asInt()).isEqualTo(22);
    }

    /**
     * Synthetic: a same epoch copy with a B* no line can hold. Comparing it must not format lines, which Orekit refuses
     * for such values; the copy is listed and the run is written.
     */
    @Test
    void aSameEpochCopyWhoseElementsCannotBeWrittenAsLinesIsListedWithoutStoppingTheRun() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        GpProcessor.Out out = processor.accept(withIss(FETCHED.plusSeconds(60), gp -> gp.put("BSTAR", 1e30)),
                FETCHED.plusSeconds(120));

        assertThat(out.deadLetters()).isEmpty();
        assertThat(runOf(processor.poll(FETCHED.plusSeconds(150))).get("differing_copies")).hasSize(1);
    }

    /** Synthetic: a same epoch copy of the recorded ISS element set without OBJECT_NAME. */
    @Test
    void aDifferingCopyWithoutANameStillGivesASchemaValidRun() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        processor.accept(withIss(FETCHED.plusSeconds(60), gp -> gp.remove("OBJECT_NAME")),
                FETCHED.plusSeconds(120));
        GpProcessor.Out out = processor.poll(FETCHED.plusSeconds(150));

        assertThat(out.deadLetters()).isEmpty();
        assertThat(runOf(out).get("differing_copies")).singleElement()
                .satisfies(d -> assertThat(d.has("dropped_name")).isFalse());
    }

    @Test
    void aLateElementSetForAWrittenRunIsLoggedOnceAndNotRecomputed() throws IOException {
        List<GpProcessor.In> all = stations(FETCHED);
        processor.accept(all.subList(0, 21), FETCHED.plusSeconds(60));
        processor.published(processor.poll(FETCHED.plusSeconds(90)).runId());
        processor.accept(all.subList(21, 22), FETCHED.plusSeconds(200));

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(GpProcessor.class);
        logger.addAppender(logs);
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(processor.poll(FETCHED.plusSeconds(300 + 5L * i)).alerts()).isEmpty();
            }
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).hasSize(1);
    }

    /** Synthetic: a same epoch copy of the recorded ISS element set with a 200,000 code point name, 800 kB in UTF-8. */
    @Test
    void aNameLongerThanSixtyFourCodePointsIsCutOnArrival() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        processor.accept(withIss(FETCHED.plusSeconds(60), gp -> gp.put("OBJECT_NAME", "\uD83D\uDE80".repeat(200_000))),
                FETCHED.plusSeconds(120));

        String name = runOf(processor.poll(FETCHED.plusSeconds(150))).get("differing_copies").get(0)
                .get("dropped_name").asString();
        assertThat(name.codePointCount(0, name.length())).isEqualTo(64);
        assertThat(name).isEqualTo("\uD83D\uDE80".repeat(61) + "...");
    }

    /** Synthetic: five same epoch copies of the recorded ISS element set, each with another mean motion. */
    @Test
    void atMostThreeDifferingCopiesAreHeldAndLaterOnesAreCountedWithoutDelayingTheRun() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        for (int i = 0; i < 3; i++) {
            double mm = 15.40 + i / 100.0;
            processor.accept(withIss(FETCHED.plusSeconds(60), gp -> gp.put("MEAN_MOTION", mm)),
                    FETCHED.plusSeconds(120));
        }
        for (int i = 3; i < 5; i++) {
            double mm = 15.40 + i / 100.0;
            processor.accept(withIss(FETCHED.plusSeconds(60), gp -> gp.put("MEAN_MOTION", mm)),
                    FETCHED.plusSeconds(145));
        }

        JsonNode run = runOf(processor.poll(FETCHED.plusSeconds(150)));

        assertThat(run.get("differing_copies")).hasSize(3);
        assertThat(run.get("differing_copies_over_cap")).singleElement().satisfies(o -> {
            assertThat(o.get("catalog_number").asInt()).isEqualTo(25544);
            assertThat(o.get("records_not_listed").asInt()).isEqualTo(2);
            assertThat(o.get("epoch").asString()).isEqualTo(run.get("differing_copies").get(0).get("used_epoch")
                    .asString());
        });
        assertThat(run.get("omitted").get("differing_copies").asInt()).isZero();
    }

    private static List<GpProcessor.In> withObject(int norad, Instant fetchedAt,
            java.util.function.Consumer<ObjectNode> change) throws IOException {
        List<GpProcessor.In> out = new ArrayList<>();
        for (GpProcessor.In in : stations(fetchedAt)) {
            ObjectNode e = (ObjectNode) JSON.readTree(in.value());
            if (e.get("gp").get("NORAD_CAT_ID").asInt() == norad) {
                change.accept((ObjectNode) e.get("gp"));
                out.add(new GpProcessor.In(in.key(), JSON.writeValueAsBytes(e)));
            }
        }
        return out;
    }

    private void overTheCopyCap(int norad, Instant fetchedAt, Instant now) throws IOException {
        for (int i = 0; i < 5; i++) {
            double mm = 15.40 + i / 100.0;
            processor.accept(withObject(norad, fetchedAt, gp -> gp.put("MEAN_MOTION", mm)), now);
        }
    }

    /** Synthetic: copies over the cap, then the recorded ISS element set with a newer epoch from a later fetch. */
    @Test
    void aNewerEpochClearsTheOverCapCount() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        overTheCopyCap(25544, FETCHED.plusSeconds(60), FETCHED.plusSeconds(120));

        processor.accept(withIss(FETCHED.plusSeconds(7_800), gp -> gp.put("EPOCH", "2026-09-27T06:00:00.000000")),
                FETCHED.plusSeconds(7_860));

        JsonNode run = runOf(processor.poll(FETCHED.plusSeconds(7_900)));
        assertThat(run.get("differing_copies")).isEmpty();
        assertThat(run.get("differing_copies_over_cap")).isEmpty();
    }

    /** Synthetic: POISK over the copy cap, then only a newer ISS element set 40 days later, so POISK ages out. */
    @Test
    void anObjectOverTheCopyCapThatAgesOutLeavesNoCount() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        overTheCopyCap(36086, FETCHED.plusSeconds(60), FETCHED.plusSeconds(120));
        Instant muchLater = FETCHED.plusSeconds(40L * 86_400);

        processor.accept(withIss(muchLater, gp -> gp.put("EPOCH", muchLater.minusSeconds(3_600).toString()
                .replace("Z", ".000000"))), muchLater.plusSeconds(60));

        JsonNode run = runOf(processor.poll(muchLater.plusSeconds(90)));
        assertThat(run.get("coverage").get("catalog_admitted").asInt()).isEqualTo(1);
        assertThat(run.get("differing_copies_over_cap")).isEmpty();
    }

    @ParameterizedTest(name = "fetched_at {0}")
    @ValueSource(strings = {"2026-09-27T05:00:00.1234567890Z", "2026-09-27T24:00:00Z"})
    void aFetchedAtThatIsNotAPlainUtcTimeIsDeadLetteredAndNotHeld(String fetchedAt) throws IOException {
        GpProcessor.In in = stations(FETCHED).get(0);
        ObjectNode e = (ObjectNode) JSON.readTree(in.value());
        e.put("fetched_at", fetchedAt);

        GpProcessor.Out out = processor.accept(List.of(new GpProcessor.In(in.key(), JSON.writeValueAsBytes(e))),
                FETCHED.plusSeconds(86_400));

        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            JsonNode d = JSON.readTree(m.value());
            assertThat(d.get("check").asString()).isEqualTo("schema");
            assertThat(d.get("reason").asString()).contains("fetched_at");
        });
        assertThat(processor.poll(FETCHED.plusSeconds(90_000)).alerts()).isEmpty();
    }

    /** A real leap second passes the date-time format check and is held as 23:59:59 (orbital conventions 2.4). */
    @Test
    void aFetchedAtAtARealLeapSecondIsHeldAsTheSecondBefore() throws IOException {
        GpProcessor.In in = stations(FETCHED).stream().filter(i -> i.key().equals("25544")).findFirst().orElseThrow();
        ObjectNode e = (ObjectNode) JSON.readTree(in.value());
        e.put("fetched_at", "2016-12-31T23:59:60Z");
        ((ObjectNode) e.get("gp")).put("EPOCH", "2016-12-31T23:00:00.000000");

        GpProcessor.Out out = processor.accept(List.of(new GpProcessor.In(in.key(), JSON.writeValueAsBytes(e))),
                Instant.parse("2017-01-01T00:00:30Z"));

        assertThat(out.deadLetters()).isEmpty();
        JsonNode run = runOf(processor.poll(Instant.parse("2017-01-01T00:01:00Z")));
        assertThat(run.get("window_start").asString()).isEqualTo("2016-12-31T23:59:59Z");
        assertThat(run.get("coverage").get("watchlist_accepted").asInt()).isEqualTo(1);
    }

    /** The committed schemas with one topic's text edited, to reach checks the real schemas stop first. */
    static TopicSchemas schemasWith(String topic, java.util.function.UnaryOperator<String> edit) throws IOException {
        Map<String, String> files = new HashMap<>();
        for (String t : List.of("raw.gp", "raw.swpc", "alerts", "dlq")) {
            files.put(t, Files.readString(Path.of("..", "schemas", t, "v1.schema.json")));
        }
        files.put(topic, edit.apply(files.get(topic)));
        return TopicSchemas.of(files);
    }

    /** The parse check behind the schema's date-time format: a rule dead letter if the format check were ever lost. */
    @ParameterizedTest(name = "fetched_at {0}")
    @ValueSource(strings = {"2026-09-27T05:00:00.1234567890Z", "2026-09-27T24:00:00Z"})
    void withoutTheFormatCheckAnUnreadableFetchedAtIsARuleDeadLetter(String fetchedAt) throws IOException {
        GpProcessor p = new GpProcessor(schemasWith("raw.gp", t -> t.replace("\"format\": \"date-time\",", "")),
                Set.of(25544), OrekitData.utc());
        GpProcessor.In in = stations(FETCHED).get(0);
        ObjectNode e = (ObjectNode) JSON.readTree(in.value());
        e.put("fetched_at", fetchedAt);

        GpProcessor.Out out = p.accept(List.of(new GpProcessor.In(in.key(), JSON.writeValueAsBytes(e))),
                FETCHED.plusSeconds(86_400));

        assertThat(out.deadLetters()).singleElement().satisfies(m -> {
            JsonNode d = JSON.readTree(m.value());
            assertThat(d.get("check").asString()).isEqualTo("rule");
            assertThat(d.get("reason").asString()).contains("\"fetched_at\" is not a valid UTC time");
        });
    }

    /** Synthetic: fields the schema allows that a two line element set cannot hold, which SGP4 still propagates. */
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({"REV_AT_EPOCH, 100000", "MEAN_MOTION_DOT, 10", "MEAN_MOTION_DOT, -1"})
    void anElementSetBeyondTheLineFormatIsHeldAndScreened(String field, String value) throws IOException {
        List<GpProcessor.In> batch = new ArrayList<>(stations(FETCHED));
        batch.removeIf(in -> in.key().equals("25544"));
        batch.addAll(withIss(FETCHED, gp -> gp.set(field, JSON.readTree(value))));

        GpProcessor.Out out = processor.accept(batch, FETCHED.plusSeconds(60));

        assertThat(out.deadLetters()).isEmpty();
        assertThat(runOf(processor.poll(FETCHED.plusSeconds(90))).get("coverage").get("watchlist_accepted").asInt())
                .isEqualTo(1);
    }

    /** Synthetic: a catalog number above 339999, which no two line element set can carry, on a stations record. */
    @Test
    void aCatalogNumberBeyondTheLineFormatIsHeldAndScreened() throws IOException {
        GpProcessor baseline = new GpProcessor(TopicSchemas.fromClasspath(), Set.of(25544), OrekitData.utc());
        baseline.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        JsonNode before = runOf(baseline.poll(FETCHED.plusSeconds(90))).get("coverage");
        List<GpProcessor.In> batch = new ArrayList<>(stations(FETCHED));
        GpProcessor.In other = batch.stream().filter(in -> !in.key().equals("25544")).findFirst().orElseThrow();
        ObjectNode e = (ObjectNode) JSON.readTree(other.value());
        ((ObjectNode) e.get("gp")).put("NORAD_CAT_ID", 340_000);
        batch.add(new GpProcessor.In("340000", JSON.writeValueAsBytes(e)));

        GpProcessor.Out out = processor.accept(batch, FETCHED.plusSeconds(60));

        assertThat(out.deadLetters()).isEmpty();
        JsonNode run = runOf(processor.poll(FETCHED.plusSeconds(90)));
        assertThat(run.get("coverage").get("catalog_admitted").asInt())
                .isEqualTo(before.get("catalog_admitted").asInt() + 1);
        assertThat(run.get("coverage").get("pairs").asInt()).isEqualTo(before.get("pairs").asInt() + 1);
        assertThat(run.get("coverage").get("pairs_not_screenable").asInt())
                .isEqualTo(before.get("pairs_not_screenable").asInt());
        assertThat(run.get("rejected").findValues("catalog_number")).noneMatch(n -> n.asInt() == 340_000);
        assertThat(run.get("not_screened").findValues("catalog_number")).noneMatch(n -> n.asInt() == 340_000);
    }

    /** Synthetic: a same epoch copy whose mean motion differs below the eight decimals a line would carry. */
    @Test
    void aCopyDifferingBelowLinePrecisionIsStillListed() throws IOException {
        processor.accept(stations(FETCHED), FETCHED.plusSeconds(60));
        double mm = JSON.readTree(withIss(FETCHED, gp -> { }).get(0).value()).get("gp").get("MEAN_MOTION").asDouble();

        processor.accept(withIss(FETCHED.plusSeconds(60), gp -> gp.put("MEAN_MOTION", mm + 1e-10)),
                FETCHED.plusSeconds(120));

        assertThat(runOf(processor.poll(FETCHED.plusSeconds(150))).get("differing_copies")).hasSize(1);
    }

    /** An alerts schema that refuses every event, so the branch that dead letters a run's own events is exercised. */
    @Test
    void runEventsThatFailTheAlertsSchemaGoToAlertsDlqUnderTheRunId() throws IOException {
        GpProcessor p = new GpProcessor(schemasWith("alerts", t -> "{\"maxProperties\": 0," + t.trim().substring(1)),
                Set.of(25544), OrekitData.utc());
        p.accept(stations(FETCHED), FETCHED.plusSeconds(60));

        GpProcessor.Out out = p.poll(FETCHED.plusSeconds(90));

        assertThat(out.runId()).isEqualTo(FETCHED + "/1");
        assertThat(out.alerts()).isEmpty();
        assertThat(out.deadLetters()).isNotEmpty().allSatisfy(m -> {
            assertThat(m.topic()).isEqualTo("alerts.dlq");
            assertThat(m.key()).isEqualTo(FETCHED + "/1");
            assertThat(JSON.readTree(m.value()).get("check").asString()).isEqualTo("schema");
        });
    }
}
