package io.github.mustafanazeer.spaceflux.risk.screening;

import org.orekit.propagation.analytical.tle.DeepSDP4;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;

import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;

public record TrackedObject(String name, TLE tle) {

    public int catalogNumber() {
        return tle.getSatelliteNumber();
    }

    public double periodMinutes() {
        return 2 * Math.PI / tle.getMeanMotion() / 60;
    }

    /**
     * Whether Orekit propagates this element set with SDP4, which it decides from the recovered mean motion rather
     * than the recorded one, so the two can disagree near 225 minutes. An element set Orekit cannot initialize is
     * not deep space here; screening lists it as not propagable instead.
     */
    public boolean deepSpace() {
        OrekitData.load();
        try {
            return TLEPropagator.selectExtrapolator(tle) instanceof DeepSDP4;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
