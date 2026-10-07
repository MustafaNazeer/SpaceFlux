package io.github.mustafanazeer.spaceflux.query.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import graphql.analysis.QueryComplexityCalculator;
import graphql.execution.CoercedVariables;
import graphql.language.Document;
import graphql.normalized.ExecutableNormalizedOperationFactory;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;

/**
 * Pins what ADR 0012, fact 8, found in graphql-java 25.0: the complexity calculator undercounts a named fragment
 * spread under several aliases, while the normalized operation counts it in full. This is why the field count
 * limit stands beside the cost limit. If an upgrade changes either number, this test says so.
 */
class FragmentCountTest {

    static final GraphQLSchema SCHEMA = new SchemaGenerator().makeExecutableSchema(
            new SchemaParser().parse("type Query { obj: Obj } type Obj { id: ID name: String parent: Obj }"),
            RuntimeWiring.newRuntimeWiring().build());

    static final String INLINE = "{ a: obj { id name parent { id name } } b: obj { id name parent { id name } } "
            + "c: obj { id name parent { id name } } d: obj { id name parent { id name } } }";
    static final String NAMED = "{ a: obj { ...f } b: obj { ...f } c: obj { ...f } d: obj { ...f } } "
            + "fragment f on Obj { id name parent { id name } }";

    static int complexity(String query) {
        Document document = Parser.parse(query);
        return QueryComplexityCalculator.newCalculator().schema(SCHEMA).document(document)
                .variables(CoercedVariables.emptyVariables()).fieldComplexityCalculator(GraphQlLimits.COST).build()
                .calculate();
    }

    static int normalizedFields(String query) {
        return ExecutableNormalizedOperationFactory.createExecutableNormalizedOperation(SCHEMA, Parser.parse(query),
                null, CoercedVariables.emptyVariables()).getOperationFieldCount();
    }

    @Test
    void theCostOfAReusedNamedFragmentIsUndercounted() {
        assertThat(complexity(INLINE)).isEqualTo(24);
        assertThat(complexity(NAMED)).isEqualTo(9);
    }

    @Test
    void theNormalizedOperationCountsBothFormsAlike() {
        assertThat(normalizedFields(INLINE)).isEqualTo(24);
        assertThat(normalizedFields(NAMED)).isEqualTo(24);
    }
}
