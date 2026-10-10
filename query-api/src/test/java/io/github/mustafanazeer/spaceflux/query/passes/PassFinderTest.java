package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.frames.EOPHistory;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.PoleCorrection;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;

import io.github.mustafanazeer.spaceflux.orbit.Fixtures;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;

/** The method checks of docs/risk/orbital-conventions.md 6.9 that need no reference: observer, frames, element sets. */
class PassFinderTest {

    static final PassFinder FINDER = new PassFinder();

    static AbsoluteDate utc(String text) {
        return new AbsoluteDate(text, OrekitData.utc());
    }

    @Test
    void theObserverIsGemini3OnTheWgs84Ellipsoid() {
        assertThat(Math.toDegrees(FINDER.observer().getPoint().getLatitude())).isEqualTo(29.557976853,
                within(1e-12));
        assertThat(Math.toDegrees(FINDER.observer().getPoint().getLongitude())).isEqualTo(-95.091374225,
                within(1e-12));
        assertThat(FINDER.observer().getPoint().getAltitude()).isEqualTo(-22.182);
    }

    /** The geodetic to Cartesian formula written out, so degrees for radians or a west positive longitude fails. */
    @Test
    void theObserversEarthFixedPositionIsTheGeodeticFormulaOfItsCoordinates() {
        double a = 6378137.0;
        double f = 1 / 298.257223563;
        double e2 = f * (2 - f);
        double phi = Math.toRadians(29.557976853);
        double lambda = Math.toRadians(-95.091374225);
        double h = -22.182;
        double n = a / Math.sqrt(1 - e2 * Math.sin(phi) * Math.sin(phi));
        Vector3D expected = new Vector3D((n + h) * Math.cos(phi) * Math.cos(lambda),
                (n + h) * Math.cos(phi) * Math.sin(lambda), ((1 - e2) * n + h) * Math.sin(phi));

        AbsoluteDate date = utc("2026-09-27T05:00:00");
        Vector3D origin = FINDER.observer().getTransformTo(FramesFactory.getITRF(IERSConventions.IERS_2010, true),
                date).transformPosition(Vector3D.ZERO);

        assertThat(Vector3D.distance(origin, expected)).isLessThan(1e-6);
        assertThat(expected.getX()).isNegative();
        assertThat(expected.getY()).isNegative();
        assertThat(expected.getZ()).isPositive();
    }

    /** 6.3: no EOP data, so UT1 is UTC and the pole is not moved; loading EOP data later must fail here. */
    @Test
    void noEarthOrientationDataIsLoaded() {
        OrekitData.load();
        EOPHistory eop = FramesFactory.getEOPHistory(IERSConventions.IERS_2010, true);
        for (String date : new String[] {"2026-09-27T05:00:00", "2026-09-28T15:00:00", "2026-09-26T19:02:35",
            "2026-09-29T20:36:24", "2026-10-07T00:00:00"}) {
            assertThat(eop.getUT1MinusUTC(utc(date))).as(date).isZero();
            PoleCorrection pole = eop.getPoleCorrection(utc(date));
            assertThat(pole.getXp()).as(date).isZero();
            assertThat(pole.getYp()).as(date).isZero();
        }
        assertThat(eop.getEntries()).isEmpty();
    }

    @Test
    void theWindowIs86400SecondsFromTheStart() {
        AbsoluteDate start = utc("2026-09-27T05:00:00");

        PassFinder.Outcome o = FINDER.find(iss(), start);

        assertThat(o.windowStart()).isEqualTo(start);
        assertThat(o.windowEnd().durationFrom(start)).isEqualTo(86_400);
        assertThat(o.searchEnd()).isEqualTo(o.windowEnd());
        assertThat(o.status()).isEqualTo(PassStatus.COMPUTED);
        assertThat(o.stopReason()).isNull();
    }

    /** The default handlers stop at the first set or the first minimum; the reference has four ISS passes here. */
    @Test
    void thePropagationDoesNotStopAtTheFirstEvent() {
        PassFinder.Outcome o = FINDER.find(iss(), utc("2026-09-27T05:00:00"));

        assertThat(o.passes()).hasSize(4);
        assertThat(o.passes()).allSatisfy(p -> {
            assertThat(p.riseClipped()).isFalse();
            assertThat(p.setClipped()).isFalse();
        });
    }

    @Test
    void anElementSetExactlyTenDaysOldIsAcceptedAndOneSecondOlderIsStale() {
        TrackedObject iss = iss();
        AbsoluteDate tenDays = iss.tle().getDate().shiftedBy(10 * 86_400);

        assertThat(FINDER.find(iss, tenDays).status()).isEqualTo(PassStatus.COMPUTED);

        PassFinder.Outcome stale = FINDER.find(iss, tenDays.shiftedBy(1));
        assertThat(stale.status()).isEqualTo(PassStatus.STALE_ELEMENT_SET);
        assertThat(stale.reason()).contains("10.0 days old").contains("10 day limit");
        assertThat(stale.passes()).isNull();
    }

