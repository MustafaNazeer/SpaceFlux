package io.github.mustafanazeer.spaceflux.risk.screening;

import java.util.ArrayList;
import java.util.List;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;

/**
 * Dense reference scan of every pair at a fixed step, independent of the screening pipeline
 * (docs/risk/orbital-conventions.md 3.6 item 2).
 */
final class BruteForceScan {

    /** {@code minima} holds every sampled local minimum up to the collection distance given to the scan. */
    record PairStats(int a, int b, double minKm, double maxKm, List<Minimum> minima) {

        List<Minimum> minimaWithin(double km) {
            return minima.stream().filter(m -> m.distanceKm() <= km).toList();
        }

        /** Whether a sampled local minimum within the given distance lies within the given time of t. */
        boolean minimaNear(double t, double withinS, double withinKm) {
            return minima.stream()
                    .anyMatch(m -> Math.abs(m.secondsFromStart() - t) < withinS && m.distanceKm() <= withinKm);
        }
    }

    record Minimum(double secondsFromStart, double distanceKm) {
    }

    private BruteForceScan() {
    }

    /**
     * The smallest separation within one step either side of a sampled minimum, by golden section search on
     * |Δr| alone, independent of the event detector the pipeline uses.
     */
    static Minimum refine(TLE a, TLE b, AbsoluteDate start, Minimum sampled, double stepS) {
        TLEPropagator pa = TLEPropagator.selectExtrapolator(a);
        TLEPropagator pb = TLEPropagator.selectExtrapolator(b);
        double ratio = (Math.sqrt(5) - 1) / 2;
        double lo = sampled.secondsFromStart() - stepS;
        double hi = sampled.secondsFromStart() + stepS;
        while (hi - lo > 1e-7) {
            double x1 = hi - ratio * (hi - lo);
            double x2 = lo + ratio * (hi - lo);
            if (distanceKm(pa, pb, start, x1) < distanceKm(pa, pb, start, x2)) {
                hi = x2;
            } else {
                lo = x1;
            }
        }
        double t = (lo + hi) / 2;
        return new Minimum(t, distanceKm(pa, pb, start, t));
    }

    private static double distanceKm(TLEPropagator pa, TLEPropagator pb, AbsoluteDate start, double t) {
        AbsoluteDate date = start.shiftedBy(t);
        return pa.getPVCoordinates(date).getPosition().distance(pb.getPVCoordinates(date).getPosition()) / 1000;
    }

    static List<PairStats> scan(List<TLE> tles, AbsoluteDate start, double durationS, double stepS, double collectKm) {
        int n = tles.size();
        List<TLEPropagator> propagators = tles.stream().map(TLEPropagator::selectExtrapolator).toList();
        double[][] min = new double[n][n];
        double[][] max = new double[n][n];
        double[][] previous = new double[n][n];
        double[][] beforePrevious = new double[n][n];
        List<List<List<Minimum>>> minima = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<List<Minimum>> row = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                row.add(new ArrayList<>());
                min[i][j] = Double.MAX_VALUE;
                previous[i][j] = Double.NaN;
                beforePrevious[i][j] = Double.NaN;
            }
            minima.add(row);
        }
        Vector3D[] positions = new Vector3D[n];
        long steps = Math.round(durationS / stepS);
        for (long k = 0; k <= steps; k++) {
            double t = k * stepS;
            AbsoluteDate date = start.shiftedBy(t);
            for (int i = 0; i < n; i++) {
                positions[i] = propagators.get(i).getPVCoordinates(date).getPosition();
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double d = positions[i].distance(positions[j]) / 1000;
                    min[i][j] = Math.min(min[i][j], d);
                    max[i][j] = Math.max(max[i][j], d);
                    if (previous[i][j] <= collectKm && previous[i][j] < beforePrevious[i][j] && previous[i][j] <= d) {
                        minima.get(i).get(j).add(new Minimum(t - stepS, previous[i][j]));
                    }
                    beforePrevious[i][j] = previous[i][j];
                    previous[i][j] = d;
                }
            }
        }
        List<PairStats> stats = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                stats.add(new PairStats(tles.get(i).getSatelliteNumber(), tles.get(j).getSatelliteNumber(), min[i][j],
                        max[i][j], minima.get(i).get(j)));
            }
        }
        return stats;
    }
}
