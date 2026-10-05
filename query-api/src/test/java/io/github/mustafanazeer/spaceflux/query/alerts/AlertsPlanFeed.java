package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.Instant;

import org.springframework.context.ApplicationContext;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;

/** Lets the plan test feed alerts events through the consumer's own processor and store, and read its lookup. */
public final class AlertsPlanFeed {

    public static final String SERIES_FOR_UPDATE = MysqlAlertStore.SERIES_FOR_UPDATE;

    private final AlertsProcessor processor;

    public AlertsPlanFeed(ApplicationContext app) {
        processor = new AlertsProcessor(TopicSchemas.fromClasspath(), app.getBean(AlertStore.class));
    }

    /** Stores one event; anything else, a dead letter included, fails the caller. */
    public void store(byte[] value, Instant now) {
        AlertsProcessor.Outcome outcome = processor.process(new AlertsProcessor.In("plan", value, 0, 0), now);
        if (outcome != AlertsProcessor.Outcome.STORED) {
            throw new IllegalStateException("not stored: " + (outcome instanceof AlertsProcessor.Outcome.DeadLetter d
                    ? new String(d.message().value(), java.nio.charset.StandardCharsets.UTF_8) : outcome));
        }
    }
}
