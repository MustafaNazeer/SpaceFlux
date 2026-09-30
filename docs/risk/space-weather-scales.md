# Space weather scales: mapping SWPC products to the NOAA G, R, and S scales

This note fixes how the risk engine turns three NOAA SWPC products into levels on the NOAA Space Weather Scales. Every threshold cites NOAA's published scale table or SWPC or NCEI product documentation. Points that rest on observation of the data rather than on documentation are labeled **observed**, with the evidence. Sources were accessed on 2026-09-27, except where the table or the text next to a quotation says 2026-09-30.

The levels SpaceFlux computes are derived mechanically from one physical measure each. NOAA's scale table says of each measure: "Based on this measure, but other physical measures are also considered." So a SpaceFlux level is labeled as derived from the named product, never presented as SWPC's own issued scale level, and SpaceFlux is not a space weather warning service.

## Sources

| # | Source | URL |
|---|---|---|
| W2 | SWPC, Planetary K-index product page | https://www.spaceweather.gov/products/planetary-k-index |
| W3 | SWPC, GOES X-ray Flux product page | https://www.spaceweather.gov/products/goes-x-ray-flux |
| W4 | SWPC, GOES Proton Flux product page | https://www.spaceweather.gov/products/goes-proton-flux |
| W7 | NOAA Space Weather Scales table (PDF, dated "December 11, 2023"), linked from https://www.spaceweather.gov/noaa-scales-explanation | https://www.spaceweather.gov/sites/default/files/images/NOAAscales.pdf |
| T1 | SWPC 3-Day Forecast text product, issue "2026 Sep 27 1230 UTC" (read 2026-09-27 about 20:33 UTC) | https://services.swpc.noaa.gov/text/3-day-forecast.txt |
| T2 | Earlier issues of the same SWPC 3-Day Forecast product, as captured by the Internet Archive (listed in Section 1.2); unmodified copies with provenance are kept in [risk-engine/src/test/resources/swpc-kp-labels](../../risk-engine/src/test/resources/swpc-kp-labels/PROVENANCE.md) | https://web.archive.org/web/2024*/https://services.swpc.noaa.gov/text/3-day-forecast.txt |
| X1 | Machol, Mothersbaugh, Lucas, Mahon, "User's Guide for GOES-R XRS L2 Products", NOAA NCEI, 15 December 2025 | https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/docs/GOES-R_XRS_L2_Data_Users_Guide.pdf |
| X2 | Machol, Mothersbaugh, Lucas, Mahon, "Readme for GOES-R XRS L2 Data", NOAA NCEI, 15 December 2025 | https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/docs/GOES-R_XRS_L2_Data_ReadMe.pdf |
| E1 | NOAA Office of Satellite and Product Operations, "GOES Eclipse Schedule" page (accessed 2026-09-30) | https://www.ospo.noaa.gov/operations/goes/eclipse.html |
| L | One off fetches of live SWPC files on 2026-09-30, not committed (Section 5 gives what was measured in each): `json/goes/primary/xrays-7-day.json`, `json/goes/secondary/xrays-7-day.json`, `json/goes/primary/integral-protons-7-day.json`, `json/goes/secondary/integral-protons-7-day.json`, `json/goes/primary/xray-flares-7-day.json`, `json/goes/instrument-sources.json` and `products/noaa-planetary-k-index.json`, one request each between 18:44:06Z and 18:45:33Z (server `Date` headers); and the primary `xrays-6-hour.json` and `integral-protons-6-hour.json` once a minute from 18:47:55Z to 19:02:59Z, 15 requests each | https://services.swpc.noaa.gov |
| A | Internet Archive captures of `products/noaa-planetary-k-index.json` made in 2026 (read 2026-09-30, not committed; Section 5.3 gives the count and what was measured) | https://web.archive.org/cdx/search/cdx?url=services.swpc.noaa.gov/products/noaa-planetary-k-index.json&from=2026&filter=statuscode:200&collapse=digest |
| F | Recorded SWPC fixtures in this repository, captured 2026-09-27T16:30:37Z, with provenance in `ingest/testdata/swpc/PROVENANCE.md` | [ingest/testdata/swpc](../../ingest/testdata/swpc/) |

Endpoints, shapes, and polling are documented in [docs/source/swpc.md](../source/swpc.md) and [ADR 0005](../adr/0005-swpc-polling-and-error-handling.md). All product URLs below are relative to `https://services.swpc.noaa.gov`.

## Summary

| Scale | Product | Records used | Field | Unit | Level 1 | Level 2 | Level 3 | Level 4 | Level 5 |
|---|---|---|---|---|---|---|---|---|---|
| G (geomagnetic storms) | `/products/noaa-planetary-k-index.json` | every record | `Kp` | Kp index | Kp ≥ 4.67 (5-) | Kp ≥ 5.67 (6-) | Kp ≥ 6.67 (7-) | Kp ≥ 7.67 (8-) | Kp ≥ 9.00 (9o) |
| R (radio blackouts) | `/json/goes/primary/xrays-6-hour.json` | `energy` equal to `"0.1-0.8nm"` | `flux` | W m⁻² | ≥ 1e-5 (M1) | ≥ 5e-5 (M5) | ≥ 1e-4 (X1) | ≥ 1e-3 (X10) | ≥ 2e-3 (X20) |
| S (solar radiation storms) | `/json/goes/primary/integral-protons-6-hour.json` | `energy` equal to `">=10 MeV"` | `flux` | pfu, protons/(cm² s sr) | ≥ 10 | ≥ 100 | ≥ 1,000 | ≥ 10,000 | ≥ 100,000 |

Below level 1 the derived level is "none". The G row applies the thirds reading in Section 1.2. The scale table itself leaves the thirds implicit, so the reading rests on SWPC's own labeling of decimal Kp values in its 3-Day Forecast product, quoted there.

## 1. G scale from planetary Kp

### 1.1 Thresholds

W7 lists the physical measure as "Kp values determined every 3 hours": G1 "Kp=5", G2 "Kp=6", G3 "Kp=7", G4 "Kp=8, including a 9-", G5 "Kp=9". W2 states the same mapping in its scales panel: "Kp = 5 (G1) Kp = 6 (G2) Kp = 7 (G3) Kp = 8, 9- (G4) Kp = 9o (G5)".

### 1.2 Kp in thirds, and how the decimal values map

Kp is reported in thirds, written with `-`, `o`, and `+` suffixes (W2 and W7 use "9-" and "9o"). **Observed:** every `Kp` value in the recorded fixture is a multiple of one third rounded to two decimals (the set is 0.0, 0.33, 0.67, 1.0, ..., 4.33), so 4.67 is "5-", 5.0 is "5o", and 5.33 is "5+".

The scale table does not say in words whether "Kp=5" includes 5- (4.67). SWPC's own products show that it does:

