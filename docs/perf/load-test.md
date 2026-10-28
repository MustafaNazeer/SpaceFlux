# Local load test

This page records two things about `query-api` on one laptop: how fast it stores a backlog of alerts waiting on the `alerts` topic, and how fast its read API answers at steady request rates. Every number comes from committed scripts run three times against a fixed, generated data set, and every output file is committed. These are local, single machine figures with one partition per topic. They are not a capacity figure for any deployment, and the events are generated, not observed.

## Harness and data

* **Data:** [`deploy/load/gen_alerts.py`](../../deploy/load/gen_alerts.py) writes schema valid alert events derived from the committed examples in `schemas/alerts/examples/`, each with a unique `event_id`, from a fixed seed. Every run here used seed 1 and a space weather share of 0.1, which gave 8,977 close approaches and 1,023 space weather level events at 10,000, and 44,990 and 5,010 at 50,000.
* **Ingest drain:** [`deploy/load/ingest-drain.sh`](../../deploy/load/ingest-drain.sh) resets the throwaway Compose project `spaceflux-load` (its own volumes only), creates the topics and runs the migrations, preloads the events onto `alerts` with `query-api` stopped, then starts `query-api` and waits until every event is stored. Ingest and the risk engine are never started, so no feed is called.
* **Read latency:** [`deploy/load/read-latency.sh`](../../deploy/load/read-latency.sh) runs [`read-latency.js`](../../deploy/load/read-latency.js) in `grafana/k6:2.2.0`, pinned by digest, against the database a 50,000 drain left. After a 15 s warm up at 20 requests per second that is not reported, three constant arrival rate stages run one after another for 60 s each, 5 s apart, at 20, 50 and 100 requests per second. Each request is, in equal thirds, the dashboard's recent alerts query over GraphQL (a page of 20), one alert by `event_id` over REST (ids chosen with a fixed stride across all 50,000 stored; each stage starts the sequence again, so a run reads 2,000 distinct ids), or the current space weather over REST. A request counts as an error unless it answers 200 within 4 s, and a GraphQL answer also counts as one if it carries errors or no alerts.
* **Commands,** from the repository root:

```sh
deploy/load/ingest-drain.sh --count 10000 --seed 1 --out docs/perf/data/load-drain-10000-<UTC stamp>.txt
deploy/load/ingest-drain.sh --count 50000 --seed 1 --out docs/perf/data/load-drain-50000-<UTC stamp>.txt
deploy/load/read-latency.sh --drain docs/perf/data/load-drain-50000-<UTC stamp>.txt --out docs/perf/data/load-read-<UTC stamp>.txt
```

* **Outputs.** The scripts wrote each file to a directory outside the repository, so that earlier result files would not mark the work tree as changed in later headers, and the files were then copied here unaltered (compared byte for byte):
  * Drain at 10,000: [A](data/load-drain-10000-20261028T041258Z.txt), [B](data/load-drain-10000-20261028T041445Z.txt), [C](data/load-drain-10000-20261028T041638Z.txt)
  * Drain at 50,000: [D](data/load-drain-50000-20261028T042557Z.txt), [E](data/load-drain-50000-20261028T043455Z.txt), [F](data/load-drain-50000-20261028T044447Z.txt)
  * Reads, one after each 50,000 drain, in the same order: [after D](data/load-read-20261028T043123Z.txt), [after E](data/load-read-20261028T044116Z.txt), [after F](data/load-read-20261028T044902Z.txt)

**How times are taken.** The drain time is the span between the first and the last stored row's `received_at`, which MySQL sets when each row is inserted, with microsecond precision. The rate is the number of events minus one divided by that span. Starting the JVM and joining the consumer group are left out and reported on their own, as the time from starting `query-api` to the first stored row. The drain window still includes JIT warm up after that first row. `received_at` is when the insert began, not when its transaction committed. Read latencies are k6's `http_req_duration` per request, from k6's own clock.

**Where it ran.** What the output files record: an Intel Core i5-9400H (8 processors), 14.9 GiB of memory, Docker 29.1.3, the Kafka and MySQL image digests, the local image ID of each `query-api` build, and the 1, 5 and 15 minute load averages at the start and end of each run. What they do not record, so it is stated here rather than recorded: the machine was a Dell Latitude 5401 laptop (4 cores, 8 threads) on Linux 6.17.0-41-generic. My usual local stack was stopped, but the laptop was not otherwise idle. k6 ran on the same machine, so it shared the CPU with the system under test. `query-api` and MySQL each have a 512 MiB memory limit and Kafka 1 GiB, as in [the Compose file](../../deploy/compose.yaml). The drain script polls the stored count once a second and checks the dead letter topic every 15 polls, and both share the CPU with the drain. The 10,000 runs were at commit `610f43f` and the 50,000 runs at `5c4b769`. The only code difference between those commits is how the scripts pass the MySQL option file, so `query-api`'s source is identical. The drain script rebuilds the `query-api` image on every run, so each output file records a different local image ID.

## 1. Draining a stored backlog

| Run | events | start up to first stored | drain | events per second | 1 min load average, start to end |
|---|---|---|---|---|---|
| A | 10,000 | 6.651 s | 60.1 s | 166.3 | 2.70 to 3.43 |
| B | 10,000 | 6.763 s | 69.5 s | 143.9 | 4.55 to 3.53 |
| C | 10,000 | 5.866 s | 54.0 s | 185.1 | 2.53 to 3.29 |
| D | 50,000 | 6.123 s | 277.3 s | 180.3 | 1.24 to 2.99 |
| E | 50,000 | 6.426 s | 321.2 s | 155.6 | 2.27 to 2.34 |
| F | 50,000 | 6.137 s | 209.4 s | 238.7 | 1.71 to 3.31 |

