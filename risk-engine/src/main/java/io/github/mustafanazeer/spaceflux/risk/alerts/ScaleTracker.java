package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import io.github.mustafanazeer.spaceflux.risk.alerts.Reading.Outcome;
import io.github.mustafanazeer.spaceflux.risk.weather.Scale;

/**
 * The series of one scale and the space_weather_level events they publish: per change of state, Kp revisions,
 * restatements of eclipse edge samples, refreshes, "no data" by age, and the end of a satellite's series when SWPC's
 * primary file moves to another satellite (docs/data/topics.md, alerts; docs/risk/space-weather-scales.md Sections 5.1
 * to 5.4). State is held in memory only (ADR 0008). Not thread safe; one scale is handled by one thread.
 */
public final class ScaleTracker {

    /** Section 5.2, eclipse edge rule 2. */
    static final Duration EDGE = Duration.ofMinutes(5);

    private final Scale scale;
    private final int rulesVersion;
    private final Duration ageLimit;
    private final Duration fallback;
    private final Map<Integer, Series> series = new LinkedHashMap<>();

    public ScaleTracker(Scale scale, int rulesVersion) {
        this.scale = scale;
        this.rulesVersion = rulesVersion;
        this.ageLimit = switch (scale) {
            case G -> Duration.ofMinutes(390);
            case R -> Duration.ofMinutes(20);
            case S -> Duration.ofMinutes(40);
        };
        this.fallback = switch (scale) {
            case G, S -> Duration.ofMinutes(15);
            case R -> Duration.ofMinutes(5);
        };
    }

