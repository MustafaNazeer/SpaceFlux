package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.query.passes.ReferencePasses.ReferenceObject;
import io.github.mustafanazeer.spaceflux.query.passes.ReferencePasses.Window;

/**
 * The service against the independent reference (docs/risk/orbital-conventions.md 6.8 and 6.9). Every angle is the
 * service's, evaluated at the reference's stored time, against the reference's stored angle; no assertion compares
 * one window with another. Every difference is printed with its ratio to the tolerance.
 */
class PassReferenceTest {

    static final double ANGLE_TOL_DEG = 0.001;
    static final double RISE_SET_AZIMUTH_TOL_DEG = 0.002;
    static final double RISE_SET_TIME_TOL_S = 0.05;
    static final double PEAK_TIME_TOL_S = 1;
    static final double TIMED_PEAK_MIN_DEG = 11;
    /** The first run criterion of the 2026-10-07 amendment to ADR 0013: each compared angle as a position. */
    static final double POSITION_BOUND_RAD = 3.0e-7;
    static final double POSITION_BOUND_FLOOR_M = 0.01;
    static final double PAIRING_S = 60;

    static final PassFinder FINDER = new PassFinder();

    /**
     * positionM is the angle difference times the range (times cos elevation for azimuth), NaN for times; centreM is
     * the object's geocentric distance at that time.
     */
    record Difference(String where, String check, double value, double tolerance, double positionM, double centreM) {

        double ratio() {
            return Math.abs(value) / tolerance;
        }

        double positionBoundM() {
            return POSITION_BOUND_RAD * centreM + POSITION_BOUND_FLOOR_M;
        }

        double positionRatio() {
            return positionM / positionBoundM();
        }
    }

    static ReferencePasses.File reference;
    static final List<Difference> DIFFERENCES = new ArrayList<>();

    @BeforeAll
    static void compareEveryWindow() {
        reference = ReferencePasses.load();
        for (ReferenceObject o : reference.objects()) {
            TrackedObject object = o.elementSet();
            for (Window w : o.windows()) {
                compare(o.noradCatId() + " " + w.kind(), object, w);
            }
        }
        System.out.println("Passes against the reference (service at the reference time minus the reference):");
        System.out.println("where | check | difference | tolerance | ratio | position (m)");
        for (Difference d : DIFFERENCES) {
            System.out.println(String.format(Locale.ROOT, "%s | %s | %.6g | %s | %.4f | %.4f", d.where(), d.check(),
                    d.value(), d.tolerance(), d.ratio(), d.positionM()));
        }
        System.out.println("Largest ratio per check:");
        largest().forEach((check, d) -> System.out.println(String.format(Locale.ROOT,
                "%s | %.6g at %s | tolerance %s | ratio %.4f | position %.4f m", check, d.value(), d.where(),
                d.tolerance(), d.ratio(), d.positionM())));
        for (ReferenceObject o : reference.objects()) {
            String prefix = o.noradCatId() + " ";
            DIFFERENCES.stream().filter(d -> d.where().startsWith(prefix) && !Double.isNaN(d.positionM()))
                    .max((a, b) -> Double.compare(a.positionM(), b.positionM()))
                    .ifPresent(d -> System.out.println(String.format(Locale.ROOT,
                            "Largest position equivalent for %d: %.4f m (%s, %s)", o.noradCatId(), d.positionM(),
                            d.where(), d.check())));
        }
        Difference far = largestPosition();
        System.out.println(String.format(Locale.ROOT,
                "Largest position equivalent: %.4f m (%s, %s, %.6g degree, bound %.4f m at %.0f km from the centre)",
                far.positionM(), far.where(), far.check(), far.value(), far.positionBoundM(), far.centreM() / 1000));
        Difference tight = tightestPosition();
        System.out.println(String.format(Locale.ROOT,
                "Largest position equivalent against its bound: %.4f of %.4f m (%s, %s)", tight.positionM(),
                tight.positionBoundM(), tight.where(), tight.check()));
    }

    static Difference largestPosition() {
        return DIFFERENCES.stream().filter(d -> !Double.isNaN(d.positionM()))
                .max((a, b) -> Double.compare(a.positionM(), b.positionM())).orElseThrow();
    }

    static Difference tightestPosition() {
        return DIFFERENCES.stream().filter(d -> !Double.isNaN(d.positionM()))
                .max((a, b) -> Double.compare(a.positionRatio(), b.positionRatio())).orElseThrow();
    }

    static Map<String, Difference> largest() {
        Map<String, Difference> out = new LinkedHashMap<>();
        for (Difference d : DIFFERENCES) {
            out.merge(d.check(), d, (a, b) -> b.ratio() > a.ratio() ? b : a);
        }
        return out;
    }

