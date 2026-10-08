package io.github.mustafanazeer.spaceflux.orbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.orekit.errors.OrekitException;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;

import tools.jackson.databind.node.ObjectNode;

/** Decay rule: docs/risk/orbital-conventions.md Section 2.4. */
class Sgp4PropagatorTest {

    private static final Set<Integer> EARLY_STOP_CASES = Set.of(22312, 28350, 28872, 29141);

    private static ReferenceCases.Case published(int catalogNumber) throws IOException {
        return ReferenceCases.published().stream()
                .filter(c -> c.catalogNumber() == catalogNumber).findFirst().orElseThrow();
    }

    private static TLE tle(ReferenceCases.Case c) {
        return GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2()));
    }

    static Stream<Arguments> referenceFailureSteps() {
        return Stream.of(
                Arguments.of(22312, 494.2028672),
                Arguments.of(28350, 1560.0),
                Arguments.of(28872, 55.0),
                Arguments.of(29141, 440.0));
    }

    @ParameterizedTest(name = "catalog {0} stops by {1} min")
    @MethodSource("referenceFailureSteps")
    void stopsNoLaterThanTheReferenceCode(int catalogNumber, double minutes) throws IOException {
        TLE tle = tle(published(catalogNumber));
        Sgp4Propagator propagator = new Sgp4Propagator(tle);

        assertThatThrownBy(() -> propagator.screeningState(tle.getDate().shiftedBy(minutes * 60)))
                .isInstanceOf(PropagationStoppedException.class)
                .hasMessageContaining("altitude");
    }

    @Test
    void neverStopsAtAnyTenSecondStepOfTheCasesThatRunToTheirEnd() throws IOException {
        Set<Integer> runToEnd = Set.of(5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925, 20413, 21897, 22674,
                23177, 23333, 23599, 24208, 25954, 26900, 26975, 28057, 28129, 28623, 28626, 29238, 88888);
        Set<Integer> seen = new HashSet<>();
        for (ReferenceCases.Case c : ReferenceCases.published()) {
            if (!runToEnd.contains(c.catalogNumber()) || !seen.add(c.catalogNumber())) {
                continue;
            }
            TLE tle = tle(c);
            Sgp4Propagator propagator = new Sgp4Propagator(tle);
            double lastMinute = c.rows().getLast().minutes();
            for (double seconds = 0; seconds <= lastMinute * 60; seconds += 10) {
                AbsoluteDate date = tle.getDate().shiftedBy(seconds);
                double at = seconds;
                assertThatCode(() -> propagator.screeningState(date))
                        .as("catalog %d at %s s", c.catalogNumber(), at)
                        .doesNotThrowAnyException();
            }
        }
        assertThat(seen).hasSize(runToEnd.size());
    }

    @Test
    void stopsJustBelowTheEightyKilometreFloor() throws IOException {
        TLE tle = tle(published(28350));
        Sgp4Propagator propagator = new Sgp4Propagator(tle);

        assertThatThrownBy(() -> propagator.screeningState(tle.getDate().shiftedBy(1200 * 60.0)))
                .isInstanceOf(PropagationStoppedException.class)
                .hasMessageContaining("altitude 79.9");
    }

    @Test
    void keepsGoingJustAboveTheEightyKilometreFloor() throws IOException {
        TLE tle = tle(published(28350));
        Sgp4Propagator propagator = new Sgp4Propagator(tle);

        assertThatCode(() -> propagator.screeningState(tle.getDate().shiftedBy(1080 * 60.0)))
                .doesNotThrowAnyException();
    }

    @Test
    void stopsOnANonFiniteState() throws IOException {
        TLE tle = tle(published(33333));
        Sgp4Propagator propagator = new Sgp4Propagator(tle);

        assertThatThrownBy(() -> propagator.screeningState(tle.getDate().shiftedBy(25 * 60.0)))
                .isInstanceOf(PropagationStoppedException.class)
                .hasMessageContaining("not finite");
    }

    @Test
    void stopsWhenOrekitRejectsTheElementSetAtSetup() throws IOException {
        ObjectNode gp = ReferenceCases.toGpJson(published(5).line1(), published(5).line2());
        gp.put("ECCENTRICITY", 0.9999995);
        TLE tle = GpElementSets.toTle(gp);

        assertThatThrownBy(() -> new Sgp4Propagator(tle))
                .isInstanceOf(PropagationStoppedException.class)
                .hasCauseInstanceOf(OrekitException.class);
    }

    @Test
    void reportsTheStateInTemeMetresWhenItIsUsable() throws IOException {
        ReferenceCases.Case c = published(5);
        TLE tle = tle(c);

        double[] km = c.rows().getFirst().positionKm();
        Vector3D expected = new Vector3D(km[0] * 1000, km[1] * 1000, km[2] * 1000);

        assertThat(new Sgp4Propagator(tle).screeningState(tle.getDate()).getPosition().distance(expected))
                .isLessThanOrEqualTo(2e-3);
    }
}
