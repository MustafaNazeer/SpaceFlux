package io.github.mustafanazeer.spaceflux.risk.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.ObjectMapper;

/** The plausibility checks of docs/risk/space-weather-scales.md Section 5.1, applied before a record reaches a series. */
class ReadingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "https://services.swpc.noaa.gov/";

    private static Reading kp(String timeTag, String fetchedAt) {
        return Reading.of("swpc.kp", JSON.readTree("{\"time_tag\":\"" + timeTag + "\",\"Kp\":2.0}"),
                Instant.parse(fetchedAt), URL).orElseThrow();
    }

    private static Reading xray(String timeTag, String fetchedAt, int satellite) {
        return Reading.of("swpc.goes.xrays", JSON.readTree("{\"time_tag\":\"" + timeTag + "\",\"satellite\":"
                + satellite + ",\"flux\":2e-7,\"observed_flux\":2e-7,\"energy\":\"0.1-0.8nm\"}"),
                Instant.parse(fetchedAt), URL).orElseThrow();
    }

    @ParameterizedTest(name = "time_tag {0} fetched {1} is {2}")
    @CsvSource({
            "2026-09-20T00:04:00Z, 2026-09-20T00:00:00Z, NONE",
            "2026-09-20T00:05:00Z, 2026-09-20T00:00:00Z, NONE",
            "2026-09-20T00:05:01Z, 2026-09-20T00:00:00Z, REJECTED",
            "2300-01-01T00:00:00Z, 2026-09-20T00:00:00Z, REJECTED",
            "2016-01-01T00:00:00Z, 2026-09-20T00:00:00Z, NONE"})
    void aGoesTimeMoreThanFiveMinutesAfterTheFetchIsRejected(String timeTag, String fetchedAt,
            Reading.Outcome outcome) {
        Reading r = xray(timeTag, fetchedAt, 18);

        assertThat(r.outcome()).isEqualTo(outcome);
        if (outcome == Reading.Outcome.REJECTED) {
            assertThat(r.reason()).contains("after").contains("fetched_at");
            assertThat(r.placeable()).isFalse();
        }
    }

    @Test
    void aFutureKpIntervalIsRejected() {
        assertThat(kp("2300-01-01T00:00:00", "2026-09-20T03:04:00Z").outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(kp("2026-09-20T00:00:00", "2026-09-20T03:04:00Z").outcome()).isEqualTo(Reading.Outcome.NONE);
    }

    @ParameterizedTest(name = "Kp at {0}")
    @CsvSource({"2026-09-20T01:30:00", "2026-09-20T03:00:01", "2026-09-20T04:00:00"})
    void aKpTimeOffTheThreeHourGridIsRejected(String timeTag) {
        Reading r = kp(timeTag, "2026-09-20T12:00:00Z");

        assertThat(r.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(r.reason()).contains("3 hour");
        assertThat(r.placeable()).isFalse();
    }

    @Test
    void aTimeThatIsNotARealDateIsRejectedWithAReason() {
        Reading r = kp("2026-02-30T00:00:00", "2026-09-20T12:00:00Z");

        assertThat(r.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(r.reason()).contains("time_tag");
    }

    @Test
    void aSatelliteBelowOneIsRejected() {
        assertThat(xray("2026-09-20T00:00:00Z", "2026-09-20T00:05:00Z", 0).outcome())
                .isEqualTo(Reading.Outcome.REJECTED);
    }

    @Test
    void aRecordFromBeyondTheEnginesOwnClockIsRejected() {
        Instant now = Instant.parse("2026-09-20T12:00:00Z");
        String record = "{\"time_tag\":\"2300-01-01T00:00:00\",\"Kp\":2.0}";

        Reading r = Reading.of("swpc.kp", JSON.readTree(record), Instant.parse("2300-01-01T03:04:00Z"), URL, now)
                .orElseThrow();

        assertThat(r.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(r.reason()).contains("clock");
        assertThat(r.placeable()).isFalse();
        assertThat(Reading.of("swpc.kp", JSON.readTree("{\"time_tag\":\"2026-09-20T09:00:00\",\"Kp\":2.0}"),
                Instant.parse("2026-09-20T12:04:00Z"), URL, now).orElseThrow().outcome())
                .isEqualTo(Reading.Outcome.NONE);
    }

    @Test
    void aGoesRecordWithoutItsBandCannotBePlacedInASeries() {
        Reading r = Reading.of("swpc.goes.xrays", JSON.readTree(
                "{\"time_tag\":\"2026-09-20T00:00:00Z\",\"satellite\":18,\"flux\":2e-7,\"observed_flux\":2e-7}"),
                Instant.parse("2026-09-20T00:03:00Z"), URL).orElseThrow();

        assertThat(r.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(r.reason()).contains("energy");
        assertThat(r.placeable()).isFalse();
    }

    /** ISO 8601 allows 24:00:00 for the end of a day, and Java reads it as 00:00 the next day; Section 5.1 rejects it. */
    @Test
    void anHourOf24IsRejectedEvenAtMidnight() {
        Reading kp = kp("2026-09-20T24:00:00", "2026-09-21T12:00:00Z");
        Reading goes = xray("2026-09-20T24:00:00Z", "2026-09-21T00:00:00Z", 18);

        assertThat(kp.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(kp.reason()).contains("time_tag");
        assertThat(goes.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(xray("2026-09-20t24:00:00Z", "2026-09-21T00:00:00Z", 18).outcome())
                .isEqualTo(Reading.Outcome.REJECTED);
    }

    /** A second of 60 is read only at 23:59:60, as 23:59:59 of that date, on any date (Section 5.1, item 3). */
    @Test
    void aSecondOf60IsAcceptedOnlyAtTheLastMinuteOfADay() {
        Reading leap = xray("2016-12-31T23:59:60Z", "2017-01-01T00:00:00Z", 18);
        Reading anyDate = xray("2026-09-20T23:59:60Z", "2026-09-21T00:00:00Z", 18);
        Reading otherMinute = xray("2026-09-20T23:58:60Z", "2026-09-21T00:00:00Z", 18);
        Reading kp = kp("2016-12-31T23:59:60", "2017-01-01T03:00:00Z");

        assertThat(leap.outcome()).isEqualTo(Reading.Outcome.NONE);
        assertThat(leap.time()).isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
        assertThat(anyDate.outcome()).isEqualTo(Reading.Outcome.NONE);
        assertThat(anyDate.time()).isEqualTo(Instant.parse("2026-09-20T23:59:59Z"));
        assertThat(otherMinute.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(otherMinute.reason()).contains("time_tag");
        assertThat(kp.outcome()).isEqualTo(Reading.Outcome.REJECTED);
        assertThat(kp.reason()).contains("3 hour");
    }
}
