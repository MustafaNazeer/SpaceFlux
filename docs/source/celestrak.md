# CelesTrak GP data: formats, queries, and usage policy

This note records what CelesTrak's own documentation says about its GP (General Perturbations) data, so the `ingest` poller is built against the provider's published guidance rather than assumptions. Every point below cites the page it came from. All pages were accessed on 2026-09-27. Where CelesTrak's documentation is silent on a point, this note says so instead of filling the gap.

## Sources

| # | Page | URL | Page's own "last updated" stamp |
|---|---|---|---|
| S1 | A New Way to Obtain GP Data (aka TLEs), by T.S. Kelso | https://celestrak.org/NORAD/documentation/gp-data-formats.php | 2026 Jun 23 22:20:47 UTC |
| S2 | CelesTrak Usage Policy, by T.S. Kelso | https://celestrak.org/usage-policy.php | 2026 May 22 21:38:28 UTC |
| S3 | NORAD GP Element Sets Current Data | https://celestrak.org/NORAD/elements/ | 2026 Sep 27 08:20:53 UTC |
| S4 | CCSDS 502.0-B-3, Orbit Data Messages, Blue Book, April 2023 (the standard S1 points to for field definitions) | https://public.ccsds.org/Pubs/502x0b3e1.pdf | April 2023 |

S4 is not a CelesTrak page. It is included because S1 defines CelesTrak's JSON and CSV fields by reference to it. S1 describes CCSDS 502.0-B-3 as "developed ... in November 2009"; the copy of 502.0-B-3 retrieved from the CCSDS site is dated April 2023. The keyword names used below match in the retrieved edition.

## GP query URL

From S1, every GP query has the form:

```
https://celestrak.org/NORAD/elements/gp.php?{QUERY}=VALUE[&FORMAT=VALUE]
```

`{QUERY}` is one of:

| Query | Meaning (S1) |
|---|---|
| `CATNR` | Catalog number, 1 to 9 digits. Returns data for a single catalog number. |
| `INTDES` | International designator (yyyy-nnn). Returns all objects from one launch. |
| `GROUP` | One of the satellite groups listed on the Current Data page (S3). |
| `NAME` | Satellite name; searches by part of the name. |
| `SPECIAL` | Special sets: `GPZ` (GEO Protected Zone), `GPZ-PLUS`, `DECAYING` (potential decays). |

S1 states that "{QUERY} must be uppercase."

`FORMAT` values (S1):

| Format | Content (S1) |
|---|---|
| `TLE` or `3LE` | Three line element sets with a 24 character name on line 0 |
| `2LE` | Two line element sets, no name line |
| `XML` | CCSDS OMM XML with all mandatory elements |
| `KVN` | CCSDS OMM KVN with all mandatory elements |
| `JSON` | "OMM keywords for all GP elements in JSON format" |
| `JSON-PRETTY` | The same, pretty printed |
| `CSV` | OMM keywords for all GP elements in CSV format |

S1: "The FORMAT specification is optional, but defaults to CSV (as of 2026 May 09)." A client that wants JSON must therefore send `FORMAT=JSON` explicitly.

The stations group is linked from S3 as `gp.php?GROUP=stations&FORMAT=csv`, and S1 gives the example `https://celestrak.org/NORAD/elements/gp.php?GROUP=STATIONS&FORMAT=XML`. S1 and S3 use both casings for the group value; the documentation only states the uppercase rule for the query keyword itself.

### Catalog numbers above 99999

S1 and S3 carry a notice that CelesTrak "ran out of 5-digit catalog numbers on 2026-07-11" and that newly cataloged objects "will not be available for them using the TLE format." S1: "TLE formats will not support objects with catalog numbers above 99999." The poller uses JSON, and catalog numbers must be handled as integers of up to 9 digits (S1 for `CATNR`; S4 Table 4-3 for `NORAD_CAT_ID`).

## JSON record fields

CelesTrak does not publish its own field by field list for the JSON format. S1 defines the fields by reference:

> data will be provided in both JSON and CSV formats, using the same keywords and definitions as provided in the OMM standard (CCSDS 502.0-B-3, Table 4-1), although null/blank or redundant (e.g., CENTER_NAME = EARTH, REF_FRAME = TEME, TIME_SYSTEM = UTC, MEAN_ELEMENT_THEORY = SGP4) mandatory fields will not be included.

S1 also notes that some fields can be missing for some objects: "an object in the current analyst sat range (80000-series) typically will not have a name (OBJECT_NAME) or International Designator (OBJECT_ID)."

