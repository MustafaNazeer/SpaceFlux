package io.github.mustafanazeer.spaceflux.query.catalog;

import java.time.Instant;

import org.springframework.context.ApplicationContext;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;

/** Lets the plan test feed element sets through the consumer's own processor and store, and read its lookup. */
public final class CatalogPlanFeed {

    public static final String HELD_FOR_UPDATE = MysqlCatalogStore.HELD_FOR_UPDATE;

    private final CatalogProcessor processor;

    public CatalogPlanFeed(ApplicationContext app) {
        processor = new CatalogProcessor(TopicSchemas.fromClasspath(), app.getBean(CatalogStore.class));
    }

    public void apply(byte[] value, Instant now) {
        CatalogProcessor.Outcome outcome = processor.process(new CatalogProcessor.In("plan", value), now);
        if (outcome != CatalogProcessor.Outcome.APPLIED) {
            throw new IllegalStateException("not applied: " + (outcome instanceof CatalogProcessor.Outcome.DeadLetter d
                    ? new String(d.message().value(), java.nio.charset.StandardCharsets.UTF_8) : outcome));
        }
    }
}
