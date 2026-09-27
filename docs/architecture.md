# SpaceFlux architecture

This document describes the planned architecture. Nothing here is built yet; each section will be updated as the code lands, and anything marked "to be decided" will be settled in an ADR under `docs/adr/` before the code that depends on it is written.

SpaceFlux is a demonstration of streaming system design on public data. It is not an operational collision avoidance or space weather warning service.

## Overview

```
CelesTrak  --+                          +--> risk-engine (Java, Orekit) --> alerts --+
SWPC       --+--> ingest (Go) --> Kafka |                                            +--> query-api (REST + GraphQL)
DONKI      --+    raw.* topics          +--> archiver --> MongoDB Atlas (raw docs)   |      +--> MySQL (catalog, alerts)
Space-Track--+                                                                       |      +--> Angular dashboard
                                                   assistant (Spring AI) --> MCP tools --> query-api
                                                   retrieval --> Atlas Vector Search (DONKI and SWPC text)
```

Data flows one way. Feed pollers publish raw events to Kafka. The risk engine and the archiver consume them independently. The risk engine publishes alerts to their own topic, which `query-api` consumes and stores. The dashboard and the assistant only ever read through `query-api`.

Kafka sits in the middle because the system has several independent producers (one per feed) and several independent consumers (risk engine, archiver) that must not block each other. It also gives replay: when screening logic changes, the risk engine can reprocess retained raw events instead of waiting for new data.

## Services

| Service | Language | Responsibility | Scales on |
|---|---|---|---|
| `ingest` | Go | Poll feeds, validate, deduplicate, publish to `raw.*` topics | Number of feeds (IO bound) |
| `risk-engine` | Java, Spring Boot, Orekit | Consume `raw.gp` and `raw.swpc`, propagate orbits, screen close approaches for the watchlist, apply storm rules, emit `alerts` | Partitions and consumer lag (CPU bound) |
| `query-api` | Java, Spring Boot | REST and GraphQL, owns MySQL, reads MongoDB, hosts the archiver consumer | Request rate |
| `assistant` | Java, Spring AI | Retrieval plus tool use over MCP, citation formatting | LLM latency |
| `dashboard` | Angular | Read only operator console | Static hosting |

### Why these are separate deployables

Each service is bounded by a different resource, which is the reason they are split rather than merged:

* **`ingest` is IO bound.** It is expected to spend most of its time waiting on HTTP responses. Go fits this well: each feed gets its own goroutine with its own rate limiter, exponential backoff with jitter, and deduplication, and a shared context cancels every poller cleanly on shutdown. Adding a feed adds a poller, not a replica.
* **`risk-engine` is CPU bound.** Orbit propagation and pairwise screening are compute heavy. It scales horizontally by adding consumers to its consumer group, up to the partition count, and in Kubernetes a horizontal autoscaler is planned to react to consumer lag rather than CPU alone. It is written in Java because Orekit, a mature open source astrodynamics library, is Java.
* **`query-api` is request bound.** Its load follows dashboard and assistant traffic, not feed volume, so it scales on request rate independently of the pipeline.
* **`assistant` is LLM latency bound.** A single answer can wait seconds on the model. Isolating it keeps that latency, and its failure modes, away from the pipeline and the API.
* **`dashboard`** is static assets and needs no compute of its own.

The archiver starts inside `query-api` to keep the local memory footprint small. Splitting it into its own deployable is a decision to revisit only if measured consumer lag justifies it.

The repository layout and the Maven multi-module build for the Java services are described in [ADR 0001](adr/0001-repo-layout-and-build-tool.md). Language, framework, and broker versions are pinned when each component is introduced, after checking current stable releases; the Go Kafka client, the JSON Schema draft, and the broker image are recorded in [ADR 0003](adr/0003-go-kafka-client-and-schema-validator.md).

## Topics and contracts

* **Topics:** `raw.gp` (orbital elements), `raw.swpc` (space weather products), `raw.donki` (space weather event reports), `raw.cdm` (conjunction data messages), and `alerts`. Each has a matching `.dlq` dead letter topic.
* **Schemas:** events are versioned JSON with one schema per topic, validated at the producer and again at the consumer. Schemas are JSON Schema files in the repository, with compatibility checked in CI; see [ADR 0002](adr/0002-event-schemas-and-serialization.md).
* **Keys:** the NORAD catalog ID for orbital data and the feed's product ID for space weather data. Events with the same key are written to the same partition, and Kafka guarantees a consumer reads a partition in the order it was written, so events for one object are processed in order as long as the partition count stays fixed (adding partitions remaps keys), the producer keeps idempotence enabled (the default unless conflicting settings are made), and each consumer handles a partition's events one at a time.

## Delivery semantics

Delivery is at least once. A consumer may see the same event more than once after a rebalance or a retry, so every consumer is idempotent: it deduplicates on the feed's own epoch and identifier fields rather than on anything the pipeline generates. The producer side deduplicates too, so an unchanged feed response does not produce a new event.

## Risk logic

This section describes the design only. Numeric thresholds are not listed here; they will be taken from published sources and recorded with citations under `docs/risk/` before the code that uses them is written.

