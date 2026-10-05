package io.github.mustafanazeer.spaceflux.query.plans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.mustafanazeer.spaceflux.query.alerts.AlertsPlanFeed;
import io.github.mustafanazeer.spaceflux.query.catalog.CatalogPlanFeed;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fills the schema with a fixed seed at the rates of docs/data/mysql-schema.md, "Expected volume": one event per
 * ingest poll (every 5 minutes) for each R and S series, three Kp events an hour, four screening runs a day with up
 * to three approaches each, the polled catalog group once, and acknowledgements on about one alert in fifty. Every
 * event goes through the consumer's own processor and store.
 */
final class PlanData {

    static final long SEED = 20261004L;
    static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    static final Path REPO = Path.of("..");
    static final Path ALERTS = REPO.resolve("schemas/alerts/examples");
    static final Path STATIONS = REPO.resolve("ingest/testdata/celestrak/gp-stations.json");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What was stored, for the counts beside the plans and for picking query parameters. */
    record Seeded(int spaceWeatherDays, int runDays, int spaceWeatherEvents, int runs, int approaches,
            int catalogObjects, int acknowledgements, List<String> runIds, List<String> cutRunIds) {
    }

    private final AlertsPlanFeed alerts;
    private final CatalogPlanFeed catalog;
    private final JdbcClient api;
    private final Random random = new Random(SEED);
    private final ObjectNode gLevel;
    private final ObjectNode gNone;
    private final ObjectNode rLevel;
    private final ObjectNode sLevel;
    private final ObjectNode restatement;
    private final ObjectNode run;
    private final ObjectNode approach;

    PlanData(AlertsPlanFeed alerts, CatalogPlanFeed catalog, JdbcClient api) throws Exception {
        this.alerts = alerts;
        this.catalog = catalog;
        this.api = api;
        gLevel = template("valid-g-level.json");
        gNone = template("valid-g-none.json");
        rLevel = template("valid-r-level.json");
        sLevel = template("valid-s-level.json");
        restatement = template("valid-r-restatement.json");
        run = template("valid-screening-run.json");
        approach = template("valid-close-approach.json");
    }

