# Kafka topics

This page lists every Kafka topic SpaceFlux has a contract for, whether or not a service writes to it yet, with its key, its value schema, and how consumers should treat it. Schemas are JSON Schema draft 2020-12 files under `schemas/`, one file per topic and major version, as decided in [ADR 0002](../adr/0002-event-schemas-and-serialization.md). The feed facts behind the `raw.gp` contract are in [the CelesTrak source note](../source/celestrak.md), and those behind `raw.swpc` are in [the SWPC source note](../source/swpc.md).

## Summary

| Topic | Producer | Key | Value schema | Retention |
| --- | --- | --- | --- | --- |
| `raw.gp` | `ingest` | `NORAD_CAT_ID` as a decimal string, for example `25544` | [`schemas/raw.gp/v1.schema.json`](../../schemas/raw.gp/v1.schema.json) | To be decided with measurements |
| `raw.gp.dlq` | `ingest`, and any consumer of `raw.gp` | The source event's key when known, otherwise no key | [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json) | To be decided with measurements |
| `raw.swpc` | `ingest` | The product ID, for example `swpc.kp` | [`schemas/raw.swpc/v1.schema.json`](../../schemas/raw.swpc/v1.schema.json) | To be decided with measurements |
| `raw.swpc.dlq` | `ingest`, and any consumer of `raw.swpc` | The product ID | [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json) | To be decided with measurements |
| `alerts` | `risk-engine` | Per event kind: `space_weather.<scale>` for space weather, the screening `run_id` for close approaches and run summaries | [`schemas/alerts/v1.schema.json`](../../schemas/alerts/v1.schema.json) | To be decided with measurements |
| `alerts.dlq` | `risk-engine`, and any consumer of `alerts` | The source event's key | [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json) | To be decided with measurements |

## `raw.gp`

One event is one CelesTrak GP element set for one object. A poll of a CelesTrak group returns a JSON array; `ingest` splits it and publishes each new element set as its own event.

### Value

An envelope around the record exactly as CelesTrak sent it:

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer, always `1` | Major version of the contract the event was written against |
| `source` | string, always `celestrak` | Provider |
| `fetched_at` | RFC 3339 UTC string ending in `Z` | When `ingest` received the HTTP response |
| `source_url` | string | The exact URL requested |
| `gp` | object | The CelesTrak record with CelesTrak's key names and JSON types, untransformed |

Every recorded CelesTrak response carries the same 17 keys. The `gp` object requires 15 of them: `EPOCH`, `MEAN_MOTION`, `ECCENTRICITY`, `INCLINATION`, `RA_OF_ASC_NODE`, `ARG_OF_PERICENTER`, `MEAN_ANOMALY`, `EPHEMERIS_TYPE`, `CLASSIFICATION_TYPE`, `NORAD_CAT_ID`, `ELEMENT_SET_NO`, `REV_AT_EPOCH`, `BSTAR`, `MEAN_MOTION_DOT`, `MEAN_MOTION_DDOT`. `OBJECT_NAME` and `OBJECT_ID` are optional, because CelesTrak documents that analyst objects in the 80000 series typically have neither a name nor an international designator ([source note](../source/celestrak.md)). Their meanings and units are the OMM (orbit mean elements message) keywords in CCSDS 502.0-B-3, Tables 4-2 and 4-3, which is how CelesTrak defines its JSON format.

Choices in the schema and where each one comes from:

* **Types** follow the recorded responses: four strings (`OBJECT_NAME`, `OBJECT_ID`, `EPOCH`, `CLASSIFICATION_TYPE`), four integers (`EPHEMERIS_TYPE`, `NORAD_CAT_ID`, `ELEMENT_SET_NO`, `REV_AT_EPOCH`), and nine numbers. `MEAN_MOTION_DDOT` is often the bare integer `0`, so it is typed as any JSON number.
* **Ranges** come only from the CCSDS NDM/XML 3.0.0 OMM schema (`ndmxml-3.0.0-omm-3.0.xsd` and `ndmxml-3.0.0-common-3.0.xsd` on sanaregistry.org, the files CCSDS 502.0-B-3 cites):
  * `ECCENTRICITY` at least 0 (type `nonNegativeDouble`; the XML schema sets no upper bound, so neither does this one).
  * `INCLINATION` 0 to 180 degrees inclusive (`inclinationRange`).
  * `RA_OF_ASC_NODE`, `ARG_OF_PERICENTER`, `MEAN_ANOMALY` from -180 inclusive to 360 exclusive (`angleRange`).
  * `ELEMENT_SET_NO` 0 to 9999 (`elementSetNoType`).
  * `REV_AT_EPOCH` at least 0 (`nonNegativeInteger`).
  * `NORAD_CAT_ID` 0 to 999999999, from the OMM definition "an integer of up to nine digits". There is no five digit cap: current data already contains catalog numbers above 99999.
  * `MEAN_MOTION`, `BSTAR`, `MEAN_MOTION_DOT`, `MEAN_MOTION_DDOT` and `EPHEMERIS_TYPE` have no range in the OMM schema and are left unconstrained.
  * `CLASSIFICATION_TYPE` and `EPHEMERIS_TYPE` are not enumerated, because CCSDS only gives a suggested coding for them.
