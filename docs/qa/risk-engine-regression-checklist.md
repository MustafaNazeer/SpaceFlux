# Risk engine regression checklist

This is the list of checks I run before calling a change to the `risk-engine` service done. It covers the Java service, the topics it reads (`raw.gp`, `raw.swpc`) and writes (`alerts`, `alerts.dlq`, `raw.gp.dlq`, `raw.swpc.dlq`), the schemas under `schemas/`, and the `risk-engine` service in the `core` Compose profile. The ingest side has its own list, [the ingest regression checklist](ingest-regression-checklist.md).

It has two parts. The automated checks run from a shell and need nothing beyond a JDK 21, Docker, and Python 3.11 or later with `jsonschema`. The acceptance checks need a running stack and a person reading the results, so each one is written as a numbered condition that either holds or does not, with the exact commands to paste. None of them is marked as passed here; results go in the log at the end.

Every command runs from the repository root unless it says otherwise.

## Automated checks

### A1. The full build from a clean tree

```sh
./mvnw -B clean verify
```

Passes when Maven prints `BUILD SUCCESS`, the summary line reads `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`, and no line starts with `[WARNING]`. The two integration test classes, `RiskEngineKafkaIntegrationTest` and `GpRestartIntegrationTest`, each start their own `apache/kafka:4.3.1` container through Testcontainers, so Docker must be running.

Always include `clean`. A plain `verify` leaves old reports in `risk-engine/target/surefire-reports/`, so a test that was renamed or deleted is still counted by anything that reads that directory.

### A2. Test counts, twice

```sh
for run in 1 2; do
  ./mvnw -B -q clean verify > /dev/null 2>&1; echo "build exit $?"
  python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
classes = tests = failures = errors = skipped = 0
for path in glob.glob("risk-engine/target/surefire-reports/TEST-*.xml"):
    r = ET.parse(path).getroot()
    classes += 1
    tests += int(r.get("tests")); failures += int(r.get("failures"))
    errors += int(r.get("errors")); skipped += int(r.get("skipped"))
print(f"classes {classes} tests {tests} failures {failures} errors {errors} skipped {skipped}")
EOF
done
```

Passes when both runs print `build exit 0` and the same counts with no failure, error, or skipped test. On 2026-10-02, at commit 59885e7, each run printed 33 test classes and 370 tests, all passing; each build took about 2 minutes 10 seconds. Later on 2026-10-02, on a second machine, two clean builds of the changes that follow 59885e7 each ran 36 test classes and 397 tests, all passing; each build took about 4 minutes 30 seconds. A difference between the two runs is a flaky test and fails this check.

### A3. Every schema example against its schema, independently of the Java validator

```sh
python3 - <<'EOF'
import json, glob
from jsonschema import Draft202012Validator, FormatChecker
names = ("raw.gp", "raw.swpc", "dlq", "alerts")
schemas = {n: json.load(open(f"schemas/{n}/v1.schema.json")) for n in names}
for s in schemas.values():
    Draft202012Validator.check_schema(s)
v = {n: Draft202012Validator(s, format_checker=FormatChecker()) for n, s in schemas.items()}
for path in sorted(glob.glob("schemas/*/examples/*.json")):
    errors = [e.message for e in v[path.split("/")[1]].iter_errors(json.load(open(path)))]
    print(path, "valid" if not errors else errors)
EOF
```

Passes when `check_schema` raises nothing and all 15 example files print `valid`: 9 under `schemas/alerts/examples/`, 1 under `schemas/dlq/examples/`, 1 under `schemas/raw.gp/examples/`, and 4 under `schemas/raw.swpc/examples/`.

### A4. The ingest suite is still green

The risk engine shares the `raw.*` schemas and the Compose file with `ingest`, so a change here runs the ingest checks A1 and A2 too. On 2026-10-02, at commit 59885e7, `gofmt -l` and `go vet` printed nothing and two runs of `go test -race -count=1 ./...` each printed 86 passing top level tests, 49 passing subtests, 12 passing packages, and one package with no test files.

### What the automated suite covers

