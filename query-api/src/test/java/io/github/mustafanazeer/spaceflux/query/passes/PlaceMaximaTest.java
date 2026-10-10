package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.passes.PassGrouping.Event;
import io.github.mustafanazeer.spaceflux.query.passes.PassGrouping.Kind;

/**
 * How detected extremum events become maxima (docs/risk/orbital-conventions.md 6.4): the clamped peak search, the
 * search next to a window edge, and maxima just below the mask. Constructed elevation functions over a 100 s
 * search; no real element set has a maximum within 0.001 degree below the mask, so that case exists only here.
 */
class PlaceMaximaTest {

    static final double END = 100;

    /** Elevation f at t, azimuth 0; fails the test if evaluated outside [0, END]. */
    static List<Event> place(DoubleUnaryOperator f, Event... detected) {
        return PassFinder.placeMaxima(List.of(detected), x -> {
            if (x < 0 || x > END) {
                throw new AssertionError("evaluated at " + x + " s, outside the search [0, " + END + "]");
            }
            return new PassGrouping.Point(x, f.applyAsDouble(x), 0);
        }, END);
    }

    static Event event(Kind kind, double t, DoubleUnaryOperator f) {
        return new Event(kind, t, f.applyAsDouble(t), 0);
    }

    static List<Event> maxima(List<Event> events) {
        return events.stream().filter(e -> e.kind() == Kind.MAXIMUM).toList();
    }

    static List<PassGrouping.Pass> group(DoubleUnaryOperator f, List<Event> events) {
        return PassGrouping.group(new PassGrouping.Point(0, f.applyAsDouble(0), 0),
                new PassGrouping.Point(END, f.applyAsDouble(END), 0), events);
    }

    /** Still rising at the start and falling at the end: the edge searches end at their inner ends, no maximum. */
    @Test
    void aPassRisingAtTheStartAndFallingAtTheEndKeepsOnlyItsOwnMaximum() {
        DoubleUnaryOperator f = x -> 30 - 0.001 * (x - 50) * (x - 50);

        List<Event> events = place(f, event(Kind.MAXIMUM, 50.8, f));

        assertThat(maxima(events)).singleElement().satisfies(m -> assertThat(m.t()).isEqualTo(50, within(0.0005)));
        PassGrouping.Pass pass = group(f, events).getFirst();
        assertThat(pass.riseClipped()).isTrue();
        assertThat(pass.setClipped()).isTrue();
        assertThat(pass.peakCount()).isEqualTo(1);
        assertThat(pass.peakAtEdge()).isFalse();
    }

    @Test
    void aMaximumInsideTheFirstFiveSecondsWithNoEventIsFoundNextToTheStart() {
        DoubleUnaryOperator f = x -> 30 - 0.01 * (x - 0.4) * (x - 0.4);

        List<Event> events = place(f, event(Kind.SET, 54.9, f));

        assertThat(maxima(events)).singleElement().satisfies(m -> assertThat(m.t()).isEqualTo(0.4, within(0.0005)));
        assertThat(events.getFirst().kind()).isEqualTo(Kind.MAXIMUM);
    }

    @Test
    void aMaximumInsideTheLastFiveSecondsWithNoEventIsFoundNextToTheEnd() {
        DoubleUnaryOperator f = x -> 30 - 0.01 * (x - 99.7) * (x - 99.7);

        List<Event> events = place(f, event(Kind.RISE, 45.2, f));

        assertThat(maxima(events)).singleElement().satisfies(m -> assertThat(m.t()).isEqualTo(99.7, within(0.0005)));
        assertThat(events.getLast().kind()).isEqualTo(Kind.MAXIMUM);
    }

    @Test
    void aMaximumJustOutsideEitherEdgeIsNotAMaximum() {
        DoubleUnaryOperator before = x -> 30 - 0.01 * (x + 0.3) * (x + 0.3);
        DoubleUnaryOperator after = x -> 30 - 0.01 * (x - 100.3) * (x - 100.3);

        assertThat(maxima(place(before, event(Kind.SET, 54.5, before)))).isEmpty();
        assertThat(maxima(place(after, event(Kind.RISE, 45.5, after)))).isEmpty();
    }

    /** The edge search is skipped when a maximum placed from an event lies within 5 s of the edge. */
    @Test
    void anEventWithinFiveSecondsOfAnEdgeIsNotSearchedForTwice() {
        DoubleUnaryOperator early = x -> 30 - 0.01 * (x - 3) * (x - 3);
        DoubleUnaryOperator late = x -> 30 - 0.01 * (x - 95.1) * (x - 95.1);

        assertThat(maxima(place(early, event(Kind.MAXIMUM, 4, early)))).hasSize(1);
        assertThat(maxima(place(early, event(Kind.MAXIMUM, 5, early)))).hasSize(1);
        assertThat(maxima(place(late, event(Kind.MAXIMUM, 95, late)))).hasSize(1);
    }

    /** The peak is placed over 10 s centred on its event, so a maximum 3.5 s from it is still found. */
    @Test
    void aMaximumThreeAndAHalfSecondsFromItsEventIsPlaced() {
        DoubleUnaryOperator f = x -> 30 - 0.0001 * (x - 53.5) * (x - 53.5);

        List<Event> events = place(f, event(Kind.MAXIMUM, 50, f));

        assertThat(maxima(events)).singleElement().satisfies(m -> assertThat(m.t()).isEqualTo(53.5, within(0.0005)));
    }

