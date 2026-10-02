# Setup guide

How to build and test the ingest service and the risk engine and run them locally with Kafka. Every command below is run from the repository root unless it says otherwise.

## Prerequisites

* **Go 1.27.1** or later, the version declared in `ingest/go.mod`. Needed only to run the `ingest` tests or build it outside Docker.
* **A JDK 21.** Needed only to run the `risk-engine` tests or build it outside Docker. Maven itself does not need to be installed: the Maven wrapper (`./mvnw`) downloads the version the repository pins.
* **Docker Engine with the Compose plugin** (the `docker compose` command). The local stack runs in Docker, and the integration tests start a Kafka container through Testcontainers, so the Docker daemon has to be running and reachable by your user.
* **curl**, or any HTTP client, to read the health endpoints.

No accounts or keys are needed. Both feeds ingested today, CelesTrak and NOAA SWPC, are public.

## Running the tests

### ingest

The tests read recorded feed responses from `ingest/testdata/` and the schema files from `schemas/` by relative path, so run them from the `ingest/` directory.

Unit tests only, no Docker required:

```sh
cd ingest
go test -short ./...
```

The `-short` flag skips the Kafka integration tests in `internal/kafkapub`.

Full suite, including the integration tests:

```sh
cd ingest
go test ./...
```

The integration tests start a single node `apache/kafka:4.3.1` container through Testcontainers and remove it afterwards. The first run pulls the image, so it takes longer. No test contacts CelesTrak or SWPC; feed behavior is tested against the recorded responses.

### risk-engine

Run the Maven build from the repository root, since the risk engine is a module of the parent `pom.xml` and its tests read the schema files from `schemas/`:

```sh
./mvnw -B verify
```

This compiles the service and runs every test, including the SGP4 verification cases, the screening cross check against a brute force scan, the storm rules against recorded storm periods, and integration tests that start an `apache/kafka:4.3.1` container through Testcontainers, so the Docker daemon has to be running. The first run downloads Maven, the dependencies, and the image, so it takes longer.

## Running the stack

The Compose file defines a `core` profile with four services: `kafka` (a single node broker), `topics` (a one shot container that creates `raw.gp`, `raw.gp.dlq`, `raw.swpc`, `raw.swpc.dlq`, `alerts`, and `alerts.dlq`, then exits), `ingest`, and `risk-engine`.

```sh
docker compose -f deploy/compose.yaml --profile core up --build
```

`ingest` and `risk-engine` start once the broker is healthy and the topics exist. `ingest` logs one JSON object per line to standard output; `risk-engine` logs in Spring Boot's default text format. The first `--build` downloads the risk engine's Maven dependencies and compiles it inside Docker, so it takes longer than later builds, which reuse the downloaded dependencies until a `pom.xml` changes. The image build does not run the tests. Press Ctrl+C to stop the stack in the foreground, or stop it from another terminal with:

```sh
docker compose -f deploy/compose.yaml --profile core down
```

Kafka's data lives in a named volume, so events already published are still there the next time the stack starts. The risk engine's screening consumer reads `raw.gp` from the beginning on every start, so after a restart it screens the newest batch it finds there again and republishes that run under the same run id, even when `ingest` makes no new CelesTrak request. The content can differ from the first time if an element set arrived late for that run; see [ADR 0007](adr/0007-alerts-topic.md) decision 12.

### Restarting without a new CelesTrak request

CelesTrak updates GP data once every 2 hours and asks clients to download it only once per update. The CelesTrak poller fetches as soon as `ingest` starts, and it does not remember the time of its last download across restarts. A restart within 2 hours of the previous CelesTrak download would therefore request the data again early, and CelesTrak can answer an early repeat with HTTP 403, which halts the CelesTrak poller until the next restart. The reasoning is in [ADR 0004](adr/0004-celestrak-polling-and-error-handling.md).

To restart within that window, enable only the SWPC feed:

```sh
INGEST_FEEDS=swpc docker compose -f deploy/compose.yaml --profile core up --build
```

`INGEST_FEEDS` accepts `celestrak`, `swpc`, or both separated by a comma. The default is both.

### Settings

`ingest` reads its settings from environment variables. The Compose file sets `KAFKA_BROKERS` and passes `INGEST_FEEDS` through from your shell.

| Variable | Default | Notes |
|---|---|---|
| `KAFKA_BROKERS` | none, required | Comma separated broker addresses. Compose sets `kafka:19092`. |
| `INGEST_FEEDS` | `celestrak,swpc` | Which feeds to poll. |
| `CELESTRAK_GROUP` | `stations` | The CelesTrak GP group to request. |
| `CELESTRAK_INTERVAL` | `2h10m` | Go duration syntax. Anything under `2h` is rejected at startup. |
| `SWPC_INTERVAL` | `5m` | Go duration syntax. Anything under `1m` is rejected at startup. |
| `INGEST_HTTP_ADDR` | `127.0.0.1:8080` | Health server address. The container image sets `:8080`, and Compose publishes it on `127.0.0.1:8080` only. |
| `INGEST_USER_AGENT` | `SpaceFlux-ingest (https://github.com/MustafaNazeer/SpaceFlux)` | Sent with every feed request. |
| `SCHEMAS_DIR` | `schemas` | The container image sets `/schemas`, where the schema files are copied at build time. |

