package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.NestedRuntimeException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;

/** A batch that cannot be written is retried until it can, never skipped and committed (ADR 0008). */
class KafkaConfigTest {

    private static final TopicPartition PARTITION = new TopicPartition("raw.swpc", 0);

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

    @Test
    @SuppressWarnings("unchecked")
    void theScreeningConsumerNeverCommitsEvenAfterARecoveredBatch() {
        var consumers = new org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, byte[]>(
                java.util.Map.of());

        var factory = new KafkaConfig().neverCommitFactory(consumers);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL);
        var handler = (org.springframework.kafka.listener.DefaultErrorHandler) org.springframework.test.util
                .ReflectionTestUtils.getField(factory, "commonErrorHandler");
        assertThat(handler.isAckAfterHandle()).isFalse();
    }

    @Test
    void anErrorWithNoRecordWaitsFiveSecondsBeforeTheContainerPollsAgain() {
        List<Long> waits = new ArrayList<>();
        DefaultErrorHandler handler = KafkaConfig.waitingHandler(waits::add);

        assertThatThrownBy(() -> handler.handleOtherException(new KafkaException("fetch failed"), null, null, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(waits).containsExactly(5_000L);
    }

    @Test
    void bothListenerFactoriesWaitOnErrorsWithNoRecord() {
        var factory = new KafkaConfig().neverCommitFactory(new DefaultKafkaConsumerFactory<String, byte[]>(Map.of()));

        assertThat(new KafkaConfig().kafkaErrorHandler()).isInstanceOf(KafkaConfig.WaitingErrorHandler.class);
        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler"))
                .isInstanceOf(KafkaConfig.WaitingErrorHandler.class);
    }

    // Spring Kafka treats ClassCastException, like a few deserialization and conversion types, as not worth
    // retrying, so by default the whole batch would be discarded (and, on the default consumer, committed past).
    @ParameterizedTest(name = "{0} handler")
    @MethodSource("bothHandlers")
    void aBatchThatFailsWithATypeSpringKafkaCallsFatalIsRetriedNotDiscarded(String name, CommonErrorHandler handler) {
        AtomicInteger retries = new AtomicInteger();

        handler.handleBatch(new ListenerExecutionFailedException("listener failed", new ClassCastException("bug")),
                batch(), mock(Consumer.class), runningContainer(), retries::incrementAndGet);

        assertThat(retries).hasValue(1);
    }

    @ParameterizedTest(name = "{0} handler")
    @MethodSource("bothHandlers")
    @SuppressWarnings("unchecked")
    void aBatchFailedAtOneRecordWithATypeSpringKafkaCallsFatalIsSoughtBackNotCommitted(String name,
            CommonErrorHandler handler) {
        Consumer<String, byte[]> consumer = mock(Consumer.class);

        assertThatThrownBy(() -> handler.handleBatch(
                new BatchListenerFailedException("record failed", new ClassCastException("bug"), 0), batch(), consumer,
                runningContainer(), () -> { }))
                .isInstanceOf(NestedRuntimeException.class)
                .hasMessageContaining("Record in retry");

        verify(consumer).seek(PARTITION, 0);
        verify(consumer, never()).commitSync(anyMap(), any());
        verify(consumer, never()).commitAsync(anyMap(), any());
    }

    static Stream<Arguments> bothHandlers() {
        var factory = new KafkaConfig().neverCommitFactory(new DefaultKafkaConsumerFactory<String, byte[]>(Map.of()));
        return Stream.of(Arguments.of("default", new KafkaConfig().kafkaErrorHandler()),
                Arguments.of("screening", ReflectionTestUtils.getField(factory, "commonErrorHandler")));
    }

    private static ConsumerRecords<String, byte[]> batch() {
        var record = new ConsumerRecord<String, byte[]>(PARTITION.topic(), PARTITION.partition(), 0, "kp", new byte[0]);
        return new ConsumerRecords<>(Map.of(PARTITION, List.of(record)), Map.of());
    }

    private static MessageListenerContainer runningContainer() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        when(container.getContainerProperties()).thenReturn(new ContainerProperties(PARTITION.topic()));
        return container;
    }
}
