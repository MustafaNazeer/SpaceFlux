# Storm period fixtures, provenance

These fixtures give the storm rules real data at and above the NOAA scale thresholds, one period per scale. The recorded live SWPC fixtures in [ingest/testdata/swpc](../../../../../ingest/testdata/swpc/PROVENANCE.md) all come from a quiet day, and the live SWPC files keep only days of history, so these periods come from NOAA archives instead.

**The three `.jsonl` files are converted, not recorded.** SWPC never served these bytes. The script [`risk-engine/tools/swpc-storms/convert.py`](../../../../tools/swpc-storms/convert.py) reads archive files published by NOAA SWPC and NOAA NCEI, rewrites each value into the shape of a `raw.swpc` event (the envelope and record shapes in [schemas/raw.swpc/v1.schema.json](../../../../../schemas/raw.swpc/v1.schema.json)), and validates every event against that schema. The archive files it reads are kept unmodified in `archive/`. The numbers are NOAA's; the JSON layout is mine. Nothing here is official NOAA output, and none of it may be presented as such.

The thresholds and field choices these fixtures exercise are documented in [docs/risk/space-weather-scales.md](../../../../../docs/risk/space-weather-scales.md).

## Summary

| Fixture | Scale | Period (UTC) | Source | Events | Highest level in the file | Evidence that NOAA put the period at that level |
| --- | --- | --- | --- | --- | --- | --- |
| `kp-2024-05-10-to-12.jsonl` | G | 2024-05-10 00:00 to 2024-05-13 00:00, 24 intervals of 3 hours | SWPC Daily Space Weather Indices, archived at NCEI | 24 | G5 (Kp 9.00) | SWPC alerts ALTK09 serials 6, 7, 8 ("NOAA Scale: G5 - Extreme") |
| `goes16-xrays-2024-05-10T03-09.jsonl` | R | 2024-05-10 03:00 to 08:59, 1 minute values | GOES-16 XRS L2 1 minute averages (operational), NCEI | 720 | R3 (3.979e-4 W m⁻², X3.9) | SWPC summary SUMX01 serial 150 ("X-ray Class: X3.9", "NOAA Scale: R3 - Strong") |
| `goes13-protons-2017-09-10T16-22.jsonl` | S | 2017-09-10 16:00 to 21:55, 5 minute values | GOES-13 EPEAD corrected integral protons, NCEI | 432 | S3 (1038.9 pfu) | SWPC alert ALTPX3 serial 29 and summary SUMPX2 serial 42 ("NOAA Scale: S3 - Strong") |

Each fixture also holds values below level 1, so the "none" result is exercised on the same data.

## Output format

Each `.jsonl` file holds one `raw.swpc` event per line: compact JSON, ASCII only, a newline after every line including the last. Key order is `schema_version`, `source`, `product`, `fetched_at`, `source_url`, `record`.

* `source` is `swpc` and `product` is the product whose record shape the event copies (`swpc.kp`, `swpc.goes.xrays`, `swpc.goes.protons`), because the risk engine selects the record schema by `product`. For the GOES-13 file the data originated at SWPC but was archived by NCEI; see the proton section.
* `source_url` is the archive file the value came from, not an SWPC product URL.
* `fetched_at` is the `Date` header of the response that delivered that archive file, not the time the value was measured.
* Only fields that exist in the archive are written. Every record omits some optional fields that the live products carry; the lists are in each section below.

## How to rerun the conversion

The script and its `requirements.txt` live in `risk-engine/tools/swpc-storms/`, outside the test resources, so only data sits in this folder. From `risk-engine/tools/swpc-storms/`, with Python 3 (3.13.7 was used):

```
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python convert.py
rm -rf .venv
```

The script derives every path from its own location: the data folder is `src/test/resources/swpc-storms/` of the module that holds its `tools/` folder (it reads `archive/` there and writes the `.jsonl` files there), and finds `schemas/raw.swpc/v1.schema.json` by walking up from its folder, so it works from any current directory and keeps working if the repository moves. It uses no network. It rewrites the three `.jsonl` files and prints their sizes and SHA256 hashes, which must match the table below; running it twice gives identical bytes. It exits with an error if any event fails the schema. Versions used: jsonschema 4.26.0, netCDF4 1.7.4, numpy 2.5.3 (pinned in `requirements.txt`).

To fetch the archive files again, run from `archive/`, one at a time with a pause between requests:

