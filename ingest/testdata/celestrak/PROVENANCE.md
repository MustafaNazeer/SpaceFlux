# CelesTrak GP fixtures, provenance

These fixtures are public CelesTrak GP (general perturbations) element data in CelesTrak's JSON format. I captured each one with a single HTTP request and saved the response body byte for byte, unmodified. The response headers from the same request sit next to each body in a `.headers.txt` file. CelesTrak's data formats, update cadence, and usage policy are summarized with links in [docs/source/celestrak.md](../../../docs/source/celestrak.md).

Capture tool: `curl -sS -D <name>.headers.txt -o <name>.json` with the User-Agent `SpaceFlux-fixture-capture (https://github.com/MustafaNazeer/SpaceFlux)`.

Capture time below is the server's `Date` response header, which is the authoritative record. My local clock read about 9 seconds earlier at the moment each request was sent.

## Recorded captures

### `gp-stations.json`

| Field | Value |
| --- | --- |
| Source URL | https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json |
| Captured (UTC) | 2026-09-27T08:57:39Z |
| HTTP status | 200 |
| Content-Type | `application/json; charset=UTF-8` |
| Size | 9279 bytes |
| SHA256 | `d8cb53b7044135e9ae8e7e613d7d4b15f4db21be6f527f987ffc222fe0463abd` |
| Records | 22 |
| EPOCH range | 2026-09-24T22:05:48.727968 to 2026-09-27T04:33:53.406144 |
| Headers | `gp-stations.headers.txt` |

The group includes the ISS (NORAD 25544) and two objects with six digit catalog numbers (100057 and 100712).

### `gp-catnr-25544.json`

| Field | Value |
| --- | --- |
| Source URL | https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=json |
| Captured (UTC) | 2026-09-27T08:57:45Z |
| HTTP status | 200 |
| Content-Type | `application/json; charset=UTF-8` |
| Size | 421 bytes |
| SHA256 | `7097564cb9924c63a9a180de8fa68b7ac65bc753603ee597be9b8b4a271bb966` |
| Records | 1 (ISS (ZARYA), NORAD 25544) |
| EPOCH range | 2026-09-27T04:10:50.460096 (single record) |
| Headers | `gp-catnr-25544.headers.txt` |

### Observations from the recorded bytes

* Both bodies are a single line JSON array terminated by `\r\n`.
* Every record carries the same 17 keys in the same order, and no value is `null`.
* `EPOCH` is a string with microsecond precision and no time zone designator.
* `MEAN_MOTION_DOT`, `MEAN_MOTION_DDOT` and `BSTAR` sometimes use exponent notation (for example `9.528e-5`).
* `MEAN_MOTION_DDOT` is written as the bare integer `0` in most records and as a decimal in others, so a parser must accept any JSON number there.

## Derived fixtures (hand edited, not recorded)

The files in `derived/` are NOT recorded responses. Each one is derived from the recorded bytes above with a single, stated edit, so the poller's error paths can be tested on otherwise real data. The recorded originals were not modified.

| File | Derived from | Change | Size | SHA256 |
| --- | --- | --- | --- | --- |
| `derived/gp-stations-missing-norad-cat-id.json` | `gp-stations.json` | Removed the bytes `"NORAD_CAT_ID":25544,` from the ISS (ZARYA) record. The other 21 records are untouched. Still valid JSON, 22 records. | 9258 bytes | `5c439cf7cb0de9b2110782e30d8be60634898cb39d973f2ac1b408afb96845fe` |
| `derived/gp-stations-mean-motion-string.json` | `gp-stations.json` | In the ISS (ZARYA) record, replaced `"MEAN_MOTION":15.48664528` with `"MEAN_MOTION":"15.48664528"` (a JSON string instead of a number). The other 21 records are untouched. Still valid JSON, 22 records. | 9281 bytes | `2498a985f0aa546cc23da0e0252c751eba4a18248fad1e1f268b445260b15ba2` |
| `derived/gp-catnr-25544-truncated.json` | `gp-catnr-25544.json` | Kept only the first 200 bytes, which cuts the body inside the `ARG_OF_PERICENTER` key. Not valid JSON. | 200 bytes | `7207cc721b8f619f8134f24363113a72a52977d08f2e54c0917b0a649ac23b02` |
| `derived/gp-empty-array.json` | none | The two bytes `[]` with no trailing newline. Hand authored to exercise the empty result path; I have not observed CelesTrak returning this exact body. | 2 bytes | `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945` |

## Verifying

From this directory:

```
sha256sum gp-stations.json gp-catnr-25544.json derived/*.json
```