    @Test
    void aMaximumAtAnEndOfItsBracketInsideTheSearchIsAnError() {
        DoubleUnaryOperator f = x -> 30 - 0.0001 * (x - 56) * (x - 56);

        assertThatThrownBy(() -> place(f, event(Kind.MAXIMUM, 50, f))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upper end of its bracket");
    }

    /**
     * The event, 1 s from the true maximum, is 0.0007 degree below the mask, after the set the elevation detector
     * found; placed, the maximum is above the mask and inside the pass, so the pass has its maximum.
     */
    @Test
    void aMaximumEventJustBelowTheMaskIsPlacedAndKeptWhenItsPeakIsAbove() {
        DoubleUnaryOperator f = x -> 10.0003 - 0.001 * (x - 50) * (x - 50);
        double halfWidth = Math.sqrt(0.3);

        List<Event> events = place(f, event(Kind.RISE, 50 - halfWidth, f), event(Kind.SET, 50 + halfWidth, f),
                event(Kind.MAXIMUM, 51, f));

        assertThat(events).extracting(Event::kind).containsExactly(Kind.RISE, Kind.MAXIMUM, Kind.SET);
        assertThat(maxima(events)).singleElement().satisfies(m -> {
            assertThat(m.t()).isEqualTo(50, within(0.0005));
            assertThat(m.elevationDeg()).isEqualTo(10.0003, within(1e-9));
        });
        assertThat(group(f, events)).singleElement().satisfies(p -> assertThat(p.peakCount()).isEqualTo(1));
    }

    @Test
    void aMaximumEventJustBelowTheMaskWhosePeakIsBelowTooIsDropped() {
        DoubleUnaryOperator f = x -> 9.9998 - 0.0001 * (x - 50) * (x - 50);

        assertThat(maxima(place(f, event(Kind.MAXIMUM, 51, f)))).isEmpty();
    }

    @Test
    void aMaximumEventMoreThanAThousandthOfADegreeBelowTheMaskIsNotPlaced() {
        DoubleUnaryOperator f = x -> 9.999 - 0.0001 * (x - 50) * (x - 50);

        assertThat(maxima(place(f, event(Kind.MAXIMUM, 51, f)))).singleElement()
                .satisfies(m -> assertThat(m.t()).isEqualTo(51));
    }

    /** The event lies more than 5 s from the edge, but the maximum it places is inside the edge's 5 s span. */
    @Test
    void aMaximumPlacedFromAnEventPastFiveSecondsIsFoundOnce() {
        DoubleUnaryOperator early = x -> 30 - 0.01 * (x - 4.2) * (x - 4.2);
        DoubleUnaryOperator earlier = x -> 30 - 0.01 * (x - 4.3) * (x - 4.3);
        DoubleUnaryOperator late = x -> 30 - 0.01 * (x - 95.8) * (x - 95.8);

        assertThat(maxima(place(early, event(Kind.MAXIMUM, 5.5, early)))).singleElement()
                .satisfies(m -> assertThat(m.t()).isEqualTo(4.2, within(0.0005)));
        assertThat(maxima(place(earlier, event(Kind.MAXIMUM, 5.5, earlier)))).singleElement()
                .satisfies(m -> assertThat(m.t()).isEqualTo(4.3, within(0.0005)));
        assertThat(maxima(place(late, event(Kind.MAXIMUM, 94.5, late)))).singleElement()
                .satisfies(m -> assertThat(m.t()).isEqualTo(95.8, within(0.0005)));
    }

    /**
     * The event 2 s inside the start is searched over [0, 7], and its maximum, before the start, is dropped there; the
     * start edge has been searched, so it is not searched again. The small bump at 3.1 s, which the search over
     * [0, 7] passes by, stands in for anything a second search over [0, 5] would pick up (it would find it).
     */
    @Test
    void anEventWhoseMaximumIsDroppedAtTheStartStopsTheEdgeSearch() {
        DoubleUnaryOperator f = x -> 30 - 0.01 * (x + 0.3) * (x + 0.3)
                + 0.2 * Math.exp(-(x - 3.1) * (x - 3.1) / 0.1);

        assertThat(maxima(place(f, event(Kind.MAXIMUM, 2, f)))).isEmpty();
    }

    /** The same at the end, mirrored. */
    @Test
    void anEventWhoseMaximumIsDroppedAtTheEndStopsTheEdgeSearch() {
        DoubleUnaryOperator f = x -> 30 - 0.01 * (x - 100.3) * (x - 100.3)
                + 0.2 * Math.exp(-(x - 96.9) * (x - 96.9) / 0.1);

        assertThat(maxima(place(f, event(Kind.MAXIMUM, 98, f)))).isEmpty();
    }

    /** The edge search covers 5 s: a maximum 4.7 s inside an edge, with no event, is found. */
    @Test
    void theEdgeSearchReachesFiveSecondsIntoTheWindow() {
        DoubleUnaryOperator early = x -> 30 - 0.01 * (x - 4.7) * (x - 4.7);
        DoubleUnaryOperator late = x -> 30 - 0.01 * (x - 95.3) * (x - 95.3);

        assertThat(maxima(place(early))).singleElement()
                .satisfies(m -> assertThat(m.t()).isEqualTo(4.7, within(0.0005)));
        assertThat(maxima(place(late))).singleElement()
                .satisfies(m -> assertThat(m.t()).isEqualTo(95.3, within(0.0005)));
    }
}
