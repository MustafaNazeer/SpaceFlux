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
            if (r.outcome() == Reading.Outcome.REJECTED) {
                dead.add(deadLetter(in, r.sourceUrl(), "rule", r.reason(), now));
            } else if (r.outcome() == Reading.Outcome.MISSING) {
                missing++;
            }
            if (!batch.isEmpty() && !sameBatch(batch.get(0), r)) {
                alerts.addAll(publish(trackers.get(batch.get(0).scale()).accept(batch, now), now));
                batch = new ArrayList<>();
            }
            batch.add(r);
        }
        if (!batch.isEmpty()) {
            alerts.addAll(publish(trackers.get(batch.get(0).scale()).accept(batch, now), now));
        }
        return new Out(alerts, dead, missing);
    }

    /** "No data" by age and fallback refreshes; called by a clock (docs/risk/space-weather-scales.md Section 5.3). */
    public synchronized Out tick(Instant now) {
        List<Message> alerts = new ArrayList<>();
        for (ScaleTracker t : trackers.values()) {
            alerts.addAll(publish(t.tick(now), now));
        }
        return new Out(alerts, List.of(), 0);
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

    private List<Message> publish(List<LevelEvent> events, Instant now) {
        List<Message> out = new ArrayList<>();
        for (LevelEvent e : events) {
            JsonNode json = AlertJson.write(e, RULES_VERSION, now);
            TopicSchemas.Result r = schemas.check(ALERTS_TOPIC, json);
            if (r.failure() != null) {
                throw new IllegalStateException("alerts event " + e.eventId() + " fails its schema: " + r.failure());
            }
            out.add(new Message(ALERTS_TOPIC, "space_weather." + e.scale(), MAPPER.writeValueAsBytes(json)));
        }
        return out;
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
