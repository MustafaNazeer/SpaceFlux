package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SpaceWeatherRowTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");

    static ObjectNode example(String file) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    static ObjectNode payload(ObjectNode event) {
        return (ObjectNode) event.get("space_weather_level");
    }

    @Test
    void aKpLevelBecomesItsRow() throws Exception {
        SpaceWeatherRow r = SpaceWeatherRow.of(example("valid-g-level.json"));

        assertThat(r.rulesVersion()).isEqualTo(1);
        assertThat(r.scale()).isEqualTo("G");
        assertThat(r.satellite()).isNull();
        assertThat(r.product()).isEqualTo("swpc.kp");
        assertThat(r.state()).isEqualTo("level");
        assertThat(r.derivedLevel()).isEqualTo(4);
        assertThat(r.derivedLabel()).isEqualTo("G4");
        assertThat(r.previousState()).isEqualTo("none");
        assertThat(r.previousDerivedLevel()).isNull();
        assertThat(r.triggerKind()).isEqualTo("level_change");
        assertThat(r.derivedFrom()).isEqualTo("SWPC estimated planetary Kp");
        assertThat(r.estimated()).isTrue();
        assertThat(r.value()).isEqualTo(7.67);
        assertThat(r.unit()).isEqualTo("Kp index");
        assertThat(r.timeTag()).isEqualTo("2024-05-10T15:00:00");
        assertThat(r.intervalStart()).isEqualTo(LocalDateTime.of(2024, 5, 10, 15, 0));
        assertThat(r.intervalEnd()).isEqualTo(LocalDateTime.of(2024, 5, 10, 18, 0));
        assertThat(r.sampleTime()).isNull();
        assertThat(r.fetchedAt()).isEqualTo(LocalDateTime.of(2026, 9, 27, 22, 5, 5));
        assertThat(r.sourceUrl()).startsWith("https://www.ngdc.noaa.gov/");
        assertThat(r.freshnessReference()).isEqualTo(LocalDateTime.of(2024, 5, 10, 15, 0));
        assertThat(r.noDataReason()).isNull();
        assertThat(r.seriesSatellite()).isZero();
    }

    @Test
    void anXrayRestatementKeepsItsSampleItsValueExactlyAndWhatRestatedIt() throws Exception {
        SpaceWeatherRow r = SpaceWeatherRow.of(example("valid-r-restatement.json"));

        assertThat(r.satellite()).isEqualTo(18);
        assertThat(r.seriesSatellite()).isEqualTo(18);
        assertThat(r.band()).isEqualTo("0.1-0.8nm");
        assertThat(r.channel()).isNull();
        assertThat(r.value()).isEqualTo(8.483063140829472e-09);
        assertThat(r.triggerKind()).isEqualTo("restatement");
        assertThat(r.sampleTime()).isEqualTo(LocalDateTime.of(2026, 9, 24, 8, 26));
        assertThat(r.averagingPeriodS()).isEqualTo(60);
        assertThat(r.noDataReason()).isEqualTo("zero_run_edge");
        assertThat(r.noDataSince()).isEqualTo(LocalDateTime.of(2026, 9, 24, 8, 26));
        assertThat(r.restatedByTimeTag()).isEqualTo(LocalDateTime.of(2026, 9, 24, 8, 27));
        assertThat(r.freshnessReference()).isEqualTo(LocalDateTime.of(2026, 9, 24, 8, 29));
    }

    @Test
    void anXrayLevelKeepsItsClass() throws Exception {
        assertThat(SpaceWeatherRow.of(example("valid-r-level.json")).xrayClass()).isEqualTo("M1.0");
    }

    @Test
    void aSatelliteOutsideTheIntRangeDoesNotFit() throws Exception {
        ObjectNode e = example("valid-r-level.json");
        payload(e).put("satellite", new BigInteger("2147483648"));

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("satellite 2147483648 is outside the range its INT column holds");
    }

    @Test
    void aBandLongerThanItsColumnDoesNotFit() throws Exception {
        ObjectNode e = example("valid-r-level.json");
        payload(e).put("band", "b".repeat(33));

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("band is 33 characters, longer than the 32 its column holds");
    }

    @Test
    void anXrayClassLongerThanItsColumnDoesNotFit() throws Exception {
        ObjectNode e = example("valid-r-level.json");
        payload(e).put("xray_class", "X1" + "0".repeat(13) + ".0");

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("xray_class is 17 characters, longer than the 16 its column holds");
    }

    @Test
    void aTimeTagLongerThanItsColumnDoesNotFit() throws Exception {
        ObjectNode e = example("valid-g-level.json");
        payload(e).put("time_tag", "t".repeat(65));

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("time_tag is 65 characters, longer than the 64 its column holds");
    }

    @Test
    void aDerivedFromLongerThanATextColumnDoesNotFit() throws Exception {
        ObjectNode e = example("valid-g-level.json");
        // 21,846 three byte characters are 65,538 bytes: a TEXT column holds 65,535 bytes, not characters.
        payload(e).put("derived_from", "\u20ac".repeat(21_846));

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("derived_from is 65538 bytes, longer than the 65535 its TEXT column holds");
    }

    @Test
    void aDerivedFromThatIsNotWellFormedUnicodeDoesNotFit() throws Exception {
        ObjectNode e = example("valid-g-level.json");
        payload(e).put("derived_from", "Kp \uD800");

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("derived_from is not well formed Unicode: it holds an unpaired surrogate");
    }

    @Test
    void aValueBeyondADoubleDoesNotFit() throws Exception {
        ObjectNode e = example("valid-r-level.json");
        payload(e).put("value", new BigDecimal("1e400"));

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("value 1E+400 cannot be stored exactly as a DOUBLE");
    }

    @Test
    void aValueBelowTheSmallestDoubleDoesNotFitRatherThanReadAsZero() throws Exception {
        ObjectNode e = example("valid-r-level.json");
        payload(e).put("value", new BigDecimal("1e-400"));

        assertThatThrownBy(() -> SpaceWeatherRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("value 1E-400 cannot be stored exactly as a DOUBLE");
    }
}
