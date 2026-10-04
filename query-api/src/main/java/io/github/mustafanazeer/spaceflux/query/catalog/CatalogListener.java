package io.github.mustafanazeer.spaceflux.query.catalog;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.github.mustafanazeer.spaceflux.contracts.DeadLetters;
import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.consume.Failures;

/**
 * Reads raw.gp one record at a time in the consumer group query-api-catalog, independently of the risk engine's
 * screening consumer (docs/data/topics.md). A record's offset is committed only after its transaction committed or
 * its dead letter was acknowledged; anything thrown makes the container deliver the record again.
 */
@Component
@ConditionalOnProperty(name = "spaceflux.catalog.enabled", havingValue = "true")
class CatalogListener {

    static final String GROUP = "query-api-catalog";
    private static final Logger LOG = LoggerFactory.getLogger(CatalogListener.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(40);

    private final CatalogProcessor processor;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Clock clock = Clock.systemUTC();

    CatalogListener(CatalogStore store, KafkaTemplate<String, byte[]> kafka) {
        this.processor = new CatalogProcessor(TopicSchemas.fromClasspath(), store);
        this.kafka = kafka;
    }

    @KafkaListener(topics = CatalogProcessor.TOPIC, groupId = GROUP)
    void onRecord(ConsumerRecord<String, byte[]> record) {
        try {
            CatalogProcessor.Outcome outcome = processor.process(
                    new CatalogProcessor.In(record.key(), record.value()), clock.instant());
            if (outcome instanceof CatalogProcessor.Outcome.DeadLetter(DeadLetters.Message m)) {
                send(m);
            }
        } catch (RuntimeException e) {
            // The error handler retries without logging each attempt, so an outage would otherwise be silent.
            LOG.warn("raw.gp record {}@{} not applied, delivered again: {}", record.partition(), record.offset(),
                    Failures.describe(e));
            throw e;
        }
    }

    private void send(DeadLetters.Message m) {
        try {
            kafka.send(m.topic(), m.key(), m.value()).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing a dead letter", e);
        } catch (Exception e) {
            throw new IllegalStateException("writing a dead letter failed", e);
        }
    }
}