| Behavior | Tests |
|---|---|
| A `raw.gp` record that fails its schema is dead lettered with `check` `schema` | `aRecordThatFailsItsSchemaIsDeadLetteredWithCheckSchema` (`GpProcessorTest`) |
| `raw.gp` rule dead letters: `fetched_at` after the engine's clock, an epoch after its own `fetch` | `anEventFetchedAfterTheEnginesClockIsRejectedAndChangesNothing`, `anElementSetDatedAfterItsFetchIsRejectedAndDoesNotReplaceTheHeldOne` (`GpProcessorTest`) |
| A `fetched_at` that is not a plain UTC time (an hour of 24, or more than nine fraction digits) is dead lettered with `check` `schema` on both input topics, and with `check` `rule` if the schema's format check were ever lost | `aFetchedAtThatIsNotAPlainUtcTimeIsDeadLetteredAndNotHeld`, `withoutTheFormatCheckAnUnreadableFetchedAtIsARuleDeadLetter` (`GpProcessorTest`); `aFetchedAtThatIsNotAPlainUtcTimeIsDeadLettered`, `withoutTheFormatCheckAnUnreadableFetchedAtIsARuleDeadLetter` (`SwpcProcessorTest`) |
| An hour of 24 in an SWPC time is rejected, even at midnight and with a lowercase `t` | `anHourOf24IsRejectedEvenAtMidnight` (`ReadingTest`) |
| An `EPOCH` with an hour of 24, with or without a fraction, cannot be converted | `rejectsInvalidField` (`GpElementSetsTest`) |
| A `raw.gp` event fetched at the real leap second `2016-12-31T23:59:60Z` is held, and its run window starts at `2016-12-31T23:59:59Z` | `aFetchedAtAtARealLeapSecondIsHeldAsTheSecondBefore` (`GpProcessorTest`) |
| A second of 60 is read only at 23:59:60, as 23:59:59 of that date on any date, and rejected at 23:58:60 | `aSecondOf60IsAcceptedOnlyAtTheLastMinuteOfADay` (`ReadingTest`) |
| An element set the propagator cannot use is dead lettered with no `check` and the batch continues | `anElementSetOrekitCannotUseIsDeadLetteredWithoutACheck` (`GpProcessorTest`); `noMutatedElementSetThrowsOrMakesARunFailItsSchema` (`GpProcessorFuzzTest`, seed 42) |
| An element set with values a two line element set cannot hold, including a catalog number of 340000, is held and screened, not dead lettered | `anElementSetBeyondTheLineFormatIsHeldAndScreened`, `aCatalogNumberBeyondTheLineFormatIsHeldAndScreened` (`GpProcessorTest`) |
| Same epoch copies are compared field by field with the name, never through formatted lines: a copy with other elements is listed, an identical one is not, a difference below line precision is still listed, and a copy no line can hold is listed without stopping the run | `aSameEpochCopyWithOtherElementsIsListedAsADifferingCopy`, `anIdenticalCopyIsNotADifferingCopy`, `aCopyDifferingBelowLinePrecisionIsStillListed`, `aSameEpochCopyWhoseElementsCannotBeWrittenAsLinesIsListedWithoutStoppingTheRun`, `aDifferingCopyWithoutANameStillGivesASchemaValidRun` (`GpProcessorTest`) |
| A `raw.swpc` record that fails its schema, or is not JSON, is dead lettered | `aRecordThatFailsItsSchemaIsDeadLetteredWithCheckSchema`, `bytesThatAreNotJsonAreDeadLetteredWithoutAUrl`, `aMalformedUrlInAFailedRecordIsLeftOutOfItsDeadLetter` (`SwpcProcessorTest`) |
| `raw.swpc` rule dead letters, and a rejected newest value sets the series to no data | `aRuleRejectionIsDeadLetteredWithCheckRuleAndSetsNoData` (`SwpcProcessorTest`); every case in `ReadingTest`; `noMutatedRecordThrowsOrVanishes` (`SwpcProcessorFuzzTest`) |
| An X-ray flux of exactly 0 is counted, not dead lettered | `aZeroFluxIsCountedNotDeadLettered` (`SwpcProcessorTest`) |
| A space weather event that fails the `alerts` schema goes to `alerts.dlq` | `anAlertThatFailsItsSchemaGoesToTheAlertsDeadLetterTopic` (`SwpcProcessorTest`) |
| Screening events that fail the `alerts` schema go to `alerts.dlq` with `check` `schema`, keyed by the run's `run_id` | `runEventsThatFailTheAlertsSchemaGoToAlertsDlqUnderTheRunId` (`GpProcessorTest`) |
| The dead letter envelope: reason cap, payload cap on a character boundary, base64 for bytes that are not UTF-8 or would inflate | every case in `DeadLettersTest` |
| Schema guards: duplicate keys, documents over 1 MiB, no remote references, no runaway patterns | `TopicSchemasTest` |
| Records with dead letters and alerts against a real broker, offset committed only after both were written | `recordsInBecomeAlertsAndDeadLettersOutAndTheOffsetIsCommitted` (`RiskEngineKafkaIntegrationTest`) |
| A `raw.gp` record that fails its schema reaches `raw.gp.dlq` on a real broker with `check` `schema` | `anElementSetThatFailsItsSchemaReachesRawGpDlq` (`RiskEngineKafkaIntegrationTest`) |
| A test that sets no broker of its own points at an address that reaches nothing, never at a local Compose broker | `aContextWithoutItsOwnBrokerPointsAtAnAddressThatReachesNothing` (`RiskEngineApplicationTest`) |
| A batch whose write failed is retried without end and gives the same events as without the failure | `theRetryBackOffNeverGivesUpAndStaysUnderAMinute` (`KafkaConfigTest`); `aFailedWriteResetsTheSeriesSoTheRedeliveredBatchPublishesAgain`, `aBatchRedeliveredAfterAFailedWriteGivesTheSameEventsAsWithoutTheFailure`, `writingStopsAtTheFirstFailedSend` (`SwpcListenerTest`); `afterARestoreARedeliveredBatchPublishesTheSameEventsAgain`, `aRedeliveredBatchPublishesNothingNew` (`SwpcProcessorTest`) |
| A failed write of the space weather timer events restores the series, and the next check computes and writes them again | `aClockCheckWhoseWriteFailedIsComputedAgainOnTheNextCheck` (`SwpcListenerTest`) |
| A failed write of a screening run is written on the next check, and once written, never again | `aRunWhoseWriteFailedIsWrittenOnTheNextCheckAndThenNotAgain` (`GpListenerTest`) |
| A consumer error with no record waits 5 s before the next poll, on both listener factories | `anErrorWithNoRecordWaitsFiveSecondsBeforeTheContainerPollsAgain`, `bothListenerFactoriesWaitOnErrorsWithNoRecord` (`KafkaConfigTest`) |
| Every compression codec is decoded at startup, and one that cannot load stops the start and names its setting | `everyCodecAProducerMayUseDecodesHere`, `aCodecThatCannotLoadItsNativeLibraryStopsTheStartAndNamesItsSetting` (`CompressionCheckTest`); `checksEveryCompressionCodecAtStartup` (`RiskEngineApplicationTest`) |
| Stale feeds and no data: age limits, timer fallback refreshes, replayed archive data, zero runs and their edges, rejected values, a primary satellite switch | `ScaleTrackerTest`, `ScaleTrackerEdgeCasesTest`; `theTimerPublishesNoDataWhenASeriesAges` (`SwpcProcessorTest`) |
| Empty inputs: no element sets, records that feed no scale, a watchlist object missing from the input, a run with only the watchlist object, an empty or missing watchlist file | `anEmptyStateScreensNothing`, `aWatchlistObjectMissingFromTheInputIsListedAsNotInInput` (`GpProcessorTest`); `recordsNoScaleIsReadFromPublishNothing` (`SwpcProcessorTest`); `aMissingWatchlistFailsToLoad`, `anEmptyOrMalformedWatchlistFailsToLoad` (`WatchlistTest`) |
| The screening consumer never commits, even after a recovered batch, and screens only the newest batch of a replay | `theScreeningConsumerNeverCommitsEvenAfterARecoveredBatch` (`KafkaConfigTest`); `aReplayOfSeveralBatchesScreensOnlyTheNewest`, `aRepeatedFetchWithNoNewerElementSetKeepsTheEarlierWindowStart` (`GpProcessorTest`); the offset assertion in `elementSetsInBecomeOneScreeningRunOut` (`RiskEngineKafkaIntegrationTest`) |
| Run identity within one process: a written run is never produced again, a late element set waits for the next newer fetch, an unwritten run is written again | `aPublishedRunIsNotRepeatedButAnUnpublishedOneIs`, `aWrittenRunIsNeverProducedAgainAndALateElementSetWaitsForTheNextFetch`, `aLateElementSetForAWrittenRunIsLoggedOnceAndNotRecomputed`, `recordsAlreadyHeldDoNotStartANewRun` (`GpProcessorTest`) |
| Run identity across a restart: with the screening group's committed offset moved to the end of `raw.gp`, a restarted application reads `raw.gp` from the beginning and writes the newest run again under the same `run_id` and `event_id` | `aRestartRepublishesTheNewestRunUnderTheSameIdentity` (`GpRestartIntegrationTest`) |
| The 30 s quiet period before a run | `aBatchIsScreenedOnceNoRecordHasArrivedForThirtySeconds` (`GpProcessorTest`) |
| Summary size: names cut to 64 code points, at most 3 differing copies, the 900,000 byte budget and the cut order | `aNameLongerThanSixtyFourCodePointsIsCutOnArrival`, `atMostThreeDifferingCopiesAreHeldAndLaterOnesAreCountedWithoutDelayingTheRun`, `aNewerEpochClearsTheOverCapCount`, `anObjectOverTheCopyCapThatAgesOutLeavesNoCount` (`GpProcessorTest`); every case in `SummaryBudgetTest` |
| The committed cut summary example, `schemas/alerts/examples/valid-screening-run-cut.json`, is exactly what the summary code writes at a 2,000 byte test budget, fits that budget, and keeps its counts and the watchlist entry | `writesTheCommittedCutScreeningRunExample`, `theExampleIsCutWithinItsBudgetAndKeepsCountsAndTheWatchlistEntry` (`ScreeningCutExampleTest`) |
| A screening run from the recorded stations file against a real broker | `elementSetsInBecomeOneScreeningRunOut` (`RiskEngineKafkaIntegrationTest`) |

