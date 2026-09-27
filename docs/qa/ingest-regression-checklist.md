# Ingest regression checklist

This is the list of checks I run before calling a change to the `ingest` service done. It covers the Go service, its Kafka topics (`raw.gp`, `raw.gp.dlq`, `raw.swpc`, `raw.swpc.dlq`), the schemas under `schemas/`, the recorded fixtures under `ingest/testdata/`, and the `core` Compose profile.

It has two parts. The automated checks run from a shell and need nothing beyond Go, Docker, and Python with `jsonschema`. The acceptance checks need a running stack and a person reading the results, so each one is written as a numbered condition that either holds or does not, with the exact commands to paste. None of them is marked as passed here; results go in the log at the end.

Every command runs from the repository root unless it says otherwise.

## Automated checks

### A1. Format, vet, and the full test suite with the race detector

```sh
cd ingest
gofmt -l .
go vet ./...
go test -race -count=1 ./...
cd ..
```

Passes when `gofmt -l` prints nothing, `go vet` prints nothing, and every package reports `ok`. The Kafka integration tests in `internal/kafkapub` start an `apache/kafka:4.3.1` container through Testcontainers, so Docker must be running. `-count=1` stops Go from reusing cached results.

### A2. Test counts, twice

```sh
cd ingest
for run in 1 2; do
  go test -count=1 -json ./... | python3 -c '
import collections, json, sys
counts = collections.Counter()
for line in sys.stdin:
    try:
        event = json.loads(line)
    except ValueError:
        continue
    test, action = event.get("Test"), event.get("Action")
    if action in ("pass", "fail", "skip"):
        kind = "package" if test is None else "subtest" if "/" in test else "test"
        counts[kind, action] += 1
        if action != "pass" and test:
            print(action, event["Package"], test)
print(dict(counts))'
done
cd ..
```

Passes when both runs print the same counts with no failed and no skipped test. On 2026-09-27, after the last code change, each run printed 84 passing top level tests, 49 passing subtests, 12 passing packages, and one package skipped for having no test files (`internal/events`). A difference between the two runs is a flaky test and fails this check.

`go test -short ./...` skips the two Kafka tests that need a broker and is useful without Docker, but it does not count as passing A1 or A2.

### A3. Fixture integrity

```sh
(cd ingest/testdata/celestrak && sha256sum gp-stations.json gp-catnr-25544.json derived/*.json)
(cd ingest/testdata/swpc && sha256sum kp.json goes-xrays-6-hour.json goes-integral-protons-6-hour.json alerts.json derived/*.json)
```

Passes when every hash matches the table in the `PROVENANCE.md` file in the same directory. A mismatch means a recorded response was edited, which the fixtures promise never happens.

### A4. Schemas, examples, and fixtures against the schemas

This check is independent of the Go validator: it uses Python's `jsonschema` package with `Draft202012Validator`.

