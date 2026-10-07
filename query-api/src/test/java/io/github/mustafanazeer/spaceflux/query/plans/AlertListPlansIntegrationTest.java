package io.github.mustafanazeer.spaceflux.query.plans;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import io.github.mustafanazeer.spaceflux.query.alerts.AlertsPlanFeed;
import io.github.mustafanazeer.spaceflux.query.plans.QueryPlansIntegrationTest.Access;
import io.github.mustafanazeer.spaceflux.query.plans.QueryPlansIntegrationTest.Plan;

/**
 * The recent alerts list (Q3) and one object's approaches (Q6) over quiet space weather, where listed events are rare:
 * each must read every table through the index docs/data/indexes.md names, sort only the few rows its UNION parts
 * return, and return exactly the rows of the plain query it replaces, page by page. With
 * {@code -Dspaceflux.plans.write=true} the plans are written to docs/data/plans.
 */
class AlertListPlansIntegrationTest {

    static final int BUILD_DAYS = 30;
    static final int FETCH = 51;

    static ConfigurableApplicationContext app;
    static JdbcClient api;
    static AlertListPlanData.Seeded seeded;

    @BeforeAll
    static void seed() throws Exception {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args("--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=false"));
        api = app.getBean("apiJdbcClient", JdbcClient.class);
        List<String> tables = QueryPlansIntegrationTest.TABLES;
        TestMysql.rootSql(String.join("; ", tables.stream().map(t -> "DELETE FROM spaceflux." + t).toList()));
        TestMysql.rootSql("SET GLOBAL innodb_flush_log_at_trx_commit = 2");
        try {
            seeded = new AlertListPlanData(new AlertsPlanFeed(app), api)
                    .seed(Integer.getInteger("spaceflux.plans.alertListDays", BUILD_DAYS));
        } finally {
            TestMysql.rootSql("SET GLOBAL innodb_flush_log_at_trx_commit = 1");
        }
        TestMysql.rootSql("ANALYZE TABLE " + String.join(", ", tables.stream().map(t -> "spaceflux." + t).toList()));
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
    }

    /** The other object with the most approaches, so its first page has rows to read. */
    static int busiestOther() {
        return api.sql("SELECT other_number FROM close_approach GROUP BY other_number ORDER BY COUNT(*) DESC, "
                + "other_number LIMIT 1").query(Integer.class).single();
    }

    static Stream<Plan> plans() {
        List<Long> listed = api.sql(AlertListQueries.RECENT_BY_SCAN).params(Long.MAX_VALUE, Integer.MAX_VALUE)
                .query((rs, n) -> rs.getLong(1)).list();
        long before = listed.get(listed.size() / 2);
        record Key(LocalDateTime tca, long seq) {
        }
        List<Key> iss = api.sql("SELECT time_of_closest_approach, alert_seq FROM close_approach "
                + "WHERE watchlist_number = ? ORDER BY time_of_closest_approach DESC, alert_seq DESC")
                .param(AlertListPlanData.WATCHLIST)
                .query((rs, n) -> new Key(rs.getObject(1, LocalDateTime.class), rs.getLong(2))).list();
        Key middle = iss.get(iss.size() / 2);
        int other = busiestOther();
        int w = AlertListPlanData.WATCHLIST;
        String ack = "ix_alert_acknowledgement_event";
        String listedIndex = "ix_space_weather_event_listed";
        String[] eventAndAck = {"p ALL none", "e eq_ref PRIMARY", "k eq_ref PRIMARY", "x ref " + ack};
        // UNION, unlike UNION ALL, removes duplicates through a temporary table of the parts' rows.
        String[] approachesEventAndAck = with(eventAndAck, "<union2,3> ALL none");
        return Stream.of(
                new Plan("alerts-recent", "api", AlertListQueries.RECENT, List.of(FETCH, FETCH, FETCH), true, null,
                        with(eventAndAck, "c index PRIMARY", "w ref " + listedIndex)),
                new Plan("alerts-recent-after", "api", AlertListQueries.RECENT_AFTER,
                        List.of(before, FETCH, before, FETCH, FETCH), true, "alert_seq < " + before,
                        with(eventAndAck, "c range PRIMARY alert_seq", "w range " + listedIndex + " alert_seq")),
                new Plan("object-approaches", "api", AlertListQueries.OBJECT_APPROACHES,
                        List.of(w, FETCH, w, FETCH, FETCH), true, null,
                        with(approachesEventAndAck, "c ref ix_close_approach_watchlist", "c ref ix_close_approach_other")),
                new Plan("object-approaches-other", "api", AlertListQueries.OBJECT_APPROACHES,
                        List.of(other, FETCH, other, FETCH, FETCH), true, null,
                        with(approachesEventAndAck, "c ref ix_close_approach_watchlist", "c ref ix_close_approach_other")),
                new Plan("object-approaches-after", "api", AlertListQueries.OBJECT_APPROACHES_AFTER,
                        List.of(w, middle.tca(), middle.tca(), middle.seq(), FETCH, w, middle.tca(), middle.tca(),
                                middle.seq(), FETCH, FETCH), true, "time_of_closest_approach <= ",
                        with(approachesEventAndAck, "c range ix_close_approach_watchlist time_of_closest_approach",
                                "c range ix_close_approach_other time_of_closest_approach")));
    }

    static String[] with(String[] common, String... more) {
        return Stream.concat(Stream.of(more), Stream.of(common)).toArray(String[]::new);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plans")
    void eachListReadsEachTableAsIndexesMdSays(Plan plan) throws Exception {
        String json = explain(plan.sql(), plan.params(), "EXPLAIN FORMAT=JSON");
        List<Access> reads = new ArrayList<>();
        QueryPlansIntegrationTest.collect(QueryPlansIntegrationTest.JSON.readTree(json), reads);

        assertThat(reads.stream().map(Access::shape).toList()).as(plan + " " + reads).containsExactlyInAnyOrder(
                Stream.of(plan.reads()).map(r -> String.join(" ", List.of(r.split(" ")).subList(0, 3))).toArray(
                        String[]::new));
        for (String expected : plan.reads()) {
            String[] w = expected.split(" ");
            if (w.length == 4) {
                assertThat(reads).as(plan + " range on " + w[3] + " of " + w[2]).anySatisfy(a -> {
                    assertThat(a.table()).isEqualTo(w[0]);
                    assertThat(a.key()).isEqualTo(w[2]);
                    assertThat(a.parts()).contains(w[3]);
                });
            }
        }
        String tree = explain(plan.sql(), plan.params(), "EXPLAIN FORMAT=TREE");
        if (plan.bounded() != null) {
            assertThat(tree).as(plan + " range bounds").contains(plan.bounded());
        }
        // Each UNION part stops at its LIMIT, read backward from its index; only the outer merge sorts.
        assertThat(tree).as(plan + " parts read in index order").contains("(reverse)");
        assertThat(tree).as(plan + " each part stops at its limit").contains("Limit: " + FETCH + " row(s)");
        assertThat(json).as(plan + " sorts the merged parts").contains("\"using_filesort\": true");
        assertThat(count(tree, "Sort")).as(plan + " sorts once").isEqualTo(1);

        if (Boolean.getBoolean("spaceflux.plans.write")) {
            write(plan, json, tree);
        }
    }

    @Test
    void theRecentListReturnsThePlainScansRowsPageByPage() {
        List<List<String>> expected = api.sql(AlertListQueries.RECENT_BY_SCAN)
                .params(Long.MAX_VALUE, Integer.MAX_VALUE).query((rs, n) -> row(rs, 9)).list();
        assertThat(expected).as("listed alerts").hasSizeGreaterThan(2 * 7);
        assertThat(expected).as("some listed space weather").anyMatch(r -> "space_weather_level".equals(r.get(2)));
        assertThat(expected).as("some acknowledged").anyMatch(r -> r.get(5) != null);

        int page = 7;
        List<List<String>> walked = new ArrayList<>();
        List<List<String>> rows = api.sql(AlertListQueries.RECENT).params(page + 1, page + 1, page + 1)
                .query((rs, n) -> row(rs, 9)).list();
        while (true) {
            walked.addAll(rows.subList(0, Math.min(page, rows.size())));
            if (rows.size() <= page) {
                break;
            }
            long before = Long.parseLong(rows.get(page - 1).get(0));
            rows = api.sql(AlertListQueries.RECENT_AFTER).params(before, page + 1, before, page + 1, page + 1)
                    .query((rs, n) -> row(rs, 9)).list();
        }
        assertThat(walked).isEqualTo(expected);
    }

    @Test
    void anObjectsApproachesMatchTheOrQueryPageByPageThroughTies() {
        for (int object : List.of(AlertListPlanData.WATCHLIST, busiestOther())) {
            List<String> expected = api.sql(AlertListQueries.OBJECT_APPROACHES_BY_OR)
                    .params(object, object, LocalDateTime.of(9999, 1, 1, 0, 0), LocalDateTime.of(9999, 1, 1, 0, 0),
                            Long.MAX_VALUE, Integer.MAX_VALUE)
                    .query((rs, n) -> rs.getLong(1) + " " + rs.getObject(2, LocalDateTime.class)).list();
            assertThat(expected).as("approaches of " + object).isNotEmpty();
            if (object == AlertListPlanData.WATCHLIST) {
                long ties = api.sql("SELECT COUNT(*) FROM (SELECT time_of_closest_approach FROM close_approach "
                        + "WHERE watchlist_number = ? GROUP BY time_of_closest_approach HAVING COUNT(*) > 1) t")
                        .param(object).query(Long.class).single();
                assertThat(ties).as("tied times of closest approach").isPositive();
            }

            int page = 5;
            List<String> walked = new ArrayList<>();
            List<List<String>> rows = api.sql(AlertListQueries.OBJECT_APPROACHES)
                    .params(object, page + 1, object, page + 1, page + 1).query((rs, n) -> row(rs, 9)).list();
            while (true) {
                rows.subList(0, Math.min(page, rows.size())).forEach(r -> walked.add(r.get(0) + " " + r.get(2)));
                if (rows.size() <= page) {
                    break;
                }
                List<String> last = rows.get(page - 1);
                LocalDateTime tca = LocalDateTime.parse(last.get(2));
                long seq = Long.parseLong(last.get(0));
                rows = api.sql(AlertListQueries.OBJECT_APPROACHES_AFTER)
                        .params(object, tca, tca, seq, page + 1, object, tca, tca, seq, page + 1, page + 1)
                        .query((rs, n) -> row(rs, 9)).list();
            }
            assertThat(walked).as("approaches of " + object).isEqualTo(expected);
        }
    }

    static List<String> row(ResultSet rs, int columns) throws SQLException {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= columns; i++) {
            Object value = rs.getObject(i);
            out.add(value instanceof LocalDateTime t ? t.toString() : value == null ? null : value.toString());
        }
        return out;
    }

    static int count(String text, String word) {
        int n = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            n++;
        }
        return n;
    }

    static String explain(String sql, List<Object> params, String how) {
        return String.join("\n", api.sql(how + " " + sql).params(params).query(String.class).list()).strip();
    }

    static void write(Plan plan, String json, String tree) throws Exception {
        Files.createDirectories(QueryPlansIntegrationTest.OUT);
        List<Access> reads = new ArrayList<>();
        QueryPlansIntegrationTest.collect(QueryPlansIntegrationTest.JSON.readTree(json), reads);
        StringBuilder text = header(plan.name(), plan.params())
                .append("SQL:\n").append(plan.sql()).append("\n\n")
                .append("EXPLAIN FORMAT=TREE:\n").append(tree).append('\n')
                .append("\nTables read, from EXPLAIN FORMAT=JSON (table, access type, index, index columns used):\n");
        reads.forEach(r -> text.append(r.table()).append(", ").append(r.type()).append(", ")
                .append(r.key() == null ? "none" : r.key()).append(", ")
                .append(r.parts().isEmpty() ? "none" : String.join(" ", r.parts())).append('\n'));
        analyze(text, plan.sql(), plan.params());
        Files.writeString(QueryPlansIntegrationTest.OUT.resolve(plan.name() + ".txt"), text.toString(),
                StandardCharsets.UTF_8);
        if ("alerts-recent".equals(plan.name())) {
            List<Object> params = List.of(Long.MAX_VALUE, FETCH);
            StringBuilder scan = header("alerts-recent-by-scan, for comparison: the first page of the recent "
                    + "alerts list as one backward scan of alert_event, without the listed index", params)
                    .append("SQL:\n").append(AlertListQueries.RECENT_BY_SCAN).append("\n\n")
                    .append("EXPLAIN FORMAT=TREE:\n")
                    .append(explain(AlertListQueries.RECENT_BY_SCAN, params, "EXPLAIN FORMAT=TREE")).append('\n');
            analyze(scan, AlertListQueries.RECENT_BY_SCAN, params);
            Files.writeString(QueryPlansIntegrationTest.OUT.resolve("alerts-recent-by-scan.txt"), scan.toString(),
                    StandardCharsets.UTF_8);
        }
    }

    static StringBuilder header(String name, List<Object> params) {
        String version = api.sql("SELECT VERSION()").query(String.class).single();
        return new StringBuilder()
                .append("Query: ").append(name).append('\n')
                .append("Account: spaceflux_api\n")
                .append("Parameters: ").append(params).append('\n')
                .append("Data from ").append(AlertListPlanData.START).append(", seed ").append(AlertListPlanData.SEED)
                .append(" (AlertListPlanData): ").append(seeded.days()).append(" days; ")
                .append(seeded.spaceWeatherEvents()).append(" space weather events of which ")
                .append(seeded.listedSpaceWeather()).append(" listed, ").append(seeded.runs())
                .append(" screening runs, ").append(seeded.approaches()).append(" close approaches, ")
                .append(seeded.acknowledgements()).append(" acknowledgement rows\n")
                .append("MySQL: ").append(version).append(", statistics from ANALYZE TABLE after seeding\n\n");
    }

    static void analyze(StringBuilder text, String sql, List<Object> params) {
        text.append("\nEXPLAIN ANALYZE, one run on ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" with ")
                .append(Runtime.getRuntime().availableProcessors())
                .append(" processors; the times describe this run only:\n")
                .append(explain(sql, params, "EXPLAIN ANALYZE")).append('\n');
    }
}
