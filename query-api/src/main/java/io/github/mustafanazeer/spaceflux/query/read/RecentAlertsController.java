package io.github.mustafanazeer.spaceflux.query.read;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/alerts/recent} (docs/api/rest.md, section 11): Q3, the list GraphQL's alerts field also reads. */
@RestController
class RecentAlertsController {

    private final AlertLists lists;

    RecentAlertsController(AlertLists lists) {
        this.lists = lists;
    }

    @GetMapping("/api/alerts/recent")
    @SuppressWarnings("unchecked")
    Map<String, Object> recent(@RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "kind", required = false) String kind) {
        Map<String, Object> page = lists.recent(limit, after, kind);
        // GraphQL answers a missing value as null; REST leaves it out.
        for (Map<String, Object> item : (List<Map<String, Object>>) page.get("items")) {
            item.values().removeIf(v -> v == null);
        }
        page.values().removeIf(v -> v == null);
        return page;
    }
}