In the retrieved edition of S4, Table 4-1 is the OMM header; the keywords themselves are in Table 4-2 (OMM Metadata) and Table 4-3 (OMM Data). The keywords S4 defines that apply to SGP4 mean elements are:

| Keyword | S4 definition (abridged) | S4 table |
|---|---|---|
| `OBJECT_NAME` | Spacecraft name | 4-2 |
| `OBJECT_ID` | Object identifier (international designator) | 4-2 |
| `CENTER_NAME`, `REF_FRAME`, `TIME_SYSTEM`, `MEAN_ELEMENT_THEORY` | Frame and theory metadata; S1 says CelesTrak omits these from JSON and CSV as redundant | 4-2 |
| `EPOCH` | Epoch of the mean Keplerian elements | 4-3 |
| `MEAN_MOTION` | Keplerian mean motion, rev/day, when the theory is SGP/SGP4 | 4-3 |
| `ECCENTRICITY` | Eccentricity | 4-3 |
| `INCLINATION` | Inclination, deg | 4-3 |
| `RA_OF_ASC_NODE` | Right ascension of ascending node, deg | 4-3 |
| `ARG_OF_PERICENTER` | Argument of pericenter, deg | 4-3 |
| `MEAN_ANOMALY` | Mean anomaly, deg | 4-3 |
| `EPHEMERIS_TYPE` | Default 0 | 4-3 |
| `CLASSIFICATION_TYPE` | Default U | 4-3 |
| `NORAD_CAT_ID` | "NORAD Catalog Number ('Satellite Number') an integer of up to nine digits" | 4-3 |
| `ELEMENT_SET_NO` | "Normally incremented sequentially but may be out of sync if it is generated from a backup source. Used to distinguish different TLEs" | 4-3 |
| `REV_AT_EPOCH` | Revolution number | 4-3 |
| `BSTAR` | Drag like ballistic coefficient for SGP4, 1/Earth radii | 4-3 |
| `MEAN_MOTION_DOT` | First time derivative of mean motion, rev/day**2 | 4-3 |
| `MEAN_MOTION_DDOT` | Second time derivative of mean motion | 4-3 |

Which of these keys actually appear in a CelesTrak JSON response, and their JSON types (string or number), is not stated in CelesTrak's documentation. That is settled from a recorded real response, not from this table. The `raw.gp` schema is written against the recorded payload.

### What identifies a record

CelesTrak's documentation does not state a uniqueness key for GP records, and it does not say how to detect that a given object's elements have changed between downloads. What the sources do support:

* `NORAD_CAT_ID` identifies the object (S4), with up to 9 digits.
* `EPOCH` is the epoch of that element set (S4).
* `ELEMENT_SET_NO` is described by S4 as "used to distinguish different TLEs" but also as possibly "out of sync if it is generated from a backup source", so S4 itself warns against relying on it alone.

The pipeline deduplicates on the pair (`NORAD_CAT_ID`, `EPOCH`): one object, one element set epoch. This is a design choice consistent with S4's definitions, not a rule CelesTrak publishes.

## Update frequency

* S2: "For GP data, updates are once every 2 hours."
* S1: "CelesTrak only checks for new GP data once every 2 hours, so there is no need for you to check more often."
* S1 adds that the upstream data changes less often than that: "There really isn't any need to download after every CelesTrak update, since the 18 SDS GP data only updates 2-3 times a day."
* S3 shows a "Current as of" timestamp for the GP data at the top of the page (on the access date: "2026 Sep 27 08:20:53 UTC").

## Polling guidance for clients

From S2, the policy CelesTrak asks every client to follow:

> Only download the data you need, when you are going to use it, and only download data once per update.

From S1, the recommended client pattern:

> modify your process to use the latest data you downloaded by default ... add a step before using the latest data to check the data file's timestamp to see if it is more than 2 hours old. If it is, re-download the data to that file and proceed to use it. Otherwise, just use the latest data.

S1 also asks clients to download only the groups they need: "There is no reason to download all of the GROUPs, since these are intended to help users only download the smaller sets of satellites they need."

CelesTrak's documentation does not mention conditional requests (for example `If-Modified-Since` or ETags), a required `User-Agent`, or a per second request rate. None of these are stated.

## Behavior when a client over polls or mishandles errors

All of the following is documented in S1 or S2:

