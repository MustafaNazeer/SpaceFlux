# SpaceFlux

SpaceFlux is designed to stream live satellite and space weather data through an event driven microservice pipeline, flag orbital and solar storm risks as the data arrives, and answer operator questions through an assistant grounded in the live data.

Designed around data from CelesTrak, NOAA SWPC, and NASA DONKI, which are public, and Space-Track, which requires an account. SpaceFlux is a demonstration of streaming system design on public data. It is not an operational collision avoidance or space weather warning service, and nothing it produces should be used for flight safety or any other safety decision.

## Status

**Work in progress. The ingest service, the risk engine, and the REST side of the query API are built; GraphQL, the MongoDB archive, the dashboard, and the assistant are not.** Everything else in this README is planned and is marked that way. Features, benchmarks, and evaluation results are added here only once they exist in the code and can be reproduced from a committed script and dataset.

### Built: the ingest service

`ingest` is a Go service that polls two public feeds and publishes every new record to Kafka as a versioned JSON event:

* **CelesTrak GP orbital elements** for the `stations` group, polled every 2 hours 10 minutes and published to `raw.gp`, keyed by NORAD catalog number. CelesTrak updates GP data every 2 hours and asks clients to stop on any HTTP error rather than retry, so the poller refuses an interval under 2 hours and halts on any response other than 200 until it is restarted ([ADR 0004](docs/adr/0004-celestrak-polling-and-error-handling.md)).
* **Four NOAA SWPC products**, polled every 5 minutes with conditional requests and published to `raw.swpc`, keyed by product: the planetary Kp index, GOES X-ray flux, GOES integral proton flux, and SWPC alerts, watches, and warnings. Transient errors are retried with exponential backoff and jitter, never sooner than 1 minute apart ([ADR 0005](docs/adr/0005-swpc-polling-and-error-handling.md)).

The CelesTrak feed and each SWPC product run in their own poller, so one halted poller does not stop the others. Every event is validated against a JSON Schema file in [`schemas/`](schemas/) before it is published. A payload that fails decoding or validation goes to the topic's dead letter topic (`raw.gp.dlq` or `raw.swpc.dlq`) with the reason attached, and nothing is dropped silently. Records already published are skipped, so an unchanged feed does not produce new events. The service exposes liveness and readiness endpoints; readiness reports Kafka, publishing, and each feed separately. Topics, keys, and schemas are described in [docs/data/topics.md](docs/data/topics.md).

### Built: the risk engine

`risk-engine` is a Java 21 Spring Boot service that consumes `raw.gp` and `raw.swpc` and publishes everything it derives to the `alerts` topic. The contract is in [ADR 0007](docs/adr/0007-alerts-topic.md) and [docs/data/topics.md](docs/data/topics.md).

* **Space weather levels.** Each SWPC sample is mapped onto the G, R, and S levels of the NOAA Space Weather Scales, using the thresholds and plausibility checks in [docs/risk/space-weather-scales.md](docs/risk/space-weather-scales.md). A level is derived mechanically from one measurement and is never an official NOAA scale level or a forecast.
* **Close approach screening.** Orbits are propagated with SGP4 through Orekit, and each batch of element sets on `raw.gp` (the CelesTrak `stations` group) is screened for approaches within 5 km of the watchlist, currently the ISS, over a 7 day window. Each run publishes one event per approach and a run summary. The propagation is checked against published SGP4 verification cases and the screening against a dense brute force scan, as recorded in [docs/risk/orbital-conventions.md](docs/risk/orbital-conventions.md). A close approach here is two public element sets coming within 5 km, not an operational conjunction assessment. Miss distances from public element sets are uncertain by kilometres, so an approach near 5 km and one just outside it cannot be told apart (Sections 4 and 5 of [the orbital conventions](docs/risk/orbital-conventions.md)).

Every event is checked against its topic schema before it is written, and a record that fails a schema or rule check goes to a dead letter topic with the reason attached. The risk engine runs in the local Compose stack next to `ingest`, from a container image built on a distroless Java 21 base that runs as a non root user on a read only filesystem.

### Built: the query API and MySQL

`query-api` is a Java 21 Spring Boot service that owns a MySQL 8.4 database and serves it over REST. The stack is set out in [ADR 0010](docs/adr/0010-query-api-stack.md), and the tables, users, and indexes in [docs/data/mysql-schema.md](docs/data/mysql-schema.md) and [docs/data/indexes.md](docs/data/indexes.md).

