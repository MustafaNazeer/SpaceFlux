# SpaceFlux

SpaceFlux is designed to stream live satellite and space weather data through an event driven microservice pipeline, flag orbital and solar storm risks as the data arrives, and answer operator questions through an assistant grounded in the live data.

Designed around data from CelesTrak, NOAA SWPC, and NASA DONKI, which are public, and Space-Track, which requires an account. SpaceFlux is a demonstration of streaming system design on public data. It is not an operational collision avoidance or space weather warning service, and nothing it produces should be used for flight safety or any other safety decision.

## Status

**Work in progress. The ingest service and the risk engine are built; the query API, dashboard, and assistant are not.** Everything else in this README is planned and is marked that way. Features, benchmarks, and evaluation results are added here only once they exist in the code and can be reproduced from a committed script and dataset.

### Built: the ingest service

`ingest` is a Go service that polls two public feeds and publishes every new record to Kafka as a versioned JSON event:

* **CelesTrak GP orbital elements** for the `stations` group, polled every 2 hours 10 minutes and published to `raw.gp`, keyed by NORAD catalog number. CelesTrak updates GP data every 2 hours and asks clients to stop on any HTTP error rather than retry, so the poller refuses an interval under 2 hours and halts on any response other than 200 until it is restarted ([ADR 0004](docs/adr/0004-celestrak-polling-and-error-handling.md)).
* **Four NOAA SWPC products**, polled every 5 minutes with conditional requests and published to `raw.swpc`, keyed by product: the planetary Kp index, GOES X-ray flux, GOES integral proton flux, and SWPC alerts, watches, and warnings. Transient errors are retried with exponential backoff and jitter, never sooner than 1 minute apart ([ADR 0005](docs/adr/0005-swpc-polling-and-error-handling.md)).

The CelesTrak feed and each SWPC product run in their own poller, so one halted poller does not stop the others. Every event is validated against a JSON Schema file in [`schemas/`](schemas/) before it is published. A payload that fails decoding or validation goes to the topic's dead letter topic (`raw.gp.dlq` or `raw.swpc.dlq`) with the reason attached, and nothing is dropped silently. Records already published are skipped, so an unchanged feed does not produce new events. The service exposes liveness and readiness endpoints; readiness reports Kafka, publishing, and each feed separately. Topics, keys, and schemas are described in [docs/data/topics.md](docs/data/topics.md).

The local stack is a Docker Compose `core` profile with a single Kafka broker, a one shot topic creation container, and `ingest`. With the container limits in place and the current code, one 7 minute run with only the SWPC feed enabled measured Kafka and `ingest` together at a median of 482.3 MiB and a maximum of 590.0 MiB. That is a single run on one machine, and the CelesTrak path was not part of it; the method, raw samples, and caveats are in [docs/perf/local-memory.md](docs/perf/local-memory.md).

To build, test, and run it, see [docs/setup-guide.md](docs/setup-guide.md).

### Built: the risk engine

`risk-engine` is a Java 21 Spring Boot service that consumes `raw.gp` and `raw.swpc` and publishes everything it derives to the `alerts` topic. The contract is in [ADR 0007](docs/adr/0007-alerts-topic.md) and [docs/data/topics.md](docs/data/topics.md).

* **Space weather levels.** Each SWPC sample is mapped onto the G, R, and S levels of the NOAA Space Weather Scales, using the thresholds and plausibility checks in [docs/risk/space-weather-scales.md](docs/risk/space-weather-scales.md). A level is derived mechanically from one measurement and is never an official NOAA scale level or a forecast.
* **Close approach screening.** Orbits are propagated with SGP4 through Orekit, and each batch of element sets on `raw.gp` (the CelesTrak `stations` group) is screened for approaches within 5 km of the watchlist, currently the ISS, over a 7 day window. Each run publishes one event per approach and a run summary. The propagation is checked against published SGP4 verification cases and the screening against a dense brute force scan, as recorded in [docs/risk/orbital-conventions.md](docs/risk/orbital-conventions.md). A close approach here is two public element sets coming within 5 km, not an operational conjunction assessment.

