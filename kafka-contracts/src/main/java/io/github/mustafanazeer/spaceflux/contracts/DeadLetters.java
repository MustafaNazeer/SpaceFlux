package io.github.mustafanazeer.spaceflux.contracts;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds dlq v1 dead letters for records a service read and could not use, with the limits ingest applies: the
 * first 256 KiB of the payload, cut on a character boundary; base64 when the bytes are not UTF-8 or when JSON escaping
 * would push the record past 900,000 bytes; a reason of at most 4 KiB; a source_url left out, with its length named
 * in the reason, whenever the dead letter with it would be over 900,000 bytes, before the payload falls back to
 * base64; and the record key left out the same way when key and value together would be over that budget, since the
 * producer's request limit counts both (docs/data/topics.md, raw.gp.dlq). No dead letter over 900,000 bytes, key
 * included, is ever returned, and every one is checked against the dlq schema first.
 */
public final class DeadLetters {

    static final int MAX_PAYLOAD = 256 << 10;
    private static final int MAX_RECORD = 900_000;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TopicSchemas schemas;
    private final String service;

    public record Message(String topic, String key, byte[] value) {
    }

    /** {@code service} is written into every dead letter as the service that rejected the payload. */
    public DeadLetters(TopicSchemas schemas, String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("a dead letter needs the name of the service that wrote it");
        }
        this.schemas = schemas;
        this.service = service;
    }

    /**
     * A validate stage dead letter for {@code sourceTopic}'s .dlq topic. {@code check} is "schema", "rule", or null
     * when neither kind of check rejected the payload.
     */
    public Message build(String sourceTopic, String sourceUrl, String key, String check, String reason, byte[] payload,
            Instant now) {
        byte[] kept = payload.length > MAX_PAYLOAD ? cutOnCharacter(payload) : payload;
        String text = utf8(kept);
        ObjectNode d = JsonNodeFactory.instance.objectNode();
        d.put("schema_version", 1);
        d.put("source_topic", sourceTopic);
        d.put("service", service);
        d.put("stage", "validate");
        if (check != null) {
            d.put("check", check);
        }
        d.put("reason", TopicSchemas.cap(reason));
        d.put("failed_at", now.toString());
        if (sourceUrl != null) {
            d.put("source_url", sourceUrl);
        }
        d.put("payload", text != null ? text : Base64.getEncoder().encodeToString(kept));
        d.put("payload_encoding", text != null ? "utf-8" : "base64");
        d.put("payload_bytes", payload.length);
        if (kept.length < payload.length) {
            d.put("payload_truncated", true);
        }
        byte[] value = MAPPER.writeValueAsBytes(d);
        // The payload and reason are capped, but a source_url copied from a record is not: one long enough would make
        // a dead letter the producer refuses, and the record would be retried forever. So whenever the dead letter
        // with it is over budget, it is left out, before the payload falls back to base64.
        if (sourceUrl != null && value.length > MAX_RECORD) {
            d.remove("source_url");
            d.put("reason", TopicSchemas.cap(reason + "; source_url left out: "
                    + sourceUrl.getBytes(StandardCharsets.UTF_8).length + " bytes"));
            value = MAPPER.writeValueAsBytes(d);
        }
        if (text != null && value.length > MAX_RECORD) {
            d.put("payload", Base64.getEncoder().encodeToString(kept));
            d.put("payload_encoding", "base64");
            value = MAPPER.writeValueAsBytes(d);
        }
        // The producer's request limit counts the key with the value, and a key copied from a record is not bounded
        // either, so it is left out the same way, when the two together would be over budget.
        String sentKey = key;
        int keyBytes = key == null ? 0 : key.getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes > 0 && keyBytes + value.length > MAX_RECORD) {
            sentKey = null;
            d.put("reason", TopicSchemas.cap(d.get("reason").asString() + "; key left out: " + keyBytes + " bytes"));
            value = MAPPER.writeValueAsBytes(d);
        }
        int sentKeyBytes = sentKey == null ? 0 : keyBytes;
        if (sentKeyBytes + value.length > MAX_RECORD) {
            throw new IllegalStateException("dead letter is " + (sentKeyBytes + value.length)
                    + " bytes with its key, over the 900,000 byte budget");
        }
        TopicSchemas.Result r = schemas.check("dlq", d);
        if (r.failure() != null) {
            throw new IllegalStateException("dead letter fails the dlq schema: " + r.failure());
        }
        return new Message(sourceTopic + ".dlq", sentKey, value);
    }

    private static byte[] cutOnCharacter(byte[] payload) {
        int cut = MAX_PAYLOAD;
        while (cut > MAX_PAYLOAD - 4 && (payload[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return Arrays.copyOf(payload, cut);
    }

    /** The bytes as text when they are valid UTF-8, otherwise null. */
    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