### Not covered by automated tests yet

These are checked by hand below, or not at all, until a test exists:

1. **Process level behavior.** The startup codec check on a real noexec mount, the exit code on SIGTERM, and how long the 5 s wait can delay a shutdown. Acceptance checks R6 and R8 cover the first two by hand.
2. **Health endpoints.** The risk engine has no liveness or readiness endpoint yet, so nothing reports a stuck consumer or a broker that cannot be reached; its logs and the `alerts` topic are the only signals.
3. **Staleness of a screening run.** A run is stale 24 hours after its `window_start`. That rule belongs to whatever reads `alerts`, and nothing reads it yet.

Five gaps that used to be on this list now have tests, each a row in the table above: a failed write of a screening run (`GpListenerTest`), a screening event that fails the `alerts` schema (`runEventsThatFailTheAlertsSchemaGoToAlertsDlqUnderTheRunId`), a restart of the screening consumer (`GpRestartIntegrationTest`; acceptance check R4 still checks it on the running stack), `raw.gp.dlq` against a real broker (`anElementSetThatFailsItsSchemaReachesRawGpDlq`), and a failed write of the space weather timer events (`aClockCheckWhoseWriteFailedIsComputedAgainOnTheNextCheck`).

## Acceptance checks on a running stack

The two rules from the ingest checklist apply here too:

