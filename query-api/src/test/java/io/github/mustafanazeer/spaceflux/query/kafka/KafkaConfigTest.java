package io.github.mustafanazeer.spaceflux.query.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOffExecution;

class KafkaConfigTest {

    @Test
    void theBackOffStartsAtOneSecondDoublesToAMinuteAndNeverGivesUp() {
        BackOffExecution b = KafkaConfig.retryUntilStored().start();

        assertThat(b.nextBackOff()).isEqualTo(1_000);
        assertThat(b.nextBackOff()).isEqualTo(2_000);
        for (int i = 0; i < 1_000; i++) {
            assertThat(b.nextBackOff()).isBetween(1_000L, 60_000L);
        }
    }

    @Test
    void anErrorWithNoRecordWaitsFiveSecondsBeforeTheContainerPollsAgain() {
        List<Long> waits = new ArrayList<>();
        DefaultErrorHandler handler = KafkaConfig.waitingHandler(waits::add);

        assertThatThrownBy(() -> handler.handleOtherException(new KafkaException("fetch failed"), null, null, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(waits).containsExactly(5_000L);
    }

    @Test
    void noExceptionTypeIsSkippedWithoutRetry() {
        DefaultErrorHandler handler = KafkaConfig.waitingHandler(millis -> { });

        // Spring Kafka's defaults mark these as not retryable, which would commit past the record.
        assertThat(handler.removeClassification(DeserializationException.class)).isNull();
        assertThat(handler.removeClassification(ConversionException.class)).isNull();
        assertThat(handler.removeClassification(ClassCastException.class)).isNull();
    }
}
