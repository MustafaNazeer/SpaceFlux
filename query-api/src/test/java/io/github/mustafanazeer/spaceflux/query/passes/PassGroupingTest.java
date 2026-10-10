package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.passes.PassGrouping.Event;
import io.github.mustafanazeer.spaceflux.query.passes.PassGrouping.Kind;
import io.github.mustafanazeer.spaceflux.query.passes.PassGrouping.Pass;
import io.github.mustafanazeer.spaceflux.query.passes.PassGrouping.Point;

/**
 * The grouping of an ordered event list into passes (docs/risk/orbital-conventions.md 6.4 and 6.9), on constructed
 * sequences only: no recorded window has a pass with more than one peak.
 */
class PassGroupingTest {

    static final Point BELOW_START = new Point(0, -20, 10);
    static final Point BELOW_END = new Point(86_400, -30, 20);

    static Event rise(double t) {
        return new Event(Kind.RISE, t, 10, 100 + t / 1000);
    }

    static Event set(double t) {
        return new Event(Kind.SET, t, 10, 200 + t / 1000);
    }

    static Event max(double t, double elevation) {
        return new Event(Kind.MAXIMUM, t, elevation, 150);
    }

    static Event min(double t, double elevation) {
        return new Event(Kind.MINIMUM, t, elevation, 160);
    }

    @Test
    void noEventsAndBothEdgesBelowGiveNoPasses() {
        assertThat(PassGrouping.group(BELOW_START, BELOW_END, List.of())).isEmpty();
    }

