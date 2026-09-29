package io.github.mustafanazeer.spaceflux.risk.screening;

import org.orekit.time.AbsoluteDate;

/**
 * A pair that was not screened for close approaches, with the separation sampled every
 * {@link ScreeningSettings#SAMPLE_STEP_S} over the pair's joint screenable span, so a stale list entry or a wrong
 * suppression stays visible. The true minimum between samples can be smaller than {@code minSeparationM}.
 */
public record SuppressedPair(int watchlistNumber, int otherNumber, Mechanism mechanism, String detail,
        double minSeparationM, AbsoluteDate minSeparationAt, double maxSeparationM, boolean stackEntryMayBeStale) {

    public enum Mechanism {
        STATIC_STACK,
        CO_ORBITING,
        SAME_ELEMENTS
    }
}
