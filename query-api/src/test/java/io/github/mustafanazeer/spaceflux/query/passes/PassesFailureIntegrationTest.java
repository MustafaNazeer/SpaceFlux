package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A pass computation that throws, as an inconsistent event sequence does (PassGrouping), reaches the client only as
 * the generic error of each API: REST 500 "The request failed.", GraphQL INTERNAL_ERROR with no detail, where it
 * nulls only that object's passes.
 */
class PassesFailureIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "a rise at 1000.0 s while a pass is already open, at org.orekit.secret";

    static OperatorApp app;

    @Configuration
    static class ThrowingFinder {

        @Bean
        @Primary
        PassFinder throwingPassFinder() {
            return new PassFinder() {
                @Override
                Outcome find(TrackedObject object, AbsoluteDate start) {
                    throw new IllegalStateException(SECRET);
                }
            };
        }
    }

    @BeforeAll
    static void start() throws Exception {
        app = new OperatorApp(new Class<?>[] {PassesApiIntegrationTest.FixedClock.class, ThrowingFinder.class});
        PassesApiIntegrationTest.storeIss("2026-09-27T04:10:50.460096", "U");
        TestMysql.rootSql("INSERT INTO spaceflux.watchlist_object VALUES (48274, 'CSS (TIANHE)', 1)");
    }

    @AfterAll
    static void stop() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.watchlist_object WHERE catalog_number = 48274");
        app.close();
    }

    @Test
    void restAnswersTheGeneric500WithNoDetailOfTheFailure() throws Exception {
        HttpResponse<String> r = app.browser().get("/api/watchlist/25544/passes");

        assertThat(r.statusCode()).isEqualTo(500);
        JsonNode p = JSON.readTree(r.body());
        assertThat(p.get("detail").asString()).isEqualTo("The request failed.");
        assertThat(r.body()).doesNotContain("rise").doesNotContain("orekit").doesNotContain("Exception")
                .doesNotContain("at io.");
    }

    /** 48274 has no catalog row, so its passes are answered without the finder. */
    @Test
    void graphQlNullsOnlyTheFailedObjectsPassesWithAnInternalErrorAndNoDetail() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");

        HttpResponse<String> r = b.postJson("/api/graphql", JSON.writeValueAsString(Map.of("query",
                "{ watchlist { catalog_number name passes { status } } }")));

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        JsonNode watchlist = body.get("data").get("watchlist");
        assertThat(watchlist).hasSize(2);
        int iss = watchlist.get(0).get("catalog_number").asInt() == 25544 ? 0 : 1;
        assertThat(watchlist.get(iss).get("name").asString()).isEqualTo("ISS (ZARYA)");
        assertThat(watchlist.get(iss).get("passes").isNull()).isTrue();
        assertThat(watchlist.get(1 - iss).get("catalog_number").asInt()).isEqualTo(48274);
        assertThat(watchlist.get(1 - iss).get("passes").get("status").asString()).isEqualTo("no_element_set");
        assertThat(body.get("errors")).singleElement().satisfies(e -> {
            assertThat(e.get("extensions").get("classification").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(e.get("path").toString()).isEqualTo("[\"watchlist\"," + iss + ",\"passes\"]");
        });
        assertThat(r.body()).doesNotContain("rise").doesNotContain("orekit").doesNotContain("Exception")
                .doesNotContain("at io.");
    }
}
