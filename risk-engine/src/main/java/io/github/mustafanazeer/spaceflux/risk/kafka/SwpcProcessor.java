package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.mustafanazeer.spaceflux.risk.alerts.AlertJson;
import io.github.mustafanazeer.spaceflux.risk.alerts.LevelEvent;
import io.github.mustafanazeer.spaceflux.risk.alerts.Reading;
import io.github.mustafanazeer.spaceflux.risk.alerts.ScaleTracker;
import io.github.mustafanazeer.spaceflux.risk.weather.Scale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns raw.swpc events into alerts events and dead letters (docs/data/topics.md; ADR 0007, ADR 0008). Records are
 * checked against the raw.swpc schema, classified by the storm rules, and read by one {@link ScaleTracker} per scale.
 * The records of one product with one fetched_at, as they arrive together, are read as one batch. Each alerts event is
 * checked against the alerts schema before it is returned. Kafka I/O is the caller's; process and tick may be called
 * from different threads.
 */
public final class SwpcProcessor {

    /** Raised by hand whenever a threshold, rule, or screening setting changes (docs/data/topics.md, alerts). */
    public static final int RULES_VERSION = 1;

    static final String SOURCE_TOPIC = "raw.swpc";
    static final String ALERTS_TOPIC = "alerts";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(SwpcProcessor.class);

    private final TopicSchemas schemas;
    private final DeadLetters deadLetters;
    private final Map<Scale, ScaleTracker> trackers = new EnumMap<>(Scale.class);

    public record In(String key, byte[] value) {
    }

    public record Message(String topic, String key, byte[] value) {
    }

    /** {@code missingValues} counts X-ray values of exactly 0, SWPC's missing marker, which are not dead lettered. */
    public record Out(List<Message> alerts, List<Message> deadLetters, int missingValues) {
    }

    public SwpcProcessor(TopicSchemas schemas) {
        this.schemas = schemas;
        this.deadLetters = new DeadLetters(schemas);
        for (Scale s : Scale.values()) {
            trackers.put(s, new ScaleTracker(s, RULES_VERSION));
        }
    }

    public synchronized Out process(List<In> records, Instant now) {
        List<Message> alerts = new ArrayList<>();
        List<Message> dead = new ArrayList<>();
        int missing = 0;
        List<Reading> batch = new ArrayList<>();
        for (In in : records) {
            TopicSchemas.Result checked = schemas.check(SOURCE_TOPIC, in.value());
            if (checked.failure() != null) {
                dead.add(deadLetter(in, sourceUrl(in.value()), "schema", checked.failure(), now));
                continue;
            }
            JsonNode event = checked.node();
            Optional<Reading> reading = read(event);
            if (reading.isEmpty()) {
                continue;
            }
            Reading r = reading.get();
            if (r.deadLettered()) {
                dead.add(deadLetter(in, r.sourceUrl(), "rule", r.reason(), now));
            } else if (r.outcome() == Reading.Outcome.MISSING) {
                missing++;
            }
            if (!batch.isEmpty() && !sameBatch(batch.get(0), r)) {
                publish(trackers.get(batch.get(0).scale()).accept(batch, now), now, alerts, dead);
                batch = new ArrayList<>();
            }
            batch.add(r);
        }
        if (!batch.isEmpty()) {
            publish(trackers.get(batch.get(0).scale()).accept(batch, now), now, alerts, dead);
        }
        return new Out(alerts, dead, missing);
    }

    /** An independent copy of every series' state, taken before a batch whose writes may fail. */
    public synchronized Snapshot snapshot() {
        Map<Scale, ScaleTracker> copy = new EnumMap<>(Scale.class);
        trackers.forEach((s, t) -> copy.put(s, t.copy()));
        return new Snapshot(copy);
    }

    /**
     * Puts every series back as it was when {@code s} was taken. Called when writing a batch's events failed, so the
     * redelivered batch is read from the same state and produces the same events with the same identities (ADR 0008,
     * at least once delivery).
     */
    public synchronized void restore(Snapshot s) {
        s.trackers.forEach((scale, t) -> trackers.put(scale, t.copy()));
    }

    public static final class Snapshot {
        private final Map<Scale, ScaleTracker> trackers;

        private Snapshot(Map<Scale, ScaleTracker> trackers) {
            this.trackers = trackers;
        }
    }

    /** "No data" by age and fallback refreshes; called by a clock (docs/risk/space-weather-scales.md Section 5.3). */
    public synchronized Out tick(Instant now) {
        List<Message> alerts = new ArrayList<>();
        List<Message> dead = new ArrayList<>();
        for (Map.Entry<Scale, ScaleTracker> e : trackers.entrySet()) {
            try {
                publish(e.getValue().tick(now), now, alerts, dead);
            } catch (RuntimeException ex) {
                LOG.error("timer check of the {} series failed; its state is reset", e.getKey(), ex);
                e.setValue(new ScaleTracker(e.getKey(), RULES_VERSION));
            }
        }
        return new Out(alerts, dead, 0);
    }

    private static Optional<Reading> read(JsonNode event) {
        Instant fetchedAt;
        try {
            fetchedAt = Instant.parse(event.get("fetched_at").asString());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        return Reading.of(event.get("product").asString(), event.get("record"), fetchedAt,
                event.get("source_url").asString());
    }

    private static boolean sameBatch(Reading a, Reading b) {
        return a.product().equals(b.product()) && a.fetchedAt().equals(b.fetchedAt());
    }

    /**
     * Adds each event to the alerts, after checking it against the alerts schema. An event that fails is written to
     * alerts.dlq instead (ADR 0007), so no record content can stop the consumer.
     */
    private void publish(List<LevelEvent> events, Instant now, List<Message> alerts, List<Message> dead) {
        for (LevelEvent e : events) {
            JsonNode json = AlertJson.write(e, RULES_VERSION, now);
            byte[] value = MAPPER.writeValueAsBytes(json);
            String key = "space_weather." + e.scale();
            TopicSchemas.Result r = schemas.check(ALERTS_TOPIC, json);
            if (r.failure() == null) {
                alerts.add(new Message(ALERTS_TOPIC, key, value));
            } else {
                LOG.error("alerts event {} fails its schema and is dead lettered", e.eventId());
                DeadLetters.Message m = deadLetters.build(ALERTS_TOPIC, e.sourceUrl(), key, "schema", r.failure(),
                        value, now);
                dead.add(new Message(m.topic(), m.key(), m.value()));
            }
        }
    }

    /** A URL read from a record that failed its schema can itself be malformed; the dead letter then goes without it. */
    private Message deadLetter(In in, String sourceUrl, String check, String reason, Instant now) {
        DeadLetters.Message m;
        try {
            m = deadLetters.build(SOURCE_TOPIC, sourceUrl, in.key(), check, reason, in.value(), now);
        } catch (IllegalStateException e) {
            m = deadLetters.build(SOURCE_TOPIC, null, in.key(), check, reason, in.value(), now);
        }
        return new Message(m.topic(), m.key(), m.value());
    }

    /** The event's source_url when it can be read and is an https URL, for a dead letter of a record that failed. */
    private static String sourceUrl(byte[] value) {
        try {
            JsonNode url = MAPPER.readTree(new String(value, StandardCharsets.UTF_8)).get("source_url");
            return url != null && url.isString() && url.asString().startsWith("https://") ? url.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