```
curl -sS -A "SpaceFlux-fixture-capture (https://github.com/MustafaNazeer/SpaceFlux)" -D <name>.headers.txt -o <name> <URL>
```

The GOES-13 CSV is kept gzipped (see the archive table); after fetching it again, compress it with `gzip -9 -n` and compare the SHA256 of the uncompressed file.

The two alert archives in `evidence/` come from SWPC's anonymous FTP server with `curl -sS -o <name> ftp://ftp.swpc.noaa.gov/pub/alerts/<name>`. FTP returns no headers.

## Files and hashes

All archive and evidence files were captured on 2026-09-27 between 22:05:05Z and 22:06:21Z, one request each, spaced about 5 seconds apart, with the User-Agent above. Every HTTPS request returned 200. Capture times are the server's `Date` header, saved verbatim in the `.headers.txt` file next to each file; the FTP times are from my clock, which `timedatectl` reported as synchronized.

### Converted fixtures

| File | Size (bytes) | SHA256 |
| --- | --- | --- |
| `kp-2024-05-10-to-12.jsonl` | 6785 | `3eeb48fd612baa4116239d7d0a7f18500724583cce367e4e5f1df63d7b26019b` |
| `goes16-xrays-2024-05-10T03-09.jsonl` | 331726 | `745b622cc2d198408bfa8edcba063923fd42a6b9659ebd7fc734cbd4392c7e4a` |
| `goes13-protons-2017-09-10T16-22.jsonl` | 149457 | `84413816344d39dd6510a16072902d59245bf802a01acd14ca05cd12c788fac7` |

### Archive files (inputs to `convert.py`, unmodified except the gzip noted below)

| File | Source URL | Captured (UTC) | Last-Modified | Size (bytes) | SHA256 |
| --- | --- | --- | --- | --- | --- |
| `archive/20240510dayind.txt` | https://www.ngdc.noaa.gov/stp/space-weather/swpc-products/daily_reports/space_weather_indices/2024/05/20240510dayind.txt | 2026-09-27T22:05:05Z | Sat, 11 May 2024 18:16:20 GMT | 2889 | `615d33308bee5b16df8a52c8c90871bd81ccc45c8b2749343bd6ddd3093edbfb` |
| `archive/20240511dayind.txt` | https://www.ngdc.noaa.gov/stp/space-weather/swpc-products/daily_reports/space_weather_indices/2024/05/20240511dayind.txt | 2026-09-27T22:05:10Z | Sun, 12 May 2024 18:16:19 GMT | 2889 | `a00b7490d5b5061979d95b880d5fa435b51017b561d8b9e5fa933d675e0a02e4` |
| `archive/20240512dayind.txt` | https://www.ngdc.noaa.gov/stp/space-weather/swpc-products/daily_reports/space_weather_indices/2024/05/20240512dayind.txt | 2026-09-27T22:05:15Z | Mon, 13 May 2024 18:16:15 GMT | 2889 | `1c4a1c9489173a352a6314badff21e6fee3cf39d7e4b6fb6c4ee8cb0d22ca37b` |
| `archive/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc` | https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/data/xrsf-l2-avg1m/2024/05/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc | 2026-09-27T22:05:20Z | Thu, 04 Dec 2025 19:07:03 GMT | 302356 | `8a79071bf6d38e4e726522505195b49ee249c8eb9461d4c3d89fb1ca0afad063` |
| `archive/g13_epead_cpflux_5m_20170901_20170930.csv.gz` | https://www.ncei.noaa.gov/data/goes-space-environment-monitor/access/avg/2017/09/goes13/csv/g13_epead_cpflux_5m_20170901_20170930.csv | 2026-09-27T22:05:26Z | Sun, 01 Oct 2017 10:19:15 GMT | original CSV 3185412, `.gz` 560749 | original CSV `545872247b0ee1f0141e55541ae3788f37a180d7777ae4701425642132625d2c`, `.gz` `152bb1326def621f94b9913db6c4fcadd872ff7b7abc368a2f75c3802629c4b9` |

The GOES-13 CSV is stored gzipped to keep the repository small: the response body was compressed with `gzip -9 -n` (gzip 1.13; `-n` leaves out the name and time stamp, so the same input gives the same `.gz`), and the plain CSV was not kept. Decompressing gives the original bytes back, and `convert.py` reads the `.gz` directly. Its headers file keeps the original name, `g13_epead_cpflux_5m_20170901_20170930.csv.headers.txt`, because it describes the response for the uncompressed file.