Every event is checked against its topic schema before it is written, and a record that fails a schema or rule check goes to a dead letter topic with the reason attached. The risk engine has no container image yet, so it is not part of the Compose stack.

## What it is meant to do

1. Poll public orbital and space weather feeds and publish every payload as a versioned event on Kafka. (Built for CelesTrak and SWPC.)
2. Propagate orbits for a watchlist of satellites (starting with the ISS) and screen them for close approaches against the public catalog. (Built against the `stations` group.)
3. Map space weather observations onto the NOAA Space Weather Scales and raise alerts when a level is reached. (Built; alerts are events on the `alerts` topic.)
4. Serve the catalog, alerts, and space weather context to an operator dashboard over REST and GraphQL, with a live alert subscription.
5. Answer questions about current conditions through an LLM assistant that retrieves space weather reports, pulls live numbers through read only tools, and cites its sources.

## Components

| Component | Stack | State | Job |
|---|---|---|---|
| `ingest` | Go | Built | One poller per feed with its own interval and backoff; validates, deduplicates, and publishes raw events |
| `risk-engine` | Java, Spring Boot, Orekit | Built | Consumes orbital and space weather events, runs SGP4 propagation and close approach screening, applies storm rules, emits alerts |
| `query-api` | Java, Spring Boot | Planned | REST and GraphQL over MySQL and MongoDB; hosts the archiver that stores raw feed documents |
| `assistant` | Java, Spring AI | Planned | Retrieval over space weather text plus tool calls to `query-api` through a read only MCP tool server, with citations |
| `dashboard` | Angular | Planned | Read only operator console showing alerts, passes, and space weather context |

Supporting pieces that exist today: Kafka topics with dead letter topics, JSON Schema files for every event, and the local Compose stack. Planned: MySQL with Flyway migrations for the catalog and alerts, MongoDB Atlas for raw documents and vector search, OpenTelemetry tracing across Kafka, Prometheus and Grafana, Terraform for an on demand AWS environment (EKS, ECR, Secrets Manager), and GitHub Actions for CI.

The full design, including why each service scales differently and how delivery, errors, and security are handled, is in [docs/architecture.md](docs/architecture.md). Decisions with lasting weight are recorded as ADRs in [docs/adr/](docs/adr/).

## Planned

* A container image for the risk engine and its place in the Compose stack
* Query API with MySQL, the MongoDB archiver, and contract tests
* Angular dashboard with GraphQL and a live alert subscription
* Container images, CI, and a Terraform environment on EKS that is brought up for demos and torn down afterwards
* DONKI and Space-Track conjunction data ingest (conjunction data is used for flags only and is never republished)
* The retrieval grounded assistant, the MCP tool server, and a committed evaluation suite that gates assistant changes in CI
* Load tests with published, reproducible results

## Security

Secrets never live in images, logs, or commits. The ingest container runs as a non root user on a read only filesystem with all Linux capabilities dropped, and its health port is bound to localhost. The planned dashboard is read only, and all feed text will be treated as untrusted input to the planned assistant. See the [threat model](docs/security/threat-model.md) and the [hardening checklist](docs/security/hardening-checklist.md).

## Data sources

* [CelesTrak](https://celestrak.org/) GP orbital elements (ingested)
* [NOAA Space Weather Prediction Center](https://www.swpc.noaa.gov/) JSON products (ingested)
* [NASA DONKI](https://kauai.ccmc.gsfc.nasa.gov/DONKI/) space weather event database, through api.nasa.gov (planned)
* [Space-Track](https://www.space-track.org/) conjunction data messages, used under its user agreement and never republished (planned)

Each provider's published usage guidance, and how the poller follows it, is recorded with links in [docs/source/celestrak.md](docs/source/celestrak.md) and [docs/source/swpc.md](docs/source/swpc.md).

## License

MIT, see [LICENSE](LICENSE).
