package io.github.mustafanazeer.spaceflux.risk.screening;

import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack;

/**
 * Drops a pair whose widened radial bands are more than the report distance apart, since two points are
 * at least as far apart as the difference of their geocentric radii (docs/risk/orbital-conventions.md 3.3).
 */
public final class RadialPrefilter {

    private RadialPrefilter() {
    }

    public static boolean mayApproach(ObjectTrack a, ObjectTrack b, double reportM) {
        return a.bandMinM() - reportM <= b.bandMaxM() && b.bandMinM() - reportM <= a.bandMaxM();
    }
}
