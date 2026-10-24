# GraphQL API

* **Status:** accepted, 2026-10-06.
* **Served by:** `query-api` at `POST /api/graphql`, the dashboard's read API, and the live alert subscription over WebSocket at `/api/graphql/ws`. The schema is [`query-api/src/main/resources/graphql/schema.graphqls`](../../query-api/src/main/resources/graphql/schema.graphqls). Limits are decided in [ADR 0012](../adr/0012-graphql-introspection-and-limits.md), and the subscription in [ADR 0014](../adr/0014-live-alert-subscription.md).

GraphQL serves the same reads as the [REST API](rest.md), shaped by the client in one request: an object, its close approaches and alerts, and the space weather context. Writes stay on REST: acknowledging an alert, signing in and signing out have no GraphQL form, and the schema has no mutations.

## Conventions

* **Field names** are the REST and contract names, in `snake_case` ([ADR 0010](../adr/0010-query-api-stack.md), amendment), so one field reads the same in an event, a row, a REST response and a GraphQL response.
* **Same answers as REST.** Each field is built from the REST handler's own answer, so values, time formats, staleness and the anonymous view of an acknowledgement are identical. The contract objects REST returns as stored text (a run's summary, a close approach, an alert's event) are typed here from [the alerts schema](../../schemas/alerts/v1.schema.json), and their numbers are written by the API rather than kept in the spelling they were received with.
* **Unsigned counts are `UInt32`.** `rules_version` (on `ScaleState`, `Alert` and `WatchlistObject`), a screening run's `approach_count`, the six `Coverage` counts and the seven `Omitted` counts are the scalar `UInt32`, a JSON integer from 0 to 4294967295. The contract gives them no upper bound and the database stores them as `INT UNSIGNED`, which holds up to 4294967295, while GraphQL's `Int` stops at 2147483647, so a valid stored value could not be written as an `Int`. A whole number written with a decimal point, such as `1.0`, which the contract's JSON Schema integer accepts, is written as the integer `1`; a fraction, a negative number, a value over the range or a string is refused, and a query literal of this type must be an integer literal.
* **Two counts stay `Int`.** `DifferingCopiesOverCap.records_not_listed` and `CatalogObject.rev_at_epoch` are GraphQL `Int`, which stops at 2147483647, although their contracts give them no upper bound. The risk engine stops its `records_not_listed` count at 2147483647 instead of wrapping, so it never writes a larger one, and no real orbit reaches a `rev_at_epoch` that large. A stored value past 2147483647 is not written: each such value gets one `INTERNAL_ERROR` error at its own path with the generic message, and the nearest field that may be `null` becomes `null`. For `rev_at_epoch` that is the field itself. `records_not_listed` and the list's items may not be `null`, so the whole `differing_copies_over_cap` list becomes `null`, once, with one error for each entry past the limit. The rest of the response still answers. A `UInt32` value past 4294967295, which the store refuses, would be answered the same way.
* **Null, not absent.** A nullable field that was asked for and has no value is `null`, as GraphQL requires. Where REST answers `404` (an alert, a catalog object, or a current screening run that is not stored), the field is `null` with no error.
* **Errors.** A request REST would refuse with `400` gets one GraphQL error whose `message` is the same text REST puts in `detail` and whose `extensions.classification` is `BAD_REQUEST`. Anything unexpected is `INTERNAL_ERROR` with a generic message, a stored value its GraphQL type cannot hold included; stack traces, SQL and the value's detail go only to the log.
* **One request per `POST`.** A JSON array of requests is refused. `GET` is not served.
* **Sign in and CSRF.** Every `POST` needs the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie, as on REST; without it the answer is `403`. The dashboard calls `GET /api/auth/session` once at startup to receive the cookie. Reads need no session; a request from the signed in operator also gets `principal` and `note` on an acknowledgement ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 5).

## Fields

