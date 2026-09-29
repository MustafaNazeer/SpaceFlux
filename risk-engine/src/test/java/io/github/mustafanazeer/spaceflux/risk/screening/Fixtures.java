package io.github.mustafanazeer.spaceflux.risk.screening;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.risk.orbit.GpElementSets;
import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.risk.orbit.ReferenceCases;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class Fixtures {

    /** A fixed screening start just after the newest epoch in the stations fixture. */
    static final AbsoluteDate STATIONS_START = new AbsoluteDate("2026-09-27T05:00:00", OrekitData.utc());

    private Fixtures() {
    }

    static List<TrackedObject> stations() throws IOException {
        List<TrackedObject> objects = new ArrayList<>();
        try (InputStream in = Fixtures.class.getResourceAsStream("/celestrak/gp-stations.json")) {
            for (JsonNode gp : new ObjectMapper().readTree(in)) {
                objects.add(new TrackedObject(gp.get("OBJECT_NAME").asString(), GpElementSets.toTle(gp)));
            }
        }
        return objects;
    }

    /** A window start just after the newer element set in the crossing pair (celestrak/PROVENANCE.md). */
    static final AbsoluteDate CROSSING_START = new AbsoluteDate("2026-09-28T15:00:00", OrekitData.utc());

    /** Two independent objects recorded for a real crossing close approach: SL-12 DEB and OBJECT AJ. */
    static List<TrackedObject> crossing() throws IOException {
        List<TrackedObject> objects = new ArrayList<>();
        for (int catalogNumber : new int[] {27958, 57036}) {
            try (InputStream in = Fixtures.class.getResourceAsStream("/celestrak/gp-catnr-" + catalogNumber + ".json")) {
                JsonNode gp = new ObjectMapper().readTree(in).get(0);
                objects.add(new TrackedObject(gp.get("OBJECT_NAME").asString(), GpElementSets.toTle(gp)));
            }
        }
        return objects;
    }

    static TrackedObject station(int catalogNumber) throws IOException {
        return stations().stream().filter(o -> o.catalogNumber() == catalogNumber).findFirst().orElseThrow();
    }

    /**
     * A synthetic variant of a recorded element set for edge cases no recording contains; never presented as
     * recorded data. Arguments left null keep the recorded value.
     */
    static TrackedObject variant(TrackedObject o, String name, Integer catalogNumber, AbsoluteDate epoch,
            Double meanMotion, Double e, Double i, Double meanAnomaly) {
        TLE t = o.tle();
        return new TrackedObject(name == null ? o.name() : name, new TLE(
                catalogNumber == null ? t.getSatelliteNumber() : catalogNumber, t.getClassification(), t.getLaunchYear(),
                t.getLaunchNumber(), t.getLaunchPiece(), t.getEphemerisType(), t.getElementNumber(),
                epoch == null ? t.getDate() : epoch, meanMotion == null ? t.getMeanMotion() : meanMotion,
                t.getMeanMotionFirstDerivative(), t.getMeanMotionSecondDerivative(), e == null ? t.getE() : e,
                i == null ? t.getI() : i, t.getPerigeeArgument(), t.getRaan(),
                meanAnomaly == null ? t.getMeanAnomaly() : meanAnomaly, t.getRevolutionNumberAtEpoch(), t.getBStar(),
                OrekitData.utc()));
    }

    static TrackedObject reference(int catalogNumber) throws IOException {
        ReferenceCases.Case c = ReferenceCases.published().stream()
                .filter(x -> x.catalogNumber() == catalogNumber).findFirst().orElseThrow();
        return new TrackedObject("reference " + catalogNumber, GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2())));
    }
}
