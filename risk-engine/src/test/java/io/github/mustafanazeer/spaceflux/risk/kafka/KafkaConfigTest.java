package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

/** A batch that cannot be written is retried until it can, never skipped and committed (ADR 0008). */
class KafkaConfigTest {

    @Test
    void theRetryBackOffNeverGivesUpAndStaysUnderAMinute() {
        BackOffExecution e = KafkaConfig.retryUntilWritten().start();
        long last = 0;
        for (int i = 0; i < 10_000; i++) {
            last = e.nextBackOff();
            assertThat(last).isNotEqualTo(BackOffExecution.STOP);
        }
        assertThat(last).isEqualTo(60_000);
    }
}
