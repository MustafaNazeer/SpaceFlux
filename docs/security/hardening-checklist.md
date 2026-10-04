# Hardening checklist

Every pull request is checked against the items whose tags match the parts of the system it touches. Items tagged `all` apply to every pull request. Each item is written so it can be answered yes or no by reading the diff, running a command, or inspecting configuration. The reasoning behind each item is in the [threat model](threat-model.md); the IDs in brackets point to the threat it addresses.

Items marked **(to be verified)** depend on a tool, library, or provider behavior that I have not yet confirmed against its documentation. The exact tool or setting is chosen and linked when the component it belongs to is built, and the mark is removed then. Known gaps in the code built so far, and when each is handled, are listed in the threat model's [deferred and open items](threat-model.md#deferred-and-open-items-in-the-built-code).

## Tags

| Tag | Applies to |
|---|---|
| `all` | Every change |
| `ingest` | Go pollers and anything that calls a third party feed |
| `kafka` | Topics, producers, consumers, broker configuration |
| `risk-engine` | Propagation, screening, storm rules, alert emission |
| `query-api` | REST and GraphQL endpoints, including subscriptions |
| `ack` | The alert acknowledgement write path and its authentication |
| `dashboard` | The Angular app |
| `assistant` | Retrieval, prompt assembly, LLM calls, output handling |
| `mcp` | The MCP tool server and every tool definition |
| `data` | MySQL, MongoDB Atlas, migrations, vector index |
| `secrets` | Any credential, key, token, or password |
| `aws` | Terraform, EKS, IAM, networking, Kubernetes manifests |
| `ci` | GitHub Actions workflows, container builds, dependency tooling |
| `observability` | Logging, tracing, metrics, dashboards |
| `repo` | Anything committed or published publicly |

## all

- [ ] `SEC-ALL-01` No secret, key, token, password, or connection string appears in the diff, including tests, fixtures, docs, and examples. [T9.1, T12.1]
- [ ] `SEC-ALL-02` Every new dependency is named in the pull request description with the reason it is needed, and its lockfile entry is committed. [T11.3]
- [ ] `SEC-ALL-03` Every new network endpoint, topic, secret, or third party call is reflected in the threat model in the same pull request. [section 9]
- [ ] `SEC-ALL-04` Input from outside the process is validated against an explicit schema or type before use. [T1.1, T4.1, T8.2]
- [ ] `SEC-ALL-05` Error responses and log lines added in the diff contain no credential, full request URL with query string, or stack trace sent to a client. [T1.5, T5.5]
- [ ] `SEC-ALL-06` Every dependency version added or changed in the diff, including transitive ones, has been checked against a vulnerability database; each open advisory is either fixed by a version override or recorded in the threat model with the reason it is not reachable. [T11.3]

## ingest

- [ ] `SEC-ING-01` Every HTTP client has connect and total request timeouts set. [T1.1]
- [ ] `SEC-ING-02` Response bodies are read through a size limit, with the limit per feed stated in code. [T1.1]
- [ ] `SEC-ING-03` TLS certificate verification is never disabled, including in tests that hit real endpoints. [T1.7]
- [ ] `SEC-ING-04` Redirects are either disabled or restricted to a per feed host allowlist. [T1.7]
- [ ] `SEC-ING-05` Each poller has its own interval and exponential backoff with jitter, and its interval is linked to the provider's published guidance in `docs/source/`. CelesTrak's guidance is verified in [docs/source/celestrak.md](../source/celestrak.md) and applied by [ADR 0004](../adr/0004-celestrak-polling-and-error-handling.md); SWPC's is verified in [docs/source/swpc.md](../source/swpc.md) and applied by [ADR 0005](../adr/0005-swpc-polling-and-error-handling.md), including the 1 minute floor on both the interval and every retry; DONKI and Space-Track guidance **(to be verified)** when each poller is added. [T1.4]
- [ ] `SEC-ING-06` Requests send the identifying header and contact information the provider asks for, if it asks. Neither CelesTrak's documentation ([docs/source/celestrak.md](../source/celestrak.md)) nor SWPC's ([docs/source/swpc.md](../source/swpc.md)) asks for one, and ingest sends a descriptive `User-Agent` to both regardless; DONKI and Space-Track **(to be verified)** when each poller is added. [T1.4]
- [ ] `SEC-ING-07` Payloads failing validation go to the matching `.dlq` topic with a reason, and are never silently dropped. [T1.1]
- [ ] `SEC-ING-08` Numeric fields are range checked (epochs, orbital elements, Kp, flux values) before publish, with bounds cited in `docs/risk/`, `docs/source/`, or `docs/data/`. A check the producer cannot make from a cited bound is named, with the consumer that makes it (for GP data, positive mean motion and plausible epochs are checked in the risk engine under `SEC-RSK-01`). [T1.3]
- [ ] `SEC-ING-09` Feed credentials (api.nasa.gov key, Space-Track login) are read from the environment at startup and never logged, including in failed request logs, and a key sent as a query parameter is redacted from `source_url` and dead letter reasons, with a test. [T1.5, T1.6]
- [ ] `SEC-ING-10` No Space-Track code lands before the ADR summarizing its user agreement exists. [T1.6]
- [ ] `SEC-ING-11` A provider base URL from configuration is checked at startup against the provider's one allowed scheme and host, and the service refuses to start otherwise. [T1.7]
- [ ] `SEC-ING-12` Every record and every dead letter published has a stated byte cap that holds after encoding and stays under the Kafka client's record limit, with a test using a payload that expands when encoded. [T1.1, T2.2]
- [ ] `SEC-ING-13` Dead letters are validated against the dead letter schema before publish. [T1.1]
- [ ] `SEC-ING-14` Health and readiness endpoints listen on loopback by default, are published by Compose only on `127.0.0.1`, set header read, write, and idle timeouts and a header size cap, and send `X-Content-Type-Options: nosniff`. [T1.8]
- [ ] `SEC-ING-15` Before the first cloud deployment, readiness responses carry status words only, with error detail kept in logs, and health ports have no public Service or ingress route. [T1.8, T5.5]
- [ ] `SEC-ING-16` A provider record whose bytes are not valid UTF-8 is dead lettered rather than published, with a test that puts invalid bytes inside a JSON string. Met today by the CelesTrak and SWPC processors, whose dead letters carry such a record as base64. [T1.1]
- [ ] `SEC-ING-17` A cache validator taken from a provider response (such as an `ETag`) is stored only up to a stated length and is dropped after an error status, so a bad value cannot hold a feed in retries. Met today by the SWPC fetcher, which keeps an `ETag` of at most 1024 bytes and clears it after any error status. [T1.9]

