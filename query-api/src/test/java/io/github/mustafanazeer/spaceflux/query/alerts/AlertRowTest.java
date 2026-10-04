package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AlertRowTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");

    static ObjectNode example(String file) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    @Test
    void theEnvelopeOfAnExampleBecomesItsRow() throws Exception {
        AlertRow row = AlertRow.of(example("valid-g-level.json"));

        assertThat(row.eventId()).isEqualTo("space_weather_level/1/G/-/2024-05-10T15:00:00/2026-09-27T22:05:05Z");
        assertThat(row.kind()).isEqualTo("space_weather_level");
        assertThat(row.schemaVersion()).isEqualTo(1);
        assertThat(row.rulesVersion()).isEqualTo(1L);
        assertThat(row.producedAt()).isEqualTo(LocalDateTime.of(2026, 9, 30, 18, 50, 27));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-04T01:02:03.123456789Z, 2026-10-04T01:02:03.123456",
            "2026-10-04T01:02:03.9999999Z, 2026-10-04T01:02:03.999999",
            "2026-10-04T01:02:03.5Z, 2026-10-04T01:02:03.500",
            "2016-12-31T23:59:60.25Z, 2016-12-31T23:59:59.250",
            "1000-01-01T00:00:00Z, 1000-01-01T00:00:00"})
    void producedAtIsTruncatedToMicrosecondsAndALeapSecondIsReadAsSecond59(String sent, LocalDateTime stored)
            throws Exception {
        ObjectNode e = example("valid-g-level.json");
        e.put("produced_at", sent);

        assertThat(AlertRow.of(e).producedAt()).isEqualTo(stored);
    }

    @Test
    void producedAtBeforeTheEarliestDatetimeDoesNotFit() throws Exception {
        ObjectNode e = example("valid-g-level.json");
        e.put("produced_at", "0999-12-31T23:59:59Z");

        assertThatThrownBy(() -> AlertRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("produced_at 0999-12-31T23:59:59Z is before 1000-01-01, the earliest DATETIME");
    }

    @Test
    void anEventIdOf512CharactersFitsAndOneOf513DoesNot() throws Exception {
        String prefix = "space_weather_level/1/";
        ObjectNode e = example("valid-g-level.json");
        // Two byte characters: the limit is characters, as VARCHAR(512) counts them, not bytes.
        e.put("event_id", prefix + "\u00e9".repeat(512 - prefix.length()));
        assertThat(AlertRow.of(e).eventId()).hasSize(512);

        e.put("event_id", prefix + "\u00e9".repeat(513 - prefix.length()));
        assertThatThrownBy(() -> AlertRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("event_id is 513 characters, longer than the 512 its column holds");
    }

    @Test
    void anEventIdIsCountedInCodePointsNotUtf16Units() throws Exception {
        String prefix = "space_weather_level/1/";
        ObjectNode e = example("valid-g-level.json");
        e.put("event_id", prefix + "\uD834\uDD1E".repeat(512 - prefix.length()));

        assertThat(AlertRow.of(e).eventId().codePointCount(0, AlertRow.of(e).eventId().length())).isEqualTo(512);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDFFF", "a\uDC00\uD800b", "end\uD83D"})
    void anEventIdThatIsNotWellFormedUnicodeDoesNotFit(String bad) throws Exception {
        ObjectNode e = example("valid-g-level.json");
        e.put("event_id", "space_weather_level/1/" + bad);

        // Connector/J would send each unpaired surrogate as '?', so two different ids would collide.
        assertThatThrownBy(() -> AlertRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("event_id is not well formed Unicode: it holds an unpaired surrogate");
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 4_294_967_295L})
    void aRulesVersionUpToTheUnsignedIntMaximumFits(long version) throws Exception {
        ObjectNode e = example("valid-g-level.json");
        e.put("rules_version", version);

        assertThat(AlertRow.of(e).rulesVersion()).isEqualTo(version);
    }

    @Test
    void aRulesVersionAboveTheUnsignedIntMaximumDoesNotFit() throws Exception {
        ObjectNode e = example("valid-g-level.json");
        e.put("rules_version", new java.math.BigInteger("4294967296"));

        assertThatThrownBy(() -> AlertRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("rules_version 4294967296 is above 4294967295, the largest its column holds");
    }

    @Test
    void aRulesVersionFarBeyondALongDoesNotFitEither() throws Exception {
        ObjectNode e = example("valid-g-level.json");
        e.put("rules_version", new java.math.BigInteger("123456789012345678901234567890"));

        assertThatThrownBy(() -> AlertRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessageContaining("is above 4294967295");
    }
}
