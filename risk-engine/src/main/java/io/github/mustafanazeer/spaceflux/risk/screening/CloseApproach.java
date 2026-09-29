package io.github.mustafanazeer.spaceflux.risk.screening;

import org.orekit.time.AbsoluteDate;

/**
 * The watchlist side is the primary object of the search; the other side is the catalog object it met. Element ages
 * are TCA minus epoch, in days, and are negative when the element set epoch is after the TCA.
 */
public record CloseApproach(int watchlistNumber, int otherNumber, AbsoluteDate tca, double missM,
        double relativeSpeedMPerS, double elementAgeDaysWatchlist, double elementAgeDaysOther) {
}
