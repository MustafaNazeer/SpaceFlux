# NOAA SWPC fixtures, provenance

These fixtures are public data from the NOAA Space Weather Prediction Center (SWPC) data service at `services.swpc.noaa.gov`. I captured each one with a single HTTP GET and saved the response body byte for byte, unmodified. The response headers from the same request sit next to each body in a `.headers.txt` file. The headers are verbatim except the `x-amz-cf-pop` and `x-amz-cf-id` values, which I replaced with `REDACTED` because the edge location code reveals roughly where the capture was made. The JSON bodies are unmodified. SWPC's product documentation, endpoint changes, and usage guidance are summarized with links in [docs/source/swpc.md](../../../docs/source/swpc.md).

Capture tool: `curl -sS -D <name>.headers.txt -o <name>.json` with the User-Agent `SpaceFlux-fixture-capture (https://github.com/MustafaNazeer/SpaceFlux)`.

Capture time below is the server's `Date` response header, which is the authoritative record. My local clock agreed with it to the second.

## Recorded captures

All four bodies came back with HTTP/2 200, `Content-Type: application/json`, and `Cache-Control: max-age=60`, served through CloudFront (`x-cache: Hit from cloudfront`). Each body is a single line JSON array with no trailing newline, and every byte is ASCII.

### `kp.json`

| Field | Value |
| --- | --- |
| Source URL | https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json |
| Captured (UTC) | 2026-09-27T16:30:37Z |
| HTTP status | 200 |
| Content-Type | `application/json` |
| Size | 4719 bytes |
| SHA256 | `90b9f3e4bf9a024a2ef68b5a535957041bdf1732c76710c8617a7dfa248126ca` |
| Records | 61 |
| time_tag range | 2026-09-20T00:00:00 to 2026-09-27T12:00:00 |
| ETag | `"126f-65c79708c4f64"` |
| Last-Modified | Sun, 27 Sep 2026 16:28:29 GMT |
| Headers | `kp.headers.txt` |
| Conditional request | `If-None-Match: "126f-65c79708c4f64"` at 16:30:44Z returned 304 with an empty body (`kp.conditional.headers.txt`) |

### `goes-xrays-6-hour.json`

| Field | Value |
| --- | --- |
| Source URL | https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json |
| Captured (UTC) | 2026-09-27T16:30:37Z |
| HTTP status | 200 |
| Content-Type | `application/json` |
| Size | 163056 bytes |
| SHA256 | `4bf38599b9e4614d939f4de4497d0bf6486e16a6107c643b0ac39dcfcecf103c` |
| Records | 716 (358 time tags, two energy bands each) |
| time_tag range | 2026-09-27T10:29:00Z to 2026-09-27T16:26:00Z |
| ETag | `"27cf0-65c796fd5690e"` |
| Last-Modified | Sun, 27 Sep 2026 16:28:17 GMT |
| Headers | `goes-xrays-6-hour.headers.txt` |
| Conditional request | `If-None-Match: "27cf0-65c796fd5690e"` at 16:30:45Z returned 304 with an empty body (`goes-xrays-6-hour.conditional.headers.txt`) |

### `goes-integral-protons-6-hour.json`

| Field | Value |
| --- | --- |
| Source URL | https://services.swpc.noaa.gov/json/goes/primary/integral-protons-6-hour.json |
| Captured (UTC) | 2026-09-27T16:30:37Z |
| HTTP status | 200 |
| Content-Type | `application/json` |
| Size | 59956 bytes |
| SHA256 | `984fd1a2c7ce021b9aa60d2b7a25fb494a59e332ac45cbbc35460b1bcc0fffa4` |
| Records | 568 (71 time tags, eight energy thresholds each) |
| time_tag range | 2026-09-27T10:30:00Z to 2026-09-27T16:20:00Z |
| ETag | `"ea34-65c796ee76656"` |
| Last-Modified | Sun, 27 Sep 2026 16:28:01 GMT |
| Headers | `goes-integral-protons-6-hour.headers.txt` |
| Conditional request | `If-None-Match: "ea34-65c796ee76656"` at 16:30:45Z returned 304 with an empty body (`goes-integral-protons-6-hour.conditional.headers.txt`) |

### `alerts.json`

| Field | Value |
| --- | --- |
| Source URL | https://services.swpc.noaa.gov/products/alerts.json |
| Captured (UTC) | 2026-09-27T16:30:37Z |
| HTTP status | 200 |
| Content-Type | `application/json` |
| Size | 39125 bytes |
| SHA256 | `790ff684a1619e71b120487086d7414c616e6a3fd42f220f6556028b4dfe1b80` |
| Records | 68 (15 distinct `product_id` values) |
| issue_datetime range | 2026-08-28 20:40:30.257 to 2026-09-27 05:02:04.940 (newest first in the file) |
| ETag | `"98d5-65c79708c7a5c"` |
| Last-Modified | Sun, 27 Sep 2026 16:28:29 GMT |
| Headers | `alerts.headers.txt` |
| Conditional request | `If-None-Match: "98d5-65c79708c7a5c"` at 16:30:45Z returned 304 with an empty body (`alerts.conditional.headers.txt`) |

