package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.orbit.Fixtures;
import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;

/**
 * Measures how long screening takes on the recorded stations fixture, for docs/perf/screening-cost.md. It is not
 * part of the default build; run it explicitly from the repository root with
 *
 * <pre>
 * ./mvnw -B -q -pl risk-engine -am test -Dtest=ScreeningCostBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dscreening.cost=true -Dscreening.cost.out=$PWD/docs/perf/data/screening-cost-NAME.txt
 * </pre>
 *
 * Every figure is wall clock time from {@link System#nanoTime()} in one JVM, after warm up runs, on a single thread.
 * The private sampled separation in {@link Screening} is called through reflection so the measured code is the
 * production code, not a copy.
 */
@EnabledIfSystemProperty(named = "screening.cost", matches = "true")
class ScreeningCostBenchmark {

    private static final int WARMUP = 3;
    private static final int REPEATS = 10;
    private static final int ISS = 25544;

    /** Keeps each result reachable so the measured work cannot be optimized away. */
    private static volatile Object sink;

    private final StringWriter text = new StringWriter();
    private final PrintWriter out = new PrintWriter(text);

    @Test
    void measuresScreeningCostOnTheStationsFixture() throws Exception {
        List<TrackedObject> stations = Fixtures.stations();
        AbsoluteDate start = Fixtures.STATIONS_START;
        AbsoluteDate end = start.shiftedBy(ScreeningSettings.WINDOW_S);
        Screening screening = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M);
        TrackedObject iss = Fixtures.station(ISS);

        out.printf("screening cost on orbit-core/src/test/resources/celestrak/gp-stations.json (%d objects)%n",
                stations.size());
        out.printf("started %s; window start %s, %.0f s window, %.0f s samples%n", Instant.now(),
                start.toStringRfc3339(OrekitData.utc()),
                ScreeningSettings.WINDOW_S, ScreeningSettings.SAMPLE_STEP_S);
        // JVM input arguments are not printed: they carry local file system paths.
        out.printf("java %s (%s), %d available processors, max heap %d MiB%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1048576);
        out.printf("warm up %d runs, then %d measured runs per figure; times in ms%n", WARMUP, REPEATS);
        out.printf("host load average at start: %s%n%n", loadAverage());

        out.println("1. Whole run as the service does it: watchlist ISS, catalog the whole fixture, named stacks");
        ScreeningResult live = screening.run(List.of(iss), stations, start);
        printCoverage(live);
        printTimes("whole run", () -> screening.run(List.of(iss), stations, start));
        out.println();

        out.println("2. The same run split into its stages (each stage timed alone, same inputs)");
        double[] trackMs = new double[stations.size()];
        List<ObjectTrack> tracks = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            TrackedObject o = stations.get(i);
            trackMs[i] = median(times(() -> ObjectTrack.sample(o, start, end, ScreeningSettings.SAMPLE_STEP_S)));
            tracks.add(ObjectTrack.sample(o, start, end, ScreeningSettings.SAMPLE_STEP_S));
        }
        out.println("  per object track (latch from epoch to window start, then the window), median of runs:");
        out.printf("    %-8s %12s %14s %14s %10s%n", "norad", "age_at_start_d", "latch_samples", "window_samples",
                "median_ms");
        double trackSum = 0;
        for (int i = 0; i < stations.size(); i++) {
            TrackedObject o = stations.get(i);
            double ageS = start.durationFrom(o.tle().getDate());
            out.printf(Locale.ROOT, "    %-8d %12.3f %14d %14d %10.2f%n", o.catalogNumber(), ageS / 86400,
                    latchSamples(ageS), windowSamples(), trackMs[i]);
            trackSum += trackMs[i];
        }
        out.printf(Locale.ROOT, "  sum of per object track medians: %.1f ms%n", trackSum);

        ObjectTrack a = trackOf(tracks, ISS);
        Method separation = separationMethod();
        double stackSum = 0;
        int stackPairs = 0;
        int prefiltered = 0;
        double prefilterSum = 0;
        for (ObjectTrack b : tracks) {
            int y = b.object().catalogNumber();
            if (y == ISS) {
                continue;
            }
            if (StationStacks.load().sharedStack(ISS, y).isPresent()) {
                stackSum += median(times(() -> invoke(separation, a, b, start, Double.MAX_VALUE)));
                stackPairs++;
            } else if (!RadialPrefilter.mayApproach(a, b, ScreeningSettings.REPORT_DISTANCE_M)) {
                prefilterSum += median(times(() -> RadialPrefilter.mayApproach(a, b, ScreeningSettings.REPORT_DISTANCE_M)));
                prefiltered++;
            }
        }
        out.printf(Locale.ROOT, "  %d stack pairs: full window separation for the suppression record, sum of medians %.1f ms"
                + " (%.1f ms per pair)%n", stackPairs, stackSum, stackPairs == 0 ? 0 : stackSum / stackPairs);
        out.printf(Locale.ROOT, "  %d pairs removed by the radial prefilter: sum of medians %.4f ms%n", prefiltered,
                prefilterSum);
        out.println();

