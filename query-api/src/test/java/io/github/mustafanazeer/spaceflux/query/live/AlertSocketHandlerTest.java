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
        "{\"id\":null,\"type\":\"ping\"}"})
    void aJsonObjectOfAClientTypeWithAnAllowedIdOrNoneIsAccepted(String message) {
        assertThat(AlertSocketHandler.accepted(message)).isTrue();
    }

    /** Repeated and escaped keys are read the way Spring for GraphQL reads them: the last one, unescaped. */
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
        "{\"id\":\"1\",\"type\":\"subscribe\",\"id\":\"\"}"})
    void anythingElseIsRefused(String message) {
        assertThat(AlertSocketHandler.accepted(message)).isFalse();
    }
}
