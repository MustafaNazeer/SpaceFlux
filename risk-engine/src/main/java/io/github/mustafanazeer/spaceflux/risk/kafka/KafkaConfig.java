package io.github.mustafanazeer.spaceflux.risk.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * A batch whose alerts or dead letters could not be written is delivered again until the write succeeds. Spring
 * Kafka's default handler gives up after 9 retries and commits past the batch, which would lose its events (ADR 0008).
 * Schema and rule failures never reach this handler: they are dead lettered by the listener itself.
 */
@Configuration
class KafkaConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(retryUntilWritten());
    }

    static BackOff retryUntilWritten() {
        ExponentialBackOff b = new ExponentialBackOff(1_000, 2.0);
        b.setMaxInterval(60_000);
        b.setMaxElapsedTime(Long.MAX_VALUE);
        return b;
    }
}
