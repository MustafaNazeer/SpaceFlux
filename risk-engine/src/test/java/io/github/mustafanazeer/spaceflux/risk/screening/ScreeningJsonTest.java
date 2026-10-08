package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.orbit.Fixtures;
import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack.StopKind;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.risk.alerts.ScreeningJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Screening results written as close_approach and screening_run events of the alerts topic. */
class ScreeningJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Numbers compare by value, so 15727 in an example equals 15727.0 written from a double. */
    private static final java.util.Comparator<JsonNode> NUMERIC = (a, b) -> a.isNumber() && b.isNumber()
            ? Double.compare(a.asDouble(), b.asDouble())
            : a.equals(b) ? 0 : 1;

    private static JsonNode example(String name) throws IOException {
        return JSON.readTree(Files.readString(Path.of("..", "schemas", "alerts", "examples", name)));
    }

    @Test
    void writesTheCommittedCloseApproachAndRunExamples() throws IOException {
        AbsoluteDate start = new AbsoluteDate("2026-09-29T05:20:09", OrekitData.utc());
        CloseApproach approach = new CloseApproach(57036, 27958,
                new AbsoluteDate("2026-09-30T03:34:37.588", OrekitData.utc()), 1973.3, 15727, 1.5585, 4.1985);
        ScreeningResult result = new ScreeningResult(start, start.shiftedBy(ScreeningSettings.WINDOW_S),
                new ScreeningResult.Coverage(1, 2, 1, 0, 0, 1), List.of(approach), List.of(), List.of(), List.of(),
                List.of(), List.of());

        List<JsonNode> events = ScreeningJson.write(result, Map.of(57036, "OBJECT AJ", 27958, "SL-12 DEB"),
                Instant.parse("2026-09-29T05:20:09Z"), 1, Instant.parse("2026-09-30T18:50:27Z"), OrekitData.utc());

        assertThat(events).hasSize(2);
        assertThat(events.get(0).equals(NUMERIC, example("valid-close-approach.json"))).as(events.get(0).toString())
                .isTrue();
        assertThat(events.get(1).equals(NUMERIC, example("valid-screening-run.json"))).as(events.get(1).toString())
                .isTrue();
    }

    @Test
    void aRealStationsRunWithTheIssWatchlistPassesTheAlertsSchema() throws IOException {
        List<TrackedObject> stations = Fixtures.stations();
        TrackedObject iss = Fixtures.station(25544);
        ScreeningResult result = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(iss), stations, Fixtures.STATIONS_START);
        Map<Integer, String> names = new java.util.HashMap<>();
        stations.forEach(o -> names.put(o.catalogNumber(), o.name()));

        List<JsonNode> events = ScreeningJson.write(result, names, Instant.parse("2026-09-27T05:00:00Z"), 1,
                Instant.parse("2026-09-30T20:00:00Z"), OrekitData.utc());

        JsonNode run = events.get(events.size() - 1);
        assertThat(run.get("kind").asString()).isEqualTo("screening_run");
        assertThat(run.get("screening_run").get("suppressed")).isNotEmpty();
        assertThat(run.get("screening_run").get("suppressed")).allSatisfy(n -> {
            assertThat(n.get("watchlist_name").asString()).isEqualTo("ISS (ZARYA)");
            assertThat(n.get("other_name").asString()).isNotBlank();
            assertThat(n.get("stack_name").asString()).isEqualTo("International Space Station");
            List<String> fields = new java.util.ArrayList<>();
            n.propertyNames().forEach(fields::add);
            assertThat(fields.indexOf("stack_name")).isEqualTo(fields.indexOf("mechanism") + 1);
        });
        assertThat(run.get("screening_run").get("approach_count").asInt()).isEqualTo(events.size() - 1);
        TopicSchemas schemas = TopicSchemas.fromClasspath();
        for (JsonNode e : events) {
            assertThat(schemas.check("alerts", e).failure()).as(e.toString()).isNull();
        }
    }

    @Test
    void writesEachStopKindAsItsSchemaCodeAndPassesTheAlertsSchema() throws IOException {
        AbsoluteDate start = new AbsoluteDate("2026-09-27T05:00:00", OrekitData.utc());
        List<ScreeningResult.NotScreened> notScreened = List.of(
                new ScreeningResult.NotScreened(28872, Role.WATCHLIST, StopKind.STOPPED_IN_WINDOW,
                        start.shiftedBy(2640), "stopped"),
                new ScreeningResult.NotScreened(28350, Role.CATALOG, StopKind.STOPPED_BEFORE_WINDOW, null, "stopped"),
                new ScreeningResult.NotScreened(33334, Role.CATALOG, StopKind.CANNOT_PROPAGATE, null, "cannot"));
        ScreeningResult result = new ScreeningResult(start, start.shiftedBy(ScreeningSettings.WINDOW_S),
                new ScreeningResult.Coverage(1, 2, 2, 2, 0, 0), List.of(), List.of(), List.of(), notScreened,
                List.of(), List.of());

        List<JsonNode> events = ScreeningJson.write(result, Map.of(28872, "A", 28350, "B", 33334, "C"),
                Instant.parse("2026-09-27T05:00:00Z"), 1, Instant.parse("2026-09-27T06:00:00Z"), OrekitData.utc());

        JsonNode run = events.get(events.size() - 1);
        List<String> kinds = new java.util.ArrayList<>();
        run.get("screening_run").get("not_screened").forEach(n -> kinds.add(n.get("kind").asString()));
        assertThat(kinds).containsExactly("stopped_in_window", "stopped_before_window", "cannot_propagate");
        assertThat(run.get("screening_run").get("not_screened").get(0).has("screened_until")).isTrue();
        assertThat(TopicSchemas.fromClasspath().check("alerts", run).failure()).as(run.toString()).isNull();
    }
}