    @Test
    void anOrdinaryPassHasItsRisePeakAndSet() {
        List<Pass> passes = PassGrouping.group(BELOW_START, BELOW_END,
                List.of(min(500, -60), rise(1000), max(1300, 45), set(1600), min(4000, -70)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.rise()).isEqualTo(new Point(1000, 10, 101));
            assertThat(p.riseClipped()).isFalse();
            assertThat(p.startEdge()).isNull();
            assertThat(p.set()).isEqualTo(new Point(1600, 10, 201.6));
            assertThat(p.setClipped()).isFalse();
            assertThat(p.endEdge()).isNull();
            assertThat(p.peak()).isEqualTo(new Point(1300, 45, 150));
            assertThat(p.peakAtEdge()).isFalse();
            assertThat(p.peakCount()).isEqualTo(1);
        });
    }

    @Test
    void severalPassesComeOutInOrderAndMaximaBelowTheMaskBelongToNone() {
        List<Pass> passes = PassGrouping.group(BELOW_START, BELOW_END, List.of(max(200, 4), rise(1000),
                max(1300, 45), set(1600), max(3000, 8), rise(7000), max(7200, 20), set(7400)));

        assertThat(passes).hasSize(2);
        assertThat(passes.get(0).peak().t()).isEqualTo(1300);
        assertThat(passes.get(1).rise().t()).isEqualTo(7000);
        assertThat(passes.get(1).peak()).isEqualTo(new Point(7200, 20, 150));
        assertThat(passes).allSatisfy(p -> assertThat(p.peakCount()).isEqualTo(1));
    }

    @Test
    void twoPeaksInOnePassReportTheHigherAndCountBoth() {
        List<Pass> passes = PassGrouping.group(BELOW_START, BELOW_END,
                List.of(rise(1000), max(1100, 30), min(1200, 15), max(1300, 50), set(1500)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.peak()).isEqualTo(new Point(1300, 50, 150));
            assertThat(p.peakCount()).isEqualTo(2);
            assertThat(p.peakAtEdge()).isFalse();
        });
    }

    @Test
    void aSecondPeakLowerThanTheFirstLeavesTheFirstAsThePeak() {
        List<Pass> passes = PassGrouping.group(BELOW_START, BELOW_END,
                List.of(rise(1000), max(1100, 50), min(1200, 15), max(1300, 30), set(1500)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.peak()).isEqualTo(new Point(1100, 50, 150));
            assertThat(p.peakCount()).isEqualTo(2);
        });
    }

    @Test
    void threePeaksInOnePassAreCountedAndTheMiddleOneCanBeHighest() {
        List<Pass> passes = PassGrouping.group(BELOW_START, BELOW_END, List.of(rise(1000), max(1100, 20),
                min(1150, 12), max(1200, 60), min(1250, 11), max(1300, 25), set(1500)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.peak()).isEqualTo(new Point(1200, 60, 150));
            assertThat(p.peakCount()).isEqualTo(3);
        });
    }

    @Test
    void equalPeaksKeepTheEarlierOne() {
        List<Pass> passes = PassGrouping.group(BELOW_START, BELOW_END,
                List.of(rise(1000), max(1100, 40), min(1200, 15), max(1300, 40), set(1500)));

        assertThat(passes.getFirst().peak().t()).isEqualTo(1100);
    }

    @Test
    void aPassInProgressAtTheStartHasNoRiseAndKeepsItsInWindowMaximum() {
        Point start = new Point(0, 25, 230);
        List<Pass> passes = PassGrouping.group(start, BELOW_END, List.of(max(60, 40), set(300), rise(5000),
                max(5100, 15), set(5200)));

        assertThat(passes).hasSize(2);
        assertThat(passes.get(0)).satisfies(p -> {
            assertThat(p.rise()).isNull();
            assertThat(p.riseClipped()).isTrue();
            assertThat(p.startEdge()).isEqualTo(start);
            assertThat(p.peak()).isEqualTo(new Point(60, 40, 150));
            assertThat(p.peakAtEdge()).isFalse();
            assertThat(p.peakCount()).isEqualTo(1);
            assertThat(p.set().t()).isEqualTo(300);
        });
        assertThat(passes.get(1).riseClipped()).isFalse();
        assertThat(passes.get(1).startEdge()).isNull();
    }

    @Test
    void aPassInProgressAtTheStartWithNoMaximumPeaksAtTheEdge() {
        Point start = new Point(0, 24.35, 229.4);
        List<Pass> passes = PassGrouping.group(start, BELOW_END, List.of(set(105), min(2000, -40)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.peak()).isEqualTo(start);
            assertThat(p.peakAtEdge()).isTrue();
            assertThat(p.peakCount()).isZero();
        });
    }

    @Test
    void aPassInProgressAtTheEndHasNoSetAndItsEdge() {
        Point end = new Point(86_400, 25, 165);
        List<Pass> passes = PassGrouping.group(BELOW_START, end, List.of(rise(86_100), max(86_300, 49)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.set()).isNull();
            assertThat(p.setClipped()).isTrue();
            assertThat(p.endEdge()).isEqualTo(end);
            assertThat(p.peak()).isEqualTo(new Point(86_300, 49, 150));
            assertThat(p.peakAtEdge()).isFalse();
        });
    }

    @Test
    void aPassInProgressAtTheEndWithNoMaximumPeaksAtTheEdge() {
        Point end = new Point(86_400, 26.4, 308.5);
        List<Pass> passes = PassGrouping.group(BELOW_START, end, List.of(rise(86_305)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.peak()).isEqualTo(end);
            assertThat(p.peakAtEdge()).isTrue();
            assertThat(p.peakCount()).isZero();
        });
    }

    /** 6.4: a maximum event inside the window is the peak even when the elevation at the edge is higher. */
    @Test
    void aClippedPassWhoseInWindowMaximumIsBelowItsEdgeElevationReportsThatMaximum() {
        Point start = new Point(0, 50, 90);
        List<Pass> passes = PassGrouping.group(start, BELOW_END,
                List.of(min(100, 30), max(200, 40), set(500)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.peak()).isEqualTo(new Point(200, 40, 150));
            assertThat(p.peakAtEdge()).isFalse();
            assertThat(p.peakCount()).isEqualTo(1);
            assertThat(p.startEdge()).isEqualTo(start);
        });
    }

    @Test
    void aPassClippedAtBothEdgesWithNoMaximumPeaksAtTheHigherEdge() {
        Point start = new Point(0, 12, 250);
        Point end = new Point(86_400, 14.6, 279);
        List<Pass> passes = PassGrouping.group(start, end, List.of(min(1000, 11)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.riseClipped()).isTrue();
            assertThat(p.setClipped()).isTrue();
            assertThat(p.startEdge()).isEqualTo(start);
            assertThat(p.endEdge()).isEqualTo(end);
            assertThat(p.peak()).isEqualTo(end);
            assertThat(p.peakAtEdge()).isTrue();
            assertThat(p.peakCount()).isZero();
        });
    }

    @Test
    void aPassClippedAtBothEdgesWithTheStartHigherPeaksAtTheStart() {
        Point start = new Point(0, 30, 250);
        Point end = new Point(86_400, 14.6, 279);

        assertThat(PassGrouping.group(start, end, List.of(min(1000, 11))).getFirst().peak()).isEqualTo(start);
    }

    /** As with equal peaks, the earlier edge is kept. */
    @Test
    void aPassClippedAtBothEdgesWithEqualEdgesPeaksAtTheStart() {
        Point start = new Point(0, 14.6, 250);
        Point end = new Point(86_400, 14.6, 279);

        assertThat(PassGrouping.group(start, end, List.of(min(1000, 11))).getFirst().peak()).isSameAs(start);
    }

    @Test
    void aPassClippedAtBothEdgesKeepsItsInteriorPeaks() {
        Point start = new Point(0, 30, 250);
        Point end = new Point(86_400, 14.6, 279);
        List<Pass> passes = PassGrouping.group(start, end,
                List.of(min(1000, 11), max(2000, 20), min(3000, 12), max(4000, 18)));

        assertThat(passes).singleElement().satisfies(p -> {
            assertThat(p.peak()).isEqualTo(new Point(2000, 20, 150));
            assertThat(p.peakCount()).isEqualTo(2);
            assertThat(p.peakAtEdge()).isFalse();
        });
    }

    @Test
    void anEdgeExactlyAtTheMaskCountsAsInsideAPass() {
        Point start = new Point(0, 10, 250);

        assertThat(PassGrouping.group(start, BELOW_END, List.of(set(0.5)))).singleElement()
                .satisfies(p -> assertThat(p.riseClipped()).isTrue());
    }

    @Test
    void aMaximumAtTheRiseOrSetTimeBelongsToThePass() {
        List<Pass> passes = PassGrouping.group(BELOW_START, BELOW_END,
                List.of(max(1000, 10.0000001), rise(1000), set(1000)));

        assertThat(passes).singleElement().satisfies(p -> assertThat(p.peakCount()).isEqualTo(1));
    }

    @Test
    void anUnclippedPassWithNoMaximumIsAnError() {
        assertThatThrownBy(() -> PassGrouping.group(BELOW_START, BELOW_END, List.of(rise(1000), set(1100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no maximum");
    }

    @Test
    void twoRisesInARowAreAnError() {
        assertThatThrownBy(() -> PassGrouping.group(BELOW_START, BELOW_END, List.of(rise(1000), rise(1100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rise");
    }

    @Test
    void aSetWithoutARiseIsAnError() {
        assertThatThrownBy(() -> PassGrouping.group(BELOW_START, BELOW_END, List.of(set(1000))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("set");
    }

    @Test
    void aRiseWhileTheStartIsAlreadyAboveTheMaskIsAnError() {
        assertThatThrownBy(() -> PassGrouping.group(new Point(0, 20, 0), BELOW_END, List.of(rise(1000))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOpenPassWithTheEndBelowTheMaskIsAnError() {
        assertThatThrownBy(() -> PassGrouping.group(BELOW_START, BELOW_END, List.of(rise(1000), max(1100, 30))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("end");
    }

    @Test
    void anEndAboveTheMaskWithNoPassOpenIsAnError() {
        assertThatThrownBy(() -> PassGrouping.group(BELOW_START, new Point(86_400, 20, 0),
                List.of(rise(1000), max(1100, 30), set(1200))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no pass is open");
    }

    @Test
    void eventsOutOfOrderAreAnError() {
        assertThatThrownBy(() -> PassGrouping.group(BELOW_START, BELOW_END,
                List.of(rise(1000), max(900, 30), set(1100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("order");
    }
}
