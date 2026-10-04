package io.github.mustafanazeer.spaceflux.query.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RawJsonTest {

    @Test
    void aMemberIsCutOutWithItsTextUnchanged() {
        String json = "{\"kind\": \"x\", \"summary\": {\"a\": 5.0e3, \"b\": [1, {\"c\": \"}\"}]}, \"after\": {}}";

        assertThat(RawJson.member(json, "summary")).isEqualTo("{\"a\": 5.0e3, \"b\": [1, {\"c\": \"}\"}]}");
    }

    @Test
    void aMemberPastTheParsersFirstBufferIsCutOutWhole() {
        // Over 32768 characters the parser reads through a buffered reader, so offsets cross buffer reloads.
        String filler = "\"" + "f".repeat(70_000) + "\"";
        String member = "{\"list\": [" + "\"" + "m".repeat(40_000) + "\"" + "], \"n\": 1.9733e3}";
        String json = "{\"filler\": " + filler + ", \"close_approach\": " + member + ", \"tail\": {\"x\": 1}}";

        assertThat(RawJson.member(json, "close_approach")).isEqualTo(member);
    }

    @Test
    void aMissingMemberOrAMemberThatIsNotAnObjectIsAnError() {
        assertThatThrownBy(() -> RawJson.member("{\"other\": {}}", "summary")).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> RawJson.member("{\"summary\": [1]}", "summary")).isInstanceOf(
                IllegalStateException.class);
    }
}
