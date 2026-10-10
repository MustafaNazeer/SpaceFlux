package io.github.mustafanazeer.spaceflux.query.passes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/** The words a reader sees about passes, in the REST note and the GraphQL schema descriptions. */
class PassesWordingTest {

    static TypeDefinitionRegistry schema() throws IOException {
        try (InputStream in = PassesWordingTest.class.getResourceAsStream("/graphql/schema.graphqls")) {
            return new SchemaParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static ObjectTypeDefinition type(String name) throws IOException {
        return schema().getType(name, ObjectTypeDefinition.class).orElseThrow();
    }

    static String field(String type, String field) throws IOException {
        return type(type).getFieldDefinitions().stream().filter(f -> f.getName().equals(field)).findFirst()
                .map(FieldDefinition::getDescription).orElseThrow().getContent();
    }

    @Test
    void theNoteSaysWhatAGeometricPassIsAndIsNot() {
        assertThat(PassesController.NOTE).isEqualTo("Geometric passes: times when this public element set, "
                + "propagated with SGP4, puts the object at or above 10 degrees of elevation from this point, with no "
                + "atmospheric refraction. They do not say whether the object can be seen (sunlight, darkness, "
                + "weather), they grow less accurate as the element set ages, and they are not an operational "
                + "prediction.");
    }

    @Test
    void thePassDescriptionExplainsClippedPasses() throws IOException {
        assertThat(type("Pass").getDescription().getContent()).isEqualTo("One pass. A pass in progress at the start "
                + "of the window has no rise and has a start_edge; one in progress at search_end has no set and has an "
                + "end_edge. A clipped pass can be higher at its edge than at its peak.");
    }

    @Test
    void theEpochAndAgeDescriptionsSayWhatTheyAre() throws IOException {
        assertThat(field("Passes", "epoch_text")).isEqualTo("Epoch of the element set used, UTC, exactly as "
                + "received with no zone suffix.");
        assertThat(field("Pass", "element_age_days")).isEqualTo("Element set age at the time of peak, in days; "
                + "accuracy falls as it grows.");
    }
}