* **Storing the pipeline's output.** Two Kafka consumers, each in its own consumer group, write to MySQL. The alerts consumer stores every `alerts` event with its exact text, the rows of its kind, and the current state of each space weather series. The catalog consumer reads `raw.gp` independently of the risk engine and keeps the newest element set per object. Both validate every record against its schema, send failures to the topic's dead letter topic, and commit a Kafka offset only after the database transaction for that record has committed, so a redelivered record changes nothing.
* **Read endpoints.** Current space weather for each scale, space weather history, the current screening run with its close approaches, one alert by ID, one catalog object, and the watchlist. They need no account, page by key rather than offset, and answer errors as RFC 9457 problem details. The contract is in [docs/api/rest.md](docs/api/rest.md); every endpoint has a contract test against a migrated database, and every query's index is proven with `EXPLAIN`. A list of the newest alerts and a paged catalog are not built yet.
* **Alert acknowledgement**, the only write path in the system ([ADR 0009](docs/adr/0009-alert-acknowledgement-auth.md)). One operator account signs in through form login to a server side session whose cookie page script cannot read, with CSRF protection on every write. The password is stored only as a bcrypt hash, at the cost measured nearest one second per check on my laptop ([docs/perf/bcrypt-cost.md](docs/perf/bcrypt-cost.md)), and failed logins are slowed by backoff rather than locking the account. Acknowledgements are append only rows: the database user behind the HTTP handlers can insert into that one table and write nothing else anywhere. Anonymous viewers see whether and when an alert was acknowledged, never the note or who acknowledged it.
* **Migrations.** The schema is built by Flyway migrations from a separate one shot `migrate` container, built from the `db-migrate` module, that runs as the migration user and exits before `query-api` starts, so the running service never holds a credential that can change the schema. `query-api` uses two connection pools, one per database user, over TLS, and refuses to start if either user's grants differ from the reviewed list.

Kafka validation and dead lettering are shared with the risk engine through a small library module, `kafka-contracts` ([ADR 0011](docs/adr/0011-shared-event-contracts-module.md)).

### The local stack

The local stack is a Docker Compose `core` profile with a single Kafka broker, a one shot topic creation container, `ingest`, `risk-engine`, MySQL, the one shot `migrate` container, and `query-api`. MySQL publishes no port and sits on an internal network that only the migration container and `query-api` join; `query-api` is published on `127.0.0.1:8081` only.

Its memory has been measured as each service joined it, each time in a single short run, so the figures are observations rather than a distribution; the method, raw samples, and caveats of every run are in [docs/perf/local-memory.md](docs/perf/local-memory.md).

