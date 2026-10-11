package io.github.mustafanazeer.spaceflux.query.live;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlRequest;

import graphql.ExecutionResult;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.OperationDefinition;

/**
 * One operation type per transport (ADR 0014, decision 1): subscriptions over the WebSocket only, queries over HTTP
 * only. Refused before any data fetcher runs.
 */
final class Transports {

    static final String OVER_SOCKET_ONLY = "Subscriptions are served over WebSocket only.";
    static final String SUBSCRIPTIONS_ONLY = "Only subscriptions are served over WebSocket.";

    private Transports() {
    }

    /** Marks a request that came over the socket, and hands its connection to the subscription. */
    static WebGraphQlInterceptor marker() {
        return (request, chain) -> {
            if (request instanceof WebSocketGraphQlRequest socket) {
                Object connection = socket.getSessionInfo().getAttributes().get(Connection.KEY);
                request.configureExecutionInput((input, builder) -> builder.graphQLContext(context -> {
                    context.put(Transports.class, Boolean.TRUE);
                    if (connection != null) {
                        context.put(Connection.KEY, connection);
                    }
                }).build());
            }
            return chain.next(request);
        };
    }

    static SimplePerformantInstrumentation rule() {
        return new SimplePerformantInstrumentation() {
            @Override
            public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                    InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
                boolean socket = parameters.getExecutionContext().getGraphQLContext().hasKey(Transports.class);
                boolean subscription = parameters.getExecutionContext().getOperationDefinition().getOperation()
                        == OperationDefinition.Operation.SUBSCRIPTION;
                if (socket && !subscription) {
                    throw new AbortExecutionException(SUBSCRIPTIONS_ONLY);
                }
                if (!socket && subscription) {
                    throw new AbortExecutionException(OVER_SOCKET_ONLY);
                }
                return SimpleInstrumentationContext.noOp();
            }
        };
    }
}