```sh
python3 - <<'EOF'
import json, glob
from jsonschema import Draft202012Validator, FormatChecker
schemas = {n: json.load(open(f"schemas/{n}/v1.schema.json")) for n in ("raw.gp", "raw.swpc", "dlq")}
for s in schemas.values():
    Draft202012Validator.check_schema(s)
v = {n: Draft202012Validator(s, format_checker=FormatChecker()) for n, s in schemas.items()}
for path in sorted(glob.glob("schemas/*/examples/*.json")):
    errors = [e.message for e in v[path.split("/")[1]].iter_errors(json.load(open(path)))]
    print("example", path, "valid" if not errors else errors)
def check(label, path, name, wrap):
    try:
        records = json.load(open(path))
    except ValueError:
        print(label, "not JSON"); return
    bad = [r for r in records if not v[name].is_valid(wrap(r))]
    print(f"{label}: {len(records)} records, {len(records) - len(bad)} valid, {len(bad)} invalid")
def swpc(product):
    return lambda r: {"schema_version": 1, "source": "swpc", "product": product, "fetched_at": "2026-09-27T16:30:37Z",
                      "source_url": "https://services.swpc.noaa.gov/", "record": r}
gp = lambda r: {"schema_version": 1, "source": "celestrak", "fetched_at": "2026-09-27T08:57:39Z",
                "source_url": "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=JSON", "gp": r}
t = "ingest/testdata/"
for product, f in [("swpc.kp", "kp.json"), ("swpc.goes.xrays", "goes-xrays-6-hour.json"),
                   ("swpc.goes.protons", "goes-integral-protons-6-hour.json"), ("swpc.alerts", "alerts.json"),
                   ("swpc.kp", "derived/kp-kp-string.json"), ("swpc.kp", "derived/kp-missing-time-tag.json"),
                   ("swpc.goes.xrays", "derived/goes-xrays-6-hour-missing-satellite.json"),
                   ("swpc.alerts", "derived/alerts-truncated.json"), ("swpc.kp", "derived/swpc-empty-array.json")]:
    check(f"swpc/{f} as {product}", t + "swpc/" + f, "raw.swpc", swpc(product))
for f in ["gp-stations.json", "gp-catnr-25544.json", "derived/gp-stations-missing-norad-cat-id.json",
          "derived/gp-stations-mean-motion-string.json", "derived/gp-catnr-25544-truncated.json", "derived/gp-empty-array.json"]:
    check(f"celestrak/{f}", t + "celestrak/" + f, "raw.gp", gp)
EOF
```

Passes when the output matches this table exactly:

| Input | Expected |
|---|---|
| All three schema files | `check_schema` raises nothing |
| All six files under `schemas/*/examples/` | valid |
| `swpc/kp.json` | 61 records, 61 valid |
| `swpc/goes-xrays-6-hour.json` | 716 records, 716 valid |
| `swpc/goes-integral-protons-6-hour.json` | 568 records, 568 valid |
| `swpc/alerts.json` | 68 records, 68 valid |
| `swpc/derived/kp-kp-string.json` | 61 records, 60 valid, 1 invalid |
| `swpc/derived/kp-missing-time-tag.json` | 61 records, 60 valid, 1 invalid |
| `swpc/derived/goes-xrays-6-hour-missing-satellite.json` | 716 records, 715 valid, 1 invalid |
| `swpc/derived/alerts-truncated.json` | not JSON |
| `swpc/derived/swpc-empty-array.json` | 0 records (the empty list path, not a schema failure) |
| `celestrak/gp-stations.json` | 22 records, 22 valid |
| `celestrak/gp-catnr-25544.json` | 1 record, 1 valid |
| `celestrak/derived/gp-stations-missing-norad-cat-id.json` | 22 records, 21 valid, 1 invalid |
| `celestrak/derived/gp-stations-mean-motion-string.json` | 22 records, 21 valid, 1 invalid |
| `celestrak/derived/gp-catnr-25544-truncated.json` | not JSON |
| `celestrak/derived/gp-empty-array.json` | 0 records |

`FormatChecker` asserts `date-time` only when the `rfc3339-validator` package is installed. Without it the `format` keyword is skipped, but every timestamp in these schemas also carries a `pattern`, which is always enforced.

### What the automated suite covers

