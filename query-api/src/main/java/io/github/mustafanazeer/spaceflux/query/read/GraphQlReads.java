package io.github.mustafanazeer.spaceflux.query.read;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;
import tools.jackson.databind.json.JsonMapper;

/**
 * The GraphQL read fields (docs/api/graphql.md). Each answer is the REST answer turned into the same snake_case
 * fields by the application's own JSON settings, so the two APIs cannot drift apart field by field. What REST
 * answers with 404 is null here; every other refusal becomes a GraphQL error (GraphQlErrors).
 */
@Controller
class GraphQlReads {

    private final SpaceWeatherController spaceWeather;
    private final SpaceWeatherHistoryController history;
    private final ScreeningController screening;
    private final AlertController alerts;
    private final CatalogController catalog;
    private final WatchlistController watchlist;
    private final AlertLists lists;
    private final JsonMapper json;

    GraphQlReads(SpaceWeatherController spaceWeather, SpaceWeatherHistoryController history,
            ScreeningController screening, AlertController alerts, CatalogController catalog,
            WatchlistController watchlist, AlertLists lists, JsonMapper json) {
        this.spaceWeather = spaceWeather;
        this.history = history;
        this.screening = screening;
        this.alerts = alerts;
        this.catalog = catalog;
        this.watchlist = watchlist;
        this.lists = lists;
        this.json = json;
    }

    @QueryMapping
    Map<String, Object> space_weather_current() {
        return fields(spaceWeather.current());
    }

    @QueryMapping
    Map<String, Object> space_weather_history(@Argument String scale, @Argument Integer satellite,
            @Argument String from, @Argument String to, @Argument Integer limit, @Argument String after) {
        return fields(history.history(scale, satellite, from, to, limit == null ? 50 : limit, after));
    }

    @QueryMapping
    Map<String, Object> screening_current() {
        ScreeningController.Current current = orNull(screening::current);
        if (current == null) {
            return null;
        }
        List<Map<String, Object>> approaches = new ArrayList<>();
        for (ScreeningController.Approach a : current.approaches()) {
            Map<String, Object> approach = new LinkedHashMap<>();
            approach.put("event_id", a.eventId());
            approach.put("close_approach", parse(a.closeApproach()));
            approach.put("acknowledgement", fields(a.acknowledgement()));
            approaches.add(approach);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stale", current.stale());
        out.put("summary", parse(current.summary()));
        out.put("approaches", approaches);
        return out;
    }

    @QueryMapping
    Map<String, Object> alert(@Argument("event_id") String eventId) {
        AlertController.Alert alert = orNull(() -> alerts.byId(eventId));
        if (alert == null) {
            return null;
        }
        Map<String, Object> out = parse(alert.event());
        out.put("received_at", alert.receivedAt());
        out.put("acknowledgement", fields(alert.acknowledgement()));
        return out;
    }

    @QueryMapping
    Map<String, Object> alerts(@Argument Integer limit, @Argument String after) {
        return lists.recent(limit == null ? 50 : limit, after);
    }

    @SchemaMapping(typeName = "CatalogObject", field = "close_approaches")
    Map<String, Object> closeApproaches(Map<String, Object> object, @Argument Integer limit, @Argument String after) {
        return lists.objectApproaches(((Number) object.get("norad_cat_id")).longValue(), limit == null ? 20 : limit,
                after);
    }

    @QueryMapping
    Map<String, Object> catalog_object(@Argument("norad_cat_id") long noradCatId) {
        return fields(orNull(() -> catalog.byNumber(noradCatId)));
    }

    @QueryMapping("watchlist")
    Object watchlistObjects() {
        return fields(watchlist.watchlist()).get("objects");
    }

    private static <T> T orNull(Supplier<T> read) {
        try {
            return read.get();
        } catch (ApiErrors.Refused e) {
            if (e.status() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fields(Object answer) {
        return answer == null ? null : json.convertValue(answer, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String stored) {
        return json.readValue(stored, LinkedHashMap.class);
    }
}
