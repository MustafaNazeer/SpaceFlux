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

    static final java.nio.file.Path EXAMPLES = java.nio.file.Path.of("..", "schemas", "alerts", "examples");

    /** Stores an example event as received, under a fresh event_id; returns that event_id. */
    static String storeAlert(String file) throws Exception {
        String text = java.nio.file.Files.readString(EXAMPLES.resolve(file));
        String original = JSON.readTree(text).get("event_id").asString();
        String id = original + "/" + UUID.randomUUID();
        String payload = text.replace("\"" + original + "\"", "\"" + id + "\"");
        TestMysql.rootSql("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, rules_version, "
                + "produced_at, source_partition, source_offset, payload) VALUES ('" + id + "', '"
                + JSON.readTree(text).get("kind").asString() + "', 1, 1, '2026-10-04 00:00:00', 0, 0, '"
                + payload.replace("\\", "\\\\").replace("'", "''") + "')");
        return id;
    }

    static String query(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void anAlertIsReturnedExactlyAsReceivedWithItsLatestAcknowledgement() throws Exception {
        String id = storeAlert("valid-r-level.json");
        TestMysql.rootSql("INSERT INTO spaceflux.alert_acknowledgement (event_id, action, principal, note) VALUES ('"
                + id + "', 'acknowledge', 'operator', 'looked at it')");
        TestMysql.rootSql("INSERT INTO spaceflux.alert_acknowledgement (event_id, action, principal, note) VALUES ('"
                + id + "', 'unacknowledge', 'operator', 'not yet')");

        HttpResponse<String> r = get("/api/alerts/by-id?event_id=" + query(id));

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(fields(body)).containsExactlyInAnyOrder("event", "received_at", "acknowledgement");
        assertThat(body.get("event").get("event_id").asString()).isEqualTo(id);
        // The number keeps the spelling it was received with, so the event is the text stored, not a re-encoding.
        assertThat(r.body()).contains("1.0624149581417441e-05");
        assertThat(fields(body.get("acknowledgement"))).containsExactlyInAnyOrder("action", "acted_at");
        assertThat(body.get("acknowledgement").get("action").asString()).isEqualTo("unacknowledge");
        assertThat(r.body()).doesNotContain("operator").doesNotContain("not yet").doesNotContain("looked at it");
    }

    @Test
    void anAlertWithNoAcknowledgementLeavesItOut() throws Exception {
        String id = storeAlert("valid-g-level.json");

        JsonNode body = JSON.readTree(get("/api/alerts/by-id?event_id=" + query(id)).body());

        assertThat(fields(body)).containsExactlyInAnyOrder("event", "received_at");
    }

    @Test
    void anUnknownMissingOrOverlongEventIdIsAProblem() throws Exception {
        assertProblem(get("/api/alerts/by-id?event_id=" + query("close_approach/1/none")), 404, "/api/alerts/by-id");
        assertProblem(get("/api/alerts/by-id"), 400, "/api/alerts/by-id");
        assertProblem(get("/api/alerts/by-id?event_id=" + "x".repeat(513)), 400, "/api/alerts/by-id");
        assertProblem(get("/api/alerts/by-id?event_id=" + "x".repeat(512)), 404, "/api/alerts/by-id");
        // 512 code points that are 1,024 UTF-16 units: the limit counts code points.
        assertProblem(get("/api/alerts/by-id?event_id=" + query("\uD83D\uDE80".repeat(512))), 404,
                "/api/alerts/by-id");
    }

    @Test
    void aCatalogObjectIsReturnedByItsNumber() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = 25544");
        TestMysql.rootSql("INSERT INTO spaceflux.catalog_object VALUES (25544, 'ISS (ZARYA)', false, '1998-067A', "
                + "'2026-09-27 04:10:50.460096', '2026-09-27T04:10:50.460096', 15.48664528, 0.0007168, 51.6315, "
                + "155.3455, 193.0559, 167.0244, 0.00018291, 9.528e-05, 0, 0, 'U', 999, 58756, "
                + "'2026-09-27 08:57:39', 'https://celestrak.org/', '2026-09-27 08:57:39', '2026-09-27 08:57:39')");

        HttpResponse<String> r = get("/api/catalog/25544");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("norad_cat_id").asLong()).isEqualTo(25544);
        assertThat(body.get("epoch_text").asString()).isEqualTo("2026-09-27T04:10:50.460096");
        assertThat(fields(body)).hasSize(23);
    }

    @Test
    void aCatalogNumberThatIsUnknownOrNotACatalogNumberIsAProblem() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.catalog_object WHERE norad_cat_id = 999999999");
        assertProblem(get("/api/catalog/999999999"), 404, "/api/catalog/999999999");
        assertProblem(get("/api/catalog/1000000000"), 400, "/api/catalog/1000000000");
        assertProblem(get("/api/catalog/-1"), 400, "/api/catalog/-1");
        assertProblem(get("/api/catalog/abc"), 400, "/api/catalog/abc");
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

    static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    static void emptyScreening() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.screening_run_approach; DELETE FROM spaceflux.screening_run_suppressed; "
                + "DELETE FROM spaceflux.screening_run_rejected; DELETE FROM spaceflux.screening_run_not_screened; "
                + "DELETE FROM spaceflux.screening_run; DELETE FROM spaceflux.close_approach");
    }

    static String escaped(String text) {
        return text.replace("\\", "\\\\").replace("'", "''");
    }

    /** Stores a close approach of the run, its distance spelled as no re-encoding writes it; returns its event_id. */
    static String approach(String runId) throws Exception {
        String text = java.nio.file.Files.readString(EXAMPLES.resolve("valid-close-approach.json"));
        String id = "close_approach/1/" + runId + "/57036/27958/" + SEQ.incrementAndGet();
        String payload = text.replace("close_approach/1/2026-09-29T05:20:09Z/1/57036/27958/2026-09-30T03:34:37.588Z",
                id).replace("2026-09-29T05:20:09Z/1", runId).replace("1973.3", "1.9733e3");
        TestMysql.rootSql("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, rules_version, "
                + "produced_at, source_partition, source_offset, payload) VALUES ('" + id + "', 'close_approach', 1, "
                + "1, '2026-10-04 00:00:00', 0, 0, '" + escaped(payload) + "'); "
                + "INSERT INTO spaceflux.close_approach VALUES (LAST_INSERT_ID(), 1, '" + runId + "', "
                + "'2026-09-29 05:20:09', '2026-10-06 05:20:09', 57036, 'OBJECT AJ', 1.5585, 27958, 'SL-12 DEB', "
                + "4.1985, '2026-09-30 03:34:37.588', 1973.3, 15727)");
        return id;
    }

    /**
     * Stores a run summary with the given approach ids listed. {@code omittedIds} null leaves out the omitted object;
     * the report distance is spelled as no re-encoding writes it. Returns the run_id.
     */
    static String run(Instant windowStart, int rulesVersion, Instant windowEnd, Integer omittedIds, int approachCount,
            java.util.List<String> listed) throws Exception {
        // The run_id keeps the instant's full precision, so runs of different tests never share one.
        String ws = windowStart.toString();
        String runId = runIdFor(windowStart, rulesVersion);
        tools.jackson.databind.node.ObjectNode event = (tools.jackson.databind.node.ObjectNode) JSON.readTree(
                java.nio.file.Files.readString(EXAMPLES.resolve("valid-screening-run.json")));
        event.put("event_id", "screening_run/" + rulesVersion + "/" + runId);
        event.put("rules_version", rulesVersion);
        tools.jackson.databind.node.ObjectNode summary = (tools.jackson.databind.node.ObjectNode) event
                .get("screening_run");
        summary.put("run_id", runId);
        summary.put("window_start", ws);
        summary.put("window_end", windowEnd.truncatedTo(ChronoUnit.SECONDS).toString());
        summary.put("approach_count", approachCount);
        tools.jackson.databind.node.ArrayNode ids = summary.putArray("approach_event_ids");
        listed.forEach(ids::add);
        if (omittedIds == null) {
            summary.remove("omitted");
        } else {
            ((tools.jackson.databind.node.ObjectNode) summary.get("omitted")).put("approach_event_ids", omittedIds);
        }
        String payload = JSON.writeValueAsString(event).replace("\"report_distance_m\":5000", "\"report_distance_m\":5.0e3");
        StringBuilder sql = new StringBuilder("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, "
                + "rules_version, produced_at, source_partition, source_offset, payload) VALUES ('screening_run/"
                + rulesVersion + "/" + runId + "', 'screening_run', 1, " + rulesVersion + ", '2026-10-04 00:00:00', 0, "
                + "0, '" + escaped(payload) + "'); INSERT INTO spaceflux.screening_run VALUES (LAST_INSERT_ID(), "
                + rulesVersion + ", '" + runId + "', " + sql(windowStart) + ", " + sql(windowEnd) + ", "
                + sql(windowStart) + ", 5000, 1, 2, 1, 0, 0, 1, " + approachCount + ", " + omittedIds
                + (omittedIds == null ? ", NULL, NULL, NULL, NULL, NULL, NULL" : ", 0, 0, 0, 0, 0, 0")
                + "); SET @run = LAST_INSERT_ID();");
        for (int i = 0; i < listed.size(); i++) {
            sql.append(" INSERT INTO spaceflux.screening_run_approach VALUES (@run, ").append(i).append(", '")
                    .append(listed.get(i)).append("');");
        }
        TestMysql.rootSql(sql.toString());
        return runId;
    }

    static String runIdFor(Instant windowStart, int rulesVersion) {
        return windowStart + "/" + rulesVersion;
    }

    @Test
    void withNoCompleteRunTheCurrentRunIsNotFound() throws Exception {
        emptyScreening();
        assertProblem(get("/api/screening/current"), 404, "/api/screening/current");

        Instant ws = Instant.now().minus(1, ChronoUnit.HOURS);
        run(ws, 1, ws.plus(7, ChronoUnit.DAYS), 0, 1, java.util.List.of("close_approach/1/never/stored"));

        assertProblem(get("/api/screening/current"), 404, "/api/screening/current");
    }

    @Test
    void theCurrentRunIsTheNewestCompleteOneWithItsSummaryAndApproachesAsReceived() throws Exception {
        emptyScreening();
        Instant older = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant newer = Instant.now().minus(1, ChronoUnit.HOURS);
        String first = approach(runIdFor(older, 1));
        String second = approach(runIdFor(older, 1));
        String runId = run(older, 1, older.plus(7, ChronoUnit.DAYS), 0, 2, java.util.List.of(second, first));
        TestMysql.rootSql("INSERT INTO spaceflux.alert_acknowledgement (event_id, action, principal, note) VALUES ('"
                + first + "', 'acknowledge', 'operator', 'seen it')");
        // A newer run whose approach has not arrived is not complete, so it is not current.
        run(newer, 1, newer.plus(7, ChronoUnit.DAYS), 0, 1, java.util.List.of("close_approach/1/late/" + SEQ.get()));

        HttpResponse<String> r = get("/api/screening/current");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(fields(body)).containsExactlyInAnyOrder("stale", "summary", "approaches");
        assertThat(body.get("stale").asBoolean()).isFalse();
        assertThat(body.get("summary").get("run_id").asString()).isEqualTo(runId);
        assertThat(r.body()).contains("\"report_distance_m\":5.0e3").contains("\"miss_distance_m\": 1.9733e3");
        JsonNode approaches = body.get("approaches");
        assertThat(approaches).hasSize(2);
        assertThat(approaches.get(0).get("event_id").asString()).isEqualTo(second);
        assertThat(fields(approaches.get(0))).containsExactlyInAnyOrder("event_id", "close_approach");
        assertThat(approaches.get(1).get("event_id").asString()).isEqualTo(first);
        assertThat(approaches.get(1).get("close_approach").get("run_id").asString()).isEqualTo(runId);
        assertThat(fields(approaches.get(1).get("acknowledgement"))).containsExactlyInAnyOrder("action", "acted_at");
        assertThat(r.body()).doesNotContain("operator").doesNotContain("seen it");
    }

    @Test
    void anApproachOfARebuiltRunThatTheSummaryDoesNotListIsNotShown() throws Exception {
        emptyScreening();
        Instant ws = Instant.now().minus(2, ChronoUnit.HOURS);
        String listed = approach(runIdFor(ws, 1));
        approach(runIdFor(ws, 1));
        run(ws, 1, ws.plus(7, ChronoUnit.DAYS), null, 1, java.util.List.of(listed));

        JsonNode approaches = JSON.readTree(get("/api/screening/current").body()).get("approaches");

        assertThat(approaches).singleElement()
                .satisfies(a -> assertThat(a.get("event_id").asString()).isEqualTo(listed));
    }

    @Test
    void aRunWhoseIdsWereCutIsCompleteOnceItHoldsApproachCountApproaches() throws Exception {
        emptyScreening();
        Instant ws = Instant.now().minus(2, ChronoUnit.HOURS);
        String runId = runIdFor(ws, 1);
        String a = approach(runId);
        run(ws, 1, ws.plus(7, ChronoUnit.DAYS), 1, 2, java.util.List.of(a));
        assertProblem(get("/api/screening/current"), 404, "/api/screening/current");

        String b = approach(runId);
        JsonNode approaches = JSON.readTree(get("/api/screening/current").body()).get("approaches");

        assertThat(approaches).hasSize(2);
        assertThat(approaches.get(0).get("event_id").asString()).isEqualTo(a);
        assertThat(approaches.get(1).get("event_id").asString()).isEqualTo(b);
    }

    /** Stores {@code count} runs newer than {@code after}, each listing an approach that never arrives. */
    static void incompleteRuns(Instant after, int count) throws Exception {
        for (int i = 1; i <= count; i++) {
            Instant ws = after.plus(i, ChronoUnit.MINUTES);
            run(ws, 1, ws.plus(7, ChronoUnit.DAYS), 0, 1, java.util.List.of("close_approach/1/never/" + SEQ
                    .incrementAndGet()));
        }
    }

    @Test
    void theSearchForTheCurrentRunReadsPastMoreIncompleteRunsThanOneBatch() throws Exception {
        emptyScreening();
        Instant ws = Instant.now().minus(3, ChronoUnit.HOURS);
        String runId = run(ws, 1, ws.plus(7, ChronoUnit.DAYS), 0, 0, java.util.List.of());
        incompleteRuns(ws, ScreeningController.SCAN_BATCH + 1);

        assertThat(JSON.readTree(get("/api/screening/current").body()).get("summary").get("run_id").asString())
                .isEqualTo(runId);
    }

    @Test
    void theSearchForTheCurrentRunStopsAfterItsLimit() throws Exception {
        emptyScreening();
        Instant ws = Instant.now().minus(3, ChronoUnit.HOURS);
        run(ws, 1, ws.plus(7, ChronoUnit.DAYS), 0, 0, java.util.List.of());
        incompleteRuns(ws, ScreeningController.SCAN_LIMIT - 1);
        assertThat(get("/api/screening/current").statusCode()).isEqualTo(200);

        incompleteRuns(ws.plus(1, ChronoUnit.HOURS), 1);

        assertProblem(get("/api/screening/current"), 404, "/api/screening/current");
    }

    @Test
    void runsWithTheSameWindowStartPickTheHigherRulesVersion() throws Exception {
        emptyScreening();
        Instant ws = Instant.now().minus(2, ChronoUnit.HOURS);
        run(ws, 2, ws.plus(7, ChronoUnit.DAYS), 0, 0, java.util.List.of());
        run(ws, 1, ws.plus(7, ChronoUnit.DAYS), 0, 0, java.util.List.of());

        JsonNode body = JSON.readTree(get("/api/screening/current").body());

        assertThat(body.get("summary").get("run_id").asString()).isEqualTo(runIdFor(ws, 2));
        assertThat(body.get("approaches")).isEmpty();
    }

    @Test
    void aRunIsStaleMoreThanADayAfterItsWindowStartOrPastItsWindowEnd() throws Exception {
        emptyScreening();
        Instant old = Instant.now().minus(25, ChronoUnit.HOURS);
        run(old, 1, old.plus(7, ChronoUnit.DAYS), 0, 0, java.util.List.of());
        assertThat(JSON.readTree(get("/api/screening/current").body()).get("stale").asBoolean()).isTrue();

        Instant recent = Instant.now().minus(23, ChronoUnit.HOURS);
        run(recent, 1, recent.plus(7, ChronoUnit.DAYS), 0, 0, java.util.List.of());
        assertThat(JSON.readTree(get("/api/screening/current").body()).get("stale").asBoolean()).isFalse();

        Instant ended = Instant.now().minus(2, ChronoUnit.HOURS);
        run(ended, 1, Instant.now().minus(1, ChronoUnit.MINUTES), 0, 0, java.util.List.of());
        assertThat(JSON.readTree(get("/api/screening/current").body()).get("stale").asBoolean()).isTrue();
    }

    /** Stores one space weather event and its alert_event row; {@code key} is interval_start for G, else sample_time. */
    static String swEvent(String scale, Integer satellite, String state, String label, Integer level, Double value,
            String trigger, String key, String extraColumns, String extraValues) throws Exception {
        String id = "space_weather_level/1/" + scale + "/" + SEQ.incrementAndGet();
        boolean g = "G".equals(scale);
        String keyColumns = g ? "interval_start, interval_end" : "sample_time";
        String keyValues = g ? "'" + key + "', DATE_ADD('" + key + "', INTERVAL 3 HOUR)" : "'" + key + "'";
        TestMysql.rootSql("INSERT INTO spaceflux.alert_event (event_id, kind, schema_version, rules_version, "
                + "produced_at, source_partition, source_offset, payload) VALUES ('" + id + "', 'space_weather_level', "
                + "1, 1, '2026-10-04 00:00:00', 0, 0, '{}'); INSERT INTO spaceflux.space_weather_event (alert_seq, "
                + "rules_version, scale, satellite, product, state, derived_level, derived_label, trigger_kind, "
                + "derived_from, estimated, unit, value, time_tag, " + keyColumns + extraColumns + ") VALUES "
                + "(LAST_INSERT_ID(), 1, '" + scale + "', " + satellite + ", '"
                + (g ? "swpc.kp" : "R".equals(scale) ? "swpc.goes.xrays" : "swpc.goes.protons") + "', '" + state
                + "', " + level + ", '" + label + "', '" + trigger + "', 'measurement', " + g + ", '"
                + (g ? "Kp index" : "R".equals(scale) ? "W m-2" : "pfu") + "', " + value + ", '"
                + key.replace(" ", "T") + (g ? "" : "Z") + "', " + keyValues + extraValues + ")");
        return id;
    }

    static final String DAY = "&from=2031-01-01T00:00:00Z&to=2031-01-02T00:00:00Z";

    @Test
    void historyGivesTheLatestEventOfEachIntervalInTheRangeOldestFirst() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_event");
        swEvent("G", null, "level", "G1", 1, 5.0, "level_change", "2030-12-31 21:00:00", "", "");
        swEvent("G", null, "none", "none", null, 3.33, "level_change", "2031-01-01 03:00:00", "", "");
        swEvent("G", null, "level", "G4", 4, 7.67, "level_change", "2031-01-01 00:00:00", "", "");
        String revised = swEvent("G", null, "level", "G3", 3, 7.0, "revision", "2031-01-01 00:00:00", "", "");
        swEvent("G", null, "level", "G2", 2, 6.0, "level_change", "2031-01-02 00:00:00", "", "");

        HttpResponse<String> r = get("/api/space-weather/history?scale=G" + DAY);

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(fields(body)).containsExactlyInAnyOrder("scale", "items");
        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        JsonNode first = items.get(0);
        assertThat(fields(first)).containsExactlyInAnyOrder("interval_start", "interval_end", "time_tag", "state",
                "derived_level", "derived_label", "value", "unit", "trigger", "event_id");
        assertThat(first.get("event_id").asString()).isEqualTo(revised);
        assertThat(first.get("derived_label").asString()).isEqualTo("G3");
        assertThat(first.get("trigger").asString()).isEqualTo("revision");
        assertThat(first.get("interval_start").asString()).isEqualTo("2031-01-01T00:00:00.000000Z");
        assertThat(first.get("interval_end").asString()).isEqualTo("2031-01-01T03:00:00.000000Z");
        assertThat(first.get("time_tag").asString()).isEqualTo("2031-01-01T00:00:00");
        assertThat(items.get(1).get("state").asString()).isEqualTo("none");
    }

    @Test
    void historyOfAGoesSeriesKeepsToItsSatelliteAndShowsWhyASampleHasNoData() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_event");
        swEvent("R", 18, "level", "R1", 1, 1.1e-05, "level_change", "2031-01-01 08:25:00", ", xray_class",
                ", 'M1.1'");
        swEvent("R", 18, "none", "none", null, 8.48e-09, "level_change", "2031-01-01 08:26:00", "", "");
        String restated = swEvent("R", 18, "no_data", "no data", null, 8.48e-09, "restatement",
                "2031-01-01 08:26:00", ", no_data_reason, no_data_since", ", 'zero_run_edge', '2031-01-01 08:26:00'");
        swEvent("R", 19, "level", "R2", 2, 6.0e-05, "level_change", "2031-01-01 08:27:00", "", "");
        // Another satellite's newer event at the same sample time does not replace this satellite's state.
        swEvent("R", 19, "level", "R2", 2, 6.0e-05, "level_change", "2031-01-01 08:26:00", "", "");

        JsonNode body = JSON.readTree(get("/api/space-weather/history?scale=R&satellite=18" + DAY).body());

        assertThat(fields(body)).containsExactlyInAnyOrder("scale", "satellite", "items");
        assertThat(body.get("satellite").asInt()).isEqualTo(18);
        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("xray_class").asString()).isEqualTo("M1.1");
        JsonNode sample = items.get(1);
        assertThat(fields(sample)).containsExactlyInAnyOrder("sample_time", "time_tag", "state", "derived_label",
                "value", "unit", "trigger", "event_id", "no_data_reason", "no_data_since");
        assertThat(sample.get("event_id").asString()).isEqualTo(restated);
        assertThat(sample.get("no_data_reason").asString()).isEqualTo("zero_run_edge");
        assertThat(sample.get("no_data_since").asString()).isEqualTo("2031-01-01T08:26:00.000000Z");
        assertThat(sample.get("sample_time").asString()).isEqualTo("2031-01-01T08:26:00.000000Z");
    }

    @Test
    void historyIsPagedByAnOpaqueCursor() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_event");
        for (int minute = 0; minute < 5; minute++) {
            swEvent("S", 18, "none", "none", null, 0.5, "level_change", "2031-01-01 10:0" + minute + ":00", "", "");
        }

        JsonNode page1 = JSON.readTree(get("/api/space-weather/history?scale=S&satellite=18&limit=2" + DAY).body());
        assertThat(page1.get("items")).hasSize(2);
        String next = page1.get("next").asString();
        JsonNode page2 = JSON.readTree(get("/api/space-weather/history?scale=S&satellite=18&limit=2" + DAY + "&after="
                + query(next)).body());
        JsonNode page3 = JSON.readTree(get("/api/space-weather/history?scale=S&satellite=18&limit=2" + DAY + "&after="
                + query(page2.get("next").asString())).body());

        assertThat(page2.get("items").get(0).get("sample_time").asString()).isEqualTo("2031-01-01T10:02:00.000000Z");
        assertThat(page3.get("items")).singleElement()
                .satisfies(i -> assertThat(i.get("sample_time").asString()).isEqualTo("2031-01-01T10:04:00.000000Z"));
        assertThat(page3.has("next")).isFalse();
        JsonNode whole = JSON.readTree(get("/api/space-weather/history?scale=S&satellite=18" + DAY).body());
        assertThat(whole.get("items")).hasSize(5);
        assertThat(whole.has("next")).isFalse();
        JsonNode full = JSON.readTree(get("/api/space-weather/history?scale=S&satellite=18&limit=5" + DAY).body());
        assertThat(full.get("items")).hasSize(5);
        assertThat(full.has("next")).isFalse();
    }

    @Test
    void aHistoryRequestOutsideTheRulesIsABadRequest() throws Exception {
        String path = "/api/space-weather/history";
        for (String q : new String[] {"?" + DAY.substring(1), "?scale=X" + DAY, "?scale=G&satellite=18" + DAY,
                "?scale=R" + DAY, "?scale=R&satellite=0" + DAY, "?scale=G&to=2031-01-02T00:00:00Z",
                "?scale=G&from=2031-01-01T00:00:00Z", "?scale=G&from=2031-01-02T00:00:00Z&to=2031-01-01T00:00:00Z",
                "?scale=G&from=2031-01-01T00:00:00Z&to=2031-01-01T00:00:00Z",
                "?scale=G&from=2031-01-01T00:00:00Z&to=2031-01-08T00:00:01Z", "?scale=G&from=yesterday&to=today",
                "?scale=G&limit=0" + DAY, "?scale=G&limit=201" + DAY, "?scale=G&after=not-a-cursor" + DAY,
                "?scale=G&after=" + query(java.util.Base64.getUrlEncoder().encodeToString("x".getBytes())) + DAY,
                "?scale=G&from=" + query("+1000000000-12-31T23:59:59Z") + "&to=" + query("+1000000000-12-31T23:59:59Z"),
                "?scale=G&from=0999-12-31T00:00:00Z&to=1000-01-01T00:00:00Z",
                "?scale=G&from=9999-12-31T00:00:00Z&to=" + query("+10000-01-01T00:00:00Z"),
                "?scale=G&after=" + query(java.util.Base64.getUrlEncoder().encodeToString(
                        "+10000-01-01T00:00:00".getBytes())) + DAY}) {
            assertProblem(get(path + q), 400, path);
        }
        assertThat(get(path + "?scale=G&from=2031-01-01T00:00:00Z&to=2031-01-08T00:00:00Z").statusCode())
                .isEqualTo(200);
        assertThat(get(path + "?scale=G&limit=200" + DAY).statusCode()).isEqualTo(200);
        assertThat(get(path + "?scale=G&from=1000-01-01T00:00:00Z&to=1000-01-02T00:00:00Z").statusCode())
                .isEqualTo(200);
    }
}
