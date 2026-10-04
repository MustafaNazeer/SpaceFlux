package io.github.mustafanazeer.spaceflux.query.catalog;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import io.github.mustafanazeer.spaceflux.contracts.DeadLetters;
import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.RecordText;
import io.github.mustafanazeer.spaceflux.query.consume.RuleRejected;

/**
 * Applies one raw.gp record to the catalog. Nothing in a record's content can make this throw; a record that cannot
 * be applied becomes a dead letter for raw.gp.dlq. Only a failure of the database itself is thrown, so the record is
 * delivered again rather than lost.
 */
final class CatalogProcessor {

    static final String TOPIC = "raw.gp";
    static final String SERVICE = "query-api";

    private final TopicSchemas schemas;
    private final DeadLetters deadLetters;
    private final CatalogStore store;

    record In(String key, byte[] value) {
    }

    sealed interface Outcome permits Outcome.Applied, Outcome.DeadLetter {

        Outcome APPLIED = new Applied();

        record Applied() implements Outcome {
        }

        record DeadLetter(DeadLetters.Message message) implements Outcome {
        }
    }

    CatalogProcessor(TopicSchemas schemas, CatalogStore store) {
        this.schemas = schemas;
        this.deadLetters = new DeadLetters(schemas, SERVICE);
        this.store = store;
    }

    Outcome process(In in, Instant now) {
        byte[] value = in.value() == null ? new byte[0] : in.value();
        String text = RecordText.strictUtf8(value);
        if (text == null) {
            return deadLetter(in, value, null, "schema", "not UTF-8 text", now);
        }
        TopicSchemas.Result checked = schemas.check(TOPIC, value);
        if (checked.failure() != null) {
            return deadLetter(in, value, null, "schema", checked.failure(), now);
        }
        String sourceUrl = checked.node().get("source_url").asString();
        CatalogRow row;
        try {
            row = CatalogRow.of(RecordText.withDecimals(text), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        } catch (RuleRejected e) {
            return deadLetter(in, value, sourceUrl, "rule", e.getMessage(), now);
        } catch (NotStorable e) {
            return deadLetter(in, value, sourceUrl, null, e.getMessage(), now);
        } catch (RuntimeException e) {
            return deadLetter(in, value, sourceUrl, null,
                    "the element set could not be read: " + e.getClass().getSimpleName(), now);
        }
        try {
            store.apply(row);
            return Outcome.APPLIED;
        } catch (NotStorable e) {
            return deadLetter(in, value, sourceUrl, null, e.getMessage(), now);
        }
    }

    /** As the risk engine does, a source_url the dead letter schema would refuse is left out rather than failing. */
    private Outcome deadLetter(In in, byte[] value, String sourceUrl, String check, String reason, Instant now) {
        DeadLetters.Message m;
        try {
            m = deadLetters.build(TOPIC, sourceUrl, in.key(), check, reason, value, now);
        } catch (IllegalStateException e) {
            m = deadLetters.build(TOPIC, null, in.key(), check, reason, value, now);
        }
        return new Outcome.DeadLetter(m);
    }
}
