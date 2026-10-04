package io.github.mustafanazeer.spaceflux.query.kafka;

import java.util.Map;
import java.util.function.LongConsumer;

import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * A record that throws is delivered again until it is stored or dead lettered, and its offset is not committed
 * before then. Only a database or broker failure throws: everything a record's content can cause becomes a dead
 * letter inside the listener. Spring Kafka's default handler gives up after 9 retries and commits past the record,
 * and skips some exception types without any retry, either of which would lose an event.
 */
@Configuration
class KafkaConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return waitingHandler(KafkaConfig::sleep);
    }

    static DefaultErrorHandler waitingHandler(LongConsumer sleep) {
        DefaultErrorHandler handler = new WaitingErrorHandler(retryUntilStored(), sleep);
        handler.setClassifications(Map.of(), true);
        return handler;
    }

    static BackOff retryUntilStored() {
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
     * handler and the container polls again at once. Waiting first keeps a persistent fault to one log line every
     * few seconds.
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
