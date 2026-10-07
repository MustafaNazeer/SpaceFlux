package io.github.mustafanazeer.spaceflux.query.plans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.mustafanazeer.spaceflux.query.alerts.AlertsPlanFeed;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fills the alert tables with a fixed seed for the two alert lists. Unlike PlanData, which sends a level change on one
 * GOES poll in ten, the space weather here is quiet most of the time, as the live feed is: each series publishes a
 * refresh per poll and only rarely enters, changes or leaves a level, so the listed rows are a small fraction of the
 * table. The rates are assumptions chosen for that shape, not measurements of the Sun:
 * <ul>
 * <li>R: one series (GOES-18), one event per 5 minute poll; an episode at level R1 starts on about one quiet poll in
 * 1,000 (about two a week) and lasts 3 to 12 polls; one episode in six steps up to R2 once; a "no data" spell starts
 * on about one quiet poll in 600 and lasts 1 to 3 polls with nothing published while it lasts; one restatement a
 * day.</li>
 * <li>S: one series, one event per poll; an episode at S1 starts on about one quiet poll in 4,000 (about one in two
 * weeks) and lasts 12 to 288 polls; one in six steps up to S2.</li>
 * <li>G: nine Kp events per 3 hour interval; an interval is at G1 with chance 1 in 40, or 1 in 2 straight after a G1
 * interval; the first event of an interval is a level change when the state differs from the last interval's.</li>
 * <li>Four screening runs a day with 0 to 3 approaches each, all for the ISS (25544, the one watchlist object), the
 * other object drawn from 200 catalog numbers so each appears in several runs; half the runs also find the last
 * run's first approach again, with the same pair and time, as overlapping windows do, so times of closest approach
 * tie.</li>
 * <li>One listed alert in five acknowledged, and one acknowledgement in four followed by an unacknowledgement.</li>
 * </ul>
 * Every event goes through the consumer's own processor and store.
 */
final class AlertListPlanData {

    static final long SEED = 20261006L;
    static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    static final int WATCHLIST = 25544;
    static final int SATELLITE = 18;
    static final Path ALERTS = Path.of("..").resolve("schemas/alerts/examples");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What was stored, for the counts beside the plans and for picking query parameters. */
    record Seeded(int days, int spaceWeatherEvents, int listedSpaceWeather, int runs, int approaches,
            int acknowledgements) {
    }

    private final AlertsPlanFeed alerts;
    private final JdbcClient api;
    private final Random random = new Random(SEED);
    private final ObjectNode gLevel;
    private final ObjectNode gNone;
    private final ObjectNode rLevel;
    private final ObjectNode sLevel;
    private final ObjectNode rNoData;
    private final ObjectNode restatement;
    private final ObjectNode run;
    private final ObjectNode approach;
    private int events;
    private Repeat repeat;

    AlertListPlanData(AlertsPlanFeed alerts, JdbcClient api) throws Exception {
        this.alerts = alerts;
        this.api = api;
        gLevel = template("valid-g-level.json");
        gNone = template("valid-g-none.json");
        rLevel = template("valid-r-level.json");
        sLevel = template("valid-s-level.json");
        rNoData = template("valid-r-no-data.json");
        restatement = template("valid-r-restatement.json");
        run = template("valid-screening-run.json");
        approach = template("valid-close-approach.json");
    }

