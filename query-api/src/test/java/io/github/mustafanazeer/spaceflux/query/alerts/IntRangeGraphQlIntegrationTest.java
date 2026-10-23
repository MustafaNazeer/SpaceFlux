package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * records_not_listed and rev_at_epoch stay GraphQL Int although their contracts set no maximum. A stored value its
 * GraphQL type cannot hold gives one generic INTERNAL_ERROR at that field's path, nulls the nearest field that may be
 * null, and leaves the rest of the response answering; the detail goes only to the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class IntRangeGraphQlIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long OVER_INT = 2_147_483_648L;
    static final BigInteger OVER_LONG = new BigInteger("9223372036854775808");
    static final long OVER_UINT32 = 4_294_967_296L;
    static final String TAG = "/int-range-graphql";
    /** A catalog number no other test uses. */
    static final int OBJECT = 90058;

    static OperatorApp app;
    static String overIntRun;
    static String overLongRun;
    static String currentRunId;
    static String twoOverIntRun;

    @BeforeAll
    static void start() throws Exception {
        app = new OperatorApp();
        cleanUp();
        AlertsProcessor processor = new AlertsProcessor(TopicSchemas.fromClasspath(),
                app.context.getBean(AlertStore.class));

        ObjectNode overInt = run("valid-screening-run-cut.json", "2020-01-02T00:00:00.515151Z", "2020-01-09T00:00:00Z");
        ((ObjectNode) overInt.get("screening_run").get("differing_copies_over_cap").get(0))
                .put("records_not_listed", OVER_INT);
        overIntRun = overInt.get("event_id").asString();
        UnsignedIntGraphQlIntegrationTest.stored(processor, overInt, 4);

        ObjectNode overLong = run("valid-screening-run-cut.json", "2020-01-03T00:00:00.616161Z",
                "2020-01-10T00:00:00Z");
        ((ObjectNode) overLong.get("screening_run").get("differing_copies_over_cap").get(0))
                .put("records_not_listed", OVER_LONG);
        overLongRun = overLong.get("event_id").asString();
        UnsignedIntGraphQlIntegrationTest.stored(processor, overLong, 5);
        TestMysql.rootSql("UPDATE spaceflux.alert_event SET payload = REPLACE(payload, '\"approach_count\":1,', "
                + "'\"approach_count\":" + OVER_UINT32 + ",') WHERE event_id = '" + overLongRun + "'");

        ObjectNode twoOverInt = run("valid-screening-run-cut.json", "2020-01-04T00:00:00.818181Z",
                "2020-01-11T00:00:00Z");
        ArrayNode entries = (ArrayNode) twoOverInt.get("screening_run").get("differing_copies_over_cap");
        ((ObjectNode) entries.get(0)).put("records_not_listed", OVER_INT);
        ObjectNode second = entries.addObject();
        second.put("catalog_number", OBJECT + 1);
        second.put("epoch", "2020-01-04T00:00:00Z");
        second.put("records_not_listed", OVER_INT);
        twoOverIntRun = twoOverInt.get("event_id").asString();
        UnsignedIntGraphQlIntegrationTest.stored(processor, twoOverInt, 7);

        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
        currentRunId = start.toString().replace("Z", ".717171Z/1");
        ObjectNode current = run("valid-screening-run.json", start.toString().replace("Z", ".717171Z"),
                start.plus(7, ChronoUnit.DAYS).toString());
        current.put("produced_at", start.toString());
        ObjectNode summary = (ObjectNode) current.get("screening_run");
        summary.put("approach_count", 0);
        summary.putArray("approach_event_ids");
        ((ObjectNode) summary.get("differing_copies_over_cap").get(0)).put("records_not_listed", OVER_INT);
        UnsignedIntGraphQlIntegrationTest.stored(processor, current, 6);

        TestMysql.rootSql("INSERT INTO spaceflux.catalog_object VALUES (" + OBJECT + ", 'TEST OBJECT', "
                + "false, '2026-001A', '2026-10-06 00:00:00', '2026-10-06T00:00:00', 15.0, 0.001, 51.6, 1, 2, 3, "
                + "0.0001, 0, 0, 0, 'U', 999, " + OVER_INT + ", '2026-10-06 00:00:00', 'https://celestrak.org/test', "
                + "'2026-10-06 00:00:00', '2026-10-06 00:00:00')");
    }

    /** A screening run under its own run_id with one entry over the copy cap. */
    static ObjectNode run(String example, String windowStart, String windowEnd) throws Exception {
        ObjectNode run = UnsignedIntGraphQlIntegrationTest.example(example);
        run.put("event_id", "screening_run/1/" + windowStart + "/1" + TAG);
        ObjectNode summary = (ObjectNode) run.get("screening_run");
        summary.put("run_id", windowStart + "/1");
        summary.put("window_start", windowStart);
        summary.put("window_end", windowEnd);
        summary.put("input_fetched_at", windowStart.substring(0, 19) + "Z");
        ObjectNode over = summary.putArray("differing_copies_over_cap").addObject();
        over.put("catalog_number", OBJECT);
        over.put("epoch", windowStart.substring(0, 10) + "T00:00:00Z");
        over.put("records_not_listed", 1);
        return run;
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
        TestMysql.rootSql("DELETE FROM spaceflux.alert_event WHERE event_id LIKE '%" + TAG + "'");
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = " + OBJECT);
    }

    static JsonNode response(String query, Map<String, Object> variables) throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        HttpResponse<String> r = b.postJson("/api/graphql", JSON.writeValueAsString(body));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).doesNotContain("serialize").doesNotContain("Long").doesNotContain("BigInteger")
                .doesNotContain("'Int'").doesNotContain("UInt32").doesNotContain("Exception")
                .doesNotContain(String.valueOf(OVER_INT)).doesNotContain(OVER_LONG.toString())
                .doesNotContain(String.valueOf(OVER_UINT32));
        return JSON.readTree(r.body());
    }

    static void assertOneInternalError(JsonNode response, Object... path) {
        assertThat(response.get("errors")).as(response.toString()).singleElement().satisfies(e -> {
            assertThat(e.get("extensions").get("classification").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(e.get("message").asString()).startsWith("INTERNAL_ERROR for ");
            assertThat(e.get("path").toString()).isEqualTo(JSON.writeValueAsString(path));
        });
    }

    @Test
    void aRevAtEpochOverIntIsNullWithAnInternalErrorAndTheObjectStillAnswers(CapturedOutput output) throws Exception {
        JsonNode r = response("query($n: Int!) { catalog_object(norad_cat_id: $n) { norad_cat_id object_name "
                + "element_set_no rev_at_epoch } }", Map.of("n", OBJECT));

        JsonNode object = r.get("data").get("catalog_object");
        assertThat(object.get("norad_cat_id").asInt()).isEqualTo(OBJECT);
        assertThat(object.get("object_name").asString()).isEqualTo("TEST OBJECT");
        assertThat(object.get("element_set_no").asInt()).isEqualTo(999);
        assertThat(object.has("rev_at_epoch")).isTrue();
        assertThat(object.get("rev_at_epoch").isNull()).isTrue();
        assertOneInternalError(r, "catalog_object", "rev_at_epoch");
        assertThat(output.getAll()).contains("Unserializable value for executionId")
                .contains("(/catalog_object/rev_at_epoch)").doesNotContain(String.valueOf(OVER_INT));
    }

    /** records_not_listed and its list items are non null, so the error nulls the list, which may be null. */
    @Test
    void aRecordsNotListedOverIntNullsTheOverCapListAndTheRunStillAnswers() throws Exception {
        JsonNode r = response("query($id: String!) { alert(event_id: $id) { event_id screening_run { run_id "
                + "approach_count differing_copies_over_cap { catalog_number epoch records_not_listed } } } }",
                Map.of("id", overIntRun));

        JsonNode alert = r.get("data").get("alert");
        assertThat(alert.get("event_id").asString()).isEqualTo(overIntRun);
        JsonNode run = alert.get("screening_run");
        assertThat(run.get("run_id").asString()).isEqualTo("2020-01-02T00:00:00.515151Z/1");
        assertThat(run.get("approach_count").asInt()).isEqualTo(1);
        assertThat(run.has("differing_copies_over_cap")).isTrue();
        assertThat(run.get("differing_copies_over_cap").isNull()).isTrue();
        assertOneInternalError(r, "alert", "screening_run", "differing_copies_over_cap", 0, "records_not_listed");
    }

    @Test
    void twoEntriesOverIntEachGetTheirOwnErrorAndTheListIsNulledOnce() throws Exception {
        JsonNode r = response("query($id: String!) { alert(event_id: $id) { event_id screening_run { run_id "
                + "differing_copies_over_cap { catalog_number records_not_listed } } } }",
                Map.of("id", twoOverIntRun));

        JsonNode run = r.get("data").get("alert").get("screening_run");
        assertThat(run.get("run_id").asString()).isEqualTo("2020-01-04T00:00:00.818181Z/1");
        assertThat(run.get("differing_copies_over_cap").isNull()).isTrue();
        assertThat(r.get("errors")).as(r.toString()).hasSize(2).allSatisfy(e -> {
            assertThat(e.get("extensions").get("classification").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(e.get("message").asString()).startsWith("INTERNAL_ERROR for ");
        });
        assertThat(r.get("errors")).extracting(e -> e.get("path").toString()).containsExactlyInAnyOrder(
                "[\"alert\",\"screening_run\",\"differing_copies_over_cap\",0,\"records_not_listed\"]",
                "[\"alert\",\"screening_run\",\"differing_copies_over_cap\",1,\"records_not_listed\"]");
    }

    @Test
    void aRecordsNotListedOverLongIsAnsweredTheSameWay() throws Exception {
        JsonNode r = response("query($id: String!) { alert(event_id: $id) { event_id screening_run { run_id "
                + "differing_copies_over_cap { catalog_number records_not_listed } } } }",
                Map.of("id", overLongRun));

        JsonNode run = r.get("data").get("alert").get("screening_run");
        assertThat(run.get("run_id").asString()).isEqualTo("2020-01-03T00:00:00.616161Z/1");
        assertThat(run.get("differing_copies_over_cap").isNull()).isTrue();
        assertOneInternalError(r, "alert", "screening_run", "differing_copies_over_cap", 0, "records_not_listed");
    }

    /**
     * The store refuses a count over 4294967295, so the stored payload is changed by hand to stand for one; UInt32 is
     * non null and ScreeningRun may be null, so the run is nulled and the alert still answers.
     */
    @Test
    void aUInt32OverItsRangeIsAnsweredTheSameWay() throws Exception {
        JsonNode r = response("query($id: String!) { alert(event_id: $id) { event_id kind screening_run { "
                + "approach_count } } }", Map.of("id", overLongRun));

        JsonNode alert = r.get("data").get("alert");
        assertThat(alert.get("event_id").asString()).isEqualTo(overLongRun);
        assertThat(alert.get("kind").asString()).isEqualTo("screening_run");
        assertThat(alert.get("screening_run").isNull()).isTrue();
        assertOneInternalError(r, "alert", "screening_run", "approach_count");
    }

    @Test
    void theCurrentRunAnswersARecordsNotListedOverIntTheSameWay() throws Exception {
        JsonNode r = response("{ screening_current { stale summary { run_id differing_copies_over_cap { "
                + "records_not_listed } } } }", null);

        JsonNode summary = r.get("data").get("screening_current").get("summary");
        assertThat(summary.get("run_id").asString()).isEqualTo(currentRunId);
        assertThat(summary.get("differing_copies_over_cap").isNull()).isTrue();
        assertOneInternalError(r, "screening_current", "summary", "differing_copies_over_cap", 0,
                "records_not_listed");
    }

    /** A refused value the API writes keeps its own BAD_REQUEST text. */
    @Test
    void aBadRequestIsUnchanged() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        HttpResponse<String> r = b.postJson("/api/graphql", JSON.writeValueAsString(Map.of("query",
                "{ alerts(kind: \"other\") { next } }")));

        JsonNode error = JSON.readTree(r.body()).get("errors").get(0);
        assertThat(error.get("extensions").get("classification").asString()).isEqualTo("BAD_REQUEST");
        assertThat(error.get("message").asString()).isEqualTo("kind is close_approach or space_weather_level.");
    }

    /** An argument out of Int's range is refused before anything runs, as graphql-java's validation error. */
    @Test
    void anArgumentOverIntIsStillAValidationError() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        HttpResponse<String> r = b.postJson("/api/graphql", JSON.writeValueAsString(Map.of("query",
                "{ catalog_object(norad_cat_id: " + OVER_INT + ") { norad_cat_id } }")));

        JsonNode body = JSON.readTree(r.body());
        assertThat(body.has("data")).as(r.body()).isFalse();
        assertThat(body.get("errors")).singleElement().satisfies(e ->
                assertThat(e.get("extensions").get("classification").asString()).isEqualTo("ValidationError"));
    }
}
