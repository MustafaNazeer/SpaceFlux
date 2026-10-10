package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.query.passes.ReferencePasses.ReferenceObject;
import io.github.mustafanazeer.spaceflux.query.passes.ReferencePasses.Window;

/**
 * docs/risk/orbital-conventions.md 6.9, "Dense scan": the service's own elevation sampled every second over each
 * reference window, against the passes the service reports. This is what tests the extremum detector's check
 * interval and the event loop behaviour of 6.4; it uses no reference values.
 */
class PassDenseScanTest {

    static final PassFinder FINDER = new PassFinder();
    static final double EDGE_S = 0.002;

    @Test
    void everySampleAboveTheMaskAndEverySampledMaximumIsInAReportedPass() {
        List<String> problems = new ArrayList<>();
        int windows = 0;
        for (ReferenceObject o : ReferencePasses.load().objects()) {
            TrackedObject object = o.elementSet();
            for (Window w : o.windows()) {
                problems.addAll(scan(o.noradCatId() + " " + w.kind(), object, w.startDate()));
                windows++;
            }
        }
        assertThat(windows).isEqualTo(12);
        assertThat(problems).isEmpty();
    }

    static List<String> scan(String window, TrackedObject object, AbsoluteDate start) {
        PassFinder.Outcome outcome = FINDER.find(object, start);
        TLEPropagator at = TLEPropagator.selectExtrapolator(object.tle());
        int n = (int) PassFinder.WINDOW_S + 1;
        double[] el = new double[n];
        for (int i = 0; i < n; i++) {
            el[i] = FINDER.point(at, start, start.shiftedBy(i)).elevationDeg();
        }
        List<double[]> spans = new ArrayList<>();
        List<Double> maxima = new ArrayList<>();
        for (PassGrouping.Pass p : outcome.passes()) {
            double from = p.rise() == null ? 0 : p.rise().t();
            double to = p.set() == null ? PassFinder.WINDOW_S : p.set().t();
            spans.add(new double[] {from, to});
            p.peaks().forEach(m -> maxima.add(m.t()));
        }
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (el[i] >= PassGrouping.MASK_DEG) {
                final int s = i;
                if (spans.stream().noneMatch(x -> x[0] - EDGE_S <= s && s <= x[1] + EDGE_S)) {
                    problems.add(String.format(Locale.ROOT, "%s: sample %d s at %.4f deg is outside every pass",
                            window, i, el[i]));
                }
            }
        }
        for (double[] x : spans) {
            if (x[1] - x[0] >= 2) {
                boolean any = false;
                for (int i = (int) Math.ceil(x[0]); i <= (int) Math.floor(x[1]) && !any; i++) {
                    any = el[i] >= PassGrouping.MASK_DEG;
                }
                if (!any) {
                    problems.add(String.format(Locale.ROOT, "%s: the pass from %.3f s holds no sample above the mask",
                            window, x[0]));
                }
            }
        }
        for (int i = 1; i < n - 1; i++) {
            if (el[i] > el[i - 1] && el[i] >= el[i + 1] && el[i] >= PassGrouping.MASK_DEG) {
                final int s = i;
                if (maxima.stream().noneMatch(m -> Math.abs(m - s) <= 1)) {
                    problems.add(String.format(Locale.ROOT, "%s: sampled maximum at %d s (%.6f deg) has no reported "
                            + "maximum within 1 s; nearest %s", window, i, el[i],
                            maxima.stream().min((a, b) -> Double.compare(Math.abs(a - s), Math.abs(b - s)))
                                    .map(m -> String.format(Locale.ROOT, "%.3f s", m)).orElse("none")));
                }
            }
        }
        return problems;
    }
}
