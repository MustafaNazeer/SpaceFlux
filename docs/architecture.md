# SpaceFlux architecture

This document describes the architecture of SpaceFlux. Two parts are built so far. The ingest service polls CelesTrak and NOAA SWPC, validates every record against a JSON Schema file, and publishes to the `raw.gp` and `raw.swpc` Kafka topics and their dead letter topics. `risk-engine` consumes both topics: it derives G, R, and S levels from `raw.swpc` and publishes them as `space_weather_level` events on the `alerts` topic, and it screens each batch of element sets on `raw.gp` for close approaches to a watchlist and publishes `close_approach` and `screening_run` events there, with dead letters on `raw.gp.dlq`, `raw.swpc.dlq`, and `alerts.dlq`. Both run locally in a Docker Compose stack alongside a single Kafka broker. Everything else is planned; each section is updated as the code lands, and anything marked "to be decided" is settled in an ADR under `docs/adr/` before the code that depends on it is written.

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

* **`ingest` is IO bound.** It spends most of its time waiting between polls and on HTTP responses. Go fits this well: each feed gets its own goroutine with its own interval, backoff with jitter, and deduplication, and a shared context cancels every poller cleanly on shutdown. Adding a feed adds a poller, not a replica. The built service is described in [Ingest](#ingest) below.
* **`risk-engine` is CPU bound.** Orbit propagation and pairwise screening are compute heavy. It is meant to scale horizontally by adding consumers to its consumer group, up to the partition count, with a horizontal autoscaler in Kubernetes reacting to consumer lag rather than CPU alone; the screening consumer today assumes a single instance reads every `raw.gp` partition (see [Orbital](#orbital)), so scaling it out needs a design change first. It is written in Java because Orekit, a mature open source astrodynamics library, is Java.
* **`query-api` is request bound.** Its load follows dashboard and assistant traffic, not feed volume, so it scales on request rate independently of the pipeline.
* **`assistant` is LLM latency bound.** A single answer can wait seconds on the model. Isolating it keeps that latency, and its failure modes, away from the pipeline and the API.
* **`dashboard`** is static assets and needs no compute of its own.

The archiver starts inside `query-api` to keep the local memory footprint small. Splitting it into its own deployable is a decision to revisit only if measured consumer lag justifies it.

The repository layout and the Maven multi-module build for the Java services are described in [ADR 0001](adr/0001-repo-layout-and-build-tool.md). Language, framework, and broker versions are pinned when each component is introduced, after checking current stable releases; the Go Kafka client, the JSON Schema draft, and the broker image are recorded in [ADR 0003](adr/0003-go-kafka-client-and-schema-validator.md).

## Ingest

`ingest` is built. It is a single Go binary that runs one poller per feed product, five in all, each in its own goroutine:

| Poller | Source | Interval | Topic | Key |
|---|---|---|---|---|
| `celestrak` | CelesTrak GP, `stations` group, JSON format | 2 hours 10 minutes (floor 2 hours) | `raw.gp` | NORAD catalog number |
| `swpc.kp` | SWPC planetary Kp index | 5 minutes (floor 1 minute) | `raw.swpc` | `swpc.kp` |
| `swpc.goes.xrays` | SWPC GOES X-ray flux, 6 hour file | 5 minutes (floor 1 minute) | `raw.swpc` | `swpc.goes.xrays` |
| `swpc.goes.protons` | SWPC GOES integral proton flux, 6 hour file | 5 minutes (floor 1 minute) | `raw.swpc` | `swpc.goes.protons` |
| `swpc.alerts` | SWPC alerts, watches, and warnings | 5 minutes (floor 1 minute) | `raw.swpc` | `swpc.alerts` |

The service refuses to start with an interval below its floor. The CelesTrak floor is CelesTrak's documented 2 hour update cadence; the SWPC floor is the 1 minute retry spacing in the NWS appropriate use notice. Why each source is handled the way it is, with links to the providers' own pages, is in [docs/source/celestrak.md](source/celestrak.md) and [docs/source/swpc.md](source/swpc.md).

One poll cycle:

1. **Fetch** over HTTPS with a descriptive `User-Agent`, a 60 second request timeout, and an 8 MiB limit on the response body. SWPC requests send `If-None-Match` with the last `ETag` when one is held, and a 304 reply publishes nothing.
2. **Decode** the JSON array and split it into one event per record, wrapped in an envelope that records the source, the URL requested, the fetch time, and for SWPC the product.
3. **Validate** each event against its schema file in `schemas/`, loaded at startup. A record that fails goes to the dead letter topic on its own, and the rest of the response is still published.
4. **Deduplicate.** For CelesTrak, an element set is published only if its (`NORAD_CAT_ID`, `EPOCH`) pair is new for that object. For SWPC, whose files are sliding windows over recent data, a record is published only if its identity was not in the previous successful response for the same product. Both are held in memory, so the first poll after a restart republishes; consumers deduplicate on the same identities.
5. **Publish** to Kafka with the franz-go client ([ADR 0003](adr/0003-go-kafka-client-and-schema-validator.md)). A record counts as published only after the broker acknowledges it. A failed publish is retried with backoff on the same body, without a new request to the provider.

Errors are handled per provider, because the two providers ask for different things:

* **CelesTrak** asks clients to stop on any response other than HTTP 200, and counts some error responses toward a limit after which it firewalls the client. So any non 200 response halts the CelesTrak poller until the process is restarted. Only failures that never reach CelesTrak (DNS, connection, TLS, or a timeout before a status line) are retried, with exponential backoff and full jitter. See [ADR 0004](adr/0004-celestrak-polling-and-error-handling.md).
* **SWPC** publishes no stop rule, only a request to space retries at least a minute apart. So a 403 or 404 halts only that product's poller (the client may be blocked, or the file retired), and every other failure is retried with exponential backoff and a random delay between 1 minute and the current ceiling. See [ADR 0005](adr/0005-swpc-polling-and-error-handling.md).
* **Bad data after a 200**, whether a body cut off in transit or one that fails decoding or validation, is dead lettered with the reason and the poller waits its normal interval, since asking again would not change the data.

A halted poller fails readiness but not liveness, so an orchestrator reports the problem instead of restarting the service into a fresh round of requests. The service listens on port 8080 for two endpoints:

* `GET /healthz` always answers 200 while the process runs.
* `GET /readyz` answers 200 only when Kafka answers a ping, no poller is halted, and no feed's latest publish attempt has failed; otherwise 503. The JSON body has a `kafka` field, a `publish` field, and one field per poller that reads `running` or `halted` with the cause.

Configuration comes from environment variables. `KAFKA_BROKERS` is required; `INGEST_FEEDS` selects `celestrak`, `swpc`, or both (the default); `CELESTRAK_GROUP`, `CELESTRAK_INTERVAL`, and `SWPC_INTERVAL` override the defaults above. The provider base URLs are fixed to `https://celestrak.org` and `https://services.swpc.noaa.gov`, and any other value is rejected at startup.

Locally, `ingest` runs in the Docker Compose `core` profile next to a single node Kafka broker and a one shot container that creates the topics (`raw.gp`, `raw.swpc`, `alerts`, and their dead letter topics). The ingest image is a static binary on a distroless base, run as a non root user on a read only filesystem with every Linux capability dropped. Memory and process limits for both containers were set from a measurement recorded in [docs/perf/local-memory.md](perf/local-memory.md). How to build, test, and run the stack is in [docs/setup-guide.md](setup-guide.md).

## Topics and contracts

* **Topics in use:** `raw.gp` (CelesTrak GP element sets, one event per object and epoch) and `raw.swpc` (SWPC records, one event per record of one product), each with a matching `.dlq` dead letter topic that shares one envelope schema. Every field, key, and deduplication identity is documented in [docs/data/topics.md](data/topics.md).
* **`alerts`:** the risk engine's results, with its own `alerts.dlq`. One event is a change of state of one derived space weather series, a correction of one of its earlier samples or intervals, or a refresh of its current state (`space_weather_level`; a refresh is published when newer records arrive without changing the state, and by a timer fallback when nothing else went out for the series, and only while the series is in a level or "none"), one close approach found by a screening run (`close_approach`), or the summary of what one screening run covered and did not screen (`screening_run`). The schema is [`schemas/alerts/v1.schema.json`](../schemas/alerts/v1.schema.json); the design and its alternatives are in [ADR 0007](adr/0007-alerts-topic.md), and every field, key, and identity is in [docs/data/topics.md](data/topics.md#alerts). `risk-engine` produces all three kinds; nothing consumes `alerts` yet. The local Compose stack creates both `alerts` and `alerts.dlq`.
* **Planned topics:** `raw.donki` (space weather event reports) and `raw.cdm` (conjunction data messages), each with its own `.dlq` topic.
* **Schemas:** events are versioned JSON with one schema per topic and major version, written in JSON Schema draft 2020-12 and kept under `schemas/`; see [ADR 0002](adr/0002-event-schemas-and-serialization.md). `ingest` validates every event, and every dead letter, before publishing. `risk-engine` validates every `raw.gp` and `raw.swpc` event it reads, and every `alerts` event and dead letter it writes, against the same files, with the Java validator chosen in [ADR 0008](adr/0008-java-kafka-client-and-schema-validator.md). A compatibility check in CI is planned with the CI pipeline.
* **Keys:** on `raw.gp`, the NORAD catalog ID; on `raw.swpc`, a fixed product ID (`swpc.kp`, `swpc.goes.xrays`, `swpc.goes.protons`, `swpc.alerts`). On `alerts`, space weather events are keyed by scale (`space_weather.G`, `space_weather.R`, `space_weather.S`), so every event of one scale, across GOES satellites, is read in the order it was produced; close approaches and run summaries are keyed by the screening run's `run_id`, so a run's approaches are read before its summary. Events with the same key are written to the same partition, and Kafka guarantees a consumer reads a partition in the order it was written, so events with one key are processed in order as long as the partition count stays fixed (adding partitions remaps keys), the producer keeps idempotence enabled (the default unless conflicting settings are made), and each consumer handles a partition's events one at a time. Locally every topic has one partition, a provisional value until consumer lag has been measured.

## Delivery semantics

Delivery is at least once. A consumer may see the same event more than once after a rebalance or a retry, so every consumer has to be idempotent. Consumers of the raw topics deduplicate on the feed's own epoch and identifier fields rather than on anything the pipeline generates; for `swpc.kp` a consumer keeps the latest record for each `time_tag`, because SWPC may revise Kp values, which it calls estimated ([ADR 0006](adr/0006-kp-record-identity.md)). Consumers of `alerts` deduplicate on `event_id`, which is built only from fields of the event itself, never from a random value or a Kafka offset, so a producer retry writes the same identity for the same event, and so does a reprocessing run for every event except the timer refreshes, which depend on when the clock ticked ([ADR 0007](adr/0007-alerts-topic.md)). `ingest` deduplicates too, so an unchanged feed response does not produce a new event; its dedupe state is held in memory, so a restart can republish, which consumer side deduplication absorbs.

## Risk logic

This section describes the design and says which parts are built. Numeric thresholds, windows, and the published sources they come from are recorded with citations in [docs/risk/orbital-conventions.md](risk/orbital-conventions.md) and [docs/risk/space-weather-scales.md](risk/space-weather-scales.md) rather than repeated here. Both sides are built and run against Kafka: `risk-engine` consumes `raw.gp` for the orbital side and `raw.swpc` for the space weather side, and publishes every result to `alerts`.

### Orbital

* Orbits are propagated from GP elements with SGP4 through Orekit. Built, and checked against the published SGP4 verification cases.
* Close approach screening runs for a **watchlist** against the public catalog, not all objects against all objects. The watchlist is a committed file, [`risk-engine/src/main/resources/screening/watchlist.json`](../risk-engine/src/main/resources/screening/watchlist.json), and holds only the ISS today. The catalog is the newest element set of every object read from `raw.gp`, which carries the CelesTrak `stations` group; element sets more than 30 days older than the window start are dropped.
* A radial band prefilter, built from each object's sampled range of distance from Earth's center over the window, discards pairs whose orbits cannot come within the report distance. Only surviving pairs get the fine search for the time of closest approach.
* Each reported approach carries the time of closest approach, the miss distance, the relative speed, and the element age of each object. Each run also reports what it covered and every object or pair it did not screen, so an empty result cannot be read as a clear one.
* A run starts once a batch, the element sets sharing one `fetched_at`, has had no new record for 30 seconds. Its 7 day window starts at the newest `fetched_at` among the element sets it uses, not at the moment it runs, so the same input gives the same run, and `run_id` is that start time plus `rules_version`. A run publishes one `close_approach` event per approach and then one `screening_run` summary, keyed by `run_id` so the approaches are read first. Element sets that arrive late for a run already written wait for the next newer fetch.
* The screening consumer never commits offsets. On every start it reads `raw.gp` from the beginning, rebuilds the newest element set per object, and screens the newest batch, so a restart republishes the same run, which consumers of `alerts` drop by `event_id` ([ADR 0008](adr/0008-java-kafka-client-and-schema-validator.md)). The record of which runs were written is held in memory, so the rule that a written `run_id` is never produced again with other content holds within one process lifetime and while one instance reads every `raw.gp` partition. After a restart, a run whose batch gained late element sets is screened again under the same `run_id` with them included.
* The propagation and screening code is written test first, checked against the published SGP4 verification cases, and cross checked against a brute force scan on recorded CelesTrak element sets.

### Space weather

* Levels map to the NOAA Space Weather Scales: the G scale from the planetary Kp index, the R scale from GOES X-ray flux in the 0.1 to 0.8 nm band, and the S scale from GOES integral proton flux at 10 MeV and above. All three inputs are already ingested ([ADR 0005](adr/0005-swpc-polling-and-error-handling.md)).
* The storm rules are built: `StormRules` in `risk-engine` derives a level from one `raw.swpc` record, or rejects the record with a reason, and is tested against storm periods converted from NOAA archives. Thresholds come from NOAA's published scale table (https://www.spaceweather.gov/noaa-scales-explanation) and are recorded with citations, together with how missing and invalid data are handled, in [docs/risk/space-weather-scales.md](risk/space-weather-scales.md).
* A derived level is computed mechanically from one measurement. It is not a scale level issued by SWPC, and the scales note sets out how one may be presented.
* Tracking each series over time is built as [ADR 0007](adr/0007-alerts-topic.md) specifies: changes of level, Kp revisions, restatements, refreshes, "no data" when a series goes stale (checked by a clock as well as on arrival), and switches of the primary GOES satellite. Series state is held in memory and rebuilt from new records after a restart ([ADR 0008](adr/0008-java-kafka-client-and-schema-validator.md)).

### Conjunction data

Space-Track conjunction data messages are planned as a second signal: they raise flags and serve as a cross check on the engine's own screening. Raw conjunction data is never republished on any public surface, and Space-Track's user agreement will be reviewed in an ADR before any code that touches it lands.

## Error handling

* **Polling:** pollers respect each provider's published polling guidance and back off exponentially with full jitter where that guidance allows retries. The cadence and error rules for CelesTrak and SWPC are set in [ADR 0004](adr/0004-celestrak-polling-and-error-handling.md) and [ADR 0005](adr/0005-swpc-polling-and-error-handling.md) and summarized under [Ingest](#ingest).
* **Malformed payloads** go to the topic's dead letter topic with the failure reason attached. Nothing is dropped silently.
* **Probes:** every service exposes liveness and readiness probes. Readiness fails when a required dependency is down. `ingest` does this today; its readiness also fails while any feed is halted. `risk-engine` has no health endpoints yet.
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
| [CelesTrak](https://celestrak.org/) GP elements | Public, no account | Orbital elements for the catalog and watchlist (the `stations` group is ingested) |
| [NOAA SWPC](https://www.swpc.noaa.gov/) JSON products | Public, no account | Planetary Kp, GOES X-ray flux, GOES integral proton flux, and SWPC alerts, watches, and warnings (ingested) |
| [NASA DONKI](https://kauai.ccmc.gsfc.nasa.gov/DONKI/) | api.nasa.gov key | Coronal mass ejection and flare history; the retrieval corpus |
| [Space-Track](https://www.space-track.org/) conjunction data messages | Account required | Conjunction flags and cross checking; never republished |

## Deployment

* **Local:** Docker Compose with profiles, so only the services being worked on run at once. The `core` profile exists today with Kafka, topic creation, `ingest`, and `risk-engine`. Its memory was measured rather than assumed before `risk-engine` joined it, and the method, the numbers, and their limits are in [docs/perf/local-memory.md](perf/local-memory.md); a measurement that includes `risk-engine` has not been recorded yet. The `risk-engine` image is built in two stages, a Maven stage on a Temurin 21 JDK and a distroless Java 21 runtime, both pinned by digest, and runs as a non root user on a read only filesystem with every Linux capability dropped and no published ports. Its `/tmp` is a tmpfs that does not allow execution. Because `ingest`'s Kafka producer compresses with snappy, and the Java snappy and zstd libraries extract a native library before loading it, the container has a second, small tmpfs owned by that user that does allow execution, used only for those libraries; the service checks at startup that every codec decodes. Container logs are rotated at 10 MB with three files kept. MySQL and `query-api` join the profile, and the measurement is repeated, when they are built.
* **Cloud:** AWS, provisioned by Terraform (VPC, EKS, ECR, Secrets Manager). The environment is on demand: it is brought up for demos and destroyed afterwards to control cost. A recorded walkthrough will keep the project legible while the cluster is down.
* **CI:** GitHub Actions builds and tests every service, builds and pushes images, runs a Terraform plan on pull requests, and deploys on demand.

## Testing

* Go (in place for `ingest`): table driven unit tests, tests that run the CelesTrak client and both feeds' processors against recorded real responses and malformed variants derived from them, and integration tests against a Kafka Testcontainer.
* Java (in place for `risk-engine`): JUnit 5 tests of SGP4 against the published verification cases, of screening against recorded CelesTrak element sets with a brute force cross check, and of the storm rules against storm periods converted from NOAA archives. The series tracking, the `raw.swpc` to `alerts` path, and the `raw.gp` screening consumer have unit tests, and integration tests run both paths into `alerts` against a Kafka Testcontainer on the same broker image as `ingest` ([ADR 0008](adr/0008-java-kafka-client-and-schema-validator.md)). Testcontainers for MySQL and MongoDB, contract tests on every REST endpoint, and GraphQL schema tests are planned with the services that need them.
* Angular: Jest component tests.
* Fixtures are real data with their provenance, not hand made data: recorded feed responses with their capture date, and, for past storm periods the live SWPC files no longer hold, NOAA archive values converted into the `raw.swpc` event shape by a committed script.
