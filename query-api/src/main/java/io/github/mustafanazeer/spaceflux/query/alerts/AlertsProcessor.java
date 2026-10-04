package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import io.github.mustafanazeer.spaceflux.contracts.DeadLetters;
import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.RecordText;
import io.github.mustafanazeer.spaceflux.query.consume.RuleRejected;

/**
 * Applies one alerts record: checks it against the schema, stores its first arrival, and drops a repeat. Nothing in a
 * record's content can make this throw; a record that cannot be stored becomes a dead letter for alerts.dlq. Only a
 * failure of the database itself is thrown, so the record is delivered again rather than lost.
 */
final class AlertsProcessor {

    static final String TOPIC = "alerts";
    static final String SERVICE = "query-api";

    private final TopicSchemas schemas;
    private final DeadLetters deadLetters;
    private final AlertStore store;

    record In(String key, byte[] value, int partition, long offset) {
    }

    sealed interface Outcome permits Outcome.Stored, Outcome.Repeat, Outcome.DeadLetter {

        Outcome STORED = new Stored();
        Outcome REPEAT = new Repeat();

        record Stored() implements Outcome {
        }

        record Repeat() implements Outcome {
        }

        record DeadLetter(DeadLetters.Message message) implements Outcome {
        }
    }

    AlertsProcessor(TopicSchemas schemas, AlertStore store) {
        this.schemas = schemas;
        this.deadLetters = new DeadLetters(schemas, SERVICE);
        this.store = store;
    }

    Outcome process(In in, Instant now) {
        byte[] value = in.value() == null ? new byte[0] : in.value();
        String text = RecordText.strictUtf8(value);
        if (text == null) {
            return deadLetter(in, value, "schema", "not UTF-8 text", now);
        }
        TopicSchemas.Result checked = schemas.check(TOPIC, value);
        if (checked.failure() != null) {
            return deadLetter(in, value, "schema", checked.failure(), now);
        }
        EventRows rows;
        try {
            rows = EventRows.of(RecordText.withDecimals(text), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        } catch (RuleRejected e) {
            return deadLetter(in, value, "rule", e.getMessage(), now);
        } catch (NotStorable e) {
            return deadLetter(in, value, null, e.getMessage(), now);
        } catch (RuntimeException e) {
            return deadLetter(in, value, null, "the event could not be read: " + e.getClass().getSimpleName(), now);
        }
        try {
            return store.store(rows, text, in.partition(), in.offset())
                    ? Outcome.STORED : Outcome.REPEAT;
        } catch (NotStorable e) {
            return deadLetter(in, value, null, e.getMessage(), now);
        }
    }

    private Outcome deadLetter(In in, byte[] value, String check, String reason, Instant now) {
        return new Outcome.DeadLetter(deadLetters.build(TOPIC, null, in.key(), check, reason, value, now));
    }
}
