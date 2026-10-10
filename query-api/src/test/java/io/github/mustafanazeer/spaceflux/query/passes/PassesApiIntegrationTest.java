package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GET /api/watchlist/{catalog_number}/passes and WatchlistObject.passes (docs/api/rest.md section 10,
 * docs/api/graphql.md) against the migrated database, with the service clock fixed inside the recorded ISS
 * element set's age limit.
 */
class PassesApiIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-09-27T05:00:00.400Z");
    static final String ISS_FILE = "orbit-core/src/test/resources/celestrak/gp-catnr-25544.json";

    static OperatorApp app;

    @Configuration
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @BeforeAll
    static void start() {
        app = new OperatorApp(new Class<?>[] {FixedClock.class});
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    /** The recorded ISS element set as the catalog consumer stores it, with the epoch and class given. */
    static void storeIss(String epochText, String classification) throws Exception {
        JsonNode gp = JSON.readTree(ReferencePasses.recorded(ISS_FILE)).get(0);
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = 25544");
        TestMysql.rootSql("INSERT INTO spaceflux.catalog_object VALUES (25544, 'ISS (ZARYA)', false, '"
                + gp.get("OBJECT_ID").asString() + "', '" + epochText.replace("T", " ") + "', '" + epochText + "', "
                + gp.get("MEAN_MOTION") + ", " + gp.get("ECCENTRICITY") + ", " + gp.get("INCLINATION") + ", "
                + gp.get("RA_OF_ASC_NODE") + ", " + gp.get("ARG_OF_PERICENTER") + ", " + gp.get("MEAN_ANOMALY")
                + ", " + gp.get("BSTAR") + ", " + gp.get("MEAN_MOTION_DOT") + ", " + gp.get("MEAN_MOTION_DDOT")
                + ", " + gp.get("EPHEMERIS_TYPE") + ", '" + classification + "', " + gp.get("ELEMENT_SET_NO") + ", "
                + gp.get("REV_AT_EPOCH") + ", '2026-09-27 08:57:39', "
                + "'https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=json', '2026-09-27 08:57:39', "
                + "'2026-09-27 08:57:39')");
    }

    @BeforeEach
    void recordedIss() throws Exception {
        storeIss("2026-09-27T04:10:50.460096", "U");
    }

    static Browser browser() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        return b;
    }

    static JsonNode rest(int catalogNumber) throws Exception {
        HttpResponse<String> r = browser().get("/api/watchlist/" + catalogNumber + "/passes");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    static Set<String> fields(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    static String utc(AbsoluteDate date) {
        return date.toStringWithoutUtcOffset(OrekitData.utc(), 3) + "Z";
    }

    @Test
    void theIssPassesAreTheOnesComputedFromTheRecordedElementSet() throws Exception {
        JsonNode r = rest(25544);

        TrackedObject iss = ReferencePasses.elementSet(ISS_FILE, 25544);
        AbsoluteDate start = new AbsoluteDate("2026-09-27T05:00:00", OrekitData.utc());
        PassFinder.Outcome expected = new PassFinder().find(iss, start);
        assertThat(r.get("window_start").asString()).isEqualTo("2026-09-27T05:00:00.000Z");
        assertThat(r.get("window_end").asString()).isEqualTo("2026-09-28T05:00:00.000Z");
        assertThat(r.get("search_end").asString()).isEqualTo("2026-09-28T05:00:00.000Z");
        assertThat(r.get("status").asString()).isEqualTo("computed");
        assertThat(r.get("epoch_text").asString()).isEqualTo("2026-09-27T04:10:50.460096");
        assertThat(r.get("passes")).hasSize(expected.passes().size()).hasSize(4);
        for (int i = 0; i < 4; i++) {
            JsonNode p = r.get("passes").get(i);
            PassGrouping.Pass e = expected.passes().get(i);
            assertThat(p.get("rise").get("time").asString()).isEqualTo(utc(expected.at(e.rise().t())));
            assertThat(p.get("rise").get("azimuth_deg").asDouble()).isEqualTo(e.rise().azimuthDeg());
            assertThat(p.get("rise").get("elevation_deg").asDouble()).isEqualTo(e.rise().elevationDeg());
            assertThat(p.get("set").get("time").asString()).isEqualTo(utc(expected.at(e.set().t())));
            assertThat(p.get("peak").get("time").asString()).isEqualTo(utc(expected.at(e.peak().t())));
            assertThat(p.get("peak").get("elevation_deg").asDouble()).isEqualTo(e.peak().elevationDeg());
            assertThat(p.get("element_age_days").asDouble()).isEqualTo(expected.elementAgeDaysAt(e.peak().t()));
            assertThat(p.get("peak_count").asInt()).isEqualTo(1);
        }
        assertThat(r.get("passes").get(0).get("rise").get("time").asString()).startsWith("2026-09-27T16:40:18.");
    }

    @Test
    void theAnswerCarriesExactlyTheDocumentedFields() throws Exception {
        JsonNode r = rest(25544);

        assertThat(fields(r)).containsExactlyInAnyOrder("catalog_number", "name", "observer", "elevation_mask_deg",
                "note", "window_start", "window_end", "status", "epoch_text", "search_end", "passes");
        assertThat(r.get("catalog_number").asInt()).isEqualTo(25544);
        assertThat(r.get("name").asString()).isEqualTo("ISS (ZARYA)");
        assertThat(r.get("elevation_mask_deg").asDouble()).isEqualTo(10);
        assertThat(r.get("note").asString()).startsWith("Geometric passes").contains("not an operational prediction");
        JsonNode o = r.get("observer");
        assertThat(fields(o)).containsExactlyInAnyOrder("name", "ngs_pid", "latitude_deg", "longitude_deg",
                "height_m");
        assertThat(o.get("name").asString()).isEqualTo("GEMINI 3");
        assertThat(o.get("ngs_pid").asString()).isEqualTo("AW6997");
        assertThat(o.get("latitude_deg").asDouble()).isEqualTo(29.557976853);
        assertThat(o.get("longitude_deg").asDouble()).isEqualTo(-95.091374225);
        assertThat(o.get("height_m").asDouble()).isEqualTo(-22.182);
        JsonNode p = r.get("passes").get(0);
        assertThat(fields(p)).containsExactlyInAnyOrder("rise", "rise_clipped", "set", "set_clipped", "peak",
                "peak_at_edge", "peak_count", "element_age_days");
        assertThat(fields(p.get("peak"))).containsExactlyInAnyOrder("time", "elevation_deg", "azimuth_deg");
        assertThat(p.get("rise_clipped").asBoolean()).isFalse();
    }

    @Test
    void aPassInProgressAtTheWindowStartCarriesItsEdgeInsteadOfARise() throws Exception {
        Clock clock = app.context.getBean(Clock.class);
        assertThat(clock.instant()).isEqualTo(NOW);
        TrackedObject iss = ReferencePasses.elementSet(ISS_FILE, 25544);
        AbsoluteDate inside = new AbsoluteDate("2026-09-27T16:42:29", OrekitData.utc());

        PassFinder.Outcome o = new PassFinder().find(iss, inside);

        assertThat(o.passes().getFirst().riseClipped()).isTrue();
        assertThat(PassesController.answer(25544, "ISS (ZARYA)", null, o).passes().getFirst()).satisfies(p -> {
            assertThat(p.rise()).isNull();
            assertThat(p.riseClipped()).isTrue();
            assertThat(p.startEdge().time()).isEqualTo("2026-09-27T16:42:29.000Z");
            assertThat(p.peakAtEdge()).isTrue();
        });
    }

    @Test
    void anObjectWithNoCatalogRowGetsNoPassesAndSaysWhy() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = 25544");

        JsonNode r = rest(25544);

        assertThat(r.get("status").asString()).isEqualTo("no_element_set");
        assertThat(r.get("reason").asString()).contains("no element set");
        assertThat(fields(r)).doesNotContain("passes", "search_end", "epoch_text", "stop_reason");
        assertThat(r.get("window_start").asString()).isEqualTo("2026-09-27T05:00:00.000Z");
    }

    @Test
    void aStaleElementSetGetsNoPassesAndSaysWhy() throws Exception {
        storeIss("2026-09-17T04:59:59", "U");

        JsonNode r = rest(25544);

        assertThat(r.get("status").asString()).isEqualTo("stale_element_set");
        assertThat(r.get("reason").asString()).contains("over the 10 day limit");
        assertThat(r.get("epoch_text").asString()).isEqualTo("2026-09-17T04:59:59");
        assertThat(fields(r)).doesNotContain("passes", "search_end");
    }

    @Test
    void anElementSetTheServiceCannotBuildGetsNoPassesAndSaysWhy() throws Exception {
        storeIss("2026-09-27T04:10:50.460096", "UU");

        JsonNode r = rest(25544);

        assertThat(r.get("status").asString()).isEqualTo("invalid_element_set");
        assertThat(r.get("reason").asString()).contains("CLASSIFICATION_TYPE");
        assertThat(fields(r)).doesNotContain("passes");
    }

    static void assertProblem(HttpResponse<String> r, int status, String path) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode p = JSON.readTree(r.body());
        assertThat(fields(p)).containsExactlyInAnyOrder("title", "status", "detail", "instance", "correlation_id");
        assertThat(p.get("instance").asString()).isEqualTo(path);
    }

    @Test
    void anObjectNotOnTheWatchlistIs404AndAMalformedNumberIs400() throws Exception {
        Browser b = browser();

        HttpResponse<String> missing = b.get("/api/watchlist/48274/passes");
        assertProblem(missing, 404, "/api/watchlist/48274/passes");
        assertThat(JSON.readTree(missing.body()).get("detail").asString()).isEqualTo(
                "No watchlist object has this number; passes are computed for watchlist objects only.");
        for (String bad : List.of("abc", "-1", "1000000000", "1.5")) {
            assertProblem(b.get("/api/watchlist/" + bad + "/passes"), 400, "/api/watchlist/" + bad + "/passes");
        }
    }

    static final String ALL_FIELDS = "catalog_number name observer { name ngs_pid latitude_deg longitude_deg "
            + "height_m } elevation_mask_deg note window_start window_end status reason epoch_text search_end "
            + "stop_reason passes { rise { time elevation_deg azimuth_deg } rise_clipped start_edge { time "
            + "elevation_deg azimuth_deg } set { time elevation_deg azimuth_deg } set_clipped end_edge { time "
            + "elevation_deg azimuth_deg } peak { time elevation_deg azimuth_deg } peak_at_edge peak_count "
            + "element_age_days }";

    static JsonNode graphQlPasses() throws Exception {
        HttpResponse<String> r = browser().postJson("/api/graphql", JSON.writeValueAsString(Map.of("query",
                "{ watchlist { catalog_number passes { " + ALL_FIELDS + " } } }")));
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.has("errors")).as(r.body()).isFalse();
        return body.get("data").get("watchlist").get(0).get("passes");
    }

    /** Every field REST returns has the same value in GraphQL; a field REST leaves out is null there. */
    static void assertSameAsRest(JsonNode g, JsonNode rest) {
        for (String field : fields(g)) {
            if (rest.has(field)) {
                if (rest.get(field).isObject() || rest.get(field).isArray()) {
                    if (rest.get(field).isArray()) {
                        assertThat(g.get(field)).as(field).hasSize(rest.get(field).size());
                        for (int i = 0; i < rest.get(field).size(); i++) {
                            assertSameAsRest(g.get(field).get(i), rest.get(field).get(i));
                        }
                    } else {
                        assertSameAsRest(g.get(field), rest.get(field));
                    }
                } else {
                    assertThat(g.get(field)).as(field).isEqualTo(rest.get(field));
                }
            } else {
                assertThat(g.get(field).isNull()).as(field + " is null").isTrue();
            }
        }
    }

    @Test
    void theGraphQlFieldMatchesTheRestAnswerFieldForField() throws Exception {
        assertSameAsRest(graphQlPasses(), rest(25544));
    }

    @Test
    void theGraphQlFieldMatchesTheRestAnswerWhenNoPassesAreComputed() throws Exception {
        storeIss("2026-09-17T04:59:59", "U");

        JsonNode g = graphQlPasses();

        assertSameAsRest(g, rest(25544));
        assertThat(g.get("passes").isNull()).isTrue();
        assertThat(g.get("status").asString()).isEqualTo("stale_element_set");
    }
}