        out.println("3. Growth with the number of pairs on the same fixture: the first k objects of the fixture are the"
                + " watchlist, the whole fixture is the catalog");
        out.printf("    %-3s %6s %10s %12s %9s %11s %9s %9s %9s %9s%n", "k", "pairs", "suppressed", "prefiltered",
                "searched", "approaches", "median", "min", "max", "stdev");
        for (int k : new int[] {1, 2, 4, 8, 16, stations.size()}) {
            List<TrackedObject> watchlist = stations.subList(0, k);
            ScreeningResult r = screening.run(watchlist, stations, start);
            double[] t = times(() -> screening.run(watchlist, stations, start));
            out.printf(Locale.ROOT, "    %-3d %6d %10d %12d %9d %11d %9.1f %9.1f %9.1f %9.1f%n", k, r.coverage().pairs(),
                    r.suppressed().size(), r.coverage().pairsRemovedByPrefilter(), r.coverage().pairsSearched(),
                    r.approaches().size(), median(t), min(t), max(t), stdev(t));
        }
        out.println();

        out.println("4. Searched pairs in the all against all run: closest approach search per pair");
        double searchSum = 0;
        List<Double> perSearch = new ArrayList<>();
        ScreeningResult all = screening.run(stations, stations, start);
        List<long[]> searchedPairs = searchedPairs(stations, tracks, all);
        for (long[] p : searchedPairs) {
            ObjectTrack x = trackOf(tracks, (int) p[0]);
            ObjectTrack y = trackOf(tracks, (int) p[1]);
            double m = median(times(() -> ClosestApproachSearch.find(x, y, start, ScreeningSettings.REPORT_DISTANCE_M)));
            perSearch.add(m);
            searchSum += m;
        }
        double[] ps = perSearch.stream().mapToDouble(Double::doubleValue).toArray();
        out.printf(Locale.ROOT, "  %d searched pairs: sum of medians %.1f ms; per pair median %.1f ms, min %.1f ms,"
                + " max %.1f ms%n", ps.length, searchSum, ps.length == 0 ? 0 : median(ps), ps.length == 0 ? 0 : min(ps),
                ps.length == 0 ? 0 : max(ps));
        assertThat(ps.length).isEqualTo(all.coverage().pairsSearched());
        out.println();

        out.println("5. Co-orbiting check: sampled separation with the 500 km early exit versus the full window, for"
                + " every pair of the fixture that passes the prefilter and has distinct element sets, as the check"
                + " would see them without the named stacks (named stack pairs are marked)");
        out.printf("    %-8s %-8s %10s %14s %16s%n", "a", "b", "exits_early", "with_exit_ms", "full_window_ms");
        StationStacks stacks = StationStacks.load();
        double exitSum = 0;
        double fullSum = 0;
        int early = 0;
        int whole = 0;
        for (int i = 0; i < tracks.size(); i++) {
            for (int j = i + 1; j < tracks.size(); j++) {
                ObjectTrack x = tracks.get(i);
                ObjectTrack y = tracks.get(j);
                if (!RadialPrefilter.mayApproach(x, y, ScreeningSettings.REPORT_DISTANCE_M)
                        || ClosestApproachSearch.sameElements(x.object().tle(), y.object().tle())) {
                    continue;
                }
                boolean exits = invoke(separation, x, y, start, ScreeningSettings.CO_ORBITING_BOUND_M) == null;
                double withExit = median(times(() -> invoke(separation, x, y, start, ScreeningSettings.CO_ORBITING_BOUND_M)));
                double full = median(times(() -> invoke(separation, x, y, start, Double.MAX_VALUE)));
                boolean stackPair = stacks.sharedStack(x.object().catalogNumber(), y.object().catalogNumber()).isPresent();
                out.printf(Locale.ROOT, "    %-8d %-8d %10s %14.3f %16.1f%s%n", x.object().catalogNumber(),
                        y.object().catalogNumber(), exits, withExit, full, stackPair ? "  (named stack pair)" : "");
                if (exits) {
                    early++;
                    exitSum += withExit;
                } else {
                    whole++;
                }
                fullSum += full;
            }
        }
        out.printf(Locale.ROOT, "  %d pairs exit early (sum %.2f ms with the exit); %d run the whole window; full window"
                + " sum over all of them %.1f ms%n", early, exitSum, whole, fullSum);
        out.println();

        out.println("6. Latch cost against element set age: ISS track with the window start moved to epoch plus d days");
        out.printf("    %-6s %14s %14s %9s %9s %9s %9s%n", "d", "latch_samples", "window_samples", "median", "min",
                "max", "stdev");
        AbsoluteDate epoch = iss.tle().getDate();
        for (double d : new double[] {0, 1, 2.5, 5, 10}) {
            AbsoluteDate s = epoch.shiftedBy(d * 86400);
            AbsoluteDate e = s.shiftedBy(ScreeningSettings.WINDOW_S);
            double[] t = times(() -> ObjectTrack.sample(iss, s, e, ScreeningSettings.SAMPLE_STEP_S));
            out.printf(Locale.ROOT, "    %-6.1f %14d %14d %9.1f %9.1f %9.1f %9.1f%n", d, latchSamples(d * 86400),
                    windowSamples(), median(t), min(t), max(t), stdev(t));
        }
        out.printf("%nhost load average at end: %s%n", loadAverage());
        out.flush();