* **CelesTrak allows one download per update, every 2 hours.** Start the stack with `INGEST_FEEDS=swpc` unless a check says otherwise. The screening checks replay the element sets already on `raw.gp`, so they need no new CelesTrak request.
* **Never delete the `kafka-data` volume** to get a clean count. The checks compare only what was produced since the running `risk-engine` container started.

Common setup, pasted once per shell:

```sh
compose() { docker compose -f deploy/compose.yaml --profile core "$@"; }
kexec() { compose exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "$@"; }
started() { docker inspect -f '{{.State.StartedAt}}' "$(compose ps -q risk-engine)"; }
consume() {
  kexec /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic "$1" \
    --from-beginning --timeout-ms 20000 \
    --formatter-property print.key=true --formatter-property 'key.separator=|' 2>/dev/null
}
```

### R1. The core profile starts with the risk engine

```sh
INGEST_FEEDS=swpc compose up -d --build --force-recreate ingest risk-engine
sleep 60
compose ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}'
compose logs --no-color risk-engine | grep -c 'Started RiskEngineApplication'
compose logs --no-color risk-engine | grep -E ' (WARN|ERROR) ' | head -n 20
```

Holds when `kafka`, `ingest`, and `risk-engine` are `running`, `topics` is `exited` with exit code `0`, the count of `Started RiskEngineApplication` lines is `1`, and the last command prints nothing. A warning is not automatically a defect, but it fails this check until the printed line is explained. `--force-recreate` restarts the two services without touching `kafka` or its volume.

