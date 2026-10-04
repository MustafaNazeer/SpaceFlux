# REST API

* **Status:** accepted, 2026-10-04.
* **Served by:** `query-api`, under the `/api` prefix, read only for anonymous viewers ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md), decision 6). Conventions are in [ADR 0010](../adr/0010-query-api-stack.md), decision 6 and its amendment: `snake_case` fields with the contract names, keyset paging, RFC 9457 problem details, and an `X-Correlation-Id` header on every response.

This page covers the first six read endpoints. Login, logout, and acknowledgement come with the authentication work; the newest alerts list, acknowledgement history, the paged catalog, and an object's full coverage come later.

## Common rules

* **Times** are RFC 3339 UTC strings ending in `Z`, written with the microseconds the database holds; a time copied from a contract field that is kept as text (`time_tag`, `epoch_text`, `run_id`, `event_id`) is returned exactly as received.
* **Absent, not null.** A field with no value is left out of the response, as the contracts leave it out of an event.
* **Errors** are `application/problem+json` bodies with `type`, `title`, `status`, `detail`, and `correlation_id`. `detail` never holds SQL, a stack trace, or a value from the database. An unknown path or a malformed parameter is `400` or `404`; anything unexpected is `500` with the same generic body.
* **Paging.** A list that can grow takes `limit` (default 50, at most 200) and `after`, an opaque cursor the previous page returned as `next`. A `limit` out of range, or a cursor the server cannot read, is `400`. A page with no `next` is the last one.
* **Acknowledgement**, wherever an alert appears, is the newest acknowledgement row for its `event_id` as `{"action": ..., "acted_at": ...}`, or absent when there is none. Anonymous responses never carry the note or the principal.

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
      "value": 1.0624149581417441e-05,
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

A scale with no series stored yet reads `{"scale": "G", "state": "no_data", "no_data_reason": "no_series"}`. `no_series` is a reason only this API returns; it is never on a topic.

## 2. Space weather history

`GET /api/space-weather/history?scale=R&satellite=18&from=...&to=...`

The state of each Kp interval (G) or GOES sample (R and S) in the time range, oldest first: for each key, the event with the highest `alert_seq` among the events that carry it (the schema's rule for "latest"). `scale` is required; `satellite` is required for R and S and refused for G. `from` and `to` are required, with `to` after `from` and at most 7 days apart; the range covers `interval_start` for G and `sample_time` for R and S, `from` inclusive and `to` exclusive. Paged.

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

## Tests

Each endpoint has a contract test against the migrated database that checks the response shape, the status codes above, and that no field outside this page appears, and each query's index is proven with `EXPLAIN` as [indexes.md](../data/indexes.md#how-each-index-is-proven) describes.
