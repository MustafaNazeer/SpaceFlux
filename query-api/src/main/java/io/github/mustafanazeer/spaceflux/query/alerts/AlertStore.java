package io.github.mustafanazeer.spaceflux.query.alerts;

/** Where a schema valid alerts event is kept. */
interface AlertStore {

    /**
     * Stores a first arrival and returns true, or returns false when its {@code event_id} is already stored. Throws
     * {@link NotStorable} when the database refuses a value of the event, and any other exception when the database
     * could not be reached or failed, in which case nothing was stored.
     */
    boolean store(AlertRow row, String payload, int partition, long offset);
}
