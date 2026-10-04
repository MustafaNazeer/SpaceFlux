package io.github.mustafanazeer.spaceflux.query.catalog;

import java.time.LocalDateTime;

/** One stored {@code catalog_object} row: the element set held and the range of fetches seen for the object. */
record CatalogState(CatalogRow row, LocalDateTime firstFetchedAt, LocalDateTime lastFetchedAt) {
}
