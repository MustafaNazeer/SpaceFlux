# Screening cost

This page records how long a close approach screening run takes in the risk engine, where that time goes, and what grows when the catalog grows. Every number below comes from one committed harness run three times against one committed fixture. It answers the question for the catalog the system screens today, the CelesTrak stations group. It does not answer it for a full catalog, and the last section says why and what data that would take.

## Harness and data

* **Harness:** [`ScreeningCostBenchmark`](../../risk-engine/src/test/java/io/github/mustafanazeer/spaceflux/risk/screening/ScreeningCostBenchmark.java), a JUnit test that is skipped unless the system property `screening.cost` is `true`, so it does not slow the default build. It calls the production screening code directly (`Screening.run`, `ObjectTrack.sample`, `RadialPrefilter`, `ClosestApproachSearch.find`); the sampled separation that backs the stack and co-orbiting checks is a private method of `Screening`, and the harness calls it through reflection, so it times the production method rather than a copy.
* **Fixture:** [`orbit-core/src/test/resources/celestrak/gp-stations.json`](../../orbit-core/src/test/resources/celestrak/gp-stations.json), a recorded CelesTrak stations group response with 22 element sets ([provenance](../../orbit-core/src/test/resources/celestrak/PROVENANCE.md)), the named stacks in `risk-engine/src/main/resources/screening/stacks.json`, and the fixed window start 2026-09-27T05:00:00Z used by the screening tests. Element set ages at that start range from 0.018 to 2.288 days.
* **Settings:** the production values, a 7 day window sampled every 10 s (60,481 samples per object), a 5 km report distance, and the 500 km co-orbiting bound.
* **Command,** from the repository root:

```sh
./mvnw -B -q -pl risk-engine -am test -Dtest=ScreeningCostBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
    -Dscreening.cost=true -Dscreening.cost.out=$PWD/docs/perf/data/screening-cost-<UTC stamp>.txt
```

* **Outputs:** three runs, each in its own JVM, committed as printed except for one edit: the third header line also listed the JVM input arguments, which carried a local file system path, so that list was removed from each file and the harness no longer prints it. Every other line, every timing included, is as printed:
  * Run A: [`data/screening-cost-20261002T005758Z.txt`](data/screening-cost-20261002T005758Z.txt)
  * Run B: [`data/screening-cost-20261002T010318Z.txt`](data/screening-cost-20261002T010318Z.txt)
  * Run C: [`data/screening-cost-20261002T010811Z.txt`](data/screening-cost-20261002T010811Z.txt)

**How times are taken.** Wall clock time from `System.nanoTime()` on one thread. Each figure is preceded by 3 warm up calls and then measured over 10 calls; the tables give the median of those 10, and for whole runs also the minimum, maximum and sample standard deviation. Stages in section 2 are timed one at a time on the same inputs, after the whole run has been timed, and each stage call gets its own 3 warm up calls and 10 measured calls, so their sum is a sum of separate medians taken at different moments, not a split of the whole run's time; section 2 shows how far apart the two came out.

