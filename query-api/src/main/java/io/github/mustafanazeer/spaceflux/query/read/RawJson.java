package io.github.mustafanazeer.spaceflux.query.read;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Cuts one member of a stored event out of its text, so the member is returned exactly as it was received. */
final class RawJson {

    private static final JsonFactory FACTORY = new JsonFactory();

    private RawJson() {
    }

    /** The text of the object held by the top level member {@code name}. */
    static String member(String json, String name) {
        try (JsonParser p = FACTORY.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("A stored event is not an object.");
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                boolean wanted = name.equals(p.currentName());
                JsonToken value = p.nextToken();
                if (wanted && value == JsonToken.START_OBJECT) {
                    int start = (int) p.currentTokenLocation().getCharOffset();
                    p.skipChildren();
                    return json.substring(start, (int) p.currentTokenLocation().getCharOffset() + 1);
                }
                p.skipChildren();
            }
        }
        throw new IllegalStateException("A stored event has no " + name + " object.");
    }
}
