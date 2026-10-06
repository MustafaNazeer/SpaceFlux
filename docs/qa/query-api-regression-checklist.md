# Query API regression checklist

This is the list of checks I run before calling a change to the `query-api` service or the database it owns done. It covers the Java service, the `db-migrate` module and its migrations, the topics `query-api` reads (`alerts`, `raw.gp`) and writes (`alerts.dlq`, `raw.gp.dlq`), the REST endpoints in [docs/api/rest.md](../api/rest.md), the operator sign in and acknowledgement, and the `mysql`, `migrate`, and `query-api` services in the `core` Compose profile. The other services have their own lists: [the ingest regression checklist](ingest-regression-checklist.md) and [the risk engine regression checklist](risk-engine-regression-checklist.md).

It has two parts. The automated checks run from a shell and need nothing beyond a JDK 21, Docker, Go, and Python 3.11 or later. The acceptance checks need a running stack and a person reading the results, so each one is written as a numbered condition that either holds or does not, with the exact commands to paste. None of them is marked as passed here; results go in the log at the end.

Every command runs from the repository root unless it says otherwise.

## Automated checks

### A1. Each module from a clean tree, one at a time

```sh
./mvnw -B clean verify -pl db-migrate -am
./mvnw -B clean verify -pl kafka-contracts -am
./mvnw -B clean verify -pl risk-engine -am
./mvnw -B clean verify -pl query-api -am
```

Run them one after another, never side by side: the `db-migrate` and `query-api` suites each start their own MySQL container, `risk-engine` and `query-api` start Kafka containers, and two suites at once can exhaust a 7 GiB machine. Each passes when Maven prints `BUILD SUCCESS`, every `Tests run:` summary reads `Failures: 0, Errors: 0, Skipped: 0`, and no line starts with `[WARNING]` or `[ERROR]`. `kafka-contracts` is built again by the `risk-engine` and `query-api` commands through `-am`; `db-migrate` is not part of the `query-api` build, since `query-api` reads the migrations from the repository.

Always include `clean`, for the reason the risk engine checklist gives: old reports in `target/surefire-reports/` keep a renamed or deleted test counted.

### A2. Test counts per module

After each A1 command, with the module's name in `M`:

```sh
M=query-api
python3 - "$M" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
classes = tests = failures = errors = skipped = 0
for path in glob.glob(f"{sys.argv[1]}/target/surefire-reports/TEST-*.xml"):
    r = ET.parse(path).getroot()
    classes += 1
    tests += int(r.get("tests")); failures += int(r.get("failures"))
    errors += int(r.get("errors")); skipped += int(r.get("skipped"))
print(f"{sys.argv[1]}: classes {classes} tests {tests} failures {failures} errors {errors} skipped {skipped}")
EOF
```

Passes when no failure, error, or skipped test is printed and the counts match the last recorded run, or the difference is explained by tests added or removed in the change. The last recorded runs printed:

| Module | Test classes | Tests | Build time | Run |
|---|---|---|---|---|
| `db-migrate` | 2 | 31 | about 19 seconds | 2026-10-06, commit a091a86 |
| `kafka-contracts` | 2 | 40 | about 4 seconds | 2026-10-06, commit a091a86 |
| `risk-engine` | 34 | 364 | about 4 minutes 25 seconds | 2026-10-06, commit c267139 |
| `query-api` | 36 | 294 | about 3 minutes 55 seconds | 2026-10-06, commit a091a86 |

The a091a86 rows come from one clean run of `./mvnw -B -pl db-migrate,query-api -am clean verify` on a lightly loaded machine, which builds the three modules in one reactor; it printed `BUILD SUCCESS` and no `[WARNING]` or `[ERROR]` line. The build times are the reactor's per module times, so they are not directly comparable with the separate A1 commands. `query-api` grew by one class and four tests over c267139: `DatabaseDownIntegrationTest` and the new acknowledgement and login cases. The `risk-engine` row was not rerun, because nothing under `risk-engine/` or `kafka-contracts/` changed after c267139.

`BcryptCostBenchmark` is not a test class by Surefire's naming rule and runs only on request ([bcrypt cost](../perf/bcrypt-cost.md)), so it is never counted or skipped.

### A3. The ingest suite is still green

`query-api` shares the `raw.gp` schema and the Compose file with `ingest`, so a change here runs the ingest checks A1 and A2 too:

```sh
cd ingest
gofmt -l .
go vet ./...
go test -race -count=1 ./...
```

