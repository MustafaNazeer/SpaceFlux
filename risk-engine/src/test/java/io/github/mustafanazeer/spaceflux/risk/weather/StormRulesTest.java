package io.github.mustafanazeer.spaceflux.risk.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Boundary rule tests for docs/risk/space-weather-scales.md, Summary and Section 5. These values test the comparisons
 * and are not real data; the recorded storm periods are in {@link StormFixturesTest}.
 */
class StormRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode record(String json) {
        return JSON.readTree(json);
    }

    @ParameterizedTest(name = "Kp {0} is level {1}")
    @CsvSource({
            "0.0, 0", "4.33, 0", "4.66, 0", "4.665, 1", "4.67, 1", "5.0, 1", "5.33, 1",
            "5.66, 1", "5.67, 2", "6.33, 2", "6.66, 2", "6.67, 3", "7.0, 3", "7.33, 3",
            "7.66, 3", "7.67, 4", "8.0, 4", "8.33, 4", "8.67, 4", "8.99, 4", "9.0, 5"})
    void theGScaleReadsKpInThirds(double kp, int level) {
        assertThat(StormRules.gLevel(kp)).isEqualTo(level);
    }

    @ParameterizedTest(name = "flux {0} W/m2 is level {1}")
    @CsvSource({
            "9.99e-6, 0", "1e-5, 1", "1.01e-5, 1", "4.99e-5, 1", "5e-5, 2", "9.99e-5, 2", "1e-4, 3",
            "9.99e-4, 3", "1e-3, 4", "1.99e-3, 4", "2e-3, 5", "1e-2, 5"})
    void theRScaleReadsTheLongBandFlux(double flux, int level) {
        assertThat(StormRules.rLevel(flux)).isEqualTo(level);
    }

    @ParameterizedTest(name = "flux {0} pfu is level {1}")
    @CsvSource({
            "9.99, 0", "10, 1", "99.9, 1", "100, 2", "999, 2", "1000, 3", "9999, 3", "10000, 4",
            "99999, 4", "100000, 5", "1e6, 5"})
    void theSScaleReadsTheTenMevFlux(double flux, int level) {
        assertThat(StormRules.sLevel(flux)).isEqualTo(level);
    }

    @Test
    void derivesAGLevelFromAKpRecordWithItsInterval() {
        DerivedLevel d = StormRules.derive("swpc.kp", record("{\"time_tag\":\"2024-05-10T21:00:00\",\"Kp\":9.0}"))
                .orElseThrow();

        assertThat(d.scale()).isEqualTo(Scale.G);
        assertThat(d.level()).isEqualTo(5);
        assertThat(d.label()).isEqualTo("G5");
        assertThat(d.timeTag()).isEqualTo("2024-05-10T21:00:00");
        assertThat(d.satellite()).isNull();
        assertThat(d.value()).isEqualTo(9.0);
    }

    @Test
    void readsTheRScaleFromTheLongBandCorrectedFluxAndCarriesTheSatellite() {
        DerivedLevel d = StormRules.derive("swpc.goes.xrays", record(
                "{\"time_tag\":\"2024-05-10T06:54:00Z\",\"satellite\":16,\"energy\":\"0.1-0.8nm\","
                        + "\"flux\":3.97e-4,\"observed_flux\":9.0e-6}"))
                .orElseThrow();

        assertThat(d.label()).isEqualTo("R3");
        assertThat(d.satellite()).isEqualTo(16);
    }

    @Test
    void ignoresTheShortBandAndOtherProtonChannels() {
        assertThat(StormRules.derive("swpc.goes.xrays", record(
                "{\"time_tag\":\"2024-05-10T06:54:00Z\",\"satellite\":16,\"energy\":\"0.05-0.4nm\",\"flux\":1e-3,"
                        + "\"observed_flux\":1e-3}"))).isEmpty();
        assertThat(StormRules.derive("swpc.goes.protons", record(
                "{\"time_tag\":\"2017-09-10T18:45:00Z\",\"satellite\":13,\"energy\":\">=100 MeV\",\"flux\":500}")))
                .isEmpty();
        assertThat(StormRules.derive("swpc.alerts", record("{\"product_id\":\"ALTK09\"}"))).isEmpty();
    }

    @Test
    void labelsAValidValueBelowLevelOneAsNone() {
        DerivedLevel d = StormRules.derive("swpc.goes.protons", record(
                "{\"time_tag\":\"2017-09-10T16:40:00Z\",\"satellite\":13,\"energy\":\">=10 MeV\",\"flux\":8.0004}"))
                .orElseThrow();

        assertThat(d.level()).isZero();
        assertThat(d.label()).isEqualTo("none");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "negative Kp | swpc.kp | {\"time_tag\":\"2024-05-10T00:00:00\",\"Kp\":-1} | negative",
            "Kp as text | swpc.kp | {\"time_tag\":\"2024-05-10T00:00:00\",\"Kp\":\"9\"} | not a number",
            "missing Kp | swpc.kp | {\"time_tag\":\"2024-05-10T00:00:00\"} | missing",
            "missing flux | swpc.goes.protons | {\"time_tag\":\"2017-09-10T16:40:00Z\",\"satellite\":13,\"energy\":\">=10 MeV\"} | missing",
            "missing satellite | swpc.goes.xrays | {\"time_tag\":\"2024-05-10T06:54:00Z\",\"energy\":\"0.1-0.8nm\",\"flux\":1e-6,\"observed_flux\":1e-6} | satellite",
            "negative flux | swpc.goes.xrays | {\"time_tag\":\"2024-05-10T06:54:00Z\",\"satellite\":16,\"energy\":\"0.1-0.8nm\",\"flux\":-1e-6,\"observed_flux\":1e-6} | negative"})
    void rejectsAnUnusableValueWithAReasonInsteadOfSettingALevel(String name, String product, String json,
            String reason) {
        assertThatThrownBy(() -> StormRules.derive(product, record(json)))
                .isInstanceOf(InvalidReadingException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void rejectsANonFiniteValue() {
        assertThatThrownBy(() -> StormRules.gLevel(Double.NaN)).isInstanceOf(InvalidReadingException.class);
        assertThatThrownBy(() -> StormRules.rLevel(Double.POSITIVE_INFINITY))
                .isInstanceOf(InvalidReadingException.class);
    }
}
