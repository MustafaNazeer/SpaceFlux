package io.github.mustafanazeer.spaceflux.query.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The read endpoints of docs/api/rest.md over HTTP, against the migrated database. */
class ReadApiIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static ConfigurableApplicationContext app;
    static String base;

    @BeforeAll
    static void start() {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class, ThrowingFilter.class)
                .web(WebApplicationType.SERVLET)
                .run(TestMysql.args("--server.port=0", "--spaceflux.alerts.enabled=false",
                        "--spaceflux.catalog.enabled=false"));
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @BeforeEach
    void emptySeries() throws Exception {
        // Other test classes share this database and write series rows too.
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series");
    }

    /** A filter that fails on one path, standing in for any filter, Spring Security's included, that throws. */
    @org.springframework.context.annotation.Configuration
    static class ThrowingFilter {

        @org.springframework.context.annotation.Bean
        jakarta.servlet.Filter failingFilter() {
            return (request, response, chain) -> {
                if (((jakarta.servlet.http.HttpServletRequest) request).getRequestURI().equals("/api/fail-in-filter")) {
                    throw new IllegalStateException("secret internal detail");
                }
                chain.doFilter(request, response);
            };
        }
    }

    static java.util.Set<String> fields(JsonNode node) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    static HttpResponse<String> send(String method, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).method(method,
                HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    static void assertProblem(HttpResponse<String> r, int status, String path) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode p = JSON.readTree(r.body());
        assertThat(p.get("status").asInt()).isEqualTo(status);
        assertThat(p.get("correlation_id").asString())
                .isEqualTo(r.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(fields(p)).containsExactlyInAnyOrder("title", "status", "detail", "instance", "correlation_id");
        assertThat(p.get("instance").asString()).isEqualTo(path);
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("at io.").doesNotContain("trace")
                .doesNotContain("secret internal detail");
    }

    static HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A UTC time the way MySQL takes it, at whole seconds. */
    static String sql(Instant t) {
        return "'" + t.truncatedTo(ChronoUnit.SECONDS).toString().replace("T", " ").replace("Z", "") + "'";
    }

    static void series(String scale, int satellite, String state, String label, Integer level, Double value,
            Instant freshness) throws Exception {
        TestMysql.rootSql("INSERT INTO spaceflux.space_weather_series (scale, series_satellite, rules_version, state, "
                + "derived_level, derived_label, value, unit, time_tag, sample_time, interval_start, "
                + "freshness_reference, state_alert_seq, last_alert_seq) VALUES ('" + scale + "', " + satellite
                + ", 1, '" + state + "', " + level + ", '" + label + "', " + value + ", '"
                + ("G".equals(scale) ? "Kp index" : "R".equals(scale) ? "W m-2" : "pfu") + "', 'tag', "
                + ("G".equals(scale) ? "NULL, " + sql(freshness) : sql(freshness) + ", NULL") + ", "
                + sql(freshness) + ", 1, 1)");
    }

    static JsonNode scale(JsonNode current, String scale) {
        for (JsonNode s : current.get("scales")) {
            if (s.get("scale").asString().equals(scale)) {
                return s;
            }
        }
        throw new AssertionError("no entry for " + scale);
    }

    @Test
    void withNoSeriesEveryScaleReadsNoDataWithNoSeries() throws Exception {
        HttpResponse<String> r = get("/api/space-weather/current");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("scales")).hasSize(3);
        for (String s : new String[] {"G", "R", "S"}) {
            assertThat(scale(body, s).get("state").asString()).isEqualTo("no_data");
            assertThat(scale(body, s).get("no_data_reason").asString()).isEqualTo("no_series");
            assertThat(scale(body, s).has("satellite")).isFalse();
        }
        assertThat(body.get("as_of").asString()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}Z");
    }

    @Test
    void aCurrentSeriesWithinItsAgeLimitShowsItsState() throws Exception {
        Instant fresh = Instant.now().minus(5, ChronoUnit.MINUTES);
        series("R", 18, "level", "R1", 1, 1.0624149581417441e-05, fresh);

        JsonNode r = scale(JSON.readTree(get("/api/space-weather/current").body()), "R");

        assertThat(r.get("state").asString()).isEqualTo("level");
        assertThat(r.get("satellite").asInt()).isEqualTo(18);
        assertThat(r.get("derived_level").asInt()).isEqualTo(1);
        assertThat(r.get("derived_label").asString()).isEqualTo("R1");
        assertThat(r.get("value").asDouble()).isEqualTo(1.0624149581417441e-05);
        assertThat(r.get("unit").asString()).isEqualTo("W m-2");
        assertThat(r.get("age_limit_s").asInt()).isEqualTo(1200);
        assertThat(r.get("rules_version").asInt()).isEqualTo(1);
        assertThat(r.get("freshness_reference").asString())
                .isEqualTo(fresh.truncatedTo(ChronoUnit.SECONDS).toString().replace("Z", ".000000Z"));
        assertThat(r.has("no_data_reason")).isFalse();
    }

    @Test
    void aSeriesPastItsAgeLimitReadsNoDataFromWhenTheLimitPassed() throws Exception {
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        series("S", 18, "level", "S1", 1, 12.344, old);

        JsonNode s = scale(JSON.readTree(get("/api/space-weather/current").body()), "S");

        assertThat(s.get("state").asString()).isEqualTo("no_data");
        assertThat(s.get("no_data_reason").asString()).isEqualTo("age_limit");
        assertThat(s.get("no_data_since").asString())
                .isEqualTo(old.plus(40, ChronoUnit.MINUTES).toString().replace("Z", ".000000Z"));
        assertThat(s.get("freshness_reference").asString()).isEqualTo(old.toString().replace("Z", ".000000Z"));
        assertThat(s.has("derived_level")).isFalse();
        assertThat(s.has("value")).isFalse();
    }

    @Test
    void theCurrentSeriesIsTheNewestThatIsNotEnded() throws Exception {
        Instant now = Instant.now();
        series("R", 16, "level", "R2", 2, 5.5e-05, now.minus(1, ChronoUnit.MINUTES));
        TestMysql.rootSql("UPDATE spaceflux.space_weather_series SET state = 'ended', derived_level = NULL, "
                + "derived_label = 'no data', value = NULL WHERE scale = 'R' AND series_satellite = 16");
        series("R", 18, "none", "none", null, 1.0e-07, now.minus(3, ChronoUnit.MINUTES));
        series("R", 19, "level", "R1", 1, 1.1e-05, now.minus(9, ChronoUnit.MINUTES));

        JsonNode r = scale(JSON.readTree(get("/api/space-weather/current").body()), "R");

        assertThat(r.get("satellite").asInt()).isEqualTo(18);
        assertThat(r.get("state").asString()).isEqualTo("none");
    }

    @Test
    void theWatchlistListsEachObjectWithItsCatalogRowWhenThereIsOne() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = 25544");
        JsonNode without = JSON.readTree(get("/api/watchlist").body());
        assertThat(without.get("objects")).singleElement().satisfies(o -> {
            assertThat(o.get("catalog_number").asLong()).isEqualTo(25544);
            assertThat(o.get("name").asString()).isEqualTo("ISS (ZARYA)");
            assertThat(o.get("rules_version").asInt()).isEqualTo(1);
            assertThat(o.has("catalog")).isFalse();
        });

        TestMysql.rootSql("INSERT INTO spaceflux.catalog_object VALUES (25544, 'ISS (ZARYA)', false, '1998-067A', "
                + "'2026-09-27 04:10:50.460096', '2026-09-27T04:10:50.460096', 15.48664528, 0.0007168, 51.6315, "
                + "155.3455, 193.0559, 167.0244, 0.00018291, 9.528e-05, 0, 0, 'U', 999, 58756, "
                + "'2026-09-27 08:57:39', 'https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json', "
                + "'2026-09-27 08:57:39', '2026-09-27 08:57:39')");
        JsonNode catalog = JSON.readTree(get("/api/watchlist").body()).get("objects").get(0).get("catalog");

        assertThat(catalog.get("object_name").asString()).isEqualTo("ISS (ZARYA)");
        assertThat(catalog.get("epoch").asString()).isEqualTo("2026-09-27T04:10:50.460096Z");
        assertThat(catalog.get("epoch_text").asString()).isEqualTo("2026-09-27T04:10:50.460096");
        assertThat(catalog.get("mean_motion").asDouble()).isEqualTo(15.48664528);
        assertThat(catalog.get("rev_at_epoch").asLong()).isEqualTo(58756);
        assertThat(catalog.get("object_name_cut").asBoolean()).isFalse();
    }

    @Test
    void everyResponseCarriesItsOwnCorrelationIdAndIgnoresTheClients() throws Exception {
        HttpResponse<String> r = get("/api/watchlist", "X-Correlation-Id", "chosen-by-the-client");

        String id = r.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(id).isNotEqualTo("chosen-by-the-client");
        assertThat(get("/api/watchlist").headers().firstValue("X-Correlation-Id").orElseThrow()).isNotEqualTo(id);
    }

    @Test
    void theErrorPathAnswersWithTheSameProblemBody() throws Exception {
        assertProblem(get("/error"), 404, "/error");
    }

    @Test
    void aMethodTheContainerRefusesGetsTheSameProblemBody() throws Exception {
        assertProblem(send("TRACE", "/api/watchlist"), 405, "/api/watchlist");
    }

    @Test
    void anExceptionInAFilterGetsAGenericProblemBodyWithNothingInternal() throws Exception {
        assertProblem(get("/api/fail-in-filter"), 500, "/api/fail-in-filter");
    }

    @Test
    void eachResponseHasExactlyTheDocumentedFields() throws Exception {
        Instant now = Instant.now();
        series("R", 18, "level", "R1", 1, 1.0624149581417441e-05, now.minus(5, ChronoUnit.MINUTES));
        TestMysql.rootSql("UPDATE spaceflux.space_weather_series SET xray_class = 'M1.0' WHERE scale = 'R'");
        series("S", 18, "level", "S1", 1, 12.344, now.minus(2, ChronoUnit.HOURS));
        JsonNode current = JSON.readTree(get("/api/space-weather/current").body());

        assertThat(fields(current)).containsExactly("as_of", "scales");
        assertThat(fields(scale(current, "G"))).containsExactlyInAnyOrder("scale", "state", "no_data_reason");
        assertThat(fields(scale(current, "R"))).containsExactlyInAnyOrder("scale", "satellite", "state",
                "derived_level", "derived_label", "value", "unit", "xray_class", "time_tag", "sample_time",
                "freshness_reference", "age_limit_s", "rules_version");
        assertThat(fields(scale(current, "S"))).containsExactlyInAnyOrder("scale", "satellite", "state",
                "derived_label", "unit", "freshness_reference", "no_data_reason", "no_data_since", "age_limit_s",
                "rules_version");

        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = 25544");
        JsonNode bare = JSON.readTree(get("/api/watchlist").body());
        assertThat(fields(bare)).containsExactly("objects");
        assertThat(fields(bare.get("objects").get(0))).containsExactlyInAnyOrder("catalog_number", "name",
                "rules_version");
    }

    @Test
    void aCatalogRowHasExactlyTheDocumentedFields() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = 25544");
        TestMysql.rootSql("INSERT INTO spaceflux.catalog_object VALUES (25544, 'ISS (ZARYA)', false, '1998-067A', "
                + "'2026-09-27 04:10:50.460096', '2026-09-27T04:10:50.460096', 15.48664528, 0.0007168, 51.6315, "
                + "155.3455, 193.0559, 167.0244, 0.00018291, 9.528e-05, 0, 0, 'U', 999, 58756, "
                + "'2026-09-27 08:57:39', 'https://celestrak.org/', '2026-09-27 08:57:39', '2026-09-27 08:57:39')");

        JsonNode catalog = JSON.readTree(get("/api/watchlist").body()).get("objects").get(0).get("catalog");

        assertThat(fields(catalog)).containsExactlyInAnyOrder("norad_cat_id", "object_name", "object_name_cut",
                "object_id", "epoch", "epoch_text", "mean_motion", "eccentricity", "inclination", "ra_of_asc_node",
                "arg_of_pericenter", "mean_anomaly", "bstar", "mean_motion_dot", "mean_motion_ddot",
                "ephemeris_type", "classification_type", "element_set_no", "rev_at_epoch", "fetched_at",
                "source_url", "first_fetched_at", "last_fetched_at");
    }

    @Test
    void twoSeriesWithTheSameFreshnessAlwaysPickTheHigherSatellite() throws Exception {
        Instant fresh = Instant.now().minus(2, ChronoUnit.MINUTES);
        series("R", 18, "none", "none", null, 1.0e-07, fresh);
        series("R", 17, "level", "R1", 1, 1.1e-05, fresh);

        assertThat(scale(JSON.readTree(get("/api/space-weather/current").body()), "R").get("satellite").asInt())
                .isEqualTo(18);
    }

    @Test
    void aSeriesWithNoFreshnessReferenceShowsItsStoredState() throws Exception {
        TestMysql.rootSql("INSERT INTO spaceflux.space_weather_series (scale, series_satellite, rules_version, state, "
                + "derived_label, unit, no_data_reason, no_data_since, state_alert_seq, last_alert_seq) VALUES "
                + "('S', 18, 1, 'no_data', 'no data', 'pfu', 'rejected', '2026-10-04 10:00:00', 1, 1)");

        JsonNode s = scale(JSON.readTree(get("/api/space-weather/current").body()), "S");

        assertThat(s.get("state").asString()).isEqualTo("no_data");
        assertThat(s.get("no_data_reason").asString()).isEqualTo("rejected");
        assertThat(s.get("no_data_since").asString()).isEqualTo("2026-10-04T10:00:00.000000Z");
        assertThat(s.has("freshness_reference")).isFalse();
    }

    @Test
    void anUnknownPathIsAProblemDetailWithTheCorrelationIdAndNothingInternal() throws Exception {
        HttpResponse<String> r = get("/api/nothing-here");
        assertProblem(r, 404, "/api/nothing-here");

        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode p = JSON.readTree(r.body());
        assertThat(p.get("status").asInt()).isEqualTo(404);
        assertThat(p.get("correlation_id").asString())
                .isEqualTo(r.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("at io.").doesNotContain("trace");
    }
}
