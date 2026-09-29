package io.github.mustafanazeer.spaceflux.risk.weather;

/**
 * A level derived mechanically from one SWPC value, never SWPC's own issued scale level
 * (docs/risk/space-weather-scales.md Section 4). Level 0 is "none": a valid value below level 1. {@code satellite} is
 * null for Kp, which is not measured by one satellite. {@code timeTag} is SWPC's own string: for Kp it has no zone
 * designator and marks the start of a 3 hour UTC interval (Section 1.3); for GOES it ends in Z and marks one 1 minute
 * or 5 minute sample.
 */
public record DerivedLevel(Scale scale, int level, String product, String timeTag, Integer satellite, double value) {

    public String label() {
        return level == 0 ? "none" : scale.name() + level;
    }
}
