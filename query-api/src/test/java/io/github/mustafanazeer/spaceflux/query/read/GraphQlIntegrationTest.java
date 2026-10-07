package io.github.mustafanazeer.spaceflux.query.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import io.github.mustafanazeer.spaceflux.query.ack.AcknowledgementIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** POST /api/graphql (docs/api/graphql.md) against the migrated database, compared with the REST answers. */
class GraphQlIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static OperatorApp app;
    static String base;

    @BeforeAll
    static void start() {
        app = new OperatorApp();
        base = app.base;
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @BeforeEach
    void emptySeries() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series");
    }

    static Browser browser() throws Exception {
        Browser b = new Browser(base);
        b.get("/api/auth/session");
        return b;
    }

    static HttpResponse<String> query(Browser b, String query) throws Exception {
        return b.postJson("/api/graphql", JSON.writeValueAsString(Map.of("query", query)));
    }

    @Test
    void currentSpaceWeatherMatchesTheRestAnswerFieldForField() throws Exception {
        ReadApiIntegrationTest.base = base;
        ReadApiIntegrationTest.series("R", 18, "level", "R1", 1, 1.0624149581417441e-05,
                Instant.now().minus(5, ChronoUnit.MINUTES));
        Browser b = browser();

        HttpResponse<String> r = query(b, "{ space_weather_current { scales { scale satellite state derived_level "
                + "derived_label value unit time_tag sample_time freshness_reference no_data_reason age_limit_s "
                + "rules_version } } }");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.has("errors")).isFalse();
        JsonNode rest = JSON.readTree(b.get("/api/space-weather/current").body());
        for (String scale : new String[] {"G", "R", "S"}) {
            JsonNode g = ReadApiIntegrationTest.scale(body.get("data").get("space_weather_current"), scale);
            JsonNode expected = ReadApiIntegrationTest.scale(rest, scale);
            for (String field : ReadApiIntegrationTest.fields(expected)) {
                assertThat(g.get(field)).as(scale + " " + field).isEqualTo(expected.get(field));
            }
        }
    }

    @Test
    void aQueryWithoutTheXsrfHeaderIsRefused() throws Exception {
        Browser b = browser();
        b.sendXsrfHeader = false;

        HttpResponse<String> r = query(b, "{ watchlist { catalog_number } }");

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).doesNotContain("catalog_number");
    }

    static JsonNode data(HttpResponse<String> r) {
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.has("errors")).as(r.body()).isFalse();
        return body.get("data");
    }

    @Test
    void theWatchlistMatchesTheRestAnswer() throws Exception {
        Browser b = browser();

        JsonNode g = data(query(b, "{ watchlist { catalog_number name rules_version } }")).get("watchlist");

        JsonNode rest = JSON.readTree(b.get("/api/watchlist").body()).get("objects");
        assertThat(g).hasSize(rest.size());
        for (int i = 0; i < rest.size(); i++) {
            assertThat(g.get(i).get("catalog_number")).isEqualTo(rest.get(i).get("catalog_number"));
            assertThat(g.get(i).get("name")).isEqualTo(rest.get(i).get("name"));
            assertThat(g.get(i).get("rules_version")).isEqualTo(rest.get(i).get("rules_version"));
        }
    }

    @Test
    void aCatalogObjectNotInTheCatalogIsNullWithNoError() throws Exception {
        JsonNode g = data(query(browser(), "{ catalog_object(norad_cat_id: 999999998) { norad_cat_id } }"));

        assertThat(g.get("catalog_object").isNull()).isTrue();
    }

    @Test
    void anOutOfRangeCatalogNumberIsAnErrorWithTheApisOwnText() throws Exception {
        HttpResponse<String> r = query(browser(), "{ catalog_object(norad_cat_id: -1) { norad_cat_id } }");

        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("errors")).hasSize(1);
        assertThat(body.get("errors").get(0).get("message").asString())
                .isEqualTo("A catalog number is a whole number from 0 to 999999999.");
        assertThat(body.get("errors").get(0).get("extensions").get("classification").asString())
                .isEqualTo("BAD_REQUEST");
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("at io.");
    }

    @Test
    void anAlertIsTypedFromItsStoredEvent() throws Exception {
        String id = AcknowledgementIntegrationTest.stored("valid-close-approach.json");

        JsonNode a = data(query(browser(), "{ alert(event_id: " + JSON.writeValueAsString(id) + ") { kind event_id "
                + "received_at space_weather_level { scale } close_approach { miss_distance_m watchlist_object { "
                + "catalog_number name } other_object { catalog_number } time_of_closest_approach } acknowledgement "
                + "{ action } } }")).get("alert");

        assertThat(a.get("kind").asString()).isEqualTo("close_approach");
        assertThat(a.get("event_id").asString()).isEqualTo(id);
        assertThat(a.get("received_at").asString()).endsWith("Z");
        assertThat(a.get("space_weather_level").isNull()).isTrue();
        assertThat(a.get("close_approach").get("miss_distance_m").asDouble()).isEqualTo(1973.3);
        assertThat(a.get("close_approach").get("watchlist_object").get("catalog_number").asInt()).isEqualTo(57036);
        assertThat(a.get("close_approach").get("watchlist_object").get("name").asString()).isEqualTo("OBJECT AJ");
        assertThat(a.get("close_approach").get("other_object").get("catalog_number").asInt()).isEqualTo(27958);
        assertThat(a.get("close_approach").get("time_of_closest_approach").asString())
                .isEqualTo("2026-09-30T03:34:37.588Z");
        assertThat(a.get("acknowledgement").isNull()).isTrue();
    }

    @Test
    void anAlertThatIsNotStoredIsNull() throws Exception {
        JsonNode g = data(query(browser(), "{ alert(event_id: \"no/such/event\") { event_id } }"));

        assertThat(g.get("alert").isNull()).isTrue();
    }

    @Test
    void theNoteAndPrincipalReachOnlyTheSignedInOperator() throws Exception {
        String id = AcknowledgementIntegrationTest.stored("valid-close-approach.json");
        Browser operator = app.signedIn();
        assertThat(AcknowledgementIntegrationTest.post(operator, id,
                "{\"action\": \"acknowledge\", \"note\": \"seen\"}").statusCode()).isEqualTo(201);
        String q = "{ alert(event_id: " + JSON.writeValueAsString(id) + ") { acknowledgement { action acted_at "
                + "principal note } } }";

        JsonNode asOperator = data(query(operator, q)).get("alert").get("acknowledgement");
        JsonNode anonymous = data(query(browser(), q)).get("alert").get("acknowledgement");

        assertThat(asOperator.get("action").asString()).isEqualTo("acknowledge");
        assertThat(asOperator.get("principal").asString()).isEqualTo(OperatorApp.OPERATOR);
        assertThat(asOperator.get("note").asString()).isEqualTo("seen");
        assertThat(anonymous.get("action").asString()).isEqualTo("acknowledge");
        assertThat(anonymous.get("acted_at").asString()).isEqualTo(asOperator.get("acted_at").asString());
        assertThat(anonymous.get("principal").isNull()).isTrue();
        assertThat(anonymous.get("note").isNull()).isTrue();
    }

    @Test
    void historyMatchesTheRestAnswerAndRefusesWhatRestRefuses() throws Exception {
        Browser b = browser();
        String range = "scale: \"G\", from: \"2024-05-10T00:00:00Z\", to: \"2024-05-11T00:00:00Z\"";

        JsonNode g = data(query(b, "{ space_weather_history(" + range + ", limit: 5) { scale items { event_id "
                + "state } next } }")).get("space_weather_history");
        JsonNode rest = JSON.readTree(b.get("/api/space-weather/history?scale=G&from=2024-05-10T00:00:00Z"
                + "&to=2024-05-11T00:00:00Z&limit=5").body());
        JsonNode refused = JSON.readTree(query(b, "{ space_weather_history(" + range + ", limit: 201) { scale } }")
                .body());

        assertThat(g.get("scale").asString()).isEqualTo("G");
        assertThat(g.get("items").size()).isEqualTo(rest.get("items").size());
        assertThat(refused.get("errors").get(0).get("extensions").get("classification").asString())
                .isEqualTo("BAD_REQUEST");
    }

    @Test
    void withNoCompleteRunTheCurrentScreeningRunIsNullOtherwiseItIsTyped() throws Exception {
        Browser b = browser();
        HttpResponse<String> rest = b.get("/api/screening/current");

        JsonNode g = data(query(b, "{ screening_current { stale summary { run_id coverage { pairs } suppressed { "
                + "watchlist_number } } approaches { event_id close_approach { miss_distance_m } } } }"))
                .get("screening_current");

        if (rest.statusCode() == 404) {
            assertThat(g.isNull()).isTrue();
        } else {
            JsonNode r = JSON.readTree(rest.body());
            assertThat(g.get("stale")).isEqualTo(r.get("stale"));
            assertThat(g.get("summary").get("run_id")).isEqualTo(r.get("summary").get("run_id"));
            assertThat(g.get("summary").get("coverage").get("pairs"))
                    .isEqualTo(r.get("summary").get("coverage").get("pairs"));
            assertThat(g.get("approaches").size()).isEqualTo(r.get("approaches").size());
        }
    }

    /** Stores a close approach event with its close_approach row; returns the event_id. */
    static String approach(int watchlist, int other, String tca) throws Exception {
        String id = "close_approach/1/test/" + watchlist + "/" + other + "/" + tca + "/" + java.util.UUID.randomUUID();
        String payload = "{\"schema_version\": 1, \"kind\": \"close_approach\", \"rules_version\": 1, "
                + "\"event_id\": \"" + id + "\", \"produced_at\": \"2026-10-06T00:00:00Z\", "
                + "\"close_approach\": {\"run_id\": \"r/1\", \"window_start\": \"2026-10-06T00:00:00Z\", "
                + "\"window_end\": \"2026-10-13T00:00:00Z\", \"watchlist_object\": {\"catalog_number\": "
                + watchlist + ", \"element_age_days\": 1.0}, \"other_object\": {\"catalog_number\": " + other
                + ", \"element_age_days\": 2.0}, \"time_of_closest_approach\": \"" + tca + "Z\", "
                + "\"miss_distance_m\": 1200.5, \"relative_speed_m_per_s\": 9000}}";
        TestMysql.rootSql("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, rules_version, "
                + "produced_at, source_partition, source_offset, payload) VALUES ('" + id + "', 'close_approach', 1, 1, "
                + "'2026-10-06 00:00:00', 0, 0, '" + payload + "'); INSERT INTO spaceflux.close_approach (alert_seq, "
                + "rules_version, run_id, window_start, window_end, watchlist_number, watchlist_element_age_days, "
                + "other_number, other_element_age_days, time_of_closest_approach, miss_distance_m, "
                + "relative_speed_m_per_s) VALUES (LAST_INSERT_ID(), 1, 'r/1', '2026-10-06 00:00:00', "
                + "'2026-10-13 00:00:00', " + watchlist + ", 1.0, " + other + ", 2.0, '" + tca.replace("T", " ")
                + "', 1200.5, 9000)");
        return id;
    }

    /** Stores an R space weather event with a real payload and its space_weather_event row; returns the event_id. */
    static String level(String state, String trigger) throws Exception {
        String id = "space_weather_level/1/R/18/test/" + java.util.UUID.randomUUID();
        String label = "level".equals(state) ? "R1" : "none";
        String payload = "{\"schema_version\": 1, \"kind\": \"space_weather_level\", \"rules_version\": 1, "
                + "\"event_id\": \"" + id + "\", \"produced_at\": \"2026-10-06T00:00:00Z\", \"space_weather_level\": "
                + "{\"scale\": \"R\", \"product\": \"swpc.goes.xrays\", \"state\": \"" + state + "\", "
                + "\"derived_label\": \"" + label + "\", \"trigger\": \"" + trigger + "\", \"derived_from\": \"test\", "
                + "\"estimated\": false, \"unit\": \"W m-2\"}}";
        TestMysql.rootSql("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, rules_version, "
                + "produced_at, source_partition, source_offset, payload) VALUES ('" + id + "', 'space_weather_level', "
                + "1, 1, '2026-10-06 00:00:00', 0, 0, '" + payload + "'); INSERT INTO spaceflux.space_weather_event "
                + "(alert_seq, rules_version, scale, satellite, product, state, derived_level, derived_label, "
                + "trigger_kind, derived_from, estimated, unit, value, time_tag, sample_time) VALUES (LAST_INSERT_ID(), "
                + "1, 'R', 18, 'swpc.goes.xrays', '" + state + "', " + ("level".equals(state) ? "1" : "NULL") + ", '"
                + label + "', '" + trigger + "', 'test', false, 'W m-2', 1.1e-5, '2026-10-06T00:00:00Z', "
                + "'2026-10-06 00:00:00')");
        return id;
    }

    static void testObject() throws Exception {
        testObject(90010);
    }

    /** Each test that reads an object's approaches uses its own catalog number, so test order cannot matter. */
    static void testObject(int number) throws Exception {
        TestMysql.rootSql("INSERT IGNORE INTO spaceflux.catalog_object VALUES (" + number + ", 'TEST OBJECT', false, "
                + "'2026-001A', '2026-10-06 00:00:00', '2026-10-06T00:00:00', 15.0, 0.001, 51.6, 1, 2, 3, 0.0001, "
                + "0, 0, 0, 'U', 999, 1, '2026-10-06 00:00:00', 'https://celestrak.org/test', "
                + "'2026-10-06 00:00:00', '2026-10-06 00:00:00')");
    }

    static java.util.List<String> ids(JsonNode page) {
        java.util.List<String> out = new java.util.ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("event_id").asString()));
        return out;
    }

    @Test
    void recentAlertsAreApproachesAndLevelChangesNewestFirstWithoutRefreshes() throws Exception {
        String first = approach(25544, 90001, "2026-10-07T01:00:00");
        level("level", "refresh");
        String level = level("level", "level_change");
        level("none", "level_change");
        String last = approach(25544, 90002, "2026-10-07T02:00:00");

        JsonNode page = data(query(browser(), "{ alerts(limit: 3) { items { event_id kind } next } }")).get("alerts");

        assertThat(ids(page)).containsExactly(last, level, first);
        assertThat(page.get("next").isNull()).isFalse();
    }

    @Test
    void theNextCursorContinuesWhereThePageStopped() throws Exception {
        String a = approach(25544, 90003, "2026-10-07T03:00:00");
        String b = approach(25544, 90004, "2026-10-07T04:00:00");
        String c = approach(25544, 90005, "2026-10-07T05:00:00");
        Browser browser = browser();

        JsonNode one = data(query(browser, "{ alerts(limit: 2) { items { event_id } next } }")).get("alerts");
        JsonNode two = data(query(browser, "{ alerts(limit: 1, after: " + JSON.writeValueAsString(
                one.get("next").asString()) + ") { items { event_id } next } }")).get("alerts");

        assertThat(ids(one)).containsExactly(c, b);
        assertThat(ids(two)).containsExactly(a);
    }

    @Test
    void anAlertInTheListIsTypedLikeOneReadById() throws Exception {
        String id = approach(25544, 90006, "2026-10-07T06:00:00");

        JsonNode item = data(query(browser(), "{ alerts(limit: 1) { items { event_id received_at close_approach { "
                + "miss_distance_m other_object { catalog_number } } acknowledgement { action } } } }"))
                .get("alerts").get("items").get(0);

        assertThat(item.get("event_id").asString()).isEqualTo(id);
        assertThat(item.get("received_at").asString()).endsWith("Z");
        assertThat(item.get("close_approach").get("miss_distance_m").asDouble()).isEqualTo(1200.5);
        assertThat(item.get("close_approach").get("other_object").get("catalog_number").asInt()).isEqualTo(90006);
        assertThat(item.get("acknowledgement").isNull()).isTrue();
    }

    @Test
    void anObjectsCloseApproachesComeFromEitherRoleNewestFirst() throws Exception {
        testObject();
        String asOther = approach(25544, 90010, "2026-10-08T01:00:00");
        String asWatchlist = approach(90010, 90011, "2026-10-08T03:00:00");
        approach(25544, 90012, "2026-10-08T02:00:00");

        JsonNode page = data(query(browser(), "{ catalog_object(norad_cat_id: 90010) { close_approaches { items { "
                + "event_id } next } } }")).get("catalog_object").get("close_approaches");

        assertThat(ids(page)).containsExactly(asWatchlist, asOther);
        assertThat(page.get("next").isNull()).isTrue();
    }

    @Test
    void aLimitOutOfRangeIsAnErrorNeverCut() throws Exception {
        testObject();
        Browser b = browser();

        for (String q : new String[] {"{ alerts(limit: 201) { next } }", "{ alerts(limit: 0) { next } }",
            "{ catalog_object(norad_cat_id: 90010) { close_approaches(limit: 51) { next } } }",
            "{ alerts(after: \"not-a-cursor\") { next } }"}) {
            JsonNode body = JSON.readTree(query(b, q).body());
            assertThat(body.get("errors").get(0).get("extensions").get("classification").asString()).as(q)
                    .isEqualTo("BAD_REQUEST");
        }
    }

    @Test
    void theListsShowTheNoteAndPrincipalOnlyToTheSignedInOperator() throws Exception {
        testObject(90020);
        String id = approach(90020, 90013, "2026-10-09T01:00:00");
        Browser operator = app.signedIn();
        assertThat(AcknowledgementIntegrationTest.post(operator, id,
                "{\"action\": \"acknowledge\", \"note\": \"checked\"}").statusCode()).isEqualTo(201);
        String ack = "acknowledgement { action principal note }";
        String recent = "{ alerts(limit: 1) { items { event_id " + ack + " } } }";
        String byObject = "{ catalog_object(norad_cat_id: 90020) { close_approaches(limit: 1) { items { event_id "
                + ack + " } } } }";

        for (Browser b : new Browser[] {browser(), operator}) {
            JsonNode fromRecent = data(query(b, recent)).get("alerts").get("items").get(0);
            JsonNode fromObject = data(query(b, byObject)).get("catalog_object").get("close_approaches")
                    .get("items").get(0);
            boolean signedIn = b == operator;
            for (JsonNode item : new JsonNode[] {fromRecent, fromObject}) {
                assertThat(item.get("event_id").asString()).isEqualTo(id);
                JsonNode a = item.get("acknowledgement");
                assertThat(a.get("action").asString()).isEqualTo("acknowledge");
                if (signedIn) {
                    assertThat(a.get("principal").asString()).isEqualTo(OperatorApp.OPERATOR);
                    assertThat(a.get("note").asString()).isEqualTo("checked");
                } else {
                    assertThat(a.get("principal").isNull()).isTrue();
                    assertThat(a.get("note").isNull()).isTrue();
                }
            }
        }
    }

    static final String CATALOG = "norad_cat_id object_name object_name_cut object_id epoch epoch_text mean_motion "
            + "eccentricity inclination ra_of_asc_node arg_of_pericenter mean_anomaly bstar mean_motion_dot "
            + "mean_motion_ddot ephemeris_type classification_type element_set_no rev_at_epoch fetched_at source_url "
            + "first_fetched_at last_fetched_at";
    static final String SCREENED = "{ catalog_number name element_age_days }";
    static final String CLOSE_APPROACH = "{ run_id window_start window_end watchlist_object " + SCREENED
            + " other_object " + SCREENED + " time_of_closest_approach miss_distance_m relative_speed_m_per_s }";
    static final String SUMMARY = "{ run_id window_start window_end input_fetched_at report_distance_m coverage { "
            + "watchlist_accepted catalog_admitted pairs pairs_not_screenable pairs_removed_by_prefilter "
            + "pairs_searched } approach_count approach_event_ids suppressed { watchlist_number watchlist_name "
            + "other_number other_name mechanism detail min_separation_m min_separation_at max_separation_m "
            + "stack_entry_may_be_stale stack_name } rejected { catalog_number name role code reason } not_screened { "
            + "catalog_number name role kind screened_until reason } epoch_after_start { catalog_number name "
            + "seconds_after_start } differing_copies { catalog_number used_name used_epoch dropped_name "
            + "dropped_epoch dropped_from elements_differ } differing_copies_over_cap { catalog_number epoch "
            + "records_not_listed } omitted { approach_event_ids suppressed rejected not_screened epoch_after_start "
            + "differing_copies differing_copies_over_cap } }";
    static final String LEVEL = "{ scale product state derived_level derived_label previous_state "
            + "previous_derived_level trigger derived_from estimated satellite band channel value unit xray_class "
            + "time_tag interval_start interval_end sample_time averaging_period_s fetched_at source_url "
            + "freshness_reference no_data_since no_data_reason restated_by_time_tag timer_refresh_at "
            + "ended_by_satellite }";
    static final String ACK = "{ action acted_at principal note }";

    /**
     * Every field REST returns has the same value in the GraphQL answer; numbers are compared by value, since REST
     * keeps a stored number's spelling and GraphQL writes it anew.
     */
    static void assertSameAs(JsonNode rest, JsonNode graphql, String path) {
        assertThat(graphql).as(path).isNotNull();
        if (rest.isObject()) {
            for (String field : ReadApiIntegrationTest.fields(rest)) {
                assertSameAs(rest.get(field), graphql.get(field), path + "." + field);
            }
        } else if (rest.isArray()) {
            assertThat(graphql.size()).as(path).isEqualTo(rest.size());
            for (int i = 0; i < rest.size(); i++) {
                assertSameAs(rest.get(i), graphql.get(i), path + "[" + i + "]");
            }
        } else if (rest.isNumber()) {
            assertThat(graphql.asDouble()).as(path).isEqualTo(rest.asDouble());
        } else {
            assertThat(graphql).as(path).isEqualTo(rest);
        }
    }

    @Test
    void aCatalogObjectMatchesTheRestAnswerInEveryField() throws Exception {
        testObject(90030);
        Browser b = browser();

        JsonNode g = data(query(b, "{ catalog_object(norad_cat_id: 90030) { " + CATALOG + " } }"))
                .get("catalog_object");

        assertSameAs(JSON.readTree(b.get("/api/catalog/90030").body()), g, "catalog_object");
    }

    @Test
    void theWatchlistWithItsCatalogRowsMatchesTheRestAnswerInEveryField() throws Exception {
        testObject(25544);
        Browser b = browser();

        JsonNode g = data(query(b, "{ watchlist { catalog_number name rules_version catalog { " + CATALOG
                + " } } }")).get("watchlist");

        assertSameAs(JSON.readTree(b.get("/api/watchlist").body()).get("objects"), g, "watchlist");
    }

    @Test
    void historyItemsMatchTheRestAnswerInEveryField() throws Exception {
        level("level", "level_change");
        level("none", "level_change");
        Browser b = browser();
        String range = "scale: \"R\", satellite: 18, from: \"2026-10-05T00:00:00Z\", to: \"2026-10-07T00:00:00Z\"";

        // All 14 item fields at 100 items cost 100 x (1 + (1 + 14)) = 1,600; a page of 200 would exceed the cost limit.
        JsonNode g = data(query(b, "{ space_weather_history(" + range + ", limit: 100) { scale satellite items { "
                + "interval_start interval_end sample_time time_tag state derived_level derived_label value unit "
                + "xray_class no_data_reason no_data_since trigger event_id } next } }")).get("space_weather_history");
        JsonNode rest = JSON.readTree(b.get("/api/space-weather/history?scale=R&satellite=18"
                + "&from=2026-10-05T00:00:00Z&to=2026-10-07T00:00:00Z&limit=100").body());

        assertThat(rest.get("items").size()).isPositive();
        assertSameAs(rest, g, "history");
    }

    @Test
    void theCurrentScreeningRunMatchesTheRestAnswerInEveryField() throws Exception {
        ReadApiIntegrationTest.base = base;
        ReadApiIntegrationTest.emptyScreening();
        Instant ws = Instant.now().minus(1, ChronoUnit.HOURS);
        String first = ReadApiIntegrationTest.approach(ReadApiIntegrationTest.runIdFor(ws, 1));
        String second = ReadApiIntegrationTest.approach(ReadApiIntegrationTest.runIdFor(ws, 1));
        ReadApiIntegrationTest.run(ws, 1, ws.plus(7, ChronoUnit.DAYS), 0, 2, java.util.List.of(second, first));
        TestMysql.rootSql("INSERT INTO spaceflux.alert_acknowledgement (event_id, action, principal, note) VALUES ('"
                + first + "', 'acknowledge', 'operator', 'seen it')");
        Browser operator = app.signedIn();

        for (Browser b : new Browser[] {browser(), operator}) {
            JsonNode g = data(query(b, "{ screening_current { stale summary " + SUMMARY + " approaches { event_id "
                    + "close_approach " + CLOSE_APPROACH + " acknowledgement " + ACK + " } } }"))
                    .get("screening_current");
            assertSameAs(JSON.readTree(b.get("/api/screening/current").body()), g, "screening_current");
            JsonNode ack = g.get("approaches").get(1).get("acknowledgement");
            assertThat(ack.get("action").asString()).isEqualTo("acknowledge");
            assertThat(ack.get("principal").isNull()).isEqualTo(b != operator);
            assertThat(ack.get("note").isNull()).isEqualTo(b != operator);
        }
    }

    @Test
    void anAlertMatchesTheRestAnswerInEveryField() throws Exception {
        Browser b = browser();
        for (String id : new String[] {AcknowledgementIntegrationTest.stored("valid-close-approach.json"),
            AcknowledgementIntegrationTest.stored("valid-r-level.json"),
            AcknowledgementIntegrationTest.stored("valid-screening-run-cut.json"),
            AcknowledgementIntegrationTest.stored("valid-screening-run-stack.json")}) {
            JsonNode g = data(query(b, "{ alert(event_id: " + JSON.writeValueAsString(id) + ") { schema_version kind "
                    + "rules_version event_id produced_at received_at space_weather_level " + LEVEL
                    + " close_approach " + CLOSE_APPROACH + " screening_run " + SUMMARY + " acknowledgement " + ACK
                    + " } }")).get("alert");
            JsonNode rest = JSON.readTree(b.get("/api/alerts/by-id?event_id="
                    + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8)).body());

            assertSameAs(rest.get("event"), g, id);
            assertThat(g.get("received_at")).isEqualTo(rest.get("received_at"));
        }
    }

    @Test
    void closeApproachesPageThroughTiedTimesInBothRoles() throws Exception {
        testObject(90040);
        String a = approach(90040, 91001, "2026-10-10T01:00:00");
        String b2 = approach(91002, 90040, "2026-10-10T01:00:00");
        String c = approach(90040, 91003, "2026-10-10T01:00:00");
        Browser b = browser();
        java.util.List<String> seen = new java.util.ArrayList<>();
        String after = null;

        for (int page = 0; page < 4; page++) {
            JsonNode p = data(query(b, "{ catalog_object(norad_cat_id: 90040) { close_approaches(limit: 1"
                    + (after == null ? "" : ", after: " + JSON.writeValueAsString(after)) + ") { items { event_id } "
                    + "next } } }")).get("catalog_object").get("close_approaches");
            seen.addAll(ids(p));
            if (p.get("next").isNull()) {
                break;
            }
            after = p.get("next").asString();
        }

        assertThat(seen).containsExactly(c, b2, a);
    }

    static String cursor(String text) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void anObjectCursorWithATimeTheDatabaseCannotHoldIsRefusedAsBadRequest() throws Exception {
        testObject(90040);
        Browser b = browser();

        for (String text : new String[] {"+999999999-12-31T23:59:59|5", "-0001-01-01T00:00|5", "0000-01-01T00:00|5",
            "0999-12-31T23:59:59|5", "10000-01-01T00:00|5"}) {
            JsonNode body = JSON.readTree(query(b, "{ catalog_object(norad_cat_id: 90040) { close_approaches(after: "
                    + JSON.writeValueAsString(cursor(text)) + ") { next } } }").body());
            assertThat(body.get("errors").get(0).get("extensions").get("classification").asString()).as(text)
                    .isEqualTo("BAD_REQUEST");
            assertThat(body.get("errors").get(0).get("message").asString()).as(text)
                    .isEqualTo("after is not a cursor this API returned.");
        }
    }

    @Test
    void aStationStackPairCarriesItsStackNameAndAnOlderSummaryReadsNull() throws Exception {
        String withName = AcknowledgementIntegrationTest.stored("valid-screening-run-stack.json");
        String older = AcknowledgementIntegrationTest.stored("valid-screening-run-stack.json");
        // The same run as an event written before the field existed.
        TestMysql.rootSql("UPDATE spaceflux.alert_event SET payload = REGEXP_REPLACE(payload, "
                + "'\"stack_name\": \"International Space Station\",[[:space:]]*', '') WHERE event_id = '" + older
                + "'");
        assertThat(TestMysql.rootQuery("SELECT payload FROM spaceflux.alert_event WHERE event_id = '" + older + "'"))
                .doesNotContain("stack_name");
        Browser b = browser();
        String q = "{ alert(event_id: %s) { screening_run { suppressed { mechanism stack_name } } } }";

        JsonNode named = data(query(b, String.format(q, JSON.writeValueAsString(withName)))).get("alert")
                .get("screening_run").get("suppressed");
        JsonNode unnamed = data(query(b, String.format(q, JSON.writeValueAsString(older)))).get("alert")
                .get("screening_run").get("suppressed");

        assertThat(named).isNotEmpty().allSatisfy(s ->
                assertThat(s.get("stack_name").asString()).isEqualTo("International Space Station"));
        assertThat(unnamed).isNotEmpty().allSatisfy(s -> assertThat(s.get("stack_name").isNull()).isTrue());
    }
}