The provider base URLs can be overridden only with their correct values (`https://celestrak.org` and `https://services.swpc.noaa.gov`); anything else is rejected at startup.

`risk-engine` reads the broker address from `KAFKA_BROKERS` (default `localhost:9092`; Compose sets `kafka:19092`). Its other settings, including the screening watchlist in `risk-engine/src/main/resources/screening/watchlist.json`, are built into the image. The Compose file also sets `JAVA_TOOL_OPTIONS` to point the snappy and zstd compression libraries at `/native`: `ingest`'s producer compresses with snappy, both Java libraries extract a native library before loading it, and the container's `/tmp` does not allow execution, so `/native` is a small tmpfs of its own that does. At startup the risk engine decodes a test record in every codec Kafka supports and exits with a message naming the setting if one cannot be loaded. Every Compose service keeps at most three 10 MB log files.

## Health endpoints

With the stack running, the `ingest` health server is on `127.0.0.1:8080`:

```sh
curl -i http://127.0.0.1:8080/healthz
curl -i http://127.0.0.1:8080/readyz
```

* `/healthz` is liveness. It answers 200 with `{"status":"alive"}` while the process runs, even when a feed is halted, so that a supervisor does not restart the service into a new round of provider requests.
* `/readyz` is readiness. It answers 200 when Kafka answers a ping, no poller is halted, and no feed's latest publish attempt has failed, and 503 otherwise. The JSON body has a `kafka` field and a `publish` field (`ok` when healthy, otherwise the error), and one field per enabled poller: `celestrak`, `swpc.kp`, `swpc.goes.xrays`, `swpc.goes.protons`, and `swpc.alerts`. Each reads `running`, or `halted:` followed by the cause.

A halted poller stays halted until `ingest` is restarted. Check the cause in the readiness body and the logs before restarting, since the provider's error is the thing to fix.

`risk-engine` has no health endpoints yet and publishes no ports. Check it through its logs and the `alerts` topic, as below.

## Reading events back

Use the console consumer that ships in the Kafka image, run inside the running `kafka` container. For example, the first five `raw.swpc` events with their keys:

```sh
docker compose -f deploy/compose.yaml exec -e KAFKA_HEAP_OPTS=-Xmx128m kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:19092 \
  --topic raw.swpc \
  --from-beginning \
  --max-messages 5 \
  --formatter-property print.key=true
```

Replace `raw.swpc` with `raw.gp`, `alerts`, or a dead letter topic (`raw.gp.dlq`, `raw.swpc.dlq`, `alerts.dlq`) to read the others. The console consumer waits for new events until it has printed `--max-messages` of them, so on a topic with fewer events (the dead letter topics are normally empty) stop it with Ctrl+C.

The `-e KAFKA_HEAP_OPTS=-Xmx128m` override matters because the `kafka` service sets a 512 MiB heap for the broker in its environment, and a tool started with `exec` inherits that environment while sharing the container's 1 GiB memory limit with the broker.

Every field of these events, their keys, and how to deduplicate them are described in [docs/data/topics.md](data/topics.md).

### What to look for on `alerts`

Read `alerts` with the command above, `--topic alerts` and a larger `--max-messages`. Each event is one line of JSON, and its `kind` field and its key say what it is:

* **`space_weather_level`**, keyed `space_weather.G`, `space_weather.R`, or `space_weather.S`. Once the risk engine has read the first SWPC poll, expect events for each of the three scales. `state` reads `level` (with `derived_label`, for example `G1`), `none`, `no_data`, or, for R and S, `ended`; the schema description of each state is in `schemas/alerts/v1.schema.json`. A level here is derived from one measurement and is not an official NOAA scale level.
* **`screening_run`**, keyed by its `run_id`. One is published shortly after 30 seconds have passed since the last element set of a CelesTrak batch arrived on `raw.gp`. Its `coverage` counts how many watchlist objects were accepted and how many catalog objects were admitted, and its `suppressed`, `rejected`, and `not_screened` lists name every object or pair it did not screen. Objects listed with the ISS in the station stacks file, `risk-engine/src/main/resources/screening/stacks.json`, such as docked vehicles, appear in `suppressed` rather than as approaches when the batch carries them.
* **`close_approach`**, keyed by the same `run_id` and published before that run's summary, one per approach within 5 km found in the run. A run can have none, in which case its summary's `approach_count` is `0`.

The three dead letter topics are normally empty. A record there carries the reason it was rejected.

## Measuring memory

`deploy/measure-ram.sh` samples the memory of the running Compose containers and writes the samples and a summary under `docs/perf/data/`. The method and the recorded results are in [docs/perf/local-memory.md](perf/local-memory.md).
