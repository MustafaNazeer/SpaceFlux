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
