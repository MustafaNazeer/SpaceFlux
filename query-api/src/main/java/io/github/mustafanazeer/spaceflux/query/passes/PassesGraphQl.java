package io.github.mustafanazeer.spaceflux.query.passes;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import graphql.GraphQLContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code WatchlistObject.passes} (docs/api/graphql.md): the REST answer turned into the same snake_case fields, as
 * every other GraphQL read is. The clock is read once per request, so every object shares one window.
 */
@Controller
class PassesGraphQl {

    /** The request's window start, kept in the request's GraphQL context. */
    static final String NOW = PassesGraphQl.class.getName() + ".now";

    private final PassesController passes;
    private final Clock clock;
    private final JsonMapper json;

    PassesGraphQl(PassesController passes, Clock clock, JsonMapper json) {
        this.passes = passes;
        this.clock = clock;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    @SchemaMapping(typeName = "WatchlistObject", field = "passes")
    Map<String, Object> passes(Map<String, Object> watchlistObject, GraphQLContext request) {
        Instant now = request.computeIfAbsent(NOW, k -> clock.instant());
        return json.convertValue(passes.passes(((Number) watchlistObject.get("catalog_number")).longValue(), now),
                Map.class);
    }
}
