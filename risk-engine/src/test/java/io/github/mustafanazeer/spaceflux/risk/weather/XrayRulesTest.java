package io.github.mustafanazeer.spaceflux.risk.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The X-ray value guards of docs/risk/space-weather-scales.md Section 5.1 and the X-ray class of Section 2.4. The
 * boundary values are not real data; the recorded eclipse and flare class files under swpc-xrays are.
 */
class XrayRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode resource(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(XrayRulesTest.class.getResourceAsStream(path), path)) {
            return JSON.readTree(in);
        }
    }

    @Test
    void aZeroFluxIsTheMissingMarkerNotALevel() {
        assertThatThrownBy(() -> StormRules.rLevel(0.0))
                .isInstanceOf(MissingValueException.class)
                .hasMessageContaining("\"flux\"");
    }

    @ParameterizedTest(name = "flux {0} is below the 1e-9 floor")
    @CsvSource({"1e-12", "5e-10", "9.99e-10"})
    void aFluxBelowTheFloorIsRejectedButIsNotTheMissingMarker(double flux) {
        assertThatThrownBy(() -> StormRules.rLevel(flux))
                .isInstanceOf(InvalidReadingException.class)
                .isNotInstanceOf(MissingValueException.class)
                .hasMessageContaining("below");
    }

    @Test
    void theFloorValueInTheFilesIsAValidNone() {
        assertThat(StormRules.rLevel(9.999999717180685e-10)).isZero();
        assertThat(StormRules.rLevel(1e-9)).isZero();
    }

    @Test
    void aFluxAboveTheUpperBoundIsRejected() {
        assertThatThrownBy(() -> StormRules.rLevel(0.21))
                .isInstanceOf(InvalidReadingException.class)
                .isNotInstanceOf(MissingValueException.class)
                .hasMessageContaining("above");
        assertThat(StormRules.rLevel(0.2)).isEqualTo(5);
    }

    @Test
    void theProtonScaleKeepsItsOwnRules() {
        assertThat(StormRules.sLevel(0.0)).isZero();
    }

    @Test
    void everyZeroMinuteOfTheRecordedEclipseIsTheMissingMarker() throws IOException {
        JsonNode records = resource("/swpc-xrays/goes18-xrays-7-day-eclipse-2026-09-24.json");
        List<String> missing = new ArrayList<>();
        int levels = 0;
        for (JsonNode r : records) {
            try {
                if (StormRules.derive("swpc.goes.xrays", r).isPresent()) {
                    levels++;
                }
            } catch (MissingValueException e) {
                if ("0.1-0.8nm".equals(r.get("energy").asString())) {
                    missing.add(r.get("time_tag").asString());
                }
            }
        }
        assertThat(missing).hasSize(66).first().isEqualTo("2026-09-24T08:27:00Z");
        assertThat(missing).last().isEqualTo("2026-09-24T09:32:00Z");
        assertThat(levels).isEqualTo(186 - 66);
    }

    @ParameterizedTest(name = "flux {0} is class {1}")
    @CsvSource({
            "9.999999747378752e-6, M1.0", "1e-5, M1.0", "4.19e-5, M4.1", "4.9999e-5, M4.9", "5e-5, M5.0",
            "9.99e-5, M9.9", "1e-4, X1.0", "3.9789e-4, X3.9", "1e-3, X10.0", "2e-3, X20.0", "1.02e-2, X102.0"})
    void theClassTruncatesTheShortestFloatDecimal(double flux, String xrayClass) {
        assertThat(StormRules.xrayClass(flux, 16)).contains(xrayClass);
    }

    @Test
    void noClassBelowR1OrBeforeGoes16() {
        assertThat(StormRules.xrayClass(9.9e-6, 18)).isEmpty();
        assertThat(StormRules.xrayClass(3.9789e-4, 15)).isEmpty();
    }

    @Test
    void theClassMatchesSwpcForEveryRecordedMay2024Flare() throws IOException {
        JsonNode rows = resource("/swpc-xrays/goes16-flare-classes-2024-05-10.json");
        assertThat(rows).hasSize(12);
        for (JsonNode row : rows) {
            double flux = row.get("flux").asDouble();
            String swpc = row.get("swpc_class").asString();
            var derived = StormRules.xrayClass(flux, row.get("satellite").asInt());
            if (swpc.startsWith("M") || swpc.startsWith("X")) {
                assertThat(derived).as(row.get("time_tag").asString()).contains(swpc);
            } else {
                assertThat(derived).as(row.get("time_tag").asString()).isEmpty();
            }
        }
    }
}
