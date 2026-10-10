package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

/** The golden section search that places a peak (docs/risk/orbital-conventions.md 6.4, "Placing the peak"). */
class PeakSearchTest {

    @Test
    void findsTheMaximumOfASmoothPeakToAMillisecond() {
        double t = PeakSearch.maximize(x -> 30 - 4e-6 * (x - 1003.2345) * (x - 1003.2345), 998, 1008, 0.001);

        assertThat(t).isEqualTo(1003.2345, within(0.0005));
    }

    @Test
    void findsAMaximumOffCentreAndStopsOnceTheBracketIsAMillisecond() {
        AtomicInteger calls = new AtomicInteger();
        double t = PeakSearch.maximize(x -> {
            calls.incrementAndGet();
            return -Math.abs(x - 7.9);
        }, 0, 10, 0.001);

        assertThat(t).isEqualTo(7.9, within(0.0005));
        assertThat(calls.get()).isLessThan(30);
    }

    @Test
    void aMaximumAtTheUpperEndOfTheBracketIsAnError() {
        assertThatThrownBy(() -> PeakSearch.maximize(x -> x, 0, 10, 0.001))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("upper end of its bracket");
    }

    @Test
    void aMaximumAtTheLowerEndOfTheBracketIsAnError() {
        assertThatThrownBy(() -> PeakSearch.maximize(x -> -x, 0, 10, 0.001))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lower end of its bracket");
    }

    /** A function that fails the test if it is evaluated outside the window [0, 100]. */
    static DoubleUnaryOperator inWindow(DoubleUnaryOperator f) {
        return x -> {
            if (x < 0 || x > 100) {
                throw new AssertionError("evaluated at " + x + " s, outside the window [0, 100]");
            }
            return f.applyAsDouble(x);
        };
    }

    static PeakSearch.Found near(DoubleUnaryOperator f, double event) {
        return PeakSearch.peakNear(inWindow(f), event, 10, 0, 100, 0.001);
    }

    @Test
    void aMaximumBeforeTheWindowStartIsAtTheWindowEdgeAndNotAPeak() {
        assertThat(near(x -> -(x + 0.5) * (x + 0.5), 2).end()).isEqualTo(PeakSearch.End.LOWER);
    }

    @Test
    void aMaximumInsideAClampedBracketIsPlaced() {
        PeakSearch.Found found = near(x -> -(x - 0.8) * (x - 0.8), 2);

        assertThat(found.end()).isEqualTo(PeakSearch.End.NONE);
        assertThat(found.t()).isEqualTo(0.8, within(0.0005));
    }

    @Test
    void aMaximumAfterTheWindowEndIsAtTheWindowEdgeAndNotAPeak() {
        assertThat(near(x -> -(x - 100.4) * (x - 100.4), 99).end()).isEqualTo(PeakSearch.End.UPPER);
        assertThat(near(x -> -(x - 101) * (x - 101), 97).end()).isEqualTo(PeakSearch.End.UPPER);
    }

    @Test
    void aMaximumAtTheLowerEndOfAnUnclampedBracketIsAnError() {
        assertThatThrownBy(() -> near(x -> -(x - 44) * (x - 44), 50)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("45.0 s").hasMessageContaining("lower end of its bracket");
    }

    @Test
    void aMaximumAtTheInnerEndOfABracketClampedAtTheOtherIsAnError() {
        assertThatThrownBy(() -> near(x -> -(x - 91) * (x - 91), 97)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("92.0 s").hasMessageContaining("lower end of its bracket");
    }
}
