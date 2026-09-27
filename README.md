# SpaceFlux

SpaceFlux is designed to stream live satellite and space weather data through an event driven microservice pipeline, flag orbital and solar storm risks as the data arrives, and answer operator questions through an assistant grounded in the live data.

Designed around data from CelesTrak, NOAA SWPC, and NASA DONKI, which are public, and Space-Track, which requires an account. SpaceFlux is a demonstration of streaming system design on public data. It is not an operational collision avoidance or space weather warning service, and nothing it produces should be used for flight safety or any other safety decision.

## Status

**Work in progress. Nothing below is built yet.** This README describes the planned system. Features, benchmarks, and evaluation results will be added here only once they exist in the code and can be reproduced from a committed script and dataset.

## What it is meant to do

1. Poll public orbital and space weather feeds and publish every payload as a versioned event on Kafka.
2. Propagate orbits for a watchlist of satellites (starting with the ISS) and screen them for close approaches against the public catalog.
3. Map space weather observations onto the NOAA Space Weather Scales and raise alerts when a level is reached.
4. Serve the catalog, alerts, and space weather context to an operator dashboard over REST and GraphQL, with a live alert subscription.
5. Answer questions about current conditions through an LLM assistant that retrieves space weather reports, pulls live numbers through read only tools, and cites its sources.

## Planned components

| Component | Stack | Job |
|---|---|---|
| `ingest` | Go | One poller per feed with its own rate limiter and backoff; validates, deduplicates, and publishes raw events |
| `risk-engine` | Java, Spring Boot, Orekit | Consumes orbital and space weather events, runs SGP4 propagation and close approach screening, applies storm rules, emits alerts |
| `query-api` | Java, Spring Boot | REST and GraphQL over MySQL and MongoDB; hosts the archiver that stores raw feed documents |
| `assistant` | Java, Spring AI | Retrieval over space weather text plus tool calls to `query-api` through a read only MCP tool server, with citations |
| `dashboard` | Angular | Read only operator console showing alerts, passes, and space weather context |

Supporting pieces: Kafka topics with dead letter topics, MySQL with Flyway migrations for the catalog and alerts, MongoDB Atlas for raw documents and vector search, OpenTelemetry tracing across Kafka, Prometheus and Grafana, Terraform for an on demand AWS environment (EKS, ECR, Secrets Manager), and GitHub Actions for CI.

The full design, including why each service scales differently and how delivery, errors, and security are handled, is in [docs/architecture.md](docs/architecture.md). Decisions with lasting weight will be recorded as ADRs under `docs/adr/` as they are made.

## Planned

* Go ingest for CelesTrak and SWPC into Kafka, with a local Docker Compose stack split into profiles
* Risk engine with SGP4 propagation built test first against published verification cases
* Query API with MySQL, the MongoDB archiver, and contract tests
* Angular dashboard with GraphQL and a live alert subscription
* Container images, CI, and a Terraform environment on EKS that is brought up for demos and torn down afterwards
* DONKI and Space-Track conjunction data ingest (conjunction data is used for flags only and is never republished)
* The retrieval grounded assistant, the MCP tool server, and a committed evaluation suite that gates assistant changes in CI
* Load tests with published, reproducible results

## Security

The dashboard is read only, secrets never live in images, logs, or commits, and all feed text is treated as untrusted input to the assistant. See the [threat model](docs/security/threat-model.md) and the [hardening checklist](docs/security/hardening-checklist.md).

## Data sources

* [CelesTrak](https://celestrak.org/) GP orbital elements
* [NOAA Space Weather Prediction Center](https://www.swpc.noaa.gov/) JSON products
* [NASA DONKI](https://kauai.ccmc.gsfc.nasa.gov/DONKI/) space weather event database, through api.nasa.gov
* [Space-Track](https://www.space-track.org/) conjunction data messages, used under its user agreement and never republished

Each provider's usage guidance will be respected; polling cadences will be documented alongside the ingest code once verified against the provider's own documentation.

## License

MIT, see [LICENSE](LICENSE).
