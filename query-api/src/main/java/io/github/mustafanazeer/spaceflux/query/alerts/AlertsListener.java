package io.github.mustafanazeer.spaceflux.query.alerts;

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
 * Reads alerts one record at a time in the consumer group query-api-alerts (ADR 0010). A record's offset is committed
 * only after its transaction committed or its dead letter was acknowledged; anything thrown makes the container
 * deliver the record again.
 */
@Component
@ConditionalOnProperty(name = "spaceflux.alerts.enabled", havingValue = "true")
class AlertsListener {

    static final String GROUP = "query-api-alerts";
    private static final Logger LOG = LoggerFactory.getLogger(AlertsListener.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(40);

    private final AlertsProcessor processor;
    private final KafkaTemplate<String, byte[]> kafka;
    private final AlertStored stored;
    private final Clock clock = Clock.systemUTC();

    AlertsListener(AlertStore store, KafkaTemplate<String, byte[]> kafka, AlertStored stored) {
        this.processor = new AlertsProcessor(TopicSchemas.fromClasspath(), store);
        this.kafka = kafka;
        this.stored = stored;
    }

    @KafkaListener(topics = AlertsProcessor.TOPIC, groupId = GROUP)
    void onRecord(ConsumerRecord<String, byte[]> record) {
        try {
            AlertsProcessor.Outcome outcome = processor.process(
                    new AlertsProcessor.In(record.key(), record.value(), record.partition(), record.offset()),
                    clock.instant());
            if (outcome instanceof AlertsProcessor.Outcome.DeadLetter(DeadLetters.Message m)) {
                send(m);
            } else if (outcome instanceof AlertsProcessor.Outcome.Stored(String eventId)) {
                handOn(eventId);
            }
        } catch (RuntimeException e) {
            // The error handler retries without logging each attempt, so an outage would otherwise be silent.
            LOG.warn("alerts record {}@{} not stored, delivered again: {}", record.partition(), record.offset(),
                    Failures.describe(e));
            throw e;
        }
    }

    /** The alert is stored and its offset must be committed whatever happens to the live subscription. */
    private void handOn(String eventId) {
        try {
            stored.stored(eventId);
        } catch (RuntimeException e) {
            LOG.warn("stored alert not handed to live subscribers: {}", Failures.describe(e));
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
