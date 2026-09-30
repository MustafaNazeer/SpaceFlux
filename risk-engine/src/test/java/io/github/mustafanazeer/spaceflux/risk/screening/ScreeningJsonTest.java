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

import io.github.mustafanazeer.spaceflux.risk.alerts.ScreeningJson;
import io.github.mustafanazeer.spaceflux.risk.kafka.TopicSchemas;
import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;
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
        assertThat(run.get("screening_run").get("approach_count").asInt()).isEqualTo(events.size() - 1);
        TopicSchemas schemas = TopicSchemas.fromClasspath();
        for (JsonNode e : events) {
            assertThat(schemas.check("alerts", e).failure()).as(e.toString()).isNull();
        }
    }
}