### Evidence files (not read by `convert.py`, unmodified)

| File | Source URL | Captured (UTC) | Size (bytes) | SHA256 |
| --- | --- | --- | --- | --- |
| `evidence/archive_20240501.html` | ftp://ftp.swpc.noaa.gov/pub/alerts/archive_20240501.html (SWPC alerts issued May 1 to 16, 2024; FTP listing date May 16 2024) | 2026-09-27T22:05:32Z | 186994 | `b99320eedda8d120808696234b6350127d9e8db708da3292ff83a52ac7723d3c` |
| `evidence/archive_20170901.html` | ftp://ftp.swpc.noaa.gov/pub/alerts/archive_20170901.html (SWPC alerts issued September 1 to 16, 2017; FTP listing date Sep 16 2017) | 2026-09-27T22:05:38Z | 147219 | `8b0b1147489ab60b4ed95e3ced1c206efbe2f20fffd431d434dd2d30920d2d1a` |
| `evidence/20240510dayevt.txt` | https://www.ngdc.noaa.gov/stp/space-weather/swpc-products/daily_reports/space_weather_event_reports/2024/05/20240510dayevt.txt | 2026-09-27T22:06:21Z | 2387 | `aa14b697252c06a0b33373038e8f920469a78b7b82ebb91dbd5fd1c49ed48274` |

The alert archives are HTML pages whose messages sit in a JavaScript array, with line breaks written as `<br>`. The quotes below are those messages with `<br>` read as a line break, each cut after its `NOAA Scale` line (the rest is the scale explanation link and a list of potential impacts).

Verify from this directory:

```
sha256sum *.jsonl archive/* evidence/*
gunzip -c archive/g13_epead_cpflux_5m_20170901_20170930.csv.gz | sha256sum
```

## Terms

* SWPC products (the daily indices, the event report, the alert archives): SWPC is part of the National Weather Service, and every SWPC page links the NWS disclaimer at https://www.weather.gov/disclaimer, which says (read 2026-09-27): "The information on National Weather Service (NWS) Web pages are in the public domain, unless specifically noted otherwise, and may be used without charge for any lawful purpose so long as you do not: 1) claim it is your own (e.g., by claiming copyright for NWS information -- see below), 2) use it in a manner that implies an endorsement or affiliation with NOAA/NWS, or 3) modify its content and then present it as official government material." The converted fixtures are a format change of that content, which is why this file and the top of each section say plainly that they are converted and not official.
* GOES-16 XRS file: its global attribute `license` reads "These data may be redistributed and used without restriction."
* GOES-13 CSV file: it carries no license statement. Its global attributes name `originating_agency = "DOC/NOAA/NCEP/NWS/SWPC"` and `archiving_agency = "DOC/NOAA/NESDIS/NCEI"`. I found no NCEI page stating terms for this directory; the NWS statement above covers SWPC originated data.

## G scale: planetary Kp, 2024-05-10 to 2024-05-12

**Source.** SWPC's "Daily Space Weather Indices" product (`dayind.txt`), one file per day, archived by NCEI. Each file's `:Geomagnetic_Indices:` section ends with a line of eight "Estimated Planetary" K indices in thirds, written with two decimals, under the column header `03  - 06  - 09  - 12  - 15  - 18  - 21  - 24`. SWPC issued each file the day after the date it covers (for example `:Issued: 2024 May 12 1816 UT` for May 11).

**Conversion.**

* The *n*th value (counting from 0) becomes a record with `time_tag` = the file's date at 00:00 plus 3*n* hours, written `YYYY-MM-DDTHH:MM:SS` without a zone, the form of the live product. The column header labels each interval by its end hour, so the first value is 00 to 03 UTC; this matches the start of interval reading of `time_tag` in the scales note.
* `Kp` is the value as a JSON number (`9.00` becomes `9.0`, `8.67` stays `8.67`).
* `a_running` and `station_count` are not in the archive and are omitted. The daily planetary A in the same line is a daily index, not `a_running`, and is not used.
* A negative value would mean missing and be skipped; none occurs.

**Evidence.** SWPC issues "ALERT: Geomagnetic K-index of N" messages in real time, each naming its synoptic period and NOAA scale. For every interval in this file, the table gives the highest such alert in `evidence/archive_20240501.html` and the level the scales note gives for the archived value (thirds reading, G1 from 4.67, G5 only at 9.00).