| Field | Answers | REST equivalent |
|---|---|---|
| `space_weather_current` | G, R and S, always all three | section 1 |
| `space_weather_history(scale, satellite, from, to, limit, after)` | One scale over at most 7 days, oldest first, paged | section 2 |
| `screening_current` | The current run, its typed summary and its approaches, or `null` | section 3 |
| `alert(event_id)` | One stored event, typed by kind, with `received_at` and its acknowledgement, or `null` | section 4 |
| `alerts(limit, after, kind)` | Recent alerts, newest first, paged; `kind` keeps one kind | none |
| `catalog_object(norad_cat_id)` | One catalog row, or `null`; its `close_approaches(limit, after)` in either role, newest first | section 5 |
| `watchlist` | Every watchlist object with its catalog row | section 6 |
| `watchlist { passes }` | One watchlist object's passes over the next 24 hours, computed on request; `null` where REST leaves a field out. `passes` itself is `null` when its computation fails, with one `INTERNAL_ERROR` error at that field's path and a generic message, and the rest of `watchlist` still answers. The clock is read once per request, so every object in one request shares the same `window_start` | section 10 |

**Recent alerts** are every close approach, and every space weather event that enters, changes or leaves a level: its trigger is not `refresh`, and its state or its previous state is `level`, Kp revisions included. Screening runs, refreshes, restatements (which only ever restate a sample published as `none` as `no_data`), and changes between `none` and `no_data` are left out. The SQL and its index are in the [MySQL schema](../data/mysql-schema.md#queries-the-api-needs) (Q3 and Q6) and [indexes.md](../data/indexes.md).

**Filtering by kind.** `kind` is optional and is a `String`, checked against its values the way `space_weather_history` checks `scale`. Left out or `null`, the list is every kind, exactly as above. `close_approach` keeps only close approaches, and `space_weather_level` keeps only the space weather events the list shows (the same rule, so never a refresh). Any other value, the empty string and a different case included, is a `BAD_REQUEST` error with the text `kind is close_approach or space_weather_level.`, never the whole list. A `next` cursor pages only the list it was returned with: used with a different `kind`, or with `kind` added or left out, it is a `BAD_REQUEST` error with the text `after was returned for a different kind; ask for the first page again.` A client that changes the filter starts again from the first page.

**Paging** follows the REST rules: a list that can grow takes `limit` and `after`, and returns `next` until the last page. A top level list defaults to 50 items and allows at most 200; a list nested under another object defaults to 20 and allows at most 50. A `limit` out of range is an error, never quietly cut.

## Live alert subscription

```graphql
subscription { alert_stored { kind event_id produced_at received_at space_weather_level { scale state } close_approach { run_id miss_distance_m } } }
```

`alert_stored` sends each alert the recent alerts list would show (above), once, as soon as `query-api` has stored it. A notice is an `AlertNotice`: `kind`, `rules_version`, `event_id`, `produced_at`, the stored `received_at`, and exactly one of `space_weather_level` and `close_approach`, typed as on `Alert`; the other is `null`. It has no acknowledgement and no screening run, since a new alert has no acknowledgement yet; anything more is fetched with `alert(event_id)`. The field takes no arguments.

The `alerts` query stays the source of truth: a notice can be lost (a client that is not connected, a full queue, a restart between the store and the push), so the dashboard keeps polling the list once a minute, keys both by `event_id`, and ignores a notice for an alert it already shows. A notice is pushed only by the instance that stored the alert, which is complete while `query-api` runs as one replica.

A stored value its GraphQL type cannot hold is answered in a notice as in a query: the nearest field that may be `null` is `null`, with one generic `INTERNAL_ERROR` at the field's path. When that nearest field would be `alert_stored` itself, which may not be `null`, the subscription ends with a protocol `error` message, `Subscription error`, and the client subscribes again ([ADR 0012](../adr/0012-graphql-introspection-and-limits.md), amendment of 2026-10-24).

**Protocol.** GraphQL over WebSocket with the `graphql-transport-ws` subprotocol: the client sends `connection_init`, the server answers `connection_ack`, then the client sends one `subscribe`. The server sends a protocol `ping` every 30 seconds, which the client answers with `pong`. No SockJS or STOMP fallback exists.

**Rules for a connection.**

| Rule | Value | What happens |
|---|---|---|
| Origin | One configured dashboard origin, matched exactly (`SPACEFLUX_DASHBOARD_ORIGIN`, for example `http://localhost:4200` locally); no pattern or wildcard | Any other origin, `null`, more than one `Origin` header, or none at all: `403` before the upgrade. With no origin configured every handshake is refused, and the rest of the API serves normally. The HTTP endpoint still has no CORS configuration |
| Connections per client address | 4 | `429` with `Retry-After: 30`, before the upgrade |
| Connections across the service | 64 | `503` with `Retry-After: 30`, before the upgrade |
| Subscriptions per connection | 1 | A second `subscribe` gets a protocol `error` message, `One subscription per connection.`, and the first keeps running |
| Message | A JSON object whose `type` is `connection_init`, `ping`, `pong`, `subscribe` or `complete`, with an `id`, when it has one, of 1 to 64 ASCII letters, digits, hyphens or underscores, and a `payload` that is an object (required for `subscribe`, optional otherwise); a `subscribe` payload needs a `query` with text, and its `operationName` must be a string and its `variables` and `extensions` objects when present | Anything else, a number as `id` included: close `4400` `Invalid message` before the message is acted on, even before `connection_init`. A message with no `id` is handled as before |
| `connection_init` | within 5 seconds | Close `4408` |
| Silence from the client | 75 seconds without any message, a `pong` included | Close `1001` |
| A blocked send | 20 seconds | The connection is closed |
| Lifetime | 60 minutes | Close `1001`; the client reconnects, and passes the origin check and the caps again |
| Text message size | 8,192 characters | Close `1009` |
| Binary message | any | Close `1003` |
| Messages after `connection_init` | 20 in any 60 seconds | Close `1008` |
| Notices waiting for one client | 16 | Close `1008`, so a client that stops reading never slows the others; a client whose delivery fails is closed with `1008` too, so it reconnects |

A refusal before the upgrade has the API's problem body. A browser sees every refused handshake only as a failed connection, so the client reconnects with backoff and relies on the poll meanwhile. The 30 second ping keeps a quiet but healthy connection open through a proxy, and the client's `pong` is what keeps it inside the 75 second limit.

Behind the cloud ingress the client address is the forwarded one, trusted only from the ingress, the same rule as login throttling; until that is configured every client shares the ingress's address and the per address cap acts as a second service wide cap.

**One operation type per transport.** The WebSocket serves `subscription` operations only: a query sent there gets the error `Only subscriptions are served over WebSocket.` and nothing runs. The HTTP endpoint serves queries only: a subscription sent by `POST` gets the error `Subscriptions are served over WebSocket only.` with no data, and a `POST` that accepts `text/event-stream` is answered `406`, so no Server Sent Events stream exists.

**Anonymous.** Everything sent over the socket is in the anonymous form, whatever session the handshake carried, and `AlertNotice` has no field that differs for the operator, so signing out or a session expiring has nothing to take back from an open socket. The handshake never creates a session.

**Limits.** The document size, field count, depth and cost limits below hold for a subscription as for a query; the cost calculator treats `Subscription` as a root like `Query`. A text message of at most 8,192 characters is the tighter bound on a document sent over the socket.

## Limits

Checked in this order, each refusal an error with no data:

| Limit | Value | Why |
|---|---|---|
| Request body | 64 KiB, answered `413` | Refused before the body is parsed; a declared length over the cap is refused without reading the body |
| Document size | 16,384 characters, 2,000 tokens, 10,000 whitespace tokens, rule depth 100 | Refused before validation, so a huge document costs almost nothing |
| Depth | 6 | Stops a client walking the graph; while introspection is on, a query that selects only `__schema`, `__type` and `__typename` is exempt and bounded by graphql-java's own introspection checks instead |
| Field count | 200, counted on the normalized operation | Aliases and named fragments count in full |
| Cost | 2,000, where a list with a `limit` costs that limit (or its default page) times what it selects, `watchlist` costs its size bound of 10 times what it selects, and a watchlist object's `passes` cost 100 plus what they select | Tracks rows read rather than fields named; a pass computation propagates a day of SGP4 states and takes a fraction of a second, so it is weighted as about 100 rows, and the whole watchlist's passes (1,470 with every field) fit once but not twice. A `limit` below 1 costs as 1 and no field costs more than 100,000, so no value can overflow the sum |
| Passes | At most one `passes` selection per request, counted on the normalized operation | The cost calculator undercounts a reused named fragment, so without this count a fragment could ask for the watchlist's passes many times at the cost of once |
| Statement time | 3 seconds per SQL statement on the API's connection pool | What actually bounds a slow request; it applies to REST too |
| Request time | 5 seconds | A backstop only: it does not stop a data fetcher that blocks on the request thread, which every fetcher here does, and it does not end a subscription, whose bounds are the connection rules above |

A cost limit alone is not enough: graphql-java's complexity calculator undercounts a named fragment spread under several aliases (ADR 0012, fact 8), so the field count stands beside it. `FragmentCountTest` pins both numbers, so an upgrade that changes either one fails the build.

**Introspection** is off unless `GRAPHQL_INTROSPECTION=true`, which only the local Compose stack sets, so the demo environment keeps the safe default. With it off, `__schema` and `__type` are refused and `__typename` still answers. The schema is public in this repository either way; the limits above are the real control.

## Tests

`GraphQlIntegrationTest` compares every field of the current space weather, the watchlist with its catalog rows, a catalog object, history items, the current screening run and one alert of each kind with the REST answer, value for value; it checks `null` where REST answers `404`, the BAD_REQUEST error text, the CSRF check, the anonymous view of an acknowledgement on `alert`, `screening_current` and both lists, and pages both lists, ties included; it filters the recent alerts by each kind and pages within it, and refuses an unknown kind and a cursor used under another filter. `GraphQlLimitsIntegrationTest` checks each limit just under and just over its value: depth plain, aliased and through inline and named fragments; field count aliased and through a named fragment; cost plain, through inline and named fragments, with huge and negative limits and with the watchlist bound; every parser limit; the operation picked by `operationName`; malformed requests, a batch, and the body cap by declared and by chunked length; and the statement timeout; and passes asked for twice, by alias and through named fragments. `PassesCostTest` pins what passes cost, `PassesApiIntegrationTest` compares every `passes` field with the REST answer, with and without computed passes, and `PassesFailureIntegrationTest` checks that a failed computation nulls only that object's `passes`, while another object's `passes` still answers, with one `INTERNAL_ERROR` at that path and no detail of the failure. `AlertSocketIntegrationTest` checks each connection rule above over a real socket: every origin variant, the caps and their `Retry-After`, a thousand connections opened and closed in five ways (closed by the client before and after `connection_init`, closed by the init timeout, refused for their origin, refused by the upgrade itself) leaving no slot held, closed connections leaving no task on the timer, a second subscription, the message size, binary and message count limits, one operation type per transport, the field count and parser limits for a subscription in aliased and named fragment forms, a socket opened with the operator's session served anonymously, also after that session is logged out, expires or is replaced by a newer login, a mutation document refused with nothing run, and that a notice carries the stored `received_at` and `null` for the other kind, arrives after the request timeout has passed, and is sent only for an alert the list shows. `AlertSocketTimeIntegrationTest` checks the time limits with lowered values; `AlertSocketSlowReaderIntegrationTest` that a client that stops reading is closed by the send timeout without holding up one that reads; `AlertSocketCapIntegrationTest` the service wide cap; `AlertSocketNoOriginIntegrationTest` the refusal with no origin configured; `AlertNoticesTest` the per client buffer, that a subscriber whose delivery fails is closed once and never keeps a notice from the others, and that storing an alert never waits on the push; `AlertsListenerTest` that only a stored alert is pushed and that a failed push never fails its record; and `AlertSubscriptionKafkaIntegrationTest` the whole path from the `alerts` topic: a repeat, a dead letter and a screening run send nothing, and two close approaches arrive once each, in order. `IntRangeGraphQlIntegrationTest` stores a `records_not_listed` past `Int` and past a Java `long`, a `rev_at_epoch` past `Int` and a `UInt32` count past its range, and checks that each gives one generic `INTERNAL_ERROR` at its path, nulls only the nearest field that may be `null`, and names no type or value, on `alert`, `catalog_object` and `screening_current`, that two entries past `Int` in one list each get their own error, that the log names the path but not the value, while a `BAD_REQUEST` and a validation error stay as they were. `GraphQlTimeoutIntegrationTest` shows the request timeout does not stop a blocking fetcher and that a cancelled statement gets a generic error, and `IntrospectionOffIntegrationTest` checks the default setting.
