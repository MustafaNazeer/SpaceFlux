# Kafka topics

This page lists every Kafka topic SpaceFlux uses so far, with its key, its value schema, and how consumers should treat it. Schemas are JSON Schema draft 2020-12 files under `schemas/`, one file per topic and major version, as decided in [ADR 0002](../adr/0002-event-schemas-and-serialization.md). The feed facts behind the `raw.gp` contract are in [the CelesTrak source note](../source/celestrak.md).

## Summary

| Topic | Producer | Key | Value schema | Retention |
| --- | --- | --- | --- | --- |
| `raw.gp` | `ingest` | `NORAD_CAT_ID` as a decimal string, for example `25544` | [`schemas/raw.gp/v1.schema.json`](../../schemas/raw.gp/v1.schema.json) | To be decided with measurements |
| `raw.gp.dlq` | `ingest`, and any consumer of `raw.gp` | The source event's key when known, otherwise no key | [`schemas/dlq/v1.schema.json`](../../schemas/dlq/v1.schema.json) | To be decided with measurements |

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

## Retention

Retention for both topics is to be decided with measurements of topic size and of how far back a reprocessing run needs to reach.

## Schema evolution

Per [ADR 0002](../adr/0002-event-schemas-and-serialization.md):

1. Every event carries `schema_version`, the major version of the file it was written against.
2. Within a major version the only allowed change is a new optional field. Consumers ignore fields they do not know, which is why both schemas allow additional properties.
3. Any other change (removing or renaming a field, changing a type, making a field required, tightening a range or pattern, adding a value to an enumeration) is a new file, for example `schemas/raw.gp/v2.schema.json`, with `schema_version` set to `2`. Consumers accept both versions until the old one is retired.
4. Compatibility between the committed file and its previous revision is checked in CI.
