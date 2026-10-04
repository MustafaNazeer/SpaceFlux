package io.github.mustafanazeer.spaceflux.query.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class GrantCheckTest {

    @Test
    void theExpectedApiGrantsNameTheConfiguredUserAndDatabase() {
        List<String> expected = GrantCheck.expected("api", "spaceflux_api", "spaceflux");

        assertThat(expected).contains(
                "GRANT USAGE ON *.* TO `spaceflux_api`@`%`",
                "GRANT SELECT ON `spaceflux`.`watchlist_object` TO `spaceflux_api`@`%`",
                "GRANT SELECT, INSERT (`action`, `event_id`, `note`, `principal`) ON "
                        + "`spaceflux`.`alert_acknowledgement` TO `spaceflux_api`@`%`");
        assertThat(expected).hasSize(13).allSatisfy(line -> assertThat(line).doesNotContain("${"));
    }

    @Test
    void theExpectedConsumerGrantsNameTheConfiguredUserAndDatabase() {
        List<String> expected = GrantCheck.expected("consumer", "other_consumer", "otherdb");

        assertThat(expected).contains(
                "GRANT USAGE ON *.* TO `other_consumer`@`%`",
                "GRANT SELECT, INSERT, UPDATE ON `otherdb`.`catalog_object` TO `other_consumer`@`%`");
        assertThat(expected).hasSize(11).allSatisfy(line -> assertThat(line).doesNotContain("${"));
    }

    @Test
    void anUnknownPoolHasNoGrantFile() {
        assertThatThrownBy(() -> GrantCheck.expected("migrate", "spaceflux_migrate", "spaceflux"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grants/migrate.txt");
    }

    @Test
    void theSameGrantsInAnotherOrderPass() {
        assertThatCode(() -> GrantCheck.compare("api", List.of("a", "b"), List.of("b", "a")))
                .doesNotThrowAnyException();
    }

    @Test
    void anExtraGrantStopsTheStartAndIsNamed() {
        assertThatThrownBy(() -> GrantCheck.compare("api", List.of("a", "b"), List.of("a", "b", "GRANT DELETE x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api pool")
                .hasMessageContaining("unexpected [GRANT DELETE x]")
                .hasMessageContaining("missing []");
    }

    @Test
    void aMissingGrantStopsTheStartAndIsNamed() {
        assertThatThrownBy(() -> GrantCheck.compare("consumer", List.of("a", "b"), List.of("a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consumer pool")
                .hasMessageContaining("unexpected []")
                .hasMessageContaining("missing [b]");
    }
}
