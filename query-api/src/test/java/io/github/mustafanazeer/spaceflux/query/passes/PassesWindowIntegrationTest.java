package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Every watchlist object in one GraphQL request is computed over the same window, read from the clock once. */
class PassesWindowIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static OperatorApp app;

    /** Starts inside the recorded ISS element set's age limit and moves on a whole second at every read. */
    static final class TickingClock extends Clock {

        private final AtomicLong seconds = new AtomicLong(Instant.parse("2026-09-27T05:00:00Z").getEpochSecond());

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochSecond(seconds.getAndIncrement());
        }
    }

    @Configuration
    static class Ticking {

        @Bean
        @Primary
        Clock tickingClock() {
            return new TickingClock();
        }
    }

    @BeforeAll
    static void start() throws Exception {
        app = new OperatorApp(new Class<?>[] {Ticking.class});
        PassesApiIntegrationTest.storeIss("2026-09-27T04:10:50.460096", "U");
        TestMysql.rootSql("INSERT INTO spaceflux.watchlist_object VALUES (48274, 'CSS (TIANHE)', 1)");
        TestMysql.rootSql("INSERT INTO spaceflux.watchlist_object VALUES (57036, 'OBJECT AJ', 1)");
    }

    @AfterAll
    static void stop() throws Exception {
        TestMysql.rootSql("DELETE FROM spaceflux.watchlist_object WHERE catalog_number IN (48274, 57036)");
        app.close();
    }

    @Test
    void everyObjectInOneRequestSharesTheWindowStart() throws Exception {
        var b = app.browser();
        b.get("/api/auth/session");

        HttpResponse<String> r = b.postJson("/api/graphql", JSON.writeValueAsString(Map.of("query",
                "{ watchlist { catalog_number passes { window_start window_end status } } }")));

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.has("errors")).as(r.body()).isFalse();
        List<String> starts = new ArrayList<>();
        for (JsonNode o : body.get("data").get("watchlist")) {
            starts.add(o.get("passes").get("window_start").asString());
        }
        assertThat(starts).hasSize(3).containsOnly(starts.getFirst());
    }
}