## kafka

- [ ] `SEC-KFK-01` Compose publishes broker ports only on `127.0.0.1`, never on all interfaces. [T2.1]
- [ ] `SEC-KFK-02` In Kubernetes the broker has no `LoadBalancer` or `NodePort` Service and no ingress route. [T2.1]
- [ ] `SEC-KFK-03` A NetworkPolicy limits which pods can reach the broker, and the cluster's network plugin is confirmed to enforce it **(to be verified)**. [T2.1]
- [ ] `SEC-KFK-04` Every consumer is idempotent on the feed's epoch and ID fields, with a test that delivers the same event twice. [T2.3]
- [ ] `SEC-KFK-05` Every consumer has bounded retries followed by a `.dlq` publish. [T2.2]
- [ ] `SEC-KFK-06` Kafka headers are used only for trace context and schema version, never for authorization or query input. [T2.4]
- [ ] `SEC-KFK-07` Java consumers read values as bytes and keys as strings and parse the JSON themselves; no type header driven deserializer, delegating deserializer, header mapper with trusted packages, or retry topic is configured. Met today by the risk engine's `raw.swpc` consumer and `query-api`'s alerts consumer. [T2.7]
- [ ] `SEC-KFK-08` A Java service builds each dead letter itself as a dead letter schema event, with the payload caps and base64 fallback of `SEC-ING-12`, validates it before publish, and adds no exception stack trace header; schema and rule failures are not retried. Met today by the risk engine's `raw.swpc` consumer and `query-api`'s alerts consumer, both through the shared `kafka-contracts` module. [T2.8, T1.1]
- [ ] `SEC-KFK-09` Producers set `enable.idempotence=true` and `acks=all` explicitly and bound `max.block.ms` and `delivery.timeout.ms`; consumers set `enable.auto.commit=false` and `allow.auto.create.topics=false`; bootstrap servers and every security setting come from configuration, with no credential in a committed file. Met today by the risk engine's `raw.swpc` consumer and `alerts` producer. [T2.1, T2.2]
- [ ] `SEC-KFK-10` Before the first cloud deployment, each client's `security.protocol` and credentials match the recorded decision on broker authentication, and the credentials come from the secret store. [T2.1, T9.4]
- [ ] `SEC-KFK-11` The JSON Schema validator loads schemas only from this repository's `schemas/` files: no remote loader, an allow rule limited to the project's schema IRIs, and the schema picked by topic, never by event content; a test shows that a remote `$ref` fails without any network connection. Met today by the risk engine and `query-api` through the shared `kafka-contracts` module, whose test counts no connection to a local listener named in a remote `$ref`. [T2.5]
- [ ] `SEC-KFK-12` Every validation call turns any `Throwable` from the validator, `StackOverflowError` included, into a failed validation and a dead letter; a test runs every `pattern` in `schemas/` against long adversarial strings in a thread with the default stack size. Met today by the risk engine and `query-api` through the shared `kafka-contracts` module. [T2.6]
- [ ] `SEC-KFK-13` Each record is parsed once from its bytes by the service's own JSON parser with an explicit maximum document length, the parsed tree is what the validator checks, and format assertions are enabled so `format` is checked as it is in `ingest`. Met today by the risk engine and `query-api` through the shared `kafka-contracts` module, which also rejects duplicated keys and documents over 1 MiB; `query-api` also refuses bytes that are not UTF-8 before parsing, because the parser accepts some malformed sequences inside strings. [T1.1, T2.6]
- [ ] `SEC-KFK-14` Every Java consumer can decode each compression codec a producer could use on the topics it reads, without making `/tmp` executable: a codec that loads a native library at run time either gets its own size limited temporary directory that allows exec (see `SEC-AWS-10`), or loads a library shipped in the image, or the topic fixes its codec at the broker with `compression.type`. Met today by the risk engine's Compose service: `-Dorg.xerial.snappy.tempdir=/native` and `-DZstdTempFolder=/native` point snappy and zstd at the `/native` tmpfs, `/tmp` stays `noexec`, and `CompressionCheck` decodes a gzip, snappy, lz4, and zstd record at startup (`CompressionCheckTest`). `query-api`'s Compose service has the same settings, tmpfs, and startup check. [T2.9]
- [ ] `SEC-KFK-15` A consumer that cannot decode or fetch a batch fails visibly at startup or backs off between attempts, rather than repeating the failure in a tight poll loop. Met today by the risk engine: a codec that cannot load stops the start (`CompressionCheck`), and both listener container factories wait 5 seconds before rethrowing an error with no record behind it (`KafkaConfig.WaitingErrorHandler`, `KafkaConfigTest`). `query-api` does the same with its own copies of both. [T2.9, T2.2]
- [ ] `SEC-KFK-16` A test run cannot reach a developer's live broker by accident: the build points the shared broker setting at an address that reaches nothing, so a test that forgets to name its own test broker fails to connect instead of reading from or writing to a local stack. Met today by the risk engine: `risk-engine/pom.xml` sets `KAFKA_BROKERS` to `127.0.0.1:1` through the Surefire plugin's `systemPropertyVariables`, and `RiskEngineApplicationTest` asserts that a context without its own broker resolves `spring.kafka.bootstrap-servers` to that address. Still open: the guard covers only the shared `spring.kafka.bootstrap-servers` key. A client specific key (`spring.kafka.consumer.bootstrap-servers`, `spring.kafka.producer.bootstrap-servers`, `spring.kafka.streams.bootstrap-servers`) set in a test property or in the developer's environment takes precedence for that client, and a test that builds a Kafka client directly takes whatever address it is given. No committed test sets a client specific key, and every committed test that builds a client directly gives it the Testcontainers broker's address. [T2.1]

