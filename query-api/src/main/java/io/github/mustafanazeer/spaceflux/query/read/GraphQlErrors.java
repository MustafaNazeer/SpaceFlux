package io.github.mustafanazeer.spaceflux.query.read;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.SerializationError;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.schema.DataFetchingEnvironment;
import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/**
 * A refusal written by the API keeps its own text as a BAD_REQUEST error. Anything else is left to Spring for
 * GraphQL, which answers INTERNAL_ERROR with a generic message and logs the exception (T5.5). A stored value its
 * GraphQL type cannot hold is refused after its data fetcher returns, so the exception resolvers never see it:
 * graphql-java writes its own error, naming the value's Java type, so that error is replaced here by the same generic
 * INTERNAL_ERROR and its text goes only to the log.
 */
@Component
class GraphQlErrors extends DataFetcherExceptionResolverAdapter implements Instrumentation {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQlErrors.class);

    @Override
    protected GraphQLError resolveToSingleError(Throwable e, DataFetchingEnvironment env) {
        if (e instanceof ApiErrors.Refused refused && refused.status() == HttpStatus.BAD_REQUEST) {
            return GraphQLError.newError().errorType(ErrorType.BAD_REQUEST).message(refused.getMessage())
                    .path(env.getExecutionStepInfo().getPath()).location(env.getField().getSourceLocation()).build();
        }
        return null;
    }

    /** Called for a query's result and for each subscription event. */
    @Override
    public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult result,
            InstrumentationExecutionParameters parameters, InstrumentationState state) {
        if (result.getErrors().stream().noneMatch(SerializationError.class::isInstance)) {
            return CompletableFuture.completedFuture(result);
        }
        String executionId = String.valueOf(parameters.getExecutionInput().getExecutionId());
        List<GraphQLError> errors = result.getErrors().stream()
                .map(e -> e instanceof SerializationError s ? internal(s, executionId) : e).toList();
        return CompletableFuture.completedFuture(result.transform(b -> b.errors(errors)));
    }

    private static GraphQLError internal(SerializationError e, String executionId) {
        LOG.error("Unserializable value for executionId {}: {}", executionId, e.getMessage());
        return GraphQLError.newError().errorType(ErrorType.INTERNAL_ERROR)
                .message(ErrorType.INTERNAL_ERROR + " for " + executionId).path(e.getPath())
                .locations(e.getLocations()).build();
    }
}