| Behavior | Tests (package) |
|---|---|
| One poller per feed; a halted feed leaves the others running | `TestOneHaltedFeedLeavesOthersRunning` (`cmd/ingest`), `TestProductsMatchChosenFiles` (`swpcfeed`) |
| Polling interval floors and defaults (2 hours for CelesTrak, 1 minute for SWPC) | `TestLoadDefaults`, `TestLoadErrors` (`config`) |
| Full interval after success, after a 304, and after a bad 200 body | `TestSuccessWaitsFullInterval`, `TestUnchangedSkipsProcessAndWaitsInterval`, `TestBadBodyIsRejectedThenWaitsFullInterval` (`poller`) |
| Exponential backoff with full jitter, a floor, and a reset after success | `TestDelayFullJitter`, `TestDelayStaysWithinBounds`, `TestDelayNeverBelowMin` (`backoff`); `TestNetworkErrorBacksOffThenRecovers`, `TestBackoffResetsAfterSuccess` (`poller`) |
| CelesTrak: any non 200 halts with no retry; redirects are not followed | `TestFetchNon200HaltsWithStatusAndBody`, `TestFetchDoesNotFollowRedirects`, `TestFetchStatusErrorBodyIsBounded` (`celestrak`); `TestHTTPErrorHaltsWithoutRetry` (`poller`) |
| No HTTP response at all is retried | `TestFetchNetworkErrorIsRetryable`, `TestFetchTimeoutIsRetryable` (`celestrak`) |
| A 200 body that is too large or cut off is dead lettered at the `fetch` stage, with no early refetch | `TestFetchRejectsOversizedBody`, `TestFetchBodyCutOffAfter200IsBodyError` (`celestrak`); `TestRejectBodyDeadLettersPrefixAtFetchStage`, `TestRejectBodyReportsTrueSizeWhenPrefixShorter`, `TestRejectBodyAlwaysMarksPayloadTruncated` (`gpfeed`); `TestRejectBodyDeadLettersAtFetchStage` (`swpcfeed`); `TestRejectFailureRetriesRejectWithoutRefetch` (`poller`) |
| SWPC: 403 and 404 halt one product; 5xx, 429, and 301 do not | `TestHaltOnlyOnForbiddenOrNotFound` (`swpcfeed`) |
| SWPC conditional requests with `If-None-Match`; the ETag is cleared after an error and capped in size | `TestConditionalRequestReturnsNotModified`, `TestNotModifiedWithoutConditionalIsStatusError` (`feedhttp`); `TestFetcherSendsETagAndReportsUnchanged`, `TestETagClearedAfterErrorAndCapped` (`swpcfeed`) |
| Deduplication, CelesTrak (`NORAD_CAT_ID`, `EPOCH`) | `TestTracker` (`dedupe`); `TestUnchangedResponseIsNotRepublished`, `TestEpochComparedAsTimeNotText`, `TestDuplicateWithinOneResponsePublishedOnce` (`gpfeed`) |
| Deduplication, SWPC sliding window | `TestWindowPublishesOnlyRecordsAbsentFromPreviousResponse`, `TestWindowUncommittedResponseChangesNothing` (`dedupe`); `TestSlidingWindowPublishesOnlyNewRecords`, `TestDuplicateIdentityWithinOneResponsePublishedOnce`, `TestGOESPrimarySwitchPublishesBothSatellites`, `TestBadBodyBetweenGoodOnesRepublishesNothing` (`swpcfeed`) |
| Context cancellation | `TestCancelledContextStopsBeforeFetch`, `TestCancellationDuringFetchIsNotRetried`, `TestSleepContextHonorsCancellation` (`poller`); `TestFetchHonorsCancellation` (`celestrak`); cancellation return in `TestOneHaltedFeedLeavesOthersRunning` (`cmd/ingest`) |
| Dead letters for bad records and bodies, `raw.gp.dlq` | `TestInvalidRecordGoesToDLQAndOthersPublish`, `TestUndecodableBodyGoesToDLQWhole` (includes the empty array), `TestNonUTF8BodyIsBase64InDLQ`, `TestImpossibleEpochGoesToDLQ`, `TestOversizedRecordGoesToDLQTruncated`, `TestLargeUndecodableBodyIsTruncatedInDLQ`, `TestEscapeHeavyPayloadStillFitsOneKafkaRecord`, `TestTruncationKeepsWholeRunes`, `TestInvalidUTF8RecordGoesToDLQ` (`gpfeed`) |
| Dead letters for bad records and bodies, `raw.swpc.dlq`, keyed by product | `TestInvalidRecordDeadLetteredOthersPublish`, `TestUndecodableBodyDeadLetteredWithProductKey` (includes the empty Kp list), `TestSameBadRecordDeadLetteredOncePerAppearance`, `TestInvalidUTF8RecordDeadLettered` (`swpcfeed`) |
| An empty alerts list is accepted | `TestEmptyAlertsListIsNotAnError` (`swpcfeed`) |
| Every recorded record publishes, keyed correctly, bytes verbatim | `TestPublishesEveryRecordOfRealResponse`, `TestRecordBytesKeptVerbatimWithoutHTMLEscaping` (`gpfeed`); `TestEveryRealRecordPublishesKeyedByProduct` (`swpcfeed`) |
| Publish failure retries the same body without a new request, and marks nothing as seen | `TestProcessFailureRetriesSameBodyWithoutRefetch` (`poller`); `TestPublishFailureIsReturnedAndNothingMarkedSeen` (`gpfeed`); `TestPublishFailureKeepsWindowSoRetryRepublishes` (`swpcfeed`) |
| Kafka: keyed records keep per key order and partition; no topic auto creation; bounded failure with no broker | `TestPublishDeliversKeyedRecordsAcrossTopics`, `TestPublishToMissingTopicFails`, `TestPingFailsWhenBrokerUnreachable`, `TestPublishGivesUpWithinDeliveryTimeoutWhenBrokerUnreachable` (`kafkapub`, the first two against a real broker) |
| Liveness and readiness | `TestReadyWhenBrokerUpAndFeedRunning`, `TestNotReadyWhenBrokerDown`, `TestHaltedFeedFailsReadinessButNotLiveness`, `TestPublishFailureStreakFailsReadinessUntilSuccess`, `TestResponsesAreNotSniffed` (`health`) |
| Schema examples and malformed documents | `TestValidExamplesPass`, `TestInvalidDocumentsFail`, `TestValidateRejectsMalformedJSON`, `TestLoadMissingFile`, `TestNameAndDesignatorAreOptional` (`schema`) |