### R2. Mounts and log rotation are as configured

```sh
P="$(docker inspect -f '{{.State.Pid}}' "$(compose ps -q risk-engine)")"
grep -E ' /(tmp|native) ' "/proc/$P/mountinfo"
for s in kafka ingest risk-engine; do
  docker inspect -f "$s {{.HostConfig.LogConfig.Type}} {{index .HostConfig.LogConfig.Config \"max-size\"}} {{index .HostConfig.LogConfig.Config \"max-file\"}}" "$(compose ps -q $s)"
done
docker inspect -f '{{.HostConfig.ReadonlyRootfs}} {{.Config.User}}' "$(compose ps -q risk-engine)"
```

Holds when all of these are true:

1. The `/tmp` line's mount options include `noexec`, and its tmpfs options include `size=16384k`.
2. The `/native` line's mount options do not include `noexec` but do include `nosuid` and `nodev`, and its tmpfs options include `size=8192k`, `mode=700`, `uid=65532`, and `gid=65532`.
3. Each of the three services prints `json-file 10m 3`.
4. The last line reads `true nonroot:nonroot`.

### R3. Space weather events on `alerts`, per scale and valid

Wait at least 3 minutes after R1 so the first SWPC poll has been read, then:

```sh
consume alerts > /tmp/alerts.txt
python3 - "$(started)" /tmp/alerts.txt <<'EOF'
import collections, datetime, json, sys
from jsonschema import Draft202012Validator
schema = Draft202012Validator(json.load(open("schemas/alerts/v1.schema.json")))
since = datetime.datetime.fromisoformat(sys.argv[1])
recent = collections.Counter()
invalid = wrong_key = 0
for line in open(sys.argv[2]):
    if not line.strip():
        continue
    key, _, value = line.rstrip("\n").partition("|")
    event = json.loads(value)
    if event.get("kind") != "space_weather_level":
        continue
    if datetime.datetime.fromisoformat(event["produced_at"]) < since:
        continue
    level = event["space_weather_level"]
    recent[key, level["state"]] += 1
    invalid += not schema.is_valid(event)
    wrong_key += key != "space_weather." + level["scale"]
for k in sorted(recent):
    print(k, recent[k])
print(f"invalid {invalid}, key differs from scale {wrong_key}")
EOF
```

Holds when each of `space_weather.G`, `space_weather.R`, and `space_weather.S` appears on at least one printed line, and the last line reads `invalid 0, key differs from scale 0`. The state counts are for reading, not for passing: which states appear depends on the sky and on SWPC's files at the time.

### R4. A screening run appears after a restart, from the element sets already on `raw.gp`

This needs at least one CelesTrak batch on `raw.gp`; if the volume has none, run check C8 of the ingest checklist first. Then:

```sh
compose restart risk-engine
sleep 120
consume raw.gp > /tmp/raw-gp.txt
consume alerts > /tmp/alerts.txt
python3 - "$(started)" /tmp/raw-gp.txt /tmp/alerts.txt <<'EOF'
import collections, datetime, json, sys
from jsonschema import Draft202012Validator
parse = datetime.datetime.fromisoformat
schema = Draft202012Validator(json.load(open("schemas/alerts/v1.schema.json")))
since = parse(sys.argv[1])
newest = max(parse(json.loads(l.partition("|")[2])["fetched_at"]) for l in open(sys.argv[2]) if l.strip())
runs, approaches, invalid = [], collections.defaultdict(set), 0
for line in open(sys.argv[3]):
    if not line.strip():
        continue
    key, _, value = line.rstrip("\n").partition("|")
    event = json.loads(value)
    if event["kind"] not in ("screening_run", "close_approach") or parse(event["produced_at"]) < since:
        continue
    invalid += not schema.is_valid(event)
    if event["kind"] == "screening_run":
        runs.append((key, event["screening_run"]))
    else:
        approaches[key].add(event["event_id"])
print(f"newest fetched_at on raw.gp {newest.isoformat()}")
for key, run in runs:
    print(f"run {run['run_id']} key matches {key == run['run_id']} window_start {run['window_start']}",
          f"approach_count {run['approach_count']} approaches received {len(approaches[key])}",
          f"listed match {set(run['approach_event_ids']) == approaches[key]} omitted {sum(run['omitted'].values())}",
          f"coverage {run['coverage']}")
print(f"runs since start {len(runs)}, invalid {invalid}")
EOF
```

