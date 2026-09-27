# NOAA SWPC JSON products: endpoints, shapes, cadence, and usage guidance

This note records what the NOAA Space Weather Prediction Center (SWPC) publishes about its JSON data products, plus what one fetched sample of each product actually contains, so the SWPC poller in `ingest` is built against verified facts. Every point cites its source. All pages and samples were accessed on 2026-09-27 (samples between about 16:22 and 16:30 UTC). Where SWPC's documentation is silent on a point, this note says so, and anything taken from a sample rather than from documentation is labeled **observed**.

Samples were fetched once each for inspection and were not kept. The recorded fixtures used by the tests are captured separately, with their own provenance.

## Sources

| # | Page | URL | Date on the page |
|---|---|---|---|
| W1 | SWPC Data Access | https://www.spaceweather.gov/content/data-access (redirected from https://www.swpc.noaa.gov/content/data-access) | none shown |
| W2 | Planetary K-index | https://www.spaceweather.gov/products/planetary-k-index | none shown |
| W3 | GOES X-ray Flux | https://www.spaceweather.gov/products/goes-x-ray-flux | none shown |
| W4 | GOES Proton Flux | https://www.spaceweather.gov/products/goes-proton-flux | none shown |
| W5 | Alerts, Watches and Warnings | https://www.spaceweather.gov/products/alerts-watches-and-warnings | none shown |
| W6 | Solar Wind | https://www.spaceweather.gov/products/solar-wind | none shown |
| W7 | NOAA Space Weather Scales (PDF linked from https://www.spaceweather.gov/noaa-scales-explanation) | https://www.spaceweather.gov/sites/default/files/images/NOAAscales.pdf | "December 11, 2023" |
| N1 | NWS Service Change Notice 26-21, "Data Format Changes Impacting Space Weather Prediction Center Products Effective on or about March 31, 2026" | https://www.weather.gov/media/notification/pdf_2026/scn26-21_Data_Format_Changes_Impacting_SWPC_Products.pdf | issued Mar 2 2026 |
| N2 | NWS Service Change Notice 26-66, solar wind display transition to operations | https://www.weather.gov/media/notification/pdf_2026/scn26-66_Display_Product_NOAA_SOLAR-1_and_NASA_IMAP_In-Situ_Instruments_T2O.pdf | issued Jul 14 2026 |
| N3 | SWPC news, "Solar Wind Data and Display Changes" | https://www.spaceweather.gov/news/solar-wind-data-and-display-changes | published Jun 30 2026 21:35 UTC |
| N4 | SWPC news, "Solar Wind and GOES Data Outages" | https://www.spaceweather.gov/news/solar-wind-and-goes-data-outages | published Sep 22 2026 14:46 UTC |
| N5 | NWS Service Change Notices index | https://www.weather.gov/notification/ | live list |
| P1 | NWS Disclaimer, section "Public Notice of Appropriate Use (Defining Abuse)", linked as "Disclaimer" from the footer of every SWPC page above | https://www.weather.gov/disclaimer | none shown |
| D1 | Directory listing of the products tree | https://services.swpc.noaa.gov/products/ | listing timestamps |
| D2 | Directory listing of the json tree | https://services.swpc.noaa.gov/json/ (and its `goes/`, `goes/primary/`, `rtsw/` subdirectories) | listing timestamps |

W1 names the base URLs: "Data Service Base URL: services.swpc.noaa.gov", "Products: services.swpc.noaa.gov/products", and "JSON: services.swpc.noaa.gov/json". Note that `www.swpc.noaa.gov` pages now answer with HTTP 301 to the same path on `www.spaceweather.gov`; the data service itself is still `services.swpc.noaa.gov`.

## Endpoint changes and retirements

These are the published changes that affect the products in this note. N5 is where NWS posts new ones.

1. **Array of arrays became array of objects (N1, effective on or about 2026-03-31).** N1 lists `products/noaa-planetary-k-index.json`, `products/noaa-planetary-k-index-forecast.json`, `products/kyoto-dst.json`, and `products/10cm-flux-30-day.json` as moving "from a format where the first entry contains the keys and the subsequent entries contain the corresponding values, to a standard JSON object format where each object explicitly defines its data using key-value pairs. The data values aside from the 'time_tag' will be numeric, and will no longer be quoted." The same notice says `products/summary/solar-wind-mag-field.json`, `products/summary/solar-wind-speed.json`, and `products/summary/10cm-flux.json` "will now include the outside brackets". **Observed:** the samples fetched on 2026-09-27 are in the new format (arrays of objects with numeric values). Older client code and older write ups that parse a header row are out of date.
2. **`products/solar-wind/` removed (N1, effective on or about 2026-04-30).** N1 deprecates and removes every `products/solar-wind/mag-*.json`, `products/solar-wind/plasma-*.json`, and `products/solar-wind/ephemerides.json` file. Replacements: `json/rtsw/rtsw_wind_1m.json`, `json/rtsw/rtsw_mag_1m.json`, and `json/rtsw/rtsw_ephemerides_1h.json`. **Observed:** `https://services.swpc.noaa.gov/products/solar-wind/`, `.../plasma-7-day.json`, and `.../mag-7-day.json` each return HTTP 404, and D1 no longer lists the directory. N1 field mapping: plasma `density` becomes `proton_density`, `speed` becomes `proton_speed`, `temperature` becomes `proton_temperature`; magnetometer `time_tag`, `bx_gsm`, `by_gsm`, `bz_gsm`, `bt` are unchanged, `lon_gsm` becomes `phi_gsm`, and N1 writes that `lat_gsm` "will map to 'theta_bsm'" (the sample's field is spelled `theta_gsm`). N1 also says the replacements hold fewer timeframes: "3-day and 7-day users must retrieve and retain the 1-day file."
3. **DSCOVR retired as a solar wind source (N3, 2026-06-30; N2).** N3: "the ingest and processing of DSCOVR data has been stopped and the legacy RTSW plot has been removed." N3: "SOLAR-1 now serves as the primary solar wind source with ACE as our backup until IMAP I-ALiRT has 24/7 coverage." N2 moved the new solar wind display to operations "on or about August 13, 2026."
4. **Recent outage (N4, 2026-09-22).** "Real Time Solar Wind (RTSW) and some GOES-18/19 observations are unavailable." The poller has to treat gaps as normal, not as a parse failure. **Observed:** `json/goes/instrument-sources.json` shows the primary X-ray satellite switching between GOES 19 and GOES 18 during 2026-09-22 (its newest entry lists `"xrays": {"primary": 18, "secondary": 19}`).

No notice found in N5 or on the SWPC news archive retires `products/alerts.json`, `products/noaa-planetary-k-index.json`, or the `json/goes/` files. The GEOALERT termination (Service Change Notice 26-39, on or about 2026-05-19) concerns a text report, not these JSON files.

## Candidate products

Sizes are the byte length of the one sample fetched. "Documented cadence" quotes SWPC; "observed" comes from the time tags inside the sample.

### Planetary Kp, 3 hour (G scale)

* **URL:** `https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json`. W2 links it: "The chart JSON is available at: Observed Planetary K Index (3 hour intervals)."
* **Contents (W2):** "The Estimated 3-hour Planetary Kp-index is derived at the NOAA Space Weather Prediction Center using data from the following ground-based magnetometers: Sitka, Alaska; Meanook, Canada; Ottawa, Canada; Fredericksburg, Virginia; Hartland, UK; Wingst, Germany; Niemegk, Germany; and Canberra, Australia."
* **Shape (observed):** array of objects, 61 records, 4,719 bytes.

  | Field | JSON type | Example |
  |---|---|---|
  | `time_tag` | string | `"2026-09-27T12:00:00"` |
  | `Kp` | number | `1.33` |
  | `a_running` | number (integer) | `5` |
  | `station_count` | number (integer) | `8` |

  The field is `Kp` with a capital K here but `kp` in the forecast file, so parsers must match case exactly.
* **Time format (observed):** ISO 8601 without a zone suffix and without fractional seconds. The file does not state the time zone.
* **Window (observed):** 2026-09-20T00:00:00 to 2026-09-27T12:00:00, about 7.5 days. The window is not stated in the documentation.
* **Cadence:** W7: "Kp values ... determined every 3 hours." W2: "This chart updates every minute" (the chart, not the index). **Observed:** consecutive `time_tag` values are exactly 10,800 seconds apart. When a new 3 hour value is appended, and whether a published value is ever revised later, is not stated. W2 calls the index "Estimated".
* **Scale link (W7, W2):** G1 to G5 correspond to Kp = 5, 6, 7, 8 (including 9 minus), and 9. W7 marks the Kp column as "Based on this measure, but other physical measures are also considered."
* **Natural identity:** `time_tag`. **Observed:** unique within the sample.

### Planetary Kp, 1 minute estimate (G scale, faster but undocumented)

* **URL:** `https://services.swpc.noaa.gov/json/planetary_k_index_1m.json` (listed in D2).
* **Documentation:** none found. W2 links only the 3 hour product.
* **Shape (observed):** array of objects, 358 records, 27,925 bytes. Fields: `time_tag` (string, `"2026-09-27T16:18:00"`), `kp_index` (integer, `0`), `estimated_kp` (number, `0.33`), `kp` (string, `"0P"`). What `kp` and `kp_index` mean is not documented; the sample suggests `kp` is the thirds notation of `estimated_kp`, but that is unconfirmed.
* **Window and cadence (observed):** 2026-09-27T10:21:00 to 16:18:00, about 6 hours, with time tags 60 seconds apart.
* **Natural identity:** `time_tag`, unique within the sample.

### Kp forecast (optional context, not a scale input by itself)

* **URL:** `https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json` (named in N1).
* **Shape (observed):** array of objects, 81 records, 6,906 bytes. Fields: `time_tag` (string), `kp` (number), `observed` (string; the sample holds 61 `"observed"`, 3 `"estimated"`, and 17 `"predicted"`), `noaa_scale` (null in every record of the sample).
* **Window (observed):** 2026-09-20T00:00:00 to 2026-09-30T00:00:00. Cadence is not documented.
* **Natural identity:** `time_tag`; a record's `observed` label and value can be expected to change as a prediction becomes an observation, but that behavior is not documented.

### GOES X-ray flux (R scale)

* **URL:** `https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json`. The same directory has `xrays-1-day.json` (634K in D2), `xrays-3-day.json` (1.9M), and `xrays-7-day.json` (4.3M); `json/goes/secondary/` mirrors it for the secondary satellite. W3: "Numerical data are also available directly from SWPC's data service at: https://services.swpc.noaa.gov/json/goes/" and "Observation data are found under the primary and secondary subdirectories."
* **Contents (W3):** "The GOES X-ray flux 6-hour and three-day plots contain 1-minute averages of solar X-rays in the 1-8 Angstrom (0.1-0.8 nm) and 0.5-4.0 Angstrom (0.05-0.4 nm) passbands. Data from the SWPC primary and secondary GOES X-ray satellites is shown." W3: "Some data dropouts occur during instrument calibrations and satellite eclipses."
* **Shape (observed):** array of objects, 716 records, 163,035 bytes; two records per minute, one per band.

  | Field | JSON type | Example |
  |---|---|---|
  | `time_tag` | string | `"2026-09-27T16:17:00Z"` |
  | `satellite` | number (integer) | `18` |
  | `flux` | number | `4.902171895082574e-07` |
  | `observed_flux` | number | `5.404338025982725e-07` |
  | `electron_correction` | number | `5.021660598458766e-08` |
  | `electron_contaminaton` | boolean | `false` (the key is spelled this way in the data) |
  | `energy` | string | `"0.1-0.8nm"` or `"0.05-0.4nm"` |

  What `flux` versus `observed_flux` and `electron_correction` mean is not explained on W3. Which value the scale should be read from needs a ruling before the risk rules use it.
* **Time format (observed):** ISO 8601 with a `Z` suffix.
* **Window and cadence (observed):** 2026-09-27T10:20:00Z to 16:17:00Z, 358 distinct time tags exactly 60 seconds apart, matching W3's "1-minute averages". W3: "The plots on this page update dynamically every minute."
* **Scale link (W7):** the Radio Blackouts column is "GOES X-ray peak brightness by class and by flux", footnoted "Flux, measured in the 0.1-0.8 nm range, in W· m-2." R1 is M1 (10^-5), R2 M5 (5 x 10^-5), R3 X1 (10^-4), R4 X10 (10^-3), R5 X20 (2 x 10^-3). So only the `"0.1-0.8nm"` records feed the R scale.
* **Natural identity:** (`time_tag`, `satellite`, `energy`). **Observed:** unique within the sample; the sample holds only satellite 18. The satellite has to be part of the identity because the primary satellite changes (see N4 and `instrument-sources.json` above).

### GOES flare summary (R scale, event level, optional)

* **URL:** `https://services.swpc.noaa.gov/json/goes/primary/xray-flares-latest.json` (450 bytes); `xray-flares-7-day.json` (14K in D2) holds a week.
* **Contents (W3):** "The latest event is the latest X-ray flare detected by the GOES satellites, either automatically or manually entered if the detection algorithm fails, without regard to any earlier events." W3 defines the begin time as "the first minute, in a sequence of 4 minutes, of steep monotonic increase in 0.1-0.8 nm flux."
* **Shape (observed):** array holding one object with `time_tag`, `satellite`, `current_class`, `current_ratio`, `current_int_xrlong`, `begin_time`, `begin_class`, `max_time`, `max_class`, `max_xrlong`, `end_time`, `max_ratio_time`, `max_ratio`, `end_class` (times as strings with `Z`, classes as strings like `"C1.2"`, the rest numbers).
* **Natural identity:** (`satellite`, `begin_time`) is the likely event identity; it is not documented and cannot be checked from a one record sample.

### GOES integral proton flux (S scale, optional)

* **URL:** `https://services.swpc.noaa.gov/json/goes/primary/integral-protons-6-hour.json`; also `-1-day` (237K), `-3-day` (709K), `-7-day` (1.6M) in D2.
* **Contents (W4):** "GOES 5-minute averaged integral proton fluxes (protons/(cm² s sr)), as observed by the SWPC primary GOES satellite". "In the 'integral-protons' JSON files, integral proton fluxes are reported for energy thresholds of ≥1, ≥5, ≥10, ≥30, ≥50, ≥60, ≥100, and ≥500 MeV."
* **Shape (observed):** array of objects, 568 records, 59,957 bytes; eight records per time tag, one per threshold. Fields: `time_tag` (string with `Z`), `satellite` (integer, `18`), `flux` (number), `energy` (string, for example `">=10 MeV"`).
* **Window and cadence (observed):** 2026-09-27T10:20:00Z to 16:10:00Z, 71 time tags exactly 300 seconds apart, matching W4's "5-minute averaged".
* **Scale link:** W4: "The ≥10 MeV products match the NOAA Solar Radiation Storm (S-scale) thresholds (10, 100, 1000, 10000, 100000 pfu), based upon values observed or expected on the primary GOES satellite." W7 footnotes the S scale "Flux levels are 5 minute averages." Only the `">=10 MeV"` records feed the S scale.
* **Natural identity:** (`time_tag`, `satellite`, `energy`), unique within the sample.

### Solar wind (context; no NOAA scale)

* **URLs:** `https://services.swpc.noaa.gov/json/rtsw/rtsw_wind_1m.json` (2,553,573 bytes, from the `Content-Range` total of a ranged request) and `https://services.swpc.noaa.gov/json/rtsw/rtsw_mag_1m.json` (1,475,398 bytes). Only the first and last 1.5 KB of these were fetched.
* **Contents (W6):** "The last 24 hours of in situ data is available via static JSON files ... There are separate files for mag, plasma and ephemeris, each of which includes the data for all of the available spacecraft." N1 defines the two new fields: `source` "identifies the satellite from which the data originated", and `active` is "a boolean indicating whether the satellite was considered active by SWPC forecasters at the time." W6: SWPC can "instantaneously switch which spacecraft is providing a particular type of RTSW data".
* **Shape (observed, first records only):** array of objects, newest first. The wind file has `time_tag` (string without zone, `"2026-09-27T16:17:04"`), `active` (boolean), `source` (string: `"IMAP"`, `"SOLAR1"`, `"ACE"` seen), `proton_speed`, `proton_temperature`, `proton_density` (numbers), many velocity and alpha fields that were `null`, and quality flags (`max_*_flag`, `overall_quality`, integers). The mag file has `time_tag`, `active`, `source`, `bt`, `bx_gsm`, `by_gsm`, `bz_gsm`, `theta_gsm`, `phi_gsm` and their GSE counterparts, `sample_size`, and quality flags. The oldest wind record seen was 2026-09-26T16:23:00, consistent with the 24 hour window.
* **Cadence:** "1m" in the file name; W6 does not state an update cadence.
* **Natural identity:** (`source`, `time_tag`). Rows from inactive sources must not be read as the live value; the consumer filters on `active`.
* **Small alternatives:** `https://services.swpc.noaa.gov/products/summary/solar-wind-speed.json` (59 bytes, observed `[{"proton_speed": 398, "time_tag": "2026-09-27T16:13:00Z"}]`) and `https://services.swpc.noaa.gov/products/summary/solar-wind-mag-field.json` (60 bytes, observed `[{"bt": 2, "bz_gsm": 1, "time_tag": "2026-09-27T16:13:00Z"}]`). N1 names both. Which spacecraft they draw from, and their update cadence, is not stated.

### Alerts, watches, warnings, and summaries

* **URL:** `https://services.swpc.noaa.gov/products/alerts.json`. W5: "The current Alerts, Watches and Warnings data is available directly from this JSON file".
* **Shape (observed):** array of objects, 68 records, 39,125 bytes, newest first. Fields: `product_id` (string, for example `"K04W"`), `issue_datetime` (string, `"2026-09-27 05:02:04.940"`: a space instead of `T`, milliseconds, no zone), `message` (string, free text with `\r\n` and `\n` line breaks). Each message begins with `Space Weather Message Code:` (for example `WARK04`), `Serial Number:`, and `Issue Time:` in UTC (for example `2026 Sep 27 0502 UTC`, matching `issue_datetime`).
* **Window (observed):** issue times from 2026-08-28 20:40 to 2026-09-27 05:02, about 30 days. Neither the window nor the update cadence is stated on W5.
* **Scale link:** messages name the scale level in their text (W4: "The ≥10 MeV Integral Flux WARNING includes the predicted level of activity based on the NOAA S-scale"; W2: K-index watches are "reported in terms of the NOAA G scale"). The text is untrusted input to anything downstream.
* **Natural identity:** (`product_id`, `issue_datetime`), unique within the sample. `product_id` alone is a message type, not a message: 15 distinct values over 68 records. The serial number is not unique either: the sample holds two `ALTEF3` messages with `Serial Number: 3738`, the later one marked `CORRECTED`, so a correction reuses the serial of the message it replaces.

### NOAA scales summary (optional, all three scales precomputed)

* **URL:** `https://services.swpc.noaa.gov/products/noaa-scales.json` (1,097 bytes).
* **Documentation:** none found for the JSON file itself.
* **Shape (observed):** a single JSON object, not an array, keyed `"-1"`, `"0"`, `"1"`, `"2"`, `"3"`. Each value has `DateStamp` (`"2026-09-27"`), `TimeStamp` (`"16:20:00"`), and `R`, `S`, `G` objects whose `Scale` and probability fields are strings or null (for example `"Scale": "0", "Text": "none"`). From the dates, `"-1"` looks like the previous day, `"0"` the current conditions, and `"1"` to `"3"` forecast days, but that reading is not documented.
* **Natural identity:** not applicable per record; the whole object is a snapshot.

## HTTP behavior (observed)

Every response from `services.swpc.noaa.gov` came through CloudFront with `cache-control: max-age=60`, a `last-modified` header, an `etag` header, `vary: Accept-Encoding`, and `access-control-allow-origin: *`. Byte ranges are supported (`accept-ranges: bytes`, HTTP 206 on a ranged request). The D1 and D2 listings show most files regenerated within the same minute. Nothing in the documentation mentions conditional requests. On 2026-09-27 a request with `If-None-Match` set to each product's `ETag` returned HTTP 304 with an empty body for all four products used here (recorded in `ingest/testdata/swpc/PROVENANCE.md`); that was answered by the CloudFront edge for unchanged files, and `If-Modified-Since` was not tested. In practice the files are regenerated about once a minute: in a live run the same day, every one of 8 polls across the four products at a 5 minute interval returned HTTP 200, so conditional requests save bandwidth here only when a file is polled again within the same minute. No `User-Agent` requirement is stated. Because of the 60 second cache, polling any file more often than once a minute cannot return newer data.

## Usage policy, rate limits, and blocking

SWPC's own pages (W1 to W6) publish no usage policy, rate limit, or blocking rule for `services.swpc.noaa.gov`. Every SWPC page links "Disclaimer" to P1, whose "Public Notice of Appropriate Use (Defining Abuse)" says it gives "guidelines for the use of all NWS websites" (SWPC is a center of the NWS). From P1:

* Blocking: "to protect our resources and our service level to the greater user community we may find it necessary to block IP addresses or query types. Users who find that their queries are blocked should send an email to sdm@noaa.gov." No threshold is published: "We are constantly monitoring those IT limits and adjusting thresholds that define the abusive usage policy."
* Cadence: "Know your data refresh frequency ... Knowing the frequency of the data update cycle will help you to create sensible request cycles." and "If you are querying a service for short fused warnings it may make sense to query our service once or twice a minute based upon your needs."
* Scope: "Request only the data that you need".
* Errors: "If a Web Service isn't available users should limit the number of retries to 1 minute intervals. If a file is not available do not immediately retry the following second, pause for an appropriate interval based on your data refresh rate."
* Maintenance: "NWS products are constantly updated/changed/decommissioned, check your logs to ensure that you are pulling valid data."

Unlike CelesTrak, nothing here tells a client to stop on the first non 200 response or counts errors toward a firewall. P1 asks for retries no faster than once a minute. So SWPC can keep the general rule of exponential backoff with jitter, with a floor of one minute between retries and a cap at the product's poll interval. A 404 deserves special handling because P1 warns that products get decommissioned: a persistent 404 should mark the product halted and visible, since retrying will not bring a retired file back.

The choices made from the options in this note (products, keys, a 5 minute interval, and error handling) are recorded in [ADR 0005](../adr/0005-swpc-polling-and-error-handling.md).

## What the Kafka key should be

The design keys space weather events by "feed product ID". SWPC does not define that term. Two readings are possible, and they differ:

1. **The SWPC file.** A fixed identifier per polled product, for example `swpc.kp.3h`, `swpc.goes.xrays.primary`, `swpc.goes.protons.primary`, `swpc.alerts` (the IDs actually used are shorter: `swpc.kp`, `swpc.goes.xrays`, `swpc.goes.protons`, `swpc.alerts`, per ADR 0005). All records of one product land on one partition in order, which matches how the risk rules consume a time series. This is the reading I recommend.
2. **The `product_id` field inside `alerts.json`** (for example `K04W`). This only exists for alerts, and it is a message type, so it would spread one product across many keys.

A per record identity for deduplication is separate from the key and is listed per product above.

## Options for the poller

Daily volume is the sample size times the number of polls per day, before any compression.

| Product | URL | Sample size (bytes) | Documented cadence | Observed cadence | NOAA scale | Dedupe identity | Volume at a suggested interval |
|---|---|---|---|---|---|---|---|
| Kp 3 hour | `/products/noaa-planetary-k-index.json` | 4,719 | every 3 hours (W7) | 3 h between records | G | `time_tag` | every 10 min: about 0.7 MB/day |
| Kp 1 minute estimate | `/json/planetary_k_index_1m.json` | 27,925 | not documented | 60 s | G (estimate only) | `time_tag` | every 5 min: about 8 MB/day |
| GOES X-ray, primary, 6 hour | `/json/goes/primary/xrays-6-hour.json` | 163,035 | 1 minute averages (W3) | 60 s | R | `time_tag`, `satellite`, `energy` | every 5 min: about 47 MB/day; every 1 min: about 235 MB/day |
| GOES flare, latest | `/json/goes/primary/xray-flares-latest.json` | 450 | not documented | one event | R (events) | `satellite`, `begin_time` (unconfirmed) | every 5 min: about 0.13 MB/day |
| GOES integral protons, primary, 6 hour | `/json/goes/primary/integral-protons-6-hour.json` | 59,957 | 5 minute averages (W4) | 300 s | S | `time_tag`, `satellite`, `energy` | every 5 min: about 17 MB/day |
| Alerts, watches, warnings | `/products/alerts.json` | 39,125 | not documented | irregular issue times | G, R, S (text) | `product_id`, `issue_datetime` | every 5 min: about 11 MB/day |
| NOAA scales summary | `/products/noaa-scales.json` | 1,097 | not documented | not measurable from one sample | G, R, S (precomputed) | whole snapshot | every 5 min: about 0.3 MB/day |
| Solar wind plasma, all sources | `/json/rtsw/rtsw_wind_1m.json` | 2,553,573 | not documented | 1 minute per source | none | `source`, `time_tag` | every 5 min: about 735 MB/day |
| Solar wind summary | `/products/summary/solar-wind-speed.json` and `solar-wind-mag-field.json` | 59 and 60 | not documented | not measurable from one sample | none | `time_tag` | every 1 min: about 0.17 MB/day for both |

All URLs are relative to `https://services.swpc.noaa.gov`.

**Recommendation.** A first version that covers the three scales the risk rules need, while keeping volume small:

1. Kp 3 hour, every 10 minutes: it is the product SWPC links for the index, it is small, and the G scale is defined on 3 hour Kp.
2. GOES X-ray primary 6 hour, every 5 minutes: the R scale is defined on the 0.1 to 0.8 nm flux and this is the smallest file holding it. A 6 hour window polled every 5 minutes overlaps heavily, so a missed poll or a restart loses nothing, and dedupe drops the repeats.
3. GOES integral protons primary 6 hour, every 5 minutes, if the S scale is in scope: it carries the ">=10 MeV" series the S scale is defined on, at the same cadence the data is averaged.
4. Alerts, every 5 minutes: SWPC's own issued warnings, and the corpus the assistant later retrieves from.

Solar wind is useful context but drives no NOAA scale; if it is wanted, the two summary files cost almost nothing, while the full RTSW files are too large to poll often. The 1 minute Kp estimate and the scales summary are undocumented, so I would not build rules on them. These intervals all respect P1 (no product faster than once a minute) and the 60 second cache.

## Open questions this note cannot settle

* The time zone of time tags without a `Z` (Kp files, RTSW files, `issue_datetime`) is not stated in the data. The alert text says UTC and agrees with `issue_datetime` in the sample, so UTC is the likely reading for alerts; for the Kp and RTSW files it is unconfirmed.
* Whether SWPC revises an already published 3 hour Kp value is not stated. If it does, dedupe on `time_tag` alone would drop the revision.
* Which X-ray value (`flux` or `observed_flux`) the R scale should be read from is not explained on W3.
* Conditional requests were tested only with `If-None-Match` against unchanged files at the CloudFront edge; behavior at the origin, and for `If-Modified-Since`, is not documented or tested.
