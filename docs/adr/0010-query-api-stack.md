# ADR 0010: The query-api stack

* **Status:** accepted
* **Date:** 2026-10-03

## Context

`query-api` is the third deployable and the second Java service. It owns MySQL, consumes `alerts` and `raw.gp` into it, serves the read endpoints the dashboard and later the assistant's tools call, and holds the one write path in the system, alert acknowledgement ([ADR 0009](0009-alert-acknowledgement-auth.md)). The tables, users and indexes it works against are already decided in the [MySQL schema](../data/mysql-schema.md) and [indexes.md](../data/indexes.md), and the security conditions in the `api`, `ack` and `dat` sections of the [hardening checklist](../security/hardening-checklist.md). This ADR fixes the libraries, the module layout and the HTTP conventions before code lands.

Versions below were read from the `spring-boot-dependencies` 4.1.1 POM on 2026-10-03: Spring Framework 7.0.9, Spring Security 7.1.1, Spring for Apache Kafka 4.1.1, Flyway 12.4.0, HikariCP 7.0.2, MySQL Connector/J 9.7.0, Testcontainers 2.0.5, and Tomcat 11.0.24.

## Decision

### 1. Two Maven modules

* **`query-api`**, the service, added to the parent POM next to `risk-engine` ([ADR 0001](0001-repo-layout-and-build-tool.md)).
* **`db-migrate`**, a small module that does one thing: check its user name placeholders, run Flyway as the migration user, and exit. It depends on `flyway-core` and `flyway-mysql` (both 12.4.0, managed by the parent) and Connector/J, and holds the migrations under `db-migrate/src/main/resources/db/migration`. Its image is built like the risk engine's: a pinned Maven build stage and the pinned distroless Java 21 `nonroot` runtime. It runs as a one shot container before `query-api` starts, exits, and publishes no port.

`query-api` has no Flyway dependency on its classpath and runs with `spring.flyway.enabled=false`, and none of its configuration, environment or mounted secrets names the migration user (`SEC-DAT-06`). The migration that seeds `watchlist_object` and the drift test against `watchlist.json` live in `db-migrate`.

### 2. Web: Spring MVC on Tomcat

`spring-boot-starter-webmvc`, the servlet stack, with one request thread per request. The service is request bound with blocking JDBC underneath, so a reactive stack would buy nothing and would split the security configuration between two models.

The build overrides `tomcat.version` to 11.0.26, because 11.0.24, the version the parent manages, has advisories (GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5) fixed in 11.0.25. No Tomcat authenticator, realm or servlet security constraint is configured; authentication is Spring Security's filter chain alone (`SEC-API-12`). The override is removed once the parent manages a fixed version.

### 3. Security as ADR 0009 decides

`spring-boot-starter-security` with the configuration ADR 0009 sets out: form login under `/api/auth/login`, `csrf.spa()`, the session cookie settings, the 8 hour absolute lifetime filter, the login throttling, and the operator account from a bcrypt hash. Nothing in this ADR changes that one.

### 4. Data access: Spring JDBC `JdbcClient`, two pools

