package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** At most 20 messages in any 60 seconds (ADR 0014 decision 8). */
class MessageBudgetTest {

    static final long SECOND = 1_000_000_000L;

    final MessageBudget budget = new MessageBudget(20, Duration.ofSeconds(60));

    @Test
    void twentyMessagesAtOnceAreAllowedAndTheTwentyFirstIsNot() {
        for (int i = 0; i < 20; i++) {
            assertThat(budget.allow(0)).isTrue();
        }

        assertThat(budget.allow(0)).isFalse();
    }

    @Test
    void theWindowSlidesSoOldMessagesStopCounting() {
        for (int i = 0; i < 20; i++) {
            assertThat(budget.allow(i * SECOND)).isTrue();
        }

        assertThat(budget.allow(59 * SECOND)).isFalse();
        // The first message, sent at 0, is now more than 60 seconds old.
        assertThat(budget.allow(60 * SECOND + 1)).isTrue();
        assertThat(budget.allow(60 * SECOND + 2)).isFalse();
    }

    @Test
    void twoPongsAMinuteForAnHourStayWithinTheBudget() {
        for (long t = 0; t < 3_600 * SECOND; t += 30 * SECOND) {
            assertThat(budget.allow(t)).isTrue();
        }
    }
}
