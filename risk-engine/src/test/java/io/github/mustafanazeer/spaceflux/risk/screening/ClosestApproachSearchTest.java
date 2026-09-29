package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;

import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;

/** docs/risk/orbital-conventions.md 3.4 and 3.6. */
class ClosestApproachSearchTest {

    private static final double REPORT_M = 5_000;
    private static final double WINDOW_S = 7 * 86400;

    /**
     * SGP4 positions carry rounding noise near 1e-6 m, so a converged TCA can sit that far above a dense sample;
     * 1 mm stays below the 2 mm compliance tolerance (docs/risk/orbital-conventions.md 1.3).
     */
    private static final double NUMERIC_SLACK_M = 1e-3;

    private static final double DENSE_STEP_S = 1;

    /** Twice the escape speed at the Earth's surface, 22.36 km/s with WGS-72, rounded up. */
    private static final double MAX_RELATIVE_SPEED_KM_PER_S = 22.4;

    private static final double HALF_STEP_TRAVEL_KM = MAX_RELATIVE_SPEED_KM_PER_S * DENSE_STEP_S / 2;

    @Test
    void findsEveryDenseScanMinimumWithinTheReportDistanceAndNothingElse() throws IOException {
        int[] counts = crossCheck(Fixtures.stations(), Fixtures.STATIONS_START);
        System.out.printf("Screening cross check: %d pairs, %d dense minima within 5 km, %d pipeline approaches%n",
                counts[0], counts[1], counts[2]);
        assertThat(counts[1]).isPositive();
    }

    @Test
    void findsTheRecordedCrossingEncounterTheDenseScanFinds() throws IOException {
        List<TrackedObject> pair = Fixtures.crossing();
        int[] counts = crossCheck(pair, Fixtures.CROSSING_START);

        ObjectTrack a = ObjectTrack.sample(pair.get(0), Fixtures.CROSSING_START,
                Fixtures.CROSSING_START.shiftedBy(WINDOW_S), ScreeningSettings.SAMPLE_STEP_S);
        ObjectTrack b = ObjectTrack.sample(pair.get(1), Fixtures.CROSSING_START,
                Fixtures.CROSSING_START.shiftedBy(WINDOW_S), ScreeningSettings.SAMPLE_STEP_S);
        CloseApproach ca = ClosestApproachSearch.find(a, b, Fixtures.CROSSING_START, REPORT_M).getFirst();
        System.out.printf("Crossing cross check: %d dense minima within 5 km, %d pipeline approaches; TCA %s, "
                + "miss %.1f m, relative speed %.3f km/s%n", counts[1], counts[2],
                ca.tca().toStringWithoutUtcOffset(OrekitData.utc(), 3) + "Z", ca.missM(), ca.relativeSpeedMPerS() / 1000);
        assertThat(counts[1]).isEqualTo(1);
        assertThat(counts[2]).isEqualTo(1);
        assertThat(ca.relativeSpeedMPerS()).isGreaterThan(10_000);
    }

    /**
     * Returns pairs, dense minima within the report distance, and pipeline approaches. A sampled minimum can sit up
     * to half a step of relative motion above the true one: at a 1 s step and a relative speed below 22.4 km/s
     * (twice the escape speed at the Earth's surface, which no two bound orbits exceed) that is under 11.2 km, so
     * every sampled minimum within 5 + 11.2 km is refined before it is compared with the pipeline.
     */
    private static int[] crossCheck(List<TrackedObject> objects, AbsoluteDate start) {
        AbsoluteDate end = start.shiftedBy(WINDOW_S);
        Map<Integer, ObjectTrack> tracks = new HashMap<>();
        Map<Integer, TrackedObject> byNumber = new HashMap<>();
        for (TrackedObject o : objects) {
            tracks.put(o.catalogNumber(), ObjectTrack.sample(o, start, end, ScreeningSettings.SAMPLE_STEP_S));
            byNumber.put(o.catalogNumber(), o);
        }

        List<BruteForceScan.PairStats> dense = BruteForceScan.scan(objects.stream().map(TrackedObject::tle).toList(),
                start, WINDOW_S, DENSE_STEP_S, REPORT_M / 1000 + HALF_STEP_TRAVEL_KM);

        int denseMinima = 0;
        int pipelineApproaches = 0;
        for (BruteForceScan.PairStats pair : dense) {
            ObjectTrack a = tracks.get(pair.a());
            ObjectTrack b = tracks.get(pair.b());
            List<CloseApproach> found = RadialPrefilter.mayApproach(a, b, REPORT_M)
                    ? ClosestApproachSearch.find(a, b, start, REPORT_M)
                    : List.of();
            List<BruteForceScan.Minimum> within = pair.minima().stream()
                    .map(m -> BruteForceScan.refine(byNumber.get(pair.a()).tle(), byNumber.get(pair.b()).tle(), start,
                            m, DENSE_STEP_S))
                    .filter(m -> m.distanceKm() * 1000 <= REPORT_M)
                    .toList();
            denseMinima += within.size();
            pipelineApproaches += found.size();

            for (BruteForceScan.Minimum m : within) {
                assertThat(found).as("pair %d-%d refined minimum at %s s", pair.a(), pair.b(), m.secondsFromStart())
                        .anySatisfy(ca -> matches(ca, m, start));
            }
            for (CloseApproach ca : found) {
                assertThat(within).as("pair %d-%d pipeline TCA at %s s has no refined counterpart", pair.a(), pair.b(),
                        ca.tca().durationFrom(start)).anySatisfy(m -> matches(ca, m, start));
            }
        }
        return new int[] {dense.size(), denseMinima, pipelineApproaches};
    }

