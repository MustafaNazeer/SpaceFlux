# bcrypt cost

The operator's password hash is bcrypt, and [ADR 0009](../adr/0009-alert-acknowledgement-auth.md) asks for the cost nearest one second per check, measured rather than assumed. This page records that measurement for the local machine and the cost chosen from it. It does not hold for the cloud environment, which gets its own measurement before acknowledgement is enabled there.

## Harness and data

* **Harness:** [`BcryptCostBenchmark`](../../query-api/src/test/java/io/github/mustafanazeer/spaceflux/query/auth/BcryptCostBenchmark.java), a JUnit test that is skipped unless the system property `bcrypt.cost` is `true`. It times the check a login runs, `matches` on the service's own encoder (`Operator.encoder(cost)`), against a wrong password, for each cost from 10 to 14: 2 warm up checks, then 7 measured checks, reporting the median, minimum and maximum.
* **Command,** from the repository root:

```sh
./mvnw -B -q -pl query-api -am test -Dtest=BcryptCostBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbcrypt.cost=true -Dbcrypt.cost.out=$PWD/docs/perf/data/bcrypt-cost-<UTC stamp>.txt
```

* **Outputs,** three runs, each in its own JVM, committed as printed:
  * Run A: [`data/bcrypt-cost-20261006T034948Z.txt`](data/bcrypt-cost-20261006T034948Z.txt)
  * Run B: [`data/bcrypt-cost-20261006T035011Z.txt`](data/bcrypt-cost-20261006T035011Z.txt)
  * Run C: [`data/bcrypt-cost-20261006T035032Z.txt`](data/bcrypt-cost-20261006T035032Z.txt)

**Where it ran.** What the files record: Java 21.0.11 (OpenJDK 64-Bit Server VM), 8 available processors, and the 1 minute load average at the start and end of each run (between 1.61 and 1.96). What they do not record: a Dell Latitude 5401 laptop with an Intel Core i5-9400H (4 cores, 8 threads), 14 GiB of RAM and Linux 6.17.0-41-generic. The JVM ran on the host, not in the service's container. The Compose service limits memory and processes but sets no CPU limit, and a bcrypt check needs a few kilobytes, so the container should not change these figures much; that is a reading of the limits, not a measurement in the container.

## Results

Median time of one check, in milliseconds:

| Cost | Run A | Run B | Run C |
|---|---|---|---|
| 10 | 57.6 | 59.8 | 59.2 |
| 11 | 115.0 | 118.3 | 118.9 |
| 12 | 229.0 | 235.7 | 244.1 |
| 13 | 459.8 | 471.0 | 471.4 |
| 14 | 916.3 | 942.9 | 945.5 |

Each step of the cost doubles the time, as bcrypt's design says it should. Cost 14 is the one nearest one second, at 916 to 946 ms in the three runs.

## Choice

The service defaults to cost 14 (`ACK_OPERATOR_BCRYPT_COST`), and the operator's hash must be made at exactly that cost, or nobody can sign in. A login therefore costs about one second of one core. At most two checks run at once and about 30 failures reach the hash in any 10 minutes ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 8), so failed logins take at most two cores at a time.
