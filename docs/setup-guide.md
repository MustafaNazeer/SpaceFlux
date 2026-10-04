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

The Compose file defines a `core` profile with seven services: `kafka` (a single node broker), `topics` (a one shot container that creates `raw.gp`, `raw.gp.dlq`, `raw.swpc`, `raw.swpc.dlq`, `alerts`, and `alerts.dlq`, then exits), `ingest`, `risk-engine`, `mysql` (MySQL 8.4), `migrate` (a one shot container that applies the database migrations, then exits), and `query-api`.

MySQL needs four passwords before its first start. Write them once:

```sh
deploy/mysql/make-secrets.sh
```

It puts one random 40 character password per account in `deploy/secrets/`, which git ignores, and never replaces a file that already exists, because the accounts are created only when the MySQL data volume is empty. Then start the stack:

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

### MySQL and the migrate container

On its first start with an empty data volume, `mysql` runs `deploy/mysql/initdb/10-users.sh`, which creates three accounts from the files in `deploy/secrets/`: `spaceflux_migrate`, which can change the schema, and `spaceflux_consumer` and `spaceflux_api`, which `query-api` uses and which get their table privileges from the last migration. Every account must connect over TLS, and the server refuses unencrypted connections; it generates its own certificate on first start. Server settings are in `deploy/mysql/conf.d/spaceflux.cnf`. `root` exists only as `root@localhost`, X Protocol is off, and the database publishes no port: only services on the Compose `database` network (`mysql`, `migrate` and `query-api`) reach it. For a look by hand, this reads the root password from its secret file without putting it on a command line:

```sh
docker compose -f deploy/compose.yaml exec mysql bash -c 'mysql --defaults-extra-file=<(printf "[client]\npassword=%s\n" "$(< /run/secrets/mysql_root_password)") -uroot spaceflux'
```

The account script runs only on the first start, while the data volume is empty. If that first start fails, the volume is already initialized and later starts come up without the accounts, so remove the volume and start again.

`migrate` waits for MySQL to be healthy, applies the migrations in `db-migrate/src/main/resources/db/migration` as `spaceflux_migrate`, and exits; a later start finds the schema current and applies nothing. It refuses to run if a user name does not match `^[a-z][a-z0-9_]{0,31}$`. To start over with an empty database, stop the stack and remove the `spaceflux_mysql-data` volume yourself; that deletes every stored row.

`query-api` starts once `migrate` has exited successfully. It opens two connection pools, one as `spaceflux_consumer` and one as `spaceflux_api`, reading their passwords only from the Compose secret files in `/run/secrets/` and never holding the migration password; it refuses to start if an environment variable or a flag also sets either password, or if `hikaricp.configurationFile` is set. Before either pool is used, it runs `SHOW GRANTS` as that user and refuses to start if the result differs in any line from the reviewed list in `query-api/src/main/resources/grants/`, so a privilege added by hand stops the service rather than widening it. Its settings come from `MYSQL_HOST` (default `mysql`), `MYSQL_PORT` (default `3306`), `MYSQL_SSL_MODE` (`REQUIRED` by default; `VERIFY_CA` and `VERIFY_IDENTITY` are the only other values accepted), `CONSUMER_USER` and `API_USER`. It publishes no port yet.

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
* **`screening_run`**, keyed by its `run_id`. One is published shortly after 30 seconds have passed since the last element set of a CelesTrak batch arrived on `raw.gp`. Its `coverage` counts how many watchlist objects were accepted and how many catalog objects were admitted, and its `suppressed`, `rejected`, and `not_screened` lists name every object or pair it did not screen, unless the summary was cut to its size budget, in which case its `omitted` counts say how many entries were left out. Objects listed with the ISS in the station stacks file, `risk-engine/src/main/resources/screening/stacks.json`, such as docked vehicles, appear in `suppressed` rather than as approaches when the batch carries them.
* **`close_approach`**, keyed by the same `run_id` and published before that run's summary, one per approach within 5 km found in the run. The miss distance is uncertain by kilometres, as [docs/data/topics.md](data/topics.md) explains under `close_approach`. A run can have none, in which case its summary's `approach_count` is `0`.

The three dead letter topics are normally empty. A record there carries the reason it was rejected.

## Measuring memory

`deploy/measure-ram.sh` samples the memory of the running Compose containers and writes the samples and a summary under `docs/perf/data/`. The method and the recorded results are in [docs/perf/local-memory.md](perf/local-memory.md).
