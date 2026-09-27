# Local memory of the core Compose profile

This page records how much memory the `core` Compose profile uses on my development machine, how I measured it, and the container limits I recommend from that measurement. Every memory number below comes from one of three runs of [`deploy/measure-ram.sh`](../../deploy/measure-ram.sh), and the raw samples and the printed summary of each are committed next to this page (one `.tsv` row per container per sample, and the script's `.summary.txt` output):

* Run 1, before any limits: [`data/local-memory-20260927T164650Z.tsv`](data/local-memory-20260927T164650Z.tsv), [`.summary.txt`](data/local-memory-20260927T164650Z.summary.txt)
* Run 2, limits applied: [`data/local-memory-20260927T170652Z.tsv`](data/local-memory-20260927T170652Z.tsv), [`.summary.txt`](data/local-memory-20260927T170652Z.summary.txt)
* Run 3, limits applied, ingest rebuilt from the final code: [`data/local-memory-20260927T173407Z.tsv`](data/local-memory-20260927T173407Z.tsv), [`.summary.txt`](data/local-memory-20260927T173407Z.summary.txt)

Three short runs under changing configurations are not enough to state run to run variance, so treat the figures as observations, not a distribution. The Configuration and Results sections describe run 1.

## Budget

The machine has 7.1 GiB of RAM, and the whole stack is never meant to run on it at once. The budget I set for the core local profile is roughly 3 GiB resident once it holds Kafka, MySQL, one JVM service, and the Go ingest service. Today the profile holds only Kafka and ingest (plus a one shot topic creation container), so this run measures that subset. MySQL and the JVM services are not built yet and are not included in any number here.

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
| ingest | `128m` | 64 | The observed peak is 15.7 MiB with only SWPC enabled. The CelesTrak path buffers bodies of up to 8 MiB and has not been measured, so the headroom is deliberately wide until a run with CelesTrak enabled confirms it. 64 threads is 6.4 times the 10 observed. |
| topics | not measured | not measured | The topic creation container ran and exited before sampling started, so I have no number for it. |

**Swap under a limit.** Docker's resource constraints documentation says that when `--memory` is set and `--memory-swap` is not, the container can also use as much swap as the memory setting. Setting only `mem_limit` in Compose therefore allows each container up to that amount again in swap. `deploy/compose.yaml` sets `memswap_limit` equal to `mem_limit`, which disables swap for the container and makes the limit a hard bound on RAM, at the cost of the kernel killing the process sooner under pressure.

With the Kafka limit above, the profile's limits total 1152 MiB (1 GiB plus 128 MiB) before MySQL and the JVM services are added. That is a sum of limits, not a measurement.

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

At its maximum Kafka used about 57 percent of its limit and ingest about 10 percent. One SWPC poll (about 73 KB received at 17:38:36Z) falls inside the window. The host was under memory pressure from other applications during all three runs, with about 2.5 to 2.6 GiB of host swap in use.

## Reproducing

1. Start the core profile (with `INGEST_FEEDS=swpc` if CelesTrak was queried within the last two hours).
2. Run `deploy/measure-ram.sh [duration_seconds] [interval_seconds] [output_dir]`. It writes a tab separated sample file and a summary to `docs/perf/data/` by default and prints the summary.
3. Compare the new summary with the committed one. The script records the image ids and the relevant environment so a mismatch in configuration is visible in the output.
