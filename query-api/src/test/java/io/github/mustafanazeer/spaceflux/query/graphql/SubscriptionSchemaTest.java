package io.github.mustafanazeer.spaceflux.query.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import graphql.analysis.QueryComplexityCalculator;
import graphql.execution.CoercedVariables;
import graphql.parser.Parser;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;

/** The subscription's payload and its cost (ADR 0014 decision 3). */
class SubscriptionSchemaTest {

    @Test
    void theOnlySubscriptionIsAlertStoredWithNoArgumentsAndALeanNotice() throws Exception {
        GraphQLSchema schema = PassesCostTest.schema();
        GraphQLObjectType subscription = schema.getSubscriptionType();

        assertThat(subscription.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
                .containsExactly("alert_stored");
        GraphQLFieldDefinition field = subscription.getFieldDefinition("alert_stored");
        assertThat(field.getArguments()).isEmpty();
        assertThat(GraphQLTypeUtil.simplePrint(field.getType())).isEqualTo("AlertNotice!");
        GraphQLObjectType notice = schema.getObjectType("AlertNotice");
        assertThat(notice.getFieldDefinitions()).extracting(f -> f.getName() + ": "
                + GraphQLTypeUtil.simplePrint(f.getType())).containsExactly("kind: String!",
                        "rules_version: UInt32!", "event_id: String!", "produced_at: String!", "received_at: String!",
                        "space_weather_level: SpaceWeatherLevel", "close_approach: CloseApproach");
    }

    static final String PAGED = "type Query { a: Int } type Subscription { page(limit: Int): [Item!]! } "
            + "type Item { x: Int }";

    static int cost(String sdl, String query) {
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(sdl),
                RuntimeWiring.newRuntimeWiring().build());
        return QueryComplexityCalculator.newCalculator().schema(schema).document(Parser.parse(query))
                .variables(CoercedVariables.emptyVariables()).fieldComplexityCalculator(GraphQlLimits.COST).build()
                .calculate();
    }

    @Test
    void aSubscriptionRootFieldIsCostedAsTopLevel() {
        // The top level default page: 50 x (1 + 1), not the nested 20 x (1 + 1).
        assertThat(cost(PAGED, "subscription { page { x } }")).isEqualTo(100);
    }

    @Test
    void theNoticeCostsOnePerFieldItSelects() throws Exception {
        assertThat(PassesCostTest.cost("subscription { alert_stored { kind event_id close_approach { run_id "
                + "watchlist_object { name } } } }")).isEqualTo(7);
    }
}
