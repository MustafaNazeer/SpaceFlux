package io.github.mustafanazeer.spaceflux.risk.screening;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.orekit.time.AbsoluteDate;

/**
 * Screens watchlist objects against the catalog over one window (docs/risk/orbital-conventions.md Section 3).
 * Nothing is dropped silently: rejected watchlist objects, objects that stop being screenable, and suppressed
 * pairs are all part of the result.
 */
public final class Screening {

    private static final Logger LOG = LoggerFactory.getLogger(Screening.class);

    private final StationStacks stacks;
    private final double coOrbitingBoundM;

    public Screening(StationStacks stacks, double coOrbitingBoundM) {
        this.stacks = stacks;
        this.coOrbitingBoundM = coOrbitingBoundM;
    }

    public ScreeningResult run(List<TrackedObject> watchlist, List<TrackedObject> catalog, AbsoluteDate start) {
        AbsoluteDate end = start.shiftedBy(ScreeningSettings.WINDOW_S);
        List<ScreeningResult.Rejected> rejected = new ArrayList<>();
        List<Integer> epochAfterStart = new ArrayList<>();
        List<TrackedObject> accepted = new ArrayList<>();
        for (TrackedObject w : watchlist) {
            if (w.periodMinutes() >= ScreeningSettings.DEEP_SPACE_PERIOD_MIN) {
                rejected.add(new ScreeningResult.Rejected(w.catalogNumber(), String.format(Locale.ROOT,
                        "period %.1f min is in the deep space regime (225 min or more), which is not screened",
                        w.periodMinutes())));
            } else if (admit(w, start, rejected, epochAfterStart)) {
                accepted.add(w);
            }
        }
        List<TrackedObject> admittedCatalog = new ArrayList<>();
        for (TrackedObject c : catalog) {
            boolean alreadyChecked = watchlist.stream().anyMatch(w -> w.catalogNumber() == c.catalogNumber());
            if (alreadyChecked ? accepted.contains(c) : admit(c, start, rejected, epochAfterStart)) {
                admittedCatalog.add(c);
            }
        }
        catalog = admittedCatalog;

        Map<Integer, ObjectTrack> tracks = new LinkedHashMap<>();
        for (TrackedObject o : accepted) {
            tracks.computeIfAbsent(o.catalogNumber(), n -> ObjectTrack.sample(o, start, end, ScreeningSettings.SAMPLE_STEP_S));
        }
        for (TrackedObject o : catalog) {
            tracks.computeIfAbsent(o.catalogNumber(), n -> ObjectTrack.sample(o, start, end, ScreeningSettings.SAMPLE_STEP_S));
        }
        List<ScreeningResult.NotScreened> notScreened = new ArrayList<>();
        for (ObjectTrack t : tracks.values()) {
            if (t.stopReason() != null) {
                notScreened.add(new ScreeningResult.NotScreened(t.object().catalogNumber(), t.screenableUntil(), t.stopReason()));
            }
        }

        List<CloseApproach> approaches = new ArrayList<>();
        List<SuppressedPair> suppressed = new ArrayList<>();
        Set<Long> screenedPairs = new HashSet<>();
        for (TrackedObject w : accepted) {
            ObjectTrack a = tracks.get(w.catalogNumber());
            for (TrackedObject c : catalog) {
                int x = w.catalogNumber();
                int y = c.catalogNumber();
                if (x == y || !screenedPairs.add(pairKey(x, y))) {
                    continue;
                }
                ObjectTrack b = tracks.get(y);
                if (!a.screenable() || !b.screenable()) {
                    continue;
                }
                Optional<String> stack = stacks.sharedStack(x, y);
                if (stack.isPresent()) {
                    suppressed.add(new SuppressedPair(x, y, SuppressedPair.Mechanism.STATIC_STACK, stack.get()));
                    continue;
                }
                if (!RadialPrefilter.mayApproach(a, b, ScreeningSettings.REPORT_DISTANCE_M)) {
                    continue;
                }
                double maxSeparation = maxSeparationM(a, b, start);
                if (maxSeparation < coOrbitingBoundM) {
                    suppressed.add(new SuppressedPair(x, y, SuppressedPair.Mechanism.CO_ORBITING, String.format(Locale.ROOT,
                            "separation never exceeds %.1f km, under the %.0f km co-orbiting bound",
                            maxSeparation / 1000, coOrbitingBoundM / 1000)));
                    continue;
                }
                approaches.addAll(ClosestApproachSearch.find(a, b, start, ScreeningSettings.REPORT_DISTANCE_M));
            }
        }
        return new ScreeningResult(start, end, approaches, suppressed, rejected, notScreened, epochAfterStart);
    }

    /** Stale element sets are rejected; an epoch after the window start is accepted, since SGP4 runs backward too. */
    private static boolean admit(TrackedObject o, AbsoluteDate start, List<ScreeningResult.Rejected> rejected,
            List<Integer> epochAfterStart) {
        double ageS = start.durationFrom(o.tle().getDate());
        if (ageS > ScreeningSettings.MAX_ELEMENT_AGE_S) {
            rejected.add(new ScreeningResult.Rejected(o.catalogNumber(), String.format(Locale.ROOT,
                    "element set is %.1f days old at the window start, over the 10 day limit", ageS / 86400)));
            return false;
        }
        if (ageS < 0) {
            LOG.info("element set {} has its epoch {} s after the window start", o.catalogNumber(), -ageS);
            epochAfterStart.add(o.catalogNumber());
        }
        return true;
    }

    private static long pairKey(int x, int y) {
        return ((long) Math.min(x, y) << 32) | Math.max(x, y);
    }

    private static double maxSeparationM(ObjectTrack a, ObjectTrack b, AbsoluteDate start) {
        AbsoluteDate end = ClosestApproachSearch.searchEnd(a, b);
        TLEPropagator pa = TLEPropagator.selectExtrapolator(a.object().tle());
        TLEPropagator pb = TLEPropagator.selectExtrapolator(b.object().tle());
        double max = 0;
        double span = end.durationFrom(start);
        for (long k = 0; k * ScreeningSettings.SAMPLE_STEP_S <= span; k++) {
            AbsoluteDate date = start.shiftedBy(k * ScreeningSettings.SAMPLE_STEP_S);
            max = Math.max(max, pa.getPVCoordinates(date).getPosition().distance(pb.getPVCoordinates(date).getPosition()));
        }
        return max;
    }
}