    @Test
    void anEpochAfterTheWindowStartIsPropagatedBackwards() {
        TrackedObject iss = iss();

        PassFinder.Outcome o = FINDER.find(iss, iss.tle().getDate().shiftedBy(-3 * 3600));

        assertThat(o.status()).isEqualTo(PassStatus.COMPUTED);
        assertThat(o.passes()).isNotEmpty();
    }

    /** 28872 decays about 50 minutes after its 2005 epoch (Section 1.2). */
    @Test
    void anElementSetThatDecayedBeforeTheWindowGivesNoPasses() throws Exception {
        TrackedObject decayed = Fixtures.reference(28872);

        PassFinder.Outcome o = FINDER.find(decayed, decayed.tle().getDate().shiftedBy(3 * 3600));

        assertThat(o.status()).isEqualTo(PassStatus.DECAYED);
        assertThat(o.reason()).contains("80 km decay floor; propagation stops here");
        assertThat(o.passes()).isNull();
    }

    /** 28350 first goes below the 80 km floor at 1050.2 minutes (Section 2.4). */
    @Test
    void anElementSetThatFailsTheFloorInsideTheWindowStopsTheSearchAtTheLastGoodSample() throws Exception {
        TrackedObject o = Fixtures.reference(28350);
        AbsoluteDate start = o.tle().getDate();

        PassFinder.Outcome out = FINDER.find(o, start);

        assertThat(out.status()).isEqualTo(PassStatus.COMPUTED);
        double stoppedMin = out.searchEnd().durationFrom(start) / 60;
        assertThat(stoppedMin).isBetween(1050.0, 1050.2);
        assertThat(out.searchEnd().durationFrom(start) % 10).isZero();
        assertThat(out.stopReason()).contains("80 km decay floor; propagation stops here");
        assertThat(out.passes()).allSatisfy(p -> {
            assertThat(p.peak().t()).isLessThanOrEqualTo(out.searchEnd().durationFrom(start));
            if (p.set() != null) {
                assertThat(p.set().t()).isLessThanOrEqualTo(out.searchEnd().durationFrom(start));
            }
        });
    }

    @Test
    void aDeepSpaceElementSetIsRejectedWithItsReason() throws Exception {
        TrackedObject deep = Fixtures.reference(23599);

        PassFinder.Outcome o = FINDER.find(deep, deep.tle().getDate());

        assertThat(o.status()).isEqualTo(PassStatus.DEEP_SPACE);
        assertThat(o.reason()).contains("deep space");
        assertThat(o.passes()).isNull();
    }

    /**
     * Epoch 2026-09-27T04:10:50.460096 (the recorded EPOCH) to the reference peak 16:41:45.749 the same day is
     * 12 h 30 min 55.288904 s, 45,055.288904 s or 0.5214733 days, worked by hand. The bound is the 1 s peak time
     * tolerance in days.
     */
    @Test
    void elementAgeAtThePeakIsThePeakTimeMinusTheEpochInDays() {
        PassFinder.Outcome o = FINDER.find(iss(), utc("2026-09-27T05:00:00"));

        PassGrouping.Pass first = o.passes().getFirst();
        assertThat(o.elementAgeDaysAt(first.peak().t())).isEqualTo(45_055.288904 / 86_400, within(1.0 / 86_400));
    }

    static TrackedObject slDeb() {
        return ReferencePasses.elementSet("orbit-core/src/test/resources/celestrak/gp-catnr-27958.json", 27958);
    }

    static void assertNoPointBeforeTheStartOrAfterTheSearchEnd(PassFinder.Outcome o) {
        double searchEnd = o.searchEnd().durationFrom(o.windowStart());
        assertThat(o.passes()).allSatisfy(p -> {
            for (PassGrouping.Point point : new PassGrouping.Point[] {p.rise(), p.startEdge(), p.set(), p.endEdge(),
                p.peak()}) {
                if (point != null) {
                    assertThat(point.t()).isBetween(0.0, searchEnd);
                }
            }
            assertThat(p.peaks()).allSatisfy(m -> assertThat(m.t()).isBetween(0.0, searchEnd));
        });
    }

