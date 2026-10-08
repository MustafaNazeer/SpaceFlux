package io.github.mustafanazeer.spaceflux.orbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.utils.PVCoordinates;

/** Tolerances and case selection: docs/risk/orbital-conventions.md Section 1. */
class Sgp4ComplianceTest {

    private static final double POSITION_TOLERANCE_M = 2e-3;
    private static final double VELOCITY_TOLERANCE_M_PER_S = 1e-5;

    private static final Set<Integer> AGREEING_CASES = Set.of(5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925,
            20413, 21897, 22312, 22674, 23177, 23333, 24208, 25954, 26900, 26975, 28057, 28129, 28350, 28623, 28626,
            28872, 29141, 29238, 88888);

    private static final double SUMMED_POSITION_TOLERANCE_M = 0.026;
    private static final double AFSPC_DEVIATION_CEILING_M = 1000;
    private static final double AFSPC_DEVIATION_STARTS_MIN = 420;

    private static final int AFSPC_DEVIATION_CASE = 23599;

    static Stream<Arguments> agreeingCases() throws IOException {
        List<ReferenceCases.Case> cases = ReferenceCases.published();
        return cases.stream()
                .filter(c -> AGREEING_CASES.contains(c.catalogNumber()))
                .filter(c -> cases.indexOf(c) == firstIndexOf(cases, c.catalogNumber()))
                .map(c -> Arguments.of(c.catalogNumber(), c));
    }

    private static int firstIndexOf(List<ReferenceCases.Case> cases, int catalogNumber) {
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i).catalogNumber() == catalogNumber) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void selectsTwentyEightAgreeingCases() throws IOException {
        assertThat(agreeingCases()).hasSize(28);
    }

    @ParameterizedTest(name = "text TLE, catalog {0}")
    @MethodSource("agreeingCases")
    void textElementSetMatchesPublishedEphemeris(int catalogNumber, ReferenceCases.Case c) {
        TLE tle = new TLE(c.line1(), c.line2(), OrekitData.utc());

        assertMatches(tle, c.rows());
    }

    @ParameterizedTest(name = "GP JSON, catalog {0}")
    @MethodSource("agreeingCases")
    void gpJsonElementSetMatchesPublishedEphemeris(int catalogNumber, ReferenceCases.Case c) {
        TLE tle = GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2()));

        assertMatches(tle, c.rows());
    }

    @Test
    void deepSpaceCase23599HasThirtySevenRowsInBothReferences() throws IOException {
        assertThat(case23599().rows()).hasSize(37);
        assertThat(improvedMode23599()).hasSize(37);
    }

    @Test
    void deepSpaceCase23599MatchesOrekitImprovedModeReferenceFromText() throws IOException {
        ReferenceCases.Case c = case23599();

        assertMatches(new TLE(c.line1(), c.line2(), OrekitData.utc()), improvedMode23599());
    }

    @Test
    void deepSpaceCase23599MatchesOrekitImprovedModeReferenceFromGpJson() throws IOException {
        ReferenceCases.Case c = case23599();

        assertMatches(GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2())), improvedMode23599());
    }

    private static ReferenceCases.Case case23599() throws IOException {
        return ReferenceCases.published().stream()
                .filter(x -> x.catalogNumber() == AFSPC_DEVIATION_CASE).findFirst().orElseThrow();
    }

    private static List<ReferenceCases.Row> improvedMode23599() throws IOException {
        return ReferenceCases.results("/sgp4/orekit-satcode-results-23599.txt").getFirst();
    }

    @Test
    void summedPositionErrorOverTheAgreeingCasesIsWithinTolerance() throws IOException {
        double sum = 0;
        double largest = 0;
        int rowCount = 0;
        for (Arguments arguments : agreeingCases().toList()) {
            ReferenceCases.Case c = (ReferenceCases.Case) arguments.get()[1];
            TLE tle = GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2()));
            Sgp4Propagator propagator = new Sgp4Propagator(tle);
            for (ReferenceCases.Row row : c.rows()) {
                double error = propagator.propagate(tle.getDate().shiftedBy(row.minutes() * 60)).getPosition()
                        .distance(kmToM(row.positionKm()));
                sum += error;
                largest = Math.max(largest, error);
                rowCount++;
            }
        }
        System.out.printf("SGP4 compliance: %d rows, summed position error %.4f m, largest row %.3f mm%n",
                rowCount, sum, largest * 1000);

        assertThat(rowCount).isEqualTo(481);
        assertThat(sum).isLessThanOrEqualTo(SUMMED_POSITION_TOLERANCE_M);
    }

    @Test
    void deepSpaceCase23599DeviatesFromAfspcModeOnlyAfter420MinutesAndByLessThanOneKilometre() throws IOException {
        ReferenceCases.Case c = case23599();
        TLE tle = GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2()));
        Sgp4Propagator propagator = new Sgp4Propagator(tle);

        for (ReferenceCases.Row row : c.rows()) {
            double error = propagator.propagate(tle.getDate().shiftedBy(row.minutes() * 60)).getPosition()
                    .distance(kmToM(row.positionKm()));
            if (row.minutes() < AFSPC_DEVIATION_STARTS_MIN) {
                assertThat(error).as("at %s min", row.minutes()).isLessThanOrEqualTo(POSITION_TOLERANCE_M);
            } else {
                assertThat(error).as("at %s min", row.minutes())
                        .isGreaterThan(POSITION_TOLERANCE_M).isLessThan(AFSPC_DEVIATION_CEILING_M);
            }
        }
    }

    static Stream<Arguments> referenceFailureSteps() {
        return Stream.of(
                Arguments.of(22312, 494.2028672),
                Arguments.of(28350, 1560.0),
                Arguments.of(28872, 55.0),
                Arguments.of(29141, 440.0));
    }

    @ParameterizedTest(name = "catalog {0} at {1} min")
    @MethodSource("referenceFailureSteps")
    void orekitReturnsAFiniteStateWhereTheReferenceCodeReportsAnError(int catalogNumber, double minutes)
            throws IOException {
        ReferenceCases.Case c = ReferenceCases.published().stream()
                .filter(x -> x.catalogNumber() == catalogNumber).findFirst().orElseThrow();
        TLE tle = new TLE(c.line1(), c.line2(), OrekitData.utc());

        PVCoordinates pv = TLEPropagator.selectExtrapolator(tle).getPVCoordinates(tle.getDate().shiftedBy(minutes * 60));

        assertThat(pv.getPosition().isNaN()).isFalse();
        assertThat(pv.getPosition().isInfinite()).isFalse();
        assertThat(pv.getVelocity().isNaN()).isFalse();
        assertThat(pv.getVelocity().isInfinite()).isFalse();
    }

    private static void assertMatches(TLE tle, List<ReferenceCases.Row> rows) {
        Sgp4Propagator propagator = new Sgp4Propagator(tle);
        for (ReferenceCases.Row row : rows) {
            PVCoordinates pv = propagator.propagate(tle.getDate().shiftedBy(row.minutes() * 60));

            double positionError = pv.getPosition().distance(kmToM(row.positionKm()));
            double velocityError = pv.getVelocity().distance(kmToM(row.velocityKmPerS()));

            assertThat(positionError).as("position error at %s min", row.minutes()).isLessThanOrEqualTo(POSITION_TOLERANCE_M);
            assertThat(velocityError).as("velocity error at %s min", row.minutes()).isLessThanOrEqualTo(VELOCITY_TOLERANCE_M_PER_S);
        }
    }

    private static Vector3D kmToM(double[] km) {
        return new Vector3D(km[0] * 1000, km[1] * 1000, km[2] * 1000);
    }
}
