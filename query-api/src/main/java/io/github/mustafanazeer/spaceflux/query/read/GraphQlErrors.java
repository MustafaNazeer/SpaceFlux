package io.github.mustafanazeer.spaceflux.query.read;

import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/**
 * A refusal written by the API keeps its own text as a BAD_REQUEST error. Anything else is left to Spring for
 * GraphQL, which answers INTERNAL_ERROR with a generic message and logs the exception (T5.5).
 */
@Component
class GraphQlErrors extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable e, DataFetchingEnvironment env) {
        if (e instanceof ApiErrors.Refused refused && refused.status() == HttpStatus.BAD_REQUEST) {
            return GraphQLError.newError().errorType(ErrorType.BAD_REQUEST).message(refused.getMessage())
                    .path(env.getExecutionStepInfo().getPath()).location(env.getField().getSourceLocation()).build();
        }
        return null;
    }
}