    /**
     * Reads the records of one response of this scale's product and returns the events they publish, in the order a
     * consumer must read them, followed by any "no data" the clock already calls for.
     */
    public List<LevelEvent> accept(List<Reading> batch, Instant now) {
        Map<Integer, List<Reading>> bySeries = new LinkedHashMap<>();
        for (Reading r : batch) {
            if (r.scale() == scale && r.placeable()) {
                bySeries.computeIfAbsent(key(r), k -> new ArrayList<>()).add(r);
            }
        }
        List<LevelEvent> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Reading>> e : bySeries.entrySet()) {
            Series s = series.computeIfAbsent(e.getKey(), Series::new);
            List<Reading> readings = new ArrayList<>(e.getValue());
            readings.sort(Comparator.comparing(Reading::time));
            List<LevelEvent> events = s.read(readings).stream().map(ev -> ev.withFreshness(s.freshness)).toList();
            out.addAll(endOthers(s));
            out.addAll(events);
        }
        out.addAll(ageCheck(now));
        for (LevelEvent ev : out) {
            series.get(ev.satellite() == null ? -1 : ev.satellite()).lastSent = now;
        }
        return out;
    }

    /** Called by a clock: "no data" by age, then fallback refreshes for quiet series (Section 5.3). */
    public List<LevelEvent> tick(Instant now) {
        List<LevelEvent> out = new ArrayList<>(ageCheck(now));
        for (LevelEvent ev : out) {
            series.get(ev.satellite() == null ? -1 : ev.satellite()).lastSent = now;
        }
        for (Series s : series.values()) {
            if (s.ended || !s.current.showsValue() || s.lastSent == null) {
                continue;
            }
            if (!now.isBefore(s.lastSent.plus(fallback))) {
                out.add(s.sampleEvent(s.freshest, "refresh", null, now));
                s.lastSent = now;
            }
        }
        return out;
    }

    private static int key(Reading r) {
        return r.satellite() == null ? -1 : r.satellite();
    }

    private List<LevelEvent> ageCheck(Instant now) {
        List<LevelEvent> out = new ArrayList<>();
        for (Series s : series.values()) {
            if (s.ended || s.freshness == null || s.current.state.equals("no_data")) {
                continue;
            }
            Instant limit = s.freshness.plus(ageLimit);
            if (now.isAfter(limit)) {
                State before = s.current;
                s.current = State.noData("age_limit", limit);
                out.add(s.stateEvent(before));
            }
        }
        return out;
    }

    /** Section 5.4 rule 2: a series whose freshness another satellite's series reached or passed has ended. */
    private List<LevelEvent> endOthers(Series b) {
        List<LevelEvent> out = new ArrayList<>();
        if (scale == Scale.G || b.freshness == null) {
            return out;
        }
        for (Series a : series.values()) {
            if (a == b || a.ended || a.freshness == null || b.freshness.isBefore(a.freshness)) {
                continue;
            }
            State before = a.current;
            a.ended = true;
            a.current = State.ended(a.freshness, b.satellite);
            out.add(a.stateEvent(before));
        }
        return out;
    }

    /** A series state: level (with its level), none, no_data (with reason and start), or ended. */
    record State(String state, Integer level, String reason, Instant since, Integer endedBy) {

        static final State UNKNOWN = new State("unknown", null, null, null, null);

        static State of(Reading r) {
            return switch (r.outcome()) {
                case LEVEL -> new State("level", r.level(), null, null, null);
                case NONE -> new State("none", null, null, null, null);
                case REJECTED, MISSING -> noData("rejected", r.time());
            };
        }

        static State noData(String reason, Instant since) {
            return new State("no_data", null, reason, since, null);
        }

        static State ended(Instant since, Integer by) {
            return new State("ended", null, null, since, by);
        }

        boolean showsValue() {
            return state.equals("level") || state.equals("none");
        }

        boolean sameAs(State o) {
            return state.equals(o.state) && Objects.equals(level, o.level);
        }
    }

    private final class Series {

        final Integer satellite;
        Instant freshness;
        Reading freshest;
        State current = State.UNKNOWN;
        boolean ended;
        Instant lastSent;
        /** Kp value per interval, for revisions (ADR 0006). */
        final Map<Instant, Reading> intervals = new TreeMap<>();
        /** Recent R samples, for eclipse edges: the reading, its state, and whether it had its own event. */
        final NavigableMap<Instant, Sample> recent = new TreeMap<>();
        Instant lastZero;

        Series(int key) {
            this.satellite = key == -1 ? null : key;
        }

        List<LevelEvent> read(List<Reading> readings) {
            boolean fresh = current == State.UNKNOWN || ended;
            State before = current;
            Instant freshnessBefore = freshness;
            List<LevelEvent> events = new ArrayList<>();
            ended = false;
            for (Reading r : readings) {
                events.addAll(scale == Scale.G ? readKp(r) : readGoes(r));
            }
            if (fresh) {
                // Section 5.4 rule 5: after a start or a switch, only the newest record sets the state.
                events.clear();
                recent.values().forEach(sm -> sm.hadEvent = false);
                if (freshness != null && !current.sameAs(before)) {
                    events.add(stateOrSample(before));
                    markHadEvent();
                }
                return events;
            }
            boolean stateEvent = events.stream().anyMatch(ev -> ev.trigger().equals("level_change")
                    || ev.trigger().equals("revision") && Objects.equals(ev.timeTag(), freshest.timeTag()));
            if (!stateEvent && freshness != null && !freshness.equals(freshnessBefore) && current.showsValue()) {
                events.add(sampleEvent(freshest, "refresh", null, null));
                markHadEvent();
            }
            return events;
        }

        private List<LevelEvent> readKp(Reading r) {
            Reading seen = intervals.get(r.time());
            if (seen != null) {
                if (Objects.equals(seen.value(), r.value()) && seen.outcome() == r.outcome()) {
                    return List.of();
                }
                intervals.put(r.time(), r);
                State previous = State.of(seen);
                if (r.time().equals(freshness)) {
                    freshest = r;
                    current = State.of(r);
                }
                return List.of(r.outcome() == Outcome.LEVEL || r.outcome() == Outcome.NONE
                        ? sampleEvent(r, "revision", previous, null)
                        : revisionToNoData(r, previous));
            }
            intervals.put(r.time(), r);
            return advance(r, State.of(r));
        }

        private List<LevelEvent> readGoes(Reading r) {
            if (freshness != null && !r.time().isAfter(freshness)) {
                return List.of();
            }
            List<LevelEvent> out = new ArrayList<>();
            State state = State.of(r);
            if (scale == Scale.R) {
                if (r.outcome() == Outcome.MISSING) {
                    Map.Entry<Instant, Sample> previous = recent.lowerEntry(r.time());
                    boolean runContinues = previous != null
                            && previous.getValue().reading.outcome() == Outcome.MISSING;
                    if (!runContinues) {
                        Instant since = r.time();
                        for (Sample sm : recent.subMap(r.time().minus(EDGE), true, r.time(), false).values()) {
                            if (sm.state.state.equals("none")) {
                                since = since.isAfter(sm.reading.time()) ? sm.reading.time() : since;
                                if (sm.hadEvent) {
                                    out.add(restatement(sm.reading, r));
                                }
                                sm.state = State.noData("zero_run_edge", sm.reading.time());
                            }
                        }
                        state = State.noData("rejected", since);
                    } else {
                        state = State.noData("rejected", r.time());
                    }
                    lastZero = r.time();
                } else if (r.outcome() == Outcome.NONE && lastZero != null
                        && !r.time().isAfter(lastZero.plus(EDGE))) {
                    state = State.noData("zero_run_edge", r.time());
                }
            }
            out.addAll(advance(r, state));
            if (scale == Scale.R) {
                recent.put(r.time(), new Sample(r, state, !out.isEmpty()));
                recent.headMap(r.time().minus(EDGE.plus(EDGE)), false).clear();
            }
            return out;
        }

        private List<LevelEvent> advance(Reading r, State state) {
            if (freshness != null && !r.time().isAfter(freshness)) {
                return List.of();
            }
            freshness = r.time();
            freshest = r;
            State before = current;
            if (state.state.equals("no_data") && before.state.equals("no_data")) {
                return List.of();
            }
            current = state;
            if (state.sameAs(before)) {
                return List.of();
            }
            return List.of(stateOrSample(before));
        }

        private LevelEvent stateOrSample(State before) {
            return current.showsValue() ? sampleEvent(freshest, "level_change", before, null) : stateEvent(before);
        }

        private void markHadEvent() {
            Sample sm = recent.get(freshness);
            if (sm != null) {
                sm.hadEvent = true;
            }
        }

        LevelEvent sampleEvent(Reading r, String trigger, State previous, Instant timerAt) {
            State st = State.of(r);
            String suffix = switch (trigger) {
                case "refresh" -> timerAt == null ? "/refresh" : "/refresh/" + timerAt;
                default -> "";
            };
            boolean g = scale == Scale.G;
            return new LevelEvent(sampleId(r) + suffix, scale, r.product(), st.state, st.level, label(st),
                    previousState(previous), previousLevel(previous), trigger, g, satellite, r.value(), r.xrayClass(),
                    r.timeTag(), g ? r.time() : null, g ? r.time().plus(Duration.ofHours(3)) : null,
                    g ? null : r.time(), r.fetchedAt(), r.sourceUrl(), freshness, timerAt, null, null, null, null);
        }

        private LevelEvent revisionToNoData(Reading r, State previous) {
            return new LevelEvent(sampleId(r), scale, r.product(), "no_data", null, "no data",
                    previousState(previous), previousLevel(previous), "revision", true, null, null, null,
                    r.timeTag(), r.time(), r.time().plus(Duration.ofHours(3)), null, r.fetchedAt(), r.sourceUrl(),
                    freshness, null, "rejected", r.time(), null, null);
        }

        private LevelEvent restatement(Reading r, Reading zero) {
            return new LevelEvent(sampleId(r) + "/restated", scale, r.product(), "no_data", null, "no data", "none",
                    null, "restatement", false, satellite, r.value(), null, r.timeTag(), null, null, r.time(),
                    r.fetchedAt(), r.sourceUrl(), zero.time(), null, "zero_run_edge", r.time(), zero.timeTag(), null);
        }

        LevelEvent stateEvent(State before) {
            State st = current;
            Reading r = freshest;
            return new LevelEvent(prefix() + "/" + st.state + "/" + st.since, scale, r.product(), st.state, null,
                    "no data", previousState(before), previousLevel(before), "level_change", scale == Scale.G,
                    satellite, null, null, null, null, null, null, null, null, freshness, null, st.reason, st.since,
                    null, st.endedBy);
        }

        private String sampleId(Reading r) {
            return prefix() + "/" + r.timeTag() + "/" + r.fetchedAt();
        }

        private String prefix() {
            return "space_weather_level/" + rulesVersion + "/" + scale + "/" + (satellite == null ? "-" : satellite);
        }
    }

    private String label(State st) {
        return switch (st.state) {
            case "level" -> scale.name() + st.level;
            case "none" -> "none";
            default -> "no data";
        };
    }

    private static String previousState(State s) {
        return s == null || s == State.UNKNOWN ? null : s.state;
    }

    private static Integer previousLevel(State s) {
        return s == null ? null : s.level;
    }

    private static final class Sample {
        final Reading reading;
        State state;
        boolean hadEvent;

        Sample(Reading reading, State state, boolean hadEvent) {
            this.reading = reading;
            this.state = state;
            this.hadEvent = hadEvent;
        }
    }
}
