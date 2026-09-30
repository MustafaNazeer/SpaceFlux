# GOES X-ray fixtures, provenance

These fixtures cover the X-ray rules in [docs/risk/space-weather-scales.md](../../../../../docs/risk/space-weather-scales.md): the missing and dimmed values around an eclipse (Section 5), the X-ray class of a value (Section 2.4), and what the files look like when SWPC changes the primary satellite (Section 5.4, outstanding, see the last section).

| File | What it is | Recorded or derived |
| --- | --- | --- |
| `goes18-xrays-7-day-eclipse-2026-09-24.json` | 372 records of SWPC's primary 7 day X-ray file around one eclipse zero run on GOES-18 | recorded bytes, a subset of whole records |
| `goes16-flare-classes-2024-05-10.json` | 12 rows pairing each X-ray class in SWPC's event report for 2024 May 10 with the GOES-16 long band flux at the reported maximum minute | derived by a committed script from two committed files |
| `evidence/xray-flares-7-day.json` | SWPC's primary 7 day flare list, whole file | recorded, unmodified |
| `evidence/instrument-sources.json` | SWPC's list of primary and secondary satellite changes, whole file | recorded, unmodified |

## Capture

All three SWPC files were captured on 2026-09-30, one HTTP GET each, about 5 seconds apart, with:

```
curl -sS -A "SpaceFlux-fixture-capture (https://github.com/MustafaNazeer/SpaceFlux)" -D <name>.headers.txt -o <name>.json <URL>
```

Every response was HTTP/2 200 with `Content-Type: application/json` and `Cache-Control: max-age=60`, served through CloudFront. As with the crossing pair in [../celestrak](../celestrak/PROVENANCE.md), only the header fields in the table below were kept; the headers files are not committed. The capture time is the response's `Date` header. For a response served from the CloudFront cache, `Date` is the time the cached copy was made, so the table also gives `Age`, the seconds the copy had spent in the cache; `Date` plus `Age` is about when I received it. My local clock read 19:19:31Z just after the last request.

| Field | `xrays-7-day.json` | `xray-flares-7-day.json` | `instrument-sources.json` |
| --- | --- | --- | --- |
| Source URL | https://services.swpc.noaa.gov/json/goes/primary/xrays-7-day.json | https://services.swpc.noaa.gov/json/goes/primary/xray-flares-7-day.json | https://services.swpc.noaa.gov/json/goes/instrument-sources.json |
| Request order | 2nd | 3rd | 1st |
| `Date` | Wed, 30 Sep 2026 19:18:36 GMT | Wed, 30 Sep 2026 19:19:31 GMT | Wed, 30 Sep 2026 19:19:20 GMT |
| `Age` | 49 (`x-cache: Hit from cloudfront`) | 3 (`Hit from cloudfront`) | none (`Miss from cloudfront`) |
| `Last-Modified` | Wed, 30 Sep 2026 19:16:57 GMT | Wed, 30 Sep 2026 19:17:07 GMT | Wed, 30 Sep 2026 19:16:47 GMT |
| `ETag` | `"454ede-65cb824979fca"` | `"3568-65cb8252cc2aa"` | `"312-65cb823f1a028"` |
| Size (bytes) | 4542174 | 13672 | 786 |
| SHA256 | `79fa1874e9a2d9db39b4791447ef36721c4a6b872a920491c683b20b3a6219f2` | `f4c6095d2e89f3e2d2c19f1fea5a65e1b248be06900941543c16ce50d25d7a10` | `5bedb9e77f260be4c02c0e36456d1de1e696164d915ae9139efaa889afd23fbe` |
| Committed | no, subset only (below) | yes, whole | yes, whole |

Each body is a single line JSON array with no trailing newline, all ASCII.

## Terms

SWPC is part of the National Weather Service, and every SWPC page links the NWS disclaimer at https://www.weather.gov/disclaimer, which says (read 2026-09-27): "The information on National Weather Service (NWS) Web pages are in the public domain, unless specifically noted otherwise, and may be used without charge for any lawful purpose so long as you do not: 1) claim it is your own (e.g., by claiming copyright for NWS information -- see below), 2) use it in a manner that implies an endorsement or affiliation with NOAA/NWS, or 3) modify its content and then present it as official government material." The subset below keeps SWPC's records unchanged and is labelled as a subset; the flare class table is my own table of NOAA values and is labelled as derived. The GOES-16 netCDF file it reads carries the global attribute `license` "These data may be redistributed and used without restriction" (see [../swpc-storms](../swpc-storms/PROVENANCE.md)).

