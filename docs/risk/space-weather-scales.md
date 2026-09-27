# Space weather scales: mapping SWPC products to the NOAA G, R, and S scales

This note fixes how the risk engine turns three NOAA SWPC products into levels on the NOAA Space Weather Scales. Every threshold cites NOAA's published scale table or SWPC or NCEI product documentation. Points that rest on observation of the data rather than on documentation are labeled **observed**, with the evidence. Sources were accessed on 2026-09-27.

The levels SpaceFlux computes are derived mechanically from one physical measure each. NOAA's scale table says of each measure: "Based on this measure, but other physical measures are also considered." So a SpaceFlux level is labeled as derived from the named product, never presented as SWPC's own issued scale level, and SpaceFlux is not a space weather warning service.

## Sources

| # | Source | URL |
|---|---|---|
| W2 | SWPC, Planetary K-index product page | https://www.spaceweather.gov/products/planetary-k-index |
| W3 | SWPC, GOES X-ray Flux product page | https://www.spaceweather.gov/products/goes-x-ray-flux |
| W4 | SWPC, GOES Proton Flux product page | https://www.spaceweather.gov/products/goes-proton-flux |
| W7 | NOAA Space Weather Scales table (PDF, dated "December 11, 2023"), linked from https://www.spaceweather.gov/noaa-scales-explanation | https://www.spaceweather.gov/sites/default/files/images/NOAAscales.pdf |
| T1 | SWPC 3-Day Forecast text product, issue "2026 Sep 27 1230 UTC" (read 2026-09-27 about 20:33 UTC) | https://services.swpc.noaa.gov/text/3-day-forecast.txt |
| X1 | Machol, Mothersbaugh, Lucas, Mahon, "User's Guide for GOES-R XRS L2 Products", NOAA NCEI, 15 December 2025 | https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/docs/GOES-R_XRS_L2_Data_Users_Guide.pdf |
| X2 | Machol, Mothersbaugh, Lucas, Mahon, "Readme for GOES-R XRS L2 Data", NOAA NCEI, 15 December 2025 | https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/docs/GOES-R_XRS_L2_Data_ReadMe.pdf |
| F | Recorded SWPC fixtures in this repository, captured 2026-09-27T16:30:37Z, with provenance in `ingest/testdata/swpc/PROVENANCE.md` | [ingest/testdata/swpc](../../ingest/testdata/swpc/) |

Endpoints, shapes, and polling are documented in [docs/source/swpc.md](../source/swpc.md) and [ADR 0005](../adr/0005-swpc-polling-and-error-handling.md). All product URLs below are relative to `https://services.swpc.noaa.gov`.

## Summary

| Scale | Product | Records used | Field | Unit | Level 1 | Level 2 | Level 3 | Level 4 | Level 5 |
|---|---|---|---|---|---|---|---|---|---|
| G (geomagnetic storms) | `/products/noaa-planetary-k-index.json` | every record | `Kp` | Kp index | Kp ≥ 4.67 (5-) | Kp ≥ 5.67 (6-) | Kp ≥ 6.67 (7-) | Kp ≥ 7.67 (8-) | Kp ≥ 9.00 (9o) |
| R (radio blackouts) | `/json/goes/primary/xrays-6-hour.json` | `energy` equal to `"0.1-0.8nm"` | `flux` | W m⁻² | ≥ 1e-5 (M1) | ≥ 5e-5 (M5) | ≥ 1e-4 (X1) | ≥ 1e-3 (X10) | ≥ 2e-3 (X20) |
| S (solar radiation storms) | `/json/goes/primary/integral-protons-6-hour.json` | `energy` equal to `">=10 MeV"` | `flux` | pfu, protons/(cm² s sr) | ≥ 10 | ≥ 100 | ≥ 1,000 | ≥ 10,000 | ≥ 100,000 |

Below level 1 the derived level is "none". The G row applies the thirds reading in Section 1.2, which is a ruling on an ambiguity in the published table, not a direct quote.

