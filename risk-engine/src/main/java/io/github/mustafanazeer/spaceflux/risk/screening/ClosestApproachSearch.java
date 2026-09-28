package io.github.mustafanazeer.spaceflux.risk.screening;

import java.util.ArrayList;
import java.util.List;

import org.hipparchus.ode.events.Action;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.EventDetectionSettings;
import org.orekit.propagation.events.EventSlopeFilter;
import org.orekit.propagation.events.ExtremumApproachDetector;
import org.orekit.propagation.events.FilterType;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;

/**
 * Times of closest approach where g = Δr · Δv crosses zero from negative to positive
 * (docs/risk/orbital-conventions.md 3.4). Each object gets its own propagator, as Orekit requires for the
 * secondary provider.
 */
public final class ClosestApproachSearch {

    private ClosestApproachSearch() {
    }

    public static AbsoluteDate searchEnd(ObjectTrack a, ObjectTrack b) {
        return a.screenableUntil().isBefore(b.screenableUntil()) ? a.screenableUntil() : b.screenableUntil();
    }

    /**
     * Identical element sets give a constant separation, so g is zero everywhere and Orekit's root search
     * never finishes; such a pair has no closest approach to find.
     */
    static boolean sameElements(TLE a, TLE b) {
        return a.getDate().equals(b.getDate()) && a.getMeanMotion() == b.getMeanMotion() && a.getE() == b.getE()
                && a.getI() == b.getI() && a.getRaan() == b.getRaan() && a.getPerigeeArgument() == b.getPerigeeArgument()
                && a.getMeanAnomaly() == b.getMeanAnomaly() && a.getBStar() == b.getBStar();
    }

    public static List<CloseApproach> find(ObjectTrack a, ObjectTrack b, AbsoluteDate start, double reportM) {
        AbsoluteDate end = searchEnd(a, b);
        if (!end.isAfter(start) || sameElements(a.object().tle(), b.object().tle())) {
            return List.of();
        }
        List<AbsoluteDate> tcas = new ArrayList<>();
        TLEPropagator primary = TLEPropagator.selectExtrapolator(a.object().tle());
        ExtremumApproachDetector detector = new ExtremumApproachDetector(
                TLEPropagator.selectExtrapolator(b.object().tle()))
                .withDetectionSettings(new EventDetectionSettings(ScreeningSettings.MAX_CHECK_S,
                        ScreeningSettings.CONVERGENCE_THRESHOLD_S, ScreeningSettings.MAX_ITERATIONS))
                .withHandler((state, d, increasing) -> {
                    tcas.add(state.getDate());
                    return Action.CONTINUE;
                });
        primary.addEventDetector(new EventSlopeFilter<>(detector, FilterType.TRIGGER_ONLY_INCREASING_EVENTS));
        primary.propagate(start, end);

        TLEPropagator stateA = TLEPropagator.selectExtrapolator(a.object().tle());
        TLEPropagator stateB = TLEPropagator.selectExtrapolator(b.object().tle());
        List<CloseApproach> approaches = new ArrayList<>();
        for (AbsoluteDate tca : tcas) {
            PVCoordinates pa = stateA.getPVCoordinates(tca);
            PVCoordinates pb = stateB.getPVCoordinates(tca);
            double miss = pb.getPosition().distance(pa.getPosition());
            if (miss <= reportM) {
                approaches.add(new CloseApproach(a.object().catalogNumber(), b.object().catalogNumber(), tca, miss,
                        pb.getVelocity().distance(pa.getVelocity()),
                        tca.durationFrom(a.object().tle().getDate()) / 86400,
                        tca.durationFrom(b.object().tle().getDate()) / 86400));
            }
        }
        return approaches;
    }
}
