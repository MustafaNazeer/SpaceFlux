package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mustafanazeer.spaceflux.contracts.DeadLetters;
import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.risk.alerts.ScreeningJson;
import io.github.mustafanazeer.spaceflux.risk.alerts.UtcTimes;
import io.github.mustafanazeer.spaceflux.risk.orbit.GpElementSets;
import io.github.mustafanazeer.spaceflux.risk.screening.Role;
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
 * caller reports them written, and within one process a run id that was written is never produced again with other
 * content; after a restart, a run that gained a late element set is rebuilt under the same id (ADR 0007 decision 12).
 * The run is computed without holding the state lock, so reading raw.gp never waits for a screening run.
 */
public final class GpProcessor {

    static final String SOURCE_TOPIC = "raw.gp";
    static final Duration QUIET = Duration.ofSeconds(30);
    /** How far a fetched_at may run past the engine's clock, or an EPOCH past its fetched_at (orbital conventions 2.4). */
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    /** Element sets this much older than the window start are dropped from the held catalog. */
    static final Duration KEEP = Duration.ofDays(30);
    /** Names are cut to this many code points on arrival, so the held state and every event stay bounded. */
    static final int MAX_NAME_CODE_POINTS = 64;
    /** Differing same epoch copies held per object; later distinct ones are only counted (ADR 0007 decision 15). */
    static final int MAX_COPIES = 3;
    private static final Logger LOG = LoggerFactory.getLogger(GpProcessor.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TopicSchemas schemas;
    private final DeadLetters deadLetters;
    private final Set<Integer> watchlist;
    private final TimeScale utc;
    private final Screening screening = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M);
    private final Map<Integer, TrackedObject> newest = new TreeMap<>();
    /** Element sets with the same epoch as the held one but other elements or name, listed as differing copies. */
    private final Map<Integer, List<TrackedObject>> copies = new TreeMap<>();
    private final Map<Integer, Integer> overCap = new TreeMap<>();
    private final Set<String> writtenRuns = new HashSet<>();
    private Instant newestFetched;
    private Instant lastArrival;
    private boolean changed;
    private long generation;
    private Out pending;

    public record In(String key, byte[] value) {
    }

    public record Message(String topic, String key, byte[] value) {
    }

    /** {@code runId} is set when {@code alerts} holds a run's events. */
    public record Out(List<Message> alerts, List<Message> deadLetters, String runId) {
    }

    private record Input(List<TrackedObject> watched, List<TrackedObject> catalog, Map<Integer, String> names,
            List<ScreeningJson.OverCap> overCap, Instant windowStart, long generation) {
    }