    static void compare(String window, TrackedObject object, Window w) {
        AbsoluteDate start = w.startDate();
        PassFinder.Outcome outcome = FINDER.find(object, start);
        assertThat(outcome.status()).as(window).isEqualTo(PassStatus.COMPUTED);
        assertThat(outcome.windowEnd().durationFrom(start)).isEqualTo(86_400);
        assertThat(w.ambiguousMaxima()).as(window + " maxima within 0.001 degree of 10").isZero();
        List<PassGrouping.Pass> service = outcome.passes();
        assertThat(service).as(window + " pass count").hasSize(w.passes().size());

        TLEPropagator at = TLEPropagator.selectExtrapolator(object.tle());
        for (int i = 0; i < service.size(); i++) {
            PassGrouping.Pass s = service.get(i);
            ReferencePasses.Pass r = w.passes().get(i);
            String where = window + " pass " + (i + 1);
            assertThat(Math.abs(outcome.at(s.peak().t()).durationFrom(r.peak().date()))).as(where + " pairing")
                    .isLessThanOrEqualTo(PAIRING_S);
            assertThat(s.riseClipped()).as(where + " rise_clipped").isEqualTo(r.riseClipped());
            assertThat(s.setClipped()).as(where + " set_clipped").isEqualTo(r.setClipped());
            assertThat(s.peakAtEdge()).as(where + " peak_at_edge").isEqualTo(r.peakAtEdge());
            assertThat(s.peakCount()).as(where + " peak_count").isEqualTo(r.peakCount());
            boolean timed = r.peak().elevationDeg() >= TIMED_PEAK_MIN_DEG;

            if (r.rise() != null) {
                crossing(where + " rise", at, start, outcome.at(s.rise().t()), r.rise(), timed);
            } else {
                edge(where + " start edge", s.startEdge(), r.startEdge(), at, outcome);
            }
            if (r.set() != null) {
                crossing(where + " set", at, start, outcome.at(s.set().t()), r.set(), timed);
            } else {
                edge(where + " end edge", s.endEdge(), r.endEdge(), at, outcome);
            }

            if (r.peakAtEdge()) {
                assertThat(outcome.at(s.peak().t())).as(where + " edge peak time").isEqualTo(r.peak().date());
                angle(where, "edge peak elevation", s.peak().elevationDeg() - r.peak().elevationDeg(), ANGLE_TOL_DEG,
                        at, r.peak().date());
            } else {
                PassGrouping.Point there = FINDER.point(at, start, r.peak().date());
                angle(where, "elevation at reference peak", there.elevationDeg() - r.peak().elevationDeg(),
                        ANGLE_TOL_DEG, at, r.peak().date());
                angle(where, "sky separation in azimuth at reference peak",
                        azimuth(there.azimuthDeg(), r.peak().azimuthDeg()) * Math.cos(Math.toRadians(
                                r.peak().elevationDeg())), ANGLE_TOL_DEG, at, r.peak().date());
                angle(where, "reported peak elevation", s.peak().elevationDeg() - r.peak().elevationDeg(),
                        ANGLE_TOL_DEG, at, outcome.at(s.peak().t()));
                add(where, "peak time", outcome.at(s.peak().t()).durationFrom(r.peak().date()), PEAK_TIME_TOL_S);
            }
        }
    }

    static void crossing(String where, TLEPropagator at, AbsoluteDate start, AbsoluteDate serviceTime,
            ReferencePasses.Point r, boolean timed) {
        PassGrouping.Point there = FINDER.point(at, start, r.date());
        angle(where, "elevation at reference rise or set", there.elevationDeg() - r.elevationDeg(), ANGLE_TOL_DEG,
                at, r.date());
        double azimuth = azimuth(there.azimuthDeg(), r.azimuthDeg());
        DIFFERENCES.add(new Difference(where, "azimuth at reference rise or set", azimuth, RISE_SET_AZIMUTH_TOL_DEG,
                Math.abs(Math.toRadians(azimuth)) * Math.cos(Math.toRadians(r.elevationDeg()))
                        * FINDER.rangeM(at, r.date()), centreM(at, r.date())));
        if (timed) {
            add(where, "rise or set time", serviceTime.durationFrom(r.date()), RISE_SET_TIME_TOL_S);
        }
    }

    static void edge(String where, PassGrouping.Point s, ReferencePasses.Point r, TLEPropagator at,
            PassFinder.Outcome outcome) {
        assertThat(s).as(where).isNotNull();
        assertThat(outcome.at(s.t())).as(where + " time").isEqualTo(r.date());
        angle(where, "elevation at the window edge", s.elevationDeg() - r.elevationDeg(), ANGLE_TOL_DEG, at,
                r.date());
    }

    static double azimuth(double service, double reference) {
        double d = service - reference;
        return d - 360 * Math.rint(d / 360);
    }

    static void add(String where, String check, double value, double tolerance) {
        DIFFERENCES.add(new Difference(where, check, value, tolerance, Double.NaN, Double.NaN));
    }

