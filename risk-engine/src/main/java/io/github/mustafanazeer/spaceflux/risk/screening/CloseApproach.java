package io.github.mustafanazeer.spaceflux.risk.screening;

import org.orekit.time.AbsoluteDate;

public record CloseApproach(int catalogNumberA, int catalogNumberB, AbsoluteDate tca, double missM,
        double relativeSpeedMPerS, double elementAgeDaysA, double elementAgeDaysB) {
}
