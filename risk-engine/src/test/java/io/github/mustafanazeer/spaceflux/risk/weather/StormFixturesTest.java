package io.github.mustafanazeer.spaceflux.risk.weather;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The storm period fixtures, with the levels their PROVENANCE.md derives from the values and matches to SWPC. */
class StormFixturesTest {

    private static List<DerivedLevel> derive(String resource) throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<DerivedLevel> levels = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                StormFixturesTest.class.getResourceAsStream("/swpc-storms/" + resource), StandardCharsets.US_ASCII))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                JsonNode event = json.readTree(line);
                StormRules.derive(event.get("product").asString(), event.get("record")).ifPresent(levels::add);
            }
        }
        return levels;
    }

    private static Map<String, Long> countByLabel(List<DerivedLevel> levels) {
        return levels.stream().collect(Collectors.groupingBy(DerivedLevel::label, TreeMap::new, Collectors.counting()));
    }

    private static String firstAtOrAbove(List<DerivedLevel> levels, int level) {
        return levels.stream().filter(d -> d.level() >= level).findFirst().orElseThrow().timeTag();
    }

    @Test
    void theMay2024KpIntervalsGetTheLevelsSwpcAlertedFor() throws IOException {
        Map<String, String> expected = new LinkedHashMap<>();
        String[] rows = {
                "2024-05-10T00:00:00 none", "2024-05-10T03:00:00 none", "2024-05-10T06:00:00 none",
                "2024-05-10T09:00:00 none", "2024-05-10T12:00:00 none", "2024-05-10T15:00:00 G4",
                "2024-05-10T18:00:00 G4", "2024-05-10T21:00:00 G5", "2024-05-11T00:00:00 G5",
                "2024-05-11T03:00:00 G4", "2024-05-11T06:00:00 G4", "2024-05-11T09:00:00 G5",
                "2024-05-11T12:00:00 G4", "2024-05-11T15:00:00 G4", "2024-05-11T18:00:00 G3",
                "2024-05-11T21:00:00 G3", "2024-05-12T00:00:00 G3", "2024-05-12T03:00:00 G3",
                "2024-05-12T06:00:00 none", "2024-05-12T09:00:00 none", "2024-05-12T12:00:00 none",
                "2024-05-12T15:00:00 none", "2024-05-12T18:00:00 none", "2024-05-12T21:00:00 G2"};
        for (String row : rows) {
            expected.put(row.split(" ")[0], row.split(" ")[1]);
        }

        List<DerivedLevel> levels = derive("kp-2024-05-10-to-12.jsonl");

        Map<String, String> actual = new LinkedHashMap<>();
        levels.forEach(d -> actual.put(d.timeTag(), d.label()));
        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    @Test
    void theMay2024XrayMinutesReachR3WhereSwpcReportedX39() throws IOException {
        List<DerivedLevel> levels = derive("goes16-xrays-2024-05-10T03-09.jsonl");

        assertThat(levels).hasSize(360).allSatisfy(d -> assertThat(d.satellite()).isEqualTo(16));
        assertThat(countByLabel(levels))
                .containsExactlyEntriesOf(new TreeMap<>(Map.of("R1", 124L, "R2", 22L, "R3", 31L, "none", 183L)));
        assertThat(firstAtOrAbove(levels, 1)).isEqualTo("2024-05-10T03:24:00Z");
        assertThat(firstAtOrAbove(levels, 2)).isEqualTo("2024-05-10T06:41:00Z");
        assertThat(levels.stream().filter(d -> d.level() == 3).map(DerivedLevel::timeTag).toList())
                .first().isEqualTo("2024-05-10T06:43:00Z");
        assertThat(levels.stream().filter(d -> d.level() == 3).map(DerivedLevel::timeTag).toList())
                .last().isEqualTo("2024-05-10T07:13:00Z");
    }

    @Test
    void theSeptember2017ProtonValuesReachS3WhereSwpcAlerted() throws IOException {
        List<DerivedLevel> levels = derive("goes13-protons-2017-09-10T16-22.jsonl");

        assertThat(levels).hasSize(72).allSatisfy(d -> assertThat(d.satellite()).isEqualTo(13));
        Map<String, Long> counts = countByLabel(levels);
        assertThat(counts).containsExactlyEntriesOf(new TreeMap<>(Map.of("S1", 4L, "S2", 55L, "S3", 4L, "none", 9L)));
        assertThat(firstAtOrAbove(levels, 1)).isEqualTo("2017-09-10T16:45:00Z");
        assertThat(firstAtOrAbove(levels, 2)).isEqualTo("2017-09-10T17:05:00Z");
        assertThat(firstAtOrAbove(levels, 3)).isEqualTo("2017-09-10T18:40:00Z");
        assertThat(levels.stream().max(Comparator.comparingDouble(DerivedLevel::value)).orElseThrow())
                .extracting(DerivedLevel::timeTag, DerivedLevel::value)
                .containsExactly("2017-09-10T18:45:00Z", 1038.9);
    }
}