### Conditional requests

For each product I sent exactly one follow up GET carrying the `ETag` from the first response in `If-None-Match`, about eight seconds after the capture. Every one returned HTTP 304 with no body, the same `ETag`, and `x-cache: Hit from cloudfront`. Only the headers of those responses are kept. What this shows, and what it does not:

* The CloudFront edge honors `If-None-Match` for an unchanged object within its 60 second cache window.
* `If-Modified-Since` was not tested.
* A conditional request after the file changed (expected to return 200 with a new `ETag`) was not tested, and neither was the origin server's behavior behind the cache.

### Observations from the recorded bytes

* No value is `null` in any record of any of the four files, and every record in a file carries the same keys in the same order.
* `kp.json`: keys `time_tag` (string), `Kp` (number, capital K), `a_running` (integer), `station_count` (integer, 8 in every record). `time_tag` has no zone designator and no fractional seconds, and consecutive values are exactly 10800 seconds apart. Kp in this window peaks at 4.33.
* `goes-xrays-6-hour.json`: keys `time_tag` (string, `Z` suffix), `satellite` (integer), `flux`, `observed_flux`, `electron_correction` (numbers, often in exponent notation), `electron_contaminaton` (boolean; the key is spelled this way in the data), `energy` (string). `energy` is `"0.05-0.4nm"` or `"0.1-0.8nm"`, 358 records each. `satellite` is 18 in every record. Time tags are 60 seconds apart with no gaps. `electron_contaminaton` is `true` in 357 of the 358 `"0.05-0.4nm"` records and `false` in every `"0.1-0.8nm"` record. Three `"0.05-0.4nm"` records carry a `flux` of `9.999999717180685e-10`, which looks like a floor value; SWPC does not document it.
* `goes-integral-protons-6-hour.json`: keys `time_tag` (string, `Z` suffix), `satellite` (integer, 18 in every record), `flux` (number), `energy` (string). `energy` takes eight values, 71 records each: `">=1 MeV"`, `">=5 MeV"`, `">=10 MeV"`, `">=30 MeV"`, `">=50 MeV"`, `">=60 MeV"`, `">=100 MeV"`, `">=500 MeV"`. Within one time tag the records are not sorted by threshold (the file ends on `">=60 MeV"`). Time tags are 300 seconds apart with no gaps.
* `alerts.json`: keys `product_id`, `issue_datetime`, `message` (all strings). `issue_datetime` uses a space instead of `T`, has milliseconds, and has no zone designator. `message` contains both `\r\n` and `\n` line breaks. The pair (`product_id`, `issue_datetime`) is unique across the 68 records; `product_id` alone is not.
* The capture window was quiet: no geomagnetic storm, radio blackout, or radiation storm level values are present.

## Derived fixtures (hand edited, not recorded)

The files in `derived/` are NOT recorded responses. Each one is derived from the recorded bytes above with a single, stated edit, so the poller's error paths can be tested on otherwise real data. The recorded originals were not modified.

| File | Derived from | Change | Size | SHA256 |
| --- | --- | --- | --- | --- |
| `derived/kp-kp-string.json` | `kp.json` | In the first record (time_tag 2026-09-20T00:00:00), replaced `"Kp":2.33` with `"Kp":"2.33"` (a JSON string instead of a number). The other 60 records are untouched. Still valid JSON, 61 records. | 4721 bytes | `3e772011b5fc4d258de50b839712781369a6245a3acb865b234a7890184e385d` |
| `derived/kp-missing-time-tag.json` | `kp.json` | Removed the bytes `"time_tag":"2026-09-20T00:00:00",` from the first record. The other 60 records are untouched. Still valid JSON, 61 records. | 4686 bytes | `8816dc19a69380ffb6223110060c10a47b915606eb5e20eeab5cd8f76f01f8ec` |
| `derived/goes-xrays-6-hour-missing-satellite.json` | `goes-xrays-6-hour.json` | Removed the bytes `"satellite": 18, ` (including the trailing space) from the first record (time_tag 2026-09-27T10:29:00Z, energy `"0.05-0.4nm"`). The other 715 records are untouched. Still valid JSON, 716 records. | 163039 bytes | `1f8c1fdf65bbad99508167b9efff3bdf2285e6380dbb7a1f82e81557f8693252` |
| `derived/alerts-truncated.json` | `alerts.json` | Kept only the first 200 bytes, which cuts the body inside the first record's `message` string. Not valid JSON. | 200 bytes | `48b863ca3de561f9bd3e49d172f593e09f1395288693225472fa9bd3ccc597ec` |
| `derived/swpc-empty-array.json` | none | The two bytes `[]` with no trailing newline. Hand authored to exercise the empty result path; I have not observed SWPC returning this exact body. | 2 bytes | `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945` |

## Verifying

From this directory:

```
sha256sum kp.json goes-xrays-6-hour.json goes-integral-protons-6-hour.json alerts.json derived/*.json
```
