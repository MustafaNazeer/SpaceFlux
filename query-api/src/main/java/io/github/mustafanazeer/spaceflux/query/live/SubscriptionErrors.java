package io.github.mustafanazeer.spaceflux.query.live;

import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import graphql.GraphQLError;
import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/**
 * A refusal written by the API keeps its own text when a subscription fails, as GraphQlErrors does for queries. It
 * reaches the client as the protocol's error message for that operation. Anything else stays the generic
 * "Subscription error" that Spring for GraphQL sends and logs.
 */
@Component
class SubscriptionErrors extends SubscriptionExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable e) {
        if (e instanceof ApiErrors.Refused refused && refused.status() == HttpStatus.BAD_REQUEST) {
            return GraphQLError.newError().errorType(ErrorType.BAD_REQUEST).message(refused.getMessage()).build();
        }
        return null;
    }
}
