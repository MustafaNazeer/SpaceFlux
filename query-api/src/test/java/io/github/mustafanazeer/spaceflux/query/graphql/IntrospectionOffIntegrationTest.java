package io.github.mustafanazeer.spaceflux.query.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import graphql.introspection.Introspection;
import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The default, which the demo environment keeps (ADR 0012, option A): __schema and __type are refused, __typename
 * still answers.
 * Boot turns introspection off for the whole JVM and never back on (ADR 0012, fact 1), so this class turns it back
 * on when it ends, for the test classes that run after it in the same JVM.
 */
class IntrospectionOffIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp();
    }

    @AfterAll
    static void stop() {
        try {
            app.close();
        } finally {
            Introspection.enabledJvmWide(true);
        }
    }

    static JsonNode query(String query) throws Exception {
        Browser b = app.browser();
        b.get("/api/auth/session");
        return JSON.readTree(b.postJson("/api/graphql", JSON.writeValueAsString(Map.of("query", query))).body());
    }

    @Test
    void theSwitchIsOffForTheWholeJvm() {
        assertThat(Introspection.isEnabledJvmWide()).isFalse();
    }

    @Test
    void schemaAndTypeAreRefused() throws Exception {
        assertThat(query("{ __schema { queryType { name } } }").get("errors").get(0).get("message").asString())
                .contains("Introspection has been disabled");
        assertThat(query("{ __type(name: \"Query\") { name } }").get("errors").get(0).get("message").asString())
                .contains("Introspection has been disabled");
    }

    @Test
    void typenameStillAnswers() throws Exception {
        JsonNode r = query("{ __typename watchlist { __typename } }");

        assertThat(r.has("errors")).isFalse();
        assertThat(r.get("data").get("__typename").asString()).isEqualTo("Query");
    }

    @Test
    void introspectionOnlyQueriesAreHeldToTheDepthLimitWhileItIsOff() throws Exception {
        JsonNode r = query(graphql.introspection.IntrospectionQuery.INTROSPECTION_QUERY);

        assertThat(r.get("errors").get(0).get("message").asString()).contains("maximum query depth exceeded");
    }
}
