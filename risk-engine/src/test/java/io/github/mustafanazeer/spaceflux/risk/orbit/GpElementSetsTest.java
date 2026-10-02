package io.github.mustafanazeer.spaceflux.risk.orbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

import org.hipparchus.util.FastMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeOffset;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GpElementSetsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = GpElementSetsTest.class.getResourceAsStream("/celestrak/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    private static ObjectNode iss() throws IOException {
        return (ObjectNode) fixture("gp-catnr-25544.json").get(0).deepCopy();
    }

    @Test
    void mapsEveryFieldOfTheIssRecordInOrekitUnits() throws IOException {
        TLE tle = GpElementSets.toTle(iss());

        assertThat(tle.getSatelliteNumber()).isEqualTo(25544);
        assertThat(tle.getClassification()).isEqualTo('U');
        assertThat(tle.getLaunchYear()).isEqualTo(1998);
        assertThat(tle.getLaunchNumber()).isEqualTo(67);
        assertThat(tle.getLaunchPiece()).isEqualTo("A");
        assertThat(tle.getEphemerisType()).isZero();
        assertThat(tle.getElementNumber()).isEqualTo(999);
        assertThat(tle.getDate()).isEqualTo(new AbsoluteDate(2026, 9, 27, 4, 10, 0.0, OrekitData.utc()).shiftedBy(new TimeOffset(50L, 460_096_000_000_000_000L)));
        assertThat(tle.getMeanMotion()).isEqualTo(15.48664528 * Math.PI / 43200);
        assertThat(tle.getMeanMotionFirstDerivative()).isEqualTo(9.528e-5 * Math.PI / 1.86624e9);
        assertThat(tle.getMeanMotionSecondDerivative()).isZero();
        assertThat(tle.getE()).isEqualTo(0.0007168);
        assertThat(tle.getI()).isEqualTo(FastMath.toRadians(51.6315));
        assertThat(tle.getRaan()).isEqualTo(FastMath.toRadians(155.3455));
        assertThat(tle.getPerigeeArgument()).isEqualTo(FastMath.toRadians(193.0559));
        assertThat(tle.getMeanAnomaly()).isEqualTo(FastMath.toRadians(167.0244));
        assertThat(tle.getRevolutionNumberAtEpoch()).isEqualTo(58756);
        assertThat(tle.getBStar()).isEqualTo(0.00018291);
    }

    @Test
    void buildsTheSameElementSetAsOrekitsTextParserForEveryReferenceCase() throws IOException {
        for (String[] lines : ReferenceCases.elementSets()) {
            TLE text = new TLE(lines[0], lines[1], OrekitData.utc());
            TLE json = GpElementSets.toTle(ReferenceCases.toGpJson(lines[0], lines[1]));

            assertThat(json.getDate()).as("epoch of %s", lines[0]).isEqualTo(text.getDate());
            assertThat(json.getMeanMotion()).isEqualTo(text.getMeanMotion());
            assertThat(json.getMeanMotionFirstDerivative()).isEqualTo(text.getMeanMotionFirstDerivative());
            assertThat(json.getMeanMotionSecondDerivative()).isEqualTo(text.getMeanMotionSecondDerivative());
            assertThat(json.getE()).isEqualTo(text.getE());
            assertThat(json.getI()).isEqualTo(text.getI());
            assertThat(json.getRaan()).isEqualTo(text.getRaan());
            assertThat(json.getPerigeeArgument()).isEqualTo(text.getPerigeeArgument());
            assertThat(json.getMeanAnomaly()).isEqualTo(text.getMeanAnomaly());
            assertThat(json.getBStar()).isEqualTo(text.getBStar());
        }
    }

    @Test
    void acceptsCatalogNumbersAboveFiveDigits() throws IOException {
        JsonNode stations = fixture("gp-stations.json");
        JsonNode soyuz = null;
        for (JsonNode gp : stations) {
            if (gp.get("NORAD_CAT_ID").asInt() == 100057) {
                soyuz = gp;
            }
        }

        TLE tle = GpElementSets.toTle(soyuz);

        assertThat(tle.getSatelliteNumber()).isEqualTo(100057);
    }

    @Test
    void mapsEveryRecordOfTheStationsGroup() throws IOException {
        for (JsonNode gp : fixture("gp-stations.json")) {
            assertThat(GpElementSets.toTle(gp).getSatelliteNumber()).isEqualTo(gp.get("NORAD_CAT_ID").asInt());
        }
    }

    @Test
    void keepsLaunchFieldsEmptyWhenObjectIdIsMissing() throws IOException {
        ObjectNode gp = iss();
        gp.remove("OBJECT_ID");

        TLE tle = GpElementSets.toTle(gp);

        assertThat(tle.getLaunchYear()).isZero();
        assertThat(tle.getLaunchNumber()).isZero();
        assertThat(tle.getLaunchPiece()).isEmpty();
    }

    @Test
    void treatsAMalformedObjectIdAsMissing() throws IOException {
        ObjectNode gp = iss();
        gp.put("OBJECT_ID", "1998 067A");

        TLE tle = GpElementSets.toTle(gp);

        assertThat(tle.getLaunchYear()).isZero();
        assertThat(tle.getLaunchPiece()).isEmpty();
    }

    static Stream<Arguments> invalidRecords() {
        return Stream.of(
                Arguments.of("EPOCH", MAPPER.getNodeFactory().textNode("not a date"), "EPOCH"),
                Arguments.of("EPOCH", MAPPER.getNodeFactory().textNode("2026-09-27T24:00:00"), "EPOCH"),
                Arguments.of("EPOCH", MAPPER.getNodeFactory().textNode("2026-09-27T24:00:00.000000"), "EPOCH"),
                Arguments.of("MEAN_MOTION", MAPPER.getNodeFactory().numberNode(0.0), "MEAN_MOTION"),
                Arguments.of("MEAN_MOTION", MAPPER.getNodeFactory().numberNode(-1.0), "MEAN_MOTION"),
                Arguments.of("ECCENTRICITY", MAPPER.getNodeFactory().numberNode(1.0), "ECCENTRICITY"),
                Arguments.of("ECCENTRICITY", MAPPER.getNodeFactory().numberNode(-0.1), "ECCENTRICITY"),
                Arguments.of("INCLINATION", MAPPER.getNodeFactory().textNode("51.6"), "INCLINATION"),
                Arguments.of("BSTAR", MAPPER.getNodeFactory().nullNode(), "BSTAR"),
                Arguments.of("BSTAR", MAPPER.getNodeFactory().numberNode(Double.NaN), "BSTAR"),
                Arguments.of("NORAD_CAT_ID", MAPPER.getNodeFactory().numberNode(0), "NORAD_CAT_ID"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidRecords")
    void rejectsInvalidField(String field, JsonNode value, String reasonMentions) throws IOException {
        ObjectNode gp = iss();
        gp.set(field, value);

        assertThatThrownBy(() -> GpElementSets.toTle(gp))
                .isInstanceOf(InvalidElementSetException.class)
                .hasMessageContaining(reasonMentions);
    }

    @Test
    void rejectsMissingRequiredField() throws IOException {
        ObjectNode gp = iss();
        gp.remove("MEAN_ANOMALY");

        assertThatThrownBy(() -> GpElementSets.toTle(gp))
                .isInstanceOf(InvalidElementSetException.class)
                .hasMessageContaining("MEAN_ANOMALY");
    }
}
