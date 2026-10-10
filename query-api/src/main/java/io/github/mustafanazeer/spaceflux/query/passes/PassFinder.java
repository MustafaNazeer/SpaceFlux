package io.github.mustafanazeer.spaceflux.query.passes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;

import org.hipparchus.util.FastMath;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.ElevationDetector;
import org.orekit.propagation.events.ElevationExtremumDetector;
import org.orekit.propagation.events.handlers.RecordAndContinue;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

import io.github.mustafanazeer.spaceflux.orbit.ElementSetLimits;
import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;

/**
 * Geometric passes of one element set over the fixed observer for 24 hours (ADR 0013, docs/risk/orbital-conventions.md
 * Section 6). Thread safe: each call builds its own propagator.
 */
public class PassFinder {

    /** NGS mark GEMINI 3, PID AW6997, NAD 83(1993) used as WGS84 (6.1). */
    static final String OBSERVER_NAME = "GEMINI 3";
    static final String OBSERVER_PID = "AW6997";
    static final double LATITUDE_DEG = 29.557976853;
    static final double LONGITUDE_DEG = -95.091374225;
    static final double HEIGHT_M = -22.182;

    static final double WINDOW_S = 86_400;
    static final double EXTREMUM_MAX_CHECK_S = 60;
    static final double ELEVATION_MAX_CHECK_S = 10;
    static final double THRESHOLD_S = 1e-6;
    static final int MAX_ITERATIONS = 100;
    /** The span centred on an extremum event over which the peak is placed, and the bracket it is placed to (6.4). */
    static final double PEAK_SPAN_S = 10;
    static final double PEAK_BRACKET_S = 0.001;
    /** A maximum event this far below the mask is still placed, since its event time can be off the peak (6.4). */
    static final double NEAR_MASK_DEG = 0.001;
    /** The span next to a window edge searched for a maximum whose event fell outside the window (6.4). */
    static final double EDGE_SPAN_S = 5;
    /** An edge search result this close to a maximum placed from an event is that maximum again (6.4). */
    static final double SAME_MAXIMUM_S = 1;
    /** The decay latch's sample step, the screening's own (6.5). */
    static final double DECAY_STEP_S = 10;

    private final TopocentricFrame observer;

