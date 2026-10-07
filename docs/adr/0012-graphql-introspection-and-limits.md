# ADR 0012: GraphQL introspection and query limits

* **Status:** accepted
* **Date:** 2026-10-06

## Context

The dashboard reads `query-api` through GraphQL: one object, then its passes, alerts and space weather context, in one request. GraphQL lets the client shape the query, so the client also chooses how much work one request costs. The [threat model](../security/threat-model.md) lists two threats this ADR answers:

| ID | Threat | Mitigation asked for |
|---|---|---|
| T5.1 | Deeply nested or aliased queries exhaust CPU or database connections | Query depth and complexity limits, result page size caps, request timeouts |
| T5.2 | Schema introspection helps an attacker map the API | A recorded decision on introspection in the demo environment; the limits in T5.1 are the real control |

T5.3, the cap on live alert subscriptions, is left to the decision that adds the subscription. Nothing below decides it.

Facts that shape the choice:

* **Stack.** `query-api` is Spring Boot 4.1.1 on Spring MVC and Tomcat with blocking JDBC ([ADR 0010](0010-query-api-stack.md)). The `spring-boot-dependencies` 4.1.1 POM manages Spring for GraphQL 2.0.5 and graphql-java 25.0.
* **Endpoint.** GraphQL is served at `/api/graphql` (`spring.graphql.http.path`), under the same `/api` prefix the ingress routes on. Spring Security's CSRF protection stays on for every `POST`, this one included ([ADR 0009](0009-alert-acknowledgement-auth.md)). The client is apollo-angular over Angular's `HttpClient`, which sends the `X-XSRF-TOKEN` header on same origin `POST` requests.
* **The schema is public anyway.** The SDL files live in the repository under `query-api`, so turning introspection off hides nothing from anyone who reads the code. It only removes the convenience of asking a running server.
* **Load profile.** A handful of dashboard viewers while the demo environment is up, one replica, one Tomcat request thread per request, and a small API connection pool. One expensive query holds a thread and a connection for as long as it runs.

### What I verified about the framework

Every class and default below was read in the sources at the exact tags (the published source jars, which I checked match the tags), and the behaviors marked "checked by running" were confirmed with a small program against the graphql-java 25.0 and reactor-core 3.8.7 jars. Those checks become tests in `query-api` before code relies on them.

