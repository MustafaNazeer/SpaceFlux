# REST API

* **Status:** accepted, 2026-10-04.
* **Served by:** `query-api`, under the `/api` prefix, read only for anonymous viewers ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 6). Conventions are in [ADR 0010](../adr/0010-query-api-stack.md), decision 6 and its amendment: `snake_case` fields with the contract names, keyset paging, RFC 9457 problem details, and an `X-Correlation-Id` header on every response.

This page covers six read endpoints, the operator's session, and acknowledgement. The newest alerts list, the paged catalog, and an object's full coverage come later.

## Common rules

* **Times** are RFC 3339 UTC strings ending in `Z`, written with the microseconds the database holds; a time copied from a contract field that is kept as text (`time_tag`, `epoch_text`, `run_id`, `event_id`) is returned exactly as received.
* **Numbers.** An event or summary returned whole (`event` in section 4, `summary` and `close_approach` in section 3) is the stored text, so its numbers keep the spelling they were received with. Every other number is written by the API from the stored value, so a value received as `8.483063140829472e-09` is returned as the equal `8.483063140829472E-9`.
* **Absent, not null.** A field with no value is left out of the response, as the contracts leave it out of an event.
* **Errors** are `application/problem+json` bodies with `title`, `status`, `detail`, `instance` (the request path), and `correlation_id`, the answers of the security layer (`401`, both kinds of `403`, and `429`) included; `type` is left out, which RFC 9457 reads as `about:blank`. `detail` never holds SQL, a stack trace, or a value from the database. An unknown path or a malformed parameter is `400` or `404`; anything unexpected is `500` with the same generic body.
* **Paging.** A list that can grow takes `limit` (default 50, at most 200) and `after`, an opaque cursor the previous page returned as `next`. A `limit` out of range, or a cursor the server cannot read, is `400`. A page with no `next` is the last one.
* **Acknowledgement**, wherever an alert appears, is the newest acknowledgement row for its `event_id` as `{"action": ..., "acted_at": ...}`, or absent when there is none. Anonymous responses never carry the note or the principal; a request from the signed in operator also gets `principal` and, when the row has one, `note` ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 5).
* **Headers.** Every response, problem bodies and the `/error` path included, carries `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`. A session ID travels only in the session cookie, never in a URL.
* **Unsafe methods** (`POST`) need the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie; without it the answer is `403`. Only the three `POST` endpoints on this page accept an unsafe method; any other `POST`, `PUT`, `PATCH` or `DELETE` is refused by the filter chain.

## 1. Current space weather

`GET /api/space-weather/current`

