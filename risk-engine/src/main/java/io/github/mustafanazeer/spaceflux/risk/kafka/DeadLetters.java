package io.github.mustafanazeer.spaceflux.risk.kafka;

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
 * Builds dlq v1 dead letters for records the risk engine read and could not use, with the limits ingest applies: the
 * first 256 KiB of the payload, cut on a character boundary; base64 when the bytes are not UTF-8 or when JSON escaping
 * would push the record past 900,000 bytes; and a reason of at most 4 KiB (docs/data/topics.md, raw.gp.dlq). Every
 * dead letter is checked against the dlq schema before it is returned.
 */
public final class DeadLetters {

    static final String SERVICE = "risk-engine";
    static final int MAX_PAYLOAD = 256 << 10;
    private static final int MAX_RECORD = 900_000;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TopicSchemas schemas;

    public record Message(String topic, String key, byte[] value) {
    }

    public DeadLetters(TopicSchemas schemas) {
        this.schemas = schemas;
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
        d.put("service", SERVICE);
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
        if (text != null && value.length > MAX_RECORD) {
            d.put("payload", Base64.getEncoder().encodeToString(kept));
            d.put("payload_encoding", "base64");
            value = MAPPER.writeValueAsBytes(d);
        }
        TopicSchemas.Result r = schemas.check("dlq", d);
        if (r.failure() != null) {
            throw new IllegalStateException("dead letter fails the dlq schema: " + r.failure());
        }
        return new Message(sourceTopic + ".dlq", key, value);
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