    public PassFinder() {
        OrekitData.load();
        OneAxisEllipsoid earth = new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING, FramesFactory.getITRF(IERSConventions.IERS_2010, true));
        observer = new TopocentricFrame(earth, new GeodeticPoint(FastMath.toRadians(LATITUDE_DEG),
                FastMath.toRadians(LONGITUDE_DEG), HEIGHT_M), OBSERVER_NAME);
    }

    /**
     * @param passes null unless status is COMPUTED; times in each pass are seconds from windowStart
     * @param searchEnd the window end, or the last good sample when the decay latch stopped the search inside it
     */
    record Outcome(AbsoluteDate windowStart, AbsoluteDate windowEnd, PassStatus status, String reason,
            AbsoluteDate searchEnd, String stopReason, AbsoluteDate epoch, List<PassGrouping.Pass> passes) {

        double elementAgeDaysAt(double t) {
            return windowStart.shiftedBy(t).durationFrom(epoch) / 86_400;
        }

        AbsoluteDate at(double t) {
            return windowStart.shiftedBy(t);
        }
    }

    TopocentricFrame observer() {
        return observer;
    }

    Outcome find(TrackedObject object, AbsoluteDate start) {
        AbsoluteDate end = start.shiftedBy(WINDOW_S);
        AbsoluteDate epoch = object.tle().getDate();
        if (object.deepSpace()) {
            return refused(start, end, epoch, PassStatus.DEEP_SPACE, String.format(Locale.ROOT,
                    "Orekit propagates this element set with the deep space model (recorded period %.1f min); passes "
                            + "are computed only for near Earth objects", object.periodMinutes()));
        }
        double ageS = start.durationFrom(epoch);
        if (ageS > ElementSetLimits.MAX_AGE_S) {
            return refused(start, end, epoch, PassStatus.STALE_ELEMENT_SET, String.format(Locale.ROOT,
                    "element set is %.1f days old at the window start, over the %.0f day limit; no passes computed",
                    ageS / 86_400, ElementSetLimits.MAX_AGE_S / 86_400));
        }
        ObjectTrack track = ObjectTrack.sample(object, start, end, DECAY_STEP_S);
        if (track.stopKind() == ObjectTrack.StopKind.CANNOT_PROPAGATE) {
            return refused(start, end, epoch, PassStatus.CANNOT_PROPAGATE, track.stopReason());
        }
        if (track.stopKind() == ObjectTrack.StopKind.STOPPED_BEFORE_WINDOW) {
            return refused(start, end, epoch, PassStatus.DECAYED, "the element set fails the decay checks before "
                    + "the window start: " + track.stopReason());
        }
        AbsoluteDate searchEnd = track.stopKind() == ObjectTrack.StopKind.STOPPED_IN_WINDOW
                ? track.screenableUntil() : end;

        TLEPropagator propagator = TLEPropagator.selectExtrapolator(object.tle());
        RecordAndContinue recorder = new RecordAndContinue();
        propagator.addEventDetector(new ElevationExtremumDetector(observer)
                .withMaxCheck(EXTREMUM_MAX_CHECK_S).withThreshold(THRESHOLD_S).withMaxIter(MAX_ITERATIONS)
                .withHandler(recorder));
        propagator.addEventDetector(new ElevationDetector(observer)
                .withConstantElevation(FastMath.toRadians(PassGrouping.MASK_DEG))
                .withMaxCheck(ELEVATION_MAX_CHECK_S).withThreshold(THRESHOLD_S).withMaxIter(MAX_ITERATIONS)
                .withHandler(recorder));
        if (searchEnd.durationFrom(start) > 0) {
            propagator.propagate(start, searchEnd);
        }

        TLEPropagator positions = TLEPropagator.selectExtrapolator(object.tle());
        List<PassGrouping.Event> detected = new ArrayList<>();
        for (RecordAndContinue.Event e : recorder.getEvents()) {
            PassGrouping.Kind kind = e.getDetector() instanceof ElevationDetector
                    ? e.isIncreasing() ? PassGrouping.Kind.RISE : PassGrouping.Kind.SET
                    : e.isIncreasing() ? PassGrouping.Kind.MINIMUM : PassGrouping.Kind.MAXIMUM;
            PassGrouping.Point p = point(e.getState(), start);
            detected.add(new PassGrouping.Event(kind, p.t(), p.elevationDeg(), p.azimuthDeg()));
        }
        double searchEndT = searchEnd.durationFrom(start);
        List<PassGrouping.Event> events = placeMaxima(detected, x -> point(positions, start, start.shiftedBy(x)),
                searchEndT);
        List<PassGrouping.Pass> passes = PassGrouping.group(point(positions, start, start),
                point(positions, start, searchEnd), events);
        return new Outcome(start, end, PassStatus.COMPUTED, null, searchEnd,
                searchEnd.equals(end) ? null : track.stopReason(), epoch, passes);
    }

    /** Elevation and azimuth of the object at a date, as the pass search computes them; t is seconds from start. */
    PassGrouping.Point point(TLEPropagator propagator, AbsoluteDate start, AbsoluteDate date) {
        return point(propagator.propagate(date), start);
    }

    /**
     * Maxima from the detected events, in time order with the other events (6.4). The extremum event's time comes
     * from an elevation rate built with SGP4's velocity, which is not exactly the derivative of its position, so each
     * maximum at or within NEAR_MASK_DEG below the mask is placed on the elevation from positions alone, searched over
     * PEAK_SPAN_S centred on the event and clamped to [0, searchEnd]; it is kept only if it lands inside the search
     * and at or above the mask. An edge at or above the mask is then searched over the EDGE_SPAN_S inside the search
     * next to it, because the event of a maximum just inside can fall outside, unless a maximum placed from an event
     * already lies within EDGE_SPAN_S of that edge or an event's maximum was dropped on it; only a result strictly
     * inside that span and more than SAME_MAXIMUM_S from every maximum placed from an event (kept or below the mask)
     * is a maximum, since a very flat peak just past EDGE_SPAN_S can pull the search off its inner end.
     *
     * @param at the elevation and azimuth at t seconds from the window start, from positions; called only inside
     *     [0, searchEnd]
     */
    static List<PassGrouping.Event> placeMaxima(List<PassGrouping.Event> detected,
            DoubleFunction<PassGrouping.Point> at, double searchEnd) {
        DoubleUnaryOperator elevation = x -> at.apply(x).elevationDeg();
        List<PassGrouping.Event> events = new ArrayList<>();
        boolean startSearched = false;
        boolean endSearched = false;
        List<Double> placed = new ArrayList<>();
        for (PassGrouping.Event e : detected) {
            if (e.kind() != PassGrouping.Kind.MAXIMUM
                    || e.elevationDeg() < PassGrouping.MASK_DEG - NEAR_MASK_DEG) {
                events.add(e);
                continue;
            }
            PeakSearch.Found peak = PeakSearch.peakNear(elevation, e.t(), PEAK_SPAN_S, 0, searchEnd, PEAK_BRACKET_S);
            if (peak.end() == PeakSearch.End.NONE) {
                placed.add(peak.t());
                maximumAt(at, peak.t(), events);
                startSearched |= peak.t() <= EDGE_SPAN_S;
                endSearched |= searchEnd - peak.t() <= EDGE_SPAN_S;
            } else {
                startSearched |= peak.end() == PeakSearch.End.LOWER;
                endSearched |= peak.end() == PeakSearch.End.UPPER;
            }
        }
        if (!startSearched && at.apply(0).aboveMask()) {
            edgeMaximum(at, elevation, 0, Math.min(EDGE_SPAN_S, searchEnd), placed, events);
        }
        if (!endSearched && at.apply(searchEnd).aboveMask()) {
            edgeMaximum(at, elevation, Math.max(searchEnd - EDGE_SPAN_S, 0), searchEnd, placed, events);
        }
        events.sort(Comparator.comparingDouble(PassGrouping.Event::t));
        return events;
    }

    /**
     * A result on the window edge or on the inner end of [a, b] means no maximum next to that edge, and one within
     * SAME_MAXIMUM_S of a placed maximum is that maximum.
     */
    private static void edgeMaximum(DoubleFunction<PassGrouping.Point> at, DoubleUnaryOperator elevation, double a,
            double b, List<Double> placed, List<PassGrouping.Event> events) {
        PeakSearch.Found found = PeakSearch.search(elevation, a, b, PEAK_BRACKET_S);
        if (found.end() == PeakSearch.End.NONE
                && placed.stream().noneMatch(t -> Math.abs(t - found.t()) <= SAME_MAXIMUM_S)) {
            maximumAt(at, found.t(), events);
        }
    }

    private static void maximumAt(DoubleFunction<PassGrouping.Point> at, double t, List<PassGrouping.Event> events) {
        PassGrouping.Point p = at.apply(t);
        if (p.aboveMask()) {
            events.add(new PassGrouping.Event(PassGrouping.Kind.MAXIMUM, p.t(), p.elevationDeg(), p.azimuthDeg()));
        }
    }

    /** Distance from the observer to the object at a date, in metres. */
    double rangeM(TLEPropagator propagator, AbsoluteDate date) {
        SpacecraftState s = propagator.propagate(date);
        return observer.getRange(s.getPosition(), s.getFrame(), s.getDate());
    }

    private PassGrouping.Point point(SpacecraftState s, AbsoluteDate start) {
        double elevation = observer.getElevation(s.getPosition(), s.getFrame(), s.getDate());
        double azimuth = observer.getAzimuth(s.getPosition(), s.getFrame(), s.getDate());
        return new PassGrouping.Point(s.getDate().durationFrom(start), FastMath.toDegrees(elevation),
                FastMath.toDegrees(azimuth));
    }

    private static Outcome refused(AbsoluteDate start, AbsoluteDate end, AbsoluteDate epoch, PassStatus status,
            String reason) {
        return new Outcome(start, end, status, reason, null, null, epoch, null);
    }
}
