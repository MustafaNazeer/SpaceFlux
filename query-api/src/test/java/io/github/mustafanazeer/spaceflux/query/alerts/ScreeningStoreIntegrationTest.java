package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** close_approach and screening_run events through the processor and the real consumer store. */
class ScreeningStoreIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    static ConfigurableApplicationContext app;
    static AlertsProcessor processor;
    static JdbcClient db;

    @BeforeAll
    static void start() {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args("--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=false"));
        processor = new AlertsProcessor(TopicSchemas.fromClasspath(), app.getBean(AlertStore.class));
        db = app.getBean("apiJdbcClient", JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    /** A run identity of its own: the window start gets a random fraction, so run_id and event_id are fresh. */
    static String freshWindowStart() {
        return String.format("2026-09-29T07:30:00.%06dZ", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    static ObjectNode run(String file, String windowStart) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
        ObjectNode p = (ObjectNode) e.get("screening_run");
        p.put("window_start", windowStart);
        p.put("input_fetched_at", windowStart);
        p.put("run_id", windowStart + "/1");
        e.put("event_id", "screening_run/1/" + windowStart + "/1");
        return e;
    }

    static ObjectNode approach(String windowStart) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve("valid-close-approach.json")));
        ObjectNode p = (ObjectNode) e.get("close_approach");
        p.put("window_start", windowStart);
        p.put("run_id", windowStart + "/1");
        e.put("event_id", "close_approach/1/" + windowStart + "/1/57036/27958/2026-09-30T03:34:37.588Z");
        return e;
    }

    static AlertsProcessor.Outcome process(ObjectNode e) {
        return processor.process(new AlertsProcessor.In("k", JSON.writeValueAsBytes(e), 0, 0), NOW);
    }

    static long seq(ObjectNode e) {
        return db.sql("SELECT alert_seq FROM alert_event WHERE event_id = ?").param(e.get("event_id").asString())
                .query(Long.class).single();
    }

    @Test
    void aCloseApproachIsStoredColumnByColumn() throws Exception {
        String start = freshWindowStart();
        ObjectNode e = approach(start);

        assertThat(process(e)).isEqualTo(AlertsProcessor.Outcome.STORED);

        Map<String, Object> row = db.sql("SELECT * FROM close_approach WHERE alert_seq = ?").param(seq(e)).query()
                .singleRow();
        assertThat(row).containsEntry("run_id", start + "/1").containsEntry("watchlist_name", "OBJECT AJ")
                .containsEntry("other_name", "SL-12 DEB").containsEntry("watchlist_element_age_days", 1.5585)
                .containsEntry("other_element_age_days", 4.1985).containsEntry("miss_distance_m", 1973.3)
                .containsEntry("relative_speed_m_per_s", 15727.0)
                .containsEntry("window_end", LocalDateTime.of(2026, 10, 6, 5, 20, 9))
                .containsEntry("time_of_closest_approach", LocalDateTime.of(2026, 9, 30, 3, 34, 37, 588_000_000));
        assertThat(((Number) row.get("watchlist_number")).longValue()).isEqualTo(57036);
        assertThat(((Number) row.get("other_number")).longValue()).isEqualTo(27958);
        assertThat(((Number) row.get("rules_version")).longValue()).isEqualTo(1);
    }

    @Test
    void aCutSummaryIsStoredWithItsCountsAndItsListsInOrder() throws Exception {
        ObjectNode e = run("valid-screening-run-cut.json", freshWindowStart());

        assertThat(process(e)).isEqualTo(AlertsProcessor.Outcome.STORED);

        long s = seq(e);
        Map<String, Object> row = db.sql("SELECT * FROM screening_run WHERE alert_seq = ?").param(s).query()
                .singleRow();
        assertThat(row).containsEntry("report_distance_m", 5000.0);
        assertThat(((Number) row.get("pairs_searched")).longValue()).isEqualTo(4);
        assertThat(((Number) row.get("omitted_suppressed")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("omitted_differing_copies")).longValue()).isEqualTo(1);
        assertThat(db.sql("SELECT catalog_number FROM screening_run_rejected WHERE run_alert_seq = ? "
                + "ORDER BY position").param(s).query(Long.class).list()).containsExactly(99999L, 43205L);
        assertThat(db.sql("SELECT position, mechanism, stack_entry_may_be_stale FROM screening_run_suppressed "
                + "WHERE run_alert_seq = ?").param(s).query().listOfRows()).singleElement().satisfies(r -> {
                    assertThat(((Number) r.get("position")).intValue()).isZero();
                    assertThat(r).containsEntry("mechanism", "static_stack")
                            .containsEntry("stack_entry_may_be_stale", false);
                });
    }

    @Test
    void aSummaryWithoutOmittedHasNullOmittedCountsAndItsApproachIdsInOrder() throws Exception {
        ObjectNode e = run("valid-screening-run.json", freshWindowStart());
        ((ObjectNode) e.get("screening_run")).remove("omitted");
        ArrayNode ids = (ArrayNode) e.get("screening_run").get("approach_event_ids");
        ids.removeAll();
        ids.add("close_approach/1/b");
        ids.add("close_approach/1/a");
        ((ObjectNode) e.get("screening_run")).put("approach_count", 2);

        assertThat(process(e)).isEqualTo(AlertsProcessor.Outcome.STORED);

        long s = seq(e);
        assertThat(db.sql("SELECT omitted_suppressed FROM screening_run WHERE alert_seq = ?").param(s)
                .query(Long.class).optional()).isEmpty();
        assertThat(db.sql("SELECT approach_event_id FROM screening_run_approach WHERE run_alert_seq = ? "
                + "ORDER BY position").param(s).query(String.class).list())
                .containsExactly("close_approach/1/b", "close_approach/1/a");
    }

    @Test
    void aSecondEventClaimingAStoredRunIdIsDeadLetteredAndStoresNothing() throws Exception {
        String start = freshWindowStart();
        process(run("valid-screening-run.json", start));
        ObjectNode forged = run("valid-screening-run.json", start);
        forged.put("event_id", forged.get("event_id").asString() + "/again");

        AlertsProcessor.Outcome outcome = process(forged);

        assertThat(outcome).isInstanceOf(AlertsProcessor.Outcome.DeadLetter.class);
        String reason = JSON.readTree(((AlertsProcessor.Outcome.DeadLetter) outcome).message().value())
                .get("reason").asString();
        assertThat(reason).contains("run_id");
        assertThat(db.sql("SELECT COUNT(*) FROM alert_event WHERE event_id = ?")
                .param(forged.get("event_id").asString()).query(Integer.class).single()).isZero();
    }

    @Test
    void aLargeSummaryStoresEveryListEntry() throws Exception {
        ObjectNode e = run("valid-screening-run-cut.json", freshWindowStart());
        ArrayNode suppressed = (ArrayNode) e.get("screening_run").get("suppressed");
        ObjectNode first = (ObjectNode) suppressed.get(0);
        // About 1,200 entries of this size come to the producer's 900,000 byte budget for one summary.
        for (int i = 1; i < 1_200; i++) {
            suppressed.add(first.deepCopy().put("other_number", 100_000 + i));
        }
        byte[] value = JSON.writeValueAsBytes(e);
        assertThat(value.length).isLessThan(900_000);

        AlertsProcessor.Outcome outcome = processor.process(new AlertsProcessor.In("k", value, 0, 0), NOW);

        assertThat(outcome).as("%s", outcome instanceof AlertsProcessor.Outcome.DeadLetter(var d)
                ? JSON.readTree(d.value()).get("reason").asString() : "").isEqualTo(AlertsProcessor.Outcome.STORED);

        List<Long> positions = db.sql("SELECT position FROM screening_run_suppressed WHERE run_alert_seq = ? "
                + "ORDER BY position").param(seq(e)).query(Long.class).list();
        assertThat(positions).hasSize(1_200).startsWith(0L, 1L).endsWith(1_199L);
    }
}
