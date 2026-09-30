package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mustafanazeer.spaceflux.risk.alerts.ScreeningJson;
import io.github.mustafanazeer.spaceflux.risk.orbit.GpElementSets;
import io.github.mustafanazeer.spaceflux.risk.screening.Screening;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningSettings;
import io.github.mustafanazeer.spaceflux.risk.screening.StationStacks;
import io.github.mustafanazeer.spaceflux.risk.screening.TrackedObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the newest element set per object from raw.gp and screens the watchlist against them once a batch has
 * finished arriving: the element sets of one fetch share its fetched_at, and a run starts once no new element set has
 * arrived for 30 seconds. The window starts at the newest fetched_at among the element sets held, so the same input
 * always gives the same run and the same event identities (ADR 0007, ADR 0008). A run's events are kept until the
 * caller reports them written, so a failed write is retried with the same events.
 */
public final class GpProcessor {

    static final String SOURCE_TOPIC = "raw.gp";
    static final Duration QUIET = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(GpProcessor.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TopicSchemas schemas;
    private final DeadLetters deadLetters;
    private final Set<Integer> watchlist;
    private final TimeScale utc;
    private final Screening screening = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M);
    private final Map<Integer, TrackedObject> newest = new TreeMap<>();
    private Instant newestFetched;
    private Instant lastArrival;
    private boolean changed;
    private Out pending;

    public record In(String key, byte[] value) {
    }

    public record Message(String topic, String key, byte[] value) {
    }

    /** {@code runId} is set when {@code alerts} holds a run's events. */
    public record Out(List<Message> alerts, List<Message> deadLetters, String runId) {
    }

    public GpProcessor(TopicSchemas schemas, Set<Integer> watchlist, TimeScale utc) {
        this.schemas = schemas;
        this.deadLetters = new DeadLetters(schemas);
        this.watchlist = Set.copyOf(watchlist);
        this.utc = utc;
    }

    /** Reads element sets; returns only dead letters. A run is produced later by {@link #poll(Instant)}. */
    public synchronized Out accept(List<In> records, Instant now) {
        List<Message> dead = new ArrayList<>();
        for (In in : records) {
            TopicSchemas.Result checked = schemas.check(SOURCE_TOPIC, in.value());
            if (checked.failure() != null) {
                dead.add(deadLetter(in, null, "schema", checked.failure(), now));
                continue;
            }
            JsonNode event = checked.node();
            String url = event.get("source_url").asString();
            Instant fetchedAt;
            TrackedObject object;
            try {
                fetchedAt = Instant.parse(event.get("fetched_at").asString());
                JsonNode gp = event.get("gp");
                JsonNode name = gp.get("OBJECT_NAME");
                object = new TrackedObject(name != null && name.isString() ? name.asString() : null,
                        GpElementSets.toTle(gp));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                dead.add(deadLetter(in, url, "rule", String.valueOf(e.getMessage()), now));
                continue;
            } catch (RuntimeException e) {
                dead.add(deadLetter(in, url, "rule", "element set cannot be read: " + e.getMessage(), now));
                continue;
            }
            TrackedObject held = newest.get(object.catalogNumber());
            if (held != null && !object.tle().getDate().isAfter(held.tle().getDate())) {
                continue;
            }
            newest.put(object.catalogNumber(), object);
            if (newestFetched == null || fetchedAt.isAfter(newestFetched)) {
                newestFetched = fetchedAt;
            }
            lastArrival = now;
            changed = true;
            pending = null;
        }
        return new Out(List.of(), dead, null);
    }

    /** The current run's events once its batch has been quiet for 30 seconds and until they are reported written. */
    public synchronized Out poll(Instant now) {
        if (!changed || lastArrival == null || now.isBefore(lastArrival.plus(QUIET))) {
            return new Out(List.of(), List.of(), null);
        }
        if (pending == null) {
            pending = run(now);
        }
        return pending;
    }

    /** The run's events were written; it is not produced again until new element sets arrive. */
    public synchronized void published(String runId) {
        if (pending != null && pending.runId().equals(runId)) {
            changed = false;
            pending = null;
        }
    }

    private Out run(Instant now) {
        List<TrackedObject> catalog = new ArrayList<>(newest.values());
        List<TrackedObject> watched = catalog.stream().filter(o -> watchlist.contains(o.catalogNumber())).toList();
        Map<Integer, String> names = new HashMap<>();
        catalog.forEach(o -> names.put(o.catalogNumber(), o.name()));
        ScreeningResult result = screening.run(watched, catalog, new AbsoluteDate(newestFetched, utc));
        List<JsonNode> events = ScreeningJson.write(result, names, newestFetched, SwpcProcessor.RULES_VERSION, now,
                utc);
        String runId = newestFetched + "/" + SwpcProcessor.RULES_VERSION;
        List<Message> alerts = new ArrayList<>();
        List<Message> dead = new ArrayList<>();
        for (JsonNode e : events) {
            byte[] value = MAPPER.writeValueAsBytes(e);
            TopicSchemas.Result r = schemas.check(SwpcProcessor.ALERTS_TOPIC, e);
            if (r.failure() == null) {
                alerts.add(new Message(SwpcProcessor.ALERTS_TOPIC, runId, value));
            } else {
                LOG.error("alerts event {} fails its schema and is dead lettered", e.get("event_id").asString());
                DeadLetters.Message m = deadLetters.build(SwpcProcessor.ALERTS_TOPIC, null, runId, "schema",
                        r.failure(), value, now);
                dead.add(new Message(m.topic(), m.key(), m.value()));
            }
        }
        return new Out(alerts, dead, runId);
    }

    private Message deadLetter(In in, String sourceUrl, String check, String reason, Instant now) {
        DeadLetters.Message m;
        try {
            m = deadLetters.build(SOURCE_TOPIC, sourceUrl, in.key(), check, reason, in.value(), now);
        } catch (IllegalStateException e) {
            m = deadLetters.build(SOURCE_TOPIC, null, in.key(), check, reason, in.value(), now);
        }
        return new Message(m.topic(), m.key(), m.value());
    }
}
