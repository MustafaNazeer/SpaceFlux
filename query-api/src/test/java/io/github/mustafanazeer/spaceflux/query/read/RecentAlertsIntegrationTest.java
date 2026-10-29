package io.github.mustafanazeer.spaceflux.query.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@code GET /api/alerts/recent} (docs/api/rest.md, section 11) against the migrated database. */
class RecentAlertsIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PATH = "/api/alerts/recent";

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp();
        ReadApiIntegrationTest.base = app.base;
        GraphQlIntegrationTest.base = app.base;
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static JsonNode page(Browser b, String query) throws Exception {
        HttpResponse<String> r = b.get(PATH + query);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).doesNotContain("null");
        return JSON.readTree(r.body());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void newestFirstWithoutRefreshesAndANextWhileMoreRemain() throws Exception {
        String first = GraphQlIntegrationTest.approach(25544, 92001, "2026-10-20T01:00:00");
        GraphQlIntegrationTest.level("level", "refresh");
        String level = GraphQlIntegrationTest.level("level", "level_change");
        String last = GraphQlIntegrationTest.approach(25544, 92002, "2026-10-20T02:00:00");

        JsonNode page = page(app.browser(), "?limit=3");

        assertThat(ReadApiIntegrationTest.fields(page)).containsExactly("items", "next");
        assertThat(GraphQlIntegrationTest.ids(page)).containsExactly(last, level, first);
    }

    @Test
    void anItemIsTheEventWithItsReceivedAtAndNoNullFields() throws Exception {
        String id = GraphQlIntegrationTest.approach(25544, 92003, "2026-10-20T03:00:00");

        JsonNode item = page(app.browser(), "?limit=1").get("items").get(0);

        assertThat(ReadApiIntegrationTest.fields(item)).containsExactlyInAnyOrder("schema_version", "kind",
                "rules_version", "event_id", "produced_at", "close_approach", "received_at");
        assertThat(item.get("event_id").asString()).isEqualTo(id);
        assertThat(item.get("received_at").asString()).endsWith("Z");
        assertThat(item.get("close_approach").get("miss_distance_m").asDouble()).isEqualTo(1200.5);
    }

    @Test
    void theCursorPagesToTheEndAndTheLastPageHasNoNext() throws Exception {
        String a = GraphQlIntegrationTest.approach(25544, 92004, "2026-10-20T04:00:00");
        String b = GraphQlIntegrationTest.approach(25544, 92005, "2026-10-20T05:00:00");
        String c = GraphQlIntegrationTest.approach(25544, 92006, "2026-10-20T06:00:00");
        Browser browser = app.browser();

        JsonNode one = page(browser, "?kind=close_approach&limit=2");
        JsonNode two = page(browser, "?kind=close_approach&limit=1&after=" + enc(one.get("next").asString()));

        assertThat(GraphQlIntegrationTest.ids(one)).containsExactly(c, b);
        assertThat(GraphQlIntegrationTest.ids(two)).containsExactly(a);
        assertThat(two.has("next")).isTrue();

        String after = two.get("next").asString();
        while (true) {
            JsonNode p = page(browser, "?kind=close_approach&limit=200&after=" + enc(after));
            if (!p.has("next")) {
                break;
            }
            after = p.get("next").asString();
        }
    }

    @Test
    void kindKeepsOneKind() throws Exception {
        GraphQlIntegrationTest.approach(25544, 92007, "2026-10-20T07:00:00");
        String level = GraphQlIntegrationTest.level("level", "level_change");
        GraphQlIntegrationTest.approach(25544, 92008, "2026-10-20T08:00:00");

        JsonNode page = page(app.browser(), "?kind=space_weather_level&limit=1");

        assertThat(GraphQlIntegrationTest.ids(page)).containsExactly(level);
        assertThat(page.get("items").get(0).get("kind").asString()).isEqualTo("space_weather_level");
    }

    @Test
    void theAnswerIsTheGraphQlListsAnswer() throws Exception {
        GraphQlIntegrationTest.approach(25544, 92009, "2026-10-20T09:00:00");
        GraphQlIntegrationTest.level("level", "level_change");
        GraphQlIntegrationTest.level("none", "level_change");
        Browser b = GraphQlIntegrationTest.browser();

        JsonNode rest = page(b, "?limit=3");
        JsonNode graphql = GraphQlIntegrationTest.alerts(b, "limit: 3");

        assertThat(GraphQlIntegrationTest.ids(rest)).isEqualTo(GraphQlIntegrationTest.ids(graphql));
        assertThat(rest.get("next").asString()).isEqualTo(graphql.get("next").asString());
    }

    @Test
    void theAcknowledgementShowsThePrincipalAndNoteToTheOperatorOnly() throws Exception {
        String id = GraphQlIntegrationTest.approach(25544, 92010, "2026-10-20T10:00:00");
        TestMysql.rootSql("INSERT INTO spaceflux.alert_acknowledgement (event_id, action, principal, note) VALUES ('"
                + id + "', 'acknowledge', 'operator', 'seen it')");

        JsonNode anonymous = page(app.browser(), "?limit=1").get("items").get(0).get("acknowledgement");
        JsonNode operator = page(app.signedIn(), "?limit=1").get("items").get(0).get("acknowledgement");

        assertThat(ReadApiIntegrationTest.fields(anonymous)).containsExactlyInAnyOrder("action", "acted_at");
        assertThat(anonymous.get("action").asString()).isEqualTo("acknowledge");
        assertThat(ReadApiIntegrationTest.fields(operator)).containsExactlyInAnyOrder("action", "acted_at",
                "principal", "note");
        assertThat(operator.get("note").asString()).isEqualTo("seen it");
    }

    @Test
    void aLimitKindOrCursorOutOfRangeIsAProblemNeverCut() throws Exception {
        Browser b = app.browser();
        String approaches = page(b, "?kind=close_approach&limit=1").get("next").asString();

        for (String q : new String[] {"?limit=0", "?limit=201", "?limit=many", "?kind=screening_run",
            "?after=not-a-cursor", "?after=" + enc(approaches)}) {
            ReadApiIntegrationTest.assertProblem(b.get(PATH + q), 400, PATH);
        }
    }
}
