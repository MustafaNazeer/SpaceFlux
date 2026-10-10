package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.DateTimeComponents;
import org.orekit.utils.IERSConventions;

import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.query.passes.ReferencePasses.ReferenceObject;
import io.github.mustafanazeer.spaceflux.query.passes.ReferencePasses.Window;

/**
 * docs/risk/orbital-conventions.md 6.9, test 1: Orekit's TEME to ITRF (IERS 2010, no EOP data) against the GMST 1982
 * rotation of AIAA 2006-6753 Equations (1) and (2) with UT1 equal to UTC, at the object's own position. Loading EOP
 * data would move the two apart, so this also pins its absence.
 */
class PassFrameTest {

    static final double BOUND_RAD = 3.0e-7;
    static final double STEP_S = 1800;

    /** AIAA 2006-6753 Equation (2), the paper's gstime: seconds of time, then 1/240 degree per second. */
    static double gmst1982Rad(AbsoluteDate date) {
        DateTimeComponents c = date.getComponents(OrekitData.utc());
        double daysFromJ2000 = c.getDate().getJ2000Day() + (c.getTime().getSecondsInUTCDay() - 43_200) / 86_400;
        double t = daysFromJ2000 / 36_525;
        double seconds = -6.2e-6 * t * t * t + 0.093104 * t * t + (876_600.0 * 3600 + 8_640_184.812866) * t
                + 67_310.54841;
        double rad = Math.IEEEremainder(Math.toRadians(seconds / 240), 2 * Math.PI);
        return rad < 0 ? rad + 2 * Math.PI : rad;
    }

    /** Equation (1): rPEF = ROT3(theta) rTEME; with no polar motion the PEF is the Earth fixed frame. */
    static Vector3D rot3(double theta, Vector3D r) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        return new Vector3D(c * r.getX() + s * r.getY(), -s * r.getX() + c * r.getY(), r.getZ());
    }

    @Test
    void atJ2000TheAngleIsTheEquationsConstantTerm() {
        AbsoluteDate j2000 = new AbsoluteDate("2000-01-01T12:00:00", OrekitData.utc());

        assertThat(Math.toDegrees(gmst1982Rad(j2000))).isEqualTo(67_310.54841 / 240, org.assertj.core.api.Assertions
                .within(1e-9));
    }

    @Test
    void orekitsEarthFixedFrameAndTheGmst1982RotationDifferByAtMostTheBoundAngle() {
        OrekitData.load();
        Frame teme = FramesFactory.getTEME();
        Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        double worst = 0;
        double worstAngle = 0;
        String where = "";
        int samples = 0;
        for (ReferenceObject o : ReferencePasses.load().objects()) {
            TrackedObject object = o.elementSet();
            TLEPropagator propagator = TLEPropagator.selectExtrapolator(object.tle());
            for (Window w : o.windows()) {
                for (double s = 0; s <= PassFinder.WINDOW_S; s += STEP_S) {
                    AbsoluteDate date = w.startDate().shiftedBy(s);
                    Vector3D r = propagator.getPVCoordinates(date, teme).getPosition();
                    Vector3D orekit = teme.getStaticTransformTo(itrf, date).transformPosition(r);
                    double d = orekit.distance(rot3(gmst1982Rad(date), r));
                    samples++;
                    worstAngle = Math.max(worstAngle, d / r.getNorm());
                    if (d > worst) {
                        worst = d;
                        where = o.noradCatId() + " " + w.kind() + " at " + date + String.format(Locale.ROOT,
                                ", %.0f km from the centre", r.getNorm() / 1000);
                    }
                }
            }
        }
        System.out.println(String.format(Locale.ROOT,
                "Frame difference, Orekit ITRF against GMST 1982: largest %.3f m over %d samples (%s)", worst,
                samples, where));
        System.out.println(String.format(Locale.ROOT,
                "Frame difference as an angle at the Earth's centre: largest %.4g rad (%.3f m at 6,798 km)",
                worstAngle, worstAngle * 6_798_000));
        assertThat(samples).isEqualTo(12 * 49);
        assertThat(worstAngle).isLessThanOrEqualTo(BOUND_RAD);
    }
}
