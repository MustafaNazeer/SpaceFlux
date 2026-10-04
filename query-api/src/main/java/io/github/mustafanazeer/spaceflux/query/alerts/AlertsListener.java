package io.github.mustafanazeer.spaceflux.query.alerts;

import java.sql.SQLException;
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
    private final Clock clock = Clock.systemUTC();

    AlertsListener(AlertStore store, KafkaTemplate<String, byte[]> kafka) {
        this.processor = new AlertsProcessor(TopicSchemas.fromClasspath(), store);
        this.kafka = kafka;
    }

    @KafkaListener(topics = AlertsProcessor.TOPIC, groupId = GROUP)
    void onRecord(ConsumerRecord<String, byte[]> record) {
        try {
            AlertsProcessor.Outcome outcome = processor.process(
                    new AlertsProcessor.In(record.key(), record.value(), record.partition(), record.offset()),
                    clock.instant());
            if (outcome instanceof AlertsProcessor.Outcome.DeadLetter(DeadLetters.Message m)) {
                send(m);
            }
        } catch (RuntimeException e) {
            // The error handler retries without logging each attempt, so an outage would otherwise be silent.
            LOG.warn("alerts record {}@{} not stored, delivered again: {}", record.partition(), record.offset(),
                    describe(e));
            throw e;
        }
    }

    /**
     * The database's own error when there is one (class, error code, SQLState, message), otherwise the innermost
     * cause. A message can echo a stored value, so line breaks and other control characters are escaped and cannot
     * start a line that reads as another log entry or change how a terminal shows it.
     */
    static String describe(Throwable t) {
        Throwable root = t;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SQLException sql) {
                return "SQLException " + sql.getErrorCode() + " " + sql.getSQLState() + ": "
                        + oneLine(sql.getMessage());
            }
            root = c;
        }
        return root.getClass().getSimpleName() + ": " + oneLine(root.getMessage());
    }

    /** Escapes every control character but tab, and U+2028 and U+2029, so a message cannot shape the log. */
    private static String oneLine(String message) {
        String text = String.valueOf(message);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                out.append("\\r");
            } else if (c == '\n') {
                out.append("\\n");
            } else if ((c < 0x20 && c != '\t') || c == 0x7f || c == 0x2028 || c == 0x2029) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
