package io.github.mustafanazeer.spaceflux.risk.screening;

import org.orekit.propagation.analytical.tle.TLE;

public record TrackedObject(String name, TLE tle) {

    public int catalogNumber() {
        return tle.getSatelliteNumber();
    }

    public double periodMinutes() {
        return 2 * Math.PI / tle.getMeanMotion() / 60;
    }
}
