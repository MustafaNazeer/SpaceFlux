# Orbital conventions: SGP4, frames, time scales, and close approach screening

This note fixes the conventions the risk engine follows when it propagates CelesTrak GP element sets and screens a watchlist for close approaches. Every threshold, tolerance, and formula below cites the source it comes from, or is marked as a derivation with the reasoning shown. Sources were accessed on 2026-09-27 unless a row says otherwise.

SpaceFlux is a public data demonstration. Its close approach output is computed from public GP data with no covariance, and it is not an operational collision avoidance product. Section 5 explains what that means for how the numbers may be read.

## Sources

| # | Source | URL |
|---|---|---|
| O1 | Vallado, Crawford, Hujsak, Kelso, "Revisiting Spacetrack Report #3", AIAA 2006-6753, CelesTrak publication page (page stamp 2023 May 10) | https://celestrak.org/publications/AIAA/2006-6753/ |
| O2 | AIAA 2006-6753, Revision 3 of the paper (PDF linked from O1) | https://celestrak.org/publications/AIAA/2006-6753/AIAA-2006-6753-Rev3.pdf |
| O3 | "Notes and Change Summary for AIAA-2006-6753" (PDF linked from O1) | https://celestrak.org/publications/AIAA/2006-6753/AIAA-2006-6753-summary.pdf |
| O4 | Frequently Asked Questions for AIAA 2006-6753 (page stamp 2022 Jul 01) | https://celestrak.org/publications/AIAA/2006-6753/faq.php |
| O5 | Orekit 13.1.8, `TLETest.testSatCodeCompliance` (Apache License 2.0) | https://github.com/CS-SI/Orekit/blob/13.1.8/src/test/java/org/orekit/propagation/analytical/tle/TLETest.java |
| O6 | Orekit 13.1.8, `TLE`, `TLEPropagator`, `SGP4`, `DeepSDP4`, `TLEConstants` sources | https://github.com/CS-SI/Orekit/tree/13.1.8/src/main/java/org/orekit/propagation/analytical/tle |
| O7 | Orekit 13.1.8, `ExtremumApproachDetector`, `EventDetector`, `EventDetectionSettings` sources | https://github.com/CS-SI/Orekit/tree/13.1.8/src/main/java/org/orekit/propagation/events |
| O8 | Maven Central metadata for `org.orekit:orekit` (latest and release both 13.1.8, last updated 2026-08-30) | https://repo1.maven.org/maven2/org/orekit/orekit/maven-metadata.xml |
| C1 | CelesTrak, "A New Way to Obtain GP Data (aka TLEs)" (summarized in [docs/source/celestrak.md](../source/celestrak.md)) | https://celestrak.org/NORAD/documentation/gp-data-formats.php |
| C2 | CelesTrak SOCRATES Plus overview (page stamp 2023 Apr 05) | https://celestrak.org/SOCRATES/ |
| C3 | CelesTrak SOCRATES format documentation | https://celestrak.org/SOCRATES/socrates-format.php |
| H1 | 18 and 19 SDS, "Spaceflight Safety Handbook for Satellite Operators", Version 1.7, April 2023 | https://www.space-track.org/documents/SFS_Handbook_For_Operators_V1.7.pdf |
| F1 | Rivero and Bombardelli, "Short-Term Space Occupancy and Conjunction Filter", arXiv:2309.02379v2 (2024), Section I review of the apogee and perigee filter | https://arxiv.org/pdf/2309.02379 |
| F2 | Hoots, Crawford, Roehrich, "An analytic method to determine future close approaches between satellites", Celestial Mechanics 33 (1984). Cited through F1; I have not read the original. | https://link.springer.com/article/10.1007/BF01234152 |

The Orekit GitHub repository `CS-SI/Orekit` is the public mirror of the project's own GitLab (`gitlab.orekit.org/orekit/orekit`); tag `13.1.8` was read on 2026-09-27.

## 1. SGP4 reference test set and tolerance

### 1.1 Which cases

The reference set is the verification set published with AIAA 2006-6753.

