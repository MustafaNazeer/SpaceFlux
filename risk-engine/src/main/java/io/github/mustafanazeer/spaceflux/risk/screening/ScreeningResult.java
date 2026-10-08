package io.github.mustafanazeer.spaceflux.risk.screening;

import java.util.List;

import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack.StopKind;

public record ScreeningResult(AbsoluteDate start, AbsoluteDate end, Coverage coverage, List<CloseApproach> approaches,
        List<SuppressedPair> suppressed, List<Rejected> rejected, List<NotScreened> notScreened,
        List<EpochAfterStart> epochAfterStart, List<DifferingCopy> differingCopies) {

    public ScreeningResult {
        approaches = List.copyOf(approaches);
        suppressed = List.copyOf(suppressed);
        rejected = List.copyOf(rejected);
        notScreened = List.copyOf(notScreened);
        epochAfterStart = List.copyOf(epochAfterStart);
        differingCopies = List.copyOf(differingCopies);
    }

    /**
     * What the run actually covered, so an empty result from an empty catalog is not read as a clear one. Every
     * pair lands in exactly one of: not screenable, suppressed, removed by the prefilter, or searched.
     */
    public record Coverage(int watchlistAccepted, int catalogAdmitted, int pairs, int pairsNotScreenable,
            int pairsRemovedByPrefilter, int pairsSearched) {
    }

    public record Rejected(int catalogNumber, Role role, Code code, String reason) {

        public enum Code {
            DEEP_SPACE,
            STALE_ELEMENT_SET,
            /** A configured watchlist object with no element set in the input. */
            NOT_IN_INPUT
        }
    }

    /**
     * {@code screenedUntil} is set only for {@link StopKind#STOPPED_IN_WINDOW}; the other kinds cover none of the window.
     * An accepted watchlist object is reported in its watchlist role, which is the stronger statement; the catalog side
     * effect shows in {@link Coverage#pairsNotScreenable()}. A watchlist object that was rejected on the watchlist side
     * and admitted as a catalog object is reported in its catalog role.
     */
    public record NotScreened(int catalogNumber, Role role, StopKind kind, AbsoluteDate screenedUntil, String reason) {
    }

    /** Screened by propagating backward from an element set newer than the window start. */
    public record EpochAfterStart(int catalogNumber, double secondsAfterStart) {
    }

    /**
     * Two entries for one catalog number that differ; the newer element set is used. {@code droppedFrom} is the list
     * the dropped entry came from, and {@code elementsDiffer} is false when only the name differs.
     */
    public record DifferingCopy(int catalogNumber, String usedName, AbsoluteDate usedEpoch, String droppedName,
            AbsoluteDate droppedEpoch, Role droppedFrom, boolean elementsDiffer) {
    }
}