One entry per scale, `G`, `R`, and `S`, always all three. The current series of a scale is the one that is not `ended` with the newest `freshness_reference` ([MySQL schema](../data/mysql-schema.md#space_weather_series)). Its state is shown as current only while the clock minus that `freshness_reference` is within the series' age limit ([scales note](../risk/space-weather-scales.md), Section 5.3: G 6 h 30 min, R 20 min, S 40 min). Past it, the entry reads `no_data` with `no_data_reason` `age_limit` and `no_data_since` the time the limit passed, whatever the stored state, with `freshness_reference` beside it.

```json
{
  "as_of": "2026-10-04T18:00:00.000000Z",
  "scales": [
    {
      "scale": "R",
      "satellite": 18,
      "state": "level",
      "derived_level": 1,
      "derived_label": "R1",
      "value": 1.0624149581417441E-5,
      "unit": "W m-2",
      "xray_class": "M1.0",
      "time_tag": "2026-10-04T17:52:00Z",
      "sample_time": "2026-10-04T17:52:00.000000Z",
      "freshness_reference": "2026-10-04T17:52:00.000000Z",
      "age_limit_s": 1200,
      "rules_version": 1
    }
  ]
}
```

A series that has no `freshness_reference` has no age to measure, so its stored state is returned as the risk engine set it, without `freshness_reference`. When two series of a scale have the same `freshness_reference`, the one with the higher satellite number is current. A scale with no series stored yet reads `{"scale": "G", "state": "no_data", "no_data_reason": "no_series"}`. `no_series` is a reason only this API returns; it is never on a topic.

## 2. Space weather history

`GET /api/space-weather/history?scale=R&satellite=18&from=...&to=...`

The state of each Kp interval (G) or GOES sample (R and S) in the time range, oldest first: for each key, the event with the highest `alert_seq` among the events that carry it (the schema's rule for "latest"). `scale` is required; `satellite` is required for R and S and refused for G. `from` and `to` are required RFC 3339 times with a year from 1000 to 9999, with `to` after `from` and at most 7 days apart; the range covers `interval_start` for G and `sample_time` for R and S, `from` inclusive and `to` exclusive. Paged.

The response echoes `scale`, and `satellite` for R and S. An item carries `interval_start` and `interval_end` for G or `sample_time` for R and S, then `time_tag`, `state`, `derived_level`, `derived_label`, `value`, `unit`, `xray_class` (R), `no_data_reason` and `no_data_since` (a restated sample or a rejected Kp revision is `no_data`), `trigger`, and `event_id`, each left out when the event has no value for it.

```json
{
  "scale": "G",
  "items": [
    {
      "interval_start": "2024-05-10T15:00:00.000000Z",
      "interval_end": "2024-05-10T18:00:00.000000Z",
      "time_tag": "2024-05-10T15:00:00",
      "state": "level",
      "derived_level": 4,
      "derived_label": "G4",
      "value": 7.67,
      "unit": "Kp index",
      "trigger": "level_change",
      "event_id": "space_weather_level/1/G/-/2024-05-10T15:00:00/2026-09-27T22:05:05Z"
    }
  ],
  "next": "..."
}
```

```json
{
  "scale": "R",
  "satellite": 18,
  "items": [
    {
      "sample_time": "2026-09-24T08:26:00.000000Z",
      "time_tag": "2026-09-24T08:26:00Z",
      "state": "no_data",
      "derived_label": "no data",
      "value": 8.483063140829472E-9,
      "unit": "W m-2",
      "no_data_reason": "zero_run_edge",
      "no_data_since": "2026-09-24T08:26:00.000000Z",
      "trigger": "restatement",
      "event_id": "space_weather_level/1/R/18/2026-09-24T08:26:00Z/2026-09-30T19:18:36Z/restated"
    }
  ]
}
```

## 3. Current screening run

`GET /api/screening/current`

The current run is the complete run with the newest `window_start`, ties broken by the higher `rules_version` ([MySQL schema](../data/mysql-schema.md#screening_run), "Completeness and the current run"). The response is the run's summary exactly as stored, with `stale` (true once the clock is more than 24 hours past `window_start`, or past `window_end`), and its approaches, each with its `event_id` and acknowledgement. `404` when no complete run is stored.

```json
{
  "stale": false,
  "summary": { "run_id": "2026-09-29T05:20:09Z/1", "window_start": "...", "coverage": { }, "suppressed": [ ] },
  "approaches": [
    {
      "event_id": "close_approach/1/2026-09-29T05:20:09Z/1/57036/27958/2026-09-30T03:34:37.588Z",
      "close_approach": { "run_id": "...", "miss_distance_m": 1973.3 },
      "acknowledgement": { "action": "acknowledge", "acted_at": "..." }
    }
  ]
}
```

Approaches come in publication order, the order of `approach_event_ids`, when the summary lists every id. When ids were cut, the run is every stored approach with its `run_id`, in the order this API received them.

This list is not paged, an exception to the paging rule above: it is one run, written whole by the risk engine, and bounded by the run's `approach_count` beside a summary the producer keeps within 900,000 bytes ([topics.md](../data/topics.md#screening_run), "Size budget"). The API is not exposed beyond loopback until a per client rate limit is in place. To find the current run, the API checks at most the 32 newest runs; when none of them is complete, the answer is `404`.

`summary` and each `close_approach` are the contract objects as received ([topics.md](../data/topics.md#screening_run)), including the lists the database keeps only in the stored event.

## 4. One alert

`GET /api/alerts/by-id?event_id=...`

The event exactly as received, its `received_at`, and its acknowledgement. `event_id` is a query parameter, because it holds `/` characters. `404` when it is not stored; `400` when it is missing or longer than 512 characters.

```json
{ "event": { "schema_version": 1, "kind": "close_approach", "event_id": "..." }, "received_at": "...", "acknowledgement": { } }
```

## 5. One catalog object

`GET /api/catalog/{norad_cat_id}`

The object's row in the catalog: its newest element set, `object_name` and `object_name_cut`, `epoch` and `epoch_text`, the mean elements, `fetched_at`, `source_url`, `first_fetched_at`, and `last_fetched_at` ([MySQL schema](../data/mysql-schema.md#catalog_object)). This is the newest element set seen for the object, not the one any screening run used. `400` for a `norad_cat_id` that is not a whole number from 0 to 999999999; `404` when it is not in the catalog.

## 6. The watchlist

`GET /api/watchlist`

Every watchlist object with its `catalog_number`, `name`, and `rules_version`, and its catalog row under `catalog` when the catalog has one. Ordered by `catalog_number`. Not paged: the list is a handful of objects configured with the risk engine.

## 7. The operator's session

There is one operator account ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 2). Reads need no session; anonymous requests never create one.

`GET /api/auth/session`

`200` with `{"username": "..."}` while signed in, `401` otherwise. Like every response, it sets the `XSRF-TOKEN` cookie (`Secure`, `SameSite=Strict`, readable by page script) when the request does not carry one, so the dashboard can call it before the login form. That cookie holds a random value, names no one, and keeps no state on the server.

`POST /api/auth/login`

Form parameters `username` and `password` (`application/x-www-form-urlencoded`) and the `X-XSRF-TOKEN` header. `204` on success, with a new session cookie `SPACEFLUX_SESSION` (`Secure`, `HttpOnly`, `SameSite=Strict`, path `/`) and a new `XSRF-TOKEN` cookie; a session that existed before the login is replaced. `401` for a wrong username or password, with the same body for both. `429` with `Retry-After` (seconds) while the client address or the whole service is backing off ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 8), or with `Retry-After: 1` while two password checks are already running; the password is not checked then. `400` when the URL has a query string, since credentials belong in the body. When the service has no operator configured, every login is `401`. Errors in order: `403` without a valid `X-XSRF-TOKEN` (not counted as a failure), then `429`, then `401`.

A session ends after 30 minutes without a request, 8 hours after the login, when a newer login replaces it, or at logout, whichever comes first. After that, a request carrying its cookie is answered exactly as one without it: the read endpoints serve the anonymous view, and the session and acknowledgement endpoints answer `401` with a problem body.

`POST /api/auth/logout`

With the `X-XSRF-TOKEN` header. `204`, the session ended, and on HTTPS `Clear-Site-Data: "cookies"` (in the cloud this needs the ingress's forwarded protocol to be trusted, [ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 8). Logging out without a session is also `204`.

## 8. Acknowledge an alert

`POST /api/alerts/acknowledgements?event_id=...`

Signed in operator only, with the `X-XSRF-TOKEN` header. The body is JSON with exactly these fields:

| Field | Required | Rule |
|---|---|---|
| `action` | yes | `acknowledge` or `unacknowledge` |
| `note` | no | Text of at most 500 Unicode code points; a longer note is refused, never cut |

`201` with the row written:

```json
{ "event_id": "...", "action": "acknowledge", "principal": "...", "acted_at": "...", "note": "..." }
```

`principal` is the signed in username and `acted_at` the database's time of the insert; neither can be sent. Errors, in the order they are checked; the first three are answered before any database access:

| Status | When |
|---|---|
| `403` | No operator is configured: every request, before any other check |
| `403` | Missing or wrong `X-XSRF-TOKEN` |
| `401` | No session |
| `415` | The `Content-Type` is not `application/json` |
| `400` | `event_id` missing or longer than 512 characters; a body that is not one JSON object, or that repeats a field; a missing or unknown `action`; a `note` that is not text (`null` included) or is over 500 code points; any other field |
| `404` | No alert has this `event_id` |
| `409` | The alert cannot be acknowledged (only `close_approach` events and `space_weather_level` events in state `level` can), or `action` equals its current state |

## 9. Acknowledgement history

`GET /api/alerts/acknowledgements?event_id=...`

Every acknowledgement row of one alert, newest first, each `{"action": ..., "acted_at": ...}`, plus `principal` and `note` for the signed in operator as the common rules say. Paged. `404` when no alert has this `event_id`; `400` when it is missing or longer than 512 characters. An alert with no rows has an empty `items`.

```json
{ "event_id": "...", "items": [ { "action": "unacknowledge", "acted_at": "..." }, { "action": "acknowledge", "acted_at": "..." } ], "next": "..." }
```

## Tests

Each endpoint has a contract test against the migrated database that checks the response shape, the status codes above, and that no field outside this page appears, and each query's index is proven with `EXPLAIN` as [indexes.md](../data/indexes.md#how-each-index-is-proven) describes.