## Eclipse zero run: `goes18-xrays-7-day-eclipse-2026-09-24.json`

**Why a subset.** The full capture is 4,542,174 bytes and 20,126 records (10,063 minutes, two bands each, 2026-09-23T19:17:00Z to 2026-09-30T19:14:00Z), about 14 times the largest fixture already in this module. One eclipse and its surroundings are enough for the rules, so I committed a subset. The full capture is not in this repository; I keep it privately, unmodified, and it is identified by the size and SHA256 in the table above. The script below refuses any other input.

**Selection rule.** Every record whose `time_tag` is from `2026-09-24T07:27:00Z` to `2026-09-24T10:32:00Z` inclusive, both bands, in file order. That is the zero run on GOES-18 from 08:27:00Z to 09:32:00Z (66 minutes) plus 60 minutes on each side. 60 minutes is well over the 5 minute eclipse edge in Section 5.2 and leaves room if that edge is widened; it also covers the whole dip back to undimmed values on both sides. I chose this run because Section 5.2 quotes its edge values.

**Byte faithful.** Each record's bytes are copied unchanged from the capture: key order, spacing, and number spelling (for example `0.0` and `1.2412084515744937e-07`) are SWPC's. The records are joined into a JSON array with `", "` between them, the separator the capture uses, starting with `[` and ending with `]` and no trailing newline, the same framing as the capture. So the file is a valid SWPC style array that parses to exactly the selected records, but SWPC never served this array as a whole. Size 79976 bytes, SHA256 `86012dd21361c8b30dfd3c5557dd95deeeabba43c4434ba30608e67324cf98f7`.

**What it holds.** 372 records, 186 minutes, every minute present (no hole), `satellite` 18 in every record, same seven keys as the recorded 6 hour file in [ingest/testdata/swpc](../../../../../ingest/testdata/swpc/PROVENANCE.md). Long band (`"0.1-0.8nm"`):

* `flux` and `observed_flux` are both exactly `0.0` in all 66 minutes from 08:27:00Z to 09:32:00Z, and in no other minute. In those 66 minutes `electron_contaminaton` is `true` and `electron_correction` is not zero (in both bands), so `observed_flux` does not equal `flux + electron_correction` there, unlike the rest of the file.
* The dimmed minutes before the run: 08:23Z 6.023e-7, 08:24Z 1.241e-7, 08:25Z 2.743e-8, 08:26Z 8.483e-9 (below the R scale, above the 1e-9 floor, so a valid value by Section 5.1 alone). After it: 09:33Z 1.369e-8, 09:34Z 8.354e-8, 09:35Z 3.830e-7, 09:36Z 5.081e-7.
* Largest `flux` 6.880e-7 (08:22Z), so every valid value in the file is below R1; no value carries a level.

Short band (`"0.05-0.4nm"`): the same 66 minutes are `0.0`; 83 other minutes hold the floor value `9.999999717180685e-10`.

**How to rerun.** From `risk-engine/tools/swpc-xrays/`, with Python 3 (3.13.7 was used) and a fresh capture saved somewhere outside the repository:

```
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python eclipse_subset.py <path to xrays-7-day.json>
rm -rf .venv
```

[`eclipse_subset.py`](../../../../tools/swpc-xrays/eclipse_subset.py) checks the input's SHA256 against the one above, so only the capture recorded here reproduces the file; a new capture of the live URL will not, because the file is regenerated every few minutes with a window that moves forward, and the 2026-09-24 minutes leave that 7 day window during 2026-10-01. It derives its paths from its own location, uses no network, and prints the output size and SHA256, which must match the values above.

**Contract.** The script also wraps each selected record as a `raw.swpc` event (`product` `swpc.goes.xrays`, `fetched_at` `2026-09-30T19:18:36Z`, `source_url` the 7 day URL) and validates it against [schemas/raw.swpc/v1.schema.json](../../../../../schemas/raw.swpc/v1.schema.json): all 372 pass. The record shape is that of the 6 hour file ingest polls (same keys, types, and order). The 7 day file itself is a different product file from the one the schema names for `swpc.goes.xrays` (`json/goes/primary/xrays-6-hour.json`); ingest never polls it, and a test that feeds these records to the risk engine should say it is using 7 day records in the 6 hour shape.

## Flare class values: `goes16-flare-classes-2024-05-10.json`