* **Temporary blocks with HTTP 403.** S1: CelesTrak enforces limits "with temporary blocks", and a blocked IP receives "a custom HTTP 403 error message explaining why you are being blocked". After the client stops, "the temporary blocks will be automatically removed within 2 hours."
* **One download per update.** S1 (update of 2026 Mar 26): CelesTrak "will now ... simply enforce the one-download-per-update policy for all users, starting with the Active and Starlink GROUPs." A repeat request before the data changes returns HTTP 403 with a message of this form:

  ```
  GP data has not updated since your last successful
  download of GROUP=active at 2026-03-26 08:10:22 UTC.
  Data is updated once every 2 hours.
  ```

  The documentation names the Active and Starlink groups as the ones enforced so far. It does not say whether the stations group is enforced.
* **Firewall after repeated errors.** S1: after a data outage in August 2025, "we now set a limit on HTTP errors (301, 403, or 404) of 50 in a 2-hour period, at which point the IP address is sent to the firewall." An earlier passage on the same page describes a threshold of more than 1,000 such errors in a day; the 50 in 2 hours limit is the later statement. S1 says a firewalled address "requires manual review to find and remove it."
* **Bandwidth.** S1: "If you are using more than 100 MB/day you can expect that your IP address may end up in the firewall."
* **Wrong domain.** S1: requests to a domain other than `https://celestrak.org` receive HTTP 301, and those 301s count toward the error limit above.
* **Stop on any non 200 response.** S2: "M2M (machine-to-machine) software should immediately stop querying when it receives any non-HTTP 200 responses and report the results to a human for investigation." S2 also says HTTP 50x "are server errors, meaning the server is struggling under heavy load. Queries need to stop immediately to allow system recovery for all users." S1: "if you receive an HTTP 403 or 404 error, the response is not going to change by repeating the request and can result in your IP address being put in the firewall."

## Implications for the poller

These follow only from the guidance above.

1. **Minimum poll interval: 2 hours per query.** CelesTrak refreshes GP data once every 2 hours (S1, S2) and asks for one download per update (S2). The poller never requests the same query more often than once every 2 hours. The interval is a configuration value with 2 hours as its floor and a default of 2 hours 10 minutes; the service refuses to start with a lower value ([ADR 0004](../adr/0004-celestrak-polling-and-error-handling.md)).
2. **The poller does not retry HTTP errors.** CelesTrak asks clients to stop on any non 200 response (S2) and counts 301, 403, and 404 responses toward a firewall threshold of 50 in 2 hours (S1). Exponential backoff with jitter is not appropriate for CelesTrak's HTTP responses. On any non 200 response the poller stops polling CelesTrak, logs the status and response body, marks the feed halted on its readiness endpoint (the stale data banner and alerting are planned to key off this), and waits for an operator; it resumes only after a restart. Failures that never produce an HTTP response (DNS, connection, TLS, timeout) do not reach CelesTrak's counters and are retried with backoff and jitter. A body that fails after a 200 status is dead lettered and the poller waits the full interval, since CelesTrak may already count that download. See [ADR 0004](../adr/0004-celestrak-polling-and-error-handling.md).
3. **A "not updated" 403 is still a stop.** The one download per update message arrives as HTTP 403 (S1). If a poll lands shortly before CelesTrak's next refresh, that response is possible on an enforced group. The documentation treats every 403 the same way, so the poller does too, rather than parsing the message text to decide whether to continue.
4. **Keep the last good payload.** S1 recommends reusing the most recent successful download until it is more than 2 hours old. The first version keeps the last fetch time in memory only, so a restart can fetch early; persisting it across restarts is deferred in [ADR 0004](../adr/0004-celestrak-polling-and-error-handling.md).
5. **Always send `FORMAT=JSON` and use `https://celestrak.org`.** The default format is CSV (S1), and any other domain returns a 301 that counts against the error limit (S1).
6. **Deduplicate on (`NORAD_CAT_ID`, `EPOCH`).** Two downloads 2 hours apart often carry the same element sets, since the upstream data updates only 2 to 3 times a day (S1). Only element sets with a new epoch are published to Kafka.
7. **Poll only what is needed.** The demo polls one group, the Space Stations group (`GROUP=stations`, linked from S3), chosen to cover the ISS (catalog number 25544, the object S1 uses in its `CATNR` examples). That the ISS is a member of the group is confirmed from the recorded response, not from the documentation. The watchlist can grow later; each addition is a separate query subject to the same 2 hour floor and counts toward the bandwidth guidance in S1.
