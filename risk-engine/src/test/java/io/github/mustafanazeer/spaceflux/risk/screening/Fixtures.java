package io.github.mustafanazeer.spaceflux.risk.screening;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    static TrackedObject station(int catalogNumber) throws IOException {
        return stations().stream().filter(o -> o.catalogNumber() == catalogNumber).findFirst().orElseThrow();
    }

    static TrackedObject reference(int catalogNumber) throws IOException {
        ReferenceCases.Case c = ReferenceCases.published().stream()
                .filter(x -> x.catalogNumber() == catalogNumber).findFirst().orElseThrow();
        return new TrackedObject("reference " + catalogNumber, GpElementSets.toTle(ReferenceCases.toGpJson(c.line1(), c.line2())));
    }
}
