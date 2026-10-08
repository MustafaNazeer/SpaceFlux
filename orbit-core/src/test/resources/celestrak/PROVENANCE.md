# CelesTrak GP fixtures, provenance

`gp-catnr-25544.json` and `gp-stations.json` are byte for byte copies of the recorded captures in [ingest/testdata/celestrak](../../../../../ingest/testdata/celestrak/PROVENANCE.md), which holds their source URLs, capture times, and response headers.

| File | SHA256 |
| --- | --- |
| `gp-catnr-25544.json` | `7097564cb9924c63a9a180de8fa68b7ac65bc753603ee597be9b8b4a271bb966` |
| `gp-stations.json` | `d8cb53b7044135e9ae8e7e613d7d4b15f4db21be6f527f987ffc222fe0463abd` |

## Crossing pair: `gp-catnr-57036.json` and `gp-catnr-27958.json`

Every close approach under 5 km in `gp-stations.json` is between members of the same station stack, so these two captures add an independent crossing pair: OBJECT AJ (NORAD 57036, inclination 97.5 degrees, eccentricity 0.0008) and SL-12 DEB (NORAD 27958, inclination 65.2 degrees, eccentricity 0.42). They are public CelesTrak GP element data in CelesTrak's JSON format, captured the same way as the files above: one HTTP request each, response body saved byte for byte and unmodified. CelesTrak's `CATNR` query returns a single catalog number, so the pair is two files rather than one array. CelesTrak's data formats, update cadence, and usage policy are summarized with links in [docs/source/celestrak.md](../../../../../docs/source/celestrak.md).

Capture tool: `curl -sS -D <name>.headers.txt -o <name>.json` with the User-Agent `SpaceFlux-fixture-capture (https://github.com/MustafaNazeer/SpaceFlux)`. The capture time is the server's `Date` response header. Only the header fields listed below were kept; the headers files are not committed.

| Field | `gp-catnr-57036.json` | `gp-catnr-27958.json` |
| --- | --- | --- |
| Source URL | https://celestrak.org/NORAD/elements/gp.php?CATNR=57036&FORMAT=json | https://celestrak.org/NORAD/elements/gp.php?CATNR=27958&FORMAT=json |
| Captured (UTC) | 2026-09-29T05:20:09Z | 2026-09-29T05:20:09Z |
| HTTP status | 200 | 200 |
| Content-Type | `application/json; charset=UTF-8` | `application/json; charset=UTF-8` |
| Content-Disposition | `filename="57036.json"` | `filename="27958.json"` |
| Size | 422 bytes | 420 bytes |
| SHA256 | `9b8afffc8da9bf84153a3b7586b4c865e70276ed0fca07ee3b80edaf1fe73376` | `cc8a3fa0ccc4c52c008cfb1b7304847d653ae16a465c99b81926b75f0686ae03` |
| Records | 1 (OBJECT AJ, 2023-085AJ) | 1 (SL-12 DEB, 1987-079AX) |
| EPOCH | 2026-09-28T14:10:20.740512 | 2026-09-25T22:48:49.415616 |

Both bodies are a single line JSON array terminated by `\r\n`, with the same 17 keys as the files above.

### Where the pair came from

I picked the pair from CelesTrak SOCRATES Plus, which screens public GP data with SGP4 for approaches within 5 km over seven days. The listing was the 1,000 fastest conjunctions of the run, https://celestrak.org/SOCRATES/table-socrates.php?NAME=,&ORDER=RELSPEED&MAX=1000 (query form from https://celestrak.org/SOCRATES/socrates-format.php), requested at 2026-09-29T05:19:31Z (`Date` header). That page stated "Data current as of 2026 Sep 28 10:17:18 UTC" and a computation interval from 2026 Sep 28 10:00:00 UTC to 2026 Oct 05 10:00:00 UTC. Its row for this pair read:

| NORAD | Name [Ops Status] | Days Since Epoch | TCA (UTC) | Min Range (km) | Relative Speed (km/sec) | Max Probability | Dilution Threshold (km) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 57036 | OBJECT AJ [+] | 2.876 | 2026-09-30 03:34:37.325 | 0.652 | 15.727 | 4.699E-06 | 0.340 |
| 27958 | SL-12 DEB [-] | 4.198 | | | | | |

The SOCRATES page itself is not copied here; the table above records only the values I read from it. The 27958 element set above is the one SOCRATES used (its days since epoch matches this EPOCH). The 57036 element set is newer than the one SOCRATES used, so the approach these files produce differs from the SOCRATES row; the tests state their own figures.
