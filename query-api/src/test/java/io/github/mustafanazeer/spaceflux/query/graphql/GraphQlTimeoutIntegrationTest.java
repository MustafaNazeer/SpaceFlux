package io.github.mustafanazeer.spaceflux.query.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR 0012, fact 11: the request timeout does not stop a data fetcher that blocks on the request thread, which is
 * every fetcher this service has. The JDBC query timeout is what bounds them (GraphQlLimitsIntegrationTest).
 */
class GraphQlTimeoutIntegrationTest {

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp(new Class<?>[] {SlowField.class}, "--spaceflux.graphql.request-timeout=500ms",
                "--spring.graphql.schema.locations=classpath:graphql/,classpath:graphql-slow/");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @Controller
    static class SlowField {

        private final org.springframework.jdbc.core.simple.JdbcClient api;

        SlowField(@org.springframework.beans.factory.annotation.Qualifier("apiJdbcClient")
                org.springframework.jdbc.core.simple.JdbcClient api) {
            this.api = api;
        }

        /** A statement that runs far past the 3 second query timeout unless the driver cancels it. */
        @QueryMapping
        Integer slow_statement() {
            return api.sql("SELECT BENCHMARK(5000000000, SHA2('x', 512))").query(Integer.class).single();
        }

        @QueryMapping
        String slow_blocking() throws InterruptedException {
            Thread.sleep(2_000);
            return "finished";
        }
    }

    @Test
    void aFetcherThatBlocksForTwoSecondsFinishesPastAHalfSecondTimeout() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        long start = System.nanoTime();

        HttpResponse<String> r = b.postJson("/api/graphql",
                new ObjectMapper().writeValueAsString(Map.of("query", "{ slow_blocking }")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(r.body()).contains("finished");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2_000);
    }

    @Test
    void aStatementCancelledByTheQueryTimeoutGetsAGenericError() throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        long start = System.nanoTime();

        // A request timeout of its own, so a broken query timeout fails this test instead of hanging the build.
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(app.base + "/api/graphql"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + b.cookies.get("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", b.cookies.get("XSRF-TOKEN"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        new ObjectMapper().writeValueAsString(Map.of("query", "{ slow_statement }"))))
                .build();
        HttpResponse<String> r = Browser.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isBetween(2_500L, 8_000L);
        assertThat(r.body()).contains("INTERNAL_ERROR").doesNotContain("BENCHMARK").doesNotContain("SELECT")
                .doesNotContain("Exception").doesNotContain("at org.").doesNotContain("timeout");
    }
}
