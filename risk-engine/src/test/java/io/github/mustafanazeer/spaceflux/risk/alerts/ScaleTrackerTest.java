package io.github.mustafanazeer.spaceflux.risk.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.risk.weather.Scale;
import tools.jackson.databind.ObjectMapper;

/**
 * How one scale's series turn classified raw.swpc records into space_weather_level events (docs/data/topics.md,
 * alerts; docs/risk/space-weather-scales.md Sections 5.1 to 5.4). Values are chosen to hit the rules and are not
 * real data.
 */
class ScaleTrackerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";

    private static Reading kp(String timeTag, double kp, String fetchedAt) {
        return Reading.of("swpc.kp", JSON.readTree("{\"time_tag\":\"" + timeTag + "\",\"Kp\":" + kp + "}"),
                Instant.parse(fetchedAt), KP_URL).orElseThrow();
    }

    private static final String XR_URL = "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json";

    private static Reading xr(int satellite, String time, double flux) {
        return Reading.of("swpc.goes.xrays", JSON.readTree("{\"time_tag\":\"2026-09-24T" + time
                + ":00Z\",\"satellite\":" + satellite + ",\"flux\":" + flux
                + ",\"observed_flux\":" + flux + ",\"energy\":\"0.1-0.8nm\"}"),
                Instant.parse("2026-09-24T" + time + ":30Z"), XR_URL).orElseThrow();
    }

    private static Instant at(String time) {
        return Instant.parse("2026-09-24T" + time + ":00Z");
    }

    private static List<Reading> minutes(int satellite, int fromMinute, int toMinute, double flux) {
        List<Reading> out = new java.util.ArrayList<>();
        for (int m = fromMinute; m <= toMinute; m++) {
            out.add(xr(satellite, String.format("08:%02d", m), flux));
        }
        return out;
    }

    @Test
    void theFirstKpValueOfASeriesIsALevelChangeWithNoPreviousState() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        Instant now = Instant.parse("2024-05-10T21:05:00Z");

        List<LevelEvent> events = g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")), now);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("level_change");
            assertThat(e.state()).isEqualTo("level");
            assertThat(e.derivedLevel()).isEqualTo(4);
            assertThat(e.derivedLabel()).isEqualTo("G4");
            assertThat(e.previousState()).isNull();
            assertThat(e.value()).isEqualTo(7.67);
            assertThat(e.timeTag()).isEqualTo("2024-05-10T18:00:00");
            assertThat(e.intervalStart()).isEqualTo(Instant.parse("2024-05-10T18:00:00Z"));
            assertThat(e.intervalEnd()).isEqualTo(Instant.parse("2024-05-10T21:00:00Z"));
            assertThat(e.freshnessReference()).isEqualTo(Instant.parse("2024-05-10T18:00:00Z"));
            assertThat(e.fetchedAt()).isEqualTo(Instant.parse("2024-05-10T21:04:00Z"));
            assertThat(e.sourceUrl()).isEqualTo(KP_URL);
            assertThat(e.estimated()).isTrue();
            assertThat(e.satellite()).isNull();
            assertThat(e.eventId())
                    .isEqualTo("space_weather_level/1/G/-/2024-05-10T18:00:00/2024-05-10T21:04:00Z");
        });
    }

    @Test
    void aNewerKpValueInTheSameStateIsARefreshOnArrival() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")),
                Instant.parse("2024-05-10T21:05:00Z"));

        List<LevelEvent> events = g.accept(List.of(kp("2024-05-10T21:00:00", 8.0, "2024-05-11T00:04:00Z")),
                Instant.parse("2024-05-11T00:05:00Z"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("refresh");
            assertThat(e.state()).isEqualTo("level");
            assertThat(e.derivedLevel()).isEqualTo(4);
            assertThat(e.value()).isEqualTo(8.0);
            assertThat(e.previousState()).isNull();
            assertThat(e.freshnessReference()).isEqualTo(Instant.parse("2024-05-10T21:00:00Z"));
            assertThat(e.eventId())
                    .isEqualTo("space_weather_level/1/G/-/2024-05-10T21:00:00/2024-05-11T00:04:00Z/refresh");
        });
    }

    @Test
    void aChangeOfLevelCarriesThePreviousState() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")),
                Instant.parse("2024-05-10T21:05:00Z"));

        List<LevelEvent> events = g.accept(List.of(kp("2024-05-10T21:00:00", 9.0, "2024-05-11T00:04:00Z")),
                Instant.parse("2024-05-11T00:05:00Z"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("level_change");
            assertThat(e.derivedLabel()).isEqualTo("G5");
            assertThat(e.previousState()).isEqualTo("level");
            assertThat(e.previousDerivedLevel()).isEqualTo(4);
        });
    }

    @Test
    void anUnchangedKpValueSentAgainPublishesNothing() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")),
                Instant.parse("2024-05-10T21:05:00Z"));

        assertThat(g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:09:00Z")),
                Instant.parse("2024-05-10T21:10:00Z"))).isEmpty();
    }

    @Test
    void aRevisedOlderKpIntervalIsARevisionThatLeavesTheCurrentStateAlone() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T15:00:00", 5.0, "2024-05-10T21:04:00Z"),
                kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")), Instant.parse("2024-05-10T21:05:00Z"));

        List<LevelEvent> events = g.accept(List.of(kp("2024-05-10T15:00:00", 4.33, "2024-05-10T21:09:00Z"),
                kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:09:00Z")), Instant.parse("2024-05-10T21:10:00Z"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("revision");
            assertThat(e.state()).isEqualTo("none");
            assertThat(e.derivedLabel()).isEqualTo("none");
            assertThat(e.previousState()).isEqualTo("level");
            assertThat(e.previousDerivedLevel()).isEqualTo(1);
            assertThat(e.timeTag()).isEqualTo("2024-05-10T15:00:00");
            assertThat(e.freshnessReference()).isEqualTo(Instant.parse("2024-05-10T18:00:00Z"));
        });
        List<LevelEvent> next = g.accept(List.of(kp("2024-05-10T21:00:00", 7.67, "2024-05-11T00:04:00Z")),
                Instant.parse("2024-05-11T00:05:00Z"));
        assertThat(next).singleElement().extracting(LevelEvent::trigger).isEqualTo("refresh");
    }

    @Test
    void aRevisedNewestIntervalIsTheOnlyEventAndSetsTheCurrentState() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")),
                Instant.parse("2024-05-10T21:05:00Z"));

        List<LevelEvent> events = g.accept(List.of(kp("2024-05-10T18:00:00", 8.67, "2024-05-10T21:09:00Z")),
                Instant.parse("2024-05-10T21:10:00Z"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("revision");
            assertThat(e.derivedLevel()).isEqualTo(4);
            assertThat(e.value()).isEqualTo(8.67);
            assertThat(e.previousState()).isEqualTo("level");
            assertThat(e.previousDerivedLevel()).isEqualTo(4);
        });
        List<LevelEvent> next = g.accept(List.of(kp("2024-05-10T21:00:00", 9.0, "2024-05-11T00:04:00Z")),
                Instant.parse("2024-05-11T00:05:00Z"));
        assertThat(next).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("level_change");
            assertThat(e.previousDerivedLevel()).isEqualTo(4);
        });
    }

    @Test
    void aKpRevisionToARejectedValueIsARevisionToNoDataForThatInterval() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")),
                Instant.parse("2024-05-10T21:05:00Z"));

        List<LevelEvent> events = g.accept(List.of(kp("2024-05-10T18:00:00", 9.5, "2024-05-10T21:09:00Z")),
                Instant.parse("2024-05-10T21:10:00Z"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("revision");
            assertThat(e.state()).isEqualTo("no_data");
            assertThat(e.noDataReason()).isEqualTo("rejected");
            assertThat(e.noDataSince()).isEqualTo(Instant.parse("2024-05-10T18:00:00Z"));
            assertThat(e.intervalStart()).isEqualTo(Instant.parse("2024-05-10T18:00:00Z"));
            assertThat(e.intervalEnd()).isEqualTo(Instant.parse("2024-05-10T21:00:00Z"));
            assertThat(e.previousState()).isEqualTo("level");
            assertThat(e.previousDerivedLevel()).isEqualTo(4);
            assertThat(e.value()).isNull();
            assertThat(e.eventId())
                    .isEqualTo("space_weather_level/1/G/-/2024-05-10T18:00:00/2024-05-10T21:09:00Z");
        });
        assertThat(g.accept(List.of(kp("2024-05-10T21:00:00", 2.0, "2024-05-11T00:04:00Z")),
                Instant.parse("2024-05-11T00:05:00Z"))).singleElement().satisfies(e -> {
                    assertThat(e.trigger()).isEqualTo("level_change");
                    assertThat(e.previousState()).isEqualTo("no_data");
                });
    }

    @Test
    void passingTheAgeLimitIsNoDataFromTheTimeTheLimitWasPassed() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")),
                Instant.parse("2024-05-10T21:05:00Z"));

        List<LevelEvent> events = g.tick(Instant.parse("2024-05-11T00:31:00Z"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("level_change");
            assertThat(e.state()).isEqualTo("no_data");
            assertThat(e.derivedLabel()).isEqualTo("no data");
            assertThat(e.noDataReason()).isEqualTo("age_limit");
            assertThat(e.noDataSince()).isEqualTo(Instant.parse("2024-05-11T00:30:00Z"));
            assertThat(e.freshnessReference()).isEqualTo(Instant.parse("2024-05-10T18:00:00Z"));
            assertThat(e.previousState()).isEqualTo("level");
            assertThat(e.value()).isNull();
            assertThat(e.eventId())
                    .isEqualTo("space_weather_level/1/G/-/no_data/age_limit/2024-05-11T00:30:00Z");
        });
        assertThat(g.tick(Instant.parse("2024-05-11T01:00:00Z"))).isEmpty();
    }

    @Test
    void replayedArchiveDataIsFollowedAtOnceByNoData() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);

        List<LevelEvent> events = g.accept(List.of(kp("2024-05-10T18:00:00", 9.0, "2024-05-10T21:04:00Z")),
                Instant.parse("2026-09-30T20:00:00Z"));

        assertThat(events).extracting(LevelEvent::state).containsExactly("level", "no_data");
        assertThat(events.get(1).noDataReason()).isEqualTo("age_limit");
    }

    @Test
    void theTimerFallbackRefreshesAQuietSeriesWithTheSameRecord() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2024-05-10T18:00:00", 7.67, "2024-05-10T21:04:00Z")),
                Instant.parse("2024-05-10T21:05:00Z"));

        assertThat(g.tick(Instant.parse("2024-05-10T21:19:00Z"))).isEmpty();
        List<LevelEvent> events = g.tick(Instant.parse("2024-05-10T21:20:00Z"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("refresh");
            assertThat(e.timerRefreshAt()).isEqualTo(Instant.parse("2024-05-10T21:20:00Z"));
            assertThat(e.freshnessReference()).isEqualTo(Instant.parse("2024-05-10T18:00:00Z"));
            assertThat(e.eventId()).isEqualTo(
                    "space_weather_level/1/G/-/2024-05-10T18:00:00/2024-05-10T21:04:00Z/refresh/2024-05-10T21:20:00Z");
        });
        assertThat(g.tick(Instant.parse("2024-05-10T21:34:00Z"))).isEmpty();
        assertThat(g.tick(Instant.parse("2024-05-10T21:35:00Z"))).hasSize(1);
        assertThat(Duration.between(Instant.parse("2024-05-10T21:20:00Z"), Instant.parse("2024-05-10T21:35:00Z")))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aRejectedXrayValueIsNoDataFromItsTime() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        r.accept(List.of(xr(18, "08:00", 2e-7)), at("08:03"));

        List<LevelEvent> events = r.accept(List.of(xr(18, "08:01", 0.5)), at("08:06"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.state()).isEqualTo("no_data");
            assertThat(e.noDataReason()).isEqualTo("rejected");
            assertThat(e.noDataSince()).isEqualTo(at("08:01"));
            assertThat(e.previousState()).isEqualTo("none");
            assertThat(e.satellite()).isEqualTo(18);
            assertThat(e.estimated()).isFalse();
            assertThat(e.eventId()).isEqualTo("space_weather_level/1/R/18/no_data/rejected/2026-09-24T08:01:00Z");
        });
        assertThat(r.accept(List.of(xr(18, "08:02", 2e-7)), at("08:07")))
                .singleElement().extracting(LevelEvent::state).isEqualTo("none");
    }

    @Test
    void aLevelCarriesItsSampleTimeAndXrayClass() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);

        List<LevelEvent> events = r.accept(List.of(xr(18, "08:00", 1.2e-5)), at("08:03"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.derivedLabel()).isEqualTo("R1");
            assertThat(e.xrayClass()).isEqualTo("M1.2");
            assertThat(e.sampleTime()).isEqualTo(at("08:00"));
            assertThat(e.intervalStart()).isNull();
        });
    }

    @Test
    void aZeroRunRestatesPublishedNoneSamplesOnItsLeadingEdge() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        r.accept(minutes(18, 15, 21, 2e-7), at("08:24"));
        r.accept(minutes(18, 22, 26, 2e-7), at("08:29"));

        List<LevelEvent> events = r.accept(minutes(18, 27, 29, 0.0), at("08:32"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("restatement");
            assertThat(e.timeTag()).isEqualTo("2026-09-24T08:26:00Z");
            assertThat(e.previousState()).isEqualTo("none");
            assertThat(e.noDataReason()).isEqualTo("zero_run_edge");
            assertThat(e.restatedByTimeTag()).isEqualTo("2026-09-24T08:27:00Z");
            assertThat(e.value()).isEqualTo(2e-7);
            assertThat(e.eventId())
                    .isEqualTo("space_weather_level/1/R/18/2026-09-24T08:26:00Z/2026-09-24T08:26:30Z/restated");
        });
        assertThat(events.get(1)).satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("level_change");
            assertThat(e.state()).isEqualTo("no_data");
            assertThat(e.noDataReason()).isEqualTo("rejected");
            assertThat(e.noDataSince()).isEqualTo(at("08:22"));
            assertThat(e.freshnessReference()).isEqualTo(at("08:29"));
        });
    }

    @Test
    void noneValuesOnTheTrailingEdgeStayNoDataButALevelShows() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        r.accept(minutes(18, 20, 26, 2e-7), at("08:29"));
        r.accept(minutes(18, 27, 30, 0.0), at("08:33"));

        assertThat(r.accept(minutes(18, 31, 35, 2e-7), at("08:38"))).isEmpty();
        assertThat(r.accept(List.of(xr(18, "08:36", 2e-7)), at("08:39")))
                .singleElement().extracting(LevelEvent::state).isEqualTo("none");

        ScaleTracker r2 = new ScaleTracker(Scale.R, 1);
        r2.accept(minutes(18, 20, 26, 2e-7), at("08:29"));
        r2.accept(minutes(18, 27, 30, 0.0), at("08:33"));
        assertThat(r2.accept(List.of(xr(18, "08:31", 2e-5)), at("08:34")))
                .singleElement().extracting(LevelEvent::derivedLabel).isEqualTo("R1");
    }

    @Test
    void anotherSatelliteReachingTheSeriesEndsItAndOnlyItsNewestRecordPublishes() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        r.accept(minutes(18, 0, 10, 2e-7), at("08:13"));

        List<Reading> switched = new java.util.ArrayList<>(minutes(19, 0, 9, 3e-5));
        switched.add(xr(19, "08:10", 2e-7));
        List<LevelEvent> events = r.accept(switched, at("08:14"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).satisfies(e -> {
            assertThat(e.state()).isEqualTo("ended");
            assertThat(e.satellite()).isEqualTo(18);
            assertThat(e.endedBySatellite()).isEqualTo(19);
            assertThat(e.noDataSince()).isEqualTo(at("08:10"));
            assertThat(e.derivedLabel()).isEqualTo("no data");
            assertThat(e.eventId()).isEqualTo("space_weather_level/1/R/18/ended/19/2026-09-24T08:10:00Z");
        });
        assertThat(events.get(1)).satisfies(e -> {
            assertThat(e.satellite()).isEqualTo(19);
            assertThat(e.state()).isEqualTo("none");
            assertThat(e.timeTag()).isEqualTo("2026-09-24T08:10:00Z");
            assertThat(e.previousState()).isNull();
        });
        assertThat(r.tick(at("08:40"))).singleElement().extracting(LevelEvent::satellite).isEqualTo(19);
    }

    @Test
    void theRecordedEclipseReadsAsNoneThenNoDataThenNone() throws Exception {
        tools.jackson.databind.JsonNode records;
        try (var in = ScaleTrackerTest.class.getResourceAsStream(
                "/swpc-xrays/goes18-xrays-7-day-eclipse-2026-09-24.json")) {
            records = JSON.readTree(in);
        }
        java.util.TreeMap<Instant, List<Reading>> byPoll = new java.util.TreeMap<>();
        for (var rec : records) {
            Reading.of("swpc.goes.xrays", rec, Instant.parse("2026-09-24T12:00:00Z"), XR_URL).ifPresent(rd -> {
                long step = rd.time().getEpochSecond() / 180;
                byPoll.computeIfAbsent(Instant.ofEpochSecond(step * 180), k -> new java.util.ArrayList<>()).add(rd);
            });
        }
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        List<LevelEvent> all = new java.util.ArrayList<>();
        byPoll.forEach((poll, batch) -> all.addAll(r.accept(batch, poll.plus(Duration.ofMinutes(5)))));

        List<LevelEvent> changes = all.stream().filter(e -> !e.trigger().equals("refresh")).toList();
        assertThat(changes).extracting(LevelEvent::trigger, LevelEvent::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("level_change", "none"),
                org.assertj.core.groups.Tuple.tuple("restatement", "no_data"),
                org.assertj.core.groups.Tuple.tuple("restatement", "no_data"),
                org.assertj.core.groups.Tuple.tuple("level_change", "no_data"),
                org.assertj.core.groups.Tuple.tuple("level_change", "none"));
        assertThat(changes.get(1).timeTag()).isEqualTo("2026-09-24T08:23:00Z");
        assertThat(changes.get(2).timeTag()).isEqualTo("2026-09-24T08:26:00Z");
        assertThat(changes.get(3).noDataSince()).isEqualTo(Instant.parse("2026-09-24T08:22:00Z"));
        assertThat(changes.get(4).timeTag()).isEqualTo("2026-09-24T09:38:00Z");
        assertThat(all).noneMatch(e -> "level".equals(e.state()));
    }
}
