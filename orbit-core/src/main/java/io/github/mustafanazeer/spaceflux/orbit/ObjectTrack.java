package io.github.mustafanazeer.spaceflux.orbit;

import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;

/**
 * One object sampled over the screening window: how long it can be screened, and its radial band
 * (docs/risk/orbital-conventions.md 2.4 and 3.3). The first unusable state ends the track for good,
 * because altitude along an SGP4 trajectory is not monotonic. Checking starts at the element set epoch when
 * that is earlier than the window, since SGP4 far past a decay returns finite states that pass the per date
 * checks; the samples before the window only feed the latch, not the band.
 */
public record ObjectTrack(TrackedObject object, AbsoluteDate screenableUntil, StopKind stopKind, String stopReason,
        double sampledMinM, double sampledMaxM, double maxRadialRateMPerS, double stepS) {

    public static ObjectTrack sample(TrackedObject object, AbsoluteDate start, AbsoluteDate end, double stepS) {
        Sgp4Propagator propagator;
        try {
            propagator = new Sgp4Propagator(object.tle());
        } catch (PropagationStoppedException e) {
            return new ObjectTrack(object, start, StopKind.CANNOT_PROPAGATE, e.getMessage(), Double.NaN, Double.NaN,
                    Double.NaN, stepS);
        }
        AbsoluteDate epoch = object.tle().getDate();
        AbsoluteDate lastGood = null;
        for (long k = 0; epoch.shiftedBy(k * stepS).isBefore(start); k++) {
            AbsoluteDate date = epoch.shiftedBy(k * stepS);
            try {
                propagator.screeningState(date);
            } catch (PropagationStoppedException e) {
                return new ObjectTrack(object, lastGood == null ? epoch : lastGood,
                        lastGood == null ? StopKind.CANNOT_PROPAGATE : StopKind.STOPPED_BEFORE_WINDOW, e.getMessage(),
                        Double.NaN, Double.NaN, Double.NaN, stepS);
            }
            lastGood = date;
        }
        boolean goodBeforeStart = lastGood != null;
        lastGood = null;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double maxRate = 0;
        StopKind stopKind = null;
        String stopReason = null;
        double span = end.durationFrom(start);
        for (long k = 0; k * stepS <= span; k++) {
            AbsoluteDate date = start.shiftedBy(k * stepS);
            PVCoordinates pv;
            try {
                pv = propagator.screeningState(date);
            } catch (PropagationStoppedException e) {
                stopKind = lastGood != null ? StopKind.STOPPED_IN_WINDOW
                        : goodBeforeStart ? StopKind.STOPPED_BEFORE_WINDOW : StopKind.CANNOT_PROPAGATE;
                stopReason = e.getMessage();
                break;
            }
            double r = pv.getPosition().getNorm();
            min = Math.min(min, r);
            max = Math.max(max, r);
            maxRate = Math.max(maxRate, Math.abs(pv.getPosition().dotProduct(pv.getVelocity()) / r));
            lastGood = date;
        }
        return new ObjectTrack(object, lastGood == null ? start : lastGood, stopKind, stopReason, min, max, maxRate,
                stepS);
    }

    public enum StopKind {
        CANNOT_PROPAGATE,
        STOPPED_BEFORE_WINDOW,
        STOPPED_IN_WINDOW
    }

    public boolean screenable() {
        return !Double.isNaN(sampledMinM) && sampledMinM != Double.POSITIVE_INFINITY;
    }

    public double bandMinM() {
        return sampledMinM - maxRadialRateMPerS * stepS / 2;
    }

    public double bandMaxM() {
        return sampledMaxM + maxRadialRateMPerS * stepS / 2;
    }
}
