package io.github.mustafanazeer.spaceflux.query.plans;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import io.github.mustafanazeer.spaceflux.query.ack.AckPlanQueries;
import io.github.mustafanazeer.spaceflux.query.alerts.AlertsPlanFeed;
import io.github.mustafanazeer.spaceflux.query.catalog.CatalogPlanFeed;
import io.github.mustafanazeer.spaceflux.query.passes.PassesPlanQueries;
import io.github.mustafanazeer.spaceflux.query.read.PlanQueries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every query the service runs today, explained as the account that runs it over seeded data, must use the index
 * docs/data/indexes.md names for it and read no table that grows with time by a full scan. The plans profile seeds
 * a year of both and writes the plans to docs/data/plans. The build seeds a week of space weather and a year of
 * screening runs: with too few runs the optimizer rightly sorts the whole run table instead of reading the window
 * index backward, which would prove nothing for the run search.
 */
class QueryPlansIntegrationTest {

    static final int BUILD_SPACE_WEATHER_DAYS = 7;
    static final int BUILD_RUN_DAYS = 365;
    static final Path OUT = Path.of("..", "docs", "data", "plans");
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> TABLES = List.of("alert_acknowledgement", "screening_run_approach",
            "screening_run_suppressed", "screening_run_rejected", "screening_run_not_screened", "screening_run",
            "close_approach", "space_weather_event", "space_weather_series", "alert_event", "catalog_object");

    static ConfigurableApplicationContext app;
    static JdbcClient api;
    static JdbcClient consumer;
    static PlanData.Seeded seeded;

    /**
     * One query as it runs: the account, the SQL, its parameters, whether it may sort, and every table it must read,
     * each as "table access_type index", with a fourth word for a range read naming the column the range is on.
     * {@code bounded}, when set, is text the tree plan must show, so a range keeps both of its bounds.
     */
    record Plan(String name, String account, String sql, List<Object> params, boolean maySort, String bounded,
            String... reads) {

        @Override
        public String toString() {
            return name;
        }
    }