* With Kafka and `ingest` only, and the container limits in place, one 7 minute run with only the SWPC feed enabled measured the two together at a median of 482.3 MiB and a maximum of 590.0 MiB. The CelesTrak path was not part of it.
* With the risk engine added, one 6 minute run on a second machine, with a screening run inside the window (known from a one off read of the live topics, not committed), measured the three containers together at a median of 638.3 MiB and a maximum of 837.0 MiB, the risk engine itself at a maximum of 176.3 MiB against its 512 MiB limit. That run screened a 22 object catalog, so it says nothing yet about a larger one ([run with the risk engine](docs/perf/local-memory.md#run-with-the-risk-engine)).
* With MySQL and `query-api` added, one 7 minute run back on the first machine, with both feeds enabled, measured the five containers together at a median of 1479.1 MiB and a maximum of 1613.4 MiB, within the roughly 3 GiB I budget for the profile on that 7.1 GiB machine, though that budget was written for one JVM service and the profile now runs two. MySQL peaked at 466.6 MiB, 91 percent of its 512 MiB limit, and `query-api` at 224.5 MiB. Nobody signed in during the run, and whether a screening run fell inside it is not known ([run with MySQL and the query API](docs/perf/local-memory.md#run-with-mysql-and-the-query-api)). I then trimmed two MySQL performance schema tables that nothing reads, which lowered MySQL's anonymous memory, read once on an idle server running alone, from 369.2 to 284.8 MiB; the full profile has not been measured again with the trim.

To build, test, and run it, see [docs/setup-guide.md](docs/setup-guide.md).

## What it is meant to do

1. Poll public orbital and space weather feeds and publish every payload as a versioned event on Kafka. (Built for CelesTrak and SWPC.)
2. Propagate orbits for a watchlist of satellites (starting with the ISS) and screen them for close approaches against the public catalog. (Built against the `stations` group.)
3. Map space weather observations onto the NOAA Space Weather Scales and publish each series' derived state (a level, none, no data, or ended) as an event on the `alerts` topic when it changes, is revised, or is refreshed. (Built.)
4. Serve the catalog, alerts, and space weather context to an operator dashboard over REST and GraphQL, with a live alert subscription, and let one operator acknowledge alerts. (The REST read endpoints listed above and acknowledgement are built; GraphQL and the subscription are not.)
5. Answer questions about current conditions through an LLM assistant that retrieves space weather reports, pulls live numbers through read only tools, and cites its sources.

## Components

| Component | Stack | State | Job |
|---|---|---|---|
| `ingest` | Go | Built | One poller per feed with its own interval and backoff; validates, deduplicates, and publishes raw events |
| `risk-engine` | Java, Spring Boot, Orekit | Built | Consumes orbital and space weather events, runs SGP4 propagation and close approach screening, applies storm rules, emits alerts |
| `query-api` | Java, Spring Boot | Built (REST); GraphQL and the archiver planned | Stores alerts and the catalog in MySQL from Kafka, serves them over REST, and holds alert acknowledgement; GraphQL, reads from MongoDB, and the archiver that stores raw feed documents are planned |
| `db-migrate` | Java, Flyway | Built | One shot container that applies the MySQL migrations as the migration user, then exits |
| `assistant` | Java, Spring AI | Planned | Retrieval over space weather text plus tool calls to `query-api` through a read only MCP tool server, with citations |
| `dashboard` | Angular | Planned | Read only operator console showing alerts, passes, and space weather context |

Supporting pieces that exist today: Kafka topics with dead letter topics, JSON Schema files for every event, MySQL with Flyway migrations for the catalog, alerts, and acknowledgements, and the local Compose stack. Planned: MongoDB Atlas for raw documents and vector search, OpenTelemetry tracing across Kafka, Prometheus and Grafana, Terraform for an on demand AWS environment (EKS, ECR, Secrets Manager), and GitHub Actions for CI.

The full design, including why each service scales differently and how delivery, errors, and security are handled, is in [docs/architecture.md](docs/architecture.md). Decisions with lasting weight are recorded as ADRs in [docs/adr/](docs/adr/).

## Planned

* The MongoDB archiver for raw feed documents, inside `query-api`
* GraphQL on `query-api` and an Angular dashboard with a live alert subscription
* CI that builds, tests, and pushes the container images, and a Terraform environment on EKS that is brought up for demos and torn down afterwards
* DONKI and Space-Track conjunction data ingest (conjunction data is used for flags only and is never republished)
* The retrieval grounded assistant, the MCP tool server, and a committed evaluation suite that gates assistant changes in CI
* Load tests with published, reproducible results

## Security

Secrets never live in images, logs, or commits. The `ingest`, `risk-engine`, `migrate`, and `query-api` containers run as a non root user on a read only filesystem with all Linux capabilities dropped; the ingest health port and the query API are bound to localhost, and the risk engine publishes no ports. MySQL publishes no port, accepts network connections only over TLS, and gives each purpose its own user: migrations, the Kafka consumers, and the HTTP handlers, whose only write is appending an acknowledgement. Every read endpoint is open and read only; acknowledging an alert needs the operator's session and a CSRF token ([ADR 0009](docs/adr/0009-alert-acknowledgement-auth.md)). The planned dashboard is read only apart from acknowledgement, and all feed text will be treated as untrusted input to the planned assistant. See the [threat model](docs/security/threat-model.md) and the [hardening checklist](docs/security/hardening-checklist.md).

## Data sources

* [CelesTrak](https://celestrak.org/) GP orbital elements (ingested)
* [NOAA Space Weather Prediction Center](https://www.swpc.noaa.gov/) JSON products (ingested)
* [NASA DONKI](https://kauai.ccmc.gsfc.nasa.gov/DONKI/) space weather event database, through api.nasa.gov (planned)
* [Space-Track](https://www.space-track.org/) conjunction data messages, used under its user agreement and never republished (planned)

Each provider's published usage guidance, and how the poller follows it, is recorded with links in [docs/source/celestrak.md](docs/source/celestrak.md) and [docs/source/swpc.md](docs/source/swpc.md).

## License

MIT, see [LICENSE](LICENSE).
