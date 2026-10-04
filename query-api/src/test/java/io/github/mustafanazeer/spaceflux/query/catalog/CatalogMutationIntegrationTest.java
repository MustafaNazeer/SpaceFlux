package io.github.mustafanazeer.spaceflux.query.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Feeds schema valid mutations of every raw.gp field through the processor and the real consumer store, and asserts
 * the outcome of each: a record's content never makes the catalog consumer throw.
 */
class CatalogMutationIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    static ConfigurableApplicationContext app;
    static CatalogProcessor processor;

    record Mutation(String name, Consumer<ObjectNode> change, Class<?> expected) {
    }

    @BeforeAll
    static void start() {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args("--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=false"));
        processor = new CatalogProcessor(TopicSchemas.fromClasspath(), app.getBean(CatalogStore.class));
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static ObjectNode gp(ObjectNode e) {
        return (ObjectNode) e.get("gp");
    }

    static List<Mutation> mutations() {
        Class<?> applied = CatalogProcessor.Outcome.Applied.class;
        Class<?> dead = CatalogProcessor.Outcome.DeadLetter.class;
        List<Mutation> m = new ArrayList<>();
        m.add(new Mutation("name 64", e -> gp(e).put("OBJECT_NAME", "n".repeat(64)), applied));
        m.add(new Mutation("name 65, cut", e -> gp(e).put("OBJECT_NAME", "n".repeat(65)), applied));
        m.add(new Mutation("name lone surrogate", e -> gp(e).put("OBJECT_NAME", "ISS \uD800"), dead));
        m.add(new Mutation("name NUL and CR LF", e -> gp(e).put("OBJECT_NAME", "a\u0000b\r\nc"), applied));
        m.add(new Mutation("designator lone surrogate", e -> gp(e).put("OBJECT_ID", "1998-\uDC00"), dead));
        m.add(new Mutation("designator 65535 bytes", e -> gp(e).put("OBJECT_ID", "i".repeat(65_535)), applied));
        m.add(new Mutation("designator 65536 bytes", e -> gp(e).put("OBJECT_ID", "i".repeat(65_536)), dead));
        m.add(new Mutation("mean motion below the smallest double",
                e -> gp(e).put("MEAN_MOTION", new BigDecimal("1e-400")), dead));
        m.add(new Mutation("mean motion with more digits than a double keeps",
                e -> gp(e).put("MEAN_MOTION", new BigDecimal("15.4866452800000000000000001")), dead));
        m.add(new Mutation("mean motion largest double", e -> gp(e).put("MEAN_MOTION", Double.MAX_VALUE), applied));
        m.add(new Mutation("negative mean motion", e -> gp(e).put("MEAN_MOTION", -1.5), applied));
        m.add(new Mutation("bstar beyond a double", e -> gp(e).put("BSTAR", new BigDecimal("1e400")), dead));
        m.add(new Mutation("ddot as integer 0", e -> gp(e).put("MEAN_MOTION_DDOT", 0), applied));
        m.add(new Mutation("element set number 9999", e -> gp(e).put("ELEMENT_SET_NO", 9999), applied));
        m.add(new Mutation("revolution beyond a long", e -> gp(e).put("REV_AT_EPOCH",
                new BigInteger("9223372036854775808")), dead));
        m.add(new Mutation("ephemeris type INT max + 1", e -> gp(e).put("EPHEMERIS_TYPE", 2_147_483_648L), dead));
        m.add(new Mutation("negative ephemeris type", e -> gp(e).put("EPHEMERIS_TYPE", -3), applied));
        m.add(new Mutation("classification lone surrogate", e -> gp(e).put("CLASSIFICATION_TYPE", "\uD800"), dead));
        m.add(new Mutation("classification 65536 bytes", e -> gp(e).put("CLASSIFICATION_TYPE",
                "c".repeat(65_536)), dead));
        m.add(new Mutation("epoch 9 digit fraction", e -> gp(e).put("EPOCH", "2026-09-27T04:10:50.123456789"),
                applied));
        m.add(new Mutation("epoch leap second", e -> gp(e).put("EPOCH", "2016-12-31T23:59:60.5"), applied));
        m.add(new Mutation("epoch text longer than 64", e -> gp(e).put("EPOCH",
                "2026-09-27T04:10:50." + "1".repeat(50)), dead));
        m.add(new Mutation("epoch exactly 5 minutes after the fetch", e -> gp(e).put("EPOCH",
                "2026-09-27T09:02:39"), applied));
        m.add(new Mutation("epoch years ahead", e -> gp(e).put("EPOCH", "2030-01-01T00:00:00"), dead));
        m.add(new Mutation("fetch 2 hours after the clock", e -> {
            e.put("fetched_at", "2026-10-04T14:00:00Z");
            gp(e).put("EPOCH", "2026-10-04T13:59:00");
        }, dead));
        m.add(new Mutation("fetch 59 minutes after the clock", e -> {
            e.put("fetched_at", "2026-10-04T12:59:00Z");
            gp(e).put("EPOCH", "2026-10-04T12:58:00");
        }, applied));
        m.add(new Mutation("source url 65536 bytes", e -> e.put("source_url",
                "https://celestrak.org/" + "u".repeat(65_536 - 22)), dead));
        m.add(new Mutation("catalog number 0", e -> gp(e).put("NORAD_CAT_ID", 0), applied));
        m.add(new Mutation("catalog number 999999999", e -> gp(e).put("NORAD_CAT_ID", 999_999_999), applied));
        return m;
    }

    @Test
    void noSchemaValidMutationOfAnElementSetMakesTheProcessorThrow() throws Exception {
        long norad = 930_000_000L;
        for (Mutation mutation : mutations()) {
            ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(CatalogRowTest.EXAMPLE));
            gp(e).put("NORAD_CAT_ID", norad++);
            mutation.change().accept(e);

            CatalogProcessor.Outcome outcome =
                    processor.process(new CatalogProcessor.In("k", JSON.writeValueAsBytes(e)), NOW);

            String reason = outcome instanceof CatalogProcessor.Outcome.DeadLetter(var d)
                    ? JSON.readTree(d.value()).get("reason").asString() : "";
            assertThat(outcome).as("%s: %s", mutation.name(), reason).isInstanceOf(mutation.expected());
        }
    }
}
