package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * No record content can make processing throw, and no schema valid record a scale is read from vanishes without an
 * alert, a dead letter, or a count (docs/security/hardening-checklist.md SEC-RSK-07; SEC-ING-07). Seeded mutations of
 * the committed raw.swpc examples, so every run tries the same records.
 */
class SwpcProcessorFuzzTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String[] VALUES = {"1e400", "-1e400", "0", "-0.0", "1e-400", "9.0049", "9.006", "0.2", "0.21",
            "1e-9", "9.99e-10", "123456789012345678901234567890", "-1", "1.5", "true", "null", "\"x\"", "[]", "{}",
            "1e308", "2147483648", "-2147483649"};
    private static final String[] TIMES = {"2026-13-45T99:99:99", "0000-01-01T00:00:00", "9999-12-31T23:59:59",
            "2026-06-30T23:59:60", "2026-09-27T10:29:00.1234567890123Z", "2026-09-27T10:29:00Z", "2026-09-27T10:29:00",
            "9999-12-31T23:59:59Z", "0000-01-01T00:00:00Z", "2300-01-01T00:00:00", "2300-01-01T00:00:00Z"};
    private static final String[] FETCHED = {"2026-09-27T16:30:37Z", "2026-09-27T16:30:37.1234567890123Z",
            "9999-12-31T23:59:59Z", "0000-01-01T00:00:00Z", "2016-12-31T23:59:60Z"};

    private static List<String> bases() throws IOException {
        Path ex = Path.of("..", "schemas", "raw.swpc", "examples");
        List<String> b = new ArrayList<>();
        for (String f : List.of("valid-kp.json", "valid-goes-xrays.json", "valid-goes-protons.json", "valid-alert.json")) {
            b.add(Files.readString(ex.resolve(f)));
        }
        b.add(b.get(1).replace("0.05-0.4nm", "0.1-0.8nm"));
        b.add(b.get(2).replaceAll("\"energy\": ?\"[^\"]*\"", "\"energy\": \">=10 MeV\""));
        return b;
    }

    private static boolean readByAScale(JsonNode event) {
        JsonNode r = event.get("record");
        String energy = r.has("energy") && r.get("energy").isString() ? r.get("energy").asString() : "";
        return switch (event.get("product").asString()) {
            case "swpc.kp" -> true;
            case "swpc.goes.xrays" -> energy.equals("0.1-0.8nm");
            case "swpc.goes.protons" -> energy.equals(">=10 MeV");
            default -> false;
        };
    }

    @Test
    void noMutatedRecordThrowsOrVanishes() throws IOException {
        List<String> bases = bases();
        TopicSchemas schemas = TopicSchemas.fromClasspath();
        Random rnd = new Random(42);
        Instant now = Instant.parse("2026-09-27T16:31:00Z");
        List<String> thrown = new ArrayList<>();
        List<String> vanished = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            ObjectNode e = (ObjectNode) JSON.readTree(bases.get(rnd.nextInt(bases.size())));
            ObjectNode rec = (ObjectNode) e.get("record");
            List<String> fields = new ArrayList<>(rec.propertyNames());
            for (int k = 0, n = 1 + rnd.nextInt(3); k < n; k++) {
                switch (rnd.nextInt(4)) {
                    case 0 -> rec.set(fields.get(rnd.nextInt(fields.size())),
                            JSON.readTree(VALUES[rnd.nextInt(VALUES.length)]));
                    case 1 -> rec.put("time_tag", TIMES[rnd.nextInt(TIMES.length)]);
                    case 2 -> e.put("fetched_at", FETCHED[rnd.nextInt(FETCHED.length)]);
                    default -> rec.remove(fields.get(rnd.nextInt(fields.size())));
                }
            }
            byte[] bytes = JSON.writeValueAsBytes(e);
            boolean valid = schemas.check("raw.swpc", bytes).failure() == null;
            SwpcProcessor p = new SwpcProcessor(schemas);
            try {
                SwpcProcessor.Out o = p.process(List.of(new SwpcProcessor.In(e.get("product").asString(), bytes)), now);
                if (valid && readByAScale(e) && o.alerts().isEmpty() && o.deadLetters().isEmpty()
                        && o.missingValues() == 0) {
                    vanished.add(rec + " fetched_at=" + e.get("fetched_at"));
                }
                p.tick(now.plusSeconds(rnd.nextInt(100_000)));
            } catch (Throwable t) {
                thrown.add(t + " <- " + rec + " fetched_at=" + e.get("fetched_at"));
            }
        }
        assertThat(thrown).isEmpty();
        assertThat(vanished).isEmpty();
    }
}