1. **The introspection switch.** `spring.graphql.schema.introspection.enabled`, "Whether field introspection should be enabled at the schema level", default `true` ([Boot properties appendix](https://docs.spring.io/spring-boot/appendix/application-properties/index.html#application-properties.web.spring.graphql.schema.introspection.enabled); [Boot GraphQL reference](https://docs.spring.io/spring-boot/reference/web/spring-graphql.html), which says introspection is allowed by default because tools such as GraphiQL need it). When it is `false`, Boot calls graphql-java's `Introspection.enabledJvmWide(false)` while building the `GraphQlSource` ([GraphQlAutoConfiguration at v4.1.1](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-graphql/src/main/java/org/springframework/boot/graphql/autoconfigure/GraphQlAutoConfiguration.java)). That flag is a static `AtomicBoolean` for the whole JVM, and Boot only ever sets it to `false`, never back to `true` ([Introspection at v25.0](https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/introspection/Introspection.java)). In a test JVM that starts contexts with both settings, the first context with introspection off turns it off for every later one.
2. **What "off" refuses.** Only the `__schema` and `__type` root fields; the request gets the error "Introspection has been disabled for this request". `__typename` still answers (checked by running). The Apollo cache uses `__typename` to identify objects, so turning introspection off leaves it working; whether apollo-angular needs anything else from introspection (for example fragments on interfaces or unions) is checked once its version is pinned. A single request can also be refused by putting `INTROSPECTION_DISABLED` in its `GraphQLContext`.
3. **GraphiQL and the schema printer are already off.** `spring.graphql.graphiql.enabled` and `spring.graphql.schema.printer.enabled` both default to `false` (configuration metadata in `spring-boot-graphql` 4.1.1). A `GET` to the endpoint is answered with `405`; queries are `POST` only ([GraphQlWebMvcAutoConfiguration at v4.1.1](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-graphql/src/main/java/org/springframework/boot/graphql/autoconfigure/servlet/GraphQlWebMvcAutoConfiguration.java)).
4. **Good faith introspection.** While introspection is on, graphql-java checks every introspection request: at most one `__schema` and one `__type`, at most one of each of `__Type.fields`, `inputFields`, `interfaces` and `possibleTypes`, at most 500 fields and at most 20 levels deep (`GOOD_FAITH_MAX_FIELDS_COUNT`, `GOOD_FAITH_MAX_DEPTH_COUNT`). It is on by default and can be switched off JVM wide or per request ([GoodFaithIntrospection at v25.0](https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/introspection/GoodFaithIntrospection.java)). Two `__type` fields under aliases are refused (checked by running). Its coordinates name the root type `Query`, so the schema's query root keeps that name.
5. **Parser limits.** Operations are parsed with at most 1,048,576 characters (`MAX_QUERY_CHARACTERS`), 15,000 grammar tokens (`MAX_QUERY_TOKENS`), 200,000 whitespace tokens (`MAX_WHITESPACE_TOKENS`) and 500 levels of grammar rules (`MAX_RULE_DEPTH`) by default. They can be changed JVM wide with `ParserOptions.setDefaultOperationParserOptions`, or for one request by putting a `ParserOptions` under the key `ParserOptions.class` in its `GraphQLContext`, which `ParseAndValidate` reads first ([ParserOptions at v25.0](https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/parser/ParserOptions.java)). Validation stops after 100 errors (`Validator.MAX_VALIDATION_ERRORS`).
6. **Depth limit.** `MaxQueryDepthInstrumentation(int maxDepth)` measures the selected operation after validation, in `beginExecuteOperation`, and aborts with "maximum query depth exceeded N > M" ([source at v25.0](https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/analysis/MaxQueryDepthInstrumentation.java)). A query that selects only root fields has depth 1; `{ obj { parent { id } } }` has depth 3. Fragments are expanded before measuring (checked by running).
7. **Complexity limit.** `MaxQueryComplexityInstrumentation(int maxComplexity)` sums a `FieldComplexityCalculator` over the selected operation, by default `1 + childComplexity` per field, with `__typename` counted as 0 ([source at v25.0](https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/analysis/MaxQueryComplexityInstrumentation.java), [QueryComplexityCalculator at v25.0](https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/analysis/QueryComplexityCalculator.java)). The default does not look at arguments, so `objs(limit: 200) { id }` costs 2, the same as one object. A custom calculator receives the field's arguments and can weigh a list by its `limit`. graphql-java's own [instrumentation guide](https://www.graphql-java.com/documentation/instrumentation) describes both instrumentations and `ChainedInstrumentation`.
8. **Aliases count; reused named fragments do not, fully.** Checked by running with the default calculator:
   * Three aliases of `obj { id }` cost 6, so aliases are counted as separate fields, and a limit of 5 refused that query.
   * The same field repeated without an alias is counted every time (`{ obj { id id id id } }` costs 5), although execution merges it into one.
   * **A named fragment spread under several aliases is undercounted.** Four aliased `obj` fields that each spread a fragment selecting `id name parent { id name }` cost 9, while the same query written out inline costs 24. An attacker can therefore pass a complexity limit by moving the repeated selection into a named fragment. Inline fragments are counted correctly. I have not found a graphql-java issue that describes this, and have not checked whether a later version behaves differently.
   * graphql-java's normalized operation (`ExecutableNormalizedOperationFactory`) counts both versions of that query as 24 fields, merges the repeated unaliased `id`, and takes a `maxFieldsCount` option that aborts with "Maximum field count exceeded" ([source at v25.0](https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/normalized/ExecutableNormalizedOperationFactory.java)). Good faith introspection uses the same factory. So a field count taken from the normalized operation is the reliable measure of size.
9. **Several operations and batches.** A document with several operations runs only the one named by `operationName`, and graphql-java refuses the document without one; depth and complexity are measured on that operation only (checked by running). The Spring MVC handler reads one request object per HTTP request (`SerializableGraphQlRequest` in [AbstractGraphQlHttpHandler at v2.0.5](https://github.com/spring-projects/spring-graphql/blob/v2.0.5/spring-graphql/src/main/java/org/springframework/graphql/server/webmvc/AbstractGraphQlHttpHandler.java)); I found no support for a JSON array of requests in Spring for GraphQL 2.0.5. A test that sends an array confirms it is refused.
10. **Registering limits with Boot.** Boot passes every `Instrumentation` bean, in order, to the `GraphQlSource` builder ([GraphQlAutoConfiguration at v4.1.1](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-graphql/src/main/java/org/springframework/boot/graphql/autoconfigure/GraphQlAutoConfiguration.java)), which wraps them in one `ChainedInstrumentation`; and every `WebGraphQlInterceptor` bean, in order, to the `WebGraphQlHandler` ([GraphQlWebMvcAutoConfiguration at v4.1.1](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-graphql/src/main/java/org/springframework/boot/graphql/autoconfigure/servlet/GraphQlWebMvcAutoConfiguration.java); [Spring for GraphQL reference, GraphQlSource](https://docs.spring.io/spring-graphql/reference/request-execution.html#execution.graphqlsource)). So a `@Bean MaxQueryDepthInstrumentation` is enough to apply it.
11. **The request timeout does not bound blocking work on this stack.** Spring for GraphQL provides `TimeoutWebGraphQlInterceptor(Duration)`, registered as a bean, which answers `408` by default and sends a cancel signal so reactive data fetchers stop ([reference, request timeout](https://docs.spring.io/spring-graphql/reference/request-execution.html#execution.timeout); [source at v2.0.5](https://github.com/spring-projects/spring-graphql/blob/v2.0.5/spring-graphql/src/main/java/org/springframework/graphql/server/TimeoutWebGraphQlInterceptor.java)). It is Reactor's `timeout` operator around execution. Execution is started with `Mono.fromFuture(graphQl.executeAsync(...))` inside `Mono.deferContextual` ([DefaultExecutionGraphQlService at v2.0.5](https://github.com/spring-projects/spring-graphql/blob/v2.0.5/spring-graphql/src/main/java/org/springframework/graphql/execution/DefaultExecutionGraphQlService.java)), and a blocking controller method runs on the calling thread unless an executor is set that does not prefer short lived tasks ([AnnotatedControllerDetectionSupport at v2.0.5](https://github.com/spring-projects/spring-graphql/blob/v2.0.5/spring-graphql/src/main/java/org/springframework/graphql/data/method/annotation/support/AnnotatedControllerDetectionSupport.java)). Boot hands it the `applicationTaskExecutor`, a `ThreadPoolTaskExecutor` on platform threads, whose `prefersShortLivedTasks()` returns `true` (Spring Framework 7.0.9). So on `query-api` as configured today every data fetcher runs synchronously on the Tomcat thread. A reduced copy of that operator chain, with a 500 ms timeout around a data fetcher that blocks for 2 seconds on the calling thread, completed normally after about 2.1 seconds with no timeout (checked by running). The interceptor still matters if data fetchers become asynchronous (virtual threads, which make Boot's executor one that runs blocking methods asynchronously, or `Callable` returns), but even then the JDBC call in flight runs until it returns. What actually bounds a slow query is a JDBC query timeout: `JdbcClient.create(JdbcOperations)` accepts a `JdbcTemplate`, whose `setQueryTimeout(int)` applies to every statement it runs (Spring Framework 7.0.9).

## Decision

**Decided on 2026-10-06:**

* **Introspection, option A:** on locally, off in the demo environment. While introspection is on, an operation that selects only `__schema`, `__type` and `__typename` skips the depth check; good faith introspection still bounds it. Every other operation, and every operation in the demo environment, gets the depth limit.
* **Every proposal in sections 2 to 6 is accepted as a starting value:** depth 6, normalized field count 200, list weighted cost 2,000, page sizes as in section 4, a 3 second JDBC query timeout on the API pool (REST included), the 5 second request timeout as a backstop, and the parser limits in section 6. They are reset from the measured size of the dashboard's real queries once the schema exists, and any change is recorded here.
* **The behaviors this ADR relies on become `query-api` tests before code relies on them:** the reused named fragment undercount, the request timeout not stopping a blocking data fetcher, the JVM wide introspection switch, and alias counting.

The options as they were weighed follow.

### 1. Introspection

| Option | Local | Demo environment | For | Against |
|---|---|---|---|---|
| A | on | off | Local tools can introspect; the public server answers nothing it does not need to | Two settings to keep straight; a test suite that mixes both must run them in separate JVMs (fact 1) |
| B | off | off | One setting everywhere, matching what ships; client code generation reads the SDL files in the repository instead of a server | No live introspection for local tools |
| C | on | on | Simplest; the schema is public anyway, good faith checks bound introspection cost, and the T5.1 limits are the real control | Tells anyone probing the demo the full shape of the API without opening the repository; and with the depth limit proposed below the standard introspection query is refused anyway (fact on depth, below) |

Notes for the ruling:
* With introspection on, the standard introspection query graphql-java ships (`IntrospectionQuery.INTROSPECTION_QUERY`) is 13 levels deep (checked by running). Any depth limit under 13 refuses it unless introspection requests are exempted from the depth check, so options A and C need either a local depth limit of at least 13 or an exemption written in the service.
* Whatever the option, `SEC-API-05` checks that the demo environment's setting matches the ruling, with a test that `__schema` and `__type` are refused there and `__typename` still answers.

### 2. Depth

**Proposal: 6.** The deepest dashboard query described so far is root, object, its alerts, an alert's acknowledgements, and their fields, about 4 or 5 levels. 6 leaves one level of margin. If the schema has fields that point back up the graph (an alert's object, an object's alerts), a depth limit is what stops an attacker walking that cycle, so the schema should also avoid back references the dashboard does not use.

### 3. Size and complexity

**Proposal: two checks, both in the service.**

* **A field count on the normalized operation, proposed at 200**, written as a small `Instrumentation` that builds the normalized operation with `maxFieldsCount`. This is what stops the reused fragment undercount (fact 8), and it counts aliases. 200 is about twice the largest dashboard query I expect; it is reset to twice the measured largest query once the queries exist.
* **A cost limit with a custom `FieldComplexityCalculator`, proposed at 2,000**, in which a list field costs its effective `limit` times the cost of what it selects, so that the number tracks rows read rather than fields named. With the page sizes below, one full top level page of 200 items with a few scalar fields stays under it, and a page of objects that each open a nested list of 50 does not. Because the default calculator undercounts reused fragments, the calculator's tests include the fragment case, and the normalized field count above stays as the backstop.

The default `MaxQueryComplexityInstrumentation` calculator alone is rejected: it ignores page size and can be bypassed with named fragments.

### 4. Page sizes

**Proposal:** every list field takes a `limit` and is paged by key, as the REST API is ([ADR 0010](0010-query-api-stack.md), decision 6). Top level lists default to 50 and allow at most 200, the same as REST. Lists nested under another object default to 20 and allow at most 50. A `limit` out of range is a GraphQL error, never silently clamped, and is enforced in the data fetcher (`SEC-API-04`), not only in the cost calculator.

### 5. Timeouts

**Proposal:**
* **A JDBC query timeout of 3 seconds** on the API pool's `JdbcTemplate`, so no single statement holds a connection longer. Every query in the schema's list is index backed and returns in milliseconds on demo data, so 3 seconds only ever fires on a fault or an attack. It applies to the REST handlers on the same pool too, which I consider correct.
* **`TimeoutWebGraphQlInterceptor` at 5 seconds**, registered as a bean, kept as a backstop for any data fetcher that becomes asynchronous. It is documented as not bounding the synchronous fetchers the service has today (fact 11). Switching the service to virtual threads to make it effective is a separate, service wide decision and is not proposed here.

### 6. Parser limits

**Proposal:** set lower operation parser limits per request from a `WebGraphQlInterceptor`, through `ParserOptions` in the `GraphQLContext`: at most 16,384 characters, 2,000 tokens, 10,000 whitespace tokens, and a rule depth of 100. The dashboard's queries are a few hundred tokens; the defaults (fact 5) allow a document of 1 MiB to be parsed before any limit above runs, because depth and cost are only measured after parsing and validation. A per request option is preferred over the JVM wide default so the limits sit next to the rest of the GraphQL configuration and tests can see them.

## Hardening checklist additions

If the options are ruled, the `query-api` section of the [hardening checklist](../security/hardening-checklist.md) is updated to name the configured limits and the test for each.

## Consequences

* The security filter chain gains `POST /api/graphql` for anonymous viewers, behind the same CSRF check as every other `POST`. GraphQL then reaches data the REST endpoints already serve, so the same rules apply to it: the anonymous view of an acknowledgement carries only `action` and `acted_at` ([ADR 0009](0009-alert-acknowledgement-auth.md), decision 5), with a test on the GraphQL path as well as the REST one.
* Limits are checked after parsing and validation, so parser limits and the rate limit (`SEC-API-09`) remain the controls for cost spent before execution.
* A request refused by a limit gets a GraphQL error with no data. Spring for GraphQL answers that with `400` when the response media type is `application/graphql-response+json` and with `200` for `application/json` (`GraphQlHttpHandler.selectResponseStatus` at v2.0.5). Its body must stay as generic as the REST problem bodies (T5.5), with a test.
* Each limit needs a test that sends a query just over it and one just under it, including the aliased and the reused fragment forms.
* Upgrading graphql-java means running those tests again, since the fragment behavior in fact 8 may change.

## Amendment, 2026-10-06: after review of the first implementation

Four changes, each with a test in `query-api`:

* **The cost cannot be cancelled.** graphql-java sums field costs as `int` with no overflow check, so a `limit` of 1,073,741,824 under a nullable field made a negative cost that cancelled the rest of the query, and a negative `limit` did the same directly. The calculator now costs a `limit` below 1 as 1, works in `long`, and stops each field's cost at 100,000. The parser admits at most 2,000 tokens, so at most 2,000 fields, and 2,000 times that ceiling stays far inside an `int`, while every cost up to the ceiling is still reported exactly. A `limit` out of range is still refused by the data fetcher.
* **The watchlist is costed at a size bound of 10.** It has no `limit`, and counting it once let `watchlist { catalog { close_approaches(limit: 50) } }` read 51 rows per watchlist object while costing as if there were one. A test fails if the seeded watchlist ever grows past the bound.
* **Request bodies are capped at 64 KiB.** Tomcat's `maxPostSize` covers only form and multipart parameters, and neither Spring for GraphQL nor Jackson's defaults bound a JSON body, so a 90 MiB `variables` value was accepted. A filter on `POST /api/graphql` answers `413` when the declared length is over the cap, without waiting for the body, and reads a chunked body only up to one byte over the cap before refusing it. The dashboard's queries are a few KiB.
* **Introspection defaults to off.** The service reads `GRAPHQL_INTROSPECTION` and defaults to `false`; only the local Compose stack sets it to `true`. An environment that forgets the setting now gets the safe value, and the depth exemption, which follows the same setting, goes with it.
