package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;

/** docs/risk/orbital-conventions.md 2.4 (latch) and 3.3 (radial band). */
class ObjectTrackTest {

    private static final double STEP_S = 10;

    @Test
    void radialBandContainsTheTrueRadiusAtEverySecond() throws IOException {
        TrackedObject iss = Fixtures.station(25544);
        AbsoluteDate end = Fixtures.STATIONS_START.shiftedBy(86400);

        ObjectTrack track = ObjectTrack.sample(iss, Fixtures.STATIONS_START, end, STEP_S);

        TLEPropagator dense = TLEPropagator.selectExtrapolator(iss.tle());
        for (int s = 0; s <= 86400; s++) {
            double r = dense.getPVCoordinates(Fixtures.STATIONS_START.shiftedBy(s)).getPosition().getNorm();
            assertThat(r).as("at %d s", s).isBetween(track.bandMinM(), track.bandMaxM());
        }
        assertThat(track.screenableUntil()).isEqualTo(end);
        assertThat(track.stopReason()).isNull();
    }

    @Test
    void padIsHalfAStepAtTheLargestSampledRadialRate() throws IOException {
        ObjectTrack track = ObjectTrack.sample(Fixtures.station(25544), Fixtures.STATIONS_START,
                Fixtures.STATIONS_START.shiftedBy(86400), STEP_S);

        assertThat(track.bandMinM()).isEqualTo(track.sampledMinM() - track.maxRadialRateMPerS() * STEP_S / 2);
        assertThat(track.bandMaxM()).isEqualTo(track.sampledMaxM() + track.maxRadialRateMPerS() * STEP_S / 2);
    }

    @Test
    void latchesAtTheLastGoodSampleBeforeTheDecayFloor() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);
        AbsoluteDate epoch = decaying.tle().getDate();

        ObjectTrack track = ObjectTrack.sample(decaying, epoch, epoch.shiftedBy(3600), STEP_S);

        double until = track.screenableUntil().durationFrom(epoch);
        assertThat(until + STEP_S).as("first failing sample, printed as 44.2 min in reference-evidence.txt")
                .isBetween(44.15 * 60, 44.25 * 60);
        assertThat(track.stopReason()).contains("altitude");
    }

    @Test
    void latchesAnObjectThatDecayedBetweenItsEpochAndTheWindowStart() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);
        AbsoluteDate epoch = decaying.tle().getDate();
        AbsoluteDate start = epoch.shiftedBy(3600);

        ObjectTrack track = ObjectTrack.sample(decaying, start, start.shiftedBy(3600), STEP_S);

        assertThat(track.screenable()).isFalse();
        assertThat(track.screenableUntil().durationFrom(epoch)).isEqualTo(2640.0);
        assertThat(track.stopReason()).contains("altitude");
    }

    @Test
    void staysLatchedWhenTheAltitudeRisesAgainAfterADip() throws IOException {
        TrackedObject object = Fixtures.reference(28350);
        AbsoluteDate epoch = object.tle().getDate();

        ObjectTrack track = ObjectTrack.sample(object, epoch, epoch.shiftedBy(1100 * 60), STEP_S);

        assertThat(track.screenableUntil().durationFrom(epoch)).isLessThan(1050.2 * 60);
    }
}