    private static void matches(CloseApproach ca, BruteForceScan.Minimum m, AbsoluteDate start) {
        assertThat(Math.abs(ca.tca().durationFrom(start) - m.secondsFromStart())).isLessThan(DENSE_STEP_S);
        assertThat(Math.abs(ca.missM() - m.distanceKm() * 1000)).isLessThanOrEqualTo(NUMERIC_SLACK_M);
    }

    @Test
    void reportedMissDistanceAndSpeedAreThoseOfTheStatesAtTca() throws IOException {
        TrackedObject iss = Fixtures.station(25544);
        TrackedObject poisk = Fixtures.station(36086);
        AbsoluteDate start = Fixtures.STATIONS_START;
        AbsoluteDate end = start.shiftedBy(86400);

        List<CloseApproach> found = ClosestApproachSearch.find(
                ObjectTrack.sample(iss, start, end, ScreeningSettings.SAMPLE_STEP_S),
                ObjectTrack.sample(poisk, start, end, ScreeningSettings.SAMPLE_STEP_S), start, REPORT_M);

        assertThat(found).isNotEmpty();
        for (CloseApproach ca : found) {
            PVCoordinates a = TLEPropagator.selectExtrapolator(iss.tle()).getPVCoordinates(ca.tca());
            PVCoordinates b = TLEPropagator.selectExtrapolator(poisk.tle()).getPVCoordinates(ca.tca());
            assertThat(ca.missM()).isEqualTo(b.getPosition().distance(a.getPosition()));
            assertThat(ca.relativeSpeedMPerS()).isEqualTo(b.getVelocity().distance(a.getVelocity()));
            assertThat(g(iss, poisk, ca.tca().shiftedBy(-1e-3))).isNegative();
            assertThat(g(iss, poisk, ca.tca().shiftedBy(1e-3))).isPositive();
            assertThat(ca.elementAgeDaysWatchlist()).isEqualTo(ca.tca().durationFrom(iss.tle().getDate()) / 86400);
            assertThat(ca.elementAgeDaysOther()).isEqualTo(ca.tca().durationFrom(poisk.tle().getDate()) / 86400);
        }
    }

    @Test
    void findsNothingForIdenticalElementSetsAndReturnsPromptly() throws IOException {
        TrackedObject dragon = Fixtures.station(67796);
        TrackedObject cygnus = Fixtures.station(68689);
        AbsoluteDate start = Fixtures.STATIONS_START;
        AbsoluteDate end = start.shiftedBy(WINDOW_S);
        ObjectTrack a = ObjectTrack.sample(dragon, start, end, ScreeningSettings.SAMPLE_STEP_S);
        ObjectTrack b = ObjectTrack.sample(cygnus, start, end, ScreeningSettings.SAMPLE_STEP_S);

        List<CloseApproach> found = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ClosestApproachSearch.find(a, b, start, REPORT_M));

        assertThat(found).isEmpty();
    }

    @Test
    void searchesOnlyWhileBothObjectsAreScreenable() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);
        AbsoluteDate epoch = decaying.tle().getDate();
        ObjectTrack a = ObjectTrack.sample(decaying, epoch, epoch.shiftedBy(3600), ScreeningSettings.SAMPLE_STEP_S);
        ObjectTrack b = ObjectTrack.sample(decaying, epoch, epoch.shiftedBy(3600), ScreeningSettings.SAMPLE_STEP_S);

        assertThat(ClosestApproachSearch.searchEnd(a, b)).isEqualTo(a.screenableUntil());
    }

    private static double g(TrackedObject a, TrackedObject b, AbsoluteDate date) {
        PVCoordinates pa = TLEPropagator.selectExtrapolator(a.tle()).getPVCoordinates(date);
        PVCoordinates pb = TLEPropagator.selectExtrapolator(b.tle()).getPVCoordinates(date);
        return pb.getPosition().subtract(pa.getPosition()).dotProduct(pb.getVelocity().subtract(pa.getVelocity()));
    }
}
