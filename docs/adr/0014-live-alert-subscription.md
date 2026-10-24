# ADR 0014: Live alert subscription over WebSocket

* **Status:** accepted
* **Date:** 2026-10-11

## Context

The dashboard polls the recent alerts list once a minute. A new close approach or space weather level change can therefore sit unseen for up to a minute. I want a new alert to appear as soon as `query-api` stores it, through a GraphQL `Subscription`, with the minute poll kept as the fallback.

The [threat model](../security/threat-model.md) left one threat to this decision:

| ID | Threat | Mitigation asked for |
|---|---|---|
| T5.3 | The live alert subscription is abused to hold many open connections | Cap concurrent subscriptions per client, idle timeouts, origin check on the WebSocket handshake |

[ADR 0012](0012-graphql-introspection-and-limits.md) set the GraphQL limits for HTTP queries and explicitly did not decide T5.3.

### What I verified about the framework

Every fact below was read in the sources at the exact tags `query-api` resolves (Spring Boot 4.1.1, Spring for GraphQL 2.0.5, Spring Framework 7.0.9, Spring Security 7.1.1, graphql-java 25.0, Reactor 3.8.7, Tomcat 11.0.26), or in the jars themselves. Facts marked **(test)** were read but not yet run; each becomes a `query-api` test before code relies on it.