The SWPC backoff wiring (a 1 minute floor and the poll interval as the ceiling) is covered by `TestSWPCBackoffNeverBelowOneMinute`, and the Compose `topics` service's topic list is checked against the code's topic names by `TestComposeProvisionsEveryTopicTheCodePublishes`, both in `cmd/ingest`.

### Not covered by automated tests yet

These are checked by hand below, or not at all, until a test exists:

1. No Kafka integration test publishes to `raw.swpc` or `raw.swpc.dlq`, and none runs a feed processor against a real broker. The broker tests use the `raw.gp` topics only.
2. Process level behavior of `main`: shutdown on SIGTERM with exit code 0, and exiting when the health server cannot bind its port.
3. Feed staleness. `ingest` does not report when a feed last published, so a product that keeps answering 304, or keeps serving the same window, looks healthy on `/readyz`.

## Acceptance checks on a running stack

Two rules apply to every check here:

* **CelesTrak allows one download per update, every 2 hours.** Unless a check says otherwise, start the stack with `INGEST_FEEDS=swpc`. Starting `ingest` with CelesTrak enabled sends a request at once, and it does not remember the previous one across restarts.
* **Never delete the `kafka-data` volume** to get a clean count. The checks compare only what was fetched or dead lettered since the running `ingest` container started.

Common setup, pasted once per shell:

```sh
compose() { docker compose -f deploy/compose.yaml --profile core "$@"; }
kexec() { compose exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "$@"; }
started() { docker inspect -f '{{.State.StartedAt}}' "$(compose ps -q ingest)"; }
consume() {
  kexec /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic "$1" \
    --from-beginning --timeout-ms 20000 \
    --formatter-property print.key=true --formatter-property 'key.separator=|' 2>/dev/null
}
dead_letters_since() {
  consume "$1" | python3 -c '
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
        print(key, letter["stage"], letter["reason"][:200])
print("dead letters since start:", count)' "$2"
}
```

### C1. The core profile starts with only the SWPC feed

```sh
INGEST_FEEDS=swpc compose up -d --build
sleep 60
compose ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}'
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(compose ps -q ingest)" | grep INGEST_FEEDS
```

