package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * No raw.gp record content can make reading or screening throw, and no run fails its own schema. Seeded mutations of
 * the recorded ISS element set, most of them keeping its epoch so they reach the same epoch copy comparison.
 */
class GpProcessorFuzzTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant FETCHED = Instant.parse("2026-09-27T05:00:00Z");
    private static final String URL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json";
    private static final String[] VALUES = {"1e30", "-1e30", "1e9", "100000", "999999", "0", "-0.0", "1e-30", "-1",
            "0.99999999", "1.0", "360", "-360", "720", "17.5", "1e308", "2147483648", "-2147483649", "\"\"",
            "\"x\"", "\"" + "N".repeat(200) + "\"", "null"};
    private static final String[] EPOCHS = {"2026-09-27T04:00:00.000000", "1957-10-04T19:28:34.000000",
            "2056-12-31T23:59:59.999999", "2026-06-30T23:59:60.000000"};

    private static List<JsonNode> stations() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(
                GpProcessorFuzzTest.class.getResourceAsStream("/celestrak/gp-stations.json"))) {
            JSON.readTree(in).forEach(out::add);
        }
        return out;
    }

    private static GpProcessor.In in(JsonNode gp, Instant fetchedAt) {
        ObjectNode e = JSON.createObjectNode();
        e.put("schema_version", 1);
        e.put("source", "celestrak");
        e.put("fetched_at", fetchedAt.toString());
        e.put("source_url", URL);
        e.set("gp", gp);
        return new GpProcessor.In(gp.get("NORAD_CAT_ID").asString(), JSON.writeValueAsBytes(e));
    }

    @Test
    void noMutatedElementSetThrowsOrMakesARunFailItsSchema() throws IOException {
        List<JsonNode> stations = stations();
        JsonNode iss = stations.stream().filter(g -> g.get("NORAD_CAT_ID").asInt() == 25544).findFirst().orElseThrow();
        List<String> fields = new ArrayList<>(iss.propertyNames());
        Random rnd = new Random(42);
        List<String> thrown = new ArrayList<>();
        List<String> deadRuns = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            GpProcessor p = new GpProcessor(TopicSchemas.fromClasspath(), Set.of(25544), OrekitData.utc());
            p.accept(stations.stream().map(g -> in(g, FETCHED)).toList(), FETCHED.plusSeconds(60));
            for (int i = 0; i < 200; i++) {
                ObjectNode gp = (ObjectNode) iss.deepCopy();
                for (int k = 0, n = 1 + rnd.nextInt(3); k < n; k++) {
                    String field = fields.get(rnd.nextInt(fields.size()));
                    switch (rnd.nextInt(6)) {
                        case 0 -> gp.remove(field);
                        case 1 -> gp.put("EPOCH", EPOCHS[rnd.nextInt(EPOCHS.length)]);
                        default -> gp.set(field, JSON.readTree(VALUES[rnd.nextInt(VALUES.length)]));
                    }
                }
                gp.put("NORAD_CAT_ID", 25544);
                try {
                    p.accept(List.of(in(gp, FETCHED)), FETCHED.plusSeconds(61));
                } catch (Throwable t) {
                    thrown.add(t + " <- " + gp);
                }
            }
            try {
                GpProcessor.Out out = p.poll(FETCHED.plusSeconds(120));
                assertThat(out.alerts()).isNotEmpty();
                out.deadLetters().forEach(m -> deadRuns.add(new String(m.value())));
            } catch (Throwable t) {
                thrown.add(t + " <- run of round " + round);
            }
        }
        assertThat(thrown).isEmpty();
        assertThat(deadRuns).isEmpty();
    }
}