* **Input file.** O2 Section V: "The file (sgp4-ver.tle) is on the Internet at the web site listed at the end of the paper, and in the Appendix." O2 Appendix D lists it as `SGP4-VER.TLE`: "Test cases include those used for the figures in the paper (with a keyword '## fig'), and those used for verification to exercise various aspects of the code. The additional values on the second line were added to simplify automatic processing of each test [...] the ephemeris starting minutes from epoch (MFE) to the ending MFE, and the delta time step in minutes. These values will not be in TLE's downloaded from the internet." A parser for the reference file must therefore strip the three trailing columns from line 2 before the line is checked as a standard TLE.
* **Expected results.** O2 Appendix E lists `TCPPVER.OUT`: "The results are given below for the verification TLE data in Appendix D. Note that this version includes the results of the Lyddane choice using the perturbed inclination as indicated in the GSFC code, and also uses the WGS-72 constants. [...] The coordinate system should be considered as TEME of date. [...] These test case results were run using the 'a', '72' options to best emulate AFSPC operation." Each row gives minutes from epoch, TEME position in km, and TEME velocity in km/s.
* **Case count.** The Appendix D listing in O2 holds 33 element sets. 29 of them have ephemeris results that a propagator can be checked against: catalog numbers 5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925, 20413, 21897, 22312, 22674, 23177, 23333, 23599, 24208, 25954, 26900, 26975, 28057, 28129, 28350, 28623, 28626, 28872, 29141, 29238, and 88888. Orekit's own compliance test (O5) uses exactly these 29 (`src/test/resources/tle/extrapolationTest-data/SatCode-entry` and `SatCode-results`). The remaining entries (33333, 33334, 33335, and a second 20413 entry) exist to exercise error handling (O2 Appendix D comments: "check error code 4", "try and check error code 2", "try to check error code 3").
* **Error and decay cases.** O2 Table 1 describes 28872 as a "Sub-orbital case (perigee -51 km, lost about 50 minutes from epoch) used to test error handling", and the Appendix D comment on 29141 describes the last stage of decay, with the object lost in under 420 minutes. The expected output for these cases is whatever rows `TCPPVER.OUT` contains before the propagator stops; the test compares only the rows present and asserts that propagation past the last row raises an error rather than returning a position.

### 1.2 Where the files come from, and the terms

* **Primary source.** The source code archive linked from O1 as "Source code (C++, C#, FORTRAN, Java, MATLAB, Pascal, 1,248,284 bytes)", at `https://celestrak.org/publications/AIAA/2006-6753/AIAA-2006-6753.zip`. A `HEAD` request on 2026-09-27 returned HTTP 200, `content-length: 1248284`, `last-modified: Wed, 10 May 2023 23:17:06 GMT`. I have not downloaded it yet, so the exact file names and paths inside the archive are not confirmed; O3 and O2 name `sgp4-ver.tle` and `tcppver.out` (the C++ driver in O2 opens `tcppver.out` for writing). When the files are fetched, their SHA256, the archive's `Last-Modified`, and the in archive paths are recorded in a provenance file next to them, the same way the feed fixtures are.
* **Terms.** O4, "Are there any Licenses required to use the SGP4 code?", answers "There is no license associated with the code" and says it may be used for any purpose, personal or commercial. It continues: "We ask only that you include citations in your documentation and source code to show the source of the code and provide links to the main page, to facilitate communications regarding any questions on the theory or source code." Committing the two files with that citation and a link to O1 satisfies the request. O4 speaks about "the SGP4 code"; it does not separately mention the test files, which ship in the same archive.
* **Fallback source.** Orekit's copies (O5 resource files) are under the Apache License 2.0, which requires keeping the license and notice when redistributing. They date from 2007 (the repository history for `SatCode-results` starts at a 2007-07-11 commit), and O3 records a November 2007 change ("the GHA (sidereal time) was set back to the older way of processing. This introduces sub-mm differences"). The CelesTrak archive is preferred because it is the authors' current publication.

### 1.3 Tolerance

For every row of every case with results:

| Quantity | Tolerance | Source |
|---|---|---|
| Position error, Euclidean norm of (expected minus computed) | 2 mm (0.002 m) | O5: `Assertions.assertEquals(0, normDifPos, 2e-3)` with positions in metres |
| Velocity error, Euclidean norm | 0.00001 m/s (1e-5 m/s) | O5: `Assertions.assertEquals(0, normDifVel, 1e-5)` |
| Sum of position errors over all rows | 0.026 m | O5: `Assertions.assertEquals(0, cumulated, 0.026)` |

