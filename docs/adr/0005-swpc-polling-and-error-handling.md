# ADR 0005: SWPC products, polling interval, and error handling

* **Status:** accepted; the `swpc.kp` identity in decision 4 is superseded by [ADR 0006](0006-kp-record-identity.md)
* **Date:** 2026-09-27

## Context

The risk logic maps space weather to the NOAA scales: G from planetary Kp, R from GOES X-ray flux, and S from GOES integral proton flux. NOAA SWPC publishes these as JSON files on `services.swpc.noaa.gov`, summarized with links in [docs/source/swpc.md](../source/swpc.md).

SWPC publishes no rate limit and no rule to stop on errors. Its pages link the NWS "Public Notice of Appropriate Use", which says NWS "may find it necessary to block IP addresses or query types", that when a service is unavailable "users should limit the number of retries to 1 minute intervals", and that products are decommissioned over time. SWPC also retired its `products/solar-wind/` files in 2026, so a product URL disappearing is a real case, not a hypothetical one.

Each SWPC file is a sliding window over recent data (for example the last 6 hours of GOES X-ray flux), so consecutive downloads mostly repeat the same records. Recorded responses carry `ETag` and `Last-Modified` headers, and a request with `If-None-Match` returned HTTP 304 with an empty body.

This differs from CelesTrak ([ADR 0004](0004-celestrak-polling-and-error-handling.md)), whose policy asks clients to stop on any non 200 response.

## Decision

1. **Products.** Four files, each polled by its own poller and published to `raw.swpc` under a fixed product ID used as the Kafka key:

   | Product ID | File | Supports |
   |---|---|---|
   | `swpc.kp` | `products/noaa-planetary-k-index.json` | G scale |
   | `swpc.goes.xrays` | `json/goes/primary/xrays-6-hour.json` | R scale |
   | `swpc.goes.protons` | `json/goes/primary/integral-protons-6-hour.json` | S scale |
   | `swpc.alerts` | `products/alerts.json` | SWPC alerts, watches, and warnings |

   A fixed key keeps every record of one product on one partition, in the order it was published.
2. **Interval.** Every product is polled every 5 minutes, with a floor of 1 minute enforced at startup. Each request sends `If-None-Match` with the last `ETag` when one is held (it is cleared after an error status and not kept if longer than 1 KiB); a 304 means nothing changed and the poller waits for the next interval.
3. **Errors are handled by class:**
   * **HTTP 403 or 404:** that product's poller stops and reports the product as halted on the readiness endpoint. A 403 may mean the client is blocked and a 404 that the file was retired; neither is fixed by retrying. The other products keep running.
   * **Any other non 200 status (for example 5xx or 429) and failures with no HTTP response:** retried with exponential backoff, each delay drawn at random between 1 minute and a ceiling that starts at 2 minutes, doubles per attempt, and is capped at the poll interval.
   * **A 200 body that fails in transit, or that fails decoding or validation:** dead lettered to `raw.swpc.dlq` as for CelesTrak, then the normal interval. An empty list is accepted for `swpc.alerts`, where a quiet period can leave none, without resetting deduplication, and dead lettered for the other three products; a body that is JSON `null` is dead lettered for all four.
   * **Kafka publish failures:** bounded and retried as in ADR 0004, on the same body, but with this feed's backoff, so never sooner than 1 minute.
4. **Deduplication.** A record is published only when it was not in the previous successful response for the same product, compared on its identity: `time_tag` for Kp; `time_tag`, `satellite`, and `energy` for GOES flux; `product_id` and `issue_datetime` for alerts. Memory stays bounded by the size of one response per product.

## Alternatives considered

* **Stop on any non 200, as for CelesTrak.** Rejected because SWPC asks only for spaced retries, and halting on a transient 503 would leave the space weather feed down until a restart.
* **Poll every minute.** The GOES files update every minute, but the X-ray file alone is about 160 KB, so polling it every minute costs about 235 MB a day; 5 minutes keeps it near 47 MB, before conditional requests reduce it further.
* **Key by product and series** (for example the X-ray energy band). Spreads load across partitions but splits the ordering that consumers of one product rely on; volume is small enough that one partition per product is sufficient.

## Consequences

* A retired or blocked product needs a human to update the configuration or contact SWPC, and is visible on the readiness endpoint until then.
* Deduplication against the previous response means that after a restart the first response is published in full; consumers deduplicate on the same identities, so this repeats data without corrupting it.
* If SWPC revises a published value under the same identity (not documented either way), the revision is not republished. This is revisited if revisions are observed.
