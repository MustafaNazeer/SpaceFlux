# Setup guide

How to build and test the ingest service and run it locally with Kafka. Every command below is run from the repository root unless it says otherwise.

## Prerequisites

* **Go 1.27.1** or later, the version declared in `ingest/go.mod`. Needed only to run the tests or build outside Docker.
* **Docker Engine with the Compose plugin** (the `docker compose` command). The local stack runs in Docker, and the integration tests start a Kafka container through Testcontainers, so the Docker daemon has to be running and reachable by your user.
* **curl**, or any HTTP client, to read the health endpoints.

No accounts or keys are needed. Both feeds ingested today, CelesTrak and NOAA SWPC, are public.

## Running the tests

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

## Running the stack

The Compose file defines a `core` profile with three services: `kafka` (a single node broker), `topics` (a one shot container that creates `raw.gp`, `raw.gp.dlq`, `raw.swpc`, and `raw.swpc.dlq`, then exits), and `ingest`.

```sh
docker compose -f deploy/compose.yaml --profile core up --build
```

`ingest` starts once the broker is healthy and the topics exist. It logs one JSON object per line to standard output. Press Ctrl+C to stop the stack in the foreground, or stop it from another terminal with:

```sh
docker compose -f deploy/compose.yaml --profile core down
```

Kafka's data lives in a named volume, so events already published are still there the next time the stack starts.

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

## Health endpoints

With the stack running, the health server is on `127.0.0.1:8080`:

```sh
curl -i http://127.0.0.1:8080/healthz
curl -i http://127.0.0.1:8080/readyz
```

* `/healthz` is liveness. It answers 200 with `{"status":"alive"}` while the process runs, even when a feed is halted, so that a supervisor does not restart the service into a new round of provider requests.
* `/readyz` is readiness. It answers 200 when Kafka answers a ping, no poller is halted, and no feed's latest publish attempt has failed, and 503 otherwise. The JSON body has a `kafka` field and a `publish` field (`ok` when healthy, otherwise the error), and one field per enabled poller: `celestrak`, `swpc.kp`, `swpc.goes.xrays`, `swpc.goes.protons`, and `swpc.alerts`. Each reads `running`, or `halted:` followed by the cause.

A halted poller stays halted until `ingest` is restarted. Check the cause in the readiness body and the logs before restarting, since the provider's error is the thing to fix.

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

Replace `raw.swpc` with `raw.gp`, `raw.gp.dlq`, or `raw.swpc.dlq` to read the other topics. The console consumer waits for new events until it has printed `--max-messages` of them, so on a topic with fewer events (the dead letter topics are normally empty) stop it with Ctrl+C.

The `-e KAFKA_HEAP_OPTS=-Xmx128m` override matters because the `kafka` service sets a 512 MiB heap for the broker in its environment, and a tool started with `exec` inherits that environment while sharing the container's 1 GiB memory limit with the broker.

Every field of these events, their keys, and how to deduplicate them are described in [docs/data/topics.md](data/topics.md).

## Measuring memory

`deploy/measure-ram.sh` samples the memory of the running Compose containers and writes the samples and a summary under `docs/perf/data/`. The method and the recorded results are in [docs/perf/local-memory.md](perf/local-memory.md).