**Derived, not recorded.** A JSON array, one object per line, with one row for each line of SWPC's event report for 2024 May 10 that carries an X-ray class (12 lines):

| Key | Value |
| --- | --- |
| `time_tag` | the report's maximum minute (second column, `Max`) on 2024-05-10, as `YYYY-MM-DDTHH:MM:SSZ` |
| `satellite` | `16`, from the netCDF global attribute `platform = "g16"` |
| `flux` | `xrsb_flux` at that minute in the netCDF file, a 32 bit float written as its 64 bit value, as in the storm fixture |
| `xrsb_flag` | the file's quality flag for that minute (0 in all 12) |
| `swpc_class` | the X-ray class on the report line: SWPC's own class, the truth value |
| `report_line` | the line number in the report (1 based) |
| `report_text` | that report line, verbatim, trailing spaces included |

Sources, both committed in [../swpc-storms](../swpc-storms/PROVENANCE.md), where their URLs, capture times, and hashes are recorded:

* `swpc-storms/evidence/20240510dayevt.txt`, SWPC Space Weather Event Reports for 2024 May 10 (issued 2024 May 11 0245 UTC), SHA256 `aa14b697252c06a0b33373038e8f920469a78b7b82ebb91dbd5fd1c49ed48274`.
* `swpc-storms/archive/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc`, NCEI GOES-16 XRS L2 1 minute averages, operational, SHA256 `8a79071bf6d38e4e726522505195b49ee249c8eb9461d4c3d89fb1ca0afad063`. Its `time` is the record start time, the same reading the storm fixture uses.

Size 2766 bytes, SHA256 `3634cbe8ed8b49a743eed1cb85f7f220f01b8eccfb16bb2e389ee3c981d5c87b`. The rows, in report order:

| `time_tag` | `flux` | `swpc_class` | Report line |
| --- | --- | --- | --- |
| 2024-05-10T00:13:00Z | 1.38868481371901e-05 | M1.3 | 13 |
| 2024-05-10T03:29:00Z | 1.450580657547107e-05 | M1.4 | 18 |
| 2024-05-10T06:54:00Z | 0.0003978928434662521 | X3.9 | 19 |
| 2024-05-10T10:14:00Z | 2.2947060642763972e-05 | M2.2 | 22 |
| 2024-05-10T14:11:00Z | 5.96866593696177e-05 | M5.9 | 26 |
| 2024-05-10T16:08:00Z | 6.924825356691144e-06 | C6.9 | 27 |
| 2024-05-10T18:32:00Z | 1.1593207091209479e-05 | M1.1 | 31 |
| 2024-05-10T18:48:00Z | 1.7997697796090506e-05 | M1.7 | 32 |
| 2024-05-10T19:05:00Z | 2.022428816417232e-05 | M2.0 | 33 |
| 2024-05-10T19:53:00Z | 1.1805874237325042e-05 | M1.1 | 35 |
| 2024-05-10T20:03:00Z | 1.9490487829898484e-05 | M1.9 | 36 |
| 2024-05-10T21:08:00Z | 3.834960807580501e-05 | M3.8 | 38 |

Notes for tests:

* **One row is below M.** C6.9 at 16:08Z is SWPC's class, but Section 2.4 attaches a class only at R1 and above (M and X), so for that row the expected result of the rule is no class, and its flux is below R1. The other 11 rows are M or X.
* **Check run once.** Taking the shortest decimal that reads back as the same 32 bit float, dividing by the decade base, and truncating to one decimal gives `swpc_class` in 12 of 12 rows; rounding instead gives it in 4 of 12. This matches Section 2.4.
* The 03:29Z and 06:54Z values are identical to the long band records at those minutes in `swpc-storms/goes16-xrays-2024-05-10T03-09.jsonl`.
* NCEI regenerated the netCDF file in December 2025 (its `date_created`), so these are not guaranteed to be the exact values SWPC classified in real time. The event report gives only the class, not the flux.

**Contract.** The rows are a truth table, not `raw.swpc` records: they lack `observed_flux` and `energy`, which the `goesXrays` record schema requires, and the values come from NCEI's netCDF, not from an SWPC JSON file. The full records for 03:29Z and 06:54Z, in the `raw.swpc` shape and schema valid, are in the storm fixture.

**How to rerun.** From `risk-engine/tools/swpc-xrays/`, with Python 3 (3.13.7 was used):

```
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python flare_classes.py
rm -rf .venv
```