1. **SWPC labels decimal Kp values with G levels in the 3-Day Forecast (T1, T2).** The product's "NOAA Kp index breakdown" table appends a G level to each 3 hour value that reaches the scale, and its summary lines state the level of the greatest value. Quoted from archived issues (accessed 2026-09-27):
   * Issue "2024 Apr 16 1230 UTC" ([archive](https://web.archive.org/web/20240416212305/https://services.swpc.noaa.gov/text/3-day-forecast.txt), [copy](../../risk-engine/src/test/resources/swpc-kp-labels/3-day-forecast-20240416212305.txt)): "The greatest expected 3 hr Kp for Apr 16-Apr 18 2024 is 4.67 (NOAA Scale G1)", and the breakdown row "03-06UT ... 4.67 (G1)".
   * Issue "2024 May 14 1230 UTC" ([archive](https://web.archive.org/web/20240514123452/https://services.swpc.noaa.gov/text/3-day-forecast.txt), [copy](../../risk-engine/src/test/resources/swpc-kp-labels/3-day-forecast-20240514123452.txt)): "is 5.67 (NOAA Scale G2)"; the breakdown shows "4.67 (G1)", "5.67 (G2)", and "4.33" with no label.
   * Issue "2024 May 20 1230 UTC" ([archive](https://web.archive.org/web/20240520152328/https://services.swpc.noaa.gov/text/3-day-forecast.txt), [copy](../../risk-engine/src/test/resources/swpc-kp-labels/3-day-forecast-20240520152328.txt)): "is 6.67 (NOAA Scale G3)"; the breakdown shows "6.67 (G3)".
   * Issue "2024 May 11 0030 UTC" ([archive](https://web.archive.org/web/20240511011540/https://services.swpc.noaa.gov/text/3-day-forecast.txt), [copy](../../risk-engine/src/test/resources/swpc-kp-labels/3-day-forecast-20240511011540.txt)): the breakdown shows "7.67 (G4)", "8.00 (G4)", "7.00 (G3)", and "4.33" with no label.
   * Issue "2024 Sep 19 1240 UTC" ([archive](https://web.archive.org/web/20240919213019/https://services.swpc.noaa.gov/text/3-day-forecast.txt), [copy](../../risk-engine/src/test/resources/swpc-kp-labels/3-day-forecast-20240919213019.txt)): "The greatest observed 3 hr Kp over the past 24 hours was 4.67 (G1-Minor)."

   Across the 96 distinct issues from 2024 Jan 1 to 2026 Jan 18 that the archive returned on 2026-09-27 (a one off verification: the issue list and the check are not committed, so the build does not rerun it; the five issues cited here are committed and are the reproducible part), every breakdown value followed the same rule with no exception: 4.33 and below carried no label, 4.67 to 5.33 carried G1, 5.67 to 6.33 G2, 6.67 to 7.00 G3, and 7.67 to 8.00 G4. No issue in that set contained 8.67 or 9.00.
2. The 9- versus 9o split comes from the scale table and the Kp page directly. W2: "Kp = 8, 9- (G4) Kp = 9o (G5)". W7 needs the words "including a 9-" to place 9- (8.67) in G4, which is only necessary because "Kp=N" otherwise covers N-, No, and N+.
3. **Observed, supporting only:** SWPC issued "ALERT: Geomagnetic K-index of 4" (`ALTK04`) for "Synoptic Period: 2100-2400" on 2026 Sep 25 and for "Synoptic Period: 0000-0300" on 2026 Sep 25, and the recorded Kp for both intervals (`time_tag` 2026-09-25T21:00:00 and 2026-09-25T00:00:00) is 3.67, that is 4-. This concerns K-index alerts rather than the G scale, and alerts are issued in real time from an estimate that can differ from the later value, so it is consistent with the reading but does not establish it.

Consequences, all as the "≥" comparisons in the Summary table: G1 from 4.67, G2 from 5.67, G3 from 6.67, G4 from 7.67, and G5 only at 9.00. The G1 to G4 lower bounds are each shown directly by SWPC's forecast labels above. G5 at 9.00 only, with 8.67 in G4, rests on W2 and W7, since no archived issue in the set contained either value. Comparisons use a tolerance of 0.005 so that 4.67 compares equal to the 4.67 threshold regardless of floating point representation; the data carries two decimals.

### 1.3 Time zone and interval anchoring of `time_tag`

**Ruling: `time_tag` is UTC and marks the start of the 3 hour interval.** The file does not say so itself (its time tags have no zone suffix), so this rests on three observations made on 2026-09-27:

1. T1, issued 1230 UTC, lists the observed breakdown for Sep 27 by UT interval: "00-03UT 4.00", "03-06UT 3.33", "06-09UT 2.00". The recorded fixture has `2026-09-27T00:00:00` = 4.0, `03:00:00` = 3.33, `06:00:00` = 2.0. Each `time_tag` matches the interval that starts at it.
2. The alert messages above label the interval as a synoptic period in UTC ("Synoptic Period: 0000-0300" with "Issue Time: 2026 Sep 25 0300 UTC"), and the Kp record for that period carries `time_tag` 00:00.
3. At 20:35 UTC, the observed Kp file's newest record was `2026-09-27T15:00:00`, and `/products/noaa-planetary-k-index-forecast.json` labeled `2026-09-27T18:00:00` as `"estimated"` while earlier tags were `"observed"`. The 18:00 interval was then still in progress (18 to 21 UTC), which fits start anchoring; under end anchoring it would already have been complete.

So a record with `time_tag` T describes the interval [T, T + 3 h) UTC, and a G level derived from it is attributed to that interval.

### 1.4 Whether SWPC revises Kp values

**Ruling: treat every Kp value as revisable, and take the latest value received for a `time_tag` as current.**

* W2 calls the product "The Estimated 3-hour Planetary Kp-index", and the forecast file labels its most recent intervals `"estimated"`, separately from `"observed"` ones (observation 3 above). Whether a value changes when its label changes is not documented. SWPC does not document whether an already published value in the observed file can change.
* **Observed:** comparing the recorded fixture (captured 16:30:37Z) with a fetch at 20:35:18Z on the same day, all 61 overlapping records were identical. One four hour comparison does not show that revisions never happen.

Consequences for the pipeline: a consumer keyed on `time_tag` must replace a stored value and re-evaluate its level when a different `Kp` arrives, never ignore it. The ingest side currently publishes a Kp record only when its `time_tag` was not in the previous response ([ADR 0005](../adr/0005-swpc-polling-and-error-handling.md), which records that a revision "is not republished"), so a revision would not reach the risk engine today. The ADR already says this is revisited if revisions are observed; a change that compares (`time_tag`, `Kp`) instead would close the gap.

## 2. R scale from GOES X-ray flux

### 2.1 Thresholds and band

W7, Radio Blackouts: the measure is "GOES X-ray peak brightness by class and by flux", footnoted "Flux, measured in the 0.1-0.8 nm range, in W· m-2." R1 "M1 (10-5)", R2 "M5 (5x10-5)", R3 "X1 (10-4)", R4 "X10 (10-3)", R5 "X20 (2x10-3)". Only records whose `energy` is `"0.1-0.8nm"` are used; the `"0.05-0.4nm"` records never set an R level.

The comparisons are "≥". X1 section 2.2 ("Flare Magnitudes") defines the classes by the flux itself ("an M5 index is defined for a 5x10-5 W m-2 peak irradiance") and says "The flare index is defined by the truncated (not rounded) irradiance; e.g., a flare with peak irradiance of 4.19 × 10−5 W/m2 is an M4.1 flare". Truncation means a flux of exactly 1e-5 is M1.0, so it reaches R1. **Observed:** every GOES value in the recorded SWPC JSON files (the live X-ray and proton captures, and the GOES-16 X-ray storm fixture converted from NCEI netCDF) is a 32 bit float widened to a 64 bit number, and the 32 bit float nearest 1e-5 is 9.999999747e-6, below the 64 bit 1e-5; the same holds at 5e-5 and 1e-4. So the value and each threshold are compared as 32 bit floats, the precision the data is published in, and a stored value of M1.0, M5.0, or X1.0 reaches its level. At 1e-3 and 2e-3 the nearest 32 bit float lies above the threshold, so R4 and R5 are unaffected. The converted GOES-13 proton storm fixture holds short decimals from a CSV archive rather than 32 bit floats; the comparison does not change the level of any of its values. How SWPC itself compares is not documented.

### 2.2 Which field: `flux`, not `observed_flux`

**Ruling: the R scale reads `flux`.**

* X1 section 4 ("1-minute Averages Product"): "Three X-ray averaged irradiance values are reported for each channel: the uncorrected irradiance observed by the instrument (e.g., xrsb flux observed), the estimated electron contamination (e.g., xrsb flux electrons), and irradiance corrected for the electron contamination (e.g., xrsb flux). For most purposes, the corrected irradiance should be used." X2 section 2.2 describes the same three values and adds "The corrected flux has a minimum threshold of 10−9 W m−2."
* SWPC does not document its JSON keys, so the match between SWPC's `flux`, `observed_flux`, and `electron_correction` and NCEI's three variables rests on the names and on arithmetic. **Observed:** in all 358 `"0.1-0.8nm"` records of the fixture, `observed_flux` equals `flux + electron_correction` to a relative difference of at most 9.5e-8 (floating point rounding). In the `"0.05-0.4nm"` band the identity breaks by up to 0.9 percent, exactly where `flux` sits at its floor of about 1e-9, which matches X2's minimum threshold for the corrected flux. So `flux` is the corrected irradiance. The identity does not hold in minutes where SWPC writes 0 for a missing measurement (Section 5.2).
* For the R scale the choice rarely matters. X2 section 4 ("Data Caveats"), item 3: "The XRS irradiances are noticeably contaminated by electrons during periods where X-ray fluxes are low and electron fluxes are high. The impact is negligible in other conditions." **Observed:** the largest `electron_correction` in the fixture's long band is 5.8e-8 W m⁻², under 0.6 percent of the R1 threshold. Reading `flux` is still the documented choice.
* X1 section 2.2 also notes that GOES-R irradiances "are provided in true physical units of W m-2" without the older SWPC scaling factors, so no rescaling is applied.

### 2.3 Time and satellite

**Observed:** `time_tag` carries a `Z` suffix (UTC) and consecutive tags are 60 seconds apart, matching W3's "1-minute averages". X1 section 2.2 says the flare index "is based on the 1-minute average of the GOES operational irradiance in the XRS-B channel at the peak of the flare", so each 1 minute record is compared on its own. Whether a `time_tag` marks the start or the end of its minute is not documented, and nothing here depends on it at 1 minute resolution.

The primary satellite changes over time (W3 and [docs/source/swpc.md](../source/swpc.md)), so every level carries the `satellite` of the record that produced it, and values from different satellites are never merged into one series. Section 5.4 covers what happens to a satellite's series when SWPC changes the primary.

### 2.4 X-ray class of a value

An R level can carry the X-ray class of the `flux` value that set it. The rule:

1. **Letter by decade.** M for a flux from 1e-5 up to but not including 1e-4 W m⁻², X for 1e-4 W m⁻² and above. X1 section 2.2: "Flare indices are denoted by a letter and a number based on the log 10 peak irradiance of the flare (X: 10-4 W m-2, M: 10-5 W m-2, C: 10-6 W m-2, B: 10-7 W m-2, and A: 10-8 W m-2)." There is no letter above X, so the X number keeps growing past 10: W7 writes R4 as "X10 (10-3)" and R5 as "X20 (2x10-3)". A class is only attached at R1 or above, so only M and X occur.
2. **Number: flux over the decade base, truncated to one decimal.** The base is 1e-5 for M and 1e-4 for X. X1 section 2.2: "an M5 index is defined for a 5x10-5 W m-2 peak irradiance, and an X2.5 index is defined as an irradiance level of 2.5x10-4 W m-2 peak irradiance. The flare index is defined by the truncated (not rounded) irradiance; e.g., a flare with peak irradiance of 4.19 × 10−5 W/m2 is an M4.1 flare, not an M4.2 flare." Truncation keeps one digit after the decimal point, as in every class X1 gives (M4.1, X2.5, X3.6) and every class in SWPC's messages and flare file (below). So 3.9789e-4 is X3.9, 1.4506e-5 is M1.4, and 2.0e-3 is X20.0.
3. **Precision.** Truncate the shortest decimal that reads back as the same 32 bit float, not the 64 bit number in the JSON. The 32 bit float nearest 1e-5 is 9.999999747e-6 (Section 2.1); dividing that 64 bit number by 1e-5 and truncating gives 0.9, so a value that reaches R1 would carry a class below M1.0. Its shortest decimal is 1e-5, which gives M1.0. Done this way, the value stored as M1.0, M5.0, X1.0, X10.0 or X20.0 carries exactly that class, and class and level agree at every threshold.
4. **GOES-16 and later only.** X1 section 2.2 says SWPC applied scaling factors to GOES 8 to 15 irradiances and that "flare indices for the earlier satellites were based on irradiances that were reported as 42% (1.0/0.7) smaller than for GOES-R", and W3 (read 2026-09-30) says the different GOES-R calibration "impacts flare magnitudes". The rule above is for GOES-R irradiances in physical units; no class is attached to a value from a satellite numbered below 16.

**SWPC truncates. Observed:**

* SWPC's flare file, committed unmodified as [`risk-engine/src/test/resources/swpc-xrays/evidence/xray-flares-7-day.json`](../../risk-engine/src/test/resources/swpc-xrays/evidence/xray-flares-7-day.json) (fetched 2026-09-30, `Date` 19:19:31Z; provenance in that folder's PROVENANCE file; the fetch at 18:44:51Z in source L returned the same bytes), lists 36 flares on GOES-18, 34 of them with a maximum (`max_xrlong` and `max_class`; the other two have `null` for both). Truncating `max_xrlong` by the rule above gives `max_class` in 34 of 34. Rounding to one decimal gives it in 17; in the other 17 rounding gives a different class (for example `max_xrlong` 1.1673e-6 is listed as C1.1, which rounds to C1.2). The file held only B and C flares, so this shows the convention below M.
* The committed R storm material (in `risk-engine/src/test/resources/swpc-storms/`, see its PROVENANCE file): for each of the 12 flares with an X-ray class in SWPC's event report `evidence/20240510dayevt.txt`, I read the GOES-16 `xrsb_flux` at the maximum minute the report gives from the committed netCDF file `archive/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc`. Truncation gives the reported class in 12 of 12; rounding in 4 of 12. The 12 values are committed as a table in [`risk-engine/src/test/resources/swpc-xrays/goes16-flare-classes-2024-05-10.json`](../../risk-engine/src/test/resources/swpc-xrays/goes16-flare-classes-2024-05-10.json), made by a committed script from the two files above. Among the 8 that rounding misses are X3.9 (06:54, 3.9789e-4, which rounds to X4.0; SWPC's summary SUMX01 serial 150 also says X3.9) and M1.4 (03:29, 1.4506e-5, which rounds to M1.5). The netCDF file was regenerated by NCEI in December 2025, so it is not guaranteed to hold the exact real time values; agreement in all 12 supports truncation but does not prove SWPC used these exact numbers.

Both checks were run once; the data they use is committed, but they are not yet committed as tests.

**Wording.** X1 defines a flare's class from "the 1-minute average ... at the peak of the flare". A class attached to one 1 minute value describes that value only: on the rise or the decay of a flare it is smaller than the flare's class, and SpaceFlux does not determine a flare's peak. Write "X-ray class X3.9 from the GOES-16 value at 06:54 UTC", never "an X3.9 flare"; SWPC's own messages give flare classes.

## 3. S scale from GOES integral protons

### 3.1 Thresholds and energy channel

W7, Solar Radiation Storms: the measure is "Flux level of > 10 MeV particles (ions)", footnoted "Flux levels are 5 minute averages. Flux in particles·s-1·ster-1·cm-2". S1 10, S2 10², S3 10³, S4 10⁴, S5 10⁵. W4: "The ≥10 MeV products match the NOAA Solar Radiation Storm (S-scale) thresholds (10, 100, 1000, 10000, 100000 pfu), based upon values observed or expected on the primary GOES satellite." Only records whose `energy` is `">=10 MeV"` are used. Values are compared as 32 bit floats as in Section 2.1; every S threshold is exact at that precision.

The comparisons are "≥". W4: "Initial ALERTS for ≥10 MeV and ≥100 MeV energies are issued for integral flux reaching or exceeding 10 pfu and 1 pfu, respectively," and "Higher threshold ≥10 MeV ALERTS are also issued for threshold exceedance of 100, 1,000, 10,000, and 100,000 pfu, matching the thresholds described in the NOAA S-scale."

### 3.2 Time and satellite

**Observed:** `time_tag` carries a `Z` suffix and consecutive tags are 300 seconds apart, matching W7's "5 minute averages" and W4's "GOES 5-minute averaged integral proton fluxes". The satellite rule in 2.3 applies here too.

W4 also notes that "Because flux levels can drop slowly, the time of a 'confirmed' drop below threshold can sometimes take several hours to determine." SpaceFlux reports the level of each 5 minute value; it does not declare the end of an event.

## 4. How a derived level is presented

A derived level reads on the dashboard and in alerts like an official scale level unless the wording stops it. These rules apply to every surface that shows one:

* **Name the source and the measurement, not a storm.** Write "G1 level from SWPC estimated Kp 4.67, 00 to 03 UTC", "R1 level from GOES-18 X-ray flux 1.2e-5 W m⁻² at 14:02 UTC", or "S1 level from GOES-18 ≥10 MeV proton flux 12 pfu at 14:05 UTC". Do not write "G1 storm in progress", "radio blackout", or "radiation storm warning": SWPC decides those with other physical measures too (the W7 footnote above), and SWPC issues its own alerts, watches, and warnings.
* **Observed values only, no forecast.** A derived level describes a value SWPC has already published. It is not a watch or a warning, and it says nothing about the next interval. For forecasts and official notices, point to SWPC at https://www.spaceweather.gov.
* **Kp is an estimate.** W2 calls it "The Estimated 3-hour Planetary Kp-index", and a value can change (Section 1.4); a G level can therefore change or disappear for the same interval, and the display says so rather than presenting it as final.
* **Per sample, not per event.** R and S levels are per 1 minute and per 5 minute value. One sample at a threshold is shown as that sample, not as the start of an event, and SpaceFlux does not declare an event's end (Section 3.2).
* **Keep the three states distinct.** "none" (valid value below level 1), "no data" (Section 5), and a level are shown differently, and "no data" is never shown as quiet.
* **No safety of life or protective language.** No surface tells anyone to take or skip an action, calls conditions safe, or presents SpaceFlux as a space weather warning service. The effects listed in the NOAA scale table describe what SWPC associates with each level in general, not an assessment of any specific satellite, grid, or person, and are quoted as such if shown at all.

## 5. Missing and invalid data

A gap is not a quiet period. Every value a level could be read from ends in one of three states: a level, "none" (a valid value below level 1), or "no data". This section says when a value is rejected (5.1), what the GOES files use in place of a missing measurement (5.2), when a series counts as "no data" (5.3), and what happens when SWPC changes the primary satellite (5.4).

### 5.1 Values that set no level

A record either sets a level or "none", or it gives "no data" for its time. There are two ways to give "no data": the value is **rejected** (the first eight items below), or it is a well formed value on an eclipse edge that would otherwise be "none" (the ninth). These checks run before a record reaches its series, so a rejected record can never become a series' newest sample. Both still count as a record received for their series when its freshness is measured (5.3).

* A value that is not finite, or is negative, is rejected with a reason and does not set a level. So is a Kp above 9.00 (plus the 0.005 tolerance of Section 1.2): W2 gives the index "an integer in the range 0-9" and places "Kp = 9o (G5)" at the top of its scale, and Kp in thirds reaches 9o at 9.00 (Section 1.2), so a larger number is not a Kp value, and deriving G5 from it would present corrupt data as the most extreme level.
* A record a level is read from that has no `time_tag`, or a GOES record without its `energy`, is rejected with a reason: a level cannot be attributed to a time, and a band or channel cannot be assumed.
* **A `time_tag` that matches the schema pattern but is not a real UTC date and time is rejected with a reason**, never dropped: the pattern checks only the shape (four digits, two, two, and so on), so `2026-02-30T00:00:00` or an hour of 24 passes it. This applies to the Kp `time_tag` (read as UTC, Section 1.3) and to both GOES `time_tag` forms.
* **A Kp record whose `time_tag` is not on a 3 hour boundary is rejected with a reason.** A Kp `time_tag` is the UTC start of a 3 hour interval (Section 1.3), so it must be 00, 03, 06, 09, 12, 15, 18 or 21 hours with zero minutes and seconds. **Observed:** all 86 Kp `time_tag` values in the committed files (61 in `ingest/testdata/swpc/kp.json`, 24 in `risk-engine/src/test/resources/swpc-storms/kp-2024-05-10-to-12.jsonl`, 1 in `schemas/raw.swpc/examples/valid-kp.json`) are on such a boundary, and so are all 21,452 in the 359 readable Internet Archive captures of 5.3 (source A, one off). A value between boundaries would otherwise be attributed to an interval that does not exist.
* **A record whose `time_tag` is more than 5 minutes after its `fetched_at` is rejected with a reason** (evidence and the alternatives considered under "Time plausibility" below). No real sample can postdate the response that carried it, and a schema valid record with a `time_tag` far in the future (for example Kp at 2300-01-01) would otherwise become the newest sample of its series, hold its freshness reference in the future so it could never go stale, and push every real record after it into history.
* **A GOES `satellite` that is not a positive integer is rejected with a reason.** The schema requires only an integer. W3 calls the current series "GOES-R (16-19)", the committed `instrument-sources.json` (5.4) names only 18 and 19, and SWPC's `json/goes/satellite-longitudes.json` (read 2026-09-30) lists 13 to 19 and also 99, with a `null` longitude and no documented meaning. So the rule is only "positive": a list of known numbers would reject every record from a new satellite until the list was changed, and 99 would have to be put on it or left off without knowing what it is. Records from any accepted number stay a separate series (Section 2.3), and the X-ray class is attached only from 16 on (Section 2.4).
* **An X-ray `flux` below 1e-9 W m⁻², including 0, is rejected with a reason.** X2 section 2.2 says "The corrected flux has a minimum threshold of 10−9 W m−2", so a smaller corrected value is not a measurement, and SWPC's files write 0 where there is none (5.2). The comparison is made between 32 bit floats, as in Section 2.1, so the floor value itself (9.999999717180685e-10 in the files, the 32 bit float nearest 1e-9) is not below it and stays a valid value below R1. Without this rule a missing X-ray measurement reads as "none". A `flux` of exactly 0 is SWPC's marker for a missing measurement (5.2), so the record is well formed: it is rejected for the level and counts as "no data", but it is not treated as corrupt data. What happens to rejected records in the pipeline is set in [docs/data/topics.md](../data/topics.md), not here.
* **An X-ray `flux` above 0.2 W m⁻² is rejected with a reason.** 0.2 is the `valid_max` NCEI declares for `xrsb_flux` in its netCDF files of the same operational product (5.2), 100 times the R5 threshold. As with every other X-ray threshold, the value and the bound are compared as 32 bit floats (Section 2.1): a value is rejected when its 32 bit float is greater than the 32 bit float nearest 0.2, so a value stored as exactly 0.2 is accepted. Nothing observed comes near it: the largest `flux` in any committed fixture is 3.98e-4 and in the live 7 day files 8.70e-6 (5.2). The bound exists so that a large positive placeholder, if SWPC ever wrote one, reads as "no data" instead of R5.
* **An X-ray value on the edge of a zero run that would be "none" is "no data"** (5.2, "Eclipse edges"), even though its `flux` is a valid number, except a leading edge value before a level (edge rule 5). An edge value at R1 or above keeps its level.
* A rejected value, and an edge value that would be "none", count as "no data" for their time, never as "none".
* The derived level is "none" only when a valid value exists and is below the level 1 threshold.

**Time plausibility, evidence and alternatives.** Every real `time_tag` is at or before the time SWPC wrote the file, and so before ingest's `fetched_at`:

* Kp: a `time_tag` is the start of a 3 hour interval that SWPC publishes only after it ends (5.3). In the committed `ingest/testdata/swpc/kp.json` the newest `time_tag` is 4 h 30 min 37 s before the capture; in the Internet Archive captures of 5.3 (source A) the newest was never less than 3 h 2 min 39 s before the capture.
* GOES: a `time_tag` marks a 1 or 5 minute average published minutes later. Newest `time_tag` minus capture time in the committed `goes-xrays-6-hour.json` and `goes-integral-protons-6-hour.json`: minus 4 min 37 s and minus 10 min 37 s; in the 15 reads of 5.3 (source L), minus 4 min 20 s to minus 6 min 43 s for X-rays and minus 8 min 0 s to minus 15 min 10 s for protons.
* The converted storm fixtures and the schema examples have `time_tag` minus `fetched_at` between minus 1 day and minus about 9 years. So in every committed file the difference is negative; the largest seen anywhere is minus 4 min 20 s.
* `fetched_at` is ingest's clock, and SWPC's times are SWPC's. The fixture captures recorded my local clock agreeing with SWPC's `Date` header to the second, but a host clock can drift, so the bound needs some tolerance. If ingest's clock ran behind by more than the tolerance, real records would be rejected, visibly and with a reason, not dropped.

| Tolerance after `fetched_at` | What it allows | Cost |
|---|---|---|
| 1 min | clock skew of under a minute | relies on a well synchronized ingest clock; a modest drift rejects current records |
| 5 min (the rule) | since real data trails the fetch by at least 4 min 20 s, a real record is rejected only if ingest's clock is more than about 9 minutes behind SWPC's | a planted future record can suppress at most 5 minutes of real data |
| 1 hour | any plausible skew | a planted record can suppress up to an hour of R data (60 records) and a Kp interval |
| per product: Kp `time_tag` plus 3 h at or before `fetched_at` plus the tolerance | uses the fact that a Kp interval is published only after it ends | rejects real data if SWPC ever starts publishing an interval in progress in this file, which it did in none of the 359 captures of 5.3 |

**No lower bound.** Replayed archive data and the converted storm fixtures are legitimately years older than their `fetched_at`. An old record cannot become a series' newest sample while a newer one exists, and freshness is measured against the clock (5.3), so an old record reads as history or as "no data", never as current.

### 5.2 Fill and sentinel values in the GOES JSON files

The concern is a positive placeholder (for example a large constant) that would read as R5 or S5, or a small one that would read as "none".

**Documented.**

* SWPC documents no fill or sentinel value for its JSON files. W3 (read 2026-09-30) says only that "Some data dropouts occur during instrument calibrations and satellite eclipses when the Earth or the moon comes between the satellite and the sun, especially during the spring and fall", and that eclipse seasons "last for about 45 to 60 days and ranges from minutes to just over an hour". W4 says nothing about missing data.
* NCEI's netCDF files of the same operational XRS product (X2 section 3: the operational data are the products "used in operations at SWPC") declare a fill value. **Observed** in the attributes of the committed file `risk-engine/src/test/resources/swpc-storms/archive/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc`: `_FillValue` is -9999.0 for every flux variable, and `xrsb_flux` has `valid_min` 1e-9 and `valid_max` 0.2 (`xrsb_flux_observed` has `valid_max` 0.003). NCEI's GOES-13 proton CSV marks missing values as -99999.0 (see the storm fixtures' PROVENANCE file). Both fill values are negative, so 5.1 already rejects them, and neither is documented as appearing in SWPC's JSON.

**Observed in the committed fixtures.** No positive constant repeats in any of them:

| File | Values checked | Negative | Largest | Repeated values |
|---|---|---|---|---|
| `ingest/testdata/swpc/goes-xrays-6-hour.json` | 716 each of `flux`, `observed_flux`, `electron_correction` | 0 | `flux` 1.225e-6 | `flux` 9.999999717180685e-10 in 3 `"0.05-0.4nm"` records, the floor |
| `ingest/testdata/swpc/goes-integral-protons-6-hour.json` | 568 `flux` | 0 | 1.75 | none |
| `risk-engine/src/test/resources/swpc-storms/goes16-xrays-2024-05-10T03-09.jsonl` | 720 each of the three fields | 0 | `flux` 3.98e-4 | `electron_correction` 0.0 in 72 records; no repeated `flux` |
| `risk-engine/src/test/resources/swpc-storms/goes13-protons-2017-09-10T16-22.jsonl` | 432 `flux` | 0 | 1242.7 (`">=5 MeV"`) | 53.586 twice (`">=100 MeV"`, ordinary data) |

**Observed in the live 7 day files (source L, one off, not committed).** Primary and secondary `xrays-7-day.json` (GOES-18 and GOES-19, 10,063 and 10,060 minutes, 2026-09-23 to 2026-09-30) and `integral-protons-7-day.json` (2,008 and 2,009 time tags): no `null`, no negative value, no repeated positive constant other than the floor below, largest X-ray `flux` 8.70e-6, largest proton `flux` 168.0 pfu (`">=1 MeV"`). Two constants repeat, both only in the X-ray files:

* the floor 9.999999717180685e-10, mostly in the `"0.05-0.4nm"` band (2,395 records on GOES-18, 4,364 on GOES-19), and in 12 `"0.1-0.8nm"` records on GOES-19;
* **0.0**, in `flux` and `observed_flux` together, in 597 minutes of each band on GOES-18 and 466 on GOES-19.

The zeros are missing measurements. On each day, GOES-18 has one run from about 08:26 to 09:32 UTC and GOES-19 one from about 04:19 to 05:25 UTC, each 62 to 66 minutes long. E1 gives the eclipse season as "late August to mid-October", eclipses of "up to 72 minutes each day", and satellite local midnight at "~0500 UTC for GOES-East and ~0900 UTC for GOES-West" (GOES-East is GOES-19 and GOES-West is GOES-18 on the same page). The other runs: 2026-09-25 17:13 to 17:32 on GOES-18 and 17:21 to 17:32 on GOES-19, while both proton files skip 17:10 to 17:30; 2026-09-29 23:02 to 2026-09-30 00:56 on GOES-18 only (see 5.4); and two short runs on GOES-18 (7 minutes at 2026-09-25 03:00, 1 minute at 2026-09-30 10:07). The proton files contain no zeros; missing proton data shows as missing time tags (5.3).

In a zero minute the other two fields are not zero, so the identity `observed_flux` = `flux` + `electron_correction` of Section 2.2 does not hold there. In the committed eclipse subset `risk-engine/src/test/resources/swpc-xrays/goes18-xrays-7-day-eclipse-2026-09-24.json` (372 records, 07:27 to 10:32 UTC), all 132 zero records (66 minutes, both bands) have `observed_flux` 0, `electron_contaminaton` true, and a nonzero `electron_correction` (7.6e-13 to 2.7e-9 W m⁻²). In the whole primary 7 day file for GOES-18 (the capture of 19:18:36Z named in 5.4; the fetch of 18:44:06Z in source L gives the same counts) there are 1,194 zero records (597 minutes, both bands). The same holds in 1,152 of them: the 908 records of the 7 eclipse runs (454 minutes) and the 244 records of two runs outside the eclipse times, 2026-09-29 23:02 to 2026-09-30 00:56 (115 minutes) and 2026-09-25 03:00 to 03:06 (7 minutes). The other 42, the runs of 2026-09-25 17:13 to 17:32 (20 minutes) and 2026-09-30 10:07 (1 minute), have `electron_contaminaton` false and `electron_correction` 0. So the flags do not tell an eclipse from an outage either. None of these fields sets a level on its own; the rule in 5.1 reads `flux` only.

The minutes on either side of an eclipse are dimmed but not zero, and the files carry no flag for them. On GOES-18 on 2026-09-24 the `"0.1-0.8nm"` flux falls from 6.0e-7 at 08:23 to 1.2e-7, 2.7e-8 and 8.5e-9 before the first zero at 08:27, and climbs back over 09:33 to 09:36; GOES-19 shows the same 3 to 4 dimmed minutes on each side of its runs. All 12 long band floor values on GOES-19 sit at these edges. That a dimmed value understates the flux, so that it can hide a level but not create one, is my physical reasoning (the Earth can only block part of the Sun's light); neither SWPC nor NCEI says anything about these minutes.

**Unknown.** Whether SWPC ever writes a positive placeholder (none documented, none in the values above); whether a proton `flux` of 0 would mean missing (never seen); whether the zeros also cover instrument calibrations, which W3 names as a cause of dropouts (no run outside the eclipse times and the two outages could be tied to one).

**Guards.** Three rules in 5.1 follow from this: the floor (a missing measurement written as 0 would otherwise read as "none", about an hour of the primary satellite's X-ray data each night in eclipse season), the upper bound at 0.2 W m⁻² (for a positive placeholder that has never been seen), and the eclipse edge rule below.

**Eclipse edges.** Measured on the `"0.1-0.8nm"` band of the live 7 day files (source L), for the 14 zero runs that fall in the eclipse times (7 on GOES-18, 7 on GOES-19, 62 to 66 minutes each). For each run I compared the minutes next to it with the median of the minutes 10 to 20 away on the same side:

| Minutes next to the run below half of that median | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| Runs, before the first zero | 0 | 3 | 10 | 1 |
| Runs, after the last zero | 3 | 9 | 2 | 0 |

Below 90 percent of the median, the dip spans 3 or 4 minutes before the run in 12 of the 14 runs and 2 to 4 minutes after it in 12 of the 14; in the other cases the minutes past the steep part stay at 54 to 90 percent for 6 or 7 minutes without climbing back, which reads as a change in the Sun's own level over those minutes rather than an eclipse edge. The steep part never exceeded 4 minutes on either side. The five zero runs outside the eclipse times (1, 7, 12, 20 and 115 minutes) show no steep dip: none of the 6 minutes on either side falls below half of the median; the lowest is 69 percent, at the end of a gentle decline before the 7 minute run. Those runs are not eclipses, and nothing in the files tells the two kinds apart except the time of day.

The rule:

1. **A zero run** is one or more consecutive `"0.1-0.8nm"` records of one satellite whose `flux` is 0 or below the 1e-9 floor of 5.1; a value below the floor counts exactly like 0. Any length counts, from 1 minute: a minimum length would not separate eclipses from outages (the longest run seen, 115 minutes, was an outage), and applying the edge to an outage run costs a few minutes of "no data" around a run that is already "no data".
2. **The edge** is every record of the same satellite and band whose `time_tag` is within 5 minutes of the run: from 5 minutes before its first zero to 5 minutes after its last, measured in `time_tag`, not in record count, so holes do not stretch it. 5 is the largest measured dip, 4 minutes, plus one minute. Considered instead: 4 minutes, which matches the largest measured dip with no margin, and 7, which would also cover the slow drifts that are not edges. Over the 7 days on GOES-18, the 5 minute edge adds at most 110 minutes of "no data" in total (11 runs, 10 minutes each).
3. **An edge value below R1 is "no data", not "none"**, with a reason naming the run, except in the leading edge before a level (rule 5). **An edge value at R1 or above keeps its level.** My reasoning, not a statement by SWPC or NCEI: the Earth can only block part of the Sun's light, so dimming can only lower a reading. A dimmed value below R1 therefore says nothing about whether the Sun was below R1, but a dimmed value at a level shows the flux reached at least that level.
4. **After the run** the order of arrival does the work: the zeros arrive before the minutes that follow them, so a value in the 5 minutes after the last zero that would be "none" is "no data" as it arrives, and one at R1 or above keeps its level (rule 3).
5. **Before the run** it does not: the minutes before the first zero arrive, and are derived, before the zeros that show they were on an edge. **Rule: derive on arrival, then restate.** Each value is derived as it arrives. When a zero run starts, the run's "no data" is extended back over the leading edge, but only as far as the first edge value after the last level in that edge: if any value in the 5 minutes before the first zero is at R1 or above, the "no data" starts at the edge value right after the last such level, and the edge values before that level keep their state; if none is, it starts at the first edge value. Every value in that span that was derived as "none" is restated as "no data". So a "no data" span never covers a published level. If that changes the published state, a correction event restates it, the same way a revised Kp value is handled (Section 1.4). By rule 3 a restatement can only ever change "none" to "no data": a level on an edge stays a level, so no level is ever withdrawn by this rule. The file changes at 3 minute steps and is polled every 5 (5.3), so the first zeros usually arrive in the same or the next poll as the edge minutes; since the current state already goes to "no data" at the first zero, a restatement in practice moves the start of that "no data" back by up to 5 minutes, and never past a level.

   Considered instead: holding each value back until a value at least 5 minutes later of the same series has arrived (or the series goes to "no data" by the age limit), so that nothing is ever restated. It gives the same history, but every R state change, including every R level, would be known about one poll (about 5 minutes) later.

### 5.3 When a series is in "no data"

**The series.** G has one series: Kp is a planetary index and carries no satellite. R and S have one series per satellite: (R, satellite) from the `"0.1-0.8nm"` records of `swpc.goes.xrays`, and (S, satellite) from the `">=10 MeV"` records of `swpc.goes.protons`. Each series has a history, one state per time tag, and a current state.

**History.** Every time tag a series is expected to have (every 3 hours for Kp, every minute for R, every 5 minutes for S, on the grid of the series' own tags) holds a level, "none", or "no data". A tag with no record, with a rejected record, or with an edge value that would be "none" and lies in a zero run's "no data" span (5.2, edge rules 3 to 5) is "no data". A missing tag never takes the level of the values on either side of it.

**Freshness reference.** For each series, the freshness reference is the newest `time_tag` among all records received for it that carry a usable `time_tag`: valid values, rejected values (5.1), and edge values alike. It measures whether SWPC is still delivering the series, not whether the newest record sets a level; a zero minute or an edge minute shows that the feed is alive even though it sets no level. A record without a usable `time_tag` cannot be placed in time and does not move it. Age is the current UTC time minus the freshness reference. It is measured against the clock, not against `fetched_at`, so data replayed from an archive or delivered late never reads as current.

**Current state.** This is what anything that shows a level as current reads, including the alerts topic:

1. If the age is over the series' age limit (below), the current state is "no data" from the time that limit was passed.
2. Otherwise it is the state of the record at the freshness reference: its level or "none", attributed to that record's time or interval (Section 4); or "no data" if that record was rejected, or is an edge value that would be "none" (5.1).

Rule 1 takes precedence over rule 2. Once a series is past its age limit, only a record whose own `time_tag` is within the age limit brings it out of "no data". A record that arrives later but whose time is still past the limit, for example a revised Kp value for the newest interval arriving after the limit has passed, or a delayed older record, goes into the history and leaves the current state at "no data"; it does not move the freshness reference forward either, unless its `time_tag` is newer.

A hole that is followed by a newer record does not change the current state; it stays "no data" in the history. A restatement of edge values (5.2, eclipse edge rule 5) changes only their history, since by then the freshness reference is a zero minute, which is already "no data".

**The Kp interval in progress is not a gap.** A record with `time_tag` T covers [T, T + 3 h) (Section 1.3), and SWPC publishes it only after the interval ends. At any moment the interval containing that moment has no value yet; it has no state, and is shown neither as "none" nor as "no data". The newest published interval is normally the one that ended last, and the one after it becomes due once it ends. **Observed** (source A): I read 361 of the 532 distinct captures made in 2026 (344 of the 355 taken 0 to 40 minutes after a 3 hour boundary, and 17 others; 2 were unreadable), covering 2026-01-01 to 2026-09-14. Of the 343 readable captures made 0 to 40 minutes after a boundary, 299 already held the interval that had just ended, the earliest 2.65 minutes after the boundary, and 44 did not, the latest 5.35 minutes after it. In all 359 readable captures, no file held an interval that had not ended, and the newest interval had ended at most 185.3 minutes (3 hours plus 5.3 minutes) before the capture. The captures on 2026-09-27 (Section 1.3) and the live fetch at 18:44:44Z on 2026-09-30 (newest `time_tag` 15:00:00, ended 18:00) fit the same pattern. So under normal operation the newest Kp `time_tag` is at most about 6 hours plus a few minutes old when SWPC's file is read.

**Cadence and delay per product.** How old the newest `time_tag` normally gets at the risk engine is the product's own delay plus the ingest poll interval (every 5 minutes, [ADR 0005](../adr/0005-swpc-polling-and-error-handling.md)), plus a retry of 1 to 2 minutes after a failed poll:

| Product | Cadence (documented) | Observed spacing and holes | Newest `time_tag` age when the file is read (observed) | Normal age at the risk engine |
|---|---|---|---|---|
| `swpc.kp` | "Kp values ... determined every 3 hours" (W7) | 10,800 s in all 61 records of `ingest/testdata/swpc/kp.json`; no hole | 3 h plus a few minutes up to 6 h plus a few minutes (above) | up to about 6 h 11 min; about 6 h 13 min after one failed poll |
| `swpc.goes.xrays` | "1-minute averages" (W3) | 60 s in all 358 tags of `ingest/testdata/swpc/goes-xrays-6-hour.json`; in the 7 day files 11 holes (GOES-18) and 12 (GOES-19) of 1 to 3 missing minutes, mostly at the same minutes on both satellites | 15 reads once a minute (source L): the file changed at 3 minute steps (`Last-Modified` 18:46:58, 18:49:58, 18:52:58, 18:55:58, 18:58:57) and then not again before the last read (`Date` 19:02:38), so one step was skipped or late; each change came with a newest `time_tag` 2 min 57 s to 2 min 58 s before it; newest `time_tag` age at read (server `Date` header) 4 min 20 s to 6 min 43 s | up to about 12 min; about 14 min after one failed poll; add up to 3 min when a hole sits at the head |
| `swpc.goes.protons` | "5-minute averaged" (W4) | 300 s in all 71 tags of `ingest/testdata/swpc/goes-integral-protons-6-hour.json`; in the 7 day files 5 holes on each satellite, at nearly the same times, of 1 to 3 missing tags (600 to 1,200 s between tags) | same 15 reads: the file changed every 3 minutes with no step missed (18:46:43 to 19:01:42), and each 5 minute value first appeared 6 min 42 s to 9 min 43 s after its `time_tag`; newest `time_tag` age at read 8 min 0 s to 15 min 10 s | up to about 20 min; about 22 min after one failed poll; add up to 15 min when a hole sits at the head |

The regeneration at 3 minute steps seen here differs from the "about once a minute" in [docs/source/swpc.md](../source/swpc.md), which was inferred from polls 5 minutes apart; 15 reads over 15 minutes on one afternoon do not show whether the period is fixed. The file is also served through a cache with `max-age=60`, which can add up to a minute.

**Age limits.** The age limit of each series is:

| Series | Age limit | Why |
|---|---|---|
| G (Kp) | 6 h 30 min | about 17 minutes above the normal maximum age with one failed poll |
| R (per satellite) | 20 min | covers the normal maximum age, one failed poll, and a 3 minute hole (the longest seen) at the head of the series |
| S (per satellite) | 40 min | covers the normal maximum age, one failed poll, and a hole of 3 missing tags (the longest seen) at the head |

A tight limit shows an outage sooner but turns ordinary holes and single failed polls into short "no data" spells, and so, with alerts sent per change of level, into extra events; a loose one shows an outage later. The alternatives considered, from the same measurements:

| Series | Tighter | Looser |
|---|---|---|
| G (Kp) | 6 h 15 min: above the normal age and one failed poll, little margin beyond that | 7 h |
| R | 15 min: above the normal age and one failed poll, but a 3 minute hole at the head can reach it | 30 min |
| S | 25 min: above the normal age and one failed poll, but a hole at the head can reach it | 60 min |

**Refresh events.** Alerts are sent per change of state, so a series that stays at the same level or at "none" sends nothing for hours. So that a reader can tell a quiet series from a silent one, a series also publishes refresh events carrying its current state and the record at its freshness reference, but only while it is current (not ended, 5.4) and its state is a level or "none". A series in "no data" publishes no refresh: its "no data" event already shows its state, and nothing newer is there to carry. A reader applies the same age limit to the carried `time_tag` that the risk engine applies; the risk engine's own state stays the authority.

1. **On arrival.** Whenever a poll brings newer records for a series in a level or "none" (its freshness reference moves forward), the series publishes a refresh, so the newest record reaches readers as soon as the risk engine has it.
2. **Timer fallback.** If no refresh and no state event went out within its interval for a series in a level or "none", the series publishes a refresh anyway, carrying the same freshness reference as before. Because the freshness reference is unchanged, a fallback refresh never changes whether a reader finds the series stale; it only shows that the risk engine is running and still watching the series while no newer records arrive. When the age limit is passed, the risk engine's "no data" event follows as usual, and refreshes stop.

| Series | Fallback interval | Time left under the age limit after the last fallback, in normal operation with one failed poll | Fallback refreshes per day per series, at most |
|---|---|---|---|
| G (Kp) | 15 min | about 2 min (6 h 13 min plus 15 min, against 6 h 30 min) | 96 |
| R (per satellite) | 5 min, the ingest poll interval | about 1 min (14 plus 5, against 20) | 288 |
| S (per satellite) | 15 min | about 3 min (22 plus 15, against 40) | 96 |

**Volume on arrival (one off measurement).** Replaying the committed quiet fixtures in 5 minute batches, as ingest polls, almost every poll brings newer records, so the on arrival rule gives about one refresh per poll per series: 286 to 290 a day for R from `ingest/testdata/swpc/goes-xrays-6-hour.json` (depending on where the polls fall against the minutes) and 288 a day for S from `ingest/testdata/swpc/goes-integral-protons-6-hour.json`, against a ceiling of 288 polls a day. For G a new Kp interval arrives about every 3 hours, so about 8 refreshes a day on arrival, plus up to 96 from the timer fallback. For R and S the fallback then rarely fires, since a refresh on arrival resets it.

The intervals are set as liveness signals, not to keep the freshness reference current: each is short enough that, while records flow normally, a reader hears from the risk engine at least once before the series could reach its age limit. That means at most the age limit minus the normal maximum age with one failed poll (5.3 table): about 17 minutes for G, 6 for R and 18 for S. In normal operation the fallback rarely fires for R and S, since each poll brings newer records; for G it fires about 11 times in each 3 hour interval between Kp values.

Considered instead: a timer only, at the same intervals, which carries the newest record up to one interval late; and a timer only at shorter intervals (G 10 min, R 3 min to match the X-ray file's regeneration steps, S 10 min), which leaves margins of about 7, 3 and 8 minutes for 144, 480 and 144 events per day per series. Either way, with a hole at the head of R (up to 3 more minutes) or S (up to 15 more), a timer alone can let a reader see the carried `time_tag` pass the limit shortly before the next refresh; refreshing on arrival avoids that whenever newer records exist.

When the ingest poller for a product stops (for example after a 404, ADR 0005), no new records arrive and every series of that product passes its age limit. That is the intended result: it reads as "no data", not as quiet and not as the last level held.

### 5.4 When SWPC changes the primary satellite

SpaceFlux polls only the `primary` GOES files (ADR 0005). So while the primary satellite is in eclipse, R has "no data" even when the secondary satellite is measuring; the secondary files are not polled, and this is accepted. W3 says `json/goes/instrument-sources.json` "provides the mapping of primary and secondary measurements from each instrument to the satellite from which that measurement is made".

**Observed:** `instrument-sources.json`, committed unmodified as [`risk-engine/src/test/resources/swpc-xrays/evidence/instrument-sources.json`](../../risk-engine/src/test/resources/swpc-xrays/evidence/instrument-sources.json) (fetched 2026-09-30, `Date` 19:19:20Z; the fetch at 18:45:16Z in source L returned the same bytes), lists GOES-19 as primary for `xrays` and `protons` from 2026-09-29T23:53:30Z and GOES-18 again from 2026-09-30T01:31:14Z. Yet the primary `xrays-7-day.json`, both as fetched at 18:44:06Z (source L) and as captured at 19:18:36Z (size and SHA256 in the same PROVENANCE file; only a subset is committed), holds satellite 18 in every one of its 10,063 minutes, including 23:53 to 01:31 (where GOES-18 has the zero run noted in 5.2), and the secondary file holds satellite 19 in every minute; the proton files are the same. So after the switch back, the primary file held GOES-18's series for its whole window, not a series pieced together from whichever satellite was primary at each minute. What the primary file held between 23:53 and 01:31 was not captured. The likely reading, not documented: a primary file carries the current primary satellite's series over its whole window, so when the primary changes, that satellite's records for the whole window (6 hours in the files SpaceFlux polls) appear at once and the previous satellite's records stop arriving.

Rules:

1. Series of different satellites are never merged (Section 2.3). A switch never makes one satellite's value the continuation of another's.
2. When records for a scale arrive from a satellite B and B's freshness reference (5.3) is at or after that of the series for satellite A, A's series is **ended**: its current state becomes "no data" from its freshness reference, with the reason that the satellite is no longer the one in SWPC's primary file. An ended series is no longer reported as current and is not checked against the age limit again, so it neither holds its last level nor sits in "no data" indefinitely. If records for A arrive again later (a switch back), the series resumes; its values in the span it was not primary are history like any others, and zeros among them are rejected by 5.1.
3. The current state of a scale is the current state of the series that is not ended and has the newest freshness reference (5.3), named with its satellite.
4. A series that stops with no other satellite taking over is not ended; it passes its age limit and reads as "no data" (5.3), since nothing shows that another satellite replaced it.
5. The records of the new satellite that arrive at once after a switch cover past hours. Only the newest of them sets the current state; the rest are history, carried with their own `time_tag`, and are not presented as new.

## 6. Test data needed for the thresholds

Every recorded SWPC fixture is from a quiet period (the recorded Kp maximum is 4.33, the long band X-ray maximum is about 1.2e-6 W m⁻², and T1 reports solar radiation "below S-scale storm level thresholds"), so none of them exercises a threshold. Two kinds of test data are needed:

1. **Boundary unit tests (rule tests, not fixtures).** For each scale and each level: a value just below the threshold, exactly at it, and just above it, plus the G scale cases 8.67 (G4, not G5) and 9.00 (G5), and 4.67 (G1) versus 4.33 (none). These test the comparison rules in this note and do not claim to be real data.
2. **Real storm period data with provenance**, at least one period per scale that reaches level 1 or higher, taken from a documented historical event or captured live when one occurs. The live SWPC files hold only a sliding window (about 7.5 days for Kp, 6 hours for the GOES files), so historical periods have to come from an archive, and a record whose shape differs from the live JSON (for example NCEI netCDF) must be converted by a committed script so the conversion is reproducible. Each such fixture records its source URL, capture date, and conversion, and says plainly that it is converted rather than recorded if so.
