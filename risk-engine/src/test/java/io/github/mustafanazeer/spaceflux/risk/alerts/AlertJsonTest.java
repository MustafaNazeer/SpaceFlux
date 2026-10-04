package io.github.mustafanazeer.spaceflux.risk.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.risk.weather.Scale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Each committed alerts example is what the serializer writes for the same event. */
class AlertJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");

    private static JsonNode example(String name) throws IOException {
        return JSON.readTree(Files.readString(EXAMPLES.resolve(name)));
    }

    private static Instant t(String s) {
        return Instant.parse(s);
    }

    @Test
    void writesTheGLevelExample() throws IOException {
        LevelEvent e = new LevelEvent("space_weather_level/1/G/-/2024-05-10T15:00:00/2026-09-27T22:05:05Z", Scale.G,
                "swpc.kp", "level", 4, "G4", "none", null, "level_change", true, null, 7.67, null,
                "2024-05-10T15:00:00", t("2024-05-10T15:00:00Z"), t("2024-05-10T18:00:00Z"), null,
                t("2026-09-27T22:05:05Z"),
                "https://www.ngdc.noaa.gov/stp/space-weather/swpc-products/daily_reports/space_weather_indices/2024/05/20240510dayind.txt",
                t("2024-05-10T15:00:00Z"), null, null, null, null, null);

        assertThat(AlertJson.write(e, 1, t("2026-09-30T18:50:27Z"))).isEqualTo(example("valid-g-level.json"));
    }

    @Test
    void writesTheRLevelExample() throws IOException {
        LevelEvent e = new LevelEvent("space_weather_level/1/R/16/2024-05-10T03:24:00Z/2026-09-27T22:05:20Z", Scale.R,
                "swpc.goes.xrays", "level", 1, "R1", "none", null, "level_change", false, 16,
                1.0624149581417441e-05, "M1.0", "2024-05-10T03:24:00Z", null, null, t("2024-05-10T03:24:00Z"),
                t("2026-09-27T22:05:20Z"),
                "https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/data/xrsf-l2-avg1m/2024/05/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc",
                t("2024-05-10T03:24:00Z"), null, null, null, null, null);

        assertThat(AlertJson.write(e, 1, t("2026-09-30T18:50:27Z"))).isEqualTo(example("valid-r-level.json"));
    }

    @Test
    void writesTheRNoDataExample() throws IOException {
        LevelEvent e = new LevelEvent("space_weather_level/1/R/18/no_data/rejected/2026-09-24T08:22:00Z", Scale.R,
                "swpc.goes.xrays", "no_data", null, "no data", "none", null, "level_change", false, 18, null, null,
                null, null, null, null, null, null, t("2026-09-24T08:27:00Z"), null, "rejected",
                t("2026-09-24T08:22:00Z"), null, null);

        assertThat(AlertJson.write(e, 1, t("2026-09-30T19:40:31Z"))).isEqualTo(example("valid-r-no-data.json"));
    }

    @Test
    void writesTheSLevelExample() throws IOException {
        JsonNode expected = example("valid-s-level.json");
        JsonNode p = expected.get("space_weather_level");
        LevelEvent e = new LevelEvent(expected.get("event_id").asString(), Scale.S, "swpc.goes.protons", "level",
                p.get("derived_level").asInt(), p.get("derived_label").asString(),
                p.get("previous_state").asString(), null, "level_change", false, p.get("satellite").asInt(),
                p.get("value").asDouble(), null, p.get("time_tag").asString(), null, null,
                t(p.get("sample_time").asString()), t(p.get("fetched_at").asString()),
                p.get("source_url").asString(), t(p.get("freshness_reference").asString()), null, null, null, null,
                null);

        assertThat(AlertJson.write(e, 1, t(expected.get("produced_at").asString()))).isEqualTo(expected);
    }

    @Test
    void everyEventTheTrackerProducesPassesTheAlertsSchema() throws IOException {
        List<LevelEvent> events = new ArrayList<>();
        String kpUrl = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        events.addAll(g.accept(List.of(kp("2024-05-10T15:00:00", 5.0, kpUrl), kp("2024-05-10T18:00:00", 7.67, kpUrl)),
                t("2024-05-10T21:05:00Z")));
        events.addAll(g.tick(t("2024-05-10T21:20:00Z")));
        events.addAll(g.accept(List.of(kp("2024-05-10T15:00:00", 4.33, kpUrl), kp("2024-05-10T18:00:00", 8.0, kpUrl)),
                t("2024-05-10T21:25:00Z")));
        events.addAll(g.accept(List.of(kp("2024-05-10T21:00:00", 12.0, kpUrl)), t("2024-05-11T00:05:00Z")));
        events.addAll(g.accept(List.of(kp("2024-05-10T18:00:00", 9.5, kpUrl)), t("2024-05-11T00:06:00Z")));
        events.addAll(g.tick(t("2024-05-11T08:00:00Z")));

        JsonNode records = JSON.readTree(getClass().getResourceAsStream(
                "/swpc-xrays/goes18-xrays-7-day-eclipse-2026-09-24.json"));
        String xrUrl = "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json";
        TreeMap<Instant, List<Reading>> byPoll = new TreeMap<>();
        for (JsonNode rec : records) {
            Reading.of("swpc.goes.xrays", rec, t("2026-09-24T12:00:00Z"), xrUrl).ifPresent(r -> byPoll
                    .computeIfAbsent(Instant.ofEpochSecond(r.time().getEpochSecond() / 180 * 180),
                            k -> new ArrayList<>()).add(r));
        }
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        byPoll.forEach((poll, batch) -> events.addAll(r.accept(batch, poll.plusSeconds(300))));
        events.addAll(r.tick(t("2026-09-24T10:40:00Z")));
        String flare = "{\"time_tag\":\"2026-09-24T10:34:00Z\",\"satellite\":19,\"flux\":3.9789e-4,"
                + "\"observed_flux\":3.9789e-4,\"energy\":\"0.1-0.8nm\"}";
        events.addAll(r.accept(List.of(Reading.of("swpc.goes.xrays", JSON.readTree(flare), t("2026-09-24T10:36:00Z"),
                xrUrl).orElseThrow()), t("2026-09-24T10:41:00Z")));
        events.addAll(r.tick(t("2026-09-24T12:00:00Z")));

        assertThat(events).extracting(LevelEvent::trigger)
                .contains("level_change", "revision", "restatement", "refresh");
        assertThat(events).extracting(LevelEvent::state).contains("level", "none", "no_data", "ended");
        assertThat(events).anyMatch(e -> e.trigger().equals("revision") && e.state().equals("no_data"));
        TopicSchemas schemas = TopicSchemas.fromClasspath();
        for (LevelEvent e : events) {
            JsonNode json = AlertJson.write(e, 1, t("2026-09-30T20:00:00Z"));
            assertThat(schemas.check("alerts", json).failure()).as(json.toString()).isNull();
        }
    }

    private static Reading kp(String timeTag, double kp, String url) {
        return Reading.of("swpc.kp", JSON.readTree("{\"time_tag\":\"" + timeTag + "\",\"Kp\":" + kp + "}"),
                t("2024-05-10T21:04:00Z"), url).orElseThrow();
    }
}