## risk-engine

- [ ] `SEC-RSK-01` Orbital elements are validated before propagation, including a positive mean motion and an epoch within a plausible window of the current time, and a test covers rejection of out of range elements. [T3.1, T1.3]
- [ ] `SEC-RSK-02` Propagation and screening per event have a time budget, and exceeding it is logged and counted. [T3.1]
- [ ] `SEC-RSK-03` Every alert carries the IDs and epochs of the events that produced it. [T3.2]
- [ ] `SEC-RSK-04` Processing of one poll finishes well inside `max.poll.interval.ms`: long work such as a screening run happens off the poll thread, and any wait the poll thread shares with it is bounded. A consumer that commits offsets commits a batch only after its events are acknowledged; the `raw.gp` screening consumer never commits and rebuilds its state from the start of `raw.gp` instead ([ADR 0008](../adr/0008-java-kafka-client-and-schema-validator.md), decisions 3 and 6). Met today by the `raw.gp` consumer, whose run is computed on its own scheduler thread outside the state lock and whose shared write lock waits at most 40 seconds for acknowledgements. A whole run on the 22 object `stations` fixture took 1.13 to 1.41 seconds on my laptop ([screening cost](../perf/screening-cost.md)); the time of one run at catalog scale is still to be measured, with a recorded fixture of a larger catalog group. [T3.3]
- [ ] `SEC-RSK-05` Feed records are read through Jackson's tree model only, with no polymorphic typing and no binding to `Path`, `Duration`, or `XMLGregorianCalendar`, and the Jackson version in the build has no open advisory. Met today by the risk engine, on Jackson 3.1.7. [T3.4, T11.3]
- [ ] `SEC-RSK-06` A space weather reading is accepted into its series only when its sample time is no later than the record's `fetched_at` plus a stated tolerance and its satellite number is positive; anything else is dead lettered as a rule failure, with a test. A sample time or `fetched_at` that cannot be read as a UTC time, any hour of 24 included, is dead lettered as a rule failure too, never dropped and never read as midnight of the next day; a second of 60 is accepted only as 23:59:60, read as 23:59:59, because real leap seconds exist. Met today by the risk engine's `raw.swpc` consumer, with a 5 minute tolerance; an hour of 24 in a `time_tag` is covered by `ReadingTest`, and in `fetched_at` it is already refused by the schema's `date-time` format assertion, with the rule as a second line. [T3.5, T1.3]
- [ ] `SEC-RSK-07` No record content can make a processing call throw: failures while deriving or building a record's events become a dead letter for that record, the scheduled refresh handles each scale on its own, and a test feeds schema valid mutations of recorded payloads through the processor and asserts that none throws and none is dropped without a dead letter or a count. Met today by the risk engine's `raw.swpc` consumer and its `raw.gp` consumer, which never formats two line element set text lines and compares same epoch copies field by field (the name with `Objects.equals`, the element set with Orekit's `TLE.equals`), so an element set that a line cannot hold, such as a catalog number above 339999, a `REV_AT_EPOCH` above 99999, or a `BSTAR` of 1e30, is held and listed rather than thrown on (`GpProcessorFuzzTest`, seed 42, 10 rounds of 200 mutated ISS element sets; `GpProcessorTest`, beyond line format and `BSTAR` copy cases). [T3.5, T3.7, T2.8, T1.1]
- [ ] `SEC-RSK-08` An element set is accepted only when its event's `fetched_at` is no later than the consumer's clock plus a stated tolerance and its `EPOCH` is no later than that `fetched_at` plus the same tolerance, so an accepted `EPOCH` is at most twice the tolerance past the clock; anything else is dead lettered as a rule failure before it can replace a held element set, with a test. Met today by the risk engine's `raw.gp` consumer, with a 5 minute tolerance, so an accepted `EPOCH` is at most 10 minutes past its clock. [T3.6, T1.3]
- [ ] `SEC-RSK-09` Every alerts event has a stated size bound under the producer's request limit, with names and lists capped and truncated with counts of what was left out, and a test builds an event from oversized input. Met today by the risk engine: names are cut to 64 code points on arrival, at most 3 differing copies are held per object with further ones counted in `differing_copies_over_cap`, and the screening run summary is kept within 900,000 bytes with cut entries counted in `omitted` (`SummaryBudgetTest`, `GpProcessorTest`). [T3.8]
- [ ] `SEC-RSK-10` Every alerts event built from schema valid input is itself schema valid, so an optional field whose input value is missing is left out rather than written as `null`, with a test. Met today by the risk engine, which leaves out a missing `OBJECT_NAME` in every list, differing copies included (`GpProcessorTest`). [T3.8, T3.2]
- [ ] `SEC-RSK-11` The scope of the screening run identity guarantee (one process lifetime, one instance reading every `raw.gp` partition) is stated in the threat model, and the consumer is not run as more than one instance until it is revisited. [T3.9]

## query-api

- [ ] `SEC-API-01` All SQL uses parameter binding; no query text is built by concatenating request values. [T4.1]
- [ ] `SEC-API-02` Mongo filters are built from typed values; request values that are objects or start with an operator character are rejected. [T4.2]
- [ ] `SEC-API-03` GraphQL enforces a maximum query depth and a maximum complexity, each with a test that sends a query over the limit. How these limits are configured in Spring for GraphQL **(to be verified)**. [T5.1]
- [ ] `SEC-API-04` Every list field and endpoint has a maximum page size enforced server side. [T5.1]
- [ ] `SEC-API-05` The GraphQL introspection setting for the cloud environment matches the recorded ADR decision. [T5.2]
- [ ] `SEC-API-06` Subscriptions have a per client connection cap, an idle timeout, and an origin check on the handshake. [T5.3]
- [ ] `SEC-API-07` CORS allows only the dashboard origin, and never combines a wildcard origin with credentials. While the dashboard is served from the same origin as the API ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md)), no CORS configuration exists at all. [T5.4]
- [ ] `SEC-API-08` Error handlers return a generic body with a correlation ID; stack traces are logged, not returned. [T5.5]
- [ ] `SEC-API-09` A per client rate limit applies in the cloud environment, at the ingress or in the service. [T5.6]
- [ ] `SEC-API-10` No endpoint returns raw CDM content; CDM derived responses expose flags and derived fields only, with a test asserting it. [T4.5]
- [ ] `SEC-API-11` Management and health endpoints that expose environment, configuration, or heap data are disabled or unreachable from outside the cluster. [T9.3]
- [ ] `SEC-API-12` `query-api` configures no Tomcat authenticator, realm, or servlet security constraint; authentication is Spring Security's filter chain alone. The build overrides `tomcat.version` to 11.0.26 while the Spring Boot parent manages 11.0.24, which has advisories fixed in 11.0.25. [T11.3]

