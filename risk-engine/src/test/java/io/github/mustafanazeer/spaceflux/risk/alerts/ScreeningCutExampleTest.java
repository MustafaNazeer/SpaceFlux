package io.github.mustafanazeer.spaceflux.risk.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.risk.screening.CloseApproach;
import io.github.mustafanazeer.spaceflux.risk.screening.Role;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningSettings;
import io.github.mustafanazeer.spaceflux.risk.screening.SuppressedPair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The committed cut summary example is what the summary code writes when a run does not fit its budget. The budget
 * here is far below the production one, so the example stays small enough to read.
 */
class ScreeningCutExampleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int EXAMPLE_BUDGET_BYTES = 2_000;

    private static final Comparator<JsonNode> NUMERIC = (a, b) -> a.isNumber() && b.isNumber()
            ? Double.compare(a.asDouble(), b.asDouble())
            : a.equals(b) ? 0 : 1;

    private static final String STACK = "International Space Station";

    /** The wording Screening writes for a pair in a stack, so the example stays exact if the budget changes. */
    private static String stackDetail(double minM, double maxM, AbsoluteDate start, AbsoluteDate end) {
        return String.format(Locale.ROOT,
                "not screened for close approaches: both are in the %s stack (pair listed since %s); separation "
                        + "between the members' propagated element sets, not a measured distance, %.1f to %.1f km "
                        + "at the %.0f s samples from %s to %s",
                STACK, "2026-09-27", minM / 1000, maxM / 1000, ScreeningSettings.SAMPLE_STEP_S,
                utc(start), utc(end));
    }

    private static String coOrbitingDetail(double minM, AbsoluteDate minAt, double maxM, AbsoluteDate start,
            AbsoluteDate end) {
        return String.format(Locale.ROOT,
                "not screened for close approaches: separation stayed under the %.0f km co-orbiting bound at every "
                        + "%.0f s sample from %s to %s (inferred from GP data, not known to be attached); sampled "
                        + "minimum %.1f km at %s, maximum %.1f km; the sampled minimum is within the %.0f km report "
                        + "distance, and approaches for this pair were not computed",
                ScreeningSettings.CO_ORBITING_BOUND_M / 1000, ScreeningSettings.SAMPLE_STEP_S, utc(start), utc(end),
                minM / 1000, utc(minAt), maxM / 1000, ScreeningSettings.REPORT_DISTANCE_M / 1000);
    }

    private static String utc(AbsoluteDate date) {
        return date.toStringRfc3339(OrekitData.utc());
    }

    /** Synthetic inputs chosen to force a cut; no screening run produced them. */
    static JsonNode cutRun() {
        AbsoluteDate start = new AbsoluteDate("2026-09-29T07:30:00", OrekitData.utc());
        AbsoluteDate end = start.shiftedBy(ScreeningSettings.WINDOW_S);
        CloseApproach approach = new CloseApproach(25544, 27958,
                new AbsoluteDate("2026-09-30T03:34:37.588", OrekitData.utc()), 1973.3, 15727, 1.5585, 4.1985);
        List<SuppressedPair> suppressed = List.of(
                new SuppressedPair(25544, 49044, SuppressedPair.Mechanism.STATIC_STACK, STACK,
                        stackDetail(41.2, 58.9, start, end), 41.2, start.shiftedBy(3600), 58.9, false),
                new SuppressedPair(25544, 67796, SuppressedPair.Mechanism.STATIC_STACK, STACK,
                        stackDetail(37.5, 52.1, start, end), 37.5, start.shiftedBy(7200), 52.1, false),
                new SuppressedPair(25544, 63129, SuppressedPair.Mechanism.CO_ORBITING, null,
                        coOrbitingDetail(812.4, start.shiftedBy(10800), 2410.7, start, end), 812.4, start.shiftedBy(10800), 2410.7, false));
        List<ScreeningResult.Rejected> rejected = List.of(
                new ScreeningResult.Rejected(43205, Role.CATALOG, ScreeningResult.Rejected.Code.STALE_ELEMENT_SET,
                        "element set is 12.4 days old at the window start, over the 10 day limit; not screened as "
                                + "a catalog object"),
                new ScreeningResult.Rejected(99999, Role.WATCHLIST, ScreeningResult.Rejected.Code.NOT_IN_INPUT,
                        "no element set for this watchlist object in the input, so it was not screened"));
        List<ScreeningResult.EpochAfterStart> after = List.of(new ScreeningResult.EpochAfterStart(64001, 12.5),
                new ScreeningResult.EpochAfterStart(64002, 47.0));
        List<ScreeningResult.DifferingCopy> differing = List.of(new ScreeningResult.DifferingCopy(48274, "CSS (TIANHE)",
                start.shiftedBy(-3600), "CSS (TIANHE)", start.shiftedBy(-3600), Role.CATALOG, true));
        ScreeningResult result = new ScreeningResult(start, end,
                new ScreeningResult.Coverage(1, 8, 7, 0, 0, 4), List.of(approach), suppressed, rejected, List.of(),
                after, differing);
        Map<Integer, String> names = Map.of(25544, "ISS (ZARYA)", 27958, "SL-12 DEB");

        List<JsonNode> events = ScreeningJson.write(result, names, List.of(), Instant.parse("2026-09-29T07:30:00Z"), 1,
                Instant.parse("2026-09-30T18:50:27Z"), OrekitData.utc(), EXAMPLE_BUDGET_BYTES);
        return events.get(events.size() - 1);
    }

    @Test
    void writesTheCommittedCutScreeningRunExample() throws IOException {
        JsonNode run = cutRun();
        JsonNode example = JSON.readTree(
                Files.readString(Path.of("..", "schemas", "alerts", "examples", "valid-screening-run-cut.json")));

        assertThat(run.equals(NUMERIC, example)).as(run.toPrettyString()).isTrue();
    }

    @Test
    void theExampleIsCutWithinItsBudgetAndKeepsCountsAndTheWatchlistEntry() {
        JsonNode run = cutRun();
        JsonNode p = run.get("screening_run");

        assertThat(JSON.writeValueAsBytes(run).length + "2026-09-30T18:50:27.123456789Z".length()
                - "2026-09-30T18:50:27Z".length()).isLessThanOrEqualTo(EXAMPLE_BUDGET_BYTES);
        assertThat(p.get("omitted").get("differing_copies").asInt()).isEqualTo(1);
        assertThat(p.get("omitted").get("epoch_after_start").asInt()).isEqualTo(2);
        assertThat(p.get("omitted").get("approach_event_ids").asInt()).isEqualTo(1);
        assertThat(p.get("omitted").get("suppressed").asInt()).isPositive();
        assertThat(p.get("omitted").get("rejected").asInt()).isZero();
        assertThat(p.get("approach_count").asInt()).isEqualTo(1);
        assertThat(p.get("coverage").get("catalog_admitted").asInt()).isEqualTo(8);
        assertThat(p.get("rejected").get(0).get("role").asString()).isEqualTo("watchlist");
    }
}