Passes when `gofmt -l` and `go vet` print nothing and every package prints `ok` or `[no test files]`. On 2026-10-06, at commit c267139, with Go 1.27.1, `gofmt -l` and `go vet` printed nothing and `go test -race -count=1 ./...` reported 86 passing top level tests, 49 passing subtests, 12 passing packages, one package with no test files, and no data race, in about 1 minute 30 seconds.

### A4. Query plans at a year of data

```sh
./mvnw -B -pl query-api -am -Pplans test
```

The normal build runs `QueryPlansIntegrationTest` at a small size and writes nothing. This profile seeds a year of space weather events and screening runs through the real consumer path and rewrites the plans under `docs/data/plans/`, which [indexes.md](../data/indexes.md#how-each-index-is-proven) reads. It takes 30 to 50 minutes on a machine with a spinning disk, because every seeded event is its own committed transaction, so run it on its own, only after a change to a query, an index, or a migration. Passes when Maven prints `BUILD SUCCESS`; then read `git diff docs/data/plans` and check that every changed plan still uses the index indexes.md names for that query.

### What the automated suite covers

| Behavior | Tests |
|---|---|
| Every migration applies as the migration user and a second run applies nothing; each account holds exactly its reviewed privileges, only the three service accounts are reachable over the network and each needs TLS, a plain connection is refused, the server runs with the committed settings, sessions run in UTC, the API user can only append acknowledgements, and the seeded watchlist is the risk engine's | `MigrationIntegrationTest` (`db-migrate`) |
| The migrate container refuses a user name outside `^[a-z][a-z0-9_]{0,31}$` before Flyway starts, a missing consumer user, an empty password file, a host carrying URL parameters, and an SSL mode that allows plaintext, and never prints the password | `SettingsTest` (`db-migrate`) |
| An `alerts` event is stored once with its exact text, a repeated `event_id` is dropped without a dead letter, and the offset is committed only after the row is written | `eachEventIsStoredOnceAsReceivedAndItsOffsetIsCommittedAfterwards` (`AlertsConsumerIntegrationTest`); `everyExampleIsStoredWithItsExactBytesAndItsSourcePosition`, `aRepeatedEventIdIsDroppedAndNotDeadLettered` (`AlertsProcessorTest`) |
| `alerts` dead letters on a real broker: a schema failure, a value that does not fit its column, and a value the database refuses, none of them stored | `aSchemaFailureAndAValueThatDoesNotFitGoToTheDeadLetterTopicAndAreNotStored`, `aValueTheDatabaseRefusesIsReportedAsNotStorableAndLeavesNoRow` (`AlertsConsumerIntegrationTest`) |
| `alerts` dead letters by kind: schema failure with its key, trailing bytes after the event, bytes that are not UTF-8, a number a double would round, a rules version that is not whole, an empty value, and series, run window, and input times more than an hour after the clock with `check` `rule` | every dead letter case in `AlertsProcessorTest`; the row cases in `AlertRowTest`, `SpaceWeatherRowTest`, and `ScreeningRowsTest` |
| A database outage is retried without end and never dead lettered, on both consumers | `aRefusedInsertIsRetriedUntilTheDatabaseAcceptsItAndIsNeverDeadLettered` (`AlertsConsumerIntegrationTest`); `aDatabaseOutageIsThrownSoTheRecordIsRetriedNotDeadLettered` (`AlertsProcessorTest`); `aDatabaseOutageIsThrownSoTheRecordIsRetried` (`CatalogProcessorTest`); `whenTheCatalogCannotBeWrittenNothingChangesAndTheRecordIsRetried` (`CatalogStoreIntegrationTest`) |
| A partly written event leaves nothing behind: a series that cannot be written stores none of its event | `whenTheSeriesCannotBeWrittenNothingOfTheEventIsStored` (`SpaceWeatherStoreIntegrationTest`) |
| A second event claiming a stored `run_id` is dead lettered and stores nothing | `aSecondEventClaimingAStoredRunIdIsDeadLetteredAndStoresNothing` (`ScreeningStoreIntegrationTest`) |
| No schema valid mutation of any example, or of any element set, makes a processor throw | `AlertsMutationIntegrationTest`, `CatalogMutationIntegrationTest` |
| `raw.gp` into the catalog on a real broker, with a bad element set dead lettered before the offset moves on | `anElementSetIsCatalogedAndABadOneDeadLetteredBeforeTheOffsetMovesOn` (`CatalogConsumerIntegrationTest`) |
| `raw.gp.dlq` letters from `query-api`: schema failure with its source URL, bytes that are not UTF-8, a value that does not fit, `check` `rule` for a fetch more than an hour ahead or an `EPOCH` more than 5 minutes after its fetch, and a huge source URL or key that still gives a letter the producer accepts | every dead letter case in `CatalogProcessorTest`; the rule cases in `CatalogRowTest` |
| Only a later `EPOCH` replaces an element set, the fetch range only widens, and arrival order does not change the result | `CatalogMergeTest`, `CatalogStoreIntegrationTest` |
| Retry back off from 1 s doubling to 60 s and never giving up, no exception type skipped, a 5 s wait on an error with no record | `KafkaConfigTest` |
| A database error is logged by its code, state, and message, and no control character in it can forge a log line | `FailuresTest` |
| Every compression codec is decoded at startup, and one that cannot load stops the start and names its setting | `CompressionCheckTest` |
| Each pool connects as its own user over TLS with a UTC session; an extra or missing privilege, a granted role, a missing password file, a password given as a flag or an environment variable, a bad user name, or a Hikari configuration file stops the start without printing a password | `MysqlPoolsIntegrationTest`, `GrantCheckTest`, `MysqlPropertiesTest`, `SecretFilesTest` |
| Every read endpoint's shape, with exactly the documented fields, the age limit rule, the current series and current run rules, staleness, the 32 run search limit, and keyset paging | `ReadApiIntegrationTest` |
| `400` for every history request outside the rules (missing or unknown scale, a satellite where none belongs, missing, reversed, equal, or over 7 day ranges, years outside 1000 to 9999, `limit` 0 or 201, unreadable cursors), for a catalog number that is not one, and for a missing or overlong `event_id` | `aHistoryRequestOutsideTheRulesIsABadRequest`, `aCatalogNumberThatIsUnknownOrNotACatalogNumberIsAProblem`, `anUnknownMissingOrOverlongEventIdIsAProblem` (`ReadApiIntegrationTest`) |
| `404` for an unknown path, an unknown alert, an unknown catalog object, and no complete screening run; `405` for a method the container refuses; `500` with a generic body for an exception in a filter; every one a problem body with exactly `title`, `status`, `detail`, `instance`, and `correlation_id`, and nothing internal | `anUnknownPathIsAProblemDetailWithTheCorrelationIdAndNothingInternal`, `aMethodTheContainerRefusesGetsTheSameProblemBody`, `anExceptionInAFilterGetsAGenericProblemBodyWithNothingInternal`, `theErrorPathAnswersWithTheSameProblemBody`, `withNoCompleteRunTheCurrentRunIsNotFound` (`ReadApiIntegrationTest`) |
| Every response carries the security headers and its own correlation id, ignoring the client's | `everyResponseCarriesTheSecurityHeaders` (`AuthIntegrationTest`); `everyResponseCarriesItsOwnCorrelationIdAndIgnoresTheClients` (`ReadApiIntegrationTest`) |
| Sign in: a `Secure`, `HttpOnly`, `SameSite=Strict` session cookie, a new session id on each login, the same `401` for a wrong password and an unknown user, `403` without the XSRF header, `400` for a query string before the password is checked, a session id in the URL ignored, no anonymous request creating a session, and the XSRF cookie set on every response to a request without it | `AuthIntegrationTest` |
| A session ends at logout, after 30 idle minutes, 8 hours after its login, and when a newer login replaces it; logout over HTTPS sends `Clear-Site-Data` | `AuthIntegrationTest`, `SessionLifetimeFilterTest`, `LogoutOverHttpsIntegrationTest` |
| Login back off: three failures free, then 2 s doubling to a 15 minute cap, per address and across the service, `429` with `Retry-After` even for the right password, a percent encoded login path throttled the same, a login refused for its XSRF header not counted, a third concurrent password check refused at once with a problem body, and thirty failures from thirty addresses holding off every address, all through the filter chain | `LoginThrottleTest`, `LoginThrottleFilterTest`, `LoginThrottleIntegrationTest` (`whileTwoPasswordChecksRunALoginIsRefusedWithAProblemBodyAndNoCheck`, `thirtyFailuresFromThirtyAddressesHoldOffEveryAddress`) |
| With no operator configured: no default user, no password logged, every login `401`, and only acknowledgement `403` | `NoOperatorIntegrationTest`, `OperatorTest` |
| Acknowledgement: `201` with the row written, notes up to 500 code points, `400` for every body outside the rules (a `null` note included), `415` for a body that is not `application/json`, `400` and `404` for the `event_id`, `409` for a kind or state that cannot be acknowledged and for an action equal to the current state, `401` without a session and before any query, `401` for a replaced session, `403` without the XSRF header, `403` for `PUT`, `PATCH`, `DELETE`, and any other `POST` | `AcknowledgementIntegrationTest` (`aBodyThatIsNotJsonByItsContentTypeIsRefused` for the `415`) |
| While MySQL does not answer, a read and an acknowledgement get `500` with the generic body after the pool's timeout, no row is written, and reads work again once it answers | `DatabaseDownIntegrationTest` |
| The operator sees the principal and note of an acknowledgement and an anonymous viewer does not, on one alert, on the current run's approaches, and in the history, which is newest first and paged | `theOperatorSeesThePrincipalAndNoteOfAnAlertsAcknowledgementAndAViewerDoesNot`, `historyListsEveryRowNewestFirstInTheViewersForm`, `historyIsPagedByLimitAndCursor`, `historyOfAnUnknownAlertIsNotFoundAndOfAnUnacknowledgedOneIsEmpty` (`AcknowledgementIntegrationTest`); `theOperatorAlsoSeesThePrincipalAndNoteOfEachApproachsAcknowledgement` (`ReadApiIntegrationTest`) |
| Each query reads each table through the index indexes.md names | `eachQueryReadsEachTableAsIndexesMdSays` (`QueryPlansIntegrationTest`) |
| The embedded Tomcat is at least the release that fixed the advisories in 11.0.24 | `TomcatVersionTest` |

### Not covered by automated tests yet

These are checked by hand below, or not at all, until a test exists:

1. **Two acknowledgements of one alert at the same moment.** The rule against an action equal to the current state is held under a lock with no concurrency test.
2. **Health endpoints.** `query-api` has no liveness or readiness endpoint yet, so nothing reports a stuck consumer or an unreachable database except the logs and the consumer lag (Q7).
3. **A browser and the `Secure` session cookie on `http://127.0.0.1:8081`.** curl sends it there; whether a browser does is checked only by Q10.
4. **Process level behavior.** The exit code on SIGTERM and the startup codec check on a real `noexec` mount are covered by Q9 and by the risk engine's R6 pattern, not by a test.

## Acceptance checks on a running stack

The two rules from the other checklists apply here too:

* **CelesTrak allows one download per update, every 2 hours.** Start the stack with `INGEST_FEEDS=swpc` unless a check says otherwise. The catalog checks read the element sets already on `raw.gp`.
* **Never delete the `kafka-data` or `mysql-data` volume** to get a clean count. The checks compare what is on the topics with what is in the tables, or only what happened since the running `query-api` container started.

Q5 and Q10 need the operator account in `deploy/.env` and its password, as the [setup guide](../setup-guide.md#the-operator-account) describes. Q5 writes two acknowledgement rows that stay in the database: acknowledgement history is append only, and the check leaves the alert in the state it found it.

Common setup, pasted once per shell:

```sh
compose() { docker compose -f deploy/compose.yaml --profile core "$@"; }
kexec() { compose exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "$@"; }
started() { docker inspect -f '{{.State.StartedAt}}' "$(compose ps -q query-api)"; }
consume() {
  kexec /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic "$1" \
    --from-beginning --timeout-ms 20000 \
    --formatter-property print.key=true --formatter-property 'key.separator=|' 2>/dev/null
}
sql() {
  compose exec -T mysql bash -c \
    'mysql --defaults-extra-file=<(printf "[client]\npassword=%s\n" "$(< /run/secrets/mysql_root_password)") -uroot -N -B spaceflux'
}
api=http://127.0.0.1:8081
```

`sql` reads its statements from standard input, for example `echo 'SELECT COUNT(*) FROM alert_event;' | sql`, and keeps the root password off every command line.

### Q1. The core profile starts with the database and the query API

```sh
INGEST_FEEDS=swpc compose up -d --build
sleep 120
compose ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}'
compose logs --no-color query-api | grep -c 'Started QueryApiApplication'
compose logs --no-color query-api | grep -E ' (WARN|ERROR) ' | head -n 20
```

Holds when all of these are true:

1. `kafka`, `ingest`, `risk-engine`, `mysql`, and `query-api` are `running`.
2. `topics` and `migrate` are `exited` with exit code `0`.
3. The count of `Started QueryApiApplication` lines is `1`.
4. The last command prints nothing. With no operator in `deploy/.env` the service warns that nobody can sign in, which is expected then and fails only Q5 and Q10. Any other warning fails this check until the printed line is explained.

### Q2. Containers, ports, and networks are as configured

```sh
for s in mysql migrate query-api; do
  docker inspect -f "$s ro={{.HostConfig.ReadonlyRootfs}} user={{.Config.User}} caps={{.HostConfig.CapDrop}} sec={{.HostConfig.SecurityOpt}} mem={{.HostConfig.Memory}} log={{.HostConfig.LogConfig.Type}}/{{index .HostConfig.LogConfig.Config \"max-size\"}}/{{index .HostConfig.LogConfig.Config \"max-file\"}}" "$(compose ps -aq $s)"
done
docker port "$(compose ps -q query-api)"
docker port "$(compose ps -q mysql)" | wc -l
docker network inspect -f '{{.Name}} internal={{.Internal}}' spaceflux_database
P="$(docker inspect -f '{{.State.Pid}}' "$(compose ps -q query-api)")"
grep -E ' /(tmp|native) ' "/proc/$P/mountinfo"
```

Holds when all of these are true:

1. `mysql` prints `ro=false user=999:999 caps=[ALL] sec=[no-new-privileges:true] mem=536870912 log=json-file/10m/3`.
2. `migrate` prints `ro=true user=nonroot:nonroot caps=[ALL] sec=[no-new-privileges:true] mem=268435456 log=json-file/10m/3`.
3. `query-api` prints `ro=true user=nonroot:nonroot caps=[ALL] sec=[no-new-privileges:true] mem=536870912 log=json-file/10m/3`.
4. `docker port` for `query-api` prints only `8080/tcp -> 127.0.0.1:8081`, and the line count for `mysql` is `0`.
5. The network line reads `spaceflux_database internal=true`.
6. The `/tmp` line's mount options include `noexec` and its tmpfs options include `size=16384k`; the `/native` line's mount options include `nosuid` and `nodev` but not `noexec`, and its tmpfs options include `size=8192k`, `mode=700`, `uid=65532`, and `gid=65532`.

### Q3. Anonymous reads answer with the documented shape and headers

```sh
python3 - "$api" <<'EOF'
import json, sys, urllib.request, urllib.error
api = sys.argv[1]
want = {"x-content-type-options": "nosniff", "x-frame-options": "DENY",
        "cache-control": "no-cache, no-store, max-age=0, must-revalidate"}
for path in ("/api/space-weather/current", "/api/watchlist", "/api/screening/current"):
    try:
        r = urllib.request.urlopen(api + path)
    except urllib.error.HTTPError as e:
        r = e
    h = {k.lower(): v for k, v in r.headers.items()}
    body = json.loads(r.read())
    bad = [k for k, v in want.items() if h.get(k) != v]
    session = any("SPACEFLUX_SESSION" in v for k, v in r.headers.items() if k.lower() == "set-cookie")
    print(path, r.status, "headers ok" if not bad else f"headers wrong {bad}",
          "correlation id" if h.get("x-correlation-id") else "NO correlation id",
          "SESSION COOKIE SET" if session else "no session cookie")
    if path.endswith("current") and "scales" in body:
        print("  scales", sorted(s["scale"] for s in body["scales"]),
              [(s["scale"], s["state"], s.get("no_data_reason")) for s in body["scales"]])
    if path == "/api/watchlist":
        print("  watchlist", [(o["catalog_number"], "catalog" in o) for o in body.get("objects", [])])
    if path == "/api/screening/current":
        print("  stale", body.get("stale"), "approaches", len(body.get("approaches", [])) if r.status == 200 else body.get("title"))
EOF
```

Holds when all of these are true:

1. `/api/space-weather/current` and `/api/watchlist` answer `200`, and `/api/screening/current` answers `200` or, when no complete run is stored, `404`.
2. Every line reads `headers ok`, `correlation id`, and `no session cookie`.
3. The scales line lists exactly `['G', 'R', 'S']`. Which states appear depends on the sky and the feeds; a `no_data` state must come with a reason.
4. The watchlist line lists the ISS, catalog number `25544`, the object the migrations seed from the risk engine's watchlist file. Whether it shows `True` for a catalog row depends on whether a CelesTrak batch is on `raw.gp`.

### Q4. Errors are problem bodies with nothing internal

```sh
python3 - "$api" <<'EOF'
import json, sys, urllib.request, urllib.error
api = sys.argv[1]
cases = [
    ("GET", "/api/no-such-path", 404),
    ("GET", "/api/catalog/not-a-number", 400),
    ("GET", "/api/catalog/999999999", 404),
    ("GET", "/api/alerts/by-id", 400),
    ("GET", "/api/alerts/by-id?event_id=no-such-event", 404),
    ("GET", "/api/space-weather/history?scale=G&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z&limit=201", 400),
    ("GET", "/api/space-weather/history?scale=G&from=2026-01-01T00:00:00Z&to=2026-01-09T00:00:00Z", 400),
    ("GET", "/api/auth/session", 401),
    ("POST", "/api/watchlist", 403),
    ("POST", "/api/alerts/acknowledgements?event_id=x", 403),
    ("TRACE", "/api/watchlist", 405),
]
for method, path, expected in cases:
    req = urllib.request.Request(api + path, method=method, data=b"{}" if method == "POST" else None,
                                 headers={"Content-Type": "application/json"} if method == "POST" else {})
    try:
        r = urllib.request.urlopen(req)
    except urllib.error.HTTPError as e:
        r = e
    text = r.read().decode()
    ctype = r.headers.get("Content-Type", "")
    try:
        body = json.loads(text)
        keys = sorted(body)
    except ValueError:
        body, keys = {}, ["NOT JSON"]
    internal = any(s in text for s in ("Exception", "at io.", "trace", "SQL", "mysql"))
    ok = (r.status == expected and ctype.startswith("application/problem+json")
          and keys == ["correlation_id", "detail", "instance", "status", "title"]
          and body.get("status") == expected and body.get("correlation_id") == r.headers.get("X-Correlation-Id")
          and not internal)
    print("ok  " if ok else "FAIL", method, path, r.status, ctype, keys, "INTERNAL TEXT" if internal else "")
EOF
```

Holds when every line starts with `ok`. The unknown path and the refused method each log a `WARN` line in `query-api`, so run Q1 before this check. Each case is a status rest.md documents: an unknown path, a malformed or unknown catalog number, a missing or unknown `event_id`, a `limit` over 200, a range over 7 days, no session, an unsafe method without the XSRF header, and a method the container refuses.

### Q5. Sign in, acknowledge, read the history, and sign out

The password prompt keeps the password out of the shell history and off the command line. Set `user` to the `ACK_OPERATOR_USERNAME` in `deploy/.env`.

```sh
user=operator
jar=$(mktemp); out=$(mktemp)
enc() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }
xsrf() { awk '$6 == "XSRF-TOKEN" {print $7}' "$jar"; }
code() { curl -s -b "$jar" -c "$jar" -o "$out" -w '%{http_code}' "$@"; }
echo "session before: $(code $api/api/auth/session)"
read -rs -p 'Password: ' pw; echo
echo "login: $(printf %s "$pw" | code -H "X-XSRF-TOKEN: $(xsrf)" --data-urlencode "username=$user" --data-urlencode password@- $api/api/auth/login)"
unset pw
echo "session after: $(code $api/api/auth/session) $(cat "$out")"
id=$(curl -s $api/api/screening/current | python3 -c 'import json, sys; a = json.load(sys.stdin).get("approaches") or []; print(a[0]["event_id"] if a else "")')
[ -n "$id" ] || id=$(echo "SELECT e.event_id FROM alert_event e JOIN space_weather_event s ON s.alert_seq = e.alert_seq WHERE s.state = 'level' AND s.trigger_kind <> 'refresh' ORDER BY e.alert_seq DESC LIMIT 1;" | sql)
echo "alert: $id"
ack="$api/api/alerts/acknowledgements?event_id=$(enc "$id")"
cur=$(curl -s "$api/api/alerts/by-id?event_id=$(enc "$id")" | python3 -c 'import json, sys; print((json.load(sys.stdin).get("acknowledgement") or {}).get("action", "unacknowledge"))')
if [ "$cur" = acknowledge ]; then a1=unacknowledge; a2=acknowledge; else a1=acknowledge; a2=unacknowledge; fi
post() { code -H "X-XSRF-TOKEN: $(xsrf)" -H "Content-Type: ${2:-application/json}" --data "$1" "$ack"; }
echo "first $a1: $(post "{\"action\": \"$a1\", \"note\": \"regression check\"}") $(cat "$out")"
echo "same again: $(post "{\"action\": \"$a1\"}")"
echo "text/plain: $(post "{\"action\": \"$a2\"}" text/plain) $(cat "$out")"
echo "null note: $(post "{\"action\": \"$a2\", \"note\": null}")"
echo "extra field: $(post "{\"action\": \"$a2\", \"principal\": \"someone\"}")"
echo "second $a2: $(post "{\"action\": \"$a2\"}")"
echo "history as operator: $(code "$ack&limit=2") $(cat "$out")"
echo "history anonymous: $(curl -s "$ack&limit=2")"
echo "logout: $(code -X POST -H "X-XSRF-TOKEN: $(xsrf)" $api/api/auth/logout)"
echo "session after logout: $(code $api/api/auth/session)"
echo "write after logout: $(post "{\"action\": \"$a1\"}")"
rm -f "$jar" "$out"
```

Holds when all of these are true:

1. `session before` is `401`, `login` is `204`, and `session after` is `200` with `{"username":"..."}` naming the operator.
2. `alert` prints an event id. If it prints nothing, no acknowledgeable alert is stored yet; wait for a screening run or a space weather level and run Q5 again.
3. `first` is `201` with a body holding `event_id`, `action`, `principal`, `acted_at`, and the note `regression check`.
4. `same again` is `409`.
5. `text/plain` is `415` with a problem body. This is the only check of `415` so far (see "Not covered" item 1).
6. `null note` and `extra field` are `400`. `null note` is the only check of that rule so far (item 2).
7. `second` is `201`.
8. `history as operator` is `200` and its first two items are `$a2` then `$a1`, each with `principal`, and the second with the note `regression check`; `history anonymous` has the same two actions and times with no `principal` and no `note`.
9. `logout` is `204`, `session after logout` is `401`, and `write after logout` is `401`.

### Q6. Login back off answers 429 and clears on success

```sh
user=operator
jar=$(mktemp)
curl -s -c "$jar" -o /dev/null $api/api/auth/session
xsrf() { awk '$6 == "XSRF-TOKEN" {print $7}' "$jar"; }
for i in 1 2 3 4 5; do
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(xsrf)" --data-urlencode "username=$user" \
    --data-urlencode "password=wrong-on-purpose-$i" -o /dev/null -D - $api/api/auth/login \
    | awk -v i="$i" 'NR == 1 {s = $2} tolower($1) == "retry-after:" {r = $2} END {print "attempt " i ": " s " retry-after " (r == "" ? "none" : r)}'
done
sleep 3
read -rs -p 'Password: ' pw; echo
printf %s "$pw" | curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(xsrf)" --data-urlencode "username=$user" \
  --data-urlencode password@- -o /dev/null -w 'right password after the wait: %{http_code}\n' $api/api/auth/login
unset pw
curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(xsrf)" -X POST -o /dev/null $api/api/auth/logout
rm -f "$jar"
```

Holds when attempts 1 to 4 print `401 retry-after none`, attempt 5 prints `429 retry-after 2`, and the right password after the wait prints `204`. The wait is per client address, so this delays only the machine running the check, and the successful login resets it.

### Q7. Every event on the topics reaches the tables

```sh
consume alerts > /tmp/alerts.txt
consume raw.gp > /tmp/raw-gp.txt
echo 'SELECT event_id FROM alert_event;' | sql > /tmp/alert-ids.txt
echo 'SELECT norad_cat_id FROM catalog_object;' | sql > /tmp/catalog-ids.txt
python3 - <<'EOF'
import json
topic, unreadable = set(), 0
for line in open("/tmp/alerts.txt"):
    if not line.strip():
        continue
    try:
        topic.add(json.loads(line.rstrip("\n").partition("|")[2])["event_id"])
    except (ValueError, KeyError, TypeError):
        unreadable += 1
table = {l.rstrip("\n") for l in open("/tmp/alert-ids.txt") if l.strip()}
missing = sorted(topic - table)
print(f"alerts: {len(topic)} event ids on the topic, {unreadable} unreadable records, {len(table)} rows,",
      f"{len(missing)} on the topic and not stored, {len(table - topic)} stored and not on the topic")
for m in missing[:5]:
    print("  not stored:", m)
keys = {l.partition("|")[0] for l in open("/tmp/raw-gp.txt") if l.strip()}
cat = {l.strip() for l in open("/tmp/catalog-ids.txt") if l.strip()}
print(f"catalog: {len(keys)} objects on raw.gp, {len(cat)} rows, {len(keys - cat)} on the topic and not stored")
EOF
for g in query-api-alerts query-api-catalog; do
  kexec /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --describe --group "$g" 2>/dev/null
done
```

Holds when all of these are true:

1. The alerts line shows `0 on the topic and not stored`, apart from records Q8 shows were dead lettered by `query-api`.
2. The catalog line shows `0 on the topic and not stored`, with the same exception.
3. Both consumer groups list every partition of their topic with a `LAG` of `0`. New SWPC events arrive every 5 minutes, so a small lag that is gone when the command is run again a few seconds later still holds.

"Stored and not on the topic" is for reading, not for passing: the topic is read first, so events that arrive between the two reads are counted there.

Then restart the service and run Q7 again:

```sh
compose restart query-api
sleep 90
compose logs --no-color --since 2m query-api | grep -c 'Started QueryApiApplication'
```

Holds when the count is `1`, Q7 holds again, and the row counts are the same as before or higher only by events that arrived in between. A restart reads from the committed offsets, so no stored event is dead lettered as a repeat.

### Q8. No dead letters from `query-api` during a healthy run

```sh
for t in alerts.dlq raw.gp.dlq; do
  consume "$t" | python3 -c '
import datetime, json, sys
since = datetime.datetime.fromisoformat(sys.argv[1])
count = 0
for line in sys.stdin:
    if not line.strip():
        continue
    key, _, value = line.rstrip("\n").partition("|")
    letter = json.loads(value)
    if letter["service"] == "query-api" and datetime.datetime.fromisoformat(letter["failed_at"]) >= since:
        count += 1
        print(key, letter["stage"], letter.get("check"), letter["reason"][:200])
print(sys.argv[2], "query-api dead letters since start:", count)' "$(started)" "$t"
done
```

Holds when both topics print `query-api dead letters since start: 0`. Otherwise each printed line names the key, the stage, the check, and the start of the reason, and the check stays failed until that reason is understood. Dead letters from other services on the same topics are the other checklists' business.

### Q9. Memory stays inside the Compose limits, and the service stops cleanly

```sh
mkdir -p /tmp/ram && deploy/measure-ram.sh 360 5 /tmp/ram
for s in mysql query-api; do
  docker inspect -f "$s OOMKilled={{.State.OOMKilled}} limit={{.HostConfig.Memory}}" "$(compose ps -q $s)"
done
QA="$(compose ps -q query-api)"
compose stop query-api
docker inspect -f 'exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$QA"
docker logs "$QA" 2>&1 | tail -n 40 | grep -c 'Consumer stopped'
```

Holds when all of these are true:

1. The summary's maximum usage for `mysql` and for `query-api` is below 512 MiB each, with no swap, and both report `OOMKilled=false limit=536870912`. Compare with the runs in [the memory report](../perf/local-memory.md).
2. The stop prints `exit=143 oom=false`. 143 is the JVM's exit status after a SIGTERM it handled; 137 means Docker had to kill it.
3. The count of `Consumer stopped` lines is `2`, one for the `alerts` consumer and one for the `raw.gp` catalog consumer.

Restart afterwards with `INGEST_FEEDS=swpc compose up -d`.

### Q10. A browser keeps the session on the loopback address

This is the one check a shell cannot make: whether a browser stores and sends the `Secure` session cookie to `http://127.0.0.1:8081`. Open `http://127.0.0.1:8081/api/auth/session` in the browser (it shows a `401` problem body, which sets the `XSRF-TOKEN` cookie), open the developer console on that page, and paste:

```js
(async () => {
  const xsrf = () => decodeURIComponent((document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || "");
  const body = new URLSearchParams({ username: prompt("Username"), password: prompt("Password") });
  const login = await fetch("/api/auth/login", { method: "POST", headers: { "X-XSRF-TOKEN": xsrf() }, body });
  const session = await fetch("/api/auth/session");
  const logout = await fetch("/api/auth/logout", { method: "POST", headers: { "X-XSRF-TOKEN": xsrf() } });
  const after = await fetch("/api/auth/session");
  console.log("xsrf cookie readable:", xsrf() !== "", "login:", login.status, "session:", session.status,
              "logout:", logout.status, "session after logout:", after.status);
})();
```

Holds when the console prints `xsrf cookie readable: true login: 204 session: 200 logout: 204 session after logout: 401`. Record the browser and its version in the log. A `session: 401` after a `204` login means the browser did not send the session cookie over plain HTTP on the loopback address, which the dashboard has to know before it is built; it is a finding, not a reason to weaken the cookie.

## Results log

One row per check per run. A check is passed only when someone has run it and read the output; nothing here is filled in ahead of time.

| Date (UTC) | Commit | Check | Result | Notes |
|---|---|---|---|---|