* **SQL by hand** through `JdbcClient` (`spring-boot-starter-jdbc`), with parameter binding only (`SEC-API-01`). No ORM: every query in the [schema's list](../data/mysql-schema.md#queries-the-api-needs) is written once, its index proven by `EXPLAIN` as [indexes.md](../data/indexes.md) requires, and an ORM would hide exactly the SQL that proof is about.
* **Two HikariCP pools**, one per database user: the consumer pool for the two Kafka consumers and the API pool for the HTTP handlers. Each is its own `DataSource` with its own `JdbcClient` and transaction manager, wired by name, so a handler cannot reach the consumer pool by accident.
* **Connection settings on both pools:** `sslMode=REQUIRED` locally (`VERIFY_CA` or `VERIFY_IDENTITY` in the cloud, decided with the cloud environment), `connectionTimeZone=+00:00` with `forceConnectionTimeZoneToSession=true`, and `allowPublicKeyRetrieval`, `allowLoadLocalInfile` and `allowUrlInLocalInfile` all `false` (`SEC-DAT-05`, `SEC-DAT-10`).
* **Grant check at startup.** Each pool runs `SHOW GRANTS` for its own user and the service refuses to start if the result differs from the expected grants, with a test that an extra privilege stops the start (`SEC-DAT-07`).

### 5. Kafka: the same client and validator as the risk engine

Spring for Apache Kafka 4.1.1 on `kafka-clients` 4.2.2 and networknt json-schema-validator 3.0.8, set up as [ADR 0008](0008-java-kafka-client-and-schema-validator.md) decides (schemas from the repository only, duplicate keys rejected, any failure inside validation dead lettered). Two consumers:

* **Alerts**, in the consumer group `query-api-alerts`, writing the event tables and the space weather projection, failures to `alerts.dlq`.
* **Catalog**, in the consumer group `query-api-catalog` ([topics.md](../data/topics.md#consumers)), keeping the newest element set per object, failures to `raw.gp.dlq`.

Both commit an offset only after the database transaction for the record has committed, and both rely on the idempotency rules in the [schema](../data/mysql-schema.md#idempotent-consumption), so delivery stays at least once with no Kafka transactions.

### 6. HTTP conventions

* **Paths** start with `/api`, with no version segment. The dashboard and the API share one origin (ADR 0009, decision 7), so the prefix is what the ingress and the development proxy route on. If an incompatible change is ever needed, a new path is added next to the old one.
* **Lists are paged by key**, never by offset, on an opaque `after` cursor that the server encodes from the last row's key. A page holds 50 items by default and at most 200; a larger `limit`, or a cursor the server cannot decode, gets `400` (`SEC-API-04`).
* **Errors are RFC 9457 problem details** (`application/problem+json`), built with Spring's `ProblemDetail`. The body carries a generic `title` and `detail` and a correlation ID; stack traces and SQL are logged and never returned (`SEC-API-08`, T5.5).
* **Every endpoint has a contract test.** The scope for the first release is the read set the dashboard needs (current space weather per scale, space weather history, the latest screening run with its close approaches, one alert by ID, a catalog object by NORAD ID, the watchlist) plus login, logout and the acknowledgement `POST`.

### 7. MySQL in Compose

The `mysql:8.4.11` image pinned by digest (`SEC-DAT-11`), with no published port and on its own internal `database` network, so only the migrate container and `query-api` reach it, `require_secure_transport=ON`, `default-time-zone=+00:00`, a memory limit of 512 MiB to start with, then measured with `deploy/measure-ram.sh`. Its initialization creates the three users with `REQUIRE SSL`, reading passwords only from `/run/secrets/` (`SEC-DAT-09`). The migrate container runs once after MySQL is healthy, and `query-api` starts only after the migrate container has exited successfully.

### 8. Tests

JUnit 5 with Testcontainers 2.0.5: `testcontainers-mysql` on the same pinned `mysql:8.4.11` image, and `testcontainers-kafka` on `apache/kafka:4.3.1` as in ADR 0008. The schema's test obligations (`DOUBLE` round trips, the column limited acknowledgement insert, the UTC session, and the exact privileges Flyway needs) are proven against that container before code relies on them.

### Amendment, 2026-10-04: response conventions

Three conventions added to decision 6 before the first read endpoints:

* **Field names are `snake_case`**, the names the Kafka contracts and the MySQL columns already use (`event_id`, `derived_label`, `freshness_reference`), so one field reads the same in an event, a row, and a response. A name that a column changed for MySQL's sake (`trigger_kind`) is returned under its contract name (`trigger`).
* **Every response carries an `X-Correlation-Id` header**, a random UUID generated for the request; a problem body repeats it, and the log line for an error carries it with the stack trace. An incoming header of that name is ignored, so a client cannot choose the value written into the log.
* **Locally, Compose publishes the API on `127.0.0.1:8081` only**, so it can be called from this machine and from nowhere else; `ingest` keeps `127.0.0.1:8080`.

Considered: `camelCase`, the usual style for a browser client, at the cost of a second name for every field; and accepting a client's correlation ID when it is a valid UUID, which would help trace a request across the dashboard and the API but lets a client pick a value that appears in the log.

## Alternatives considered

* **Spring WebFlux with R2DBC.** Non blocking end to end, but the service's load is a handful of dashboard viewers, R2DBC would replace the JDBC driver and pool the schema's facts were checked against, and ADR 0009's configuration is written for the servlet filter chain.
* **Spring Data JDBC or JPA.** Less SQL to write, but the queries are few, each needs a proven index, and the append only acknowledgement table and the 1062 duplicate rule are easier to read as plain SQL than to work around in a mapping layer.
* **Flyway inside `query-api` at startup.** One container fewer, but the running service would then hold the migration user's password and DDL rights, which `SEC-DAT-06` rules out.
* **The official Flyway image as the migrate container.** No module to maintain, but it brings its own CLI distribution to review and pin, and the user name placeholder check (`SEC-DAT-08`) would have to run in a wrapper script instead of in tested Java code.
* **One connection pool with the union of both users' grants.** Simpler wiring, but a flaw in an HTTP handler could then write to the event and catalog tables.
* **A version segment such as `/api/v1`.** Conventional, but there is one client, shipped from the same repository, and nothing yet to keep compatible.
* **Offset paging.** Simple to write, but pages shift while new alerts arrive and the cost grows with the offset.

## Consequences

* A new endpoint needs a contract test, a query from the schema's list or an addition to it, and the index proof in indexes.md.
* Upgrading the Spring Boot parent means checking whether the Tomcat override can go.
* `query-api` holds two database credentials and the migrate container a third; the cloud environment provides all three from the secret store.
* If `query-api` ever runs more than one replica, sessions and the login throttling need shared storage (ADR 0009, Consequences); the two consumers already scale by partition within their groups.
