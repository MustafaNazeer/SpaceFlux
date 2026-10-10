package io.github.mustafanazeer.spaceflux.query.passes;

import java.util.function.DoubleUnaryOperator;

/** Golden section search for the maximum of a function with one peak in a bracket. */
final class PeakSearch {

    private static final double INVERSE_PHI = (Math.sqrt(5) - 1) / 2;

    /** Where the final bracket of a search ended up: strictly inside [a, b], or touching one of its ends. */
    enum End {
        NONE, LOWER, UPPER
    }

    /** The middle of the final bracket, and which end of [a, b] that bracket touches, if any. */
    record Found(double t, End end) {
    }

    private PeakSearch() {
    }

    /**
     * The middle of the final bracket, once it is no wider than {@code bracket}. A maximum that the search pushes
     * against either end of [a, b] is not a peak inside the bracket, so it is an error rather than a result.
     */
    static double maximize(DoubleUnaryOperator f, double a, double b, double bracket) {
        Found found = search(f, a, b, bracket);
        if (found.end() != End.NONE) {
            throw atEnd(found, a, b);
        }
        return found.t();
    }

    /**
     * The peak near an extremum event at {@code event}, searched over {@code span} centred on it and clamped to
     * [windowStart, windowEnd], so nothing is evaluated outside the window. End NONE: the peak is at t. End LOWER or
     * UPPER: the maximum fell on the window start or end, so the true maximum lies outside the window and there is no
     * peak. A maximum at a bracket end that is not a window edge is an error.
     */
    static Found peakNear(DoubleUnaryOperator f, double event, double span, double windowStart, double windowEnd,
            double bracket) {
        double a = Math.max(event - span / 2, windowStart);
        double b = Math.min(event + span / 2, windowEnd);
        Found found = search(f, a, b, bracket);
        if (found.end() == End.NONE) {
            return found;
        }
        double end = found.end() == End.LOWER ? a : b;
        if (end == windowStart) {
            return new Found(found.t(), End.LOWER);
        }
        if (end == windowEnd) {
            return new Found(found.t(), End.UPPER);
        }
        throw atEnd(found, a, b);
    }

    /**
     * Golden section over [a, b]. The ends themselves are never evaluated. A bracket no wider than {@code bracket}
     * from the start touches both ends and is reported at its lower end.
     */
    static Found search(DoubleUnaryOperator f, double a, double b, double bracket) {
        double lo = a;
        double hi = b;
        double x1 = hi - INVERSE_PHI * (hi - lo);
        double x2 = lo + INVERSE_PHI * (hi - lo);
        double f1 = f.applyAsDouble(x1);
        double f2 = f.applyAsDouble(x2);
        while (hi - lo > bracket) {
            if (f1 >= f2) {
                hi = x2;
                x2 = x1;
                f2 = f1;
                x1 = hi - INVERSE_PHI * (hi - lo);
                f1 = f.applyAsDouble(x1);
            } else {
                lo = x1;
                x1 = x2;
                f1 = f2;
                x2 = lo + INVERSE_PHI * (hi - lo);
                f2 = f.applyAsDouble(x2);
            }
        }
        End end = lo == a ? End.LOWER : hi == b ? End.UPPER : End.NONE;
        return new Found((lo + hi) / 2, end);
    }

    private static IllegalStateException atEnd(Found found, double a, double b) {
        return new IllegalStateException("the maximum searched for between " + a + " s and " + b + " s lies at the "
                + (found.end() == End.LOWER ? "lower" : "upper") + " end of its bracket");
    }
}
