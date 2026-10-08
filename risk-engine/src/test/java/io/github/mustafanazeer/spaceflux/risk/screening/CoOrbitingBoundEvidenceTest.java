package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.orbit.Fixtures;
import io.github.mustafanazeer.spaceflux.orbit.ReferenceCases;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;

/**
 * Prints the stations fixture figures behind the named stacks and the co-orbiting bound
 * (docs/risk/orbital-conventions.md 3.7), and fails when any of them changes from the committed report.
 * Run with -Dtest=CoOrbitingBoundEvidenceTest.
 */
class CoOrbitingBoundEvidenceTest {

    @Test
    void printsTheFiguresBehindTheStacksAndTheBound() throws IOException {
        List<TrackedObject> objects = Fixtures.stations();
        Map<Integer, String> names = objects.stream()
                .collect(Collectors.toMap(TrackedObject::catalogNumber, TrackedObject::name));
        StationStacks stacks = StationStacks.load();

        List<BruteForceScan.PairStats> pairs = BruteForceScan.scan(objects.stream().map(TrackedObject::tle).toList(),
                Fixtures.STATIONS_START, ScreeningSettings.WINDOW_S, ScreeningSettings.SAMPLE_STEP_S, 0);

        Map<String, BruteForceScan.PairStats> widestPerStack = new TreeMap<>();
        List<BruteForceScan.PairStats> independent = new ArrayList<>();
        for (BruteForceScan.PairStats pair : pairs) {
            Optional<String> stack = stacks.sharedStack(pair.a(), pair.b());
            if (stack.isPresent()) {
                widestPerStack.merge(stack.get(), pair, (x, y) -> y.maxKm() > x.maxKm() ? y : x);
            } else {
                independent.add(pair);
            }
        }

        Function<BruteForceScan.PairStats, String> label = p -> names.get(p.a()) + " and " + names.get(p.b());
        List<String> report = new ArrayList<>();
        report.add(String.format(Locale.ROOT, "stations fixture from %s, %d objects, %d pairs, sampled every %.0f s over %.0f days",
                Fixtures.STATIONS_START, objects.size(), pairs.size(), ScreeningSettings.SAMPLE_STEP_S,
                ScreeningSettings.WINDOW_S / 86400));
        widestPerStack.forEach((stack, p) -> report.add(String.format(Locale.ROOT,
                "%s: widest pair %s, largest separation %.3f km", stack, label.apply(p), p.maxKm())));
        BruteForceScan.PairStats leastOpening = independent.stream()
                .min(Comparator.comparingDouble(BruteForceScan.PairStats::maxKm)).orElseThrow();
        BruteForceScan.PairStats closest = independent.stream()
                .min(Comparator.comparingDouble(BruteForceScan.PairStats::minKm)).orElseThrow();
        report.add(String.format(Locale.ROOT, "%d pairs outside a shared stack", independent.size()));
        report.add(String.format(Locale.ROOT, "outside a shared stack, smallest largest separation: %s, %.3f km",
                label.apply(leastOpening), leastOpening.maxKm()));
        report.add(String.format(Locale.ROOT, "outside a shared stack, smallest separation: %s, %.3f km",
                label.apply(closest), closest.minKm()));

        report.forEach(System.out::println);
        assertThat(report).containsExactlyElementsOf(ReferenceCases.lines("/evidence/co-orbiting-evidence.txt"));
    }
}