Holds when `kafka` is `running`, `topics` is `exited` with exit code `0`, `ingest` is `running`, and the environment line reads `INGEST_FEEDS=swpc`. `--build` matters: a container left over from an earlier build can run older code than the working tree.

### C2. Liveness and readiness report four running SWPC products

```sh
curl -sS -w '\nHTTP %{http_code}\n' http://127.0.0.1:8080/healthz
curl -sS -w '\nHTTP %{http_code}\n' http://127.0.0.1:8080/readyz
```

Holds when `/healthz` answers `HTTP 200` with `{"status":"alive"}`, and `/readyz` answers `HTTP 200` with exactly these fields: `kafka` is `ok`, `publish` is `ok`, and `swpc.kp`, `swpc.goes.xrays`, `swpc.goes.protons`, and `swpc.alerts` are each `running`. A `celestrak` field must not appear.

### C3. Events on `raw.swpc`, per key, valid, and not duplicated

Wait at least 2 minutes after C1 so the first poll of every product has finished, then:

```sh
consume raw.swpc > /tmp/raw-swpc.txt
python3 - "$(started)" /tmp/raw-swpc.txt <<'EOF'
import collections, datetime, json, sys
from jsonschema import Draft202012Validator
schema = Draft202012Validator(json.load(open("schemas/raw.swpc/v1.schema.json")))
identity = {
    "swpc.kp": ["time_tag"],
    "swpc.goes.xrays": ["time_tag", "satellite", "energy"],
    "swpc.goes.protons": ["time_tag", "satellite", "energy"],
    "swpc.alerts": ["product_id", "issue_datetime"],
}
since = datetime.datetime.fromisoformat(sys.argv[1])
total, recent = collections.Counter(), collections.Counter()
invalid = mislabeled = unknown = duplicates = 0
seen = set()
for line in open(sys.argv[2]):
    if not line.strip():
        continue
    key, _, value = line.rstrip("\n").partition("|")
    event = json.loads(value)
    total[key] += 1
    if key not in identity:
        unknown += 1
        continue
    if not schema.is_valid(event):
        invalid += 1
    if event.get("product") != key:
        mislabeled += 1
    if datetime.datetime.fromisoformat(event["fetched_at"]) >= since:
        recent[key] += 1
        ident = (key,) + tuple(json.dumps(event["record"].get(f)) for f in identity[key])
        duplicates += ident in seen
        seen.add(ident)
for key in sorted(total):
    print(f"{key}: {total[key]} total, {recent[key]} since start")
print(f"unknown keys {unknown}, invalid {invalid}, key differs from product {mislabeled}, duplicate identities since start {duplicates}")
EOF
```

Holds when all of these are true:

1. Every key printed is one of the four product IDs, and `unknown keys` is 0.
2. `swpc.kp`, `swpc.goes.xrays`, and `swpc.goes.protons` each have at least one event since start. (`swpc.alerts` can legitimately be 0 if SWPC's file is empty.)
3. `invalid` is 0 and `key differs from product` is 0.
4. `duplicate identities since start` is 0. The first response after a start is published in full by design, which is why only events fetched since this container started are compared. A nonzero count fails the check until explained; the one known legitimate cause is a record that vanished from SWPC's file for a poll and then came back.

### C4. Polls are spaced by the interval and logged without warnings

Wait at least 11 minutes after C1 so every product has been polled at least twice, then:

```sh
compose logs --no-log-prefix --no-color ingest | python3 -c '
import collections, datetime, json, sys
outcomes = {"processed response", "not modified"}
times = collections.defaultdict(list)
problems = 0
for line in sys.stdin:
    try:
        entry = json.loads(line)
    except ValueError:
        continue
    if entry.get("level") in ("WARN", "ERROR"):
        problems += 1
        print("problem:", line.strip())
    if entry.get("msg") in outcomes:
        times[entry["feed"]].append(datetime.datetime.fromisoformat(entry["time"]))
for feed in sorted(times):
    t = times[feed]
    gaps = [(b - a).total_seconds() for a, b in zip(t, t[1:])]
    print(f"{feed}: {len(t)} polls, shortest gap {min(gaps) if gaps else None} s")
print(f"warnings and errors: {problems}")'
```

Holds when each of the four products shows at least 2 polls, every shortest gap is at least 299 seconds (the 5 minute interval, less a second for timestamp rounding), and `warnings and errors` is 0. A warning is not automatically a defect (SWPC has real outages), but it fails this check until the printed line is explained.

### C5. No dead letters during a healthy run

```sh
dead_letters_since raw.swpc.dlq "$(started)"
```

Holds when the last line reads `dead letters since start: 0`. Otherwise each printed line names the product, the stage, and the start of the reason, and the check stays failed until that reason is understood.

### C6. Memory stays inside the Compose limits

```sh
deploy/measure-ram.sh 360 5
for s in kafka ingest; do docker inspect -f "$s OOMKilled={{.State.OOMKilled}} limit={{.HostConfig.Memory}}" "$(compose ps -q $s)"; done
```

Holds when the summary's maximum usage for `ingest` is below 128 MiB, the maximum for `kafka` is below 1024 MiB, and both containers report `OOMKilled=false`. The script writes its samples and summary under `docs/perf/data/`; compare them with the runs recorded in [the memory report](../perf/local-memory.md).

### C7. Clean shutdown

```sh
ING="$(compose ps -q ingest)"
compose stop ingest
docker inspect -f 'exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$ING"
docker logs "$ING" 2>&1 | tail -n 1
```

Holds when the output reads `exit=0 oom=false` and the last log line is a JSON object whose `msg` is `shutting down`. Restart afterwards with `INGEST_FEEDS=swpc compose up -d` if the CelesTrak window has not passed.

### C8. The CelesTrak feed end to end

Run this only when at least 2 hours have passed since the last CelesTrak download from this machine, and at most once per 2 hours.

```sh
INGEST_FEEDS=celestrak,swpc compose up -d --build
sleep 90
curl -sS http://127.0.0.1:8080/readyz; echo
compose logs --no-log-prefix --no-color ingest | grep -c '"msg":"fetching","feed":"celestrak"'
consume raw.gp > /tmp/raw-gp.txt
python3 - "$(started)" /tmp/raw-gp.txt <<'EOF'
import datetime, json, sys
from jsonschema import Draft202012Validator
schema = Draft202012Validator(json.load(open("schemas/raw.gp/v1.schema.json")))
since = datetime.datetime.fromisoformat(sys.argv[1])
recent = invalid = wrong_key = 0
for line in open(sys.argv[2]):
    if not line.strip():
        continue
    key, _, value = line.rstrip("\n").partition("|")
    event = json.loads(value)
    if datetime.datetime.fromisoformat(event["fetched_at"]) < since:
        continue
    recent += 1
    invalid += not schema.is_valid(event)
    wrong_key += key != str(event["gp"]["NORAD_CAT_ID"])
print(f"events since start {recent}, invalid {invalid}, key differs from NORAD_CAT_ID {wrong_key}")
EOF
dead_letters_since raw.gp.dlq "$(started)"
```

Holds when `/readyz` shows `"celestrak":"running"` next to the four SWPC products, the fetch count is exactly 1, the events since start are more than 0 with `invalid` 0 and `key differs from NORAD_CAT_ID` 0, and the last line reads `dead letters since start: 0`. Write down the time of this run: it starts the next 2 hour window.

## Results log

One row per check per run. A check is passed only when someone has run it and read the output; nothing here is filled in ahead of time.

| Date (UTC) | Commit | Check | Result | Notes |
|---|---|---|---|---|
| | | C1 | not yet run | |
| | | C2 | not yet run | |
| | | C3 | not yet run | |
| | | C4 | not yet run | |
| | | C5 | not yet run | |
| | | C6 | not yet run | |
| | | C7 | not yet run | |
| | | C8 | not yet run | |
