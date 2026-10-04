package io.github.mustafanazeer.spaceflux.query.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.RuleRejected;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CatalogRowTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLE = Path.of("..", "schemas", "raw.gp", "examples", "valid-iss.json");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 12, 0);

    static ObjectNode iss() throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(EXAMPLE));
    }

    static ObjectNode gp(ObjectNode event) {
        return (ObjectNode) event.get("gp");
    }

    @Test
    void theIssElementSetBecomesItsRow() throws Exception {
        CatalogRow r = CatalogRow.of(iss(), NOW);

        assertThat(r.noradCatId()).isEqualTo(25544);
        assertThat(r.objectName()).isEqualTo("ISS (ZARYA)");
        assertThat(r.objectNameCut()).isFalse();
        assertThat(r.objectId()).isEqualTo("1998-067A");
        assertThat(r.epoch()).isEqualTo(LocalDateTime.of(2026, 9, 27, 4, 10, 50, 460_096_000));
        assertThat(r.epochText()).isEqualTo("2026-09-27T04:10:50.460096");
        assertThat(r.meanMotion()).isEqualTo(15.48664528);
        assertThat(r.eccentricity()).isEqualTo(0.0007168);
        assertThat(r.inclination()).isEqualTo(51.6315);
        assertThat(r.raOfAscNode()).isEqualTo(155.3455);
        assertThat(r.argOfPericenter()).isEqualTo(193.0559);
        assertThat(r.meanAnomaly()).isEqualTo(167.0244);
        assertThat(r.bstar()).isEqualTo(0.00018291);
        assertThat(r.meanMotionDot()).isEqualTo(9.528e-05);
        assertThat(r.meanMotionDdot()).isEqualTo(0.0);
        assertThat(r.ephemerisType()).isZero();
        assertThat(r.classificationType()).isEqualTo("U");
        assertThat(r.elementSetNo()).isEqualTo(999);
        assertThat(r.revAtEpoch()).isEqualTo(58756);
        assertThat(r.fetchedAt()).isEqualTo(LocalDateTime.of(2026, 9, 27, 8, 57, 39));
        assertThat(r.sourceUrl()).isEqualTo("https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json");
    }

    @Test
    void anObjectWithoutNameOrDesignatorKeepsThemNull() throws Exception {
        ObjectNode e = iss();
        gp(e).remove("OBJECT_NAME");
        gp(e).remove("OBJECT_ID");

        CatalogRow r = CatalogRow.of(e, NOW);

        assertThat(r.objectName()).isNull();
        assertThat(r.objectNameCut()).isFalse();
        assertThat(r.objectId()).isNull();
    }

    @Test
    void aNameOf64CodePointsIsKeptWhole() throws Exception {
        ObjectNode e = iss();
        gp(e).put("OBJECT_NAME", "N".repeat(64));

        CatalogRow r = CatalogRow.of(e, NOW);

        assertThat(r.objectName()).isEqualTo("N".repeat(64));
        assertThat(r.objectNameCut()).isFalse();
    }

    @Test
    void aLongerNameIsCutTo61CodePointsAndAnEllipsisAsAlertsCutIt() throws Exception {
        ObjectNode e = iss();
        gp(e).put("OBJECT_NAME", "A".repeat(60) + "BCDEFG");

        CatalogRow r = CatalogRow.of(e, NOW);

        assertThat(r.objectName()).isEqualTo("A".repeat(60) + "B...");
        assertThat(r.objectNameCut()).isTrue();
    }

    @Test
    void aNameThatIsNotWellFormedUnicodeDoesNotFit() throws Exception {
        ObjectNode e = iss();
        gp(e).put("OBJECT_NAME", "ISS \uD800");

        assertThatThrownBy(() -> CatalogRow.of(e, NOW)).isInstanceOf(NotStorable.class)
                .hasMessage("OBJECT_NAME is not well formed Unicode: it holds an unpaired surrogate");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-27T04:10:50.123456789, 2026-09-27T04:10:50.123456",
            "2026-09-27T04:10:50, 2026-09-27T04:10:50",
            "2016-12-31T23:59:60.5, 2016-12-31T23:59:59.500"})
    void theEpochIsReadAsUtcTruncatedToMicrosecondsAndKeptAsSent(String sent, LocalDateTime stored)
            throws Exception {
        ObjectNode e = iss();
        e.put("fetched_at", "2027-01-01T00:00:00Z");
        gp(e).put("EPOCH", sent);

        CatalogRow r = CatalogRow.of(e, LocalDateTime.of(2027, 1, 1, 0, 0));

        assertThat(r.epoch()).isEqualTo(stored);
        assertThat(r.epochText()).isEqualTo(sent);
    }

    @Test
    void aMeanMotionADoubleCannotHoldExactlyDoesNotFit() throws Exception {
        ObjectNode e = iss();
        gp(e).put("MEAN_MOTION", new BigDecimal("15.4866452800000000000000001"));

        assertThatThrownBy(() -> CatalogRow.of(e, NOW)).isInstanceOf(NotStorable.class)
                .hasMessageContaining("MEAN_MOTION").hasMessageContaining("exactly as a DOUBLE");
    }

    @Test
    void aRevolutionNumberBeyondALongDoesNotFit() throws Exception {
        ObjectNode e = iss();
        gp(e).put("REV_AT_EPOCH", new java.math.BigInteger("9223372036854775808"));

        assertThatThrownBy(() -> CatalogRow.of(e, NOW)).isInstanceOf(NotStorable.class)
                .hasMessageContaining("REV_AT_EPOCH");
    }

    @Test
    void aFetchTimeMoreThanAnHourAfterTheClockIsRefusedByRule() throws Exception {
        ObjectNode e = iss();
        e.put("fetched_at", "2026-10-04T13:00:00.000001Z");
        gp(e).put("EPOCH", "2026-10-04T12:30:00");

        assertThatThrownBy(() -> CatalogRow.of(e, NOW)).isInstanceOf(RuleRejected.class)
                .hasMessage("fetched_at 2026-10-04T13:00:00.000001Z is more than 1 hour after "
                        + "2026-10-04T12:00:00Z, when it was read");
    }

    @Test
    void anEpochMoreThanFiveMinutesAfterItsOwnFetchIsRefusedByRule() throws Exception {
        ObjectNode e = iss();
        gp(e).put("EPOCH", "2026-09-27T09:02:39.000001");

        assertThatThrownBy(() -> CatalogRow.of(e, NOW)).isInstanceOf(RuleRejected.class)
                .hasMessage("EPOCH 2026-09-27T09:02:39.000001Z is more than 5 minutes after fetched_at "
                        + "2026-09-27T08:57:39Z");
    }

    @Test
    void anEpochExactlyFiveMinutesAfterItsFetchIsAccepted() throws Exception {
        ObjectNode e = iss();
        gp(e).put("EPOCH", "2026-09-27T09:02:39");

        assertThat(CatalogRow.of(e, NOW).epoch()).isEqualTo(LocalDateTime.of(2026, 9, 27, 9, 2, 39));
    }
}