Why this tolerance is meaningful:

* O2 Section IV: because SGP4 is analytical, "comparisons are relatively simple because the output should be the same from each program. Different programming languages (C++, FORTRAN, MATLAB, or Pascal) and compilers produced very small differences, but these were well below the accuracy of two-line element sets". The expected results are printed to 1e-8 km (0.01 mm) in position and 1e-9 km/s in velocity (O2 Appendix E), so 2 mm is two orders of magnitude above print precision and far below any physical meaning.
* O5 shows that Orekit 13.1.8's `TLEPropagator` already meets these values on the same 29 cases, so a failure at this tolerance means an input or unit mistake in SpaceFlux, not a limit of the library.
* If a case fails against the 2023 CelesTrak files but passes against Orekit's 2007 copy, the difference is the November 2007 change O3 describes. That is reported, not absorbed by loosening the tolerance.

Time handling in the test follows O5: the propagation date is the element set epoch shifted by `minutes_from_epoch * 60` seconds (`tle.getDate().shiftedBy(minFromStart * 60)`), so the comparison is on time since epoch and does not depend on calendar dates. O2 Appendix E notes that "The dates are adjusted for the leap second for satellite 20413, but this was done outside the SGP4 routine", which is why the calendar columns of `TCPPVER.OUT` are not used.

### 1.4 Constants and model selection

* **Gravity constants: WGS-72.** O2 Appendix E: the reference results use "the WGS-72 constants" and the "'72'" option. O4 recommends the same settings "to match the expected operation of the US Air Force Joint Space Operations Center". Orekit's `TLEConstants` (O6) states "This constants are used in the WGS-72 model, compliant with NORAD implementations" and sets `EARTH_RADIUS = 6378.135` km and `XKE = 0.0743669161331734132` with the comment `mu = 3.986008e+14`. SpaceFlux uses Orekit's constants unchanged and never substitutes WGS-84 values.
* **Near Earth versus deep space.** Orekit selects the model inside `TLEPropagator.selectExtrapolator` (O6): "Period >= 225 minutes is deep space", implemented as `2π / (n0 * 1440) >= 1/6.4` days, choosing `DeepSDP4` above and `SGP4` below. The risk engine always calls `selectExtrapolator` and never constructs `SGP4` or `DeepSDP4` directly.
* **Pinned library version.** Orekit 13.1.8 is both `latest` and `release` on Maven Central (O8, checked 2026-09-27). Every source reference in this note is to tag `13.1.8`.

## 2. Using Orekit with CelesTrak GP JSON

### 2.1 What a GP record means

CelesTrak's JSON follows CCSDS OMM keywords and omits mandatory fields whose values never change, which C1 names: "CENTER_NAME = EARTH, REF_FRAME = TEME, TIME_SYSTEM = UTC, MEAN_ELEMENT_THEORY = SGP4". So every GP record is:

* a set of **SGP4 mean elements** (not osculating elements, and not usable with any other propagator),
* whose `EPOCH` is in **UTC**,
* and whose propagated output is in **TEME**. O4: the SGP4 output is "Cartesian position and velocity versus Time Since TLE Epoch in the True Equator, Mean Equinox (TEME) coordinate system".

### 2.2 Building an Orekit `TLE` from a JSON record

The risk engine does not format text TLE lines. Catalog numbers above 99999 already occur (C1: "TLE formats will not support objects with catalog numbers above 99999"; the recorded stations group holds 100057 and 100712), so it builds the `TLE` object from fields with the constructor Orekit documents in O6:

```
TLE(int satelliteNumber, char classification, int launchYear, int launchNumber, String launchPiece,
    int ephemerisType, int elementNumber, AbsoluteDate epoch, double meanMotion,
    double meanMotionFirstDerivative, double meanMotionSecondDerivative, double e, double i,
    double pa, double raan, double meanAnomaly, int revolutionNumberAtEpoch, double bStar,
    TimeScale utc)
```

Field mapping and units (the units on the right are Orekit's, from the constructor's Javadoc in O6):