    private static ObjectNode template(String file) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(ALERTS.resolve(file)));
    }

    /** One GOES series as a state machine over its polls. */
    private final class Goes {

        final String scale;
        final int startOneIn;
        final int minPolls;
        final int maxPolls;
        final boolean noData;
        String state = "none";
        int level;
        int remaining;
        boolean stepped;

        Goes(String scale, int startOneIn, int minPolls, int maxPolls, boolean noData) {
            this.scale = scale;
            this.startOneIn = startOneIn;
            this.minPolls = minPolls;
            this.maxPolls = maxPolls;
            this.noData = noData;
        }

        void poll(Instant sample, Instant now) {
            switch (state) {
                case "none" -> {
                    if (random.nextInt(startOneIn) == 0) {
                        state = "level";
                        level = 1;
                        stepped = false;
                        remaining = minPolls + random.nextInt(maxPolls - minPolls + 1);
                        store(goes(scale, sample, "level", 1, "level_change", "none", null), now);
                    } else if (noData && random.nextInt(600) == 0) {
                        state = "no_data";
                        remaining = 1 + random.nextInt(3);
                        store(noData(sample), now);
                    } else {
                        store(goes(scale, sample, "none", 0, "refresh", null, null), now);
                    }
                }
                case "level" -> {
                    remaining--;
                    if (remaining <= 0) {
                        state = "none";
                        store(goes(scale, sample, "none", 0, "level_change", "level", level), now);
                    } else if (!stepped && remaining == 2 && random.nextInt(6) == 0) {
                        stepped = true;
                        level = 2;
                        store(goes(scale, sample, "level", 2, "level_change", "level", 1), now);
                    } else {
                        store(goes(scale, sample, "level", level, "refresh", null, null), now);
                    }
                }
                default -> {
                    remaining--;
                    if (remaining <= 0) {
                        state = "none";
                        store(goes(scale, sample, "none", 0, "level_change", "no_data", null), now);
                    }
                }
            }
        }
    }

    Seeded seed(int days) throws Exception {
        Instant end = START.plus(days, ChronoUnit.DAYS);
        Instant now = end.plus(1, ChronoUnit.HOURS);
        Goes r = new Goes("R", 1_000, 3, 12, true);
        Goes s = new Goes("S", 4_000, 12, 288, false);
        boolean gWasLevel = false;
        int runs = 0;
        int approaches = 0;
        for (Instant t = START; t.isBefore(end); t = t.plus(5, ChronoUnit.MINUTES)) {
            long second = t.getEpochSecond();
            if (second % 10_800 == 0) {
                boolean level = random.nextInt(gWasLevel ? 2 : 40) == 0;
                for (int fetch = 0; fetch < 9; fetch++) {
                    Instant fetched = t.plus(20L * fetch, ChronoUnit.MINUTES).plusSeconds(65);
                    boolean change = fetch == 0 && level != gWasLevel;
                    store(kp(t, fetched, level, change), now);
                }
                gWasLevel = level;
            }
            r.poll(t, now);
            s.poll(t, now);
            if (second % 86_400 == 43_200) {
                store(restated(t), now);
            }
            if (second % 21_600 == 0) {
                approaches += screen(t.plusMillis(9_000 + random.nextInt(60_000)), now);
                runs++;
            }
        }
        List<String> listed = api.sql("SELECT e.event_id FROM alert_event e LEFT JOIN space_weather_event w "
                + "ON w.alert_seq = e.alert_seq WHERE e.kind = 'close_approach' OR w.listed = 1 ORDER BY e.alert_seq")
                .query(String.class).list();
        int acknowledgements = 0;
        for (String id : listed) {
            if (random.nextInt(5) == 0) {
                ack(id, "acknowledge");
                acknowledgements++;
                if (random.nextInt(4) == 0) {
                    ack(id, "unacknowledge");
                    acknowledgements++;
                }
            }
        }
        int listedSpaceWeather = api.sql("SELECT COUNT(*) FROM space_weather_event WHERE listed = 1")
                .query(Integer.class).single();
        return new Seeded(days, events, listedSpaceWeather, runs, approaches, acknowledgements);
    }

    /** Stores one space weather event. */
    private void store(byte[] event, Instant now) {
        alerts.store(event, now);
        events++;
    }

    private void ack(String eventId, String action) {
        api.sql("INSERT INTO alert_acknowledgement (event_id, action, principal, note) VALUES (?, ?, ?, ?)")
                .params(eventId, action, "operator", "seen").update();
    }

    private byte[] kp(Instant interval, Instant fetched, boolean level, boolean change) {
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
        previous(p, change ? "level_change" : "refresh", change ? (level ? "none" : "level") : null,
                change && !level ? 1 : null);
        e.put("event_id", "space_weather_level/1/G/-/" + tag + "/" + fetched);
        e.put("produced_at", fetched.plusSeconds(1).toString());
        return JSON.writeValueAsBytes(e);
    }

    /** One poll of a GOES series in state level or none. */
    private byte[] goes(String scale, Instant sample, String state, int level, String trigger, String previousState,
            Integer previousLevel) {
        boolean r = "R".equals(scale);
        ObjectNode e = (r ? rLevel : sLevel).deepCopy();
        ObjectNode p = (ObjectNode) e.get("space_weather_level");
        Instant fetched = sample.plusSeconds(r ? 70 : 310);
        p.put("satellite", SATELLITE);
        p.put("derived_from", r ? "GOES-18 X-ray flux 0.1-0.8nm" : "GOES-18 >=10 MeV integral proton flux");
        p.put("time_tag", sample.toString());
        p.put("sample_time", sample.toString());
        p.put("freshness_reference", sample.toString());
        p.put("fetched_at", fetched.toString());
        if ("level".equals(state)) {
            p.put("state", "level");
            p.put("derived_level", level);
            p.put("derived_label", scale + level);
            if (r) {
                p.put("value", level == 1 ? 2.0e-05 : 5.0e-05);
                p.put("xray_class", level == 1 ? "M2.0" : "M5.0");
            } else {
                p.put("value", level == 1 ? 12.3 : 120.0);
            }
        } else {
            p.remove("derived_level");
            p.remove("xray_class");
            p.put("state", "none");
            p.put("derived_label", "none");
            p.put("value", r ? 1.0e-07 + random.nextInt(900) * 1.0e-09 : 0.3 + random.nextInt(50) / 100.0);
        }
        previous(p, trigger, previousState, previousLevel);
        e.put("event_id", "space_weather_level/1/" + scale + "/" + SATELLITE + "/" + sample + "/" + fetched
                + ("refresh".equals(trigger) ? "/refresh" : ""));
        e.put("produced_at", fetched.plusSeconds(1).toString());
        return JSON.writeValueAsBytes(e);
    }

    /** The start of an R "no data" spell for a rejected record. */
    private byte[] noData(Instant sample) {
        ObjectNode e = rNoData.deepCopy();
        ObjectNode p = (ObjectNode) e.get("space_weather_level");
        p.put("satellite", SATELLITE);
        p.put("derived_from", "GOES-18 X-ray flux 0.1-0.8nm");
        p.put("no_data_since", sample.toString());
        p.put("freshness_reference", sample.toString());
        e.put("event_id", "space_weather_level/1/R/" + SATELLITE + "/no_data/rejected/" + sample);
        e.put("produced_at", sample.plusSeconds(71).toString());
        return JSON.writeValueAsBytes(e);
    }

    /** A restatement of an R sample already published as none. */
    private byte[] restated(Instant sample) {
        ObjectNode e = restatement.deepCopy();
        ObjectNode p = (ObjectNode) e.get("space_weather_level");
        Instant fetched = sample.plusSeconds(190);
        p.put("satellite", SATELLITE);
        p.put("derived_from", "GOES-18 X-ray flux 0.1-0.8nm");
        p.put("time_tag", sample.toString());
        p.put("sample_time", sample.toString());
        p.put("no_data_since", sample.toString());
        p.put("restated_by_time_tag", sample.plusSeconds(60).toString());
        p.put("freshness_reference", sample.plusSeconds(180).toString());
        p.put("fetched_at", fetched.toString());
        e.put("event_id", "space_weather_level/1/R/" + SATELLITE + "/" + sample + "/" + fetched + "/restated");
        e.put("produced_at", fetched.plusSeconds(1).toString());
        return JSON.writeValueAsBytes(e);
    }

    private static void previous(ObjectNode p, String trigger, String state, Integer level) {
        p.put("trigger", trigger);
        p.remove("previous_state");
        p.remove("previous_derived_level");
        if (state != null) {
            p.put("previous_state", state);
        }
        if (level != null) {
            p.put("previous_derived_level", level);
        }
    }

    /** One run and its approaches, published first. */
    private int screen(Instant windowStart, Instant now) {
        String ws = windowStart.toString();
        String runId = ws + "/1";
        int found = random.nextInt(4);
        // Half the runs find the last run's first approach again: same pair, same time, a new event.
        boolean again = repeat != null && repeat.tca().isAfter(windowStart) && random.nextBoolean();
        Repeat first = null;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < found + (again ? 1 : 0); i++) {
            ObjectNode e = approach.deepCopy();
            ObjectNode p = (ObjectNode) e.get("close_approach");
            Instant tca = windowStart.plus(1 + random.nextInt(160), ChronoUnit.HOURS)
                    .plusMillis(random.nextInt(3_600_000)).truncatedTo(ChronoUnit.MILLIS);
            int other = 40_000 + random.nextInt(200);
            if (again && i == found) {
                tca = repeat.tca();
                other = repeat.other();
            }
            if (first == null) {
                first = new Repeat(other, tca);
            }
            p.put("run_id", runId);
            p.put("window_start", ws);
            p.put("window_end", windowStart.plus(7, ChronoUnit.DAYS).toString());
            ObjectNode watch = (ObjectNode) p.get("watchlist_object");
            watch.put("catalog_number", WATCHLIST);
            watch.put("name", "ISS (ZARYA)");
            ((ObjectNode) p.get("other_object")).put("catalog_number", other);
            p.put("time_of_closest_approach", tca.toString());
            p.put("miss_distance_m", 500.0 + random.nextInt(4_400));
            String id = "close_approach/1/" + runId + "/" + WATCHLIST + "/" + other + "/" + tca;
            e.put("event_id", id);
            alerts.store(JSON.writeValueAsBytes(e), now);
            ids.add(id);
        }
        repeat = first;
        ObjectNode e = run.deepCopy();
        ObjectNode p = (ObjectNode) e.get("screening_run");
        p.put("run_id", runId);
        p.put("window_start", ws);
        p.put("input_fetched_at", ws);
        p.put("window_end", windowStart.plus(7, ChronoUnit.DAYS).toString());
        p.put("approach_count", ids.size());
        ArrayNode listed = p.putArray("approach_event_ids");
        ids.forEach(listed::add);
        ((ObjectNode) p.get("omitted")).put("approach_event_ids", 0);
        e.put("event_id", "screening_run/1/" + runId);
        alerts.store(JSON.writeValueAsBytes(e), now);
        return ids.size();
    }

    private record Repeat(int other, Instant tca) {
    }
}
