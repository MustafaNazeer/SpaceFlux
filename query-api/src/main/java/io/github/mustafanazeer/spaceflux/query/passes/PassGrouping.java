package io.github.mustafanazeer.spaceflux.query.passes;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups an ordered list of elevation events into passes (docs/risk/orbital-conventions.md 6.2 and 6.4). Times are
 * seconds from the window start, so this needs no Orekit. A pass is an interval at or above the 10 degree mask; a
 * pass already above it at the start, or still above it at the end, is clipped there and carries the edge point.
 * Its peaks are the maxima inside it; the highest is its peak. A clipped pass with no maximum inside the window
 * peaks at its higher edge, flagged as not a true maximum.
 */
final class PassGrouping {

    static final double MASK_DEG = 10;

    enum Kind {
        RISE, SET, MAXIMUM, MINIMUM
    }

    record Event(Kind kind, double t, double elevationDeg, double azimuthDeg) {

        Point point() {
            return new Point(t, elevationDeg, azimuthDeg);
        }
    }

    record Point(double t, double elevationDeg, double azimuthDeg) {

        boolean aboveMask() {
            return elevationDeg >= MASK_DEG;
        }
    }

    record Pass(Point rise, boolean riseClipped, Point startEdge, Point set, boolean setClipped, Point endEdge,
            List<Point> peaks, Point peak, boolean peakAtEdge) {

        int peakCount() {
            return peaks.size();
        }
    }

    private PassGrouping() {
    }

    /**
     * @param start the window start with the elevation there
     * @param end the end of the search (the window end, or where the search stopped) with the elevation there
     * @param events rises, sets, maxima and minima strictly inside the search, in time order
     */
    static List<Pass> group(Point start, Point end, List<Event> events) {
        List<Point[]> intervals = new ArrayList<>();
        List<Point> maxima = new ArrayList<>();
        Point open = start.aboveMask() ? start : null;
        boolean clippedOpen = open != null;
        double last = start.t();
        for (Event e : events) {
            if (e.t() < last) {
                throw new IllegalStateException("events out of time order at " + e.t() + " s");
            }
            last = e.t();
            switch (e.kind()) {
                case RISE -> {
                    if (open != null) {
                        throw new IllegalStateException("a rise at " + e.t() + " s while a pass is already open");
                    }
                    open = e.point();
                    clippedOpen = false;
                }
                case SET -> {
                    if (open == null) {
                        throw new IllegalStateException("a set at " + e.t() + " s with no pass open");
                    }
                    intervals.add(new Point[] {clippedOpen ? null : open, e.point()});
                    open = null;
                    clippedOpen = false;
                }
                case MAXIMUM -> maxima.add(e.point());
                case MINIMUM -> {
                }
            }
        }
        if (open != null) {
            if (!end.aboveMask()) {
                throw new IllegalStateException("a pass open at " + open.t() + " s has no set, but the end of the "
                        + "search is below the mask");
            }
            intervals.add(new Point[] {clippedOpen ? null : open, null});
        } else if (end.aboveMask()) {
            throw new IllegalStateException("the end of the search is above the mask, but no pass is open");
        }

        List<Pass> passes = new ArrayList<>();
        for (Point[] interval : intervals) {
            Point rise = interval[0];
            Point set = interval[1];
            double from = rise == null ? start.t() : rise.t();
            double to = set == null ? end.t() : set.t();
            List<Point> peaks = new ArrayList<>();
            Point peak = null;
            for (Point m : maxima) {
                if (m.t() >= from && m.t() <= to) {
                    peaks.add(m);
                    if (peak == null || m.elevationDeg() > peak.elevationDeg()) {
                        peak = m;
                    }
                }
            }
            Point startEdge = rise == null ? start : null;
            Point endEdge = set == null ? end : null;
            boolean atEdge = peak == null;
            if (atEdge) {
                if (startEdge == null && endEdge == null) {
                    throw new IllegalStateException("the pass rising at " + from + " s has no maximum");
                }
                peak = startEdge == null ? endEdge
                        : endEdge == null || startEdge.elevationDeg() >= endEdge.elevationDeg() ? startEdge : endEdge;
            }
            passes.add(new Pass(rise, rise == null, startEdge, set, set == null, endEdge, List.copyOf(peaks), peak,
                    atEdge));
        }
        return passes;
    }
}
