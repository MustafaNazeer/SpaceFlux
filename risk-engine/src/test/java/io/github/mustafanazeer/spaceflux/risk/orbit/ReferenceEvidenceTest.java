package io.github.mustafanazeer.spaceflux.risk.orbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEConstants;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.utils.PVCoordinates;

/**
 * Prints every figure docs/risk/orbital-conventions.md reports that can be recomputed from the committed
 * reference files, so each one traces to this test, and fails when any of them changes from the committed
 * report. Run with -Dtest=ReferenceEvidenceTest.
 */
class ReferenceEvidenceTest {

    private static final double EARTH_RADIUS_KM = TLEConstants.EARTH_RADIUS;

    private static final Set<Integer> COMPARED = Set.of(5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925, 20413,
            21897, 22312, 22674, 23177, 23333, 23599, 24208, 25954, 26900, 26975, 28057, 28129, 28350, 28623, 28626,
            28872, 29141, 29238, 88888);

    private final List<String> report = new ArrayList<>();

    private void line(String format, Object... args) {
        report.add(String.format(Locale.ROOT, format, args));
    }

    private static TLE tle(ReferenceCases.Case c) {
        return new TLE(c.line1(), c.line2(), OrekitData.utc());
    }

    private static Vector3D km(double[] v) {
        return new Vector3D(v[0] * 1000, v[1] * 1000, v[2] * 1000);
    }

    private static double altitudeKm(double[] positionKm) {
        return Math.sqrt(positionKm[0] * positionKm[0] + positionKm[1] * positionKm[1] + positionKm[2] * positionKm[2])
                - EARTH_RADIUS_KM;
    }

