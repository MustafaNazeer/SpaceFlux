package io.github.mustafanazeer.spaceflux.risk.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.risk.weather.Scale;
import tools.jackson.databind.ObjectMapper;

/**
 * Cases found in review: identities that must stay distinct, values below the floor inside a zero run, redelivered
 * records around a satellite switch, a level inside a zero run's leading edge, and a revision after the age limit.
 */
class ScaleTrackerEdgeCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String XR_URL = "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json";
    private static final String KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";

    private static Reading xr(int sat, String hhmm, double flux, String fetched) {
        return Reading.of("swpc.goes.xrays", JSON.readTree("{\"time_tag\":\"2026-09-24T" + hhmm
                + ":00Z\",\"satellite\":" + sat + ",\"flux\":" + flux + ",\"observed_flux\":" + flux
                + ",\"energy\":\"0.1-0.8nm\"}"), at(fetched), XR_URL).orElseThrow();
    }

    private static Reading kp(String timeTag, double v, String fetched) {
        return Reading.of("swpc.kp", JSON.readTree("{\"time_tag\":\"" + timeTag + "\",\"Kp\":" + v + "}"),
                Instant.parse(fetched), KP_URL).orElseThrow();
    }

    private static Instant at(String hhmmss) {
        return Instant.parse("2026-09-24T" + hhmmss + "Z");
    }

    private static void minuteByMinute(ScaleTracker r, int from, int to, double flux) {
        for (int m = from; m <= to; m++) {
            String t = String.format("08:%02d", m);
            r.accept(List.of(xr(18, t, flux, t + ":30")), at(t + ":30"));
        }
    }

    @Test
    void anAgeLimitSpellAndARejectedSpellAtTheSameMinuteHaveDifferentIds() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        List<LevelEvent> all = new ArrayList<>(r.accept(List.of(xr(18, "08:00", 1e-7, "08:03:00")), at("08:03:00")));
        all.addAll(r.tick(at("08:21:00")));
        all.addAll(r.accept(List.of(xr(18, "08:19", 1e-7, "08:22:00"), xr(18, "08:20", 0.5, "08:22:00")),
                at("08:22:00")));

        assertThat(all).extracting(LevelEvent::eventId).doesNotHaveDuplicates();
        assertThat(all).last().satisfies(e -> {
            assertThat(e.state()).isEqualTo("no_data");
            assertThat(e.noDataReason()).isEqualTo("rejected");
            assertThat(e.eventId()).isEqualTo("space_weather_level/1/R/18/no_data/rejected/2026-09-24T08:20:00Z");
        });
    }

    @Test
    void aRevisionOfAnIntervalStillPastTheAgeLimitLeavesTheSeriesInNoData() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        List<LevelEvent> all = new ArrayList<>(
                g.accept(List.of(kp("2026-09-24T00:00:00", 3.0, "2026-09-24T03:05:00Z")), at("03:05:00")));
        all.addAll(g.tick(at("06:31:00")));

        List<LevelEvent> revision = g.accept(List.of(kp("2026-09-24T00:00:00", 5.0, "2026-09-24T06:40:00Z")),
                at("06:40:00"));
        all.addAll(revision);
        all.addAll(g.tick(at("07:00:00")));

        assertThat(revision).singleElement().satisfies(e -> {
            assertThat(e.trigger()).isEqualTo("revision");
            assertThat(e.derivedLabel()).isEqualTo("G1");
        });
        assertThat(all).extracting(LevelEvent::eventId).doesNotHaveDuplicates();
        assertThat(all).filteredOn(e -> "no_data".equals(e.state())).hasSize(1);
        assertThat(all.get(1).eventId()).isEqualTo("space_weather_level/1/G/-/no_data/age_limit/2026-09-24T06:30:00Z");
    }

    @Test
    void aValueBelowTheFloorStartsAZeroRunLikeAZero() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        minuteByMinute(r, 20, 25, 1e-7);

        List<LevelEvent> events = r.accept(List.of(xr(18, "08:26", 5e-10, "08:26:30")), at("08:26:30"));

        assertThat(events).filteredOn(e -> e.trigger().equals("restatement")).extracting(LevelEvent::timeTag)
                .containsExactly("2026-09-24T08:21:00Z", "2026-09-24T08:22:00Z", "2026-09-24T08:23:00Z",
                        "2026-09-24T08:24:00Z", "2026-09-24T08:25:00Z");
        assertThat(events).last().satisfies(e -> {
            assertThat(e.state()).isEqualTo("no_data");
            assertThat(e.noDataSince()).isEqualTo(at("08:21:00"));
        });
    }

    @Test
    void theTrailingEdgeIsMeasuredFromTheLastValueOfTheRunEvenBelowTheFloor() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        r.accept(List.of(xr(18, "08:20", 1e-7, "08:20:30")), at("08:20:30"));
        r.accept(List.of(xr(18, "08:26", 0.0, "08:27:30"), xr(18, "08:27", 5e-10, "08:27:30")), at("08:27:30"));

        assertThat(r.accept(List.of(xr(18, "08:32", 1e-7, "08:32:30")), at("08:32:30"))).isEmpty();
        assertThat(r.accept(List.of(xr(18, "08:33", 1e-7, "08:33:30")), at("08:33:30")))
                .singleElement().extracting(LevelEvent::state).isEqualTo("none");
    }

    @Test
    void aRedeliveredRecordOfAnEndedSeriesChangesNothing() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        List<LevelEvent> all = new ArrayList<>(r.accept(
                List.of(xr(18, "08:00", 1e-7, "08:01:00"), xr(18, "08:05", 1e-7, "08:06:00")), at("08:06:00")));
        all.addAll(r.accept(List.of(xr(19, "08:05", 1e-7, "08:06:10")), at("08:06:10")));

        List<LevelEvent> redelivered = r.accept(List.of(xr(18, "08:03", 1e-7, "08:07:00")), at("08:07:00"));
        all.addAll(redelivered);
        all.addAll(r.accept(List.of(xr(19, "08:06", 1e-7, "08:07:10")), at("08:07:10")));

        assertThat(redelivered).isEmpty();
        assertThat(all).filteredOn(e -> "ended".equals(e.state())).singleElement()
                .extracting(LevelEvent::satellite).isEqualTo(18);
        assertThat(all).extracting(LevelEvent::eventId).doesNotHaveDuplicates();
    }

    @Test
    void aZeroRunDoesNotSpanALevelOnItsLeadingEdge() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        r.accept(List.of(xr(18, "08:20", 1e-7, "08:20:30")), at("08:20:30"));
        r.accept(List.of(xr(18, "08:21", 2e-5, "08:21:30")), at("08:21:30"));
        r.accept(List.of(xr(18, "08:22", 1e-7, "08:22:30")), at("08:22:30"));

        List<LevelEvent> events = r.accept(List.of(xr(18, "08:23", 0.0, "08:23:30")), at("08:23:30"));

        assertThat(events).filteredOn(e -> e.trigger().equals("restatement")).extracting(LevelEvent::timeTag)
                .containsExactly("2026-09-24T08:22:00Z");
        assertThat(events).last().extracting(LevelEvent::noDataSince).isEqualTo(at("08:22:00"));
    }

    @Test
    void anEndedSpellIsIdentifiedByTheSatelliteThatTookOver() {
        ScaleTracker r = new ScaleTracker(Scale.R, 1);
        r.accept(List.of(xr(18, "08:05", 1e-7, "08:06:00")), at("08:06:00"));

        List<LevelEvent> events = r.accept(List.of(xr(19, "08:05", 1e-7, "08:06:10")), at("08:06:10"));

        assertThat(events.get(0).eventId()).isEqualTo("space_weather_level/1/R/18/ended/19/2026-09-24T08:05:00Z");
    }

    @Test
    void aSnapshotRestoresTheStateSoAReplayedBatchGivesTheSameEvents() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        g.accept(List.of(kp("2026-09-24T00:00:00", 3.0, "2026-09-24T03:04:00Z")), at("03:05:00"));
        g.accept(List.of(kp("2026-09-24T03:00:00", 3.0, "2026-09-24T06:04:00Z")), at("06:05:00"));
        ScaleTracker saved = g.copy();
        List<Reading> revision = List.of(kp("2026-09-24T00:00:00", 5.0, "2026-09-24T06:09:00Z"));

        List<LevelEvent> first = g.accept(revision, at("06:10:00"));
        List<LevelEvent> replayed = saved.copy().accept(revision, at("06:10:00"));

        assertThat(replayed).isEqualTo(first);
        assertThat(first).singleElement().extracting(LevelEvent::trigger).isEqualTo("revision");
        assertThat(saved.tick(at("06:40:00"))).noneMatch(e -> "no_data".equals(e.state()));
    }

    @Test
    void kpHistoryKeepsOnlyTheIntervalsSwpcStillPublishes() {
        ScaleTracker g = new ScaleTracker(Scale.G, 1);
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < 8 * 30; i++) {
            Instant t = start.plusSeconds(10_800L * i);
            g.accept(List.of(kp(t.toString().replace("Z", ""), 2.0, t.plusSeconds(11_040).toString())),
                    t.plusSeconds(11_100));
        }

        assertThat(g.kpIntervalsHeld()).isLessThanOrEqualTo(8 * 8 + 1);
        Instant newest = start.plusSeconds(10_800L * (8 * 30 - 1));
        Instant old = newest.minusSeconds(9 * 86_400);
        assertThat(g.accept(List.of(kp(old.toString().replace("Z", ""), 5.0, newest.plusSeconds(11_100).toString())),
                newest.plusSeconds(11_200))).isEmpty();
    }
}