1. **The endpoint needs a new dependency.** Boot's servlet GraphQL WebSocket configuration is active only when `spring.graphql.websocket.path` is set and `org.springframework.web.socket.WebSocketHandler` is on the classpath ([GraphQlWebMvcAutoConfiguration at v4.1.1](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-graphql/src/main/java/org/springframework/boot/graphql/autoconfigure/servlet/GraphQlWebMvcAutoConfiguration.java)). That class lives in `spring-websocket`, which `query-api` does not have today. Tomcat's own WebSocket server and Reactor are already present.
2. **Boot's properties.** `spring.graphql.websocket.path` (no default; endpoint off until set), `spring.graphql.websocket.connection-init-timeout` (default 60 s), `spring.graphql.websocket.keep-alive` (no default, so no server pings). Boot has no property for a connection cap, an idle timeout or a message size.
3. **The handler runs any operation type.** `GraphQlWebSocketHandler` executes whatever a `subscribe` message carries; a query or mutation is answered with one `next` and `complete`. It sets no limit on subscriptions per connection. Once the endpoint exists, the socket is a second query endpoint, with no body cap, no CSRF check and no per request rate limit at the ingress ([GraphQlWebSocketHandler at v2.0.5](https://github.com/spring-projects/spring-graphql/blob/v2.0.5/spring-graphql/src/main/java/org/springframework/graphql/server/webmvc/GraphQlWebSocketHandler.java)).
4. **A second subscription transport appears by itself.** Boot always routes `POST` to the GraphQL path with `Accept: text/event-stream` to `GraphQlSseHandler`. As soon as the schema has a `Subscription` type, that is a long lived HTTP stream that no WebSocket control sees.
5. **The security context outlives the session.** At the handshake the handler captures a snapshot of every registered thread local and restores it for each later message on that socket; Spring Security 7.1.1 registers its security context accessor for this. A socket opened while I am signed in would keep my view (an acknowledgement's `note` and `principal`, [ADR 0009](0009-alert-acknowledgement-auth.md) decision 5) after sign out, after the session expires, or after a newer login replaces it.
6. **The default origin check is weak.** With no `spring.graphql.cors.*` property, the handler checks the handshake for same origin only, and `WebUtils.isSameOrigin` returns true when the `Origin` header is missing. When it is present, "same origin" is inferred from the scheme, host and port the container believes it serves, which behind a TLS terminating ingress depends on forwarded header handling. Setting `spring.graphql.cors.allowed-origins` would also add CORS to the HTTP GraphQL path, which the API deliberately does not have (`SEC-API-07`).
7. **Servlet side controls do not see frames.** The handshake is one `GET`, so the existing "every `GET` is public" rule admits it, CSRF does not check it, and the 64 KiB body cap (a filter on `POST`) never sees a frame. Tomcat's default text message buffer is 8,192 characters; a larger message is closed with `1009` **(test)**.
8. **What still applies over the socket.** The parser limits are a `WebGraphQlInterceptor` and the depth, field count, cost and `passes` rules are `Instrumentation` beans; both are shared by the HTTP and WebSocket handlers. Whether graphql-java runs the instrumentation hook the limits use for subscription operations **(test)**. The cost calculator treats only a `Query` parent as top level. The 5 second request timeout bounds only the creation of the subscription's publisher, not the stream **(test)**.
9. **Tomcat's idle timeout cannot catch a silent client.** Its session idle timeout fires only when both reads and writes are idle, so the server's own keep alive pings keep a client that never answers open forever. The per session user property `org.apache.tomcat.websocket.READ_IDLE_TIMEOUT_MS` closes on read idleness alone, with `1001`.
10. **The JSON writer.** Boot gives the WebSocket handler the application's JSON mapper, which omits absent values. `query-api` already replaced the HTTP handler's writer because a nullable field that was asked for must come back as `null`; the WebSocket handler would not get that fix. Boot's handler bean is replaceable (`@ConditionalOnMissingBean`), so the service can supply its own.
11. **How `query-api` learns an alert was stored.** `AlertsListener` reads `alerts` on one thread per instance, and `AlertsProcessor` returns `Stored`, `Repeat` or `DeadLetter`. `Stored` is returned only after the insert's transaction committed; a redelivered record becomes `Repeat`, and a database failure throws before any outcome. `received_at` is assigned by the database. The recent alerts list shows close approaches and space weather events that enter, change or leave a level (the generated `listed` column), never screening runs.

### Load profile

The same as ADR 0012: one replica, a handful of viewers while the demo environment is up. Alerts are rare, minutes to days apart.

## Decision

**Decided on 2026-10-11.** Each decision below has a test in `query-api` (or in the dashboard for client behavior).

### 1. Transport

* **GraphQL over WebSocket**, protocol `graphql-transport-ws`, at **`/api/graphql/ws`**, a path of its own so its handshake checks never overlap the HTTP endpoint's.
* **Dependency: `spring-boot-starter-websocket`**, at the version Spring Boot manages. No SockJS and no STOMP are configured. The resolved `spring-websocket` version is checked against OSV when the dependency is added.
* **One operation type per transport.** The WebSocket runs `subscription` operations only; a query sent there gets an error with fixed text and nothing executes; the schema defines no mutations, so a mutation document is refused by validation and nothing executes either. The HTTP endpoint runs queries only; a subscription sent by `POST` is refused before execution.
* **The Server Sent Events route is switched off**, so no long lived HTTP stream exists outside the WebSocket's caps.
* **The service supplies its own `GraphQlWebSocketHandler` bean**, which carries the same JSON writer as HTTP (fact 10), the handshake checks below, and the close bookkeeping for the caps.

### 2. Fan out: in process, after the store commits

When `AlertsListener` gets `Stored`, it hands the `event_id` to an in memory hub. The hub reads the row back through the API pool (so `received_at` is the stored value), drops it unless the recent list would show it, and emits it to local subscribers without blocking the consumer thread. `Repeat`, `DeadLetter` and a thrown failure publish nothing, so a subscriber only ever sees alerts that are stored, each once. A failure in the hub never reaches the listener.

The hub queues at most 1,024 stored `event_id`s waiting to be read back; past that one is dropped and logged, and the poll shows it. When nobody is subscribed, the hub reads nothing.

This is complete because `query-api` runs as one replica ([ADR 0010](0010-query-api-stack.md) requires shared session and throttle storage before a second one). A crash between commit and publish loses only the push; the poll shows the alert within a minute. **If `query-api` ever runs more than one replica**, each subscriber would see only the alerts its own replica stored, and this decision is revisited: polling MySQL with a trailing window is the simplest complete replacement.

Options weighed:

| Option | With N replicas | Only stored alerts, once | Cost |
|---|---|---|---|
| In process after commit (chosen) | Each subscriber sees about 1/N of alerts; the poll fills the rest | Free, from the `Stored` outcome | Smallest; one indexed read per alert |
| A second Kafka listener per instance, unique group | Complete | The record often arrives before the storing replica commits, so it must wait for the row and match its partition and offset; a dead lettered record times out | A second consumer per replica, timing dependent tests |
| Poll MySQL every few seconds | Complete | By construction, but a cursor on `alert_seq` can skip a row committed out of order, so it needs a trailing window and a recent `event_id` set | Latency of the poll interval; a query per interval per replica |

### 3. Payload: a lean notice

```graphql
type Subscription {
  "Each alert the recent alerts list would show, once, as it is stored. The alerts query stays the source of truth."
  alert_stored: AlertNotice!
}

type AlertNotice {
  kind: String!
  rules_version: UInt32!
  event_id: String!
  produced_at: String!
  received_at: String!
  space_weather_level: SpaceWeatherLevel
  close_approach: CloseApproach
}
```

* `SpaceWeatherLevel` and `CloseApproach` are the existing types, built from the stored row with no further reads.
* **No `acknowledgement` and no `screening_run`.** A new alert cannot have an acknowledgement yet, and a screening run's lists can approach 900,000 bytes, which fanned out to every subscriber would multiply that cost. The dashboard fetches anything heavier through `alert(event_id:)`, which has every HTTP control.
* **The field takes no arguments**, so no list or `limit` is reachable from the subscription. The cost calculator names the `Subscription` root explicitly.
* **The dashboard dedupes on `event_id`**: a notice for an alert already shown is ignored and not announced, and each poll result replaces the list while keeping one entry per `event_id`. The poll's order is authoritative.

### 4. Everything on the socket is anonymous

Every operation over the WebSocket is served in the anonymous form whatever session the handshake carried, and the payload type has no operator only field at all, so fact 5 has nothing to leak. The handshake never creates an HTTP session. Anonymous viewers may subscribe, as they may read everything else. Sign in, sign out and session expiry therefore need no server side socket teardown.

### 5. Origin check

* The handshake is accepted only from **one configured dashboard origin, matched exactly**, with no pattern or wildcard. With no origin configured, every handshake is refused and the rest of the API serves normally.
* A different origin, `null`, and a **missing `Origin` header get `403`** without an upgrade. Every browser sends `Origin` on a WebSocket handshake; a script that wants alerts can use the HTTP API, which has its own limits.
* The allowlist is applied by the WebSocket handler alone, never through `spring.graphql.cors.*`, so the HTTP endpoint keeps no CORS configuration.
* CSRF does not apply to the handshake. That is acceptable only together with decisions 1 (subscriptions only) and 4 (anonymous only): forging a handshake then gains nothing a viewer cannot already read.

### 6. Caps

| Limit | Value | When exceeded |
|---|---|---|
| Open connections per client address | 4 | `429` with `Retry-After`, before the upgrade |
| Open connections across the service | 64 | `503` with `Retry-After`, before the upgrade |
| Active subscriptions per connection | 1 | An error for the second operation; the first keeps running |

* A connection holds its slot from the handshake on, and releases it on every close path, including a handshake refused after the slot was reserved.
* Both refusals carry `Retry-After: 30`.
* The client is identified by address only, never by session, because viewers have no session. Locally that is the socket address. Behind the cloud ingress it is the forwarded address trusted only from the ingress, the same rule login throttling needs; until that is in place the per address cap acts as a second global cap, which fails closed.

### 7. Time limits

| Setting | Value | Close |
|---|---|---|
| `connection_init` must arrive within | 5 s | `4408` |
| Server protocol `ping` every | 30 s | |
| Client sends nothing for (Tomcat read idle timeout, fact 9) | 75 s | `1001` |
| A send blocked for | 20 s | connection closed |
| Maximum connection lifetime | 60 min | `1001`; the dashboard reconnects |

The 30 second ping keeps an ingress from cutting a healthy but quiet connection, and a healthy client answers each ping, which is what makes the 75 second read idle timeout safe. The 60 minute lifetime makes every connection pass the origin check and the caps again at least hourly.

### 8. Message limits

* The container's maximum text message size is set explicitly to **8,192 characters**; a larger message closes with `1009`. Binary messages are closed with `1003`.
* After `connection_init`, a connection may send at most **20 messages in any 60 seconds**; over that it is closed with `1008`. Two pongs a minute use two of them.
* The parser limits, depth, normalized field count, cost and `passes` rules of ADR 0012 hold for subscription operations. The field count and parser limits are tested over the socket, the field count in aliased and named fragment forms. No valid selection under `AlertNotice` can reach the depth, cost or `passes` limits (its deepest selection is 4 levels and `passes` is not reachable), so those are covered by a unit test that the cost calculator treats the `Subscription` root as top level.
* Each subscriber has a **bounded buffer of 16 notices**; a subscriber that overflows it is closed with `1008` rather than slowing the others or growing memory. A subscriber whose delivery fails is also removed from fan out and closed with `1008`, so its dashboard reconnects instead of keeping a socket that will never deliver again. Behind that buffer, Spring's handler moves each message to the connection's send thread through Reactor's `publishOn`, which queues up to 256 more and is not configurable there. So a stalled client can hold at most about 272 small notices; in practice the 20 second send timeout closes it first. Memory per connection stays bounded either way.
* No error message or close reason on the socket carries a stack trace, SQL or class name.

All numbers are starting values, like ADR 0012's: the memory and CPU cost of one open connection and of one alert fanned out to every subscriber are measured with the load tests, and the caps reset from that measurement.

## Consequences

* `query-api` gains `spring-websocket` and a long lived connection type. Each connection is a session with its own single thread scheduler (a virtual thread on Java 21) and two message buffers.
* The hardening checklist rewrites `SEC-API-06` and adds rows for the caps, the time limits, one operation type per transport, the message limits, and the anonymous socket; the threat model rewrites T5.3 and adds rows for the controls the socket bypasses, the captured security context, and fan out cost.
* The dashboard's GraphQL client must answer server pings and reconnect after `1001` and after a refused handshake, with backoff; that is checked against the client library version it pins. The development proxy must forward the WebSocket upgrade and the browser's `Origin` unchanged.
* Behaviors this ADR relies on become tests before code relies on them: the limits running for subscription operations, the request timeout not cutting a stream, null fields over the socket, Tomcat's close code for an oversize message, the response to a subscription sent over plain HTTP, and the cap counters returning to zero after every close path.
* Running `query-api` with more than one replica reopens decision 2.

## Amendment, 2026-10-24: what a message may hold

Spring for GraphQL 2.0.5 writes parts of a message into its own log lines as the client sent them: a subscribe id when an operation fails ("Unresolved ... for request id"), and an unknown message type, line breaks included, at `ERROR` before it closes with `1011`. graphql-transport-ws sets no format for an id beyond being unique among the operations of a connection, and closes with `4400` on a message it does not accept. The socket now reads every text message before Spring does, with the same JSON settings as Spring's own reader, and closes with `4400` `Invalid message` unless the message is a JSON object whose `type` is one a client sends (`connection_init`, `ping`, `pong`, `subscribe` or `complete`) and whose `id`, when present and not `null`, is 1 to 64 ASCII letters, digits, hyphens or underscores. A message with no `id` is handled by Spring as before. Because the check runs first, an invalid message sent before `connection_init` also closes with `4400`, where Spring would close a subscribe sent that early with `4401`. The dashboard's client sends 36 character UUIDs and only those five types, so it is unaffected.
