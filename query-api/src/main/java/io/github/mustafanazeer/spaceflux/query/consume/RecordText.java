package io.github.mustafanazeer.spaceflux.query.consume;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** How a consumer reads a record's bytes before it builds rows from them. */
public final class RecordText {

    // The schema check parses numbers as doubles, which loses digits a column check needs, so rows are read from a
    // second parse of the same text that keeps every decimal.
    private static final JsonMapper DECIMALS =
            JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private RecordText() {
    }

    /**
     * The bytes as text, or null when they are not UTF-8. The JSON parser accepts some malformed UTF-8 (overlong
     * forms, sequences above U+10FFFF) inside strings, and a lenient decode would replace them, so a stored payload
     * would not be the bytes received.
     */
    public static String strictUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** The text parsed with every decimal kept, for building rows. */
    public static JsonNode withDecimals(String text) {
        return DECIMALS.readTree(text);
    }
}
