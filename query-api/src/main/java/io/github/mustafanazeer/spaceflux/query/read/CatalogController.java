package io.github.mustafanazeer.spaceflux.query.read;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/** {@code GET /api/catalog/{norad_cat_id}} (docs/api/rest.md, section 5). */
@RestController
class CatalogController {

    static final long NORAD_MAX = 999_999_999L;

    private final JdbcClient api;

    CatalogController(@Qualifier("apiJdbcClient") JdbcClient api) {
        this.api = api;
    }

    @GetMapping("/api/catalog/{norad_cat_id}")
    CatalogView byNumber(@PathVariable("norad_cat_id") long noradCatId) {
        if (noradCatId < 0 || noradCatId > NORAD_MAX) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST, "A catalog number is a whole number from 0 to "
                    + "999999999.");
        }
        return api.sql("SELECT " + CatalogView.COLUMNS + " FROM catalog_object c WHERE c.norad_cat_id = ?")
                .param(noradCatId)
                .query((rs, n) -> CatalogView.read(rs, 1))
                .optional()
                .orElseThrow(() -> new ApiErrors.Refused(HttpStatus.NOT_FOUND, "No catalog object has this number."));
    }
}
