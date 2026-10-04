package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** space_weather_level events through the processor and the real consumer store. */
class SpaceWeatherStoreIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    static ConfigurableApplicationContext app;
    static AlertsProcessor processor;
    static AlertStore store;
    static JdbcClient db;

    @BeforeAll
    static void start() {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args());
        store = app.getBean(AlertStore.class);
        processor = new AlertsProcessor(TopicSchemas.fromClasspath(), store);
        db = app.getBean("apiJdbcClient", JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    /** An example with a fresh event_id and its payload changed; returns the event_id. */
    static ObjectNode event(String file, Consumer<ObjectNode> change) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
        e.put("event_id", e.get("event_id").asString() + "/" + UUID.randomUUID());
        change.accept((ObjectNode) e.get("space_weather_level"));
        return e;
    }

    static AlertsProcessor.Outcome process(ObjectNode e) {
        return processor.process(new AlertsProcessor.In("k", JSON.writeValueAsBytes(e), 0, 0), NOW);
    }

    static long seq(ObjectNode e) {
        return db.sql("SELECT alert_seq FROM alert_event WHERE event_id = ?").param(e.get("event_id").asString())
                .query(Long.class).single();
    }

    static Map<String, Object> series(String scale, int satellite) {
        return db.sql("SELECT * FROM space_weather_series WHERE scale = ? AND series_satellite = ?")
                .params(scale, satellite).query().singleRow();
    }

    @Test
    void aKpLevelIsStoredColumnByColumnAndStartsTheGSeries() throws Exception {
        // Other test classes share this database and write the G series too.
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series WHERE scale = 'G'");
        ObjectNode e = event("valid-g-level.json", p -> { });

        assertThat(process(e)).isEqualTo(AlertsProcessor.Outcome.STORED);

        Map<String, Object> row = db.sql("SELECT * FROM space_weather_event WHERE alert_seq = ?").param(seq(e))
                .query().singleRow();
        assertThat(row).containsEntry("scale", "G").containsEntry("product", "swpc.kp")
                .containsEntry("state", "level").containsEntry("derived_label", "G4")
                .containsEntry("previous_state", "none").containsEntry("trigger_kind", "level_change")
                .containsEntry("derived_from", "SWPC estimated planetary Kp").containsEntry("estimated", true)
                .containsEntry("value", 7.67).containsEntry("unit", "Kp index")
                .containsEntry("time_tag", "2024-05-10T15:00:00")
                .containsEntry("interval_start", LocalDateTime.of(2024, 5, 10, 15, 0))
                .containsEntry("interval_end", LocalDateTime.of(2024, 5, 10, 18, 0))
                .containsEntry("fetched_at", LocalDateTime.of(2026, 9, 27, 22, 5, 5))
                .containsEntry("freshness_reference", LocalDateTime.of(2024, 5, 10, 15, 0))
                .containsEntry("satellite", null).containsEntry("sample_time", null);
        assertThat(((Number) row.get("derived_level")).intValue()).isEqualTo(4);
        Map<String, Object> g = series("G", 0);
        assertThat(g).containsEntry("state", "level").containsEntry("derived_label", "G4")
                .containsEntry("interval_start", LocalDateTime.of(2024, 5, 10, 15, 0));
        assertThat(((Number) g.get("state_alert_seq")).longValue()).isEqualTo(seq(e));
        assertThat(((Number) g.get("last_alert_seq")).longValue()).isEqualTo(seq(e));
    }

    @Test
    void anXrayValueRoundTripsExactly() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series WHERE scale = 'R' AND series_satellite = 9100");
        ObjectNode e = event("valid-r-level.json", p -> p.put("satellite", 9100));

        process(e);

        assertThat(db.sql("SELECT value FROM space_weather_event WHERE alert_seq = ?").param(seq(e))
                .query(Double.class).single()).isEqualTo(1.0624149581417441e-05);
        assertThat(series("R", 9100)).containsEntry("xray_class", "M1.0")
                .containsEntry("value", 1.0624149581417441e-05);
    }

    @Test
    void aRestatementIsHistoryAndMovesOnlyTheSeriesFreshnessAndLastEvent() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series WHERE scale = 'R' AND series_satellite = 9101");
        ObjectNode noData = event("valid-r-no-data.json", p -> p.put("satellite", 9101));
        ObjectNode restated = event("valid-r-restatement.json", p -> p.put("satellite", 9101));

        process(noData);
        process(restated);

        Map<String, Object> r = series("R", 9101);
        assertThat(r).containsEntry("state", "no_data").containsEntry("no_data_reason", "rejected")
                .containsEntry("no_data_since", LocalDateTime.of(2026, 9, 24, 8, 22))
                .containsEntry("freshness_reference", LocalDateTime.of(2026, 9, 24, 8, 29));
        assertThat(((Number) r.get("state_alert_seq")).longValue()).isEqualTo(seq(noData));
        assertThat(((Number) r.get("last_alert_seq")).longValue()).isEqualTo(seq(restated));
        assertThat(db.sql("SELECT restated_by_time_tag FROM space_weather_event WHERE alert_seq = ?")
                .param(seq(restated)).query(LocalDateTime.class).single())
                .isEqualTo(LocalDateTime.of(2026, 9, 24, 8, 27));
    }

    @Test
    void aRestatementForASeriesWithNoRowIsStoredAsHistoryOnly() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series WHERE scale = 'R' AND series_satellite = 9102");
        ObjectNode restated = event("valid-r-restatement.json", p -> p.put("satellite", 9102));

        assertThat(process(restated)).isEqualTo(AlertsProcessor.Outcome.STORED);

        assertThat(db.sql("SELECT COUNT(*) FROM space_weather_event WHERE alert_seq = ?").param(seq(restated))
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT COUNT(*) FROM space_weather_series WHERE scale = 'R' AND series_satellite = 9102")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void whenTheSeriesCannotBeWrittenNothingOfTheEventIsStored() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.space_weather_series WHERE scale = 'S' AND series_satellite = 9103");
        process(event("valid-s-level.json", p -> p.put("satellite", 9103)));
        ObjectNode refresh = event("valid-s-level.json", p -> {
            p.put("satellite", 9103);
            p.put("trigger", "refresh");
            p.remove("previous_state");
        });
        TestMysql.rootSql("REVOKE UPDATE ON spaceflux.space_weather_series FROM 'spaceflux_consumer'@'%'");
        try {
            assertThatThrownBy(() -> process(refresh)).isNotInstanceOf(NotStorable.class);
            assertThat(db.sql("SELECT COUNT(*) FROM alert_event WHERE event_id = ?")
                    .param(refresh.get("event_id").asString()).query(Integer.class).single()).isZero();
        } finally {
            TestMysql.rootSql("GRANT UPDATE ON spaceflux.space_weather_series TO 'spaceflux_consumer'@'%'");
        }

        assertThat(process(refresh)).isEqualTo(AlertsProcessor.Outcome.STORED);
        assertThat(((Number) series("S", 9103).get("last_alert_seq")).longValue()).isEqualTo(seq(refresh));
    }
}