## ack

- [ ] `SEC-ACK-01` The authentication design is recorded in an ADR before the endpoint is merged. Met: [ADR 0009](../adr/0009-alert-acknowledgement-auth.md), a server side session cookie with form login. [section 8]
- [ ] `SEC-ACK-02` Unauthenticated requests are rejected before any database access, with a test. [T6.1]
- [ ] `SEC-ACK-03` If a cookie carries the session: CSRF protection is on, and the cookie is `Secure`, `HttpOnly`, and `SameSite`. If a bearer token is used: it is not stored in `localStorage`. [T6.2]
- [ ] `SEC-ACK-04` Login attempts are rate limited, and any stored password uses a slow password hashing function. [T6.3]
- [ ] `SEC-ACK-05` The request body is allowlisted to an optional `note` of at most 500 Unicode code points (counted with `String.codePointCount`, matching the `VARCHAR(500)` column); a longer note and any unknown field, `principal` and `acted_at` included, are refused with `400` by an explicit setting with a test; the alert ID comes from the path, `principal` is set from the security context, and `acted_at` by the database at insert. [T6.5]
- [ ] `SEC-ACK-06` `alert_acknowledgement` rows are append only and record `event_id`, `action`, `principal`, `acted_at`, and `note`; unacknowledging writes a new row with `action` `unacknowledge`. [T6.4]
- [ ] `SEC-ACK-07` The identity used by the MCP tool server is rejected by this endpoint, with a test. [T8.7]
- [ ] `SEC-ACK-08` Demo credentials are created per environment from the secret store and never appear in the repo or in recordings. The secret is a bcrypt hash generated offline; the plain password is never stored in `.env`, Secrets Manager, or the container environment. [T6.6]
- [ ] `SEC-ACK-09` No default user exists: the service defines its own authentication beans, and a test asserts that Spring Boot's "Using generated security password" line never appears in the startup log. [T6.7, T9.3]
- [ ] `SEC-ACK-10` Without a configured operator username and password hash, the acknowledgement endpoint answers `403` for every request and the rest of the API starts and serves normally, with a test. [T6.1, T6.7]
- [ ] `SEC-ACK-11` The session cookie is `Secure`, `HttpOnly`, and `SameSite=Strict` in the cloud profile; the session ID changes at login; logout invalidates the session and sends `Clear-Site-Data`; at most one operator session exists. [T6.2]
- [ ] `SEC-ACK-12` A session ends after 30 minutes idle and 8 hours after login, whichever comes first; the absolute lifetime is measured from the recorded login instant, with a test. [T6.2]
- [ ] `SEC-ACK-13` CSRF is configured with `csrf.spa()`, and a test shows an acknowledgement without a valid `X-XSRF-TOKEN` header is refused. [T6.2]
- [ ] `SEC-ACK-14` Anonymous requests never create a session: the request cache is `NullRequestCache`, with a test that a refused anonymous request sets no session cookie. [T6.1]
- [ ] `SEC-ACK-15` Login throttling uses backoff, never a hard lockout of the only account; a blocked attempt answers `429` with `Retry-After` without running the password check; per client address 3 consecutive failures are free, then the address is blocked for 2 seconds after the 4th failure, doubling with each further failure up to 15 minutes, and reset by a successful login or 1 hour with no failure; across all addresses at most 30 failures in any 10 minute window, above which every login attempt is refused until the window has fewer; at most 2 password checks run at once, and a third concurrent attempt answers `429` with `Retry-After: 1` at once; at most 10,000 addresses are tracked, oldest evicted first ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 8); tests cover the backoff, the global window, and the `429` path. [T6.3]
- [ ] `SEC-ACK-16` Only `close_approach` events and `space_weather_level` events in state `level` can be acknowledged; a `screening_run`, a refresh, a "no data" event, or an unknown alert ID is refused, with a test for each. [T6.5]
- [ ] `SEC-ACK-17` Append only is enforced by grants, not triggers, matching the [database users](../data/mysql-schema.md#database-users): the API user keeps `SELECT` on the tables it serves and its only write privilege is `INSERT` on `alert_acknowledgement`, limited to `event_id`, `action`, `principal`, and `note`; it has no `UPDATE` or `DELETE` anywhere; the consumer user has no privilege on `alert_acknowledgement`; the migration user is used only by Flyway. Checked against the grants as created. [T6.4, T4.3]
- [ ] `SEC-ACK-18` Responses to anonymous requests carry only `action` and `acted_at` of acknowledgement rows, with no display name; `note` and `principal` are left out of the response, not hidden by the page, with a test. [T6.4]

## dashboard

- [ ] `SEC-DSH-01` No use of Angular's sanitizer bypass APIs, and no `innerHTML` binding of feed or assistant text. [T7.3]
- [ ] `SEC-DSH-02` Assistant answers render as plain text or sanitized markdown with images disabled and links restricted to an allowlist of source domains. [T7.3]
- [ ] `SEC-DSH-03` A Content Security Policy is served that disallows inline script and limits `connect-src` to the API origin. [T7.3, T5.4]
- [ ] `SEC-DSH-04` The demonstration disclaimer is visible on every view that shows an alert. [T1.3, T3.2]
- [ ] `SEC-DSH-05` No API key or credential is present in the built bundle; checked by searching the build output. [T9.1]

## assistant

- [ ] `SEC-AST-01` System instructions, user question, retrieved documents, and tool results are assembled in separate, clearly delimited sections; retrieved and tool text is labelled as quoted source material. [T7.1, T7.2]
- [ ] `SEC-AST-02` The eval set contains direct and indirect injection cases (including a retrieved document with embedded instructions), and they pass before merge. [T7.1, T7.2]
- [ ] `SEC-AST-03` Every citation in an answer resolves to a document retrieved for that request; unresolved citations are removed and counted. [T7.4]
- [ ] `SEC-AST-04` The user question has a maximum length, the model call has a maximum output token count, and each request has a maximum number of tool call rounds. [T7.5, T8.6]
- [ ] `SEC-AST-05` A per client request rate limit applies to the assistant endpoint. [T7.5]
- [ ] `SEC-AST-06` The OpenAI account has a spending limit set, and its behavior on reaching the limit is confirmed from OpenAI's documentation **(to be verified)**. [T7.5]
- [ ] `SEC-AST-07` No secret, connection string, or internal hostname is placed in any prompt, checked by reading the prompt templates. [T7.6]
- [ ] `SEC-AST-08` Only documents from the configured feeds are embedded, and each chunk stores its source ID and fetch time. [T7.7]
- [ ] `SEC-AST-09` Model output is never executed, evaluated, or interpolated into a query; it reaches tools only through validated parameters. [T7.8]
- [ ] `SEC-AST-10` No raw CDM content reaches a prompt or the vector index. [T7.9]

## mcp

- [ ] `SEC-MCP-01` Every tool is read only and maps to exactly one read endpoint on `query-api`; the list of tools and their endpoints is documented next to the tool server code. [T8.1]
- [ ] `SEC-MCP-02` No tool accepts a URL, host name, file path, or free form query string. [T8.3]
- [ ] `SEC-MCP-03` Every tool parameter has a type and bounds (ID format, maximum time window, maximum result count) enforced server side, with a test that sends an out of bounds value. [T8.2]
- [ ] `SEC-MCP-04` Tool names and descriptions are static in source; nothing about a tool is loaded from data at runtime. [T8.5]
- [ ] `SEC-MCP-05` Each tool has a rate limit, and the tool server rejects calls over it. [T8.6]
- [ ] `SEC-MCP-06` The tool server has no public Service or ingress route, and callers are authenticated if it uses a network transport. The supported transports and their security options in Spring AI **(to be verified)**. [T8.4]
- [ ] `SEC-MCP-07` The tool server calls `query-api` with its own read scoped identity. [T8.7]

## data

- [ ] `SEC-DAT-01` Separate database users exist for migrations (DDL) and for the application (no DDL). For MySQL there are exactly three, matching [database users](../data/mysql-schema.md#database-users): the migration user holds `CREATE`, `ALTER`, `DROP`, `INDEX`, `REFERENCES`, `SELECT`, `INSERT`, and `UPDATE` on `spaceflux.*` with `GRANT OPTION`, plus `DELETE` and `CREATE` on `spaceflux.flyway_schema_history` only (MySQL refuses a table level grant on a table that does not exist yet unless it includes `CREATE`, which the schema wide `CREATE` already covers), and no global privilege and nothing on the `mysql` schema; the consumer and API users hold only the grants listed there and never `GRANT OPTION`. `root` exists only as `root@localhost` (Compose sets `MYSQL_ROOT_HOST: localhost`), and since the server skips name resolution it can log in only over the Unix socket inside the MySQL container, never over TCP; a test asserts that the only accounts whose host is not `localhost` are the three SpaceFlux users. Any privilege added to the migration user needs a new security review. Retiring the migration user, or taking its grant rights away, uses `REVOKE ALL PRIVILEGES, GRANT OPTION FROM` that user or `DROP USER`: revoking its listed privileges, or `REVOKE ALL PRIVILEGES ON spaceflux.*`, leaves `GRANT OPTION` on the schema in place, and the second also leaves the grant on `flyway_schema_history`. [T4.3, T4.6]
- [ ] `SEC-DAT-02` The archiver's Mongo user can write only the raw document collections; the retrieval user can only read the vector collection. How Atlas free tier roles can be scoped **(to be verified)**. [T4.3]
- [ ] `SEC-DAT-03` The Atlas network access list does not include an allow all entry. [T4.4]
- [ ] `SEC-DAT-04` Raw CDM documents live in a collection that no public endpoint, tool, or embedding job reads. [T4.5, T7.9]
- [ ] `SEC-DAT-05` Database connections use TLS where the server supports it (Atlas requires it). MySQL runs with `require_secure_transport=ON`, every MySQL account is created with `REQUIRE SSL`, and clients set `sslMode=REQUIRED` locally and `VERIFY_CA` or `VERIFY_IDENTITY` in the cloud, with a test that the same account connects over TLS and is refused without it (error 1045, since the `REQUIRE SSL` check refuses the login before `require_secure_transport` is consulted) and a separate assertion that `require_secure_transport` is on. [T4.4, T4.7]
- [ ] `SEC-DAT-06` Only the migrate container holds the migration password: `query-api`'s build bans every `org.flywaydb` artifact in every scope except `test` (Spring Boot's repackage step copies `provided` dependencies into the jar, so a compile and runtime ban alone is not enough), it runs with `spring.flyway.enabled=false`, and no `query-api` configuration, environment, or mounted secret names the migration user. [T4.3, T4.6]
- [ ] `SEC-DAT-07` At startup each `query-api` connection pool runs `SHOW GRANTS` for its own user and refuses to start if the result differs from the expected grants, with a test that an extra privilege stops the start. The check runs `SHOW GRANTS` without `FOR`, so mandatory roles are included ([SHOW GRANTS](https://dev.mysql.com/doc/refman/8.4/en/show-grants.html)), and a role grant, partial revoke, proxy, or dynamic privilege line counts as a difference. It runs once per pool at startup; a grant changed while the service runs is detected at the next start. [T4.6]
- [ ] `SEC-DAT-08` The migrate container refuses to run unless every user name placeholder matches `^[a-z][a-z0-9_]{0,31}$`, with a test that a name containing a quote is refused before Flyway starts; account host parts are written in the migration, not taken from a placeholder. [T4.8]
- [ ] `SEC-DAT-09` Local database passwords live only in the git ignored `deploy/secrets/`, written by `deploy/mysql/make-secrets.sh` as 40 characters of `[A-Za-z0-9]`, in a `0700` directory (the files are `0644` because Compose ignores `mode` on file secrets and the containers do not run as my host user). The account creation script reads passwords only from `/run/secrets/`, refuses any password that is not at least 32 characters of `[A-Za-z0-9]`, never enables shell tracing, never passes a password on a command line, and never prints one. [T4.9]
- [ ] `SEC-DAT-10` MySQL accounts use `caching_sha2_password`; `mysql_native_password` is not enabled on the server; no connection string sets `allowPublicKeyRetrieval`, `allowLoadLocalInfile`, or `allowUrlInLocalInfile` to `true`. [T4.7, T4.10]
- [ ] `SEC-DAT-11` The MySQL image is pinned by digest (`mysql:8.4.11@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242` today), and the migrate container's image is built from base images pinned by digest; Compose publishes the MySQL port only on `127.0.0.1`, or not at all; and the migrate container exits after it runs and publishes no port. [T2.1, T11.4, T4.3]
- [ ] `SEC-DAT-12` MySQL's X Plugin is off (`mysqlx = OFF` in `deploy/mysql/conf.d/spaceflux.cnf`), so nothing listens on 33060; MySQL joins only the `database` Compose network, and only services that hold a MySQL credential (the migrate container and `query-api`) join that network, which Compose marks `internal` so it has no route to the internet; `query-api` is the only service on both the `database` network and another network. [T2.1, T4.3]
- [ ] `SEC-DAT-13` The MySQL container runs as `999:999` with `cap_drop: [ALL]`, no `cap_add`, and `no-new-privileges`; the migrate container runs as a non root user, read only, with every capability dropped. [T4.3]
- [ ] `SEC-DAT-14` `query-api` reads each database password only from its own secret file through a config tree, and refuses to start if any other property source, such as an environment variable or a command line flag, sets the password's key or the property it binds to. It refuses to start if `hikaricp.configurationFile` is set, and a test asserts the driver's effective `sslMode`, `allowLoadLocalInfile`, `allowUrlInLocalInfile`, and `allowPublicKeyRetrieval` on a pooled connection from each pool, together with the 30 second `socketTimeout` written in the URL, so a database that accepts a connection and then stops answering fails a statement instead of blocking a consumer. [T4.7, T4.9, T4.10]
- [ ] `SEC-DAT-15` `query-api`'s consumers never throw on record content: bytes that are not UTF-8 are dead lettered with `check` `schema`; a value its column cannot store as received (over length in code points, out of range, not a real time, not well formed Unicode, or refused by MySQL with 1406, 1264, 1292, 1366, 3819 or 1048) is dead lettered with no `check`; any other database or broker error is retried without limit and logged once per attempt as its error code, SQLState and message with control characters and line breaks escaped; the record's key and payload are never logged, though a database message can quote a single column value; a test feeds schema valid mutations of every example through the processor and the real database and asserts that none throws. [T3.10, T2.2]

## secrets

- [ ] `SEC-SEC-01` `.env` and its variants are git ignored, `.env.example` contains placeholders only, and `.dockerignore` excludes `.env`, `.env.*`, and private key and key store files (`*.pem`, `*.key`, `*.p12`, `*.jks`, `id_*`) at any depth, not only at the root. [T9.1]
- [ ] `SEC-SEC-02` No secret is passed as a Docker build argument or copied into an image layer. [T9.2]
- [ ] `SEC-SEC-03` In EKS, secrets come from AWS Secrets Manager through the chosen sync mechanism, not from values in manifests or Helm values files **(mechanism to be verified)**. [T9.4]
- [ ] `SEC-SEC-04` Each service has its own Kubernetes service account, and RBAC lets it read only its own secrets. [T9.4]
- [ ] `SEC-SEC-05` Encryption at rest for Kubernetes Secrets is confirmed for the cluster configuration in use **(to be verified)**. [T9.4]
- [ ] `SEC-SEC-06` Every secret has written rotation and revocation steps in `docs/security/`. [T9.6]
- [ ] `SEC-SEC-07` The OpenAI key used by CI is separate from the one used by the running assistant, so either can be revoked alone. [T9.6, T11.1]

## aws

- [ ] `SEC-AWS-01` CI authenticates to AWS through GitHub OIDC with a role whose trust policy is restricted to this repository and to the branches allowed to deploy; no IAM user access keys exist for CI. [T10.1]
- [ ] `SEC-AWS-02` Pods get AWS permissions only through per service roles bound to service accounts (IRSA or EKS Pod Identity, **to be verified**), never through the node role. [T10.2]
- [ ] `SEC-AWS-03` The EKS API endpoint is restricted to known address ranges or is private **(options to be verified)**. [T10.3]
- [ ] `SEC-AWS-04` Every resource is created by Terraform and tagged with the project name; nothing is created by hand. [T10.4]
- [ ] `SEC-AWS-05` Terraform state is not committed; if remote state is used, its bucket blocks public access, is encrypted, and is versioned. [T9.5]
- [ ] `SEC-AWS-06` Secret values are not stored as plain Terraform variables or outputs; any secret that passes through Terraform is marked sensitive, and what that marking does and does not protect is checked **(to be verified)**. [T9.5]
- [ ] `SEC-AWS-07` Budget alerts are configured on the account, and after each `terraform destroy` the billing console is checked for remaining resources. [T10.4]
- [ ] `SEC-AWS-08` Containers run as a non root user, drop all Linux capabilities not needed, set resource requests and limits, and use a read only root filesystem where the runtime allows. The same applies to local Compose services: every long running service drops all capabilities, sets `no-new-privileges`, and has a memory limit, a swap limit, and a process limit sized from the measurement in [docs/perf/local-memory.md](../perf/local-memory.md). [T10.5]
- [ ] `SEC-AWS-09` Only the dashboard, `query-api`, and (if the ADR allows it) the assistant are reachable through ingress; Kafka, databases, the MCP tool server, Prometheus, and Grafana are not. [T2.1, T8.4, T13.2]
- [ ] `SEC-AWS-10` A writable mount that allows exec exists only where a library must load native code from it; it is a size limited tmpfs mounted `nosuid` and `nodev`, owned by the service user with mode `0700`, `/tmp` stays `noexec`, and every manifest that runs the image (Compose and Kubernetes) carries the same mount and the JVM option that points the library at it. Met today by the risk engine's Compose service, whose 8 MiB `/native` tmpfs is mounted `exec,nosuid,nodev` with `uid=65532,gid=65532,mode=0700` beside a 16 MiB `noexec` `/tmp`; the JVM options are set in `deploy/compose.yaml`, not in the image, so the Kubernetes manifests must repeat both. [T2.9, T10.5]
- [ ] `SEC-AWS-11` Every Compose service rotates its container logs with a size and file count limit, so a service that logs in a loop cannot fill the disk. Met today by every service in `deploy/compose.yaml`, through a shared `x-logging` block: `json-file` with `max-size` 10m and `max-file` 3. [T2.9]

## ci

- [ ] `SEC-CI-01` Every third party action is pinned to a full commit SHA. [T11.2]
- [ ] `SEC-CI-02` Each workflow sets `permissions` explicitly, at the lowest level its jobs need. [T11.2]
- [ ] `SEC-CI-03` No workflow uses `pull_request_target` together with a checkout of the pull request's code. [T11.1]
- [ ] `SEC-CI-04` Jobs that use secrets (eval run against OpenAI, image push, Terraform) run only on pushes to protected branches or on manual runs started by the repo owner, not on pull requests from forks. [T11.1]
- [ ] `SEC-CI-05` A secret scanner runs on every push and pull request (tool choice **to be verified**). [T9.1, T12.1]
- [ ] `SEC-CI-06` Dependency vulnerability alerts and update pull requests are enabled for Go modules, the Java build, and npm. [T11.3]
- [ ] `SEC-CI-07` Container images are built from pinned, minimal base images and scanned for known vulnerabilities and embedded secrets before push to ECR (scanner choice **to be verified**). [T9.2, T11.4]

## observability

- [ ] `SEC-OBS-01` Logging configuration redacts credential headers, cookies, and URL query strings. [T1.5, T13.1]
- [ ] `SEC-OBS-02` Span attributes follow an allowlist; request bodies, prompts, and raw CDM content are not recorded. [T13.1, T7.9]
- [ ] `SEC-OBS-03` Prompt and answer logging is off by default outside local runs. [T13.1]
- [ ] `SEC-OBS-04` Grafana's default admin credentials are replaced from the secret store, and neither Grafana nor Prometheus is publicly exposed. [T13.2]

## repo

- [ ] `SEC-REP-01` GitHub secret scanning is enabled on the repository, and push protection is enabled if it is available for this repository type **(to be verified)**. [T12.1]
- [ ] `SEC-REP-02` A local pre push secret scan is installed and passes. [T12.1]
- [ ] `SEC-REP-03` `main` is protected and requires passing status checks before merge. [T12.3]
- [ ] `SEC-REP-04` No Space-Track payload, raw or partially redacted, exists anywhere in the repository, including test fixtures. [T12.2, T4.5]
- [ ] `SEC-REP-05` Screenshots, recordings, and docs added in the diff show no key, credential, internal host name, or raw CDM content. [T12.5, T6.6]
- [ ] `SEC-REP-06` A `SECURITY.md` explains how to report a vulnerability privately, once the private reporting option is confirmed **(to be verified)**. [T12.4]

## If a secret leaks

1. Revoke or rotate the secret at its provider immediately. This comes first, because removing it from history does not undo exposure.
2. Check the provider's usage and billing for activity since the leak.
3. Remove the secret from the working tree and add a scanner rule or ignore entry that would have caught it.
4. Decide separately whether rewriting history is worth it; a revoked secret in history is harmless, a live one is not.