        String report = text.toString();
        System.out.print(report);
        Path file = Path.of(System.getProperty("screening.cost.out", "target/screening-cost.txt"));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, report, StandardCharsets.UTF_8);
    }

    private void printCoverage(ScreeningResult r) {
        ScreeningResult.Coverage c = r.coverage();
        out.printf("  coverage: %d watchlist, %d catalog, %d pairs, %d not screenable, %d suppressed, %d removed by the"
                + " prefilter, %d searched, %d approaches%n", c.watchlistAccepted(), c.catalogAdmitted(), c.pairs(),
                c.pairsNotScreenable(), r.suppressed().size(), c.pairsRemovedByPrefilter(), c.pairsSearched(),
                r.approaches().size());
    }

    private void printTimes(String label, Supplier<?> work) {
        double[] t = times(work);
        out.printf(Locale.ROOT, "  %s: median %.1f, min %.1f, max %.1f, mean %.1f, stdev %.1f; runs %s%n", label,
                median(t), min(t), max(t), mean(t), stdev(t), round(t));
    }

    private static double[] times(Supplier<?> work) {
        for (int i = 0; i < WARMUP; i++) {
            work.get();
        }
        double[] t = new double[REPEATS];
        for (int i = 0; i < REPEATS; i++) {
            long s = System.nanoTime();
            sink = work.get();
            t[i] = (System.nanoTime() - s) / 1e6;
        }
        return t;
    }

    /** The 1, 5 and 15 minute load averages, since other work on the host slows every figure here. */
    private static String loadAverage() {
        try {
            return Files.readString(Path.of("/proc/loadavg")).trim();
        } catch (IOException e) {
            return "unavailable";
        }
    }

    private static long latchSamples(double ageS) {
        return ageS <= 0 ? 0 : (long) Math.ceil(ageS / ScreeningSettings.SAMPLE_STEP_S);
    }

    private static long windowSamples() {
        return (long) Math.floor(ScreeningSettings.WINDOW_S / ScreeningSettings.SAMPLE_STEP_S) + 1;
    }

    private static ObjectTrack trackOf(List<ObjectTrack> tracks, int norad) {
        return tracks.stream().filter(t -> t.object().catalogNumber() == norad).findFirst().orElseThrow();
    }

    /** Pairs the run searched, rebuilt by the same rules: screenable, not stacked, not identical, kept by the prefilter. */
    private static List<long[]> searchedPairs(List<TrackedObject> objects, List<ObjectTrack> tracks, ScreeningResult r) {
        StationStacks stacks = StationStacks.load();
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            for (int j = i + 1; j < objects.size(); j++) {
                int x = objects.get(i).catalogNumber();
                int y = objects.get(j).catalogNumber();
                ObjectTrack a = trackOf(tracks, x);
                ObjectTrack b = trackOf(tracks, y);
                boolean suppressed = r.suppressed().stream().anyMatch(s -> (s.watchlistNumber() == x
                        && s.otherNumber() == y) || (s.watchlistNumber() == y && s.otherNumber() == x));
                if (a.screenable() && b.screenable() && stacks.sharedStack(x, y).isEmpty() && !suppressed
                        && RadialPrefilter.mayApproach(a, b, ScreeningSettings.REPORT_DISTANCE_M)) {
                    pairs.add(new long[] {x, y});
                }
            }
        }
        return pairs;
    }

    private static Method separationMethod() throws NoSuchMethodException {
        Method m = Screening.class.getDeclaredMethod("separation", ObjectTrack.class, ObjectTrack.class,
                AbsoluteDate.class, double.class);
        m.setAccessible(true);
        return m;
    }

    private static Object invoke(Method m, ObjectTrack a, ObjectTrack b, AbsoluteDate start, double stopAtM) {
        try {
            return m.invoke(null, a, b, start, stopAtM);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double median(double[] t) {
        double[] s = t.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    private static double min(double[] t) {
        return Arrays.stream(t).min().orElse(Double.NaN);
    }

    private static double max(double[] t) {
        return Arrays.stream(t).max().orElse(Double.NaN);
    }

    private static double mean(double[] t) {
        return Arrays.stream(t).average().orElse(Double.NaN);
    }

    private static double stdev(double[] t) {
        double m = mean(t);
        return Math.sqrt(Arrays.stream(t).map(x -> (x - m) * (x - m)).sum() / (t.length - 1));
    }

    private static String round(double[] t) {
        return Arrays.toString(Arrays.stream(t).map(x -> Math.round(x * 10) / 10.0).toArray());
    }
}
