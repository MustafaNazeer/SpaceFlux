package io.github.mustafanazeer.spaceflux.query.read;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/watchlist} (docs/api/rest.md, section 6). */
@RestController
class WatchlistController {

    static final String WATCHLIST = "SELECT w.catalog_number, w.name, w.rules_version, " + CatalogView.COLUMNS
            + " FROM watchlist_object w LEFT JOIN catalog_object c ON c.norad_cat_id = w.catalog_number "
            + "ORDER BY w.catalog_number";

    private final JdbcClient api;

    record Watchlist(List<WatchlistObject> objects) {
    }

    record WatchlistObject(long catalogNumber, String name, long rulesVersion, CatalogView catalog) {
    }

    WatchlistController(@Qualifier("apiJdbcClient") JdbcClient api) {
        this.api = api;
    }

    @GetMapping("/api/watchlist")
    Watchlist watchlist() {
        return new Watchlist(api.sql(WATCHLIST)
                .query((rs, n) -> new WatchlistObject(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        CatalogView.read(rs, 4)))
                .list());
    }
}