Holds when all of these are true:

1. `runs since start` is at least 1 and `invalid` is 0.
2. Every run line shows `key matches True`.
3. The newest run's `window_start` is the same instant as the printed newest `fetched_at` on `raw.gp`. (The one legitimate exception is a newest batch whose every record was dead lettered; then R5 shows those dead letters.)
4. On that run line, `approach_count` equals `approaches received`, `listed match` is `True`, and `omitted` is `0`.
5. Its `coverage` shows `watchlist_accepted` of `1`.

Running R4 twice without a new CelesTrak batch in between must print the same `run_id` both times; that is the replay behavior described in the setup guide.

### R5. No dead letters during a healthy run

```sh
for t in raw.gp.dlq raw.swpc.dlq alerts.dlq; do
  consume "$t" | python3 -c '
import datetime, json, sys
since = datetime.datetime.fromisoformat(sys.argv[1])
count = 0
for line in sys.stdin:
    if not line.strip():
        continue
    key, _, value = line.rstrip("\n").partition("|")
    letter = json.loads(value)
    if datetime.datetime.fromisoformat(letter["failed_at"]) >= since:
        count += 1
        print(key, letter["service"], letter["stage"], letter.get("check"), letter["reason"][:200])
print(sys.argv[2], "dead letters since start:", count)' "$(started)" "$t"
done
```

Holds when all three topics print `dead letters since start: 0`. Otherwise each printed line names the key, the service that wrote it, the stage, the check, and the start of the reason, and the check stays failed until that reason is understood.

### R6. A noexec native directory stops the start with a clear message

This runs the same image outside Compose, with no network, so it cannot touch the running stack:

```sh
docker run --rm --network none --read-only --memory 512m \
  --tmpfs /tmp:size=16m \
  --tmpfs /native:noexec,nosuid,nodev,size=8m,uid=65532,gid=65532,mode=0700 \
  -e 'JAVA_TOOL_OPTIONS=-Dorg.xerial.snappy.tempdir=/native -DZstdTempFolder=/native' \
  spaceflux-risk-engine:latest > /tmp/noexec.log 2>&1
echo "exit $?"
grep -o 'cannot decode [^:]*' /tmp/noexec.log | sort -u
```

Holds when the output reads `exit 1` and the line below it starts with `cannot decode snappy compressed records` and names `-Dorg.xerial.snappy.tempdir`. Run R1 first so that `spaceflux-risk-engine:latest` is the image built from the working tree.

### R7. Memory stays inside the Compose limit

```sh
deploy/measure-ram.sh 360 5
docker inspect -f 'risk-engine OOMKilled={{.State.OOMKilled}} limit={{.HostConfig.Memory}}' "$(compose ps -q risk-engine)"
```

Holds when the summary's maximum usage for `risk-engine` is below 512 MiB and the container reports `OOMKilled=false limit=536870912`. Compare the samples with the runs recorded in [the memory report](../perf/local-memory.md). A run that is meant to measure screening cost must have a screening run inside its window; restart `risk-engine` just before starting the script, as in R4.

### R8. Clean shutdown

```sh
RE="$(compose ps -q risk-engine)"
compose stop risk-engine
docker inspect -f 'exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$RE"
docker logs "$RE" 2>&1 | tail -n 40 | grep -c 'Consumer stopped'
```

Holds when the output reads `exit=143 oom=false` and the count below it is `2`, one for the `raw.swpc` consumer and one for the `raw.gp` screening consumer. 143 is the JVM's exit status after a SIGTERM it handled; 137 would mean Docker had to kill it after the stop timeout, which fails this check. Restart afterwards with `INGEST_FEEDS=swpc compose up -d`.

## Results log

One row per check per run. A check is passed only when someone has run it and read the output; nothing here is filled in ahead of time.

| Date (UTC) | Commit | Check | Result | Notes |
|---|---|---|---|---|
