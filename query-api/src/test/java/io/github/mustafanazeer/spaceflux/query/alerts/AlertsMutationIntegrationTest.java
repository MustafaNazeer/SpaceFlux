package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
 * The unlimited retry is safe only because nothing in a record's content can make the processor throw. Feeds schema
 * valid mutations of every envelope field of every example through the processor and the real consumer store, since
 * which values MySQL refuses is the database's behavior, not a fake's.
 */
class AlertsMutationIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    static ConfigurableApplicationContext app;
    static AlertsProcessor processor;

    record Mutation(String name, Consumer<ObjectNode> change, Class<?> expected) {
    }

    @BeforeAll
    static void start() {
        TestMysql.start();
        app = new SpringApplicationBuilder(QueryApiApplication.class).web(WebApplicationType.NONE)
                .run(TestMysql.args());
        processor = new AlertsProcessor(TopicSchemas.fromClasspath(), app.getBean(AlertStore.class));
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static List<Mutation> mutations() {
        Class<?> stored = AlertsProcessor.Outcome.Stored.class;
        Class<?> dead = AlertsProcessor.Outcome.DeadLetter.class;
        List<Mutation> m = new ArrayList<>();
        m.add(new Mutation("event_id 512 code points", e -> id(e, "\uD834\uDD1E", 512), stored));
        m.add(new Mutation("event_id 513 code points", e -> id(e, "\uD834\uDD1E", 513), dead));
        m.add(new Mutation("event_id lone high surrogate", e -> suffix(e, "\uD800"), dead));
        m.add(new Mutation("event_id lone low surrogate", e -> suffix(e, "\uDFFF"), dead));
        m.add(new Mutation("event_id NUL", e -> suffix(e, "a\u0000b"), stored));
        m.add(new Mutation("event_id CR LF", e -> suffix(e, "a\r\nb"), stored));
        m.add(new Mutation("rules_version 1", e -> e.put("rules_version", 1), stored));
        m.add(new Mutation("rules_version max", e -> e.put("rules_version", 4_294_967_295L), stored));
        m.add(new Mutation("rules_version max + 1", e -> e.put("rules_version", 4_294_967_296L), dead));
        m.add(new Mutation("rules_version 1.0", e -> e.put("rules_version", 1.0), stored));
        m.add(new Mutation("rules_version 1 and a far decimal", e -> e.put("rules_version",
                new java.math.BigDecimal("1.0000000000000000001")), dead));
        m.add(new Mutation("rules_version 1e300", e -> e.put("rules_version", 1e300), dead));
        m.add(new Mutation("rules_version huge integer",
                e -> e.put("rules_version", new BigInteger("9".repeat(400))), dead));
        m.add(new Mutation("produced_at year 1000", e -> e.put("produced_at", "1000-01-01T00:00:00Z"), stored));
        m.add(new Mutation("produced_at year 999", e -> e.put("produced_at", "0999-12-31T23:59:59Z"), dead));
        m.add(new Mutation("produced_at Feb 30", e -> e.put("produced_at", "2026-02-30T00:00:00Z"), dead));
        m.add(new Mutation("produced_at second 60", e -> e.put("produced_at", "2016-12-31T23:59:60Z"), stored));
        m.add(new Mutation("produced_at 9 digit fraction",
                e -> e.put("produced_at", "2026-10-04T00:00:00.999999999Z"), stored));
        // The schema's date-time format refuses a fraction this long, so it never reaches the columns.
        m.add(new Mutation("produced_at 1000 digit fraction",
                e -> e.put("produced_at", "2026-10-04T00:00:00." + "9".repeat(1000) + "Z"), dead));
        m.add(new Mutation("produced_at year 9999", e -> e.put("produced_at", "9999-12-31T23:59:59.999999Z"), stored));
        return m;
    }

    /** A change to one payload field, applied only to examples of {@code scales} that carry the field. */
    record FieldMutation(String field, String name, Consumer<ObjectNode> change, Class<?> expected, String scales) {

        FieldMutation(String field, String name, Consumer<ObjectNode> change, Class<?> expected) {
            this(field, name, change, expected, "GRS");
        }
    }

    static List<FieldMutation> spaceWeatherMutations() {
        Class<?> stored = AlertsProcessor.Outcome.Stored.class;
        Class<?> dead = AlertsProcessor.Outcome.DeadLetter.class;
        List<FieldMutation> m = new ArrayList<>();
        m.add(new FieldMutation("satellite", "INT max", p -> p.put("satellite", Integer.MAX_VALUE), stored));
        m.add(new FieldMutation("satellite", "INT max + 1", p -> p.put("satellite", 2_147_483_648L), dead));
        // band and averaging_period_s are constants per scale in the schema, so these never reach the columns.
        m.add(new FieldMutation("band", "33 characters", p -> p.put("band", "b".repeat(33)), dead));
        m.add(new FieldMutation("derived_from", "lone surrogate", p -> p.put("derived_from", "Kp \uD800"), dead));
        m.add(new FieldMutation("derived_from", "65535 bytes", p -> p.put("derived_from", "d".repeat(65_535)),
                stored));
        m.add(new FieldMutation("derived_from", "65536 bytes", p -> p.put("derived_from", "d".repeat(65_536)), dead));
        m.add(new FieldMutation("derived_from", "NUL and CR LF", p -> p.put("derived_from", "a\u0000b\r\nc"),
                stored));
        // The schema caps a G value at 9.005, so these are R and S only.
        m.add(new FieldMutation("value", "largest double", p -> p.put("value", Double.MAX_VALUE), stored, "RS"));
        m.add(new FieldMutation("value", "beyond a double", p -> p.put("value", new java.math.BigDecimal("1e400")),
                dead, "RS"));
        m.add(new FieldMutation("value", "smallest double", p -> p.put("value", Double.MIN_VALUE), stored));
        m.add(new FieldMutation("value", "below the smallest double", p -> p.put("value",
                new java.math.BigDecimal("1e-400")), dead));
        m.add(new FieldMutation("value", "more digits than a double keeps", p -> p.put("value",
                new java.math.BigDecimal("0.1000000000000000000001")), dead));
        m.add(new FieldMutation("time_tag", "64 characters", p -> p.put("time_tag", "t".repeat(64)), stored));
        m.add(new FieldMutation("time_tag", "65 characters", p -> p.put("time_tag", "t".repeat(65)), dead));
        // The class must agree with the level, so the long classes come with R5, where any X20.0 or above is valid.
        m.add(new FieldMutation("xray_class", "16 characters", p -> r5(p, "X2" + "0".repeat(12) + ".0"), stored));
        m.add(new FieldMutation("xray_class", "17 characters", p -> r5(p, "X2" + "0".repeat(13) + ".0"), dead));
        m.add(new FieldMutation("averaging_period_s", "INT max + 1", p -> p.put("averaging_period_s",
                2_147_483_648L), dead));
        m.add(new FieldMutation("fetched_at", "year 999", p -> p.put("fetched_at", "0999-01-01T00:00:00Z"), dead));
        m.add(new FieldMutation("fetched_at", "second 60", p -> p.put("fetched_at", "2016-12-31T23:59:60.5Z"),
                stored));
        m.add(new FieldMutation("source_url", "65535 bytes", p -> p.put("source_url",
                "https://e.org/" + "u".repeat(65_535 - 14)), stored));
        m.add(new FieldMutation("source_url", "65536 bytes", p -> p.put("source_url",
                "https://e.org/" + "u".repeat(65_536 - 14)), dead));
        m.add(new FieldMutation("no_data_since", "year 999", p -> p.put("no_data_since", "0999-01-01T00:00:00Z"),
                dead));
        m.add(new FieldMutation("freshness_reference", "9 digit fraction", p -> p.put("freshness_reference",
                "2026-09-24T08:29:00.123456789Z"), stored));
        m.add(new FieldMutation("freshness_reference", "year 9999", p -> p.put("freshness_reference",
                "9999-12-31T23:59:59Z"), dead));
        m.add(new FieldMutation("freshness_reference", "59 minutes after the clock", p -> p.put("freshness_reference",
                "2026-10-04T12:59:00Z"), stored));
        return m;
    }

    @Test
    void noSchemaValidMutationOfASpaceWeatherFieldMakesTheProcessorThrow() throws Exception {
        List<String> examples = List.of("valid-g-level.json", "valid-g-none.json", "valid-r-level.json",
                "valid-r-no-data.json", "valid-r-restatement.json", "valid-s-level.json");
        int run = 0;
        int applied = 0;
        for (String example : examples) {
            for (FieldMutation mutation : spaceWeatherMutations()) {
                ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(example)));
                ObjectNode p = (ObjectNode) e.get("space_weather_level");
                if (!p.has(mutation.field()) || !mutation.scales().contains(p.get("scale").asString())) {
                    continue;
                }
                e.put("event_id", e.get("event_id").asString() + "/sw" + run++);
                mutation.change().accept(p);
                applied++;

                AlertsProcessor.Outcome outcome = processor.process(
                        new AlertsProcessor.In("k", JSON.writeValueAsBytes(e), 0, run), NOW);

                String reason = outcome instanceof AlertsProcessor.Outcome.DeadLetter(var d)
                        ? JSON.readTree(d.value()).get("reason").asString() : "";
                assertThat(outcome).as("%s, %s %s: %s", example, mutation.field(), mutation.name(), reason)
                        .isInstanceOf(mutation.expected());
            }
        }
        assertThat(applied).isGreaterThan(60);
    }

    static void r5(ObjectNode p, String xrayClass) {
        p.put("derived_level", 5);
        p.put("derived_label", "R5");
        p.put("xray_class", xrayClass);
    }

    static void suffix(ObjectNode e, String tail) {
        e.put("event_id", e.get("event_id").asString() + "/" + tail);
    }

    /** An event_id of exactly {@code codePoints} code points, built from its kind prefix and {@code fill}. */
    static void id(ObjectNode e, String fill, int codePoints) {
        String prefix = e.get("kind").asString() + "/1/" + Integer.toHexString(e.hashCode()) + "/";
        e.put("event_id", prefix + fill.repeat(codePoints - prefix.length()));
    }

    @Test
    void noSchemaValidMutationOfAnyExampleMakesTheProcessorThrow() throws Exception {
        List<Path> examples;
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            examples = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(examples).hasSizeGreaterThanOrEqualTo(9);
        int run = 0;
        for (Path example : examples) {
            for (Mutation mutation : mutations()) {
                ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(example));
                // A fresh identity per case, so each lands as a first arrival unless the mutation is the identity.
                e.put("event_id", e.get("event_id").asString() + "/m" + run++);
                mutation.change().accept(e);

                AlertsProcessor.Outcome outcome = processor.process(
                        new AlertsProcessor.In("k", JSON.writeValueAsBytes(e), 0, run), NOW);

                String reason = outcome instanceof AlertsProcessor.Outcome.DeadLetter(var d)
                        ? JSON.readTree(d.value()).get("reason").asString() : "";
                assertThat(outcome).as("%s, %s: %s", example.getFileName(), mutation.name(), reason)
                        .isInstanceOf(mutation.expected());
            }
        }
    }
}
