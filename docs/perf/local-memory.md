# Local memory of the core Compose profile

This page records how much memory the `core` Compose profile uses on my development machines, how I measured it, and the container limits I recommend from that measurement. Every profile memory number below comes from one of five runs of [`deploy/measure-ram.sh`](../../deploy/measure-ram.sh), and the raw samples and the printed summary of each are committed next to this page (one `.tsv` row per container per sample, and the script's `.summary.txt` output):

* Run 1, before any limits: [`data/local-memory-20260927T164650Z.tsv`](data/local-memory-20260927T164650Z.tsv), [`.summary.txt`](data/local-memory-20260927T164650Z.summary.txt)
* Run 2, limits applied: [`data/local-memory-20260927T170652Z.tsv`](data/local-memory-20260927T170652Z.tsv), [`.summary.txt`](data/local-memory-20260927T170652Z.summary.txt)
* Run 3, limits applied, ingest rebuilt from the final code: [`data/local-memory-20260927T173407Z.tsv`](data/local-memory-20260927T173407Z.tsv), [`.summary.txt`](data/local-memory-20260927T173407Z.summary.txt)
* Run 4, the risk engine added to the profile, on a second machine: [`data/local-memory-20261002T004545Z.tsv`](data/local-memory-20261002T004545Z.tsv), [`.summary.txt`](data/local-memory-20261002T004545Z.summary.txt)
* Run 5, MySQL and `query-api` added, back on the first machine: [`data/local-memory-20261006T072643Z.tsv`](data/local-memory-20261006T072643Z.tsv), [`.summary.txt`](data/local-memory-20261006T072643Z.summary.txt)

The MySQL configuration change described under [Trimming the MySQL performance schema](#trimming-the-mysql-performance-schema) was measured separately, with MySQL running alone, and those two readouts are committed as [`data/mysql-alone-20261006T074119Z.txt`](data/mysql-alone-20261006T074119Z.txt) and [`data/mysql-alone-20261006T074336Z.txt`](data/mysql-alone-20261006T074336Z.txt).

Five short runs under changing configurations, on two machines, are not enough to state run to run variance, so treat the figures as observations, not a distribution. The Configuration and Results sections describe run 1. Runs 1 to 3 and run 5 were made on a machine with 7.1 GiB of RAM and run 4 on a laptop with 14 GiB; each summary file records its host's kernel, Docker version, and total memory and swap.

## Budget

The machine of runs 1 to 3, the smaller of the two, has 7.1 GiB of RAM, and the whole stack is never meant to run on it at once. The budget I set for the core local profile is roughly 3 GiB resident once it holds Kafka, MySQL, one JVM service, and the Go ingest service. Runs 1 to 3 measured Kafka and ingest only (plus a one shot topic creation container). Run 4 adds the risk engine, the first JVM service, and is described in [Run with the risk engine](#run-with-the-risk-engine). Run 5 adds MySQL and `query-api`, the second JVM service, so the profile now runs two JVM services rather than the one the budget was written for, and is described in [Run with MySQL and the query API](#run-with-mysql-and-the-query-api). MongoDB is not part of the local profile and is not in any number here.

## Method

The script finds every running container whose `com.docker.compose.project` label is `spaceflux`, resolves each one's cgroup from `/proc/<pid>/cgroup`, and then reads the cgroup v2 files directly on the host at a fixed interval:

* `memory.current`, and from `memory.stat` the `anon`, `file`, and `inactive_file` fields
* `memory.swap.current`
* `pids.current` (on Linux this counts threads, not just processes)
* `memory.peak` once at the end (the high water mark since the container started, page cache included)
* received and sent bytes on the container's `eth0`, from `/proc/<pid>/net/dev`, as evidence that polls happened inside the window

It also samples host `used`, `available`, and swap in use from `free -b` each interval.

**What "usage" means.** Usage is `memory.current` minus `inactive_file`. Docker's documentation for `docker stats` says that on cgroup v2 hosts the CLI subtracts the `inactive_file` value from total usage, and I checked that on this host rather than assuming it: the script reads the cgroup immediately before and after calling `docker stats --no-stream` for each container, at the start and at the end of the run. At the start, ingest read 8.04 MiB from the cgroup and 8.043 MiB from `docker stats`, and Kafka read 260.94 MiB and 261 MiB. At the end, ingest matched exactly (12.82 MiB), and Kafka's `docker stats` value (285.9 MiB) matched the cgroup read taken right after it (285.86 MiB) while the read before it differed, which shows Kafka's usage moving during the call rather than a different formula.

**Why swap is reported separately.** `memory.current` does not count pages a container has swapped out. This host was under memory pressure from other desktop applications during the run (host swap in use peaked at 2615.5 MiB), and part of Kafka's heap was swapped out for the whole run. So I report usage alone and usage plus swap. The second is the better estimate of what Kafka would hold resident on an idle machine.

**Command.**

```sh
INGEST_FEEDS=swpc docker compose -f deploy/compose.yaml --profile core up -d --build
deploy/measure-ram.sh 420 5
```

The run lasted 420 seconds with a 5 second interval, giving 83 samples per series. The script's defaults are 360 seconds and 5 seconds, which is long enough to span at least one poll at the default 5 minute SWPC interval.

## Configuration

| Item | Value |
|---|---|
| Kernel | 6.17.0-41-generic |
| Docker Engine | 29.1.3, cgroup v2, systemd cgroup driver |
| Host memory (`free -h`) | 7.1Gi total; 11Gi swap (a 7.1G zram device and a 4G swap file) |
| Kafka image | `apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837` |
| Ingest image | locally built `spaceflux-ingest`, image id `sha256:69cf2fb706f55c1ff456d58767b1ab6febbba2f640038d9b6614e378ae59d089` |
| `INGEST_FEEDS` | `swpc` |
| `SWPC_INTERVAL` | unset, so the default of 5 minutes |
| `KAFKA_HEAP_OPTS` | unset, so the image's default applies (see below) |
| Limits in effect | none (`mem_limit` 0, `pids_limit` unset) |
| Window | 2026-09-27 16:46:50Z to 16:53:57Z; Kafka had been up since 16:41:47Z and ingest since 16:42:14Z |

**Why CelesTrak was off.** I ran ingest with only the SWPC feed because CelesTrak updates GP data once every 2 hours and asks clients to download it only once per update ([ADR 0004](../adr/0004-celestrak-polling-and-error-handling.md)), and a request had already been made within that window. Starting ingest with CelesTrak enabled sends a request immediately. With CelesTrak enabled, its poller adds one request every 2 hours 10 minutes. Its memory cost is therefore not in these numbers; that path buffers response bodies of up to 8 MiB, so it needs its own run.

**Polls inside the window.** Ingest received 73,741 bytes in the interval ending 16:47:18Z and 73,855 bytes in the interval ending 16:52:18Z, five minutes apart, which matches two SWPC polls. I also checked ingest's readiness endpoint by hand before and after the run; that check is not recorded in the committed files.

## Results

Usage is `memory.current` minus `inactive_file`. All values in MiB except thread counts.

| Series | Min | Median | Max | Swap max | Usage plus swap, median | Usage plus swap, max | Threads max |
|---|---|---|---|---|---|---|---|
| Kafka | 261.0 | 283.9 | 385.0 | 110.9 | 377.4 | 483.2 | 158 |
| Ingest | 8.0 | 11.8 | 12.8 | 0.0 | 11.8 | 12.8 | 10 |
| Both containers | 269.0 | 295.6 | 396.8 | 110.9 | 389.4 | 495.0 | |
| Host used | 3755.2 | 3939.5 | 4226.0 | | | | |
| Host available | 3070.5 | 3357.0 | 3541.3 | | | | |

`memory.peak` since container start, which includes page cache and startup: Kafka 648.5 MiB, ingest 15.7 MiB.

Kafka's usage sat near 284 MiB for most of the run with short rises to 385 MiB, during which its thread count rose from about 115 to as many as 158. I have not identified what triggered those rises. Its swapped out memory stayed between 84.8 and 110.9 MiB throughout.

**Does the core profile fit the budget?** For the part that exists today, yes: Kafka and ingest together peaked at 495.0 MiB counting swap, against a budget of roughly 3 GiB. That says nothing yet about MySQL or the JVM services, which have to be measured with this same script once they run. The Kafka figure also understates what the broker can grow to with its current heap setting, as the next section explains.

## The Kafka heap

`KAFKA_HEAP_OPTS` is not set in the Compose file or in the image's environment. The image's `/opt/kafka/bin/kafka-server-start.sh` sets a default when it is empty:

```sh
if [ "x$KAFKA_HEAP_OPTS" = "x" ]; then
    export KAFKA_HEAP_OPTS="-Xmx1G -Xms1G"
fi
```

The running broker's command line confirms `-Xmx1G -Xms1G`, and `kafka-run-class.sh` adds its default G1 options (`-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:InitiatingHeapOccupancyPercent=35`). The broker's GC log reports `Heap Initial Capacity: 1G`. In run 1 the broker's GC log showed about 178 MiB of heap left after young collections, and metaspace about 10 MiB; that log was not kept when the broker was restarted with a smaller heap. The committed excerpt [`data/kafka-gc-20260927.txt`](data/kafka-gc-20260927.txt), from the restarted broker with a 512 MiB heap, shows the same range: 162 to 172 MiB left after each young collection from 17:12Z onward, which is the basis for the 512 MiB heap below.

This matters for the limit. The JVM reserves 1 GiB of heap up front, but Linux only makes a page resident when it is first touched, and G1 touches more heap regions as it keeps allocating over a longer run. The 483 MiB I observed is therefore a floor for this configuration, not a ceiling. A `mem_limit` derived only from the observed maximum would sit below the heap the broker is allowed to use, and the kernel would kill the broker once enough of the heap became resident.

## Limits

The `1g` Kafka limit with a 512 MiB heap and the `128m` ingest limit below are applied in `deploy/compose.yaml`, each with `memswap_limit` equal to `mem_limit` so neither container can spill into swap. They were checked with a second run, reported under "Run with the limits applied".

| Service | `mem_limit` | `pids_limit` | Reasoning |
|---|---|---|---|
| kafka | `1g`, with `KAFKA_HEAP_OPTS: "-Xmx512m -Xms512m"` | 512 | A 512 MiB heap is about 2.9 times the 178 MiB left after young collections, and 1 GiB leaves 512 MiB for metaspace (about 10 MiB observed), thread stacks, code cache, direct buffers, and the log segment page cache, which counts toward the limit but is reclaimable. 512 threads is about 3.2 times the 158 observed. |
| kafka, if the default heap stays | no lower than `1536m` | 512 | With `-Xms1G` the whole 1 GiB heap can become resident, so the limit needs the heap plus non heap memory on top of it. |
| ingest | `128m` | 64 | The observed peak is 15.7 MiB with only SWPC enabled, and 16.6 MiB in run 5, where CelesTrak was enabled and its one request at startup fetched a body of 9,720 bytes. The CelesTrak path buffers bodies of up to 8 MiB, and a body near that size has not been measured, so the headroom stays deliberately wide. 64 threads is 6.4 times the 10 observed. |
| risk-engine | `512m` | 128 | Set before any measurement and kept after run 4: the risk engine peaked at 176.3 MiB (34 percent of the limit) and its `memory.peak` since start, page cache included, was 176.6 MiB, with 23 threads (128 is about 5.6 times that). The JVM, not this limit, sets the heap ceiling: no heap option is passed, so Java 21 sizes the heap at 25 percent of the container limit, 128 MiB, with the serial collector (see run 4). That measurement covered a 22 object catalog; how heap use grows with a larger catalog has not been measured. |
| mysql | `512m` | 256 | Set before any measurement. In run 5, with the performance schema at its default sizing, MySQL peaked at 466.6 MiB of usage (91 percent of the limit) and its `memory.peak` since start was 471.8 MiB (92 percent), and across the window its anonymous memory stayed between 383.2 and 384.7 MiB and its page cache between 81.2 and 81.4 MiB. That is too close to the limit, so rather than raise it I trimmed two performance schema tables nothing reads; with MySQL running alone that cut its anonymous memory by 84.4 MiB (see [Trimming the MySQL performance schema](#trimming-the-mysql-performance-schema)). The full profile has not been measured with the trim yet. The InnoDB buffer pool is fixed at 128 MiB in `deploy/mysql/conf.d/spaceflux.cnf`. 256 threads is about 6 times the 43 observed. |
| query-api | `512m` | 128 | Set before any measurement and kept after run 5: `query-api` peaked at 224.5 MiB of usage (44 percent of the limit) and its `memory.peak` since start was 240.6 MiB (47 percent), with 38 threads (128 is about 3.4 times that). No one signed in during the window, so the cost of a bcrypt check at cost 14 is not in these numbers, and I did not check which heap size the JVM picked for this image. |
| migrate | `256m` | 64 | Not measured: the migration container ran and exited before sampling started. |
| topics | not measured | not measured | The topic creation container ran and exited before sampling started, so I have no number for it. |

**Swap under a limit.** Docker's resource constraints documentation says that when `--memory` is set and `--memory-swap` is not, the container can also use as much swap as the memory setting. Setting only `mem_limit` in Compose therefore allows each container up to that amount again in swap. `deploy/compose.yaml` sets `memswap_limit` equal to `mem_limit`, which disables swap for the container and makes the limit a hard bound on RAM, at the cost of the kernel killing the process sooner under pressure.

With the Kafka limit above, the profile's limits total 1152 MiB (1 GiB plus 128 MiB) for Kafka and ingest, 1664 MiB with the risk engine's 512 MiB, and 2688 MiB with MySQL's and `query-api`'s 512 MiB each, for the five containers that keep running. The migration container's 256 MiB is not included because it exits before `query-api` starts. That is a sum of limits, not a measurement, and it sits under the roughly 3 GiB budget with about 384 MiB to spare.

## Run with the limits applied

Run 2, 2026-09-27 17:06:52Z to 17:12:59Z, 360 s at a 5 s interval, 71 samples, same host and `INGEST_FEEDS=swpc`, after `KAFKA_HEAP_OPTS: "-Xmx512m -Xms512m"`, the memory, swap, and process limits above, and `cap_drop: [ALL]` with `no-new-privileges` were applied to Kafka. Raw samples and summary: `docs/perf/data/local-memory-20260927T170652Z.tsv` and `.summary.txt`.

| Container | usage min | median | max | swap max | threads max |
|---|---|---|---|---|---|
| kafka | 413.5 MiB | 436.4 MiB | 546.2 MiB | 0 | 129 |
| ingest | 7.7 MiB | 11.4 MiB | 13.6 MiB | 0 | 10 |
| both | 424.0 MiB | 448.8 MiB | 557.6 MiB | 0 | |

At its maximum Kafka used about 53 percent of its 1 GiB limit (about 43 percent at the median) and ingest about 11 percent of its 128 MiB limit, with no swap, since the limits forbid it. One SWPC poll (about 73 KB received at 17:11:19Z) falls inside the window.

Run 3, 2026-09-27 17:34:07Z to 17:41:17Z, 420 s at a 5 s interval, 83 samples, same limits and `INGEST_FEEDS=swpc`, with ingest rebuilt from the final code at 17:33:34Z.

| Container | usage min | median | max | swap max | threads max |
|---|---|---|---|---|---|
| kafka | 470.8 MiB | 471.9 MiB | 580.8 MiB | 0 | 129 |
| ingest | 7.5 MiB | 9.2 MiB | 12.7 MiB | 0 | 10 |
| both | 478.8 MiB | 482.3 MiB | 590.0 MiB | 0 | |

At its maximum Kafka used about 57 percent of its limit and ingest about 10 percent. One SWPC poll (about 73 KB received at 17:38:36Z) falls inside the window. The host was under memory pressure from other applications during all three runs, with host swap in use peaking at 2.4 to 2.6 GiB per run (2486.2 to 2657.4 MiB in the summaries).

## Run with the risk engine

Run 4, 2026-10-02 00:45:45Z to 00:51:55Z, 360 s at a 5 s interval, 71 samples, on a different machine from runs 1 to 3: a laptop with 14 GiB of RAM and 4 GiB of swap, the same kernel (6.17.0-41-generic) and Docker Engine (29.1.3, cgroup v2, systemd driver). Raw samples and summary: [`data/local-memory-20261002T004545Z.tsv`](data/local-memory-20261002T004545Z.tsv) and [`.summary.txt`](data/local-memory-20261002T004545Z.summary.txt).

**Configuration.** The images were built from the committed code at commit `59885e7` and the two services recreated with

```sh
INGEST_FEEDS=swpc docker compose -f deploy/compose.yaml --profile core up --build -d
deploy/measure-ram.sh
```

| Item | Value |
|---|---|
| Kafka image | `apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837`, up since 00:07:03Z |
| Ingest image | locally built `spaceflux-ingest`, image id `sha256:b96a86cb6397fdf45d61b14cbcb239613d0d5ca9b0c4d3ebc5a2b00c31da2ef5`, started 00:45:31Z |
| Risk engine image | locally built `spaceflux-risk-engine`, image id `sha256:a0c7dcb53acb7844c23675955b43f3859b9093f9b60591ebbf0d20ae8153f59e`, started 00:45:31Z |
| `INGEST_FEEDS` | `swpc` |
| Limits in effect | as in `deploy/compose.yaml`: Kafka `1g`, ingest `128m`, risk engine `512m`, each without swap; pids 512, 64, 128 |
| Risk engine JVM | Java 21.0.12.1 from the distroless image; `JAVA_TOOL_OPTIONS` carries only the two native library directories, so no heap option is set |

**A screening run inside the window.** The screening consumer reads `raw.gp` from the beginning on every start and publishes a run about 30 seconds after the newest batch stops growing. The risk engine container started at 00:45:31Z, about 13 seconds before the measurement started at 00:45:45Z and 18 seconds before the first sample at 00:45:50Z (the start time is in the summary file, the sample times in the `.tsv`). The details of the screening run come from a one off read of the live stack's Kafka topics after the run, and that output is not committed: `raw.gp` held one CelesTrak stations fetch (fetched 2026-10-01T23:40:11Z), so no new CelesTrak request was needed; the `screening_run` event for run `2026-10-01T23:40:11.638297494Z/1` on the `alerts` topic had `produced_at` 2026-10-02T00:46:09.664Z, 24 seconds into the window, and record timestamp 00:46:11.097Z; that run admitted 22 catalog objects and formed 21 pairs, 7 suppressed by the named station stacks, 14 removed by the radial prefilter, none searched, no approaches; and the three dead letter topics were empty. Between the samples at 00:46:06Z and 00:46:11Z the risk engine's usage rose from 158.7 to 163.0 MiB. A 5 second interval can miss a shorter peak, so the `memory.peak` figure below, which the kernel keeps for the whole life of the container, is the better bound. One SWPC poll (71,206 bytes received by ingest in the interval ending 00:50:34Z) also falls inside the window.

| Container | usage min | median | max | swap max | threads max |
|---|---|---|---|---|---|
| kafka | 459.8 MiB | 460.9 MiB | 659.7 MiB | 0 | 162 |
| ingest | 8.8 MiB | 9.7 MiB | 13.7 MiB | 0 | 14 |
| risk-engine | 158.2 MiB | 164.2 MiB | 176.3 MiB | 0 | 23 |
| all three | 631.6 MiB | 638.3 MiB | 837.0 MiB | 0 | |

`memory.peak` since container start, page cache included: Kafka 726.4 MiB, ingest 16.4 MiB, risk engine 176.6 MiB. The host had no swap in use at any sample. At its maximum the risk engine used about 34 percent of its 512 MiB limit and Kafka about 64 percent of its 1 GiB limit. Kafka's maximum is higher than in runs 2 and 3 (546.2 and 580.8 MiB); this host was not under memory pressure, while runs 1 to 3 had host swap in use peaking at 2.4 to 2.6 GiB, so the two hosts are not directly comparable. The risk engine's usage climbed slowly across the window, from about 164 MiB a minute in to 176 MiB at the end, so six minutes is too short to say where it levels off.

**The risk engine's heap.** With no heap option, the JVM picks its heap from the container limit. I checked what it picks with the same image and the same limit in a separate, short lived container (`docker run --rm --memory 512m --memory-swap 512m --entrypoint /usr/bin/java spaceflux-risk-engine -XX:+PrintFlagsFinal -version`, a one off check whose output is not committed): `MaxRAMPercentage` 25, `MaxHeapSize` 134217728 bytes (128 MiB), `InitialHeapSize` 8 MiB, and the serial collector. So a heap that outgrows 128 MiB fails with an `OutOfMemoryError` inside the JVM long before the container reaches 512 MiB. I did not record the live heap after collections, as I did for Kafka, so the share of the 176.3 MiB that is heap is not known from this run.

**Does the core profile fit the budget?** The three containers together peaked at 837.0 MiB, within the roughly 3 GiB budget, with MySQL and `query-api` still to be added. This holds for a 22 object catalog and a single watchlist object.

## Run with MySQL and the query API

Run 5, 2026-10-06 07:26:43Z to 07:34:02Z, 420 s at a 5 s interval, 80 samples per container (first at 07:26:53Z, last at 07:33:48Z), on the machine of runs 1 to 3 (7.1 GiB of RAM, the same kernel and Docker Engine as before). Raw samples and summary: [`data/local-memory-20261006T072643Z.tsv`](data/local-memory-20261006T072643Z.tsv) and [`.summary.txt`](data/local-memory-20261006T072643Z.summary.txt).

**Configuration.** The images were built from the committed code at commit `7b20231` and the profile started with the default feeds:

```sh
docker compose -f deploy/compose.yaml --profile core up -d --build
deploy/measure-ram.sh 420 5
```

| Item | Value |
|---|---|
| Kafka image | `apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837`, started 07:25:29Z |
| MySQL image | `mysql:8.4.11@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242`, started 07:25:29Z |
| Ingest image | locally built `spaceflux-ingest`, image id `sha256:2d609b26b2890ff0f9261ab57b6450d7554949846205a27b533125a9bdfcf1d6`, started 07:26:27Z |
| Risk engine image | locally built `spaceflux-risk-engine`, image id `sha256:4764ab461d8a307d053fc0d85f7c51723c806e60d1fa7b56972ddee4c3904e7b`, started 07:26:27Z |
| Query API image | locally built `spaceflux-query-api`, image id `sha256:5c403543726afbe3b34d9fcaf409e244e8587612828fe3a62dcf900a06468481`, started 07:26:27Z |
| `INGEST_FEEDS` | `celestrak,swpc`, the default |
| Operator account | configured in `deploy/.env`, so sign in was enabled; no one signed in during the window |
| MySQL performance schema | default sizing; the trim described in the next section came after this run |
| Limits in effect | as in `deploy/compose.yaml`: Kafka `1g`, ingest `128m`, risk engine `512m`, MySQL `512m`, `query-api` `512m`, each without swap; pids 512, 64, 128, 256, 128 |

The topic creation and migration containers ran and exited before sampling started, so the script did not see them. I closed the browser just before the run, but the host still had about 1.9 GiB of swap in use carried over from before it (1901.6 to 1906.4 MiB across the samples), so this host was again under memory pressure, as in runs 1 to 3.

**Feed traffic.** With CelesTrak enabled, ingest sent one CelesTrak request at startup, about 07:26:29Z, 14 seconds before the measurement started; ingest's log records a 9,720 byte body (the log is not committed). Ingest's `memory.peak` since container start, 16.6 MiB, covers that fetch. One SWPC poll falls inside the window: ingest received 74,875 bytes in the interval ending 07:31:30Z. I did not read the topics after this run, so I cannot say whether a screening run fell inside the window. The risk engine's usage rose from 157.2 to 180.2 MiB between the samples at 07:27:08Z and 07:27:13Z, the same interval in which MySQL received 12,957 bytes and Kafka 13,198 bytes; that timing is consistent with a screening run being stored, but it is not evidence of one. I have not tied the other intervals the summary lists to specific events.

| Container | usage min | median | max | swap max | threads max |
|---|---|---|---|---|---|
| kafka | 591.6 MiB | 603.0 MiB | 728.2 MiB | 0 | 129 |
| mysql | 464.4 MiB | 464.9 MiB | 466.6 MiB | 0 | 43 |
| query-api | 212.8 MiB | 217.3 MiB | 224.5 MiB | 0 | 38 |
| risk-engine | 156.6 MiB | 182.1 MiB | 183.0 MiB | 0 | 23 |
| ingest | 10.6 MiB | 13.7 MiB | 16.0 MiB | 0 | 10 |
| all five | 1446.3 MiB | 1479.1 MiB | 1613.4 MiB | 0 | |

`memory.peak` since container start, page cache included: Kafka 760.3 MiB, MySQL 471.8 MiB, `query-api` 240.6 MiB, risk engine 186.0 MiB, ingest 16.6 MiB. Host used memory ranged from 4036.4 to 4345.8 MiB and host available memory from 2950.7 to 3260.1 MiB. At its maximum Kafka used about 71 percent of its 1 GiB limit, the risk engine about 36 percent and `query-api` about 44 percent of their 512 MiB limits, and ingest about 13 percent of its 128 MiB limit.

**MySQL is close to its limit.** MySQL's usage barely moved across the window, and at its maximum it used 91 percent of its 512 MiB limit. The `.tsv` splits it: anonymous memory between 383.2 and 384.7 MiB and page cache (`file`) between 81.2 and 81.4 MiB, of which only 3.2 to 3.5 MiB was inactive, so almost all of the page cache counts in usage. The kernel can reclaim page cache before it has to kill the process, but the anonymous memory it cannot reclaim at all, because the limit forbids swap. I have not identified which files make up the 81 MiB of page cache. That anonymous figure is what I set out to lower next.

**Does the core profile fit the budget?** The five containers together peaked at 1613.4 MiB, within the roughly 3 GiB budget, with two JVM services rather than the one the budget assumed. This holds for the local data at the time of the run and with no sign in or acknowledgement traffic inside the window.

## Trimming the MySQL performance schema

To see where MySQL's anonymous memory goes, I started MySQL alone, with no other container of the project running, and read its own accounting as root: `sys.memory_global_total`, the per area sums of `performance_schema.memory_summary_global_by_event_name`, and the container cgroup's `memory.stat`. Raw readouts: [`data/mysql-alone-20261006T074119Z.txt`](data/mysql-alone-20261006T074119Z.txt) (default sizing) and [`data/mysql-alone-20261006T074336Z.txt`](data/mysql-alone-20261006T074336Z.txt) (trimmed). With the default sizing MySQL had allocated 438.99 MiB, of which 224.51 MiB was the performance schema and 195.78 MiB InnoDB. Two performance schema tables, both at 10,000 rows, accounted for about 84 MiB of that: the statement digest summary (`events_statements_summary_by_digest`, 50.05 MiB with its digest text) and the long statement history (`events_statements_history_long`, 33.96 MiB with its SQL and digest text). Nothing in the project reads either, and the long history consumer is disabled by default (`setup_consumers` showed `events_statements_history_long` as `NO`), so it never fills.

The MySQL 8.4 reference manual ([Performance Schema System Variables](https://dev.mysql.com/doc/refman/8.4/en/performance-schema-system-variables.html)) lists both `performance_schema_digests_size` and `performance_schema_events_statements_history_long_size` as global, not dynamic, with a maximum of 1048576 and a default of autosizing, so they can only be set at startup, in an option file or on the command line. I set both to 1000 in `deploy/mysql/conf.d/spaceflux.cnf`. For digests, `query-api`'s main code has 35 SQL call sites and the 14 migrations hold 36 statements, so even with the driver's and the migration tool's own statements the services issue well under 1000 distinct statements; if that ever stops being true, the `Performance_schema_digest_lost` status variable counts the digests that did not fit (it read 0). For the long history, 1000 rows only keeps the table usable if the consumer is switched on while debugging. The statement summaries by user, which the acknowledgement integration test reads to show that an unauthenticated write never reaches the database, are sized by other variables and did not change: the table had 840 rows before and after, and after the restart it was still counting root's statements.

Each readout was taken about 100 seconds after the server started (07:39:38Z to 07:41:19Z, and 07:41:58Z to 07:43:36Z):

| MySQL alone | default sizing | trimmed | change |
|---|---|---|---|
| `sys.memory_global_total` | 438.99 MiB | 363.39 MiB | minus 75.60 MiB |
| performance schema | 224.51 MiB | 148.91 MiB | minus 75.60 MiB |
| InnoDB | 195.78 MiB | 195.78 MiB | none |
| digest summary, with digest text | 50.05 MiB | 5.01 MiB | minus 45.04 MiB |
| long statement history, with its text | 33.96 MiB | 3.40 MiB | minus 30.56 MiB |
| cgroup `anon` | 369.2 MiB | 284.8 MiB | minus 84.4 MiB |
| cgroup `memory.peak` since start | 384.5 MiB | 300.0 MiB | minus 84.5 MiB |

These are single readouts of an idle server with no client connections, not a profile measurement, and they are not comparable with run 5's figures for MySQL, which include the services' connections and 81 MiB of page cache. I kept MySQL's limit at 512 MiB. Whether the trim gives the same saving under the full profile needs a new run with this script.

The error summary tables are the next largest performance schema item: 34.88 MiB across the five `events_errors_summary_*` tables in the default readout. The manual lists `performance_schema_error_size` as global and not dynamic, defaulting to the number of server error codes (5564 here), and says its intended use is either that default or 0 to instrument no errors. Nothing in the project reads those tables either, so setting it to 0 could free up to about that much, at the cost of per error statistics. I kept them, since they help when diagnosing a database error, and the trim above already leaves about 227 MiB between MySQL's anonymous memory alone (284.8 MiB) and the limit.

## Reproducing

1. Start the core profile (with `INGEST_FEEDS=swpc` if CelesTrak was queried within the last two hours). Since MySQL joined the profile, it needs the password files in `deploy/secrets/` first, as the [setup guide](../setup-guide.md) describes.
2. Run `deploy/measure-ram.sh [duration_seconds] [interval_seconds] [output_dir]`. It writes a tab separated sample file and a summary to `docs/perf/data/` by default and prints the summary.
3. Compare the new summary with the committed one. The script records the image ids and the relevant environment so a mismatch in configuration is visible in the output.
