package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Values an INT UNSIGNED column holds above 2147483647, GraphQL's Int, reach the client as JSON integers on every
 * path that returns them. The events are stored through the consumer's own processor and store, so this does not
 * depend on another test having stored such a value first.
 */
class UnsignedIntGraphQlIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final long MAX = 4_294_967_295L;
    static final String TAG = "/uint32-graphql";
    /** A catalog number no other test uses, put in the stored close approach as its watchlist object. */
    static final int WATCHLIST_OBJECT = 90057;

    static OperatorApp app;
    static String closeApproachId;
    static String runId;
    static String decimalPointId;

    @BeforeAll
    static void start() throws Exception {
        app = new OperatorApp();
        cleanUp();
        AlertsProcessor processor = new AlertsProcessor(TopicSchemas.fromClasspath(),
                app.context.getBean(AlertStore.class));

        ObjectNode approach = example("valid-close-approach.json");
        approach.put("rules_version", MAX);
        ((ObjectNode) approach.get("close_approach").get("watchlist_object")).put("catalog_number", WATCHLIST_OBJECT);
        closeApproachId = approach.get("event_id").asString() + TAG;
        approach.put("event_id", closeApproachId);
        stored(processor, approach, 1);

        ObjectNode decimalPoint = example("valid-close-approach.json");
        decimalPoint.put("rules_version", 1.0);
        decimalPointId = decimalPoint.get("event_id").asString() + "/decimal-point" + TAG;
        decimalPoint.put("event_id", decimalPointId);
        stored(processor, decimalPoint, 3);

        ObjectNode run = example("valid-screening-run-cut.json");
        run.put("rules_version", MAX);
        runId = run.get("event_id").asString() + TAG;
        run.put("event_id", runId);
        ObjectNode summary = (ObjectNode) run.get("screening_run");
        summary.put("run_id", "2020-01-01T00:00:00.424242Z/1");
        summary.put("window_start", "2020-01-01T00:00:00.424242Z");
        summary.put("window_end", "2020-01-08T00:00:00Z");
        summary.put("input_fetched_at", "2020-01-01T00:00:00Z");
        summary.put("approach_count", MAX);
        summary.get("coverage").propertyNames().forEach(f -> ((ObjectNode) summary.get("coverage")).put(f, MAX));
        summary.get("omitted").propertyNames().forEach(f -> ((ObjectNode) summary.get("omitted")).put(f, MAX));
        stored(processor, run, 2);

        TestMysql.rootSql("INSERT INTO spaceflux.watchlist_object VALUES (" + WATCHLIST_OBJECT + ", 'TEST OBJECT', "
                + MAX + ")");
        TestMysql.rootSql("INSERT INTO spaceflux.catalog_object VALUES (" + WATCHLIST_OBJECT + ", 'TEST OBJECT', "
                + "false, '2026-001A', '2026-10-06 00:00:00', '2026-10-06T00:00:00', 15.0, 0.001, 51.6, 1, 2, 3, "
                + "0.0001, 0, 0, 0, 'U', 999, 1, '2026-10-06 00:00:00', 'https://celestrak.org/test', "
                + "'2026-10-06 00:00:00', '2026-10-06 00:00:00')");
        String fresh = LocalDateTime.ofInstant(Instant.now().minus(1, ChronoUnit.MINUTES), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        TestMysql.rootSql("INSERT INTO spaceflux.space_weather_series (scale, series_satellite, rules_version, state, "
                + "derived_level, derived_label, value, unit, time_tag, sample_time, interval_start, "
                + "freshness_reference, state_alert_seq, last_alert_seq) VALUES ('R', 18, " + MAX + ", 'none', NULL, "
                + "'none', 1.0e-7, 'W m-2', 'tag', '" + fresh + "', NULL, '" + fresh + "', 1, 1)");
    }

    @AfterAll
    static void stop() throws Exception {
        cleanUp();
        app.close();
    }

    static void cleanUp() throws Exception {
        String ours = "(SELECT alert_seq FROM (SELECT alert_seq FROM spaceflux.alert_event WHERE event_id LIKE '%"
                + TAG + "') t)";
        for (String child : new String[] {"screening_run_approach", "screening_run_suppressed",
            "screening_run_rejected", "screening_run_not_screened"}) {
            TestMysql.rootSql("DELETE FROM spaceflux." + child + " WHERE run_alert_seq IN " + ours);
        }
        TestMysql.rootSql("DELETE FROM spaceflux.screening_run WHERE alert_seq IN " + ours);
        TestMysql.rootSql("DELETE FROM spaceflux.close_approach WHERE alert_seq IN " + ours);
        TestMysql.rootSql("DELETE FROM spaceflux.alert_event WHERE event_id LIKE '%" + TAG + "'");
        TestMysql.rootSql("DELETE FROM spaceflux.watchlist_object WHERE catalog_number = " + WATCHLIST_OBJECT);
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = " + WATCHLIST_OBJECT);
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series");
    }

    static void stored(AlertsProcessor processor, ObjectNode event, long offset) throws Exception {
        AlertsProcessor.Outcome outcome = processor.process(
                new AlertsProcessor.In("k", JSON.writeValueAsBytes(event), 0, offset), Instant.now());
        String reason = outcome instanceof AlertsProcessor.Outcome.DeadLetter(var d)
                ? JSON.readTree(d.value()).get("reason").asString() : "";
        assertThat(outcome).as(reason).isInstanceOf(AlertsProcessor.Outcome.Stored.class);
    }

    static ObjectNode example(String file) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    static JsonNode data(String query, Map<String, Object> variables) throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        HttpResponse<String> r = b.postJson("/api/graphql", JSON.writeValueAsString(body));
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode json = JSON.readTree(r.body());
        assertThat(json.has("errors")).as(r.body()).isFalse();
        return json.get("data");
    }

    static void assertMax(JsonNode value, String what) {
        assertThat(value.isIntegralNumber()).as(what + " is a JSON integer: " + value).isTrue();
        assertThat(value.asLong()).as(what).isEqualTo(MAX);
    }

    @Test
    void oneAlertCarriesItsRulesVersion() throws Exception {
        JsonNode d = data("query($id: String!) { alert(event_id: $id) { rules_version } }",
                Map.of("id", closeApproachId));

        assertMax(d.get("alert").get("rules_version"), "alert.rules_version");
    }

    /** The contract allows a whole number written 1.0; it is stored as received and read back as the integer 1. */
    @Test
    void aRulesVersionWrittenWithADecimalPointIsReadAsAnInteger() throws Exception {
        JsonNode version = data("query($id: String!) { alert(event_id: $id) { rules_version } }",
                Map.of("id", decimalPointId)).get("alert").get("rules_version");

        assertThat(version.isIntegralNumber()).as(version.toString()).isTrue();
        assertThat(version.asLong()).isEqualTo(1);
    }

    @Test
    void theAlertsListCarriesItsRulesVersion() throws Exception {
        Map<String, Object> variables = new HashMap<>();
        JsonNode found = null;
        for (int page = 0; page < 100 && found == null; page++) {
            JsonNode alerts = data("query($after: String) { alerts(limit: 50, after: $after) { items { event_id "
                    + "rules_version } next } }", variables).get("alerts");
            for (JsonNode item : alerts.get("items")) {
                if (item.get("event_id").asString().equals(closeApproachId)) {
                    found = item;
                }
            }
            if (alerts.get("next").isNull()) {
                break;
            }
            variables.put("after", alerts.get("next").asString());
        }

        assertThat(found).as("the stored close approach is listed").isNotNull();
        assertMax(found.get("rules_version"), "alerts.items.rules_version");
    }

    @Test
    void aWatchlistObjectAndItsCloseApproachesCarryTheirRulesVersions() throws Exception {
        JsonNode watchlist = data("{ watchlist { catalog_number rules_version catalog { close_approaches(limit: 20) { "
                + "items { event_id rules_version } } } } }", null).get("watchlist");

        JsonNode object = null;
        for (JsonNode o : watchlist) {
            if (o.get("catalog_number").asInt() == WATCHLIST_OBJECT) {
                object = o;
            }
        }
        assertThat(object).isNotNull();
        assertMax(object.get("rules_version"), "watchlist.rules_version");
        JsonNode items = object.get("catalog").get("close_approaches").get("items");
        assertThat(items).anySatisfy(i -> {
            assertThat(i.get("event_id").asString()).isEqualTo(closeApproachId);
            assertMax(i.get("rules_version"), "close_approaches.items.rules_version");
        });
    }

    @Test
    void theCurrentSpaceWeatherCarriesItsRulesVersion() throws Exception {
        JsonNode scales = data("{ space_weather_current { scales { scale rules_version } } }", null)
                .get("space_weather_current").get("scales");

        assertThat(scales).anySatisfy(s -> {
            assertThat(s.get("scale").asString()).isEqualTo("R");
            assertMax(s.get("rules_version"), "space_weather_current.scales.rules_version");
        });
    }

    @Test
    void aScreeningRunCarriesItsCountsAndRulesVersion() throws Exception {
        JsonNode alert = data("query($id: String!) { alert(event_id: $id) { rules_version screening_run { "
                + "approach_count coverage { watchlist_accepted catalog_admitted pairs pairs_not_screenable "
                + "pairs_removed_by_prefilter pairs_searched } omitted { approach_event_ids suppressed rejected "
                + "not_screened epoch_after_start differing_copies differing_copies_over_cap } } } }",
                Map.of("id", runId)).get("alert");

        assertMax(alert.get("rules_version"), "rules_version");
        JsonNode run = alert.get("screening_run");
        assertMax(run.get("approach_count"), "approach_count");
        run.get("coverage").propertyNames().forEach(f -> assertMax(run.get("coverage").get(f), "coverage." + f));
        assertThat(run.get("omitted").propertyNames()).hasSize(7);
        run.get("omitted").propertyNames().forEach(f -> assertMax(run.get("omitted").get(f), "omitted." + f));
    }
}
