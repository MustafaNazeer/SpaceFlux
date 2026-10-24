package io.github.mustafanazeer.spaceflux.query.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.SerializationError;
import graphql.execution.ExecutionId;
import graphql.execution.ResultPath;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.schema.CoercingSerializeException;

@ExtendWith(OutputCaptureExtension.class)
class GraphQlErrorsTest {

    /**
     * An enum's refusal would quote a stored string, and on WebSocket the execution id is the client's subscribe id,
     * so neither may start a line of its own in the log.
     */
    @Test
    void aStoredStringAndTheExecutionIdCannotShapeTheLog(CapturedOutput output) throws Exception {
        SerializationError error = new SerializationError(ResultPath.parse("/alert/kind"),
                new CoercingSerializeException("Unknown value 'x\nFORGED one'"));
        ExecutionInput input = ExecutionInput.newExecutionInput("{ alert { kind } }")
                .executionId(ExecutionId.from("1\nFORGED two\u2028")).build();

        new GraphQlErrors().instrumentExecutionResult(
                ExecutionResult.newExecutionResult().errors(List.of(error)).build(),
                new InstrumentationExecutionParameters(input, null), null).get();

        assertThat(output.getAll()).contains("Unserializable value for executionId 1\\nFORGED two\\u2028: ")
                .contains("Unknown value 'x\\nFORGED one'").doesNotContain("\nFORGED").doesNotContain("\u2028");
    }
}
