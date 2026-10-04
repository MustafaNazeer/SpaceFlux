package io.github.mustafanazeer.spaceflux.query.catalog;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;

/** Where the newest element set per object is kept. */
interface CatalogStore {

    /**
     * Applies one element set by the catalog update rule. Throws {@link NotStorable} when the database refuses a
     * value, and any other exception when the database could not be reached or failed, in which case nothing changed.
     */
    void apply(CatalogRow row);
}
