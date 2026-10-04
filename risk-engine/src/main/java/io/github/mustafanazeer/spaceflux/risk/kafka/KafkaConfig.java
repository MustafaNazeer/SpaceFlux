package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.util.function.LongConsumer;

import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * A batch whose alerts or dead letters could not be written is delivered again until the write succeeds. Spring
 * Kafka's default handler gives up after 9 retries and commits past the batch, which would lose its events (ADR 0008).
 * Schema and rule failures never reach this handler: they are dead lettered by the listener itself.
 */
@Configuration
class KafkaConfig {

    /** Written into every dead letter this service builds. */
    static final String SERVICE = "risk-engine";
    static final String NEVER_COMMIT_FACTORY = "neverCommitFactory";

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return waitingHandler(KafkaConfig::sleep);
    }

    /**
     * For the raw.gp screening consumer, which rebuilds its state from the start of the topic on every start: manual
     * acknowledgement that is never given, so no offset is ever committed (ADR 0008).
     */
    @Bean(NEVER_COMMIT_FACTORY)
    @SuppressWarnings("unchecked")
    ConcurrentKafkaListenerContainerFactory<String, byte[]> neverCommitFactory(ConsumerFactory<?, ?> consumers) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory((ConsumerFactory<String, byte[]>) consumers);
        f.setBatchListener(true);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        DefaultErrorHandler neverCommit = waitingHandler(KafkaConfig::sleep);
        // The default commits a batch's offsets after a retry succeeds; this consumer never commits.
        neverCommit.setAckAfterHandle(false);
        f.setCommonErrorHandler(neverCommit);
        return f;
    }

    static DefaultErrorHandler waitingHandler(LongConsumer sleep) {
        return new WaitingErrorHandler(retryUntilWritten(), sleep);
    }

    static BackOff retryUntilWritten() {
        ExponentialBackOff b = new ExponentialBackOff(1_000, 2.0);
        b.setMaxInterval(60_000);
        b.setMaxElapsedTime(Long.MAX_VALUE);
        return b;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * An error with no record behind it, such as a fetch that cannot be decompressed, is rethrown by the default
     * handler and the container polls again at once, which repeats the error as fast as the broker answers. Waiting
     * first keeps a persistent fault to one log line every few seconds.
     */
    static final class WaitingErrorHandler extends DefaultErrorHandler {

        static final long WAIT_MS = 5_000;
        private final LongConsumer sleep;

        WaitingErrorHandler(BackOff backOff, LongConsumer sleep) {
            super(backOff);
            this.sleep = sleep;
        }

        @Override
        public void handleOtherException(Exception thrownException, Consumer<?, ?> consumer,
                MessageListenerContainer container, boolean batchListener) {
            sleep.accept(WAIT_MS);
            super.handleOtherException(thrownException, consumer, container, batchListener);
        }
    }
}