* **`EPOCH`** has no time zone designator and no `format` keyword. It must match the CCSDS calendar form `YYYY-MM-DDThh:mm:ss` with optional fractional seconds of any length (CCSDS 502.0-B-3 section 7.5.10). CelesTrak omits the optional `Z`, and its time system is UTC. Recorded values have six fractional digits.
* **Timestamps** `fetched_at` carry `format: date-time` and also a pattern, because in draft 2020-12 `format` is only an annotation unless a validator is configured to assert it.
* **Additional properties are allowed in `gp`.** CelesTrak defines its JSON by reference to the OMM standard and can add keywords. Since `ingest` validates every record before publishing, a closed `gp` object would send every record to the dead letter topic the day a new keyword appeared. Consumers read the keys they need and ignore the rest. The envelope is open for the same reason: ADR 0002 lets a version gain optional fields, and a consumer still running the previous copy of the file must accept them.

### Key and partitioning

The record key is `gp.NORAD_CAT_ID` written as a decimal string. All element sets for one object land on one partition, so a consumer sees each object's element sets in the order `ingest` published them. That ordering holds only while the partition count stays fixed, the producer keeps idempotence enabled, and each consumer processes a partition's events one at a time; see [the architecture overview](../architecture.md#topics-and-contracts). The partition count is not fixed yet; it is set once consumer lag has been measured.

### Deduplication

Delivery is at least once, so the same element set can arrive more than once. The dedupe key is the pair (`NORAD_CAT_ID`, `EPOCH`): one object, one element set epoch. `ingest` skips an element set whose pair matches the latest one it published for that object since it started, and duplicates within one response; it keeps this in memory, so a restart can republish. Every consumer drops a pair it has already processed, which is what makes the delivery safe. `ELEMENT_SET_NO` is not part of the key, because the OMM standard notes it can be out of sync when generated from a backup source.

Because the `EPOCH` pattern allows different numbers of fractional digits, comparing it as a parsed timestamp is safer than comparing raw strings.

### Example

[`schemas/raw.gp/examples/valid-iss.json`](../../schemas/raw.gp/examples/valid-iss.json) is the ISS (ZARYA) record from a recorded CelesTrak `GROUP=stations` response, byte for byte, with `fetched_at` set to that response's capture time.

## `raw.gp.dlq`

Every topic's dead letter topic uses the same envelope, [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json). Nothing that fails is dropped silently; it lands here with the reason attached.

| Field | Required | Meaning |
| --- | --- | --- |
| `schema_version` | yes | Always `1` |
| `source_topic` | yes | The topic the payload was meant for or read from, for example `raw.gp` |
| `service` | yes | Which service dead lettered it. Both the producer and every consumer validate, so the stage alone does not say who failed |
| `stage` | yes | `fetch`, `decode`, `validate`, or `publish` |
| `check` | no | Only with `stage` `validate`, and set only when a check rejected the payload: `schema` when a topic schema check (in `ingest` or a consumer) rejected it, `rule` when it matches its schema but a rule of `risk-engine` rejected its value. Absent when the payload never reached either check (the UTF-8 and record size dead letters in `ingest`), when neither check rejected it (a record that passed its topic schema but failed `ingest`'s identity parse, for example an `EPOCH` that matches the pattern but is not a real date), and on dead letters written before the field existed |
| `reason` | yes | Human readable error, including the failing field path for validation errors |
| `failed_at` | yes | RFC 3339 UTC time of the failure, ending in `Z` |
| `source_url` | no | The provider URL, when there is one |
| `payload` | yes | The failing bytes, unchanged |
| `payload_encoding` | yes | `utf-8` when the bytes are stored as text, `base64` when they are not valid UTF-8 |
| `payload_bytes` | yes | Length of the original bytes before encoding or truncation |
| `payload_truncated` | no | `true` when `payload` holds only a prefix, because the original was too large for a Kafka record; absent means `false` |

The payload is a string rather than embedded JSON because the bytes that failed may not be JSON at all, as with a truncated response body.

What goes in the payload depends on the stage: for a `fetch` failure (a response body that was too large or cut off after a 200 status) it is the part of the body that arrived; for a `decode` failure it is the whole response body, since no record could be extracted; for a `validate` failure it is the single record or event that failed, so the other records in the same response are still published. Payloads are cut to at most 256 KiB, on a character boundary, so a dead letter fits in one Kafka record (the client's default limit is 1,000,012 bytes). JSON escaping can inflate some text up to sixfold, so a dead letter that would still be too large stores its payload as base64 instead. A single `raw.gp` record over 512 KiB is dead lettered rather than published. For a `fetch` failure the payload is always marked truncated, and `payload_bytes` counts the bytes received, which for a body over the size limit is the limit plus one rather than the full size.

The key is the source event's key when it is known (a single record that failed validation) and empty otherwise (a body that could not be decoded).

[`schemas/dlq/examples/truncated-body.json`](../../schemas/dlq/examples/truncated-body.json) shows a `decode` failure on a CelesTrak response cut off after 200 bytes. The payload is the first 200 bytes of a recorded response; the truncation itself was made by hand to exercise this path.

## `raw.swpc`

One event is one record from one NOAA SWPC JSON product. Each polled file is a JSON array; `ingest` splits it and publishes each new record as its own event. Which files are polled, how often, and how errors are handled is set in [ADR 0005](../adr/0005-swpc-polling-and-error-handling.md).

| Product ID | File under `https://services.swpc.noaa.gov/` | One record is | Used for |
| --- | --- | --- | --- |
| `swpc.kp` | `products/noaa-planetary-k-index.json` | One 3 hour planetary Kp value | G scale |
| `swpc.goes.xrays` | `json/goes/primary/xrays-6-hour.json` | One 1 minute X-ray flux average for one satellite and one passband | R scale, from the `0.1-0.8nm` records only |
| `swpc.goes.protons` | `json/goes/primary/integral-protons-6-hour.json` | One 5 minute integral proton flux average for one satellite and one energy threshold | S scale, from the `>=10 MeV` records only |
| `swpc.alerts` | `products/alerts.json` | One SWPC alert, watch, warning, or summary message | SWPC's own issued messages |

### Value

An envelope around the record exactly as SWPC sent it:

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer, always `1` | Major version of the contract the event was written against |
| `source` | string, always `swpc` | Provider |
| `product` | string, one of the four product IDs above | Which file the record came from; also the Kafka key |
| `fetched_at` | RFC 3339 UTC string ending in `Z` | When `ingest` received the HTTP response |
| `source_url` | string starting with `https://` | The exact URL requested |
| `record` | object | One element of the SWPC array with SWPC's key names and JSON types, untransformed |

The schema for `record` is chosen by `product` (an `if`/`then` per product in the schema file). Each record schema requires only the fields that the dedupe identity or the NOAA scale rules need; every other field SWPC sends is typed but optional.

| Product | Required | Why each is required | Optional, typed |
| --- | --- | --- | --- |
| `swpc.kp` | `time_tag` (string), `Kp` (number) | (`time_tag`, `Kp`) is the identity ([ADR 0006](../adr/0006-kp-record-identity.md)); `time_tag` is the time of the value and `Kp` is what the G scale is read from | `a_running` (integer), `station_count` (integer) |
| `swpc.goes.xrays` | `time_tag` (string), `satellite` (integer), `energy` (string), `flux` (number), `observed_flux` (number) | The first three are the identity, and `energy` selects the band the R scale is defined on; SWPC does not say which of `flux` and `observed_flux` the R scale is read from, so both are required until that is settled | `electron_correction` (number), `electron_contaminaton` (boolean) |
| `swpc.goes.protons` | `time_tag` (string), `satellite` (integer), `energy` (string), `flux` (number) | The first three are the identity, and `energy` selects the threshold the S scale is defined on; `flux` is what the S scale is read from | none |
| `swpc.alerts` | `product_id` (string), `issue_datetime` (string), `message` (string) | The first two are the identity; `message` is the only content of the record, so an alert without it carries nothing to act on | none |

Choices in the schema and where each one comes from:

* **Key names and types** follow the recorded responses. Every record in each of the four recorded files has the same keys in the same order, and no value is `null`. `Kp` has a capital K in this file (the Kp forecast file spells it `kp`). `electron_contaminaton` is misspelled that way in SWPC's data and is kept as sent.
* **No ranges.** SWPC publishes no value ranges for these files, so none are enforced.
* **Time formats** match the observed form exactly, one pattern per format:
  * Kp `time_tag`: `YYYY-MM-DDThh:mm:ss`, no zone designator, no fractional seconds.
  * GOES `time_tag`: `YYYY-MM-DDThh:mm:ssZ`, no fractional seconds.
  * Alert `issue_datetime`: `YYYY-MM-DD hh:mm:ss.sss`, a space instead of `T`, three fractional digits, no zone designator.

  If SWPC changes a format, the affected records are dead lettered with the pattern error, so the change is visible instead of being misread downstream.
* **`energy` and `product_id` are not enumerated.** The recorded values are `0.05-0.4nm` and `0.1-0.8nm` for X-rays, eight thresholds from `>=1 MeV` to `>=500 MeV` for protons, and 15 distinct message types for alerts. A new value from SWPC should reach consumers, not dead letter every record.
* **Additional properties are allowed** in `record` and in the envelope, for the same reasons as `raw.gp`: SWPC can add keys, and a version can gain optional fields.
* **`product` is set by `ingest`** from the file it polled, never inferred from the record. The record schemas cannot catch every mislabel: an X-ray record carries every field the proton schema requires, so it would pass as `swpc.goes.protons`. Kp and alert records fail under any other product, and a proton record fails as `swpc.goes.xrays` because it has no `observed_flux`.
* **`fetched_at`** carries `format: date-time` and a pattern, as in `raw.gp`.

### Key and partitioning

The record key is `product`. All records of one product land on one partition, so a consumer sees each product's records in the order `ingest` published them, under the same conditions as for `raw.gp`. With four keys, at most four partitions receive `raw.swpc` records whatever the partition count.

### Deduplication

Each product has its own identity, which is unique within every recorded response:

| Product | Identity |
| --- | --- |
| `swpc.kp` | `time_tag` |
| `swpc.goes.xrays` | `time_tag`, `satellite`, `energy` |
| `swpc.goes.protons` | `time_tag`, `satellite`, `energy` |
| `swpc.alerts` | `product_id`, `issue_datetime` |

`satellite` is part of the GOES identity because SWPC switches which satellite is primary: its instrument source list shows the primary X-ray satellite moving between GOES 19 and GOES 18 on 2026-09-22. Two satellites' values for the same minute are two records. `product_id` alone is a message type, not a message, and a correction reuses the serial number of the message it corrects, so neither identifies an alert on its own.

Each file is a sliding window over recent data. In the recorded responses the windows were about 7.5 days for Kp, 6 hours for X-rays and protons, and about 30 days for alerts; SWPC does not document the window lengths. Consecutive responses therefore mostly repeat each other. `ingest` publishes a record only when its identity was not in the previous successful response for the same product, which publishes the records that entered the window and nothing else. A 304 response publishes nothing. After a restart there is no previous response, so the first response is published in full. Every consumer drops identities it has already processed, which is what makes the delivery safe. The exception is `swpc.kp`: its consumers keep the latest record for each `time_tag` as that interval's value ([ADR 0006](../adr/0006-kp-record-identity.md)), so a value that is revised and then revised back is applied again rather than dropped as already seen.

### What SWPC does not document

Consumers need to know these points, none of which SWPC states:

* **Time zone of the Kp `time_tag`.** The value has no zone designator, and neither the file nor the product page gives one. It is unconfirmed.
* **Time zone of the alert `issue_datetime`.** The value has no zone designator either, but each message's `Issue Time:` line states UTC, and in all 68 recorded alerts it agrees with `issue_datetime` to the minute.
* **Kp revisions.** SWPC calls these values estimated and does not say whether a published value is ever revised. Because the identity is (`time_tag`, `Kp`) ([ADR 0006](../adr/0006-kp-record-identity.md)), a revised value is published again as a second record with the same `time_tag`, and consumers take the latest record for a `time_tag` as its value.
* **`flux` versus `observed_flux`.** The X-ray product page does not explain how the two differ, what `electron_correction` is, or which value the R scale is read from. In the recorded response, `electron_contaminaton` is `true` in 357 of the 358 `0.05-0.4nm` records and `false` in every `0.1-0.8nm` record, and three `0.05-0.4nm` records have a `flux` of `9.999999717180685e-10`, which looks like a floor value but is undocumented.
* **Gaps are normal.** SWPC notes GOES data dropouts during instrument calibrations and satellite eclipses, and posted a GOES and solar wind outage on 2026-09-22. A missing minute is not a parse failure.
* **Alert text is untrusted input** to anything downstream.

### Examples

[`schemas/raw.swpc/examples/`](../../schemas/raw.swpc/examples/) holds one event per product: `valid-kp.json`, `valid-goes-xrays.json`, `valid-goes-protons.json`, and `valid-alert.json`. Each `record` is the first record of a recorded SWPC response, byte for byte, with `fetched_at` set to that response's capture time.

## `raw.swpc.dlq`

Uses the shared dead letter envelope, [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json), unchanged, with `source_topic` set to `raw.swpc` and `source_url` set to the product file. What goes in the payload at each stage is the same as for `raw.gp.dlq`.

The key is the product ID for every dead letter. Each payload comes from polling exactly one product, so unlike `raw.gp.dlq` the key is known even when a body cannot be decoded.

An empty list is dead lettered for `swpc.kp`, `swpc.goes.xrays`, and `swpc.goes.protons`, where it means the feed broke, and accepted silently for `swpc.alerts`, where a quiet period can legitimately leave no alerts; accepting it does not reset deduplication. A body that is JSON `null` is dead lettered for every product. Because each file is a sliding window, a bad record is dead lettered once while it stays in the window, and an undecodable body once while the same body keeps repeating (it is dead lettered again if it returns after a good response). Both feeds' processors dead letter a record that is not valid UTF-8, since a strict JSON reader would reject it.

`risk-engine` also writes here. A record that fails the schema when it is read gets `stage` `validate` and `check` `schema`. A record that matches the schema but that a rule of [the scales note](../risk/space-weather-scales.md) rejects, for example a Kp above 9.00, a negative flux, or an X-ray flux above 0 but below the floor or above 0.2 W m-2, gets `stage` `validate` and `check` `rule`. Either way the record sets no level; when it is the newest record of its series, the series is in "no data" on `alerts`. An X-ray flux of exactly 0 is the exception: it is SWPC's marker for a missing measurement, not corrupt data, so it is not dead lettered here. It still sets no level, makes the series "no data" (reason `rejected`) when it is the newest record, and is counted by `risk-engine`. That keeps the zeros of each night's eclipse in eclipse season out of this topic.

## `alerts`

`risk-engine` publishes every result it derives to `alerts`: space weather levels derived from `raw.swpc`, and close approaches and run summaries from screening the watchlist against the catalog. `query-api` consumes it. The design and the alternatives are in [ADR 0007](../adr/0007-alerts-topic.md). The name is close to SWPC's own `swpc.alerts` product on `raw.swpc`, but an `alerts` event is a result SpaceFlux derived, never an SWPC alert, watch, or warning, and must never be worded as an SWPC ALERT message.

Nothing on this topic is an official NOAA scale level, a forecast, or an operational conjunction assessment. A space weather level is derived mechanically from one measurement ([the scales note](../risk/space-weather-scales.md), Section 4 for how it may be presented), and a close approach means two public element sets propagated with SGP4 come within 5 km ([the orbital conventions](../risk/orbital-conventions.md), Section 5).

### Value

Every event has this envelope and exactly one of the three payload objects, the one named by `kind`:

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer, always `1` | Major version of the contract the event was written against |
| `kind` | `space_weather_level`, `close_approach`, or `screening_run` | Which payload object the event carries |
| `rules_version` | integer, 1 or more | The version of the rules the event was derived under. I raise it by hand whenever a threshold, a rule, or a screening setting changes (anything in [the scales note](../risk/space-weather-scales.md) or [the orbital conventions](../risk/orbital-conventions.md) that can change a derived level, a rejection, or a screening result, and any change to the station stack list `risk-engine/src/main/resources/screening/stacks.json`). It is part of every `event_id` and of `run_id`, so reprocessing under new rules publishes new events rather than being dropped as duplicates |
| `event_id` | string starting with the kind, `/`, `rules_version`, and `/` | The identity consumers deduplicate on; see [Deduplication](#deduplication-2) |
| `produced_at` | RFC 3339 UTC string ending in `Z` | When `risk-engine` wrote the event, not when the data was measured or fetched |
| `space_weather_level`, `close_approach`, or `screening_run` | object | The payload, below |

Timestamps written by SpaceFlux carry `format: date-time` and the same pattern as on the raw topics. Additional properties are allowed everywhere, as on the raw topics, so a version can gain optional fields.

### `space_weather_level`

A series is the G series from planetary Kp (one series, no satellite), or the R series from GOES 0.1-0.8nm X-ray flux or the S series from GOES >=10 MeV integral proton flux, one series per satellite. Values from two satellites are never merged. An event is published for one of four reasons, given in `trigger`:

* `level_change`: the series' current state differs from its previous state. That includes a change into or out of "no data" or "ended", and the first state known after `risk-engine` starts. Samples are read in `time_tag` order and each can change the state; a sample in the same state as the one before publishes nothing.
* `revision`: SWPC sent a different Kp value for an interval already seen. Every revision is published, whether or not the level changed, including a revision to "none". G only. When the revised interval is the newest one, the revision also sets the current state and is the only event published for it; a revision of an older interval replaces only that interval's state. A revision to a value the rules reject (for example Kp 9.5) has state `no_data`, `no_data_reason` `rejected`, and `no_data_since` the interval start, carries the interval's `time_tag`, `interval_start`, `interval_end`, `fetched_at`, `source_url`, `freshness_reference`, and `previous_state`, and has no value; the record is also dead lettered with `check` `rule`. Revising the newest interval that way sets the current state to `no_data`.
* `restatement`: a sample published as "none" lies within the edge before a zero run of X-ray records, which is only known once the zeros arrive, so it is restated as "no data" ([the scales note](../risk/space-weather-scales.md), Sections 5.1 and 5.2). SWPC's value is unchanged. Published only for a sample that had an event of its own, which includes a refresh on arrival that carried it as the newest record, because consumers store R states per `sample_time`. R only. It replaces that sample's state and never sets the current state.
* `refresh`: the state did not change. A current series (not ended, within its age limit) repeats its current state with its newest record, that record's value, `xray_class` where it applies, and `freshness_reference`. It is published when newer records arrive for the series without changing its state, and by a timer fallback only when nothing went out for the series within its interval (the intervals are in the scales note, Section 5.3). `state` is `level` or `none`. State changes are still published at once. Timer refreshes are not reproducible on replay.

 Each event describes one sample or one 3 hour interval, never an event with a start and an end.

| Field | Present | Meaning |
| --- | --- | --- |
| `scale` | always | `G`, `R`, or `S` |
| `product` | always | The `raw.swpc` product the value came from: `swpc.kp` for G, `swpc.goes.xrays` for R, `swpc.goes.protons` for S |
| `state` | always | `level`, `none`, `no_data`, or `ended`; see the next table |
| `derived_level` | `level` only | 1 to 5, derived with the thresholds of the scales note; never SWPC's issued level. `derived_label` always matches it |
| `derived_label` | always | `G1` to `S5`, `none`, or `no data` (for both `no_data` and `ended`) |
| `previous_state`, `previous_derived_level` | when known; always on a revision; never on a refresh | The state before the event (for a revision or restatement, the state of that interval or sample). Absent when `risk-engine` held no earlier state, for example after a restart |
| `trigger` | always | `level_change`, `revision`, `restatement`, or `refresh` |
| `derived_from` | always | The measurement in words, for example `SWPC estimated planetary Kp` or `GOES-16 X-ray flux 0.1-0.8nm` |
| `estimated` | always | `true` for G (SWPC calls Kp estimated, and a value can be revised); `false` for R and S |
| `satellite` | R and S | GOES satellite number; absent for G |
| `band` | R | `0.1-0.8nm` |
| `channel` | S | `>=10 MeV` |
| `unit` | always | `Kp index`, `W m-2`, or `pfu`, the unit of the series, also on events that carry no value |
| `value` | `level`, `none`, and restatements (a G `value` is at most 9.005); never on a rejected Kp revision | The value as received |
| `xray_class` | R, `level`, GOES-16 or later, optional | The X-ray class of this one value: M or X, truncated to one decimal, the X number continuing past 10, as Section 2.4 of the scales note defines. It agrees with `derived_level`: R1 is M1.0 to M4.9, R2 M5.0 to M9.9, R3 X1.0 to X9.9, R4 X10.0 to X19.9, R5 X20.0 and above. Never a flare's class |
| `time_tag` | `level`, `none`, restatements, and rejected Kp revisions | SWPC's own `time_tag`, as sent (Kp has no zone designator) |
| `interval_start`, `interval_end` | G, `level`, `none`, and rejected Kp revisions | The 3 hour UTC interval the Kp value describes; `interval_start` is `time_tag` read as UTC (scales note, Section 1.3) |
| `sample_time`, `averaging_period_s` | R and S, `level`, `none`, and restatements | The UTC time of the GOES average and its length: 60 s for X-rays, 300 s for protons |
| `fetched_at`, `source_url` | `level`, `none`, restatements, and rejected Kp revisions | Copied from the `raw.swpc` event the value came from, so replayed or archived data shows when it was really fetched |
| `freshness_reference` | `level`, `none`, refreshes, and restatements; otherwise whenever the series has had a record with a usable `time_tag` | The newest `time_tag` of any record of the series with a usable `time_tag`, rejected values, zeros and edge values included, as UTC (for G, the newest `interval_start`); scales note, Sections 5.1 and 5.3 |
| `timer_refresh_at` | timer refreshes | The tick of the fallback timer that produced the refresh; absent on a refresh sent on arrival of newer records |
| `no_data_reason` | `no_data` | `rejected` (the newest record was rejected, scales note Section 5.1; this is also the reason at the start of a zero run, even when `no_data_since` points back at a well formed edge sample), `zero_run_edge` (R only: a would be "none" value on the edge of a zero run, Sections 5.1 and 5.2; always the reason on a restatement, and on a `level_change` in practice only after a restart, because otherwise the series is already in "no data" from the zeros when an edge value after them arrives), or `age_limit` (Section 5.3) |
| `no_data_since` | `no_data` and `ended` | The time from which the series has no data, as the scales note defines it: the rejected record's `time_tag`, moved back to the first restated edge sample after the last level inside the leading edge of a zero run, so a "no data" span never covers a published level; the time the age limit was passed, which is not the time data stopped, so a surface shows `freshness_reference`, the last record's time, next to it; the ended series' newest `time_tag`; on a restatement, the restated sample's time; or, on a rejected Kp revision, the interval start |
| `restated_by_time_tag` | restatements | The `time_tag` of the first zero of the run that showed the sample was on its edge |
| `ended_by_satellite` | `ended` | The satellite whose records took over |

The states:

| `state` | Means | Never read as |
| --- | --- | --- |
| `level` | A valid value at or above level 1 | An issued scale level, a forecast, or a notice to act |
| `none` | A valid value below level 1 | "No data" |
| `no_data` | No valid value, for the reason in `no_data_reason` | "None" or quiet |
| `ended` | R or S only: SWPC's primary file moved to another satellite, whose records reached or passed this series' newest `time_tag` (scales note, Section 5.4). Reads as "no data", and the series is no longer the current one of its scale | The last level held, or a series still in "no data" |

**Current state and staleness.** The current state of a scale is the series that is not ended with the newest `freshness_reference`. A consumer shows it as current only while the clock minus the newest `freshness_reference` it holds for that series, from any event including a refresh, is within the series' age limit in the scales note, Section 5.3. `risk-engine` also publishes a `no_data` event with reason `age_limit` when the clock passes the limit, so a series that receives nothing at all still changes state. Age is measured against the clock, not `fetched_at`, so archived or replayed data never reads as current: a level derived from it describes its own sample and is followed at once by `no_data`.

**Rejected values** set no level; each is dead lettered to `raw.swpc.dlq` with `check` `rule`, except an X-ray flux of exactly 0, SWPC's missing marker, which is counted instead. The rejections are listed in the scales note, Section 5.1; besides the value checks they include a `time_tag` that is not a real UTC time, a `time_tag` more than 5 minutes after `fetched_at`, a Kp `time_tag` off a 3 hour boundary, and a `satellite` below 1. An edge value read as "no data" is a valid record and is not dead lettered. When a rejected record is the newest of its series, the series goes to `no_data`. The rules, including the upper bound on X-ray flux and the width of a zero run's edge, are the scales note's, Sections 5.1 and 5.2.

**Primary satellite switch.** When one satellite's records reach or pass the newest `time_tag` of another satellite's series of the same scale, the old series gets an `ended` event and the new series its current state. The records a new or resumed series receives in one response (one `fetched_at`) are read as one batch: only the newest sets the state and can produce a `level_change` event, and the older ones are history, not published here, as the scales note, Section 5.4 requires. They remain on `raw.swpc`.

**Kp intervals and GOES samples.** A consumer stores G states by `interval_start` and R and S states by satellite and `sample_time`, and takes the latest event for each as its state, the same rule `raw.swpc` consumers follow for Kp records ([ADR 0006](../adr/0006-kp-record-identity.md)).

**After a restart** the first event of a series has no `previous_state` and can repeat the state a consumer already holds; that is not a change.

### `close_approach`

One approach within the report distance (5 km) found by one screening run.

| Field | Meaning |
| --- | --- |
| `run_id` | The run that found it; its `screening_run` event has the same `run_id` |
| `window_start`, `window_end` | The run's 7 day window, UTC |
| `watchlist_object` | The watchlist object the search ran for: `catalog_number`, `element_age_days`, and `name` when the element set has one |
| `other_object` | The catalog object it came close to, same fields; it can itself be on the watchlist |
| `time_of_closest_approach` | UTC |
| `miss_distance_m` | Distance between the two propagated positions at that time, in metres, at the precision computed. Its uncertainty is at least of the order of kilometres and grows with element age, so surfaces show it in kilometres with no more precision than that supports (orbital conventions, Sections 4 and 5) |
| `relative_speed_m_per_s` | In metres per second |

`element_age_days` is the time of closest approach minus the element set epoch, in days, and is negative when the epoch is after it. The window starts at the newest input fetch, not at the moment the run executes, so an approach can already be in the past when it is read; it is reported with its UTC time like any other (orbital conventions, Section 3.2).

### `screening_run`

One event per run, published after all of the run's approaches, and published even when the run finds none. A run is stale 24 hours after its `window_start` and always after its `window_end` ([the orbital conventions](../risk/orbital-conventions.md)). No approaches means only that no searched pair's SGP4 propagation came within `report_distance_m`: objects not in the input, rejected, not screened, or in a suppressed pair are not covered, and the uncertainty of every distance is of the order of kilometres ([the orbital conventions](../risk/orbital-conventions.md), Section 5).

| Field | Meaning |
| --- | --- |
| `run_id` | The run's `window_start` as written there, a slash, and `rules_version`, for example `2026-09-29T05:20:09Z/1` |
| `window_start`, `window_end` | The screening window: from the `fetched_at` of the newest `raw.gp` event among the element sets the run used, to 7 days later |
| `input_fetched_at` | That same `fetched_at`, equal to `window_start`, stated as the data's origin. A run's results are stale 24 hours after `window_start`, and always after `window_end`, under the staleness rule for screening results in [the orbital conventions](../risk/orbital-conventions.md) |
| `report_distance_m` | `5000` |
| `coverage` | `watchlist_accepted`, `catalog_admitted`, `pairs`, `pairs_not_screenable`, `pairs_removed_by_prefilter`, `pairs_searched`. Every pair is exactly one of not screenable, suppressed, removed by the prefilter, or searched, so the suppressed count is the length of `suppressed` and `pairs` is `pairs_not_screenable` plus that length plus `pairs_removed_by_prefilter` plus `pairs_searched` |
| `approach_count`, `approach_event_ids` | How many `close_approach` events the run published, and the `event_id` of each |
| `suppressed` | Pairs not screened for close approaches: `watchlist_number`, `other_number`, `mechanism`, `detail`, `min_separation_m`, `min_separation_at`, `max_separation_m`, `stack_entry_may_be_stale`. The separations are sampled, so the true minimum can be smaller |
| `rejected` | Objects rejected before any pair was formed, once per role: `catalog_number`, `role`, `code`, `reason` |
| `not_screened` | Admitted objects whose track does not cover the window: `catalog_number`, `role`, `kind`, `reason`, and `screened_until` for `stopped_in_window` only |
| `epoch_after_start` | Objects screened backward from an element set newer than the window start: `catalog_number`, `seconds_after_start` |
| `differing_copies` | Differing input entries for one catalog number: `catalog_number`, `used_name`, `used_epoch`, `dropped_name`, `dropped_epoch`, `dropped_from`, `elements_differ` |

Every list is present, empty when nothing applies. The meaning of each list and count is in [the orbital conventions](../risk/orbital-conventions.md), Sections 3.2 and 3.7. `role` is `watchlist` or `catalog`. The codes are open lists: a consumer shows an unknown value with its `reason` or `detail` instead of rejecting the event. The known values are:

| Field | Known values |
| --- | --- |
| `suppressed[].mechanism` | `static_stack` (both objects are listed in the same station stack), `co_orbiting` (separation stayed under the co-orbiting bound over the whole window), `same_elements` (identical element sets) |
| `rejected[].code` | `deep_space` (a watchlist object propagated with the deep space model), `stale_element_set` (element set older than 10 days at the window start) |
| `not_screened[].kind` | `cannot_propagate` (no usable state in the window), `stopped_before_window`, `stopped_in_window` |

**Which run is current.** Each run replaces the one before it. The current approaches are those of the newest run, by `window_start`, whose summary has arrived and all of whose `approach_event_ids` have been received.

### Key and partitioning

| Event kind | Key | What the ordering gives a consumer |
| --- | --- | --- |
| `space_weather_level` | The scale: `space_weather.G`, `space_weather.R`, or `space_weather.S` | All events of one scale, across its satellites, in the order they were produced: an ended series and the series that replaced it are read in that order, and a revision or restatement is read after the event it corrects |
| `close_approach`, `screening_run` | `run_id` | A run's approaches before its summary, so the summary marks the run as complete |

The same conditions as on the raw topics apply: a fixed partition count, producer idempotence, and one at a time processing per partition. Runs are not ordered against each other by the key.

### Deduplication

Delivery is at least once. `event_id` is built only from the event's own fields, so a retry or a reprocessing run under the same rules writes the same identity, and every consumer drops an identity it has already applied. `<v>` is `rules_version`:

| Event | `event_id` |
| --- | --- |
| `space_weather_level` from a sample (`level_change` or `revision`) | `space_weather_level/<v>/<scale>/<satellite, or - for G>/<time_tag>/<fetched_at>` |
| `space_weather_level`, restatement | `space_weather_level/<v>/R/<satellite>/<time_tag>/<fetched_at>/restated` |
| `space_weather_level`, refresh on arrival | `space_weather_level/<v>/<scale>/<satellite, or - for G>/<time_tag>/<fetched_at>/refresh` |
| `space_weather_level`, refresh by timer | `space_weather_level/<v>/<scale>/<satellite, or - for G>/<time_tag>/<fetched_at>/refresh/<timer_refresh_at>` |
| `space_weather_level`, `no_data` | `space_weather_level/<v>/<scale>/<satellite, or - for G>/no_data/<no_data_reason>/<no_data_since>` |
| `space_weather_level`, `ended` | `space_weather_level/<v>/<scale>/<satellite>/ended/<ended_by_satellite>/<no_data_since>` |
| `close_approach` | `close_approach/<v>/<run_id>/<watchlist catalog number>/<other catalog number>/<time_of_closest_approach>` |
| `screening_run` | `screening_run/<v>/<run_id>` |

`fetched_at` is in the sample identity because a Kp value can be revised and then revised back to a value it had before; only the later fetch time tells those two events apart. A restatement keeps the `time_tag` and `fetched_at` of the sample it restates, because its cause is a later record, so its identity adds `/restated`. A sample is restated at most once, because the pipeline never re-reads a GOES identity it has already processed. A refresh on arrival is identified by the newest record it carries; a timer refresh repeats a record an earlier event carried, so its identity adds the timer tick. Timer refreshes depend on the clock and are not reproducible on replay; every other identity is. A "no data" spell is identified by its reason and `no_data_since`, the time it starts, and an "ended" spell by `ended_by_satellite` and `no_data_since`, so two spells of different cause that start at the same minute stay distinct; a catch up batch can, for example, start a `rejected` spell at the same minute an `age_limit` spell was dated. A series past its age limit never leaves "no data" on a record that is itself still past the limit (scales note, Section 5.3, rule 1 takes precedence), so an `age_limit` spell is never published twice. A pair can have more than one approach in a window, so the time of closest approach is in the approach identity.

### Examples

[`schemas/alerts/examples/`](../../schemas/alerts/examples/) holds one event per case. The values come from committed data, as follows; `rules_version` is 1, and `produced_at` is the time each example was written, since no running service produced them.

| File | Built from |
| --- | --- |
| `valid-g-level.json` | Kp 7.67 for 2024-05-10 15:00 UTC (G4), after 2.67 (none) for 12:00, in the converted storm fixture `risk-engine/src/test/resources/swpc-storms/kp-2024-05-10-to-12.jsonl` |
| `valid-g-none.json` | Kp 3.67 for 2024-05-12 06:00 UTC (none), after 7.0 (G3) for 03:00, same fixture |
| `valid-r-level.json` | GOES-16 0.1-0.8nm flux 1.0624149581417441e-05 W m-2 at 2024-05-10 03:24 UTC (R1), after 9.548e-6 (none) at 03:23, in `goes16-xrays-2024-05-10T03-09.jsonl`. Its `xray_class` `M1.0` follows the scales note, Section 2.4: the shortest decimal of the 32 bit value is 1.062415e-5, which truncates to M1.0 |
| `valid-r-no-data.json` | GOES-18 0.1-0.8nm flux `0.0` at 2026-09-24 08:27 UTC, the first zero of an eclipse zero run, after 8.483e-9 (none) at 08:26, in the recorded subset `risk-engine/src/test/resources/swpc-xrays/goes18-xrays-7-day-eclipse-2026-09-24.json`. The zero is rejected, so the series goes to `no_data` with reason `rejected`. The edge values 08:22 to 08:26 were derived as none on arrival and are restated, so `no_data_since` is 08:22, 5 minutes before the first zero (scales note, Section 5.2); no edge value is at a level, so nothing moves it later. `freshness_reference` is 08:27, the zero's own `time_tag`, since rejected values and zeros count for it (Section 5.3) |
| `valid-r-restatement.json` | The restatement of the GOES-18 edge sample at 2026-09-24 08:26 UTC, derived below |
| `valid-s-level.json` | GOES-13 >=10 MeV flux 12.344 pfu at 2017-09-10 16:45 UTC (S1), after 8.0004 (none) at 16:40, in `goes13-protons-2017-09-10T16-22.jsonl`. GOES-13 is before GOES-16, which does not matter for S |
| `valid-close-approach.json` | The recorded crossing of OBJECT AJ (57036, watchlist) and SL-12 DEB (27958), both captured at 2026-09-29T05:20:09Z, so the window starts then. TCA 2026-09-30T03:34:37.588Z, miss 1973.3 m, relative speed 15.727 km/s (written as 15727 m/s), at the precision the cross check prints in the orbital conventions, Section 3.6. Element ages are the TCA minus each recorded `EPOCH`, to four decimals |
| `valid-screening-run.json` | The summary of the same run: one watchlist object, both objects admitted as catalog objects, one pair, searched, one approach, and every list empty |

**One off verification, not rerun by the build:** on 2026-09-30 I ran the screening code once on the committed crossing fixtures with this window start, from a throwaway test that is not committed. It gave the same approach as the committed cross check, which starts the window at 2026-09-28T15:00:00Z, and the coverage and empty lists above.

The storm fixtures are converted from NOAA archives, not recorded from SWPC; their provenance, including why `fetched_at` and `source_url` point at the archive files, is in `risk-engine/src/test/resources/swpc-storms/PROVENANCE.md`. Read against the clock, these 2017 and 2024 values are far past every age limit, so a live consumer would show each of these series as "no data" right after the event. There is no example of `ended` or of a Kp revision, because no committed data contains one, and none of a refresh, which needs a running series rather than a single record. `valid-r-restatement.json` comes from the same eclipse subset, replayed as if live in 3 minute batches starting at its first record (07:27), which is how often SWPC regenerated the file when observed (scales note, Section 5.3). The batches ending 08:23 and 08:26 each publish a refresh on arrival carrying their newest record, so both of those edge samples had an event of its own; when the batch 08:27 to 08:29 brings the first zero, both are restated. The example is the restatement of 08:26: `value` is the fixture's 8.483063140829472e-09, `restated_by_time_tag` is 08:27, `freshness_reference` is 08:29, the newest record of that batch, and `fetched_at` and `source_url` follow the subset's own convention (`2026-09-30T19:18:36Z` and the 7 day file URL, from `risk-engine/tools/swpc-xrays/eclipse_subset.py`), so every record shares one fetch time, which a live run would not. With 5 minute batches from 07:27 only 08:26 would be restated. Replayed as archive data against the real clock, the series would be past its age limit and publish no refresh, so nothing would be restated.

## `alerts.dlq`

Uses the shared dead letter envelope, [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json), with `source_topic` set to `alerts`. It receives `alerts` events that fail validation at the producer or at a consumer, with `stage` `validate` and `check` `schema`, and events the broker rejects, with `stage` `publish`. The payload is the single event that failed, and the key is that event's key. There is no `source_url`, because no provider request is involved.

## Retention

Retention for every topic is to be decided with measurements of topic size and of how far back a reprocessing run needs to reach.

## Schema evolution

Per [ADR 0002](../adr/0002-event-schemas-and-serialization.md):

1. Every event carries `schema_version`, the major version of the file it was written against.
2. Within a major version the only allowed change is a new optional field. Consumers ignore fields they do not know, which is why every schema allows additional properties.
3. Any other change (removing or renaming a field, changing a type, making a field required, tightening a range or pattern, adding a value to an enumeration) is a new file, for example `schemas/raw.gp/v2.schema.json`, with `schema_version` set to `2`. Consumers accept both versions until the old one is retired. Adding a fifth SWPC product ID to the `product` enumeration of `raw.swpc` is such a change.
4. Compatibility between the committed file and its previous revision is to be checked in CI; there is no CI pipeline yet, so until then it is checked in review.
