package io.github.mustafanazeer.spaceflux.query.ack;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** POST and GET /api/alerts/acknowledgements and the operator's view of alerts (docs/api/rest.md, sections 8, 9). */
public class AcknowledgementIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final AtomicInteger SEQ = new AtomicInteger();
    static final String ACK = "{\"action\": \"acknowledge\"}";
    static final String UNACK = "{\"action\": \"unacknowledge\"}";

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static String esc(String text) {
        return text.replace("\\", "\\\\").replace("'", "''");
    }

    static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static Set<String> fields(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    /** Stores an example event under a fresh event_id, with only its alert_event row; returns the event_id. */
    public static String stored(String file) throws Exception {
        String text = Files.readString(EXAMPLES.resolve(file));
        String original = JSON.readTree(text).get("event_id").asString();
        String id = original + "/" + UUID.randomUUID();
        TestMysql.rootSql("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, rules_version, "
                + "produced_at, source_partition, source_offset, payload) VALUES ('" + esc(id) + "', '"
                + JSON.readTree(text).get("kind").asString() + "', 1, 1, '2026-10-05 00:00:00', 0, 0, '"
                + esc(text.replace("\"" + original + "\"", "\"" + id + "\"")) + "')");
        return id;
    }

    /** Stores an R space weather event with its space_weather_event row; returns the event_id. */
    static String spaceWeather(String state, String trigger) throws Exception {
        String id = "space_weather_level/1/R/18/" + SEQ.incrementAndGet() + "/" + UUID.randomUUID();
        TestMysql.rootSql("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, rules_version, "
                + "produced_at, source_partition, source_offset, payload) VALUES ('" + id + "', 'space_weather_level', "
                + "1, 1, '2026-10-05 00:00:00', 0, 0, '{}'); INSERT INTO spaceflux.space_weather_event (alert_seq, "
                + "rules_version, scale, satellite, product, state, derived_level, derived_label, trigger_kind, "
                + "derived_from, estimated, unit, value, time_tag, sample_time) VALUES (LAST_INSERT_ID(), 1, 'R', 18, "
                + "'swpc.goes.xrays', '" + state + "', " + ("level".equals(state) ? "1" : "NULL") + ", '"
                + ("level".equals(state) ? "R1" : "no_data".equals(state) ? "no data" : "none") + "', '" + trigger
                + "', 'measurement', false, 'W m-2', 1.1e-5, '2026-10-05T00:00:00Z', '2026-10-05 00:00:00')");
        return id;
    }

    public static HttpResponse<String> post(Browser b, String eventId, String body) throws Exception {
        return b.postJson("/api/alerts/acknowledgements?event_id=" + q(eventId), body);
    }

    static int rows(String eventId) throws Exception {
        return Integer.parseInt(TestMysql.rootQuery("SELECT COUNT(*) FROM spaceflux.alert_acknowledgement "
                + "WHERE event_id = '" + esc(eventId) + "'"));
    }

    /** SQL statements the API's database user has run so far. */
    static long apiStatements() throws Exception {
        return Long.parseLong(TestMysql.rootQuery("SELECT COALESCE(SUM(COUNT_STAR), 0) FROM performance_schema."
                + "events_statements_summary_by_user_by_event_name WHERE USER = '" + TestMysql.API
                + "' AND EVENT_NAME LIKE 'statement/sql/%'"));
    }

    static void assertProblem(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(JSON.readTree(r.body()).get("correlation_id").asString())
                .isEqualTo(r.headers().firstValue("X-Correlation-Id").orElseThrow());
    }

    @Test
    void theOperatorAcknowledgesACloseApproach() throws Exception {
        String id = stored("valid-close-approach.json");

        HttpResponse<String> r = post(app.signedIn(), id, ACK);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode row = JSON.readTree(r.body());
        assertThat(fields(row)).containsExactlyInAnyOrder("event_id", "action", "principal", "acted_at");
        assertThat(row.get("event_id").asString()).isEqualTo(id);
        assertThat(row.get("action").asString()).isEqualTo("acknowledge");
        assertThat(row.get("principal").asString()).isEqualTo(OperatorApp.OPERATOR);
        assertThat(row.get("acted_at").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(TestMysql.rootQuery("SELECT action, principal, note IS NULL FROM spaceflux.alert_acknowledgement "
                + "WHERE event_id = '" + esc(id) + "'")).isEqualTo("acknowledge\toperator\t1");
    }

    @Test
    void aNoteIsStoredAndReturnedAndMayHoldFiveHundredCodePointsOutsideTheBasicPlane() throws Exception {
        String id = stored("valid-close-approach.json");
        String note = "𝐀".repeat(500);

        HttpResponse<String> r = post(app.signedIn(), id, "{\"action\": \"acknowledge\", \"note\": \"" + note + "\"}");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(JSON.readTree(r.body()).get("note").asString()).isEqualTo(note);
    }

    @Test
    void theRequestBodyHoldsOnlyAnActionAndANote() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser b = app.signedIn();
        String[] refused = {
            "{\"action\": \"acknowledge\", \"note\": \"" + "𝐀".repeat(501) + "\"}",
            "{\"action\": \"acknowledge\", \"principal\": \"someone\"}",
            "{\"action\": \"acknowledge\", \"acted_at\": \"2020-01-01T00:00:00Z\"}",
            "{\"action\": \"acknowledge\", \"event_id\": \"x\"}",
            "{\"note\": \"no action\"}",
            "{\"action\": \"delete\"}",
            "{\"action\": null}",
            "{\"action\": \"acknowledge\", \"note\": 5}",
            "{\"action\": \"acknowledge\", \"note\": null}",
            "[\"acknowledge\"]",
            "\"acknowledge\"",
            "{\"action\": \"acknowledge\"} {}",
            "{\"action\": \"unacknowledge\", \"action\": \"acknowledge\"}",
            "",
        };
        for (String body : refused) {
            assertProblem(post(b, id, body), 400);
        }
        assertThat(rows(id)).isZero();
    }

    @Test
    void aBodyThatIsNotJsonByItsContentTypeIsRefused() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser b = app.signedIn();

        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", "application/xml"}) {
            assertProblem(b.post("/api/alerts/acknowledgements?event_id=" + q(id), type, ACK), 415);
        }
        assertThat(rows(id)).isZero();
    }

    @Test
    void aMissingOverlongOrUnknownEventIdIsAProblem() throws Exception {
        Browser b = app.signedIn();

        assertProblem(b.postJson("/api/alerts/acknowledgements", ACK), 400);
        assertProblem(post(b, "x".repeat(513), ACK), 400);
        assertProblem(post(b, "close_approach/no/such/" + UUID.randomUUID(), ACK), 404);
    }

    @Test
    void onlyCloseApproachesAndSpaceWeatherLevelsCanBeAcknowledged() throws Exception {
        Browser b = app.signedIn();

        assertThat(post(b, spaceWeather("level", "level_change"), ACK).statusCode()).isEqualTo(201);
        assertThat(post(b, spaceWeather("level", "revision"), ACK).statusCode()).isEqualTo(201);
        for (String id : new String[] {stored("valid-screening-run.json"), spaceWeather("no_data", "level_change"),
            spaceWeather("none", "level_change"), spaceWeather("ended", "level_change"),
            spaceWeather("level", "refresh")}) {
            assertProblem(post(b, id, ACK), 409);
            assertThat(rows(id)).as(id).isZero();
        }
    }

    @Test
    void anActionEqualToTheCurrentStateIsRefusedAndWritesNoRow() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser b = app.signedIn();

        assertProblem(post(b, id, UNACK), 409);
        assertThat(post(b, id, ACK).statusCode()).isEqualTo(201);
        assertProblem(post(b, id, ACK), 409);
        assertThat(post(b, id, UNACK).statusCode()).isEqualTo(201);
        assertProblem(post(b, id, UNACK), 409);
        assertThat(post(b, id, ACK).statusCode()).isEqualTo(201);

        assertThat(TestMysql.rootQuery("SELECT GROUP_CONCAT(action ORDER BY ack_id) FROM "
                + "spaceflux.alert_acknowledgement WHERE event_id = '" + esc(id) + "'"))
                .isEqualTo("acknowledge,unacknowledge,acknowledge");
    }

    @Test
    void withoutASessionTheWriteIsUnauthorizedBeforeAnyQuery() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser anonymous = app.browser();
        anonymous.get("/api/auth/session");
        long before = apiStatements();

        HttpResponse<String> r = post(anonymous, id, ACK);

        assertProblem(r, 401);
        assertThat(apiStatements()).isEqualTo(before);
        assertThat(rows(id)).isZero();
    }

    @Test
    void aSessionANewerLoginReplacedCannotWriteAndReadsTheViewersForm() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser first = app.signedIn();
        post(app.signedIn(), id, "{\"action\": \"acknowledge\", \"note\": \"by the second\"}");

        assertProblem(post(first, id, UNACK), 401);
        JsonNode ack = JSON.readTree(first.get("/api/alerts/by-id?event_id=" + q(id)).body()).get("acknowledgement");

        assertThat(rows(id)).isEqualTo(1);
        assertThat(fields(ack)).containsExactlyInAnyOrder("action", "acted_at");
    }

    @Test
    void withoutTheXsrfHeaderEvenTheOperatorIsForbidden() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser b = app.signedIn();
        b.sendXsrfHeader = false;

        assertProblem(post(b, id, ACK), 403);
        assertThat(rows(id)).isZero();
    }

    @Test
    void noOtherUnsafeMethodIsAccepted() throws Exception {
        Browser b = app.signedIn();
        String path = "/api/alerts/acknowledgements?event_id=" + q(stored("valid-close-approach.json"));

        for (String method : new String[] {"PUT", "PATCH", "DELETE"}) {
            assertProblem(b.send(method, path, ACK), 403);
        }
        assertProblem(b.postJson("/api/watchlist", "{}"), 403);
        assertProblem(b.postJson("/api/alerts/by-id?event_id=x", "{}"), 403);
    }

    @Test
    void theOperatorSeesThePrincipalAndNoteOfAnAlertsAcknowledgementAndAViewerDoesNot() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser b = app.signedIn();
        post(b, id, "{\"action\": \"acknowledge\", \"note\": \"checked the geometry\"}");
        String path = "/api/alerts/by-id?event_id=" + q(id);

        JsonNode operator = JSON.readTree(b.get(path).body()).get("acknowledgement");
        JsonNode viewer = JSON.readTree(app.browser().get(path).body()).get("acknowledgement");

        assertThat(fields(operator)).containsExactlyInAnyOrder("action", "acted_at", "principal", "note");
        assertThat(operator.get("note").asString()).isEqualTo("checked the geometry");
        assertThat(fields(viewer)).containsExactlyInAnyOrder("action", "acted_at");
    }

    @Test
    void historyListsEveryRowNewestFirstInTheViewersForm() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser b = app.signedIn();
        post(b, id, "{\"action\": \"acknowledge\", \"note\": \"first\"}");
        post(b, id, UNACK);
        String path = "/api/alerts/acknowledgements?event_id=" + q(id);

        HttpResponse<String> r = app.browser().get(path);

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(fields(body)).containsExactlyInAnyOrder("event_id", "items");
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("action").asString()).isEqualTo("unacknowledge");
        for (JsonNode item : body.get("items")) {
            assertThat(fields(item)).containsExactlyInAnyOrder("action", "acted_at");
        }
        JsonNode operator = JSON.readTree(b.get(path).body()).get("items").get(1);
        assertThat(fields(operator)).containsExactlyInAnyOrder("action", "acted_at", "principal", "note");
        assertThat(operator.get("note").asString()).isEqualTo("first");
    }

    @Test
    void historyIsPagedByLimitAndCursor() throws Exception {
        String id = stored("valid-close-approach.json");
        Browser b = app.signedIn();
        for (int i = 0; i < 5; i++) {
            post(b, id, i % 2 == 0 ? ACK : UNACK);
        }
        String path = "/api/alerts/acknowledgements?event_id=" + q(id) + "&limit=2";

        JsonNode first = JSON.readTree(app.browser().get(path).body());
        JsonNode second = JSON.readTree(app.browser().get(path + "&after=" + q(first.get("next").asString())).body());
        JsonNode third = JSON.readTree(app.browser().get(path + "&after=" + q(second.get("next").asString())).body());

        assertThat(first.get("items")).hasSize(2);
        assertThat(second.get("items")).hasSize(2);
        assertThat(third.get("items")).hasSize(1);
        assertThat(third.has("next")).isFalse();
        assertThat(first.get("items").get(0).get("action").asString()).isEqualTo("acknowledge");
        assertThat(third.get("items").get(0).get("action").asString()).isEqualTo("acknowledge");
        assertProblem(app.browser().get(path + "&after=notACursor"), 400);
        assertProblem(app.browser().get("/api/alerts/acknowledgements?event_id=" + q(id) + "&limit=201"), 400);
        assertProblem(app.browser().get("/api/alerts/acknowledgements?event_id=" + q(id) + "&limit=0"), 400);
    }

    @Test
    void historyOfAnUnknownAlertIsNotFoundAndOfAnUnacknowledgedOneIsEmpty() throws Exception {
        assertProblem(app.browser().get("/api/alerts/acknowledgements?event_id=nope/" + UUID.randomUUID()), 404);
        assertProblem(app.browser().get("/api/alerts/acknowledgements"), 400);
        assertProblem(app.browser().get("/api/alerts/acknowledgements?event_id=" + "x".repeat(513)), 400);

        JsonNode body = JSON.readTree(app.browser().get("/api/alerts/acknowledgements?event_id="
                + q(stored("valid-close-approach.json"))).body());

        assertThat(body.get("items")).isEmpty();
        assertThat(body.has("next")).isFalse();
    }
}