    /** An angle difference already in sky terms (elevation, or azimuth times cos elevation), in degrees. */
    static void angle(String where, String check, double value, double tolerance, TLEPropagator at,
            AbsoluteDate date) {
        DIFFERENCES.add(new Difference(where, check, value, tolerance,
                Math.abs(Math.toRadians(value)) * FINDER.rangeM(at, date), centreM(at, date)));
    }

    static double centreM(TLEPropagator at, AbsoluteDate date) {
        return at.getPVCoordinates(date).getPosition().getNorm();
    }

    @Test
    void everyDifferenceIsWithinItsTolerance() {
        assertThat(DIFFERENCES).hasSizeGreaterThan(100);
        assertThat(DIFFERENCES).allSatisfy(d -> assertThat(d.ratio()).as(d.toString()).isLessThanOrEqualTo(1));
    }

    @Test
    void everyComparedAngleIsWithinTheFrameRotationBoundAsAPosition() {
        assertThat(DIFFERENCES.stream().filter(d -> !Double.isNaN(d.positionM())).count()).isGreaterThan(100);
        assertThat(DIFFERENCES).filteredOn(d -> !Double.isNaN(d.positionM())).allSatisfy(d -> assertThat(
                d.positionM()).as(d.toString()).isLessThanOrEqualTo(d.positionBoundM()));
    }

    @Test
    void everyCheckOfTheTableRan() {
        assertThat(largest().keySet()).containsExactlyInAnyOrder("elevation at reference rise or set",
                "azimuth at reference rise or set", "rise or set time", "elevation at reference peak",
                "sky separation in azimuth at reference peak", "reported peak elevation", "peak time", "elevation at the window edge",
                "edge peak elevation");
    }

    /** 6.9, "Reference file itself". */
    @Test
    void theReferenceFileHoldsWhatItsProvenanceStates() {
        double largestCrossingOffset = 0;
        for (ReferenceObject o : reference.objects()) {
            assertThat(ReferencePasses.sha256(ReferencePasses.recorded(o.sourceFile()))).as(o.sourceFile())
                    .isEqualTo(o.sourceSha256());
            assertThat(o.windows()).hasSize(3);
            for (Window w : o.windows()) {
                for (ReferencePasses.Pass p : w.passes()) {
                    for (ReferencePasses.Point c : new ReferencePasses.Point[] {p.rise(), p.set()}) {
                        if (c != null) {
                            largestCrossingOffset = Math.max(largestCrossingOffset, Math.abs(c.elevationDeg() - 10));
                        }
                    }
                    for (ReferencePasses.Point m : p.peaks()) {
                        assertThat(Math.abs(m.elevationDeg() - 10)).isGreaterThan(0.001);
                    }
                }
            }
        }
        assertThat(largestCrossingOffset).isLessThanOrEqualTo(0.0002);
        assertThat(reference.root().get("observer").get("latitude_deg").asDouble()).isEqualTo(PassFinder.LATITUDE_DEG);
        assertThat(reference.root().get("observer").get("longitude_deg").asDouble())
                .isEqualTo(PassFinder.LONGITUDE_DEG);
        assertThat(reference.root().get("observer").get("height_m").asDouble()).isEqualTo(PassFinder.HEIGHT_M);
        assertThat(reference.root().get("settings").get("threshold_deg").asDouble()).isEqualTo(PassGrouping.MASK_DEG);
        assertThat(reference.root().get("settings").get("window_s").asDouble()).isEqualTo(PassFinder.WINDOW_S);
    }

    /** 6.8: the cut window of 57036 starts before the element set's epoch, and the service accepts it. */
    @Test
    void theCutWindowStartingBeforeItsEpochIsAccepted() {
        ReferenceObject aj = reference.objects().stream().filter(o -> o.noradCatId() == 57036).findFirst()
                .orElseThrow();
        Window w = aj.windows().stream().filter(x -> x.kind().equals("ends_inside_pass")).findFirst().orElseThrow();
        TrackedObject object = aj.elementSet();

        assertThat(w.startDate().isBefore(object.tle().getDate())).isTrue();
        assertThat(FINDER.find(object, w.startDate()).status()).isEqualTo(PassStatus.COMPUTED);
    }

    /** Both clipped cases of 6.4 occur at both edges in the reference, so the edge rules are all compared. */
    @Test
    void theReferenceCoversBothClippedCasesAtBothEdges() {
        boolean[] seen = new boolean[4];
        for (ReferenceObject o : reference.objects()) {
            for (Window w : o.windows()) {
                for (ReferencePasses.Pass p : w.passes()) {
                    if (p.riseClipped()) {
                        seen[p.peakAtEdge() ? 0 : 1] = true;
                    }
                    if (p.setClipped()) {
                        seen[p.peakAtEdge() ? 2 : 3] = true;
                    }
                }
            }
        }
        assertThat(seen).containsOnly(true);
    }
}
