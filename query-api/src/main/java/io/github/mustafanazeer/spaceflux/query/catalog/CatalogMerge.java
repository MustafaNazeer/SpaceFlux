package io.github.mustafanazeer.spaceflux.query.catalog;

import java.time.LocalDateTime;

/**
 * The catalog update rule (docs/data/mysql-schema.md, {@code catalog_object}): the element set moves only to a later
 * epoch, an equal epoch keeps the stored copy, and the first and last fetch times move to the earlier and later of
 * the stored and received ones. Every step is a minimum or a maximum, so the result does not depend on the order or
 * the number of deliveries, except between two different element sets with the same epoch, where the copy stored
 * first is kept, as in the risk engine.
 */
final class CatalogMerge {

    private CatalogMerge() {
    }

    static CatalogState apply(CatalogState held, CatalogRow received) {
        if (held == null) {
            return new CatalogState(received, received.fetchedAt(), received.fetchedAt());
        }
        CatalogRow row = received.epoch().isAfter(held.row().epoch()) ? received : held.row();
        return new CatalogState(row, earlier(held.firstFetchedAt(), received.fetchedAt()),
                later(held.lastFetchedAt(), received.fetchedAt()));
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return b.isBefore(a) ? b : a;
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return b.isAfter(a) ? b : a;
    }
}
