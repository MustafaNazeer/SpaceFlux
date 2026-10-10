package io.github.mustafanazeer.spaceflux.query.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import graphql.analysis.QueryComplexityCalculator;
import graphql.execution.CoercedVariables;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;

/** What passes cost under the service's own schema and calculator (docs/api/graphql.md, "Limits"). */
class PassesCostTest {

    static GraphQLSchema schema() throws IOException {
        try (InputStream in = PassesCostTest.class.getResourceAsStream("/graphql/schema.graphqls")) {
            return new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)), RuntimeWiring.newRuntimeWiring().build());
        }
    }

    static int cost(String query) throws IOException {
        return QueryComplexityCalculator.newCalculator().schema(schema()).document(Parser.parse(query))
                .variables(CoercedVariables.emptyVariables()).fieldComplexityCalculator(GraphQlLimits.COST).build()
                .calculate();
    }

    @Test
    void passesCostTheirWeightPlusWhatTheySelectForEachWatchlistObjectTheBoundAllows() throws Exception {
        // 10 x (1 + (100 + 1 + 1)) = 1,030.
        assertThat(cost("{ watchlist { passes { status } } }")).isEqualTo(1_030);
        // 10 x (1 + 1 + (100 + 1 + 3 + (1 + (1 + 3)))) = 1,110.
        assertThat(cost("{ watchlist { catalog_number passes { status window_start window_end passes { peak { "
                + "time elevation_deg azimuth_deg } } } } }")).isEqualTo(1_110);
    }

    @Test
    void theCostAloneWouldRefuseTheWholeWatchlistsPassesTwice() throws Exception {
        assertThat(cost("{ a: watchlist { passes { status } } b: watchlist { passes { status } } }"))
                .isEqualTo(2_060).isGreaterThan(GraphQlLimits.MAX_COST);
    }

    @Test
    void theDashboardsFullPassesSelectionFitsUnderTheLimit() throws Exception {
        String all = "{ watchlist { catalog_number name passes { catalog_number name observer { name ngs_pid "
                + "latitude_deg longitude_deg height_m } elevation_mask_deg note window_start window_end status "
                + "reason epoch_text search_end stop_reason passes { rise { time elevation_deg azimuth_deg } "
                + "rise_clipped start_edge { time elevation_deg azimuth_deg } set { time elevation_deg azimuth_deg } "
                + "set_clipped end_edge { time elevation_deg azimuth_deg } peak { time elevation_deg azimuth_deg } "
                + "peak_at_edge peak_count element_age_days } } } }";

        assertThat(cost(all)).isLessThanOrEqualTo(GraphQlLimits.MAX_COST);
        System.out.println("Cost of every passes field for the whole watchlist: " + cost(all));
    }
}