    /**
     * 27958's true maximum at 2026-09-29T12:12:07.844Z lies 0.156 s before this start, so the 10 s bracket around
     * its extremum event reaches before the window. Clamped, the maximum falls on the window start: the first pass
     * is clipped there with no maximum inside the window, and nothing is computed before the start.
     */
    @Test
    void aMaximumJustBeforeTheWindowStartIsDroppedAndThePassPeaksAtItsStartEdge() {
        PassFinder.Outcome o = FINDER.find(slDeb(), utc("2026-09-29T12:12:08"));

        assertThat(o.status()).isEqualTo(PassStatus.COMPUTED);
        assertNoPointBeforeTheStartOrAfterTheSearchEnd(o);
        PassGrouping.Pass first = o.passes().getFirst();
        assertThat(first.rise()).isNull();
        assertThat(first.riseClipped()).isTrue();
        assertThat(first.peakAtEdge()).isTrue();
        assertThat(first.peakCount()).isZero();
        assertThat(first.peak()).isSameAs(first.startEdge());
        assertThat(first.peak().t()).isZero();
        assertThat(first.startEdge().elevationDeg()).isEqualTo(16.0986, within(0.001));
    }

    /** Fractional start; the last pass is still up at the window end and has no maximum inside the window. */
    @Test
    void aPassStillUpAtTheWindowEndWithItsMaximumAfterItPeaksAtItsEndEdge() {
        PassFinder.Outcome o = FINDER.find(slDeb(), utc("2026-09-28T04:46:37.600"));

        assertThat(o.status()).isEqualTo(PassStatus.COMPUTED);
        assertNoPointBeforeTheStartOrAfterTheSearchEnd(o);
        PassGrouping.Pass last = o.passes().getLast();
        assertThat(last.setClipped()).isTrue();
        assertThat(last.set()).isNull();
        assertThat(last.peakAtEdge()).isTrue();
        assertThat(last.peakCount()).isZero();
        assertThat(last.peak()).isSameAs(last.endEdge());
        assertThat(last.peak().t()).isEqualTo(86_400);
        assertThat(last.peak().elevationDeg()).isEqualTo(39.813398, within(0.001));
    }

    /** Whole second starts at which the unclamped bracket reached outside the window. */
    @Test
    void otherStartsNearA27958MaximumDoNotFail() {
        for (String start : new String[] {"2026-09-28T11:52:18", "2026-10-05T10:53:30"}) {
            PassFinder.Outcome o = FINDER.find(slDeb(), utc(start));

            assertThat(o.status()).as(start).isEqualTo(PassStatus.COMPUTED);
            assertNoPointBeforeTheStartOrAfterTheSearchEnd(o);
        }
    }

    /**
     * 27958's true maximum at 2026-09-29T04:46:37.976Z is 0.376 s inside this window, but its extremum event, from
     * SGP4's velocity, falls before the start, so only the search next to the start edge finds it.
     */
    @Test
    void aMaximumJustInsideTheWindowStartIsFoundByTheEdgeSearch() {
        AbsoluteDate start = utc("2026-09-29T04:46:37.600");
        TrackedObject deb = slDeb();

        PassFinder.Outcome o = FINDER.find(deb, start);

        PassGrouping.Pass first = o.passes().getFirst();
        assertThat(first.riseClipped()).isTrue();
        assertThat(first.peakAtEdge()).isFalse();
        assertThat(first.peakCount()).isEqualTo(1);
        AbsoluteDate reference = utc("2026-09-29T04:46:37.976");
        assertThat(o.at(first.peak().t()).durationFrom(reference)).isBetween(-1.0, 1.0);
        assertThat(first.peak().elevationDeg()).isEqualTo(maximumElevationNear(deb, reference), within(0.001));
    }

    /** The same maximum, 0.156 s before the end of a window that starts a day earlier. */
    @Test
    void aMaximumJustInsideTheWindowEndIsFoundByTheEdgeSearch() {
        TrackedObject deb = slDeb();

        PassFinder.Outcome o = FINDER.find(deb, utc("2026-09-28T12:12:08"));

        PassGrouping.Pass last = o.passes().getLast();
        assertThat(last.setClipped()).isTrue();
        assertThat(last.peakAtEdge()).isFalse();
        assertThat(last.peakCount()).isEqualTo(1);
        AbsoluteDate reference = utc("2026-09-29T12:12:07.844");
        assertThat(o.at(last.peak().t()).durationFrom(reference)).isBetween(-1.0, 1.0);
        assertThat(last.peak().elevationDeg()).isEqualTo(maximumElevationNear(deb, reference), within(0.001));
    }

    /**
     * The extremum event of the maximum at 2026-09-29T12:12:07.844Z can fall more than 5 s from these starts while
     * the maximum itself is placed within 5 s of them; it is one maximum, found once.
     */
    @Test
    void aMaximumPlacedFromAnEventNearTheStartIsNotFoundAgainByTheEdgeSearch() {
        AbsoluteDate reference = utc("2026-09-29T12:12:07.844");
        for (String start : new String[] {"2026-09-29T12:12:03", "2026-09-29T12:12:04"}) {
            PassFinder.Outcome o = FINDER.find(slDeb(), utc(start));

            PassGrouping.Pass first = o.passes().getFirst();
            assertThat(first.riseClipped()).as(start).isTrue();
            assertThat(first.peakAtEdge()).as(start).isFalse();
            assertThat(first.peakCount()).as(start).isEqualTo(1);
            assertThat(o.at(first.peak().t()).durationFrom(reference)).as(start).isBetween(-1.0, 1.0);
        }
    }