| `time_tag` | Kp | Level from the value | Highest K alert for that synoptic period (serial, issue time) |
| --- | --- | --- | --- |
| 2024-05-10T00:00:00 | 3.0 | none | none of K5 or higher |
| 2024-05-10T03:00:00 | 3.0 | none | none of K5 or higher |
| 2024-05-10T06:00:00 | 2.67 | none | none of K5 or higher |
| 2024-05-10T09:00:00 | 2.33 | none | none of K5 or higher |
| 2024-05-10T12:00:00 | 2.67 | none | none of K5 or higher |
| 2024-05-10T15:00:00 | 7.67 | G4 | ALTK08 "K-index of 8, 9-", G4 (31, 2024 May 10 1744 UTC) |
| 2024-05-10T18:00:00 | 8.67 | G4 | ALTK08 "K-index of 8, 9-", G4 (32, 2024 May 10 1904 UTC) |
| 2024-05-10T21:00:00 | 9.0 | G5 | ALTK09 "K-index of 9o", G5 (6, 2024 May 10 2334 UTC) |
| 2024-05-11T00:00:00 | 9.0 | G5 | ALTK09 "K-index of 9o", G5 (7, 2024 May 11 0246 UTC) |
| 2024-05-11T03:00:00 | 8.33 | G4 | ALTK08, G4 (35, 2024 May 11 0338 UTC) |
| 2024-05-11T06:00:00 | 8.67 | G4 | ALTK08, G4 (36, 2024 May 11 0709 UTC) |
| 2024-05-11T09:00:00 | 9.0 | G5 | ALTK09 "K-index of 9o", G5 (8, 2024 May 11 1139 UTC) |
| 2024-05-11T12:00:00 | 8.67 | G4 | ALTK08, G4 (38, 2024 May 11 1240 UTC) |
| 2024-05-11T15:00:00 | 8.33 | G4 | ALTK08, G4 (39, 2024 May 11 1650 UTC) |
| 2024-05-11T18:00:00 | 7.33 | G3 | ALTK07, G3 (152, 2024 May 11 1942 UTC) |
| 2024-05-11T21:00:00 | 7.33 | G3 | ALTK07, G3 (153, 2024 May 11 2144 UTC) |
| 2024-05-12T00:00:00 | 6.67 | G3 | ALTK07, G3 (154, 2024 May 12 0301 UTC) |
| 2024-05-12T03:00:00 | 7.0 | G3 | ALTK07, G3 (155, 2024 May 12 0437 UTC) |
| 2024-05-12T06:00:00 | 3.67 | none | none of K5 or higher |
| 2024-05-12T09:00:00 | 3.67 | none | none of K5 or higher |
| 2024-05-12T12:00:00 | 2.67 | none | none of K5 or higher |
| 2024-05-12T15:00:00 | 2.67 | none | none of K5 or higher |
| 2024-05-12T18:00:00 | 4.0 | none | none of K5 or higher |
| 2024-05-12T21:00:00 | 6.33 | G2 | ALTK06, G2 (569, 2024 May 12 2238 UTC) |

The level from the archived value equals the level of the highest alert in all 24 intervals. Three values bear directly on the thirds reading: 8.67 (9-) got a K8 "8, 9-" alert at G4, not G5, in both 2024-05-10T18:00:00 and 2024-05-11T06:00:00; 9.00 (9o) got G5; and 6.67 (7-) got a K7 alert at G3. The alerts were issued in real time from preliminary values, and the daily file was issued the next day, so agreement here is supporting evidence, not proof that the two values were identical.

Two quoted examples:

```
Space Weather Message Code: ALTK09
Serial Number: 6
Issue Time: 2024 May 10 2334 UTC

ALERT: Geomagnetic K-index of 9o
Synoptic Period: 2100-2400 UTC
Active Warning: Yes
NOAA Scale: G5 - Extreme
```

```
Space Weather Message Code: ALTK08
Serial Number: 32
Issue Time: 2024 May 10 1904 UTC

ALERT: Geomagnetic K-index of 8, 9-
Synoptic Period: 1800-2100 UTC
Active Warning: Yes
NOAA Scale: G4 - Severe
```

Levels present: none (10 intervals), G2 (1), G3 (4), G4 (6), G5 (3). No interval falls at G1; the boundary unit tests cover it.

**Observed, not documented.** The archive holds no SWPC "K-index of 4" alert for 2024-05-12 18:00 to 21:00, although the archived value for that interval is 4.00. The only K4 alert in the three days is for 2024-05-10 15:00 to 18:00.