No full capture is needed for this one. [`flare_classes.py`](../../../../tools/swpc-xrays/flare_classes.py) reads only the two committed files, checks their SHA256 against the values listed above and stops if either differs, uses no network, derives its paths from its own location, and prints the size and SHA256, which must match the values above; running it twice gives identical bytes. Versions used: netCDF4 1.7.4, numpy 2.5.3, jsonschema 4.26.0 (pinned in `requirements.txt`).

## Evidence files

**`evidence/xray-flares-7-day.json`.** 36 flares, all on GOES-18, `time_tag` 2026-09-25T02:31:00Z to 2026-09-30T02:30:00Z; 34 have `max_class` and `max_xrlong`, 2 have `null` for both. Every `max_class` is B (20) or C (14). Keys per record: `time_tag`, `begin_time`, `begin_class`, `max_time`, `max_class`, `max_xrlong`, `max_ratio`, `max_ratio_time`, `current_int_xrlong`, `end_time`, `end_class`, `satellite`. Checked once on this capture: truncating `max_xrlong` as above gives `max_class` in 34 of 34, rounding in 17 of 34. It shows SWPC's truncation below M; Section 2.4 attaches classes only at M and above, so this file tests the digit rule, not the letters the rule produces. It is not a `raw.swpc` product (the schema's `product` enum has no flare list, and ingest does not poll it).

**`evidence/instrument-sources.json`.** Two entries, newest first. At 2026-09-29T23:53:30Z every instrument's primary became GOES-19 (secondary GOES-18); at 2026-09-30T01:31:14Z `xrays` and `protons` went back to primary GOES-18 (secondary GOES-19) while the other instruments stayed on GOES-19. Not a `raw.swpc` product either.

## Primary switch crossing: outstanding

No fixture yet. At capture the newest switch in `evidence/instrument-sources.json` was at 2026-09-30T01:31:14Z, about 17 hours 48 minutes earlier. That is outside the window of the 6 hour files, and the 7 day primary X-ray file holds `satellite` 18 in all 20,126 records, including 2026-09-29T23:53Z to 2026-09-30T01:31Z, when GOES-19 was primary for X-rays. So neither file shows the switch any more, and what the primary files held while GOES-19 was primary was never captured.

**Capture procedure for the next switch.**

1. Fetch `https://services.swpc.noaa.gov/json/goes/instrument-sources.json`. A switch is an entry whose `xrays` or `protons` `primary` differs from the entry after it (entries are newest first). Its `time_tag` is when it took effect.
2. If that `time_tag` is less than about 5 hours ago, or the switch is in progress, fetch, one request each, about 5 seconds apart, with the `curl` line above: `json/goes/primary/xrays-6-hour.json`, `json/goes/secondary/xrays-6-hour.json`, `json/goes/primary/integral-protons-6-hour.json`, `json/goes/secondary/integral-protons-6-hour.json`, and the four matching `-7-day.json` files, plus `instrument-sources.json` again. Record `Date`, `Age`, `Last-Modified`, `ETag`, size, and SHA256 for each.
3. Recognize the switch in the data files: the primary file's `satellite` differs from the one before the switch, or a single file holds more than one `satellite` value. Record which satellites each file holds and over which `time_tag` span, since Section 5.4 says it is not known whether a primary file carries the new satellite's whole window at once.
4. If possible, fetch the two primary 6 hour files again every 5 minutes (the ingest poll interval) until the switch is at least 6 hours old, so the sequence the ingest poller would have seen is recorded. The files were seen to change every 3 minutes (Section 5.3), so a sequence at the poll interval is what the risk engine receives.
5. Keep the full captures outside the repository. Commit only whole records, in the same way as the eclipse subset above, chosen around the switch time, with the selection rule and a script that reproduces the subset from the recorded SHA256.

SWPC does not announce switches in advance in any file I found, so this capture is opportunistic. Internet Archive captures of the primary files could hold an older switch. I queried the archive's CDX index for captures since 2026-08-01 of four URLs, one query each, finishing at about 19:23Z on 2026-09-30. Three of the queries (`xrays-6-hour.json`, `integral-protons-6-hour.json`, and `instrument-sources.json`) returned the archive's "Temporarily Offline" page instead of a listing. The fourth, `xrays-7-day.json`, returned one capture, made 2026-09-02T15:05:02Z; I did not read it, so whether it shows a switch is not known. The offline pages describe only those three queries at that time; how long the outage lasted is not known.

## Verifying

From this directory:

```
sha256sum *.json evidence/*.json
```
