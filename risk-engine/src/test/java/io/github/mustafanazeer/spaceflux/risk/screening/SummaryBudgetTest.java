package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack.StopKind;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.risk.alerts.ScreeningJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A screening_run summary always fits under the producer's 1 MiB record limit, cutting lists in a fixed order. */
class SummaryBudgetTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AbsoluteDate START = new AbsoluteDate("2026-09-29T05:20:09", OrekitData.utc());
    private static final Instant WINDOW_START = Instant.parse("2026-09-29T05:20:09Z");
    private static final Instant PRODUCED = Instant.parse("2026-09-30T18:50:27Z");

    private static ScreeningResult result(int suppressed, int rejected) {
        List<SuppressedPair> s = new ArrayList<>();
        for (int i = 0; i < suppressed; i++) {
            s.add(new SuppressedPair(25544, 100_000 + i, SuppressedPair.Mechanism.CO_ORBITING, null, "d".repeat(300),
                    100.0, START, 400.0, false));
        }
        List<ScreeningResult.Rejected> r = new ArrayList<>();
        for (int i = 0; i < rejected; i++) {
            r.add(new ScreeningResult.Rejected(200_000 + i, Role.CATALOG,
                    ScreeningResult.Rejected.Code.STALE_ELEMENT_SET, "r".repeat(250)));
        }
        r.add(new ScreeningResult.Rejected(99_999, Role.WATCHLIST, ScreeningResult.Rejected.Code.NOT_IN_INPUT,
                "no element set for this watchlist object in the input, so it was not screened"));
        return new ScreeningResult(START, START.shiftedBy(ScreeningSettings.WINDOW_S),
                new ScreeningResult.Coverage(1, suppressed + 1, suppressed, 0, 0, 0), List.of(), s, r, List.of(),
                List.of(), List.of());
    }

    private static JsonNode summary(ScreeningResult result, Instant producedAt) {
        List<JsonNode> events = ScreeningJson.write(result, Map.of(), List.of(), WINDOW_START, 1, producedAt,
                OrekitData.utc());
        return events.get(events.size() - 1);
    }

    private static int size(JsonNode event) {
        return JSON.writeValueAsBytes(event).length;
    }

    @Test
    void aSmallRunOmitsNothingAndCarriesZeroCounts() {
        JsonNode run = summary(result(7, 0), PRODUCED).get("screening_run");

        assertThat(run.get("omitted").properties()).hasSize(7)
                .allSatisfy(e -> assertThat(e.getValue().asInt()).isZero());
        assertThat(run.get("suppressed")).hasSize(7);
    }

    @Test
    void anOversizedSummaryCutsSuppressedBeforeRejectedAndFitsTheBudget() {
        JsonNode event = summary(result(3_000, 1_000), PRODUCED);
        JsonNode run = event.get("screening_run");

        assertThat(size(event) + "2026-09-30T18:50:27.123456789Z".length() - PRODUCED.toString().length())
                .isLessThanOrEqualTo(ScreeningJson.SUMMARY_BUDGET_BYTES);
        assertThat(run.get("omitted").get("suppressed").asInt()).isPositive();
        assertThat(run.get("suppressed").size() + run.get("omitted").get("suppressed").asInt()).isEqualTo(3_000);
        assertThat(run.get("omitted").get("rejected").asInt()).isZero();
        assertThat(run.get("rejected")).hasSize(1_001);
        assertThat(TopicSchemas.fromClasspath().check("alerts", event).failure()).isNull();
    }

    @Test
    void whenRejectedMustBeCutTooTheWatchlistEntriesAreKept() {
        JsonNode run = summary(result(0, 4_000), PRODUCED).get("screening_run");

        assertThat(run.get("omitted").get("rejected").asInt()).isPositive();
        assertThat(run.get("rejected").get(0).get("role").asString()).isEqualTo("watchlist");
        assertThat(run.get("rejected").get(0).get("catalog_number").asInt()).isEqualTo(99_999);
    }

    private static ScreeningResult epochsAfterStart(int count, int reasonLength) {
        List<ScreeningResult.EpochAfterStart> e = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            e.add(new ScreeningResult.EpochAfterStart(100_000 + i, 12.5));
        }
        return new ScreeningResult(START, START.shiftedBy(ScreeningSettings.WINDOW_S),
                new ScreeningResult.Coverage(1, 1, 0, 0, 0, 0), List.of(), List.of(),
                List.of(new ScreeningResult.Rejected(99_999, Role.WATCHLIST,
                        ScreeningResult.Rejected.Code.NOT_IN_INPUT, "r".repeat(reasonLength))),
                List.of(), e, List.of());
    }

    /**
     * The summary grows one byte at a time over more bytes than one entry takes, so some length puts the cut point
     * inside the ten characters a short produced_at saves.
     */
    @Test
    void theCutDoesNotDependOnHowLongProducedAtIs() {
        for (int r = 1; r < 80; r++) {
            JsonNode a = summary(epochsAfterStart(20_000, r), PRODUCED).get("screening_run");
            JsonNode b = summary(epochsAfterStart(20_000, r), Instant.parse("2026-09-30T18:50:27.123456789Z"))
                    .get("screening_run");

            assertThat(a.get("omitted").get("epoch_after_start").asInt()).isPositive();
            assertThat(b.get("omitted")).as("reason of %d characters", r).isEqualTo(a.get("omitted"));
        }
    }

    private static ScreeningResult rejectedOnly(int count, int reasonLength) {
        List<ScreeningResult.Rejected> r = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            r.add(new ScreeningResult.Rejected(200_000 + i, Role.CATALOG,
                    ScreeningResult.Rejected.Code.STALE_ELEMENT_SET, "r".repeat(reasonLength)));
        }
        return new ScreeningResult(START, START.shiftedBy(ScreeningSettings.WINDOW_S),
                new ScreeningResult.Coverage(1, 1, 0, 0, 0, 0), List.of(), List.of(), r, List.of(), List.of(),
                List.of());
    }

    private static long padded(JsonNode event, Instant producedAt) {
        return size(event) + "2026-09-30T18:50:27.123456789Z".length() - producedAt.toString().length();
    }

    /**
     * Entry sizes change one byte at a time, so the cut ends at every offset, including where the omitted count gains
     * a digit.
     */
    @Test
    void aCutStaysWithinTheBudgetAndRemovesNoMoreThanItMust() {
        for (int r = 1; r < 400; r++) {
            JsonNode event = summary(rejectedOnly(1_200_000 / (r + 100), r), PRODUCED);
            JsonNode run = event.get("screening_run");
            long entry = JSON.writeValueAsBytes(run.get("rejected").get(0)).length + 1;

            assertThat(run.get("omitted").get("rejected").asInt()).isPositive();
            assertThat(padded(event, PRODUCED)).as("reason of %d characters", r)
                    .isLessThanOrEqualTo(ScreeningJson.SUMMARY_BUDGET_BYTES)
                    .isGreaterThan(ScreeningJson.SUMMARY_BUDGET_BYTES - entry - 1);
        }
    }

    private static final List<String> CUT_ORDER = List.of("differing_copies", "differing_copies_over_cap",
            "epoch_after_start", "approach_event_ids", "suppressed", "not_screened", "rejected");

    /** One hundred entries in every list, plus a watchlist entry in rejected whose reason sets the summary's size. */
    private static List<JsonNode> everyList(int fillerLength) {
        List<CloseApproach> approaches = new ArrayList<>();
        List<SuppressedPair> suppressed = new ArrayList<>();
        List<ScreeningResult.Rejected> rejected = new ArrayList<>();
        List<ScreeningResult.NotScreened> notScreened = new ArrayList<>();
        List<ScreeningResult.EpochAfterStart> after = new ArrayList<>();
        List<ScreeningResult.DifferingCopy> copies = new ArrayList<>();
        List<ScreeningJson.OverCap> overCap = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            approaches.add(new CloseApproach(25544, 300_000 + i, START, 1000.0, 10.0, 1.0, 1.0));
            suppressed.add(new SuppressedPair(25544, 310_000 + i, SuppressedPair.Mechanism.CO_ORBITING, null, "d",
                    100.0, START, 400.0, false));
            rejected.add(new ScreeningResult.Rejected(320_000 + i, Role.CATALOG,
                    ScreeningResult.Rejected.Code.STALE_ELEMENT_SET, "r"));
            notScreened.add(new ScreeningResult.NotScreened(330_000 + i, Role.CATALOG,
                    StopKind.CANNOT_PROPAGATE, null, "n"));
            after.add(new ScreeningResult.EpochAfterStart(340_000 + i, 12.5));
            copies.add(new ScreeningResult.DifferingCopy(350_000 + i, "a", START, "b", START, Role.CATALOG, true));
            overCap.add(new ScreeningJson.OverCap(360_000 + i, START, 2));
        }
        rejected.add(new ScreeningResult.Rejected(99_999, Role.WATCHLIST, ScreeningResult.Rejected.Code.NOT_IN_INPUT,
                "f".repeat(fillerLength)));
        ScreeningResult result = new ScreeningResult(START, START.shiftedBy(ScreeningSettings.WINDOW_S),
                new ScreeningResult.Coverage(1, 1, 0, 0, 0, 0), approaches, suppressed, rejected, notScreened, after,
                copies);
        return ScreeningJson.write(result, Map.of(), overCap, WINDOW_START, 1, PRODUCED, OrekitData.utc());
    }

    /**
     * For each list, a summary sized so the cut ends halfway through it: every earlier list is empty and every later
     * one whole.
     */
    @Test
    void listsAreCutInTheRuledOrder() {
        List<JsonNode> base = everyList(0);
        JsonNode baseRun = base.get(base.size() - 1).get("screening_run");
        long baseSize = padded(base.get(base.size() - 1), PRODUCED);
        for (int k = 0; k < CUT_ORDER.size(); k++) {
            long overflow = 0;
            for (int j = 0; j < k; j++) {
                overflow += JSON.writeValueAsBytes(baseRun.get(CUT_ORDER.get(j))).length;
            }
            overflow += JSON.writeValueAsBytes(baseRun.get(CUT_ORDER.get(k))).length / 2;

            List<JsonNode> events = everyList((int) (ScreeningJson.SUMMARY_BUDGET_BYTES - baseSize + overflow));
            JsonNode run = events.get(events.size() - 1).get("screening_run");

            for (int j = 0; j < CUT_ORDER.size(); j++) {
                int whole = CUT_ORDER.get(j).equals("rejected") ? 101 : 100;
                int left = run.get(CUT_ORDER.get(j)).size();
                String what = "cut ending in " + CUT_ORDER.get(k) + ", list " + CUT_ORDER.get(j);
                if (j < k) {
                    assertThat(left).as(what).isZero();
                } else if (j == k) {
                    assertThat(left).as(what).isBetween(1, whole - 1);
                } else {
                    assertThat(left).as(what).isEqualTo(whole);
                }
            }
        }
    }
}
