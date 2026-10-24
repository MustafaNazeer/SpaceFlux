package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The check every text message passes before Spring for GraphQL reads it. */
class AlertSocketHandlerTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"connection_init\"}",
        "{\"type\":\"connection_init\",\"payload\":{}}",
        "{\"type\":\"ping\"}",
        "{\"type\":\"pong\"}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"{ a }\"}}",
        "{\"id\":\"1\",\"type\":\"complete\"}",
        "{\"id\":null,\"type\":\"ping\"}",
        "{\"type\":\"ping\",\"payload\":{}}",
        "{\"type\":\"pong\",\"payload\":null}",
        "{\"type\":\"connection_init\",\"payload\":null}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"{ a }\","
            + "\"operationName\":null,\"variables\":null,\"extensions\":null}}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"{ a }\","
            + "\"operationName\":\"A\",\"variables\":{},\"extensions\":{}}}"})
    void aJsonObjectOfAClientTypeWithAnAllowedIdAndPayloadIsAccepted(String message) {
        assertThat(AlertSocketHandler.accepted(message)).isTrue();
    }

    /**
     * Repeated and escaped keys are read the way Spring for GraphQL reads them: the last one, unescaped. Spring casts
     * a payload to a map and, of the types a client sends, requires one only for subscribe, where it also requires a
     * query with text and checks the types of operationName, variables and extensions.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "x",
        "",
        "[]",
        "{}",
        "{\"type\":5}",
        "{\"type\":\"next\"}",
        "{\"type\":\"connection_init\"} {}",
        "{\"type\":\"x\\nFAKE\"}",
        "{\"id\":\"ok\",\"id\":\"a\\nb\",\"type\":\"complete\"}",
        "{\"\\u0069d\":\"a\\nb\",\"type\":\"complete\"}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"id\":\"\"}",
        "{\"type\":\"ping\",\"payload\":\"x\"}",
        "{\"type\":\"connection_init\",\"payload\":[]}",
        "{\"type\":\"connection_init\",\"payload\":5}",
        "{\"id\":\"1\",\"type\":\"subscribe\"}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":null}",
        "{\"id\":\"1\",\"type\":\"complete\",\"payload\":\"x\"}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{}}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":5}}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"  \\t\\n\"}}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"{ a }\",\"operationName\":5}}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"{ a }\",\"variables\":\"s\"}}",
        "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"{ a }\",\"extensions\":[]}}"})
    void anythingElseIsRefused(String message) {
        assertThat(AlertSocketHandler.accepted(message)).isFalse();
    }
}