## R scale: GOES-16 X-rays, 2024-05-10 03:00 to 08:59 UTC

**Source.** NCEI's GOES-R XRS Level 2 1 minute averages for GOES-16, operational version (directory `xrsf-l2-avg1m`, file prefix `dn_`), one netCDF file per day. The NCEI "Readme for GOES-R XRS L2 Data" (15 December 2025, https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/docs/GOES-R_XRS_L2_Data_ReadMe.pdf), section 3, says the operational data are the products "used in operations at SWPC", while the science quality data (`_science`, `sci_`) include later calibrations and fixes. The file's own attributes say it was regenerated on 2025-12-04 (`date_created`) with algorithm version 2.4, so these values are not guaranteed to be byte for byte what SWPC served in real time on 2024-05-10.

**Conversion.** For every minute with `time` in [03:00, 09:00) UTC, two records are written, first the short band and then the long band, the order of the live file:

| Record field | Short band record (`energy` `"0.05-0.4nm"`) | Long band record (`energy` `"0.1-0.8nm"`) |
| --- | --- | --- |
| `time_tag` | `time` (seconds since 2000-01-01 12:00:00 UTC, "Record start time") as `YYYY-MM-DDTHH:MM:SSZ` | same |
| `satellite` | `16`, from the global attribute `platform = "g16"` | same |
| `flux` | `xrsa_flux` | `xrsb_flux` |
| `observed_flux` | `xrsa_flux_observed` | `xrsb_flux_observed` |
| `electron_correction` | `xrsa_flux_electrons` | `xrsb_flux_electrons` |

The netCDF values are 32 bit floats and are written as the 64 bit value of that float, which is also how the recorded live file's values look. `electron_contaminaton` is omitted: the archive has an `electron_correction_flag` with different meanings, and mapping it to the live boolean would be a guess. Values that are masked or not finite would be skipped; none were in the window. Every minute in the window has `xrsb_flag` equal to 0.

The mapping of `flux`, `observed_flux`, and `electron_correction` to the NCEI variables is the one the scales note establishes. The satellite is GOES-16 because every class and time SWPC published for this window matches the GOES-16 values below; SWPC's messages do not name the satellite.

**Evidence.** From `evidence/archive_20240501.html`:

```
Space Weather Message Code: SUMX01
Serial Number: 150
Issue Time: 2024 May 10 0740 UTC

SUMMARY: X-ray Event exceeded X1
Begin Time: 2024 May 10 0627 UTC
Maximum Time: 2024 May 10 0654 UTC
End Time: 2024 May 10 0706 UTC
X-ray Class: X3.9
Optical Class: 3b
Location: S15W36
NOAA Scale: R3 - Strong
```

```
Space Weather Message Code: ALTXMF
Serial Number: 375
Issue Time: 2024 May 10 0644 UTC

ALERT: X-Ray Flux exceeded M5
Threshold Reached: 2024 May 10 0641 UTC
NOAA Scale: R2 - Moderate
```

From `evidence/20240510dayevt.txt` (SWPC Space Weather Event Reports for 2024 May 10; SWPC issues no alert at M1, so the event report is the evidence for R1): the line `0315 0329 0340  3664        M1.4` (begin, maximum, end, region, class) and the line `0627 0654 0706  3664        X3.9 ...`.

What the fixture holds, long band only:

| Check | Fixture value | Matches |
| --- | --- | --- |
| Maximum | 3.978928434662521e-4 at 06:54Z, X3.9 when truncated | SUMX01 150: X3.9, maximum 0654 |
| First minute at or above 5e-5 (R2) | 06:41Z (5.252e-5); 06:40Z is 4.642e-5 | ALTXMF 375: threshold reached 0641 |
| M1.4 event | 1.450580657547107e-5 at 03:29Z, M1.4 when truncated | event report: M1.4, maximum 0329 |
| First R1 minute | 03:24Z (1.062e-5); 03:23Z is 9.548e-6 | |

Long band minutes by level (360 total): none 183, R1 124, R2 22, R3 31. R3 minutes run 06:43Z to 07:13Z. The largest long band `electron_correction` in the window is 2.71e-8 W m⁻².

## S scale: GOES-13 protons, 2017-09-10 16:00 to 21:55 UTC

