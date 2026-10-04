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
                .run(TestMysql.args("--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=false"));
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

    /** A change to a close_approach or screening_run event, by the file it applies to. */
    record EventMutation(String file, String name, Consumer<ObjectNode> change, Class<?> expected) {
    }

    static ObjectNode at(ObjectNode e, String... path) {
        tools.jackson.databind.JsonNode node = e;
        for (String step : path) {
            node = step.matches("[0-9]+") ? node.get(Integer.parseInt(step)) : node.get(step);
        }
        return (ObjectNode) node;
    }

    static List<EventMutation> screeningMutations() {
        Class<?> stored = AlertsProcessor.Outcome.Stored.class;
        Class<?> dead = AlertsProcessor.Outcome.DeadLetter.class;
        String ca = "valid-close-approach.json";
        String cut = "valid-screening-run-cut.json";
        List<EventMutation> m = new ArrayList<>();
        m.add(new EventMutation(ca, "name 64", e -> at(e, "close_approach", "other_object").put("name",
                "n".repeat(64)), stored));
        m.add(new EventMutation(ca, "name lone surrogate", e -> at(e, "close_approach", "other_object").put("name",
                "SL \uDC00"), dead));
        m.add(new EventMutation(ca, "element age with more digits than a double", e -> at(e, "close_approach",
                "watchlist_object").put("element_age_days", new java.math.BigDecimal("1.55850000000000000000001")),
                dead));
        m.add(new EventMutation(ca, "negative element age", e -> at(e, "close_approach", "watchlist_object")
                .put("element_age_days", -0.25), stored));
        m.add(new EventMutation(ca, "largest catalog number", e -> at(e, "close_approach", "other_object")
                .put("catalog_number", 999_999_999), stored));
        m.add(new EventMutation(ca, "miss distance below the smallest double", e -> at(e, "close_approach")
                .put("miss_distance_m", new java.math.BigDecimal("1e-400")), dead));
        m.add(new EventMutation(ca, "window start a year ahead", e -> at(e, "close_approach").put("window_start",
                "2027-10-04T00:00:00Z"), dead));
        m.add(new EventMutation(ca, "closest approach a week ahead", e -> at(e, "close_approach")
                .put("time_of_closest_approach", "2026-10-11T00:00:00Z"), stored));
        m.add(new EventMutation(cut, "mechanism 65", e -> at(e, "screening_run", "suppressed", "0").put("mechanism",
                "m".repeat(65)), dead));
        m.add(new EventMutation(cut, "detail lone surrogate", e -> at(e, "screening_run", "suppressed", "0")
                .put("detail", "x \uD800"), dead));
        m.add(new EventMutation(cut, "detail 65536 bytes", e -> at(e, "screening_run", "suppressed", "0")
                .put("detail", "d".repeat(65_536)), dead));
        m.add(new EventMutation(cut, "reason NUL and CR LF", e -> at(e, "screening_run", "rejected", "1")
                .put("reason", "a\u0000b\r\nc"), stored));
        m.add(new EventMutation(cut, "code 64", e -> at(e, "screening_run", "rejected", "0").put("code",
                "c".repeat(64)), stored));
        m.add(new EventMutation(cut, "separation time a week ahead", e -> at(e, "screening_run", "suppressed", "0")
                .put("min_separation_at", "2026-10-11T00:00:00Z"), stored));
        m.add(new EventMutation(cut, "coverage count above INT UNSIGNED", e -> at(e, "screening_run", "coverage")
                .put("pairs", 4_294_967_296L), dead));
        m.add(new EventMutation(cut, "approach id 513", e -> ((tools.jackson.databind.node.ArrayNode) at(e,
                "screening_run").get("approach_event_ids")).add("close_approach/" + "a".repeat(498)), dead));
        m.add(new EventMutation(cut, "approach id 512", e -> ((tools.jackson.databind.node.ArrayNode) at(e,
                "screening_run").get("approach_event_ids")).add("close_approach/" + "a".repeat(497)), stored));
        m.add(new EventMutation(cut, "input fetched a year ahead", e -> at(e, "screening_run").put("input_fetched_at",
                "2027-10-04T00:00:00Z"), dead));
        return m;
    }

    @Test
    void noSchemaValidMutationOfAScreeningFieldMakesTheProcessorThrow() throws Exception {
        int run = 0;
        for (EventMutation mutation : screeningMutations()) {
            ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(mutation.file())));
            // A run identity of its own, so a summary is never refused as a second claim on a stored run_id.
            String start = String.format("2026-09-29T07:30:00.%06dZ", 1 + run++);
            ObjectNode p = (ObjectNode) e.get(e.has("screening_run") ? "screening_run" : "close_approach");
            p.put("run_id", start + "/1");
            p.put("window_start", start);
            if (p.has("input_fetched_at")) {
                p.put("input_fetched_at", start);
            }
            e.put("event_id", e.get("kind").asString() + "/1/" + start + "/1/m" + run);
            mutation.change().accept(e);

            AlertsProcessor.Outcome outcome = processor.process(
                    new AlertsProcessor.In("k", JSON.writeValueAsBytes(e), 0, run), NOW);

            String reason = outcome instanceof AlertsProcessor.Outcome.DeadLetter(var d)
                    ? JSON.readTree(d.value()).get("reason").asString() : "";
            assertThat(outcome).as("%s, %s: %s", mutation.file(), mutation.name(), reason)
                    .isInstanceOf(mutation.expected());
        }
    }

    static void r5(ObjectNode p, String xrayClass) {
        p.put("derived_level", 5);
        p.put("derived_label", "R5");
        p.put("xray_class", xrayClass);
    }

    static void freshRun(ObjectNode e, int run) {
        String kind = e.get("kind").asString();
        if (!kind.equals("screening_run") && !kind.equals("close_approach")) {
            return;
        }
        String start = String.format("2026-09-28T07:30:00.%06dZ", run);
        ObjectNode p = (ObjectNode) e.get(kind);
        p.put("run_id", start + "/1");
        p.put("window_start", start);
        if (p.has("input_fetched_at")) {
            p.put("input_fetched_at", start);
        }
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
                // A run's identity is its run_id too, so screening examples get a window start of their own.
                e.put("event_id", e.get("event_id").asString() + "/m" + run++);
                freshRun(e, run);
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
