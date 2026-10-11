package io.github.mustafanazeer.spaceflux.query.live;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.context.ApplicationContext;

import io.github.mustafanazeer.spaceflux.query.alerts.AlertsPlanFeed;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stores example alerts with identities of their own through the consumer's processor and store. */
final class Alerts {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private Alerts() {
    }

    static ObjectNode example(String file) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    /** A close approach, which the recent alerts list always shows. */
    static ObjectNode approach() throws Exception {
        ObjectNode e = example("valid-close-approach.json");
        e.put("event_id", e.get("event_id").asString() + "/" + UUID.randomUUID());
        return e;
    }

    /** A screening run, which the recent alerts list never shows. */
    static ObjectNode run() throws Exception {
        String windowStart = String.format("2026-09-29T08:30:00.%06dZ",
                ThreadLocalRandom.current().nextInt(1_000_000));
        ObjectNode e = example("valid-screening-run.json");
        ObjectNode p = (ObjectNode) e.get("screening_run");
        p.put("window_start", windowStart);
        p.put("input_fetched_at", windowStart);
        p.put("run_id", windowStart + "/1");
        e.put("event_id", "screening_run/1/" + windowStart + "/1");
        return e;
    }

    static String store(ApplicationContext app, ObjectNode event) {
        new AlertsPlanFeed(app).store(JSON.writeValueAsBytes(event), NOW);
        return event.get("event_id").asString();
    }
}
