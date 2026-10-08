package io.github.mustafanazeer.spaceflux.orbit;

import java.util.Locale;

import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEConstants;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;

/**
 * SGP4 or SDP4 as Orekit selects them for the element set; states are in TEME, in metres. Not thread safe:
 * Orekit's TLEPropagator keeps propagation state in mutable fields, so use one instance per thread.
 */
public final class Sgp4Propagator {

    /** docs/risk/orbital-conventions.md Section 2.4. */
    private static final double DECAY_ALTITUDE_FLOOR_M = 80_000;

    private static final double DECAY_RADIUS_M = TLEConstants.EARTH_RADIUS * 1000 + DECAY_ALTITUDE_FLOOR_M;

    private final TLEPropagator propagator;

    /** Orekit propagates to the epoch while it initializes, so an unusable element set fails here. */
    public Sgp4Propagator(TLE tle) {
        OrekitData.load();
        try {
            this.propagator = TLEPropagator.selectExtrapolator(tle);
        } catch (RuntimeException e) {
            throw new PropagationStoppedException(
                    "element set " + tle.getSatelliteNumber() + " cannot be propagated: " + e.getMessage(), e);
        }
    }

    /** The raw library state, with no decay checks; for comparison against reference ephemerides. */
    PVCoordinates propagate(AbsoluteDate date) {
        return propagator.getPVCoordinates(date);
    }

    /**
     * The state at the date, or {@link PropagationStoppedException} when Orekit fails, the state is not finite,
     * or the object is below the decay altitude floor. Orekit itself never reports decay.
     */
    public PVCoordinates screeningState(AbsoluteDate date) {
        PVCoordinates pv;
        try {
            pv = propagator.getPVCoordinates(date);
        } catch (RuntimeException e) {
            throw new PropagationStoppedException("propagation failed at " + date.toStringRfc3339(OrekitData.utc()) + ": " + e.getMessage(), e);
        }
        if (pv.getPosition().isNaN() || pv.getPosition().isInfinite()
                || pv.getVelocity().isNaN() || pv.getVelocity().isInfinite()) {
            throw new PropagationStoppedException("state not finite at " + date.toStringRfc3339(OrekitData.utc()));
        }
        double radius = pv.getPosition().getNorm();
        if (radius < DECAY_RADIUS_M) {
            throw new PropagationStoppedException(String.format(Locale.ROOT,
                    "SGP4 altitude %.1f km at %s, under the %.0f km screening floor; screening stops here "
                            + "(a screening convention, not a reentry prediction)",
                    (radius / 1000) - TLEConstants.EARTH_RADIUS, date.toStringRfc3339(OrekitData.utc()),
                    DECAY_ALTITUDE_FLOOR_M / 1000));
        }
        return pv;
    }
}