At 10,000 events the median is 166.3 events per second (143.9 to 185.1). At 50,000 it is 180.3 (155.6 to 238.7). Every run stored every event, sent none to `alerts.dlq`, and left the consumer group with lag 0. Start up to the first stored row was 5.9 to 6.8 s.

The run to run spread is wide: the fastest 50,000 run was 53 percent faster than the slowest. Run B started at the highest load average and was the slowest of its size, but run F started at a higher load average than run D and was still the fastest, so the recorded load averages do not explain the spread on their own. With three runs per size, I read the two sizes as overlapping rather than one being faster.

`query-api` stores each event in its own transaction and commits the Kafka offset after each record (`ack-mode: record`), with one partition and so one consumer thread. These figures describe that design on this machine. They do not show how throughput would change with more partitions or consumers, which this test did not try.

## 2. Read latency

Each cell is the median of the three runs, with the lowest and highest of the three in brackets, in milliseconds. A p99 here is the median of three per run p99 values, not the p99 of all requests pooled. Requests and errors are totals over the three runs.

**20 requests per second**

| Request | p50 | p95 | p99 | max | requests | errors |
|---|---|---|---|---|---|---|
| GraphQL recent alerts | 10.3 (3.8 to 10.9) | 14.8 (12.9 to 15.5) | 19.7 (15.5 to 22.3) | 23.0 (22.7 to 29.7) | 1,201 | 0 |
| REST alert by id | 3.9 (1.7 to 4.3) | 5.2 (5.1 to 5.5) | 5.9 (5.9 to 6.2) | 7.0 (7.0 to 12.8) | 1,200 | 0 |
| REST current space weather | 3.4 (1.5 to 3.7) | 4.6 (4.6 to 4.9) | 5.6 (5.1 to 5.7) | 9.7 (5.5 to 15.7) | 1,200 | 0 |

**50 requests per second**

| Request | p50 | p95 | p99 | max | requests | errors |
|---|---|---|---|---|---|---|
| GraphQL recent alerts | 3.5 (3.3 to 9.4) | 10.5 (5.4 to 12.8) | 14.4 (9.7 to 16.1) | 18.9 (13.3 to 22.9) | 3,003 | 0 |
| REST alert by id | 1.4 (1.3 to 3.3) | 3.8 (1.8 to 4.0) | 4.0 (3.5 to 4.5) | 7.6 (5.5 to 8.2) | 3,000 | 0 |
| REST current space weather | 1.2 (1.1 to 2.9) | 3.3 (1.6 to 3.5) | 3.6 (3.0 to 3.9) | 5.1 (4.6 to 14.0) | 3,000 | 0 |

**100 requests per second**

| Request | p50 | p95 | p99 | max | requests | errors |
|---|---|---|---|---|---|---|
| GraphQL recent alerts | 7.1 (3.0 to 8.0) | 9.7 (8.2 to 9.9) | 13.1 (12.3 to 13.9) | 18.2 (16.6 to 21.8) | 6,002 | 0 |
| REST alert by id | 2.7 (1.2 to 2.9) | 3.3 (3.2 to 3.4) | 4.5 (3.5 to 5.5) | 8.0 (7.4 to 9.2) | 6,000 | 0 |
| REST current space weather | 2.3 (1.1 to 2.5) | 2.9 (2.8 to 2.9) | 3.1 (3.1 to 3.4) | 6.4 (6.2 to 7.1) | 6,000 | 0 |

Across the 30,606 measured requests none was an error, and no stage dropped an iteration. A stage at R requests per second for 60 s completed either 60 R or 60 R plus one iterations. The output files show the latter as `unaccounted` minus 1, which means one more than the target, not one missing.

Latency did not rise steadily with the request rate. In all three runs the 20 per second stage had the highest median for every request type. Differences between runs at the same rate were as large as differences between rates: the GraphQL median at 100 per second was 3.0 ms in the read after D and 7.1 and 8.0 ms in the other two, while at 50 per second the read after E was close to the read after D (3.3 and 3.5 ms). From 50 to 100 per second the median p50 did rise for every request type, for example GraphQL from 3.5 to 7.1 ms. I do not know why. CPU frequency scaling, other work on the laptop, and warm up that 15 s did not cover are all possible causes, and nothing these files record separates them. What the figures do support is narrower: up to 100 requests per second on this machine, every request passed the error test above (a 200 within 4 s, and for GraphQL no errors and a non empty page), and the slowest single request in any measured stage took 29.7 ms. They do not find the rate at which latency starts to climb.

The GraphQL query is slower than the REST reads because it returns 20 alerts with their nested fields and goes through GraphQL parsing, validation and the query limits, where each REST read returns one row or one small object. The current space weather endpoint read the generated data. A single request to it after the last run answered `no_data` for G, R and S; that was a one off read, not committed, and it suggests this test timed that state rather than one with live levels.

## What this does not cover

* More than one partition or consumer, and so any scaling of the drain.
* The risk engine's throughput and the WebSocket subscription's fan out to many dashboards.
* Signed in requests and the acknowledgement write path.
* Any cloud deployment. These numbers come from one laptop with the load generator on the same machine.
