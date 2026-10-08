package io.github.mustafanazeer.spaceflux.orbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** docs/risk/orbital-conventions.md 3.2: the regime is the one Orekit selects, not the recorded mean motion. */
class TrackedObjectTest {

    @Test
    void theRegimeFollowsOrekitsChoiceOfPropagatorNearTheBoundary() throws IOException {
        TrackedObject nearBoundary = Fixtures.variant(Fixtures.station(25544), "SYNTHETIC NEAR 225 MIN", 99992, null,
                2 * Math.PI / (224.95 * 60), 0.45, 0.0, null);

        assertThat(nearBoundary.periodMinutes()).isLessThan(225);
        assertThat(nearBoundary.deepSpace()).isTrue();
    }

    @Test
    void recordedStationsAreNearEarthAndTheDeepSpaceReferenceCaseIsNot() throws IOException {
        assertThat(Fixtures.station(25544).deepSpace()).isFalse();
        assertThat(Fixtures.reference(23599).deepSpace()).isTrue();
    }
}