| JSON key | Orekit argument | Conversion | Orekit unit |
|---|---|---|---|
| `NORAD_CAT_ID` | `satelliteNumber` | as is | none |
| `CLASSIFICATION_TYPE` | `classification` | first character | none |
| `OBJECT_ID` (`yyyy-nnnP`) | `launchYear`, `launchNumber`, `launchPiece` | split on the hyphen: year, launch number, piece letters. C1 warns analyst objects may have no `OBJECT_ID`; see 2.4 | none |
| `EPHEMERIS_TYPE` | `ephemerisType` | as is | none |
| `ELEMENT_SET_NO` | `elementNumber` | as is | none |
| `EPOCH` | `epoch` | parsed as an ISO 8601 date in the UTC time scale, keeping all fractional digits | `AbsoluteDate` |
| `MEAN_MOTION` (rev/day) | `meanMotion` | multiply by π / 43200 | rad/s |
| `MEAN_MOTION_DOT` | `meanMotionFirstDerivative` | multiply by π / 1.86624e9 | rad/s² |
| `MEAN_MOTION_DDOT` | `meanMotionSecondDerivative` | multiply by π / 5.3747712e13 | rad/s³ |
| `ECCENTRICITY` | `e` | as is | none |
| `INCLINATION` (deg) | `i` | to radians | rad |
| `ARG_OF_PERICENTER` (deg) | `pa` | to radians | rad |
| `RA_OF_ASC_NODE` (deg) | `raan` | to radians | rad |
| `MEAN_ANOMALY` (deg) | `meanAnomaly` | to radians | rad |
| `REV_AT_EPOCH` | `revolutionNumberAtEpoch` | as is | none |
| `BSTAR` (1/earth radii) | `bStar` | as is | 1/earth radii |

Where the three mean motion factors come from: Orekit's own line parser (O6, `TLE(String, String, TimeScale)`) applies exactly these factors to the TLE text fields, with the comment "converted from rev/day, 2 * rev/day^2 and 6 * rev/day^3 to rad/s, rad/s^2 and rad/s^3". π / 43200 is 2π / 86400, one revolution per day in rad/s. Using the same factors means an object built from JSON and an object built from the equivalent text lines are the same object.

**The mean motion derivatives do not affect SGP4 output.** In Orekit 13.1.8 (O6), `TLEPropagator`, `SGP4`, `SDP4`, and `DeepSDP4` read only the epoch, mean motion, eccentricity, inclination, argument of perigee, right ascension of the ascending node, mean anomaly, and B* from the `TLE` (a search of those four files for `getMeanMotionFirstDerivative` and `getMeanMotionSecondDerivative` finds nothing). So the open question of whether CelesTrak's `MEAN_MOTION_DOT` carries the TLE convention (the first derivative already divided by 2, per O2 Appendix B, Figure 11: "The mean motion derivative is already divided by 2, and the second derivative is already divided by 6") or the full derivative cannot change a propagated position. The table above assumes the TLE convention, which only matters if the engine ever regenerates text lines.

### 2.3 Time scales and data

* The `TimeScale utc` argument is Orekit's UTC from the default data context, which needs Orekit's physical data (leap seconds) loaded at startup. A missing data set is a startup failure, never a silent default.
* Screening uses TEME directly (Section 3). Both objects in a pair are propagated by SGP4 and read out in the same TEME frame at the same `AbsoluteDate`, so relative distance and relative speed need no frame transformation. That keeps Earth orientation parameters out of the screening path. A conversion from TEME to an Earth fixed frame (ground tracks, latitude and longitude on the dashboard) is a separate concern; O2 Section II ("Program Interface Issues") says "We recommend converting TEME to a truly standard coordinate frame before interfacing with other external programs", and O4 notes that "AFSPC has never officially released a method detailing how the TEME coordinate frame is related to other official standard coordinate frames".

### 2.4 Validation before propagation

A GP record is rejected (dead lettered with a reason) rather than propagated when:

* `EPOCH` does not parse as a UTC date;
* `MEAN_MOTION` is not positive, or `ECCENTRICITY` is outside [0, 1). SGP4 is defined for elliptical orbits, and Orekit 13.1.8 raises `TOO_LARGE_ECCENTRICITY_FOR_PROPAGATION_MODEL` when eccentricity leaves the valid range during propagation (O6);
* any numeric field is not finite.

