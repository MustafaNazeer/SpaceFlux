package io.github.mustafanazeer.spaceflux.risk.screening;

import java.util.List;

import org.orekit.time.AbsoluteDate;

public record ScreeningResult(AbsoluteDate start, AbsoluteDate end, List<CloseApproach> approaches,
        List<SuppressedPair> suppressed, List<Rejected> rejected, List<NotScreened> notScreened,
        List<Integer> epochAfterStart) {

    public record Rejected(int catalogNumber, String reason) {
    }

    public record NotScreened(int catalogNumber, AbsoluteDate screenedUntil, String reason) {
    }
}