**Source.** NCEI's archive of GOES-13 EPEAD 5 minute averages, "corrected proton flux" product, one CSV per month (`g13_epead_cpflux_5m_20170901_20170930.csv`). The file starts with a netCDF style metadata header; the data table begins after the line `data:`. GOES-13 sat at 75.2 to 75.5 degrees west during the event (the file's satellite location table).

**Why this period and satellite.** NCEI's GOES-R proton archive (SGPS L2 5 minute averages, checked for GOES-16 on 2026-09-27) holds differential channels and only a >500 MeV integral channel, so it has no ≥10 MeV integral series to convert. Rebuilding one from differential channels would mean reimplementing SWPC's integration, which is not a format conversion. The GOES-13 file has integral channels from >1 to >100 MeV directly, so the S period comes from before the GOES-R era.

**Conversion.** For every row with `time_tag` in [16:00, 22:00) UTC on 2017-09-10, one record per integral channel, in ascending energy order:

| Record field | Value |
| --- | --- |
| `time_tag` | the CSV `time_tag` (`2017-09-10 18:45:00.000`) as `2017-09-10T18:45:00Z` |
| `satellite` | `13`, from the global attribute `satellite_id = "GOES-13"` |
| `flux` | column `ZPGT<n>E`, in p/(cm² s sr) |
| `energy` | `">=<n> MeV"` for n in 1, 5, 10, 30, 50, 60, 100 |

* The archive labels the channels ">1 MeV", ">10 MeV" and so on; the live product labels them `">=1 MeV"`, `">=10 MeV"`. I use the live labels so the S rule selects the same `energy` value it selects live.
* The `E` columns are used, not `W`. The file describes `ZPGT10E` as "Flux of >10 MeV protons from the B detector that faces either East or West depending on the yaw flip of the satellite with a correction applied to remove contaminating particles". The `E` series reproduces every time and value in SWPC's messages below; the `W` series does not (it reads 129.3 at 18:45).
* The >1 MeV column is `-99999.0` (missing, flag 99999) at every time in the window, so those 72 records are skipped. The file then holds 6 records per time tag instead of the live file's 8 (the live file also has >=500 MeV, which GOES-13 did not report). All other quality flags in the window are 0.
* Whether the CSV `time_tag` marks the start or end of the 5 minutes is not stated in the file. It is copied as is.

**Evidence.** From `evidence/archive_20170901.html`:

```
Space Weather Message Code: ALTPX3
Serial Number: 29
Issue Time: 2017 Sep 10 1846 UTC

ALERT: Proton Event 10MeV Integral Flux exceeded 1000pfu
Begin Time: 2017 Sep 10 1840 UTC
NOAA Scale: S3 - Strong
```

```
Space Weather Message Code: SUMPX2
Serial Number: 42
Issue Time: 2017 Sep 13 1810 UTC

SUMMARY: Proton Event 10MeV Integral Flux exceeded 100pfu
Begin Time: 2017 Sep 10 1705 UTC
Maximum Time: 2017 Sep 10 1845 UTC
End Time: 2017 Sep 12 2135 UTC
Maximum 10MeV Flux: 1038 pfu
NOAA Scale: S3 - Strong
```

Also in the archive: ALTPX1 serial 317 ("exceeded 10pfu", begin 2017 Sep 10 1645 UTC, S1) and ALTPX2 serial 61 ("exceeded 100pfu", begin 2017 Sep 10 1705 UTC, S2).

What the fixture holds, `">=10 MeV"` only:

| Check | Fixture value | Matches |
| --- | --- | --- |
| First value at or above 10 (S1) | 12.344 at 16:45Z; 16:40Z is 8.0004 | ALTPX1 317: begin 1645 |
| First value at or above 100 (S2) | 109.88 at 17:05Z; 17:00Z is 40.37 | ALTPX2 61: begin 1705 |
| First value at or above 1000 (S3) | 1018.0 at 18:40Z; 18:35Z is 913.98 | ALTPX3 29: begin 1840 |
| Maximum | 1038.9 at 18:45Z | SUMPX2 42: 1038 pfu at 1845 |

`">=10 MeV"` values by level (72 total): none 9, S1 4, S2 55, S3 4.

**Observed, not documented.** Outside the window, the same CSV column peaks at 1493.5 at 2017-09-11 11:45, above the 1038 pfu maximum that SWPC's summaries SUMPX2 42 and SUMPX1 85 (issued 2017 Sep 15) report for this event. The archive does not say why; the fixture window stops at 21:55 on 2017-09-10 and does not include that value.
