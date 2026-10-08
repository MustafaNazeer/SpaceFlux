package io.github.mustafanazeer.spaceflux.orbit;

/** docs/risk/orbital-conventions.md Section 2.4, "Stale element sets". */
public final class ElementSetLimits {

    /** Measured at the start of the span being propagated: exactly this old is accepted, any older is stale. */
    public static final double MAX_AGE_S = 10 * 86_400;

    private ElementSetLimits() {
    }
}
