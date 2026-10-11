package io.github.mustafanazeer.spaceflux.query.alerts;

/** Told the event_id of each alert whose insert committed, on the consumer's thread (ADR 0014, decision 2). */
@FunctionalInterface
public interface AlertStored {

    /** Must return at once; the consumer's next record waits for it. */
    void stored(String eventId);
}