**Where it ran.** What the output files record: Java 21.0.11 (OpenJDK 64-Bit Server VM), 8 available processors, a 3,816 MiB maximum heap, and the host's load averages at the start and end of each run. The JVM ran under Maven Surefire with this module's settings from `risk-engine/pom.xml` (`-Xshare:off` and the Mockito agent). What the files do not record, so it is stated here rather than recorded: the machine was a Dell Latitude 5401 laptop with an Intel Core i5-9400H (4 cores, 8 threads), 14 GiB of RAM and Linux 6.17.0-41-generic, and other builds were running on it during the runs. The service itself runs on Java 21.0.12.1 in its container with a 128 MiB heap and the serial collector ([local memory](local-memory.md#run-with-the-risk-engine)), so these figures describe the screening code, not the container.

The 1 minute load average was 4.87 at the start and 4.67 at the end of run A, 5.79 and 2.38 for run B, and 3.15 and 2.36 for run C. Load from other work on the host is possibly why run A is slower and noisier than runs B and C, but these readings do not show it: run B started at a higher load than run A and was still faster. With other work on the host, these figures are possibly higher than an idle machine of this kind would give, not lower.

## 1. A whole run as the service does it

Watchlist: the ISS (25544). Catalog: all 22 objects. Result in every run: 21 pairs, 7 suppressed by the International Space Station stack, 14 removed by the radial prefilter, none searched, no approaches. The live service's run on the newer stations fetch it screened during the [memory measurement](local-memory.md#run-with-the-risk-engine) showed the same counts when I read its summary back from Kafka; that was a one off read of the live stack, and its output is not committed.

| Run | median | min | max | stdev |
|---|---|---|---|---|
| A | 1271.3 ms | 1148.8 ms | 1410.7 ms | 95.6 ms |
| B | 1192.5 ms | 1162.2 ms | 1288.8 ms | 46.0 ms |
| C | 1164.1 ms | 1132.8 ms | 1259.5 ms | 35.2 ms |

Across all 30 measured runs the fastest took 1132.8 ms and the slowest 1410.7 ms. The spread between the three medians (1164.1 to 1271.3 ms) is wider than the spread inside runs B and C; varying load on the host is a possible cause, but the recorded load averages alone do not establish it.

A per pair figure for this run would mislead: none of its 21 pairs was searched, and most of the time goes to work done once per object, as the next section shows.

## 2. Where the time goes

| Stage | Run A | Run B | Run C |
|---|---|---|---|
| Sampling 22 object tracks (sum of per object medians) | 1026.2 ms | 909.9 ms | 745.3 ms |
| One object track, fastest to slowest object | 30.3 to 65.8 ms | 30.9 to 64.3 ms | 30.0 to 40.1 ms |
| Full window separation for the 7 stack pairs (sum) | 459.3 ms | 520.6 ms | 409.3 ms |
| The same, per stack pair | 65.6 ms | 74.4 ms | 58.5 ms |
| Radial prefilter for the 14 pairs it removes (sum) | 0.0042 ms | 0.0034 ms | 0.0026 ms |

* **Object tracks** are sampled once per admitted object, watchlist and catalog alike. Each track propagates the object at every 10 s sample of the window, and first from its element set epoch up to the window start, so that an object that has already decayed is caught before the window (the latch). With the fixture's ages that is 60,481 window samples plus 157 to 19,766 latch samples per object.
* **Stack pairs** are not searched, but the suppression record carries the pair's minimum and maximum sampled separation, so each one propagates both objects across the whole window.
* **The prefilter** compares two precomputed radial bands and costs a fraction of a microsecond per pair.

**The stage sum against the whole run.** Tracks plus stack pairs (the prefilter adds under 0.01 ms) sum to 1485.5 ms in run A against a whole run median of 1271.3 ms, 16.8 percent over; 1430.5 ms against 1192.5 ms in run B, 20.0 percent over; and 1154.6 ms against 1164.1 ms in run C, 0.8 percent under. Only run C's stage sum is close to its whole run. The whole run does the same work on one thread (`Screening.run` samples each track and then computes each stack pair's separation in turn, with no parallel code in the screening path), so the stages do not do more work than the whole run, and the gap comes from timing them separately. The files show how much separate timings of identical work can differ: the co-orbiting harness behind section 4 (section 5 of each output file) times the same separation call, on the same 7 ISS stack pairs, a second time in the same JVM, and those 7 full window figures sum to 426.9 ms in run A, 431.3 ms in run B and 418.8 ms in run C, against 459.3, 520.6 and 409.3 ms here. The track figures in runs A and B are also less even than in run C (30.3 to 65.8 ms and 30.9 to 64.3 ms per object, against 30.0 to 40.1 ms). The files do not show what slowed those stage timings, so I do not attribute it.

So by share of the stage sum, not of the whole run, track sampling is about two thirds (69.1, 63.6 and 64.6 percent in runs A, B and C) and the separation of the 7 stack pairs about one third (30.9, 36.4 and 35.4 percent).

## 3. Closest approach search per pair

The ISS run searches no pair, so the harness times the search on the pairs the all against all run (all 22 objects as the watchlist) searches: 9 pairs, none of them within 5 km.

| | Run A | Run B | Run C |
|---|---|---|---|
| Per pair, median of the 9 | 31.4 ms | 32.4 ms | 29.7 ms |
| Per pair, fastest to slowest | 27.8 to 37.1 ms | 28.0 to 35.6 ms | 25.2 to 32.1 ms |
| All 9 pairs (sum) | 287.3 ms | 285.4 ms | 260.2 ms |

The harness covers only these 9 pairs from the stations fixture. Search cost depends on how many separation minima the event detector has to find and refine over the window, so I would not assume the same figure for pairs on very different orbits without measuring them.

## 4. The co-orbiting check and its early exit

A pair that passes the prefilter, is not in a named stack, and has both tracks covering the whole window is first checked against the 500 km co-orbiting bound: its separation is sampled every 10 s and the check stops at the first sample at or beyond 500 km. Only a pair that stays under the bound for the whole window pays for the whole window.

The harness runs this check on every pair of the fixture that passes the prefilter and has distinct element sets, 32 pairs, as it would see them without the named stacks, and times it twice: with the 500 km exit and with no exit.

| | Run A | Run B | Run C |
|---|---|---|---|
| Pairs that exit early | 9 | 9 | 9 |
| Their check with the exit, slowest pair | 0.007 ms | 0.004 ms | 0.005 ms |
| The same pairs over the whole window | 61.4 to 96.0 ms | 58.4 to 66.2 ms | 57.1 to 60.1 ms |
| Pairs that run the whole window | 23 | 23 | 23 |
| Their whole window check | 56.7 to 85.6 ms | 58.3 to 73.6 ms | 56.5 to 79.8 ms |

The 23 pairs that never exceed 500 km are exactly the named stack pairs, so in the service they are suppressed by the stack list and pay the same full window cost for their suppression record (section 2). The 9 pairs that exit do so within microseconds, against about 60 ms each without the exit. On this fixture the early exit is what keeps the co-orbiting check from costing as much as a search for every independent pair.

## 5. The latch and element set age

The latch samples from the element set epoch to the window start, so its cost grows with the age of the element set at the window start. Screening admits element sets up to 10 days old, which is up to 86,400 latch samples on top of the 60,481 window samples. The harness samples the ISS track with the window start moved to its epoch plus d days.

| d | latch samples | Run A | Run B | Run C |
|---|---|---|---|---|
| 0 | 0 | 39.9 ms | 32.1 ms | 31.4 ms |
| 1 | 8,640 | 42.7 ms | 36.2 ms | 35.2 ms |
| 2.5 | 21,600 | 67.1 ms | 42.4 ms | 40.4 ms |
| 5 | 43,200 | 63.6 ms | 52.3 ms | 53.8 ms |
| 10 | 86,400 | 96.5 ms | 74.1 ms | 81.6 ms |

In runs B and C, whose rows here have the smaller spread, a track with a 10 day old element set took about 2.3 to 2.6 times as long as one at age 0. Run A's 2.5 day row is out of line with its neighbours and has the largest spread of its rows; possibly load on the host, since runs B and C do not repeat it, but the files do not show the cause.

## 6. Growth with the number of pairs on this fixture

To see how a run grows with pairs without new data, the harness makes the first k objects of the fixture the watchlist and keeps all 22 as the catalog. The number of tracks stays 22; only the pairs change.

| k | pairs | suppressed | prefiltered | searched | Run A | Run B | Run C |
|---|---|---|---|---|---|---|---|
| 1 | 21 | 7 | 14 | 0 | 1333.2 ms | 1176.4 ms | 1161.5 ms |
| 2 | 41 | 13 | 28 | 0 | 1659.0 ms | 1599.7 ms | 1512.0 ms |
| 4 | 78 | 22 | 56 | 0 | 2338.8 ms | 2077.6 ms | 2055.0 ms |
| 8 | 140 | 27 | 113 | 0 | 2530.2 ms | 2386.0 ms | 2447.2 ms |
| 16 | 216 | 31 | 176 | 9 | 3143.8 ms | 2922.5 ms | 2881.4 ms |
| 22 | 231 | 38 | 184 | 9 | 3435.2 ms | 3386.5 ms | 3278.6 ms |

Medians of 10 calls each. The rise follows the pairs that do propagation work (suppressed and searched), not the pair count: the 184 prefiltered pairs at k = 22 cost almost nothing, while the 38 suppressed and 9 searched pairs account for most of the growth over k = 1.

## What grows with catalog size

From the code and the figures above, for a watchlist of W objects and a catalog of N admitted objects:

* **Tracks: one per object, N + W at most.** Each costs about 30 ms at age 0 on this machine and roughly 2.5 times that with a 10 day old element set. This term grows linearly with the catalog regardless of how many pairs survive the prefilter.
* **Pairs: W times N.** Per pair the cost depends on its fate: a fraction of a microsecond when the prefilter removes it; microseconds when the co-orbiting check exits early; about 60 ms when it is a named stack pair or stays co-orbiting for the whole window; about 25 to 37 ms when it is searched.
* **Memory held during a run** is small per object: a track keeps its summary values (radial band, rate, where it stops), not its samples. The run summary event is bounded at 900,000 bytes, and every list beyond that is counted rather than listed.

What this fixture cannot tell me is the share of pairs in each fate for a real catalog. The stations group is 22 objects, nearly all in low Earth orbit near the two stations, and on it the prefilter removes 14 of the ISS's 21 pairs. In a full catalog spanning every altitude the prefilter share, the number of searched pairs, and the age distribution of element sets at the window start are all unmeasured, so I do not give a run time for a full catalog here. The arithmetic on the per object figure alone, for instance, would leave out the searched pairs, which are the part that cannot be estimated without data.

The screening run is computed on a scheduler thread, not on the consumer's poll thread, so a long run does not by itself risk `max.poll.interval.ms`; how long it would take at catalog scale is the open question.

## Measuring at catalog scale

A catalog scale figure needs a recorded fixture of a larger CelesTrak group, captured once with the same procedure and provenance as the stations fixture, and the same harness pointed at it. CelesTrak asks clients to download only the groups they need and enforces one download per update on its largest groups ([CelesTrak notes](../source/celestrak.md)), so that capture is a deliberate, one time request, not something the harness does.