A propagation error at a requested time (decay, for example the 28872 and 29141 reference cases) ends that object's screening at the last good time and is reported as "not screened past T", the way SOCRATES lists "SGP4 Propagation Errors: A list of objects which were not screened because their GP data shows they have already decayed [...] or will decay prior to the end of the computation interval" (C3). A missing `OBJECT_ID` (C1 says analyst objects "typically will not have ... International Designator (OBJECT_ID)") does not block propagation; the launch fields are identification only and none of them reach SGP4 (Section 2.2).

## 3. Close approach screening

### 3.1 What is screened

Each watchlist object is screened against every other object in the ingested catalog. For each pair the engine reports: time of closest approach (TCA), miss distance at TCA, relative speed at TCA, and the element age of each object at TCA. These are the same quantities SOCRATES reports from public GP data with SGP4 (C3: "Min Range (km): The distance between the two conjuncting objects at the time of closest approach.", "Relative Speed (km/sec): Relative speed of the two conjuncting objects at the time of closest approach.", and "Days Since Epoch: The number of days from the epoch of the NORAD GP or SupGP (green) element set used to produce the prediction until the time of closest approach for the conjunction.").

### 3.2 Window

| Setting | Value | Source |
|---|---|---|
| Screening window | from the screening run's start time to 7 days later | C2: SOCRATES looks "for satellite conjunctions over the next seven days"; H1 Table 4, near Earth O/O ephemeris screening, "Period < 225min", propagation "7 days" |
| Regime covered | watchlist objects with period below 225 minutes (the near Earth regime) | H1 Table 4 regime boundary "Period < 225min"; O6 uses the same 225 minute boundary to switch to deep space |

A watchlist object in the deep space regime (period of 225 minutes or more) is rejected with a visible message rather than screened with near Earth settings. H1 Table 4 gives deep space a different window (10 days) and a different volume; supporting it is a separate decision.

### 3.3 Prefilter: radial band overlap

The apogee and perigee filter (F2 as described in F1) removes pairs whose radial distance ranges never overlap. F1: "It selectively excludes pairs of objects whose Earth-centric radii cannot intersect, determined by the minimal and maximal radial values derived from perigee and apogee calculations", and it warns that with mean elements "introducing an adjustable buffer zone is essential" because perturbations change perigee and apogee. F1 also records the variant used here: "selective sampling across all trajectories to ascertain the minimum and maximum radial distances of each one".

Rule, applied per object over the screening window:

1. Propagate with SGP4 on a fixed grid of step h and record the geocentric radius r(t) = |position(t)| at each grid point. Let r_min and r_max be the smallest and largest sampled values.
2. Widen the band by a sampling pad: p = (max over the grid of |dr/dt|) × h / 2, where dr/dt = (position · velocity) / |position| at each grid point. **Derivation:** if the true maximum of r lies at a time t* between grid points t_k and t_k+1, then t* is within h / 2 of one of them, so r(t*) is at most that grid value plus |dr/dt|max × h / 2, and therefore at most r_max plus p. The same argument bounds the true minimum from below by r_min minus p. Using the largest sampled |dr/dt| in place of the true maximum is an approximation, so p is a pad, not a proof; the brute force check in 3.6 is what confirms no pair is lost.
3. Keep the pair (A, B) only if the bands overlap within the report distance D: r_min,A minus p_A minus D ≤ r_max,B plus p_B, and r_min,B minus p_B minus D ≤ r_max,A plus p_A.

**Derivation of 3:** the distance between two points is at least the difference of their distances from Earth's center, |r_A minus r_B|. If the widened bands are more than D apart, no pair of positions at any time in the window can be within D, so dropping the pair cannot lose a conjunction inside D.

The grid step h is the same step as the fine search maximum check interval (3.4), so the samples are reused.

### 3.4 Fine search: time of closest approach

For each surviving pair, TCA is where the relative distance has a local minimum. With relative position Δr = r_B minus r_A and relative velocity Δv = v_B minus v_A (both in TEME at the same date), the derivative of |Δr|² is 2 Δr · Δv. So:

* **TCA:** a time where g(t) = Δr · Δv crosses zero from negative to positive (closing to opening). This is exactly the switching function of Orekit's `ExtremumApproachDetector` (O7): "The g is positive when the primary object is getting further away from the secondary object and is negative when it is getting closer to it", with `g = Vector3D.dotProduct(deltaPV.getPosition(), deltaPV.getVelocity())`. Closest approaches are its increasing events; O7's class documentation shows filtering to them with `EventSlopeFilter` and `FilterType.TRIGGER_ONLY_INCREASING_EVENTS`.
* **Miss distance:** |Δr(TCA)|.
* **Relative speed:** |Δv(TCA)|.

