package io.github.mustafanazeer.spaceflux.risk.screening;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mustafanazeer.spaceflux.orbit.ElementSetLimits;
import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack;
import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack.StopKind;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult.Coverage;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult.DifferingCopy;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult.EpochAfterStart;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult.NotScreened;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult.Rejected;

/**
 * Screens watchlist objects against the catalog over one window (docs/risk/orbital-conventions.md Section 3).
 * Objects are identified by catalog number: when the inputs hold differing entries for one number, the newest
 * element set is used for both roles and the other is listed. Nothing is dropped silently: rejected objects,
 * objects that stop being screenable, suppressed pairs, and differing copies are all part of the result.
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
        Map<Integer, Map<TrackedObject, Role>> copies = new LinkedHashMap<>();
        Set<Integer> watchlistNumbers = new LinkedHashSet<>();
        for (TrackedObject o : watchlist) {
            copies.computeIfAbsent(o.catalogNumber(), n -> new LinkedHashMap<>()).putIfAbsent(o, Role.WATCHLIST);
            watchlistNumbers.add(o.catalogNumber());
        }
        Set<Integer> catalogNumbers = new LinkedHashSet<>(watchlistNumbers);
        for (TrackedObject o : catalog) {
            copies.computeIfAbsent(o.catalogNumber(), n -> new LinkedHashMap<>()).putIfAbsent(o, Role.CATALOG);
            catalogNumbers.add(o.catalogNumber());
        }
        Map<Integer, TrackedObject> byNumber = new LinkedHashMap<>();
        List<DifferingCopy> differingCopies = new ArrayList<>();
        copies.forEach((n, entries) -> byNumber.put(n, newest(entries, differingCopies)));

        List<Rejected> rejected = new ArrayList<>();
        List<TrackedObject> accepted = new ArrayList<>();
        for (int n : watchlistNumbers) {
            TrackedObject w = byNumber.get(n);
            if (w.deepSpace()) {
                rejected.add(new Rejected(n, Role.WATCHLIST, Rejected.Code.DEEP_SPACE, String.format(Locale.ROOT,
                        "Orekit propagates this element set with the deep space model (periods of %.0f min or more, "
                                + "judged from the mean motion SGP4 recovers; recorded period %.1f min), a regime that "
                                + "is not screened; not screened as a watchlist object against the catalog, but it can "
                                + "still appear as the other object in another watchlist object's pair",
                        ScreeningSettings.DEEP_SPACE_PERIOD_MIN, w.periodMinutes())));
            } else if (fresh(w, Role.WATCHLIST, start, rejected)) {
                accepted.add(w);
            }
        }
        List<TrackedObject> admitted = new ArrayList<>();
        for (int n : catalogNumbers) {
            TrackedObject c = byNumber.get(n);
            if (fresh(c, Role.CATALOG, start, rejected)) {
                admitted.add(c);
            }
        }

        Map<Integer, ObjectTrack> tracks = new LinkedHashMap<>();
        List<EpochAfterStart> epochAfterStart = new ArrayList<>();
        for (TrackedObject o : concat(accepted, admitted)) {
            if (tracks.containsKey(o.catalogNumber())) {
                continue;
            }
            tracks.put(o.catalogNumber(), ObjectTrack.sample(o, start, end, ScreeningSettings.SAMPLE_STEP_S));
            double after = o.tle().getDate().durationFrom(start);
            if (after > 0) {
                LOG.info("element set {} has its epoch {} s after the window start", o.catalogNumber(), after);
                epochAfterStart.add(new EpochAfterStart(o.catalogNumber(), after));
            }
        }
        Set<Integer> acceptedNumbers = new HashSet<>();
        accepted.forEach(o -> acceptedNumbers.add(o.catalogNumber()));
        List<NotScreened> notScreened = new ArrayList<>();
        for (ObjectTrack t : tracks.values()) {
            if (t.stopReason() != null) {
                int n = t.object().catalogNumber();
                notScreened.add(new NotScreened(n, acceptedNumbers.contains(n) ? Role.WATCHLIST : Role.CATALOG,
                        t.stopKind(), t.stopKind() == StopKind.STOPPED_IN_WINDOW ? t.screenableUntil() : null,
                        t.stopReason()));
            }
        }

        List<CloseApproach> approaches = new ArrayList<>();
        List<SuppressedPair> suppressed = new ArrayList<>();
        Set<Long> formed = new HashSet<>();
        int notScreenable = 0;
        int prefiltered = 0;
        int searched = 0;
        for (TrackedObject w : accepted) {
            ObjectTrack a = tracks.get(w.catalogNumber());
            for (TrackedObject c : admitted) {
                int x = w.catalogNumber();
                int y = c.catalogNumber();
                if (x == y || !formed.add(pairKey(x, y))) {
                    continue;
                }
                ObjectTrack b = tracks.get(y);
                if (!a.screenable() || !b.screenable()) {
                    notScreenable++;
                    continue;
                }
                Optional<String> stack = stacks.sharedStack(x, y);
                if (stack.isPresent()) {
                    suppressed.add(stackSuppression(x, y, stack.get(), separation(a, b, start, Double.MAX_VALUE),
                            start, ClosestApproachSearch.searchEnd(a, b)));
                    continue;
                }
                if (ClosestApproachSearch.sameElements(a.object().tle(), b.object().tle())) {
                    suppressed.add(new SuppressedPair(x, y, SuppressedPair.Mechanism.SAME_ELEMENTS, null,
                            "not screened for close approaches: identical element sets, so the propagated separation "
                                    + "is 0 km throughout; likely a duplicate record or a set shared by attached objects",
                            0, start, 0, false));
                    continue;
                }
                if (!RadialPrefilter.mayApproach(a, b, ScreeningSettings.REPORT_DISTANCE_M)) {
                    prefiltered++;
                    continue;
                }
                if (!ClosestApproachSearch.searchEnd(a, b).isBefore(end)) {
                    Separation s = separation(a, b, start, coOrbitingBoundM);
                    if (s != null) {
                        suppressed.add(coOrbitingSuppression(x, y, s, start, end));
                        continue;
                    }
                }
                searched++;
                approaches.addAll(ClosestApproachSearch.find(a, b, start, ScreeningSettings.REPORT_DISTANCE_M));
            }
        }
        Coverage coverage = new Coverage(accepted.size(), admitted.size(), formed.size(), notScreenable, prefiltered,
                searched);
        return new ScreeningResult(start, end, coverage, approaches, suppressed, rejected, notScreened, epochAfterStart,
                differingCopies);
    }

    /**
     * The entry with the latest epoch (the first listed, watchlist before catalog, on a tie); every other distinct
     * entry is recorded with the list it came from.
     */
    private static TrackedObject newest(Map<TrackedObject, Role> entries, List<DifferingCopy> differing) {
        TrackedObject used = null;
        for (TrackedObject o : entries.keySet()) {
            if (used == null || o.tle().getDate().isAfter(used.tle().getDate())) {
                used = o;
            }
        }
        for (Map.Entry<TrackedObject, Role> e : entries.entrySet()) {
            TrackedObject o = e.getKey();
            if (o != used) {
                differing.add(new DifferingCopy(o.catalogNumber(), used.name(), used.tle().getDate(), o.name(),
                        o.tle().getDate(), e.getValue(), !o.tle().equals(used.tle())));
            }
        }
        return used;
    }

    /** Stale element sets are rejected; an epoch after the window start is accepted, since SGP4 runs backward too. */
    private static boolean fresh(TrackedObject o, Role role, AbsoluteDate start, List<Rejected> rejected) {
        double ageS = start.durationFrom(o.tle().getDate());
        if (ageS > ElementSetLimits.MAX_AGE_S) {
            rejected.add(new Rejected(o.catalogNumber(), role, Rejected.Code.STALE_ELEMENT_SET, String.format(Locale.ROOT,
                    "element set is %.1f days old at the window start, over the %.0f day limit; not screened as %s",
                    ageS / 86400, ElementSetLimits.MAX_AGE_S / 86400,
                    role == Role.WATCHLIST ? "a watchlist object" : "a catalog object")));
            return false;
        }
        return true;
    }

    private SuppressedPair stackSuppression(int x, int y, String stack, Separation s, AbsoluteDate start,
            AbsoluteDate spanEnd) {
        boolean mayBeStale = s.maxM() > coOrbitingBoundM;
        String listedSince = later(stacks.added(x), stacks.added(y));
        String detail = String.format(Locale.ROOT,
                "not screened for close approaches: both are in the %s stack (pair listed since %s); separation "
                        + "between the members' propagated element sets, not a measured distance, %.1f to %.1f km "
                        + "at the %.0f s samples from %s to %s",
                stack, listedSince, s.minM() / 1000, s.maxM() / 1000, ScreeningSettings.SAMPLE_STEP_S, utc(start),
                utc(spanEnd));
        if (mayBeStale) {
            detail += String.format(Locale.ROOT, "; that exceeds the %.0f km co-orbiting bound, so the list entry may "
                    + "be stale", coOrbitingBoundM / 1000);
        }
        return new SuppressedPair(x, y, SuppressedPair.Mechanism.STATIC_STACK, stack, detail, s.minM(), s.minAt(),
                s.maxM(), mayBeStale);
    }

    /** ISO dates compare correctly as text. */
    private static String later(String a, String b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private SuppressedPair coOrbitingSuppression(int x, int y, Separation s, AbsoluteDate start, AbsoluteDate end) {
        String detail = String.format(Locale.ROOT,
                "not screened for close approaches: separation stayed under the %.0f km co-orbiting bound at every "
                        + "%.0f s sample from %s to %s (inferred from GP data, not known to be attached); sampled "
                        + "minimum %.1f km at %s, maximum %.1f km",
                coOrbitingBoundM / 1000, ScreeningSettings.SAMPLE_STEP_S, utc(start), utc(end), s.minM() / 1000,
                utc(s.minAt()), s.maxM() / 1000);
        if (s.minM() <= ScreeningSettings.REPORT_DISTANCE_M) {
            detail += String.format(Locale.ROOT, "; the sampled minimum is within the %.0f km report distance, and "
                    + "approaches for this pair were not computed", ScreeningSettings.REPORT_DISTANCE_M / 1000);
        }
        return new SuppressedPair(x, y, SuppressedPair.Mechanism.CO_ORBITING, null, detail, s.minM(), s.minAt(),
                s.maxM(), false);
    }

    private static String utc(AbsoluteDate date) {
        return date.toStringRfc3339(OrekitData.utc());
    }

    private static List<TrackedObject> concat(List<TrackedObject> a, List<TrackedObject> b) {
        List<TrackedObject> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static long pairKey(int x, int y) {
        return ((long) Math.min(x, y) << 32) | Math.max(x, y);
    }

    private record Separation(double minM, AbsoluteDate minAt, double maxM) {
    }

    /** The sampled separation over the pair's joint span, or null as soon as a sample reaches {@code stopAtM}. */
    private static Separation separation(ObjectTrack a, ObjectTrack b, AbsoluteDate start, double stopAtM) {
        AbsoluteDate end = ClosestApproachSearch.searchEnd(a, b);
        TLEPropagator pa = TLEPropagator.selectExtrapolator(a.object().tle());
        TLEPropagator pb = TLEPropagator.selectExtrapolator(b.object().tle());
        double min = Double.POSITIVE_INFINITY;
        AbsoluteDate minAt = start;
        double max = 0;
        double span = end.durationFrom(start);
        for (long k = 0; k * ScreeningSettings.SAMPLE_STEP_S <= span; k++) {
            AbsoluteDate date = start.shiftedBy(k * ScreeningSettings.SAMPLE_STEP_S);
            double d = pa.getPVCoordinates(date).getPosition().distance(pb.getPVCoordinates(date).getPosition());
            if (d >= stopAtM) {
                return null;
            }
            if (d < min) {
                min = d;
                minAt = date;
            }
            max = Math.max(max, d);
        }
        return new Separation(min, minAt, max);
    }
}