## 1. G scale from planetary Kp

### 1.1 Thresholds

W7 lists the physical measure as "Kp values determined every 3 hours": G1 "Kp=5", G2 "Kp=6", G3 "Kp=7", G4 "Kp=8, including a 9-", G5 "Kp=9". W2 states the same mapping in its scales panel: "Kp = 5 (G1) Kp = 6 (G2) Kp = 7 (G3) Kp = 8, 9- (G4) Kp = 9o (G5)".

### 1.2 Kp in thirds, and how the decimal values map

Kp is reported in thirds, written with `-`, `o`, and `+` suffixes (W2 and W7 use "9-" and "9o"). **Observed:** every `Kp` value in the recorded fixture is a multiple of one third rounded to two decimals (the set is 0.0, 0.33, 0.67, 1.0, ..., 4.33), so 4.67 is "5-", 5.0 is "5o", and 5.33 is "5+".

The table does not say directly whether "Kp=5" includes 5- (4.67). I read it as including it, for two reasons:

1. W7 needs the words "including a 9-" to place 9- (8.67) in G4. That exception is only necessary if "Kp=9" would otherwise include 9-, which means "Kp=N" covers N-, No, and N+.
2. **Observed:** SWPC issued "ALERT: Geomagnetic K-index of 4" (`ALTK04`) for "Synoptic Period: 2100-2400" on 2026 Sep 25 and for "Synoptic Period: 0000-0300" on 2026 Sep 25, and the recorded Kp for both intervals (`time_tag` 2026-09-25T21:00:00 and 2026-09-25T00:00:00) is 3.67, that is 4-. SWPC treated a 4- interval as reaching K 4. (Alerts are issued in real time, so this is supporting evidence, not proof: the real time estimate could have been higher than the final value.)

Consequences, all as the "≥" comparisons in the Summary table: G1 from 4.67, G2 from 5.67, G3 from 6.67, G4 from 7.67, and G5 only at 9.00, since W2 and W7 both put 9- in G4. Comparisons use a tolerance of 0.005 so that 4.67 compares equal to the 4.67 threshold regardless of floating point representation; the data carries two decimals.

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

The comparisons are "≥". X1 section 2.2 ("Flare Magnitudes") defines the classes by the flux itself ("an M5 index is defined for a 5x10-5 W m-2 peak irradiance") and says "The flare index is defined by the truncated (not rounded) irradiance; e.g., a flare with peak irradiance of 4.19 × 10−5 W/m2 is an M4.1 flare". Truncation means a flux of exactly 1e-5 is M1.0, so it reaches R1.

### 2.2 Which field: `flux`, not `observed_flux`

**Ruling: the R scale reads `flux`.**

* X1 section 4 ("1-minute Averages Product"): "Three X-ray averaged irradiance values are reported for each channel: the uncorrected irradiance observed by the instrument (e.g., xrsb flux observed), the estimated electron contamination (e.g., xrsb flux electrons), and irradiance corrected for the electron contamination (e.g., xrsb flux). For most purposes, the corrected irradiance should be used." X2 section 2.2 describes the same three values and adds "The corrected flux has a minimum threshold of 10−9 W m−2."
* SWPC does not document its JSON keys, so the match between SWPC's `flux`, `observed_flux`, and `electron_correction` and NCEI's three variables rests on the names and on arithmetic. **Observed:** in all 358 `"0.1-0.8nm"` records of the fixture, `observed_flux` equals `flux + electron_correction` to a relative difference of at most 9.5e-8 (floating point rounding). In the `"0.05-0.4nm"` band the identity breaks by up to 0.9 percent, exactly where `flux` sits at its floor of about 1e-9, which matches X2's minimum threshold for the corrected flux. So `flux` is the corrected irradiance.
* For the R scale the choice rarely matters. X2 section 4 ("Data Caveats"), item 3: "The XRS irradiances are noticeably contaminated by electrons during periods where X-ray fluxes are low and electron fluxes are high. The impact is negligible in other conditions." **Observed:** the largest `electron_correction` in the fixture's long band is 5.8e-8 W m⁻², under 0.6 percent of the R1 threshold. Reading `flux` is still the documented choice.
* X1 section 2.2 also notes that GOES-R irradiances "are provided in true physical units of W m-2" without the older SWPC scaling factors, so no rescaling is applied.