    @BeforeAll
    static void seed() throws Exception {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args("--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=false"));
        api = app.getBean("apiJdbcClient", JdbcClient.class);
        consumer = app.getBean("consumerJdbcClient", JdbcClient.class);
        // Other test classes share this database; the plans are taken over this data alone.
        TestMysql.rootSql(String.join("; ", TABLES.stream().map(t -> "DELETE FROM spaceflux." + t).toList()));
        // Each consumer transaction waits on a log flush; seeding skips that wait, and plans do not depend on it.
        TestMysql.rootSql("SET GLOBAL innodb_flush_log_at_trx_commit = 2");
        try {
            seeded = new PlanData(new AlertsPlanFeed(app), new CatalogPlanFeed(app), api).seed(
                            Integer.getInteger("spaceflux.plans.spaceWeatherDays", BUILD_SPACE_WEATHER_DAYS),
                            Integer.getInteger("spaceflux.plans.runDays", BUILD_RUN_DAYS));
        } finally {
            TestMysql.rootSql("SET GLOBAL innodb_flush_log_at_trx_commit = 1");
        }
        TestMysql.rootSql("ANALYZE TABLE " + String.join(", ", TABLES.stream().map(t -> "spaceflux." + t).toList()));
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
    }

    static Stream<Plan> plans() {
        String run = seeded.runIds().get(seeded.runIds().size() / 2);
        String cutRun = seeded.cutRunIds().get(seeded.cutRunIds().size() / 2);
        long runSeq = api.sql("SELECT alert_seq FROM screening_run WHERE run_id = ?").param(run).query(Long.class)
                .single();
        LocalDateTime runStart = api.sql("SELECT window_start FROM screening_run WHERE run_id = ?").param(run)
                .query(LocalDateTime.class).single();
        String acknowledged = api.sql("SELECT event_id FROM alert_acknowledgement ORDER BY ack_id LIMIT 1")
                .query(String.class).single();
        long ackId = api.sql("SELECT MAX(ack_id) FROM alert_acknowledgement WHERE event_id = ?").param(acknowledged)
                .query(Long.class).single();
        // A continuation cursor in the middle of the table: the oldest row's cursor makes the primary key range
        // nearly empty, which no real history page would see.
        long middle = api.sql("SELECT ack_id FROM alert_acknowledgement ORDER BY ack_id LIMIT 1 OFFSET ?")
                .param(seeded.acknowledgements() / 2).query(Long.class).single();
        String middleEvent = api.sql("SELECT event_id FROM alert_acknowledgement WHERE ack_id = ?").param(middle)
                .query(String.class).single();
        LocalDateTime from = LocalDateTime.of(2025, 1, 4, 0, 0);
        LocalDateTime to = from.plusDays(7);
        LocalDateTime cursor = from.plusDays(2);
        String g = "ix_space_weather_event_interval";
        String rs = "ix_space_weather_event_sample";
        String ack = "ix_alert_acknowledgement_event";
        return Stream.of(
                new Plan("space-weather-current", "api", PlanQueries.SPACE_WEATHER_CURRENT, List.of(), true, null,
                        "space_weather_series ALL none"),
                new Plan("space-weather-history-g", "api", PlanQueries.history(true, false),
                        List.of("G", from, to, 51), false, "<= interval_start <",
                        "e range " + g + " interval_start", "x ref " + g, "a eq_ref PRIMARY"),
                new Plan("space-weather-history-g-after", "api", PlanQueries.history(true, true),
                        List.of("G", from, to, cursor, 51), false, "< interval_start <",
                        "e range " + g + " interval_start", "x ref " + g, "a eq_ref PRIMARY"),
                new Plan("space-weather-history-r", "api", PlanQueries.history(false, false),
                        List.of("R", 18, from, to, 51), false, "<= sample_time <",
                        "e range " + rs + " sample_time", "x ref " + rs, "a eq_ref PRIMARY"),
                new Plan("space-weather-history-r-after", "api", PlanQueries.history(false, true),
                        List.of("R", 18, from, to, cursor, 51), false, "< sample_time <",
                        "e range " + rs + " sample_time", "x ref " + rs, "a eq_ref PRIMARY"),
                new Plan("screening-candidates", "api", PlanQueries.RUN_CANDIDATES, List.of(), false, null,
                        "r index ix_screening_run_window"),
                new Plan("screening-candidates-after", "api", PlanQueries.RUN_CANDIDATES_AFTER,
                        List.of(runStart, runStart, 1, 1, runSeq), false, null,
                        "r range ix_screening_run_window window_start"),
                new Plan("screening-listed-missing", "api", PlanQueries.RUN_LISTED_MISSING, List.of(runSeq), false,
                        null, "a ref PRIMARY", "e eq_ref uk_alert_event_event_id"),
                new Plan("screening-cut-count", "api", PlanQueries.RUN_CUT_COUNT, List.of(cutRun), false, null,
                        "close_approach ref ix_close_approach_run"),
                new Plan("screening-summary", "api", PlanQueries.RUN_SUMMARY, List.of(runSeq), false, null,
                        "alert_event const PRIMARY"),
                new Plan("screening-listed-approaches", "api", PlanQueries.RUN_LISTED_APPROACHES, List.of(runSeq),
                        false, null, "a ref PRIMARY", "e eq_ref uk_alert_event_event_id", "k eq_ref PRIMARY",
                        "x ref " + ack),
                new Plan("screening-cut-approaches", "api", PlanQueries.RUN_CUT_APPROACHES, List.of(cutRun), false,
                        null, "c ref ix_close_approach_run", "e eq_ref PRIMARY", "k eq_ref PRIMARY", "x ref " + ack),
                new Plan("alert-by-id", "api", PlanQueries.ALERT_BY_ID, List.of(acknowledged), false, null,
                        "alert_event const uk_alert_event_event_id"),
                new Plan("alert-latest-acknowledgement", "api", PlanQueries.LATEST_ACKNOWLEDGEMENT,
                        List.of(acknowledged), false, null, "alert_acknowledgement ref " + ack),
                new Plan("ack-target", "api", AckPlanQueries.TARGET, List.of(acknowledged), false, null,
                        "e const uk_alert_event_event_id", "s const PRIMARY"),
                new Plan("ack-current", "api", AckPlanQueries.CURRENT, List.of(acknowledged), false, null,
                        "alert_acknowledgement ref " + ack),
                new Plan("ack-written", "api", AckPlanQueries.WRITTEN, List.of(ackId), false, null,
                        "alert_acknowledgement const PRIMARY"),
                new Plan("ack-exists", "api", AckPlanQueries.EXISTS, List.of(acknowledged), false, null,
                        "alert_event const uk_alert_event_event_id"),
                new Plan("ack-history", "api", AckPlanQueries.HISTORY, List.of(acknowledged, 51), false, null,
                        "alert_acknowledgement ref " + ack),
                new Plan("ack-history-after", "api", AckPlanQueries.HISTORY_AFTER, List.of(middleEvent, middle + 1,
                        51), false, null, "alert_acknowledgement range " + ack + " ack_id"),
                new Plan("catalog-by-number", "api", PlanQueries.CATALOG_BY_NUMBER, List.of(25544), false, null,
                        "c const PRIMARY"),
                new Plan("watchlist", "api", PlanQueries.WATCHLIST, List.of(), false, null, "w index PRIMARY",
                        "c eq_ref PRIMARY"),
                new Plan("watchlist-object-passes", "api", PassesPlanQueries.WATCHLIST_OBJECT, List.of(25544), false,
                        null, "w const PRIMARY", "c const PRIMARY"),
                new Plan("consumer-series-for-update", "consumer", AlertsPlanFeed.SERIES_FOR_UPDATE,
                        List.of("R", 19), false, null, "space_weather_series const PRIMARY"),
                new Plan("consumer-catalog-for-update", "consumer", CatalogPlanFeed.HELD_FOR_UPDATE, List.of(25544),
                        false, null, "catalog_object const PRIMARY"));
    }

    /** Each table the plan reads, from EXPLAIN FORMAT=JSON: name, access type, index, and the index columns used. */
    record Access(String table, String type, String key, List<String> parts) {

        String shape() {
            return table + " " + type + " " + (key == null ? "none" : key);
        }
    }

    static void collect(JsonNode node, List<Access> out) {
        if (node.isObject()) {
            if (node.has("table_name") && node.has("access_type")) {
                List<String> parts = new ArrayList<>();
                if (node.has("used_key_parts")) {
                    node.get("used_key_parts").forEach(k -> parts.add(k.asString()));
                }
                out.add(new Access(node.get("table_name").asString(), node.get("access_type").asString(),
                        node.has("key") ? node.get("key").asString() : null, parts));
            }
            node.properties().forEach(e -> collect(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(n -> collect(n, out));
        }
    }

    static String explain(Plan plan, String how) {
        JdbcClient db = "api".equals(plan.account()) ? api : consumer;
        return String.join("\n", db.sql(how + " " + plan.sql()).params(plan.params()).query(String.class).list())
                .strip();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plans")
    void eachQueryReadsEachTableAsIndexesMdSays(Plan plan) throws Exception {
        String json = explain(plan, "EXPLAIN FORMAT=JSON");
        List<Access> reads = new ArrayList<>();
        collect(JSON.readTree(json), reads);

        // Exactly these reads, so a table that falls back to a shorter index prefix, another index, or a scan fails.
        assertThat(reads.stream().map(Access::shape).toList()).as(plan + " " + reads).containsExactlyInAnyOrder(
                Stream.of(plan.reads()).map(r -> String.join(" ", List.of(r.split(" ")).subList(0, 3))).toArray(
                        String[]::new));
        for (String expected : plan.reads()) {
            String[] w = expected.split(" ");
            if (w.length == 4) {
                assertThat(reads).as(plan + " range on " + w[3]).anySatisfy(a -> {
                    assertThat(a.table()).isEqualTo(w[0]);
                    assertThat(a.parts()).contains(w[3]);
                });
            }
        }
        if (plan.bounded() != null) {
            assertThat(explain(plan, "EXPLAIN FORMAT=TREE")).as(plan + " range bounds").contains(plan.bounded());
        }
        // Q1 does sort, so its plan shows that this is how MySQL writes a sort and the check below can fail.
        if (plan.maySort()) {
            assertThat(json).as(plan + " sorts").contains("\"using_filesort\": true");
        } else {
            assertThat(json).as(plan + " sorts").doesNotContain("\"using_filesort\": true");
        }

        if (Boolean.getBoolean("spaceflux.plans.write")) {
            write(plan);
        }
    }

    static void write(Plan plan) throws Exception {
        Files.createDirectories(OUT);
        String version = api.sql("SELECT VERSION()").query(String.class).single();
        StringBuilder text = new StringBuilder()
                .append("Query: ").append(plan.name()).append('\n')
                .append("Account: spaceflux_").append(plan.account()).append('\n')
                .append("Parameters: ").append(plan.params()).append('\n')
                .append("Data from ").append(PlanData.START).append(", seed ").append(PlanData.SEED).append(": ")
                .append(seeded.spaceWeatherDays()).append(" days of space weather, ").append(seeded.runDays())
                .append(" days of screening runs; ").append(seeded.spaceWeatherEvents())
                .append(" space weather events, ").append(seeded.runs()).append(" screening runs, ")
                .append(seeded.approaches()).append(" close approaches, ").append(seeded.catalogObjects())
                .append(" catalog objects, ").append(seeded.acknowledgements()).append(" acknowledgements\n")
                .append("MySQL: ").append(version).append(", statistics from ANALYZE TABLE after seeding\n\n")
                .append("SQL:\n").append(plan.sql()).append("\n\n")
                .append("EXPLAIN FORMAT=TREE:\n").append(explain(plan, "EXPLAIN FORMAT=TREE")).append('\n');
        // The tree shows a single row lookup only as "Rows fetched before execution", so each read's index is listed.
        List<Access> reads = new ArrayList<>();
        collect(JSON.readTree(explain(plan, "EXPLAIN FORMAT=JSON")), reads);
        text.append("\nTables read, from EXPLAIN FORMAT=JSON (table, access type, index, index columns used):\n");
        reads.forEach(r -> text.append(r.table()).append(", ").append(r.type()).append(", ")
                .append(r.key() == null ? "none" : r.key()).append(", ")
                .append(r.parts().isEmpty() ? "none" : String.join(" ", r.parts())).append('\n'));
        if ("api".equals(plan.account())) {
            text.append("\nEXPLAIN ANALYZE, one run on ").append(System.getProperty("os.name")).append(' ')
                    .append(System.getProperty("os.arch")).append(" with ")
                    .append(Runtime.getRuntime().availableProcessors())
                    .append(" processors; the times describe this run only:\n")
                    .append(explain(plan, "EXPLAIN ANALYZE")).append('\n');
        }
        Files.writeString(OUT.resolve(plan.name() + ".txt"), text.toString(), StandardCharsets.UTF_8);
    }
}