    @Test
    void printsTheFiguresTheConventionsNoteReports() throws IOException {
        List<ReferenceCases.Case> cases = ReferenceCases.published();

        ReferenceCases.Case c23599 = find(cases, 23599, 0);
        TLEPropagator p23599 = TLEPropagator.selectExtrapolator(tle(c23599));
        double maxPos = 0;
        double maxPosAt = 0;
        double minLatePos = Double.MAX_VALUE;
        double minLateAt = 0;
        double maxVel = 0;
        double maxVelAt = 0;
        for (ReferenceCases.Row row : c23599.rows()) {
            PVCoordinates pv = p23599.getPVCoordinates(tle(c23599).getDate().shiftedBy(row.minutes() * 60));
            double pos = pv.getPosition().distance(km(row.positionKm()));
            double vel = pv.getVelocity().distance(km(row.velocityKmPerS()));
            if (pos > maxPos) {
                maxPos = pos;
                maxPosAt = row.minutes();
            }
            if (row.minutes() >= 420 && pos < minLatePos) {
                minLatePos = pos;
                minLateAt = row.minutes();
            }
            if (vel > maxVel) {
                maxVel = vel;
                maxVelAt = row.minutes();
            }
        }
        line("23599 vs AFSPC rows: largest position %.2f m at %.0f min; smallest from 420 min %.2f m at %.0f min;"
                + " largest velocity %.4f m/s at %.0f min", maxPos, maxPosAt, minLatePos, minLateAt, maxVel, maxVelAt);

        ReferenceCases.Case c33333 = find(cases, 33333, 0);
        for (ReferenceCases.Row row : c33333.rows()) {
            line("33333 at %.0f min: Orekit differs by %.3f km", row.minutes(), difference(c33333, row) / 1000);
        }
        ReferenceCases.Case c33335 = find(cases, 33335, 0);
        double max33335 = 0;
        for (ReferenceCases.Row row : c33335.rows()) {
            max33335 = Math.max(max33335, difference(c33335, row));
        }
        line("33335: Orekit differs by %.1f m at epoch, %.1f m at most", difference(c33335, c33335.rows().getFirst()),
                max33335);
        ReferenceCases.Case second20413 = find(cases, 20413, 1);
        line("20413 second entry at %.0f min: Orekit differs by %.0f km", second20413.rows().get(1).minutes(),
                difference(second20413, second20413.rows().get(1)) / 1000);

        double[][] failures = {{22312, 494.2028672}, {28350, 1560}, {28872, 55}, {29141, 440}};
        for (double[] f : failures) {
            ReferenceCases.Case c = find(cases, (int) f[0], 0);
            Vector3D r = TLEPropagator.selectExtrapolator(tle(c)).getPVCoordinates(tle(c).getDate().shiftedBy(f[1] * 60))
                    .getPosition();
            line("%d at %s min (reference error step): Orekit radius %.1f km, altitude %.1f km", (int) f[0], f[1],
                    r.getNorm() / 1000, r.getNorm() / 1000 - EARTH_RADIUS_KM);
        }
        ReferenceCases.Case c33334 = find(cases, 33334, 0);
        boolean allNaN = true;
        for (double minutes = 0; minutes <= 1440; minutes += 5) {
            allNaN &= TLEPropagator.selectExtrapolator(tle(c33334))
                    .getPVCoordinates(tle(c33334).getDate().shiftedBy(minutes * 60)).getPosition().isNaN();
        }
        line("33334 every 5 min from 0 to 1440 min: Orekit position NaN at every step %s", allNaN);

        ReferenceCases.Case c28350 = find(cases, 28350, 0);
        line("28350 at 1080 min: Orekit altitude %.2f km", TLEPropagator.selectExtrapolator(tle(c28350))
                .getPVCoordinates(tle(c28350).getDate().shiftedBy(1080 * 60.0)).getPosition().getNorm() / 1000
                - EARTH_RADIUS_KM);

        for (int blank : new int[] {11801, 88888}) {
            ReferenceCases.Case c = find(cases, blank, 0);
            line("%d blank designator: launch year %d from text, %d from GP JSON", blank, tle(c).getLaunchYear(),
                    GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2())).getLaunchYear());
        }

        Set<Integer> agreeing = Set.of(5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925, 20413, 21897, 22312,
                22674, 23177, 23333, 24208, 25954, 26900, 26975, 28057, 28129, 28350, 28623, 28626, 28872, 29141, 29238,
                88888);
        double largestRow = 0;
        String largestAt = "";
        for (int catalogNumber : agreeing) {
            ReferenceCases.Case c = find(cases, catalogNumber, 0);
            for (ReferenceCases.Row row : c.rows()) {
                double d = difference(c, row);
                if (d > largestRow) {
                    largestRow = d;
                    largestAt = catalogNumber + " at " + row.minutes() + " min";
                }
            }
        }
        line("largest row error over the 28 agreeing cases: %.3f mm, %s", largestRow * 1000, largestAt);

        ReferenceCases.Case c28872 = find(cases, 28872, 0);
        TLEPropagator p28872 = TLEPropagator.selectExtrapolator(tle(c28872));
        for (double days : new double[] {1, 30, 365, 7670}) {
            line("28872 at %.0f days after epoch: Orekit radius %.1f km", days,
                    p28872.getPVCoordinates(tle(c28872).getDate().shiftedBy(days * 86400)).getPosition().getNorm() / 1000);
        }

        line("33333 at 25 min: Orekit position NaN %s", TLEPropagator.selectExtrapolator(tle(c33333))
                .getPVCoordinates(tle(c33333).getDate().shiftedBy(25 * 60.0)).getPosition().isNaN());

        Set<Integer> seen = new HashSet<>();
        for (ReferenceCases.Case c : cases) {
            if (!COMPARED.contains(c.catalogNumber()) || !seen.add(c.catalogNumber())) {
                continue;
            }
            ReferenceCases.Row lowest = c.rows().getFirst();
            for (ReferenceCases.Row row : c.rows()) {
                if (altitudeKm(row.positionKm()) < altitudeKm(lowest.positionKm())) {
                    lowest = row;
                }
            }
            ReferenceCases.Row last = c.rows().getLast();
            if (altitudeKm(lowest.positionKm()) < 300) {
                line("%d published rows: lowest %.1f km at %s min; last row %s min at %.1f km", c.catalogNumber(),
                        altitudeKm(lowest.positionKm()), lowest.minutes(), last.minutes(), altitudeKm(last.positionKm()));
            }
        }

        Set<Integer> earlyStop = Set.of(22312, 28350, 28872, 29141);
        seen.clear();
        double lowestOther = Double.MAX_VALUE;
        int lowestOtherCase = 0;
        for (ReferenceCases.Case c : cases) {
            if (!COMPARED.contains(c.catalogNumber()) || !seen.add(c.catalogNumber())) {
                continue;
            }
            TLE tle = tle(c);
            TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);
            double end = c.rows().getLast().minutes() * 60;
            double lowest = Double.MAX_VALUE;
            double lowestAt = 0;
            double firstBelow80 = Double.NaN;
            double firstBelow100 = Double.NaN;
            for (double s = 0; s <= end; s += 10) {
                double alt = propagator.getPVCoordinates(tle.getDate().shiftedBy(s)).getPosition().getNorm() / 1000
                        - EARTH_RADIUS_KM;
                if (alt < lowest) {
                    lowest = alt;
                    lowestAt = s;
                }
                if (alt < 80 && Double.isNaN(firstBelow80)) {
                    firstBelow80 = s;
                }
                if (alt < 100 && Double.isNaN(firstBelow100)) {
                    firstBelow100 = s;
                }
            }
            if (earlyStop.contains(c.catalogNumber())) {
                line("%d sampled every 10 s: first below 80 km at %.1f min", c.catalogNumber(), firstBelow80 / 60);
            } else {
                if (!Double.isNaN(firstBelow100)) {
                    line("%d sampled every 10 s: lowest %.1f km at %.1f min, first below 100 km at %.1f min",
                            c.catalogNumber(), lowest, lowestAt / 60, firstBelow100 / 60);
                }
                if (c.catalogNumber() != 16925 && lowest < lowestOther) {
                    lowestOther = lowest;
                    lowestOtherCase = c.catalogNumber();
                }
            }
        }
        line("lowest sampled altitude of the other run to end cases: %.1f km (%d)", lowestOther, lowestOtherCase);

        report.forEach(System.out::println);
        assertThat(report).containsExactlyElementsOf(ReferenceCases.lines("/evidence/reference-evidence.txt"));
    }

    private static double difference(ReferenceCases.Case c, ReferenceCases.Row row) {
        TLE tle = tle(c);
        return TLEPropagator.selectExtrapolator(tle).getPVCoordinates(tle.getDate().shiftedBy(row.minutes() * 60))
                .getPosition().distance(km(row.positionKm()));
    }

    private static ReferenceCases.Case find(List<ReferenceCases.Case> cases, int catalogNumber, int occurrence) {
        return cases.stream().filter(c -> c.catalogNumber() == catalogNumber).skip(occurrence).findFirst().orElseThrow();
    }
}
