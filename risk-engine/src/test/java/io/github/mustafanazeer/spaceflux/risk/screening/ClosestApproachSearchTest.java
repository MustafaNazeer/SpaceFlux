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

/** docs/risk/orbital-conventions.md 3.4 and 3.6. */
class ClosestApproachSearchTest {

    private static final double REPORT_M = 5_000;
    private static final double WINDOW_S = 7 * 86400;

    /**
     * SGP4 positions carry rounding noise near 1e-6 m, so a converged TCA can sit that far above a dense sample;
     * 1 mm stays below the 2 mm compliance tolerance (docs/risk/orbital-conventions.md 1.3).
     */
    private static final double NUMERIC_SLACK_M = 1e-3;

    @Test
    void findsEveryDenseScanMinimumWithinTheReportDistanceAndNothingElse() throws IOException {
        List<TrackedObject> objects = Fixtures.stations();
        AbsoluteDate start = Fixtures.STATIONS_START;
        AbsoluteDate end = start.shiftedBy(WINDOW_S);
        Map<Integer, ObjectTrack> tracks = new HashMap<>();
        for (TrackedObject o : objects) {
            tracks.put(o.catalogNumber(), ObjectTrack.sample(o, start, end, ScreeningSettings.SAMPLE_STEP_S));
        }

        List<BruteForceScan.PairStats> dense = BruteForceScan.scan(objects.stream().map(TrackedObject::tle).toList(),
                start, WINDOW_S, 1, 2 * REPORT_M / 1000);

        int denseMinima = 0;
        int pipelineApproaches = 0;
        for (BruteForceScan.PairStats pair : dense) {
            ObjectTrack a = tracks.get(pair.a());
            ObjectTrack b = tracks.get(pair.b());
            List<CloseApproach> found = RadialPrefilter.mayApproach(a, b, REPORT_M)
                    ? ClosestApproachSearch.find(a, b, start, REPORT_M)
                    : List.of();
            List<BruteForceScan.Minimum> within = pair.minimaWithin(REPORT_M / 1000);
            denseMinima += within.size();
            pipelineApproaches += found.size();

            for (BruteForceScan.Minimum m : within) {
                assertThat(found).as("pair %d-%d dense minimum at %s s", pair.a(), pair.b(), m.secondsFromStart())
                        .anySatisfy(ca -> {
                            assertThat(Math.abs(ca.tca().durationFrom(start) - m.secondsFromStart())).isLessThan(1.0);
                            assertThat(ca.missM()).isLessThanOrEqualTo(m.distanceKm() * 1000 + NUMERIC_SLACK_M);
                            assertThat(m.distanceKm() * 1000 - ca.missM()).isLessThanOrEqualTo(ca.relativeSpeedMPerS() * 1.0);
                        });
            }
            for (CloseApproach ca : found) {
                double t = ca.tca().durationFrom(start);
                assertThat(pair.minimaNear(t, 1.0, REPORT_M / 1000 + ca.relativeSpeedMPerS() / 1000))
                        .as("pair %d-%d pipeline TCA at %s s has no dense counterpart", pair.a(), pair.b(), t)
                        .isTrue();
            }
        }
        System.out.printf("Screening cross check: %d pairs, %d dense minima within 5 km, %d pipeline approaches%n",
                dense.size(), denseMinima, pipelineApproaches);
        assertThat(denseMinima).isPositive();
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