    public GpProcessor(TopicSchemas schemas, Set<Integer> watchlist, TimeScale utc) {
        this.schemas = schemas;
        this.deadLetters = new DeadLetters(schemas, KafkaConfig.SERVICE);
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
            try {
                fetchedAt = UtcTimes.parse(event.get("fetched_at").asString());
            } catch (DateTimeParseException e) {
                dead.add(deadLetter(in, url, "rule", "\"fetched_at\" is not a valid UTC time", now));
                continue;
            }
            if (fetchedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
                dead.add(deadLetter(in, url, "rule",
                        "\"fetched_at\" is more than 5 minutes after the risk engine's clock " + now, now));
                continue;
            }
            TrackedObject object;
            try {
                JsonNode gp = event.get("gp");
                JsonNode name = gp.get("OBJECT_NAME");
                object = new TrackedObject(name != null && name.isString() ? cut(name.asString()) : null,
                        GpElementSets.toTle(gp));
            } catch (RuntimeException e) {
                dead.add(deadLetter(in, url, null, "element set cannot be read: " + e.getMessage(), now));
                continue;
            }
            if (object.tle().getDate().isAfter(new AbsoluteDate(fetchedAt.plus(FUTURE_TOLERANCE), utc))) {
                dead.add(deadLetter(in, url, "rule", "\"EPOCH\" is more than 5 minutes after fetched_at " + fetchedAt,
                        now));
                continue;
            }
            if (hold(object)) {
                if (newestFetched == null || fetchedAt.isAfter(newestFetched)) {
                    newestFetched = fetchedAt;
                }
                lastArrival = now;
                changed = true;
                generation++;
                pending = null;
            }
        }
        return new Out(List.of(), dead, null);
    }

    /** True when the element set is new information: a newer epoch, or a differing copy of the held epoch. */
    private boolean hold(TrackedObject object) {
        int n = object.catalogNumber();
        TrackedObject held = newest.get(n);
        if (held == null || object.tle().getDate().isAfter(held.tle().getDate())) {
            newest.put(n, object);
            copies.remove(n);
            overCap.remove(n);
            return true;
        }
        if (!object.tle().getDate().equals(held.tle().getDate()) || same(held, object)) {
            return false;
        }
        List<TrackedObject> list = copies.computeIfAbsent(n, k -> new ArrayList<>());
        if (list.stream().anyMatch(c -> same(c, object))) {
            return false;
        }
        if (list.size() >= MAX_COPIES) {
            overCap.merge(n, 1, Integer::sum);
            return false;
        }
        list.add(object);
        return true;
    }

    static String cut(String name) {
        if (name.codePointCount(0, name.length()) <= MAX_NAME_CODE_POINTS) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, MAX_NAME_CODE_POINTS - 3)) + "...";
    }

    /**
     * Compared field by field, never through formatted lines: Orekit builds lines lazily and refuses values a line
     * cannot hold, and a line rounds away differences below its precision. TLE.equals compares exactly the fields of
     * orbital conventions 2.4, "Copies with the held epoch".
     */
    private static boolean same(TrackedObject a, TrackedObject b) {
        return Objects.equals(a.name(), b.name()) && a.tle().equals(b.tle());
    }

    /**
     * The current run's events once its batch has been quiet for 30 seconds and until they are reported written. The
     * run is computed outside the state lock; if element sets arrived meanwhile, nothing is returned and the next call
     * computes the run again.
     */
    public Out poll(Instant now) {
        Input input;
        synchronized (this) {
            if (!changed || lastArrival == null || now.isBefore(lastArrival.plus(QUIET))) {
                return empty();
            }
            if (pending != null) {
                return pending;
            }
            String runId = runId(newestFetched);
            if (writtenRuns.contains(runId)) {
                LOG.info("element sets arrived for the written run {}; they are screened with the next newer fetch",
                        runId);
                changed = false;
                return empty();
            }
            input = snapshot();
        }
        Out out = run(input, now);
        synchronized (this) {
            if (generation != input.generation()) {
                return empty();
            }
            pending = out;
            return out;
        }
    }

    /** The run's events were written; in this process it is not produced again, and its id is never reused. */
    public synchronized void published(String runId) {
        writtenRuns.add(runId);
        if (pending != null && pending.runId().equals(runId)) {
            changed = false;
            pending = null;
        }
    }

    private Input snapshot() {
        AbsoluteDate oldest = new AbsoluteDate(newestFetched.minus(KEEP), utc);
        newest.values().removeIf(o -> o.tle().getDate().isBefore(oldest));
        copies.keySet().retainAll(newest.keySet());
        overCap.keySet().retainAll(newest.keySet());
        List<TrackedObject> held = new ArrayList<>(newest.values());
        List<TrackedObject> catalog = new ArrayList<>(held);
        copies.values().forEach(catalog::addAll);
        List<TrackedObject> watched = held.stream().filter(o -> watchlist.contains(o.catalogNumber())).toList();
        Map<Integer, String> names = new HashMap<>();
        catalog.forEach(o -> names.putIfAbsent(o.catalogNumber(), o.name()));
        List<ScreeningJson.OverCap> over = overCap.entrySet().stream()
                .map(e -> new ScreeningJson.OverCap(e.getKey(), newest.get(e.getKey()).tle().getDate(), e.getValue()))
                .toList();
        return new Input(watched, catalog, names, over, newestFetched, generation);
    }

    private static Out empty() {
        return new Out(List.of(), List.of(), null);
    }

    private static String runId(Instant windowStart) {
        return windowStart + "/" + SwpcProcessor.RULES_VERSION;
    }

    private Out run(Input in, Instant now) {
        ScreeningResult result = withMissingWatchlist(
                screening.run(in.watched(), in.catalog(), new AbsoluteDate(in.windowStart(), utc)), in);
        List<JsonNode> events = ScreeningJson.write(result, in.names(), in.overCap(), in.windowStart(),
                SwpcProcessor.RULES_VERSION, now, utc);
        String runId = runId(in.windowStart());
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

    /** Names every configured watchlist object that has no element set in the input (SPEC 5.4: absence is an error). */
    private ScreeningResult withMissingWatchlist(ScreeningResult r, Input in) {
        Set<Integer> present = new HashSet<>();
        in.catalog().forEach(o -> present.add(o.catalogNumber()));
        List<ScreeningResult.Rejected> rejected = new ArrayList<>(r.rejected());
        watchlist.stream().sorted().filter(n -> !present.contains(n)).forEach(n -> rejected.add(
                new ScreeningResult.Rejected(n, Role.WATCHLIST, ScreeningResult.Rejected.Code.NOT_IN_INPUT,
                        "no element set for this watchlist object in the input, so it was not screened")));
        return new ScreeningResult(r.start(), r.end(), r.coverage(), r.approaches(), r.suppressed(), rejected,
                r.notScreened(), r.epochAfterStart(), r.differingCopies());
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