Detector settings:

| Setting | Value | Reason |
|---|---|---|
| Maximum check interval | 60 s | O7 (`EventDetector`): the propagator checks g "at least once every max check interval", and the interval "is therefore devoted to separate roots". Two roots of g closer together than the interval can be missed. Orekit's default is 600 s (`EventDetectionSettings.DEFAULT_MAX_CHECK = 600`), which is a large fraction of a 90 minute LEO orbit, so SpaceFlux uses a tighter value. 60 s is a working value confirmed or tightened by the brute force check in 3.6, not a published constant. |
| Convergence threshold | 1e-6 s | Orekit default, `EventDetectionSettings.DEFAULT_THRESHOLD = 1.e-6` (O7). At a relative speed of 16 km/s, a timing error of 1e-6 s moves the reported point by at most 0.016 m, far below the meaning of the result. |
| Maximum iterations | 100 | Orekit default, `DEFAULT_MAX_ITER = 100` (O7). |

O7 also warns that the secondary provider must be independent of the primary's propagator: "if the provider is a propagator, it should not be run together in a propagators parallelizer with the propagator this detector is registered in". Each pair therefore uses its own `TLEPropagator` instance for the secondary object.

### 3.5 Report threshold

| Setting | Value | Source |
|---|---|---|
| Report a close approach when | miss distance at TCA ≤ 5 km | C2: SOCRATES "is set to look for all conjunctions which are within 5 km at time of closest approach (TCA)", using public GP data and SGP4 |

5 km is chosen because SOCRATES is the published screening that uses the same inputs SpaceFlux has: public GP data, SGP4, and no covariance. Screening volumes and probability of collision thresholds from H1 (for example Table 5, near Earth: "TCA ≤ 3 days and Overall miss ≤ 1km and Probability of Collision ≥ e^-7" for a Space-Track CDM) apply to the 18 and 19 SDS High Accuracy Catalog and to operator ephemerides with covariance. SpaceFlux has neither, so it does not apply those criteria and does not compute a probability of collision.

### 3.6 Required checks before the screening code is accepted

1. **SGP4 compliance:** the 29 reference cases pass at the Section 1.3 tolerance.
2. **Brute force cross check:** on a recorded catalog fixture, a dense scan of |Δr| at 1 s steps over the full 7 day window, for every pair (the recorded stations group has 22 objects), finds no local minimum within D that the prefilter plus detector pipeline missed. Every TCA the pipeline reports matches the dense scan's minimum to within 1 s in time and to within the change of |Δr| over that second.
3. **Formula checks:** relative speed and miss distance computed independently from Δr and Δv at the reported TCA agree with the reported values, and g(TCA) is within the convergence threshold of zero.

### 3.7 Objects that share the watchlist object's orbit

The recorded stations group (captured 2026-09-27) includes ISS modules (POISK, ISS (NAUKA)) and docked vehicles (CREW DRAGON 12, CYGNUS NG-24, PROGRESS-MS 34, SOYUZ-MS 29, PROGRESS-MS 35) whose element sets place them on the ISS's orbit, with identical or near identical epochs. Screened naively, they appear as permanent conjunctions near zero range. GP data does not say whether an object is attached to another. How such pairs are excluded is an open decision; the screening output must never show a docked vehicle as a close approach.

## 4. Element age

O2 Appendix B: "In general, TLE data is accurate to about a kilometer or so at epoch and it quickly degrades (Hartman, 1993)." Every reported close approach therefore carries each object's element age at TCA (C3, "Days Since Epoch"), and the reported miss distance is shown with the understanding that its uncertainty is at least of the order of kilometres and grows with element age.

## 5. How these numbers may be read

* A reported close approach means "two public element sets, propagated with SGP4, come within 5 km of each other". It is not a collision probability and not a prediction that an operator would act on. C2 itself says that "the minimum distance method ignores position covariance information and can lead to an exaggerated assessment of the true risk".
* Miss distances are reported in kilometres with no more precision than the element accuracy supports.
* Nothing in SpaceFlux is an operational conjunction assessment or collision avoidance service.
