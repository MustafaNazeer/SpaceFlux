package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON Schema of each topic, loaded once from the repository's schema files and never from the network, and the
 * check every consumer runs on read and every producer runs before publish (ADR 0002, ADR 0008). A record is parsed
 * once, as a tree, rejecting duplicated keys and documents over 1 MiB. Any failure, including an error thrown inside
 * validation, is returned as a failed check with its reason rather than thrown, so one hostile record cannot stop a
 * consumer (docs/security/threat-model.md T2.5 to T2.8).
 */
public final class TopicSchemas {

    static final String ID_PREFIX = "https://github.com/MustafaNazeer/SpaceFlux/schemas/";
    static final List<String> TOPICS = List.of("raw.gp", "raw.swpc", "alerts", "dlq");
    static final int MAX_REASON_BYTES = 4 << 10;
    private static final long MAX_DOCUMENT_BYTES = 1 << 20;

    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_DOCUMENT_BYTES).build())
            .build()).build();

    private final Map<String, Schema> schemas;

    /** The parsed record when {@code failure} is null; otherwise the reason, at most 4 KiB. */
    public record Result(JsonNode node, String failure) {
    }

    private TopicSchemas(Map<String, Schema> schemas) {
        this.schemas = schemas;
    }

    /** Fails when any topic's schema file is missing, so the service does not start without its contracts. */
    public static TopicSchemas fromClasspath() {
        Map<String, String> files = new HashMap<>();
        for (String topic : TOPICS) {
            String path = "/schemas/" + topic + "/v1.schema.json";
            try (InputStream in = TopicSchemas.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("schema file " + path + " is missing");
                }
                files.put(topic, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read schema file " + path, e);
            }
        }
        return of(files);
    }

    static TopicSchemas of(Map<String, String> schemaByTopic) {
        Map<String, String> byIri = new HashMap<>();
        schemaByTopic.forEach((topic, text) -> byIri.put(iri(topic), text));
        SchemaRegistryConfig config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> b
                .schemaRegistryConfig(config)
                .schemaLoader(l -> l.allow(iri -> iri.toString().startsWith(ID_PREFIX))
                        .resourceLoaders(r -> r.resources(byIri))));
        Map<String, Schema> schemas = new HashMap<>();
        for (String topic : schemaByTopic.keySet()) {
            schemas.put(topic, registry.getSchema(SchemaLocation.of(iri(topic))));
        }
        return new TopicSchemas(Map.copyOf(schemas));
    }

    private static String iri(String topic) {
        return ID_PREFIX + topic + "/v1.schema.json";
    }

    public Result check(String topic, byte[] value) {
        Schema schema = schemas.get(topic);
        if (schema == null) {
            return failed("no schema for topic " + topic);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(value);
        } catch (RuntimeException e) {
            return failed("not a valid JSON document: " + e.getMessage());
        }
        return check(schema, node);
    }

    /** Checks an event this service built, before publishing it. */
    public Result check(String topic, JsonNode node) {
        Schema schema = schemas.get(topic);
        return schema == null ? failed("no schema for topic " + topic) : check(schema, node);
    }

    private static Result check(Schema schema, JsonNode node) {
        List<Error> errors;
        try {
            errors = schema.validate(node);
        } catch (Throwable t) {
            return failed("schema validation failed with " + t.getClass().getSimpleName());
        }
        if (errors.isEmpty()) {
            return new Result(node, null);
        }
        return failed(errors.stream().map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                .collect(Collectors.joining("; ")));
    }

    private static Result failed(String reason) {
        return new Result(null, cap(reason));
    }

    /** Cuts to at most 4 KiB of UTF-8 without splitting a character. */
    static String cap(String reason) {
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_REASON_BYTES) {
            return reason;
        }
        int end = MAX_REASON_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