### 2.3 Time and satellite

**Observed:** `time_tag` carries a `Z` suffix (UTC) and consecutive tags are 60 seconds apart, matching W3's "1-minute averages". X1 section 2.2 says the flare index "is based on the 1-minute average of the GOES operational irradiance in the XRS-B channel at the peak of the flare", so each 1 minute record is compared on its own. Whether a `time_tag` marks the start or the end of its minute is not documented, and nothing here depends on it at 1 minute resolution.

The primary satellite changes over time (W3 and [docs/source/swpc.md](../source/swpc.md)), so every level carries the `satellite` of the record that produced it, and values from different satellites are never merged into one series.

## 3. S scale from GOES integral protons

### 3.1 Thresholds and energy channel

W7, Solar Radiation Storms: the measure is "Flux level of > 10 MeV particles (ions)", footnoted "Flux levels are 5 minute averages. Flux in particles·s-1·ster-1·cm-2". S1 10, S2 10², S3 10³, S4 10⁴, S5 10⁵. W4: "The ≥10 MeV products match the NOAA Solar Radiation Storm (S-scale) thresholds (10, 100, 1000, 10000, 100000 pfu), based upon values observed or expected on the primary GOES satellite." Only records whose `energy` is `">=10 MeV"` are used.

The comparisons are "≥". W4: "Initial ALERTS for ≥10 MeV and ≥100 MeV energies are issued for integral flux reaching or exceeding 10 pfu and 1 pfu, respectively," and "Higher threshold ≥10 MeV ALERTS are also issued for threshold exceedance of 100, 1,000, 10,000, and 100,000 pfu, matching the thresholds described in the NOAA S-scale."

### 3.2 Time and satellite

**Observed:** `time_tag` carries a `Z` suffix and consecutive tags are 300 seconds apart, matching W7's "5 minute averages" and W4's "GOES 5-minute averaged integral proton fluxes". The satellite rule in 2.3 applies here too.

W4 also notes that "Because flux levels can drop slowly, the time of a 'confirmed' drop below threshold can sometimes take several hours to determine." SpaceFlux reports the level of each 5 minute value; it does not declare the end of an event.

## 4. Missing and invalid data

* A gap is not a quiet period. When a product has no record for an interval (outages happen; see [docs/source/swpc.md](../source/swpc.md)), the derived level for that interval is "no data", never "none".
* A value that is not finite, or is negative, is rejected with a reason and does not set a level.
* The derived level is "none" only when a valid value exists and is below the level 1 threshold.

## 5. Test data needed for the thresholds

Every recorded SWPC fixture is from a quiet period (the recorded Kp maximum is 4.33, the long band X-ray maximum is about 1.2e-6 W m⁻², and T1 reports solar radiation "below S-scale storm level thresholds"), so none of them exercises a threshold. Two kinds of test data are needed:

1. **Boundary unit tests (rule tests, not fixtures).** For each scale and each level: a value just below the threshold, exactly at it, and just above it, plus the G scale cases 8.67 (G4, not G5) and 9.00 (G5), and 4.67 (G1) versus 4.33 (none). These test the comparison rules in this note and do not claim to be real data.
2. **Real storm period data with provenance**, at least one period per scale that reaches level 1 or higher, taken from a documented historical event or captured live when one occurs. The live SWPC files hold only a sliding window (about 7.5 days for Kp, 6 hours for the GOES files), so historical periods have to come from an archive, and a record whose shape differs from the live JSON (for example NCEI netCDF) must be converted by a committed script so the conversion is reproducible. Each such fixture records its source URL, capture date, and conversion, and says plainly that it is converted rather than recorded if so.
