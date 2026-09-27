# ADR 0004: CelesTrak polling interval and error handling

* **Status:** accepted
* **Date:** 2026-09-27

## Context

The design calls for every poller to back off exponentially with jitter and to respect each provider's published polling guidance. For CelesTrak those two rules conflict. CelesTrak's usage policy, summarized with quotes and links in [docs/source/celestrak.md](../source/celestrak.md), says:

* GP data updates once every 2 hours, and clients should download data only once per update.
* Machine to machine clients "should immediately stop querying when it receives any non-HTTP 200 responses and report the results to a human for investigation."
* HTTP 301, 403, and 404 responses count toward a limit of 50 in 2 hours, after which the client's IP address is firewalled and needs manual review to be removed.
* A repeat download before the data refreshes can itself return HTTP 403 on the groups where that rule is enforced.

Retrying an HTTP error with backoff would push a client toward the firewall limit, so the general backoff rule cannot apply to CelesTrak's HTTP responses.

## Decision

1. **Poll interval.** The CelesTrak interval is configurable with a floor of 2 hours; the service refuses to start with a lower value. The default is 2 hours 10 minutes, so a poll is less likely to land just before CelesTrak's refresh and receive the "not updated" 403.
2. **Errors are handled by class:**
   * **Any HTTP response other than 200** (including 301, 403, 404, and 5xx): the CelesTrak poller stops. It logs the status and a bounded prefix of the response body, reports the feed as halted through its readiness endpoint (and through metrics once they are added), and makes no further CelesTrak requests until the process is restarted. The process itself keeps running, so liveness still passes and an orchestrator does not restart it into a request loop.
   * **No HTTP response at all** (DNS failure, connection refused, TLS failure, or a timeout before the status line): these never reach CelesTrak's counters, so the poller retries with exponential backoff and full jitter, capped at the poll interval.
   * **A 200 status whose body fails in transit** (larger than the 8 MiB limit, or cut off by a read error or timeout): CelesTrak may already count this as the one download for the update, so the poller does not fetch again early. The part of the body that arrived goes to `raw.gp.dlq` with stage `fetch`, and the poller waits the full interval.
   * **Kafka publish failures:** each publish is roughly bounded by a 60 second delivery timeout (the client cannot fail a record whose request is already in flight to the broker, so an unanswered request can take longer), then retried with backoff and jitter on the same body, without a new CelesTrak request. A record is marked as published for deduplication only after the broker acknowledges it. While publishing keeps failing, readiness reports the failure streak.
   * **A 200 response whose body fails decoding or schema validation:** the payload goes to `raw.gp.dlq` with the reason attached, and the poller continues on its normal schedule, because repeating the request would not change the data.
3. **Deduplication.** Only element sets with a new (`NORAD_CAT_ID`, `EPOCH`) pair are published, since consecutive downloads often carry the same element sets.
4. **Requests** always use `https://celestrak.org` and `FORMAT=JSON`, and identify the client with a descriptive `User-Agent`.

## Alternatives considered

* **Backoff on every error.** Matches the general rule, but contradicts CelesTrak's stated policy and risks the IP being firewalled.
* **Stop on every error, including network failures.** Most conservative, but a brief local network outage would halt the feed until a manual restart, although such failures never reach CelesTrak.
* **Parse the 403 message and treat "not updated" as a skip.** Rejected because CelesTrak documents every 403 the same way and message text is not a stable contract.

## Consequences

* A CelesTrak HTTP error needs a human to look at it and restart the service. The readiness endpoint and the logs make that visible now; the dashboard's stale data banner and a halted feed metric are planned.
* A restart shortly after a successful poll issues a new request earlier than the interval. Persisting the last successful fetch time across restarts is deferred; it is revisited if restarts become frequent.
* Other feeds keep the general backoff rule unless their own documentation says otherwise, recorded per feed under `docs/source/`.