### Orbital

* Orbits are propagated from GP elements with SGP4 through Orekit.
* Close approach screening runs for a **watchlist** (starting with the ISS) against the public catalog, not all objects against all objects.
* A perigee and apogee overlap prefilter discards pairs whose orbits cannot come close. Only surviving pairs get finer propagation.
* Each flagged approach reports the time of closest approach, the miss distance, and the relative speed.
* The propagation and screening code is written test first and checked against published SGP4 verification cases.

### Space weather

* Flags map to the NOAA Space Weather Scales: the G scale from the planetary Kp index and the R scale from GOES X-ray flux. Whether the S scale (from GOES proton flux) is included depends on which SWPC products the ingest service consumes, which is to be decided.
* Scale thresholds will be taken from NOAA's published scale table (https://www.spaceweather.gov/noaa-scales-explanation) and recorded with citations under `docs/risk/` before the storm rules are written.

### Conjunction data

Space-Track conjunction data messages are planned as a second signal: they raise flags and serve as a cross check on the engine's own screening. Raw conjunction data is never republished on any public surface, and Space-Track's user agreement will be reviewed in an ADR before any code that touches it lands.

## Error handling

* **Polling:** pollers back off exponentially with jitter and respect each provider's published polling guidance. The exact cadence per feed will be documented next to the ingest code once verified against the provider's own documentation.
* **Malformed payloads** go to the topic's dead letter topic with the failure reason attached. Nothing is dropped silently.
* **Probes:** every service exposes liveness and readiness probes. Readiness fails when a required dependency is down.
* **Stale data:** the dashboard shows a per feed stale data banner when a feed's newest event is older than its expected cadence.
* **Absence is an error, not a value.** A missing feed, an empty catalog, or a missing evaluation file raises a visible warning instead of falling back to a plausible default.

## Security posture

* **Secrets** come from AWS Secrets Manager in the cloud environment and from a git ignored `.env` file locally. No secret ever appears in an image, a log, or a commit.
* **Read only by default.** The dashboard is read only. The only write path is alert acknowledgement, which sits behind authentication; the exact scheme is to be decided.
* **Untrusted text.** The assistant treats every piece of feed text and every retrieved document as untrusted input, since feed text is a prompt injection surface. The tools it can call are read only, validate their parameters, and are rate limited.
* **Spend limits.** The LLM API account will have a monthly spending limit set before the first call (whether that limit blocks requests or only notifies is to be verified), and the evaluation suite will use a small fixed question set so CI runs stay cheap.

The full threat model is in [security/threat-model.md](security/threat-model.md) and the hardening checklist in [security/hardening-checklist.md](security/hardening-checklist.md).

## Observability

* **Tracing:** OpenTelemetry trace context propagates through Kafka message headers, so a single trace spans the poller, the topic, the risk engine, and the resulting alert.
* **Metrics:** Prometheus scrapes consumer lag, feed freshness, alert rate, and assistant evaluation scores.
* **Dashboards:** Grafana dashboards will be committed to the repo as JSON.

## Assistant

The assistant answers questions about current orbital and space weather conditions. It retrieves relevant passages from DONKI and SWPC text through Atlas Vector Search, pulls live numbers by calling `query-api` through a read only MCP tool server, assembles a bounded context from both, and answers with citations. A committed evaluation set (groundedness, citation correctness, refusal on out of scope questions, and tool call correctness) is planned to gate assistant changes in CI. Pass thresholds will be set from a measured baseline, not chosen in advance.

## Data sources

| Feed | Access | Used for |
|---|---|---|
| [CelesTrak](https://celestrak.org/) GP elements | Public, no account | Orbital elements for the catalog and watchlist |
| [NOAA SWPC](https://www.swpc.noaa.gov/) JSON products | Public, no account | Kp, solar wind, X-ray flux, and alerts; exact products to be decided |
| [NASA DONKI](https://kauai.ccmc.gsfc.nasa.gov/DONKI/) | api.nasa.gov key | Coronal mass ejection and flare history; the retrieval corpus |
| [Space-Track](https://www.space-track.org/) conjunction data messages | Account required | Conjunction flags and cross checking; never republished |

## Deployment

* **Local:** Docker Compose with profiles, so only the services being worked on run at once. The memory budget of the core profile will be measured and recorded rather than assumed.
* **Cloud:** AWS, provisioned by Terraform (VPC, EKS, ECR, Secrets Manager). The environment is on demand: it is brought up for demos and destroyed afterwards to control cost. A recorded walkthrough will keep the project legible while the cluster is down.
* **CI:** GitHub Actions builds and tests every service, builds and pushes images, runs a Terraform plan on pull requests, and deploys on demand.

## Testing

* Go: table driven unit tests and integration tests against a Kafka Testcontainer.
* Java: JUnit 5 with Testcontainers for Kafka, MySQL, and MongoDB; contract tests on every REST endpoint; GraphQL schema tests.
* Angular: Jest component tests.
* Fixtures are recorded real feed payloads with their provenance and capture date, not hand made data.
