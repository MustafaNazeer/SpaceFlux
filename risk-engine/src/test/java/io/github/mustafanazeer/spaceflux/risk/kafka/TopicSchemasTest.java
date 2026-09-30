package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Schema checks on read and before publish (ADR 0002, ADR 0008), under the conditions of the dependency review in
 * docs/security/threat-model.md (T2.5 to T2.8).
 */
class TopicSchemasTest {

    private static final Path SCHEMAS = Path.of("..", "schemas");
    private static final TopicSchemas SCHEMAS_ON_CLASSPATH = TopicSchemas.fromClasspath();

    static Stream<Path> examples() throws IOException {
        return Files.walk(SCHEMAS).filter(p -> p.getParent().getFileName().toString().equals("examples")).sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void everyCommittedExamplePassesItsTopicSchema(Path example) throws IOException {
        String topic = example.getParent().getParent().getFileName().toString();

        TopicSchemas.Result r = SCHEMAS_ON_CLASSPATH.check(topic, Files.readAllBytes(example));

        assertThat(r.failure()).isNull();
        assertThat(r.node()).isNotNull();
    }

    @Test
    void aFailureNamesTheField() throws IOException {
        String kp = Files.readString(SCHEMAS.resolve("raw.swpc/examples/valid-kp.json")).replace("\"Kp\"", "\"kp\"");

        TopicSchemas.Result r = SCHEMAS_ON_CLASSPATH.check("raw.swpc", kp.getBytes(StandardCharsets.UTF_8));

        assertThat(r.node()).isNull();
        assertThat(r.failure()).contains("Kp");
    }

    @Test
    void aDuplicatedKeyIsASchemaFailure() throws IOException {
        String kp = Files.readString(SCHEMAS.resolve("raw.swpc/examples/valid-kp.json"))
                .replaceFirst("\"Kp\":", "\"Kp\": 9.0, \"Kp\":");

        TopicSchemas.Result r = SCHEMAS_ON_CLASSPATH.check("raw.swpc", kp.getBytes(StandardCharsets.UTF_8));

        assertThat(r.failure()).containsIgnoringCase("duplicate");
    }

    @Test
    void aDocumentOverOneMebibyteIsRefused() {
        byte[] big = ("{\"pad\":\"" + "a".repeat(1 << 20) + "\"}").getBytes(StandardCharsets.UTF_8);

        assertThat(SCHEMAS_ON_CLASSPATH.check("raw.swpc", big).failure()).isNotNull();
    }

    @Test
    void notJsonIsAFailureNotAnException() {
        assertThat(SCHEMAS_ON_CLASSPATH.check("raw.swpc", "{\"a\":".getBytes(StandardCharsets.UTF_8)).failure())
                .isNotNull();
        assertThat(SCHEMAS_ON_CLASSPATH.check("raw.swpc", new byte[] {(byte) 0xff}).failure()).isNotNull();
    }

    @Test
    void theReasonIsCappedAtFourKibibytes() {
        StringBuilder many = new StringBuilder("{\"schema_version\":1");
        for (int i = 0; i < 2000; i++) {
            many.append(",\"f").append(i).append("\":").append(i);
        }
        many.append('}');

        String failure = SCHEMAS_ON_CLASSPATH.check("raw.swpc", many.toString().getBytes(StandardCharsets.UTF_8))
                .failure();

        assertThat(failure).isNotNull();
        assertThat(failure.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
    }

    @Test
    void aStackOverflowInsideValidationIsAFailure() throws IOException {
        String dlq = Files.readString(SCHEMAS.resolve("dlq/examples/truncated-body.json"))
                .replace("\"source_topic\": \"raw.gp\"", "\"source_topic\": \"a" + ".a".repeat(200_000) + "\"");
        assertThat(dlq).contains(".a.a.a");

        TopicSchemas.Result r = SCHEMAS_ON_CLASSPATH.check("dlq", dlq.getBytes(StandardCharsets.UTF_8));

        assertThat(r.failure()).isNotNull();
    }

    @Test
    void anUnknownTopicIsRefused() {
        assertThat(SCHEMAS_ON_CLASSPATH.check("raw.nope", "{}".getBytes(StandardCharsets.UTF_8)).failure())
                .contains("raw.nope");
    }

    @Test
    void aRemoteReferenceIsNeverFetched() throws IOException {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread accept = Thread.ofVirtual().start(() -> {
                while (!listener.isClosed()) {
                    try (var s = listener.accept()) {
                        connections.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            String remote = "http://127.0.0.1:" + listener.getLocalPort() + "/x.schema.json";
            String schema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$id\":\""
                    + TopicSchemas.ID_PREFIX + "probe/v1.schema.json\",\"$ref\":\"" + remote + "\"}";

            TopicSchemas probe = TopicSchemas.of(Map.of("probe", schema));
            TopicSchemas.Result r = probe.check("probe", "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(r.failure()).isNotNull();
            accept.interrupt();
        }
        assertThat(connections).hasValue(0);
    }

    /** Every pattern in the schema files, against long strings built to make a backtracking engine work hard. */
    @Test
    void noSchemaPatternRunsAwayOnLongAdversarialStrings() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> patterns = new ArrayList<>();
        try (Stream<Path> files = Files.list(SCHEMAS)) {
            for (Path dir : files.toList()) {
                Path f = dir.resolve("v1.schema.json");
                if (Files.exists(f)) {
                    collectPatterns(json.readTree(Files.readString(f)), patterns);
                }
            }
        }
        assertThat(patterns).hasSizeGreaterThanOrEqualTo(20);
        List<String> fillers = List.of("a", "0", "9", "a.", "0-", "T", "Z", ":", " ", "-", ".0", "https://", "M1.");
        List<String> slow = new ArrayList<>();
        Thread worker = new Thread(() -> {
            for (String p : patterns) {
                Pattern re = Pattern.compile(p);
                for (String filler : fillers) {
                    String input = filler.repeat(200_000 / filler.length()) + "!";
                    long t0 = System.nanoTime();
                    try {
                        re.matcher(input).find();
                    } catch (StackOverflowError e) {
                        continue;
                    }
                    if (Duration.ofNanos(System.nanoTime() - t0).toMillis() > 1000) {
                        slow.add(p + " with " + filler);
                    }
                }
            }
        });
        worker.start();
        worker.join(Duration.ofMinutes(2));
        assertThat(worker.isAlive()).as("patterns still running after 2 minutes").isFalse();
        assertThat(slow).isEmpty();
    }

    private static void collectPatterns(JsonNode node, List<String> out) {
        if (node.isObject()) {
            JsonNode p = node.get("pattern");
            if (p != null && p.isString()) {
                out.add(p.asString());
            }
            node.properties().forEach(e -> collectPatterns(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(n -> collectPatterns(n, out));
        }
    }
}