    /** The maximum at 2026-09-29T04:46:37.976Z, 4.724 s before the end of this window. */
    @Test
    void aMaximumPlacedFromAnEventNearTheEndIsNotFoundAgainByTheEdgeSearch() {
        PassFinder.Outcome o = FINDER.find(slDeb(), utc("2026-09-28T04:46:42.700"));

        PassGrouping.Pass last = o.passes().getLast();
        assertThat(last.setClipped()).isTrue();
        assertThat(last.peakAtEdge()).isFalse();
        assertThat(last.peakCount()).isEqualTo(1);
        assertThat(o.at(last.peak().t()).durationFrom(utc("2026-09-29T04:46:37.976"))).isBetween(-1.0, 1.0);
    }

    /** Starts and an end within a few seconds of a 27958 maximum: no pass counts one maximum twice. */
    @Test
    void noPassNearA27958MaximumHasTwoPeaksWithinTwoSeconds() {
        for (String start : new String[] {"2026-09-29T12:12:03", "2026-09-29T12:12:04", "2026-09-28T11:52:13",
            "2026-10-05T10:53:25", "2026-09-28T04:46:42.700"}) {
            PassFinder.Outcome o = FINDER.find(slDeb(), utc(start));

            assertThat(o.passes()).as(start).allSatisfy(p -> {
                for (int i = 1; i < p.peaks().size(); i++) {
                    assertThat(p.peaks().get(i).t() - p.peaks().get(i - 1).t()).as(start).isGreaterThan(2);
                }
            });
        }
    }

    /**
     * The maximum at 2026-09-29T04:46:37.976Z is placed from its event 5.024 s before the end of this window, where
     * the peak is so flat that the edge search can still return a point strictly inside its span next to it; that
     * point is the same maximum, counted once.
     */
    @Test
    void aMaximumJustOverFiveSecondsBeforeTheEndIsCountedOnce() {
        PassFinder.Outcome o = FINDER.find(slDeb(), utc("2026-09-28T04:46:43"));

        PassGrouping.Pass last = o.passes().getLast();
        assertThat(last.setClipped()).isTrue();
        assertThat(last.peakCount()).isEqualTo(1);
        assertThat(o.at(last.peak().t()).durationFrom(utc("2026-09-29T04:46:37.976"))).isBetween(-1.0, 1.0);
    }

    /** The same just over 5 s after the start, for the maximum at 2026-09-29T12:12:07.844Z. */
    @Test
    void aMaximumJustOverFiveSecondsAfterTheStartIsCountedOnce() {
        PassFinder.Outcome o = FINDER.find(slDeb(), utc("2026-09-29T12:12:02.800"));

        PassGrouping.Pass first = o.passes().getFirst();
        assertThat(first.riseClipped()).isTrue();
        assertThat(first.peakCount()).isEqualTo(1);
        assertThat(o.at(first.peak().t()).durationFrom(utc("2026-09-29T12:12:07.844"))).isBetween(-1.0, 1.0);
    }

    /** The window ends at 2026-09-29T12:12:12.900, just over 5 s after the maximum at 12:12:07.844Z. */
    @Test
    void aMaximumJustOverFiveSecondsBeforeAFractionalEndIsCountedOnce() {
        PassFinder.Outcome o = FINDER.find(slDeb(), utc("2026-09-28T12:12:12.900"));

        PassGrouping.Pass last = o.passes().getLast();
        assertThat(last.setClipped()).isTrue();
        assertThat(last.peakCount()).isEqualTo(1);
        assertThat(o.at(last.peak().t()).durationFrom(utc("2026-09-29T12:12:07.844"))).isBetween(-1.0, 1.0);
    }

    /** The largest elevation from positions within 2 s of a time, sampled every millisecond. */
    static double maximumElevationNear(TrackedObject object, AbsoluteDate time) {
        org.orekit.propagation.analytical.tle.TLEPropagator p =
                org.orekit.propagation.analytical.tle.TLEPropagator.selectExtrapolator(object.tle());
        double best = Double.NEGATIVE_INFINITY;
        for (int ms = -2000; ms <= 2000; ms++) {
            best = Math.max(best, FINDER.point(p, time, time.shiftedBy(ms / 1000.0)).elevationDeg());
        }
        return best;
    }

    static TrackedObject iss() {
        return ReferencePasses.elementSet("orbit-core/src/test/resources/celestrak/gp-catnr-25544.json", 25544);
    }
}
