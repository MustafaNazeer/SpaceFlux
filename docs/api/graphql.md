# GraphQL API

* **Status:** accepted, 2026-10-06.
* **Served by:** `query-api` at `POST /api/graphql`, the dashboard's read API. The schema is [`query-api/src/main/resources/graphql/schema.graphqls`](../../query-api/src/main/resources/graphql/schema.graphqls). Limits are decided in [ADR 0012](../adr/0012-graphql-introspection-and-limits.md).

GraphQL serves the same reads as the [REST API](rest.md), shaped by the client in one request: an object, its close approaches and alerts, and the space weather context. Writes stay on REST: acknowledging an alert, signing in and signing out have no GraphQL form, and the schema has no mutations.

## Conventions

* **Field names** are the REST and contract names, in `snake_case` ([ADR 0010](../adr/0010-query-api-stack.md), amendment), so one field reads the same in an event, a row, a REST response and a GraphQL response.
* **Same answers as REST.** Each field is built from the REST handler's own answer, so values, time formats, staleness and the anonymous view of an acknowledgement are identical. The contract objects REST returns as stored text (a run's summary, a close approach, an alert's event) are typed here from [the alerts schema](../../schemas/alerts/v1.schema.json), and their numbers are written by the API rather than kept in the spelling they were received with.
* **Null, not absent.** A nullable field that was asked for and has no value is `null`, as GraphQL requires. Where REST answers `404` (an alert, a catalog object, or a current screening run that is not stored), the field is `null` with no error.
* **Errors.** A request REST would refuse with `400` gets one GraphQL error whose `message` is the same text REST puts in `detail` and whose `extensions.classification` is `BAD_REQUEST`. Anything unexpected is `INTERNAL_ERROR` with a generic message; stack traces and SQL go only to the log.
* **One request per `POST`.** A JSON array of requests is refused. `GET` is not served.
* **Sign in and CSRF.** Every `POST` needs the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie, as on REST; without it the answer is `403`. The dashboard calls `GET /api/auth/session` once at startup to receive the cookie. Reads need no session; a request from the signed in operator also gets `principal` and `note` on an acknowledgement ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 5).

## Fields

| Field | Answers | REST equivalent |
|---|---|---|
| `space_weather_current` | G, R and S, always all three | section 1 |
| `space_weather_history(scale, satellite, from, to, limit, after)` | One scale over at most 7 days, oldest first, paged | section 2 |
| `screening_current` | The current run, its typed summary and its approaches, or `null` | section 3 |
| `alert(event_id)` | One stored event, typed by kind, with `received_at` and its acknowledgement, or `null` | section 4 |
| `alerts(limit, after)` | Recent alerts, newest first, paged | none |
| `catalog_object(norad_cat_id)` | One catalog row, or `null`; its `close_approaches(limit, after)` in either role, newest first | section 5 |
| `watchlist` | Every watchlist object with its catalog row | section 6 |

**Recent alerts** are every close approach, and every space weather event that enters, changes or leaves a level: its trigger is not `refresh`, and its state or its previous state is `level`, restatements included. Screening runs, refreshes, and changes between `none` and `no_data` are left out. The SQL and its index are in the [MySQL schema](../data/mysql-schema.md#queries-the-api-needs) (Q3 and Q6) and [indexes.md](../data/indexes.md).

**Paging** follows the REST rules: a list that can grow takes `limit` and `after`, and returns `next` until the last page. A top level list defaults to 50 items and allows at most 200; a list nested under another object defaults to 20 and allows at most 50. A `limit` out of range is an error, never quietly cut.

## Limits

Checked in this order, each refusal an error with no data:

| Limit | Value | Why |
|---|---|---|
| Request body | 64 KiB, answered `413` | Refused before the body is parsed; a declared length over the cap is refused without reading the body |
| Document size | 16,384 characters, 2,000 tokens, 10,000 whitespace tokens, rule depth 100 | Refused before validation, so a huge document costs almost nothing |
| Depth | 6 | Stops a client walking the graph; while introspection is on, a query that selects only `__schema`, `__type` and `__typename` is exempt and bounded by graphql-java's own introspection checks instead |
| Field count | 200, counted on the normalized operation | Aliases and named fragments count in full |
| Cost | 2,000, where a list with a `limit` costs that limit (or its default page) times what it selects, and `watchlist` costs its size bound of 10 times what it selects | Tracks rows read rather than fields named; a `limit` below 1 costs as 1 and no field costs more than 100,000, so no value can overflow the sum |
| Statement time | 3 seconds per SQL statement on the API's connection pool | What actually bounds a slow request; it applies to REST too |
| Request time | 5 seconds | A backstop only: it does not stop a data fetcher that blocks on the request thread, which every fetcher here does |

A cost limit alone is not enough: graphql-java's complexity calculator undercounts a named fragment spread under several aliases (ADR 0012, fact 8), so the field count stands beside it. `FragmentCountTest` pins both numbers, so an upgrade that changes either one fails the build.

**Introspection** is off unless `GRAPHQL_INTROSPECTION=true`, which only the local Compose stack sets, so the demo environment keeps the safe default. With it off, `__schema` and `__type` are refused and `__typename` still answers. The schema is public in this repository either way; the limits above are the real control.

## Tests

`GraphQlIntegrationTest` compares every field of the current space weather, the watchlist with its catalog rows, a catalog object, history items, the current screening run and one alert of each kind with the REST answer, value for value; it checks `null` where REST answers `404`, the BAD_REQUEST error text, the CSRF check, the anonymous view of an acknowledgement on `alert`, `screening_current` and both lists, and pages both lists, ties included. `GraphQlLimitsIntegrationTest` checks each limit just under and just over its value: depth plain, aliased and through inline and named fragments; field count aliased and through a named fragment; cost plain, through inline and named fragments, with huge and negative limits and with the watchlist bound; every parser limit; the operation picked by `operationName`; malformed requests, a batch, and the body cap by declared and by chunked length; and the statement timeout. `GraphQlTimeoutIntegrationTest` shows the request timeout does not stop a blocking fetcher and that a cancelled statement gets a generic error, and `IntrospectionOffIntegrationTest` checks the default setting.
