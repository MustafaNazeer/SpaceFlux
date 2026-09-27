# Kafka topics

This page lists every Kafka topic SpaceFlux uses so far, with its key, its value schema, and how consumers should treat it. Schemas are JSON Schema draft 2020-12 files under `schemas/`, one file per topic and major version, as decided in [ADR 0002](../adr/0002-event-schemas-and-serialization.md). The feed facts behind the `raw.gp` contract are in [the CelesTrak source note](../source/celestrak.md), and those behind `raw.swpc` are in [the SWPC source note](../source/swpc.md).

## Summary

| Topic | Producer | Key | Value schema | Retention |
| --- | --- | --- | --- | --- |
| `raw.gp` | `ingest` | `NORAD_CAT_ID` as a decimal string, for example `25544` | [`schemas/raw.gp/v1.schema.json`](../../schemas/raw.gp/v1.schema.json) | To be decided with measurements |
| `raw.gp.dlq` | `ingest`, and any consumer of `raw.gp` | The source event's key when known, otherwise no key | [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json) | To be decided with measurements |
| `raw.swpc` | `ingest` | The product ID, for example `swpc.kp` | [`schemas/raw.swpc/v1.schema.json`](../../schemas/raw.swpc/v1.schema.json) | To be decided with measurements |
| `raw.swpc.dlq` | `ingest`, and any consumer of `raw.swpc` | The product ID | [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json) | To be decided with measurements |

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
| `swpc.kp` | `time_tag` (string), `Kp` (number) | `time_tag` is the identity and the time of the value; `Kp` is what the G scale is read from | `a_running` (integer), `station_count` (integer) |
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

Each file is a sliding window over recent data. In the recorded responses the windows were about 7.5 days for Kp, 6 hours for X-rays and protons, and about 30 days for alerts; SWPC does not document the window lengths. Consecutive responses therefore mostly repeat each other. `ingest` publishes a record only when its identity was not in the previous successful response for the same product, which publishes the records that entered the window and nothing else. A 304 response publishes nothing. After a restart there is no previous response, so the first response is published in full. Every consumer drops identities it has already processed, which is what makes the delivery safe.

### What SWPC does not document

Consumers need to know these points, none of which SWPC states:

* **Time zone of the Kp `time_tag`.** The value has no zone designator, and neither the file nor the product page gives one. It is unconfirmed.
* **Time zone of the alert `issue_datetime`.** The value has no zone designator either, but each message's `Issue Time:` line states UTC, and in all 68 recorded alerts it agrees with `issue_datetime` to the minute.
* **Kp revisions.** SWPC calls these values estimated and does not say whether a published value is ever revised. Because the identity is `time_tag` alone, a revised value for a `time_tag` already published is not republished.
* **`flux` versus `observed_flux`.** The X-ray product page does not explain how the two differ, what `electron_correction` is, or which value the R scale is read from. In the recorded response, `electron_contaminaton` is `true` in 357 of the 358 `0.05-0.4nm` records and `false` in every `0.1-0.8nm` record, and three `0.05-0.4nm` records have a `flux` of `9.999999717180685e-10`, which looks like a floor value but is undocumented.
* **Gaps are normal.** SWPC notes GOES data dropouts during instrument calibrations and satellite eclipses, and posted a GOES and solar wind outage on 2026-09-22. A missing minute is not a parse failure.
* **Alert text is untrusted input** to anything downstream.

### Examples

[`schemas/raw.swpc/examples/`](../../schemas/raw.swpc/examples/) holds one event per product: `valid-kp.json`, `valid-goes-xrays.json`, `valid-goes-protons.json`, and `valid-alert.json`. Each `record` is the first record of a recorded SWPC response, byte for byte, with `fetched_at` set to that response's capture time.

## `raw.swpc.dlq`

Uses the shared dead letter envelope, [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json), unchanged, with `source_topic` set to `raw.swpc` and `source_url` set to the product file. What goes in the payload at each stage is the same as for `raw.gp.dlq`.

The key is the product ID for every dead letter. Each payload comes from polling exactly one product, so unlike `raw.gp.dlq` the key is known even when a body cannot be decoded.

An empty list is dead lettered for `swpc.kp`, `swpc.goes.xrays`, and `swpc.goes.protons`, where it means the feed broke, and accepted silently for `swpc.alerts`, where a quiet period can legitimately leave no alerts; accepting it does not reset deduplication. A body that is JSON `null` is dead lettered for every product. Because each file is a sliding window, a bad record is dead lettered once while it stays in the window, and an undecodable body once while the same body keeps repeating (it is dead lettered again if it returns after a good response). Both feeds' processors dead letter a record that is not valid UTF-8, since a strict JSON reader would reject it.

## Retention

Retention for all four topics is to be decided with measurements of topic size and of how far back a reprocessing run needs to reach.

## Schema evolution

Per [ADR 0002](../adr/0002-event-schemas-and-serialization.md):

1. Every event carries `schema_version`, the major version of the file it was written against.
2. Within a major version the only allowed change is a new optional field. Consumers ignore fields they do not know, which is why every schema allows additional properties.
3. Any other change (removing or renaming a field, changing a type, making a field required, tightening a range or pattern, adding a value to an enumeration) is a new file, for example `schemas/raw.gp/v2.schema.json`, with `schema_version` set to `2`. Consumers accept both versions until the old one is retired. Adding a fifth SWPC product ID to the `product` enumeration of `raw.swpc` is such a change.
4. Compatibility between the committed file and its previous revision is to be checked in CI; there is no CI pipeline yet, so until then it is checked in review.
