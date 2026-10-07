package io.github.mustafanazeer.spaceflux.query.graphql;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.server.TimeoutWebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlInterceptor;

import graphql.ExecutionResult;
import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.AbortExecutionException;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.Field;
import graphql.language.Selection;
import graphql.normalized.ExecutableNormalizedOperationFactory;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLTypeUtil;

/** The limits ADR 0012 decides, each checked by GraphQlLimitsIntegrationTest. */
@Configuration(proxyBeanMethods = false)
class GraphQlLimits {

    static final int MAX_DEPTH = 6;
    static final int MAX_FIELDS = 200;
    static final int MAX_COST = 2_000;
    static final int COST_CEILING = 100_000;
    static final int TOP_LEVEL_PAGE = 50;
    static final int NESTED_PAGE = 20;
    static final int WATCHLIST_MAX = 10;

    static final ParserOptions PARSER = ParserOptions.newParserOptions().maxCharacters(16_384).maxTokens(2_000)
            .maxWhitespaceTokens(10_000).maxRuleDepth(100).build();

    /** Parser limits for every request, so a large document is refused before validation runs. */
    @Bean
    @Order(1)
    WebGraphQlInterceptor parserLimits() {
        return (request, chain) -> {
            request.configureExecutionInput((input, builder) -> builder
                    .graphQLContext(context -> context.put(ParserOptions.class, PARSER)).build());
            return chain.next(request);
        };
    }

    /** A backstop for asynchronous data fetchers only; today's fetchers block and are bounded by the JDBC timeout. */
    @Bean
    @Order(2)
    TimeoutWebGraphQlInterceptor requestTimeout(@Value("${spaceflux.graphql.request-timeout:5s}") Duration timeout) {
        return new TimeoutWebGraphQlInterceptor(timeout);
    }

    @Bean
    @Order(1)
    Instrumentation depthLimit(@Value("${spring.graphql.schema.introspection.enabled:false}") boolean introspection) {
        return new MaxQueryDepthInstrumentation(MAX_DEPTH) {
            @Override
            public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                    InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
                if (introspection && onlyIntrospection(parameters.getExecutionContext())) {
                    return SimpleInstrumentationContext.noOp();
                }
                return super.beginExecuteOperation(parameters, state);
            }
        };
    }

    /** True when every root field is __schema, __type or __typename; good faith introspection bounds those. */
    static boolean onlyIntrospection(ExecutionContext context) {
        for (Selection<?> selection : context.getOperationDefinition().getSelectionSet().getSelections()) {
            if (!(selection instanceof Field field) || !field.getName().startsWith("__")) {
                return false;
            }
        }
        return true;
    }

    /** Counts the normalized operation, in which aliases and named fragments are expanded alike (ADR 0012, fact 8). */
    @Bean
    @Order(2)
    Instrumentation fieldCountLimit() {
        return new SimplePerformantInstrumentation() {
            @Override
            public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                    InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
                ExecutionContext context = parameters.getExecutionContext();
                try {
                    ExecutableNormalizedOperationFactory.createExecutableNormalizedOperation(
                            context.getGraphQLSchema(), context.getOperationDefinition(), context.getFragmentsByName(),
                            context.getCoercedVariables(), ExecutableNormalizedOperationFactory.Options.defaultOptions()
                                    .maxFieldsCount(MAX_FIELDS));
                } catch (AbortExecutionException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new AbortExecutionException("The query could not be measured.");
                }
                return SimpleInstrumentationContext.noOp();
            }
        };
    }

    @Bean
    @Order(3)
    Instrumentation costLimit() {
        return new MaxQueryComplexityInstrumentation(MAX_COST, COST);
    }

    /**
     * A field that takes a limit costs that limit, or its default page, times one plus what it selects; the watchlist
     * costs its size bound times one plus what it selects; every other field costs one plus what it selects. Other
     * lists without a limit are ones the contract bounds (a run's lists), so they are counted once. A limit below 1
     * is costed as 1, so no field can cost less than nothing and cancel the rest of the query; the data fetcher
     * refuses it anyway. Costs are worked out in long and each field's cost stops at COST_CEILING: the
     * parser admits at most 2,000 tokens, so at most 2,000 fields, and 2,000 times the ceiling stays far inside the
     * int sum graphql-java keeps, while every cost up to the ceiling is still reported exactly (ADR 0012).
     */
    static final FieldComplexityCalculator COST = (env, children) -> {
        boolean topLevel = "Query".equals(GraphQLTypeUtil.simplePrint(env.getParentType()));
        long rows;
        if (env.getFieldDefinition().getArgument("limit") != null) {
            Object limit = env.getArguments().get("limit");
            rows = limit instanceof Integer n ? Math.max(n, 1) : topLevel ? TOP_LEVEL_PAGE : NESTED_PAGE;
        } else if (topLevel && "watchlist".equals(env.getField().getName())) {
            rows = WATCHLIST_MAX;
        } else {
            rows = 1;
        }
        return (int) Math.min(rows * (1L + children), COST_CEILING);
    };
}
