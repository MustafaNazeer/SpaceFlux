package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The space_weather_series rules of docs/data/mysql-schema.md, applied one event at a time. */
class SeriesProjectionTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final LocalDateTime T15 = LocalDateTime.of(2024, 5, 10, 15, 0);
    static final LocalDateTime T18 = LocalDateTime.of(2024, 5, 10, 18, 0);

    static SpaceWeatherRow event(String file, Consumer<ObjectNode> change) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
        change.accept((ObjectNode) e.get("space_weather_level"));
        return SpaceWeatherRow.of(e);
    }

    static SpaceWeatherRow kpLevel() throws Exception {
        return event("valid-g-level.json", p -> { });
    }

    @Test
    void theFirstEventThatSetsAStateCreatesTheRowFromIt() throws Exception {
        SeriesState s = SeriesProjection.apply(null, kpLevel(), 7);

        assertThat(s.scale()).isEqualTo("G");
        assertThat(s.seriesSatellite()).isZero();
        assertThat(s.rulesVersion()).isEqualTo(1);
        assertThat(s.state()).isEqualTo("level");
        assertThat(s.derivedLevel()).isEqualTo(4);
        assertThat(s.derivedLabel()).isEqualTo("G4");
        assertThat(s.value()).isEqualTo(7.67);
        assertThat(s.unit()).isEqualTo("Kp index");
        assertThat(s.timeTag()).isEqualTo("2024-05-10T15:00:00");
        assertThat(s.intervalStart()).isEqualTo(T15);
        assertThat(s.freshnessReference()).isEqualTo(T15);
        assertThat(s.stateAlertSeq()).isEqualTo(7);
        assertThat(s.lastAlertSeq()).isEqualTo(7);
    }

    @Test
    void aFirstEventThatDoesNotSetAStateCreatesNoRow() throws Exception {
        assertThat(SeriesProjection.apply(null, event("valid-r-restatement.json", p -> { }), 3)).isNull();
        SpaceWeatherRow olderRevision = event("valid-g-level.json", p -> {
            p.put("trigger", "revision");
            p.put("interval_start", "2024-05-10T12:00:00Z");
        });
        assertThat(SeriesProjection.apply(null, olderRevision, 4)).isNull();
    }

    @Test
    void aRestatementNeverSetsTheStateButMovesFreshnessAndTheLastEvent() throws Exception {
        SeriesState level = SeriesProjection.apply(null, event("valid-r-level.json", p -> {
            p.put("satellite", 18);
            p.put("freshness_reference", "2026-09-24T08:26:00Z");
        }), 10);

        SeriesState after = SeriesProjection.apply(level, event("valid-r-restatement.json", p -> { }), 11);

        assertThat(after.state()).isEqualTo("level");
        assertThat(after.stateAlertSeq()).isEqualTo(10);
        assertThat(after.lastAlertSeq()).isEqualTo(11);
        assertThat(after.freshnessReference()).isEqualTo(LocalDateTime.of(2026, 9, 24, 8, 29));
    }

    @Test
    void aRefreshSetsTheStateAndFreshnessNeverMovesBackwards() throws Exception {
        SeriesState level = SeriesProjection.apply(null, kpLevel(), 1);

        SeriesState after = SeriesProjection.apply(level, event("valid-g-level.json", p -> {
            p.put("trigger", "refresh");
            p.put("state", "none");
            p.remove("derived_level");
            p.put("derived_label", "none");
            p.put("value", 2.0);
            p.put("freshness_reference", "2024-05-10T12:00:00Z");
        }), 2);

        assertThat(after.state()).isEqualTo("none");
        assertThat(after.derivedLevel()).isNull();
        assertThat(after.stateAlertSeq()).isEqualTo(2);
        assertThat(after.freshnessReference()).isEqualTo(T15);
    }

    @Test
    void aRevisionOfTheNewestIntervalSetsTheState() throws Exception {
        SeriesState level = SeriesProjection.apply(null, kpLevel(), 1);

        SeriesState after = SeriesProjection.apply(level, event("valid-g-level.json", p -> {
            p.put("trigger", "revision");
            p.put("value", 8.33);
            p.put("derived_level", 5);
            p.put("derived_label", "G5");
        }), 2);

        assertThat(after.derivedLabel()).isEqualTo("G5");
        assertThat(after.value()).isEqualTo(8.33);
        assertThat(after.stateAlertSeq()).isEqualTo(2);
    }

    @Test
    void aRevisionOfAnOlderIntervalIsHistoryOnly() throws Exception {
        SeriesState level = SeriesProjection.apply(null, kpLevel(), 1);

        SeriesState after = SeriesProjection.apply(level, event("valid-g-level.json", p -> {
            p.put("trigger", "revision");
            p.put("interval_start", "2024-05-10T12:00:00Z");
            p.put("interval_end", "2024-05-10T15:00:00Z");
            p.put("derived_label", "G5");
        }), 2);

        assertThat(after.derivedLabel()).isEqualTo("G4");
        assertThat(after.stateAlertSeq()).isEqualTo(1);
        assertThat(after.lastAlertSeq()).isEqualTo(2);
    }

    @Test
    void aRevisionThatIsItselfTheNewestIntervalSetsTheStateAfterFreshnessMoves() throws Exception {
        SeriesState level = SeriesProjection.apply(null, kpLevel(), 1);

        SeriesState after = SeriesProjection.apply(level, event("valid-g-level.json", p -> {
            p.put("trigger", "revision");
            p.put("interval_start", "2024-05-10T18:00:00Z");
            p.put("freshness_reference", "2024-05-10T18:00:00Z");
            p.put("derived_label", "G3");
            p.put("derived_level", 3);
        }), 2);

        assertThat(after.derivedLabel()).isEqualTo("G3");
        assertThat(after.intervalStart()).isEqualTo(T18);
        assertThat(after.freshnessReference()).isEqualTo(T18);
    }

    @Test
    void anEventUnderAnOlderRulesVersionIsNotAppliedAtAll() throws Exception {
        SeriesState v2 = SeriesProjection.apply(null, event("valid-g-level.json", p -> { }) , 1);
        v2 = new SeriesState(v2.scale(), v2.seriesSatellite(), 2, v2.state(), v2.derivedLevel(), v2.derivedLabel(),
                v2.value(), v2.unit(), v2.xrayClass(), v2.timeTag(), v2.intervalStart(), v2.sampleTime(),
                v2.noDataReason(), v2.noDataSince(), v2.endedBySatellite(), v2.freshnessReference(), 1, 1);

        SpaceWeatherRow v1Newer = event("valid-g-level.json", p -> p.put("freshness_reference",
                "2024-05-11T00:00:00Z"));
        SeriesState after = SeriesProjection.apply(v2, v1Newer, 2);

        assertThat(after).isSameAs(v2);
    }

    @Test
    void anEventUnderANewerRulesVersionSetsTheStateAndItsVersion() throws Exception {
        SeriesState v1 = SeriesProjection.apply(null, kpLevel(), 1);
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve("valid-g-level.json")));
        e.put("rules_version", 2);

        SeriesState after = SeriesProjection.apply(v1, SpaceWeatherRow.of(e), 2);

        assertThat(after.rulesVersion()).isEqualTo(2);
        assertThat(after.stateAlertSeq()).isEqualTo(2);
    }

    @Test
    void endedSetsTheStateWithTheSatelliteThatTookOver() throws Exception {
        SeriesState level = SeriesProjection.apply(null, event("valid-r-level.json", p -> { }), 1);

        SeriesState after = SeriesProjection.apply(level, event("valid-r-no-data.json", p -> {
            p.put("satellite", 16);
            p.put("state", "ended");
            p.remove("no_data_reason");
            p.put("ended_by_satellite", 18);
            p.put("no_data_since", "2024-05-10T03:24:00Z");
            p.put("freshness_reference", "2024-05-10T03:24:00Z");
        }), 2);

        assertThat(after.state()).isEqualTo("ended");
        assertThat(after.endedBySatellite()).isEqualTo(18);
        assertThat(after.value()).isNull();
        assertThat(after.noDataReason()).isNull();
        assertThat(after.stateAlertSeq()).isEqualTo(2);
    }
}
