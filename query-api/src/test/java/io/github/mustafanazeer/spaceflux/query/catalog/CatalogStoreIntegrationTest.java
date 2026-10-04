package io.github.mustafanazeer.spaceflux.query.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** raw.gp element sets through the processor and the real consumer store. */
class CatalogStoreIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    // Catalog numbers of their own, so this class never meets rows other tests wrote.
    static final AtomicLong NEXT = new AtomicLong(910_000_000L + (System.nanoTime() % 1_000_000L) * 10);

    static ConfigurableApplicationContext app;
    static CatalogProcessor processor;
    static JdbcClient db;

    @BeforeAll
    static void start() {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args("--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=false"));
        processor = new CatalogProcessor(TopicSchemas.fromClasspath(), app.getBean(CatalogStore.class));
        db = app.getBean("apiJdbcClient", JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static ObjectNode iss(long norad, String epoch, String fetchedAt, Consumer<ObjectNode> change) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(CatalogRowTest.EXAMPLE));
        e.put("fetched_at", fetchedAt);
        ObjectNode gp = (ObjectNode) e.get("gp");
        gp.put("NORAD_CAT_ID", norad);
        gp.put("EPOCH", epoch);
        change.accept(gp);
        return e;
    }

    static CatalogProcessor.Outcome process(ObjectNode e) {
        return processor.process(new CatalogProcessor.In("k", JSON.writeValueAsBytes(e)), NOW);
    }

    static Map<String, Object> row(long norad) {
        return db.sql("SELECT * FROM catalog_object WHERE norad_cat_id = ?").param(norad).query().singleRow();
    }

    @Test
    void anElementSetIsStoredColumnByColumn() throws Exception {
        long norad = NEXT.getAndIncrement();

        assertThat(process(iss(norad, "2026-09-27T04:10:50.460096", "2026-09-27T08:57:39Z", g -> { })))
                .isEqualTo(CatalogProcessor.Outcome.APPLIED);

        Map<String, Object> r = row(norad);
        assertThat(r).containsEntry("object_name", "ISS (ZARYA)").containsEntry("object_name_cut", false)
                .containsEntry("object_id", "1998-067A")
                .containsEntry("epoch", LocalDateTime.of(2026, 9, 27, 4, 10, 50, 460_096_000))
                .containsEntry("epoch_text", "2026-09-27T04:10:50.460096")
                .containsEntry("mean_motion", 15.48664528).containsEntry("eccentricity", 0.0007168)
                .containsEntry("bstar", 0.00018291).containsEntry("mean_motion_dot", 9.528e-05)
                .containsEntry("mean_motion_ddot", 0.0).containsEntry("classification_type", "U")
                .containsEntry("fetched_at", LocalDateTime.of(2026, 9, 27, 8, 57, 39))
                .containsEntry("first_fetched_at", LocalDateTime.of(2026, 9, 27, 8, 57, 39))
                .containsEntry("last_fetched_at", LocalDateTime.of(2026, 9, 27, 8, 57, 39));
        assertThat(((Number) r.get("element_set_no")).intValue()).isEqualTo(999);
        assertThat(((Number) r.get("rev_at_epoch")).longValue()).isEqualTo(58756);
        assertThat(((Number) r.get("ephemeris_type")).intValue()).isZero();
    }

    @Test
    void onlyANewerEpochReplacesTheElementSetAndTheFetchRangeOnlyWidens() throws Exception {
        long norad = NEXT.getAndIncrement();
        process(iss(norad, "2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> g.put("OBJECT_NAME", "MIDDLE")));
        process(iss(norad, "2026-09-27T04:00:00", "2026-09-27T09:00:00Z", g -> g.put("OBJECT_NAME", "OLDER")));
        process(iss(norad, "2026-09-28T04:00:00.000000", "2026-09-28T10:00:00Z", g -> g.put("OBJECT_NAME", "SAME")));
        process(iss(norad, "2026-09-29T04:00:00", "2026-09-29T09:00:00Z", g -> g.put("OBJECT_NAME", "NEWER")));
        process(iss(norad, "2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> g.put("OBJECT_NAME", "MIDDLE")));

        assertThat(row(norad)).containsEntry("object_name", "NEWER")
                .containsEntry("epoch", LocalDateTime.of(2026, 9, 29, 4, 0))
                .containsEntry("fetched_at", LocalDateTime.of(2026, 9, 29, 9, 0))
                .containsEntry("first_fetched_at", LocalDateTime.of(2026, 9, 27, 9, 0))
                .containsEntry("last_fetched_at", LocalDateTime.of(2026, 9, 29, 9, 0));
    }

    @Test
    void aLongNameIsStoredCutAndMarked() throws Exception {
        long norad = NEXT.getAndIncrement();

        process(iss(norad, "2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> g.put("OBJECT_NAME", "D".repeat(70))));

        assertThat(row(norad)).containsEntry("object_name", "D".repeat(61) + "...")
                .containsEntry("object_name_cut", true);
    }

    @Test
    void whenTheCatalogCannotBeWrittenNothingChangesAndTheRecordIsRetried() throws Exception {
        long norad = NEXT.getAndIncrement();
        process(iss(norad, "2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> { }));
        ObjectNode newer = iss(norad, "2026-09-29T04:00:00", "2026-09-29T09:00:00Z", g -> g.put("OBJECT_NAME", "N"));
        TestMysql.rootSql("REVOKE UPDATE ON spaceflux.catalog_object FROM 'spaceflux_consumer'@'%'");
        try {
            assertThatThrownBy(() -> process(newer)).isNotInstanceOf(NotStorable.class);
            assertThat(row(norad)).containsEntry("object_name", "ISS (ZARYA)");
        } finally {
            TestMysql.rootSql("GRANT UPDATE ON spaceflux.catalog_object TO 'spaceflux_consumer'@'%'");
        }

        assertThat(process(newer)).isEqualTo(CatalogProcessor.Outcome.APPLIED);
        assertThat(row(norad)).containsEntry("object_name", "N");
    }
}