    private static ObjectNode template(String file) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(ALERTS.resolve(file)));
    }

    /** Space weather for the first {@code spaceWeatherDays} days, screening runs for the first {@code runDays}. */
    Seeded seed(int spaceWeatherDays, int runDays) throws Exception {
        Instant end = START.plus(spaceWeatherDays, ChronoUnit.DAYS);
        Instant now = START.plus(Math.max(spaceWeatherDays, runDays), ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);
        int events = 0;
        for (Instant interval = START; interval.isBefore(end); interval = interval.plus(3, ChronoUnit.HOURS)) {
            // Three an hour: the first fetch of the interval can change the level, the later ones refresh it.
            boolean level = random.nextInt(20) == 0;
            for (int fetch = 0; fetch < 9; fetch++) {
                Instant fetched = interval.plus(20L * fetch, ChronoUnit.MINUTES).plusSeconds(65);
                alerts.store(kp(interval, fetched, fetch, level), now);
                events++;
            }
        }
        Instant middle = START.plus(Duration.between(START, end).dividedBy(2));
        for (Instant t = START; t.isBefore(end); t = t.plus(5, ChronoUnit.MINUTES)) {
            // The primary satellite changes halfway, so each scale has two series.
            int satellite = t.isBefore(middle) ? 18 : 19;
            for (String scale : List.of("R", "S")) {
                alerts.store(goes(scale, satellite, t), now);
                events++;
            }
            if (t.getEpochSecond() % 86_400 == 43_200) {
                alerts.store(restated(satellite, t), now);
                events++;
            }
        }
        List<String> runIds = new ArrayList<>();
        List<String> cutRunIds = new ArrayList<>();
        int approaches = 0;
        for (int day = 0; day < runDays; day++) {
            for (int k = 0; k < 4; k++) {
                Instant windowStart = START.plus(day, ChronoUnit.DAYS).plus(6L * k, ChronoUnit.HOURS)
                        .plusMillis(9_000 + random.nextInt(60_000));
                boolean cut = (runIds.size() + cutRunIds.size()) % 25 == 24;
                approaches += screen(windowStart, cut, now, cut ? cutRunIds : runIds);
            }
        }
        int objects = 0;
        for (JsonNode gp : JSON.readTree(Files.readString(STATIONS))) {
            ObjectNode raw = JSON.createObjectNode();
            raw.put("schema_version", 1);
            raw.put("source", "celestrak");
            raw.put("fetched_at", "2026-09-27T08:57:39Z");
            raw.put("source_url", "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json");
            raw.set("gp", gp);
            catalog.apply(JSON.writeValueAsBytes(raw), Instant.parse("2026-09-28T00:00:00Z"));
            objects++;
        }
        List<String> acknowledged = new ArrayList<>();
        for (String id : api.sql("SELECT event_id FROM alert_event ORDER BY alert_seq").query(String.class).list()) {
            if (random.nextInt(50) == 0) {
                acknowledged.add(id);
            }
        }
        for (String id : acknowledged) {
            api.sql("INSERT INTO alert_acknowledgement (event_id, action, principal, note) VALUES (?, ?, ?, ?)")
                    .params(id, "acknowledge", "operator", "seen").update();
        }
        return new Seeded(spaceWeatherDays, runDays, events, runIds.size() + cutRunIds.size(), approaches, objects,
                acknowledged.size(), runIds, cutRunIds);
    }

    private byte[] kp(Instant interval, Instant fetched, int fetch, boolean level) {
        ObjectNode e = (level ? gLevel : gNone).deepCopy();
        ObjectNode p = (ObjectNode) e.get("space_weather_level");
        String tag = interval.toString().replace("Z", "");
        p.put("time_tag", tag);
        p.put("interval_start", interval.toString());
        p.put("interval_end", interval.plus(3, ChronoUnit.HOURS).toString());
        p.put("freshness_reference", interval.toString());
        p.put("fetched_at", fetched.toString());
        if (level) {
            p.put("derived_level", 1);
            p.put("derived_label", "G1");
            p.put("value", 5.33);
        } else {
            p.put("value", 2.0 + interval.getEpochSecond() % 130 / 100.0);
        }
        trigger(p, fetch == 0 ? "level_change" : "refresh", level);
        e.put("event_id", "space_weather_level/1/G/-/" + tag + "/" + fetched);
        e.put("produced_at", fetched.plusSeconds(1).toString());
        return JSON.writeValueAsBytes(e);
    }

    /** One poll of a GOES series: its newest sample, mostly below level 1. */
    private byte[] goes(String scale, int satellite, Instant sample) {
        boolean level = random.nextInt(50) == 0;
        boolean r = "R".equals(scale);
        ObjectNode e = (r ? rLevel : sLevel).deepCopy();
        ObjectNode p = (ObjectNode) e.get("space_weather_level");
        Instant fetched = sample.plusSeconds(r ? 70 : 310);
        p.put("satellite", satellite);
        p.put("derived_from", (r ? "GOES-" + satellite + " X-ray flux 0.1-0.8nm"
                : "GOES-" + satellite + " >=10 MeV integral proton flux"));
        p.put("time_tag", sample.toString());
        p.put("sample_time", sample.toString());
        p.put("freshness_reference", sample.toString());
        p.put("fetched_at", fetched.toString());
        if (level) {
            p.put("value", r ? 2.0e-05 : 12.3);
            if (r) {
                p.put("xray_class", "M2.0");
            }
        } else {
            p.remove("derived_level");
            p.remove("xray_class");
            p.put("state", "none");
            p.put("derived_label", "none");
            p.put("value", r ? 1.0e-07 + random.nextInt(900) * 1.0e-09 : 0.3 + random.nextInt(50) / 100.0);
        }
        trigger(p, random.nextInt(10) == 0 ? "level_change" : "refresh", level);
        e.put("event_id", "space_weather_level/1/" + scale + "/" + satellite + "/" + sample + "/" + fetched);
        e.put("produced_at", fetched.plusSeconds(1).toString());
        return JSON.writeValueAsBytes(e);
    }

    /** A restatement of an R sample already published, as when a zero run shows it was on the edge. */
    private byte[] restated(int satellite, Instant sample) {
        ObjectNode e = restatement.deepCopy();
        ObjectNode p = (ObjectNode) e.get("space_weather_level");
        Instant fetched = sample.plusSeconds(190);
        p.put("satellite", satellite);
        p.put("derived_from", "GOES-" + satellite + " X-ray flux 0.1-0.8nm");
        p.put("time_tag", sample.toString());
        p.put("sample_time", sample.toString());
        p.put("no_data_since", sample.toString());
        p.put("restated_by_time_tag", sample.plusSeconds(60).toString());
        p.put("freshness_reference", sample.plusSeconds(180).toString());
        p.put("fetched_at", fetched.toString());
        e.put("event_id", "space_weather_level/1/R/" + satellite + "/" + sample + "/" + fetched + "/restated");
        e.put("produced_at", fetched.plusSeconds(1).toString());
        return JSON.writeValueAsBytes(e);
    }

    private static void trigger(ObjectNode p, String trigger, boolean level) {
        p.put("trigger", trigger);
        p.remove("previous_state");
        p.remove("previous_derived_level");
        if ("level_change".equals(trigger)) {
            p.put("previous_state", level ? "none" : "level");
            if (!level) {
                p.put("previous_derived_level", 1);
            }
        }
    }

    /** One run and its approaches, published first; a cut run lists one approach fewer than it found. */
    private int screen(Instant windowStart, boolean cut, Instant now, List<String> into) {
        String ws = windowStart.toString();
        String runId = ws + "/1";
        int found = cut ? 1 + random.nextInt(3) : random.nextInt(4);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < found; i++) {
            ObjectNode e = approach.deepCopy();
            ObjectNode p = (ObjectNode) e.get("close_approach");
            Instant tca = windowStart.plus(1 + random.nextInt(160), ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
            int other = 40_000 + random.nextInt(20_000);
            p.put("run_id", runId);
            p.put("window_start", ws);
            p.put("window_end", windowStart.plus(7, ChronoUnit.DAYS).toString());
            ((ObjectNode) p.get("other_object")).put("catalog_number", other);
            p.put("time_of_closest_approach", tca.toString());
            p.put("miss_distance_m", 500.0 + random.nextInt(4_400));
            String id = "close_approach/1/" + runId + "/57036/" + other + "/" + tca;
            e.put("event_id", id);
            alerts.store(JSON.writeValueAsBytes(e), now);
            ids.add(id);
        }
        ObjectNode e = run.deepCopy();
        ObjectNode p = (ObjectNode) e.get("screening_run");
        p.put("run_id", runId);
        p.put("window_start", ws);
        p.put("input_fetched_at", ws);
        p.put("window_end", windowStart.plus(7, ChronoUnit.DAYS).toString());
        p.put("approach_count", found);
        ArrayNode listed = p.putArray("approach_event_ids");
        ids.subList(0, cut ? found - 1 : found).forEach(listed::add);
        ((ObjectNode) p.get("omitted")).put("approach_event_ids", cut ? 1 : 0);
        e.put("event_id", "screening_run/1/" + runId);
        alerts.store(JSON.writeValueAsBytes(e), now);
        into.add(runId);
        return found;
    }
}
