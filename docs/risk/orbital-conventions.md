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
| O9 | AIAA 2006-6753 source code archive `AIAA-2006-6753.zip` (Last-Modified 2023-05-10, downloaded 2026-09-27; SHA256 in the provenance file of Section 1.1) | https://celestrak.org/publications/AIAA/2006-6753/AIAA-2006-6753.zip |
| O10 | `sgp4/sgp4_CodeReadme.pdf` inside O9 ("Current version: February 20, 2017"), with dated revision notes | inside O9 |
| O11 | Reference SGP4 code inside O9: `sgp4/for/SGP4UNIT.FOR` and its driver `sgp4/for/TESTFOR.FOR` (which produced `tforverf.out`), and `sgp4/cpp/SGP4/SGP4/SGP4.cpp` | inside O9 |
| O12 | Orekit 13.1.8, `SatCode-results` (Apache License 2.0) | https://github.com/CS-SI/Orekit/blob/13.1.8/src/test/resources/tle/extrapolationTest-data/SatCode-results |
| C1 | CelesTrak, "A New Way to Obtain GP Data (aka TLEs)" (summarized in [docs/source/celestrak.md](../source/celestrak.md)) | https://celestrak.org/NORAD/documentation/gp-data-formats.php |
| C2 | CelesTrak SOCRATES Plus overview (page stamp 2023 Apr 05) | https://celestrak.org/SOCRATES/ |
| C3 | CelesTrak SOCRATES format documentation | https://celestrak.org/SOCRATES/socrates-format.php |
| H1 | 18 and 19 SDS, "Spaceflight Safety Handbook for Satellite Operators", Version 1.7, April 2023 | https://www.space-track.org/documents/SFS_Handbook_For_Operators_V1.7.pdf |
| F1 | Rivero and Bombardelli, "Short-Term Space Occupancy and Conjunction Filter", arXiv:2309.02379v2 (2024), Section I review of the apogee and perigee filter | https://arxiv.org/pdf/2309.02379 |
| K1 | FAI, "Statement about the Karman Line", 30 Nov 2018 (fai.org returned HTTP 403 to a direct request on 2026-09-27; read through the Internet Archive snapshot of 2025-01-04) | https://www.fai.org/news/statement-about-karman-line |
| F2 | Hoots, Crawford, Roehrich, "An analytic method to determine future close approaches between satellites", Celestial Mechanics 33 (1984). Cited through F1; I have not read the original. | https://link.springer.com/article/10.1007/BF01234152 |

The Orekit GitHub repository `CS-SI/Orekit` is the public mirror of the project's own GitLab (`gitlab.orekit.org/orekit/orekit`); tag `13.1.8` was read on 2026-09-27.

## 1. SGP4 reference test set and tolerance

**How the figures in Sections 1 and 2 are produced.** Every figure that can be recomputed from the committed reference files is printed by a committed test, and the text names the test. `ReferenceEvidenceTest` prints the evidence figures (run `./mvnw -B test -Dtest=ReferenceEvidenceTest` from the repository root and read the standard output) and fails the build if any printed line differs from the committed report in [`risk-engine/src/test/resources/evidence/reference-evidence.txt`](../../risk-engine/src/test/resources/evidence/reference-evidence.txt), so a library upgrade cannot leave these figures stale; and `Sgp4ComplianceTest` prints the summed compliance line. Figures that need files or programs that are not committed (the reference C++ code compiled and run, the archive's MATLAB and Java result files, Orekit's full `SatCode-results`) are marked **one off verification** with the method stated. They were checked once on 2026-09-27 and are not reproduced by the build.

### 1.1 Which files and which cases

The reference set is the verification set published with AIAA 2006-6753. The two files the tests read are committed under [`risk-engine/src/test/resources/sgp4/`](../../risk-engine/src/test/resources/sgp4/PROVENANCE.md), copied byte for byte from O9; the provenance file there records the archive's SHA256, `Last-Modified`, and the in archive path of each file.

* **Input file: `SGP4-VER.TLE`** (O9 path `sgp4/cpp/testsgp4/TestSGP4/SGP4-VER.TLE`). O2 Appendix D: "Test cases include those used for the figures in the paper (with a keyword '## fig'), and those used for verification to exercise various aspects of the code. The additional values on the second line were added to simplify automatic processing of each test [...] the ephemeris starting minutes from epoch (MFE) to the ending MFE, and the delta time step in minutes. These values will not be in TLE's downloaded from the internet." A parser strips those three trailing columns from line 2 before the line is read as a standard TLE. The file holds 33 element sets: 5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925, 20413, 21897, 22312, 22674, 23177, 23333, 23599, 24208, 25954, 26900, 26975, 28057, 28129, 28350, 28623, 28626, 28872, 29141, 29238, 88888, 33333, 33334, 33335, and 20413 a second time (same elements, span 1844000 to 1845100 minutes).
* **Expected results: `tforverf.out`** (O9 path `sgp4/for/tforverf.out`). The paper's own result file, `TCPPVER.OUT` (O2 Appendix E), is not in the archive: the C++ test driver writes it when run, and the archive ships none. The archive carries three consolidated result files in that layout instead: Fortran, MATLAB, and Java. O10 tells users to run in "a" (AFSPC) mode with the "72" (WGS-72) constants and compare with the MATLAB file `sgp4/mat/tmatverDec2015.out`. That file ends with a malformed final line (a row at 120 minutes followed by further rows with no line breaks) after the last case, so I use the Fortran file instead, which ends cleanly. Over every row the two share, the Fortran and MATLAB positions agree to within 0.082 mm (largest at catalog 23333, 360 minutes) and their velocities are identical as printed (one off verification: a row by row comparison of the two files from O9; the MATLAB file is not committed). The Fortran driver (O11, `TESTFOR.FOR`) runs in AFSPC mode (`opsmode = 'a'`) with WGS-72, so `tforverf.out` carries the same settings O2 Appendix E describes for `TCPPVER.OUT`: "These test case results were run using the 'a', '72' options to best emulate AFSPC operation", in "TEME of date". Each row gives minutes from epoch, TEME position in km, and TEME velocity in km/s; the columns after the seventh are the driver's own conversions and are not reference data.
* **The first row of every case is at 0 minutes** (the element set epoch), even when the requested span starts later (22312 requests 54.2028672 to 1440 minutes; its rows are 0, then 54.2028672 onward).
* **Pairing element sets with results.** The test reader pairs the 33 element sets with the 33 result blocks of `tforverf.out` by position in file order. It fails if the counts differ, and it checks that the catalog number in each block's header equals the element set's catalog number, so a missing or reordered block cannot pair a case with another case's rows.

### 1.2 What the test compares

| Cases | Compared against | Why |
|---|---|---|
| 28 cases: 5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925, 20413 (first entry only), 21897, 22312, 22674, 23177, 23333, 24208, 25954, 26900, 26975, 28057, 28129, 28350, 28623, 28626, 28872, 29141, 29238, 88888 | every row of that case in `tforverf.out`, 481 rows in all | the published reference |
| 23599 | Orekit's improved mode rows for 23599 (O12, committed as `orekit-satcode-results-23599.txt` with Orekit's license and notice), 37 rows; the test asserts that both this file and the 23599 block of `tforverf.out` hold 37 rows | known deviation, Section 1.4 |
| 33333, 33334, 33335, second 20413 entry | not compared | the file's error handling cases; see below |

These are the same 29 cases Orekit's own compliance test uses (O5, resource files `SatCode-entry` and `SatCode-results`). Four of the 28 stop early in `tforverf.out`, because the reference code reports an error at the next step:

| Catalog | Requested span (min) | Last published row (min) | Reference code at the next step |
|---|---|---|---|
| 22312 | 54.2028672 to 1440 | 474.2028672 | error 1 at 494.2028672 (mean eccentricity out of range) |
| 28350 | 0 to 2880 | 1440 | error 1 at 1560 |
| 28872 | 0 to 60 | 50 | error 6 at 55 (decayed, radius below one Earth radius) |
| 29141 | 0 to 440 | 420 | error 6 at 440 |

The error codes and their conditions are from O11 (`SGP4.cpp`, where `satrec.error` is set). The step at which each case fails is a one off verification: the unmodified O11 C++ code, compiled and run on `SGP4-VER.TLE` in "a" mode, returns those errors at those steps. The build does not rerun it. O2 Table 1 describes 28872 as a "Sub-orbital case (perigee -51 km, lost about 50 minutes from epoch) used to test error handling", and the `SGP4-VER.TLE` comment on 29141 reads "Last stage of decay - lost in under 420 min". The test compares every published row of these four cases, and those rows agree with Orekit at the tolerance below.

The four cases left out (the Orekit differences below are printed by `ReferenceEvidenceTest`; the reference code's error steps are the same one off verification as above):

* **33333** ("check error code 4"): rows 0 to 20 minutes, then the reference code stops with error 4 at 25 minutes. Orekit's state agrees at 0 minutes (0.000 km) but differs by 49.855 km at 5 minutes and by 3398.272 km at 20 minutes, so the rows cannot serve as a reference for Orekit.
* **33334** ("try and check error code 2"): the reference code fails while initializing (error 3). Its single 0 minute row in `tforverf.out` is byte for byte the last row of 33333 (the driver printed a stale state), so it is not a propagated state and is never compared.
* **33335** ("try to check error code 3"): 73 rows, 0 to 1440 minutes, no error. Orekit differs from these rows by 44.2 m at epoch and 50.6 m at most. I have not traced the cause, so the case is left out rather than explained.
* **20413, second entry**: rows at 0 and then 1844000 to 1844340 minutes; the reference code stops with error 6 at 1844345. Orekit agrees at 0 minutes and differs by 27924 km at 1844000 minutes, so the rows are not a reference for Orekit.

**Past the last published row the test asserts nothing about positions.** It compares only rows that exist. Orekit 13.1.8 does not raise an error where the reference code does: its only runtime check is `TOO_LARGE_ECCENTRICITY_FOR_PROPAGATION_MODEL` when eccentricity exceeds 1 minus 1e-6 (`TLEPropagator.computePVCoordinates`, O6), and `SGP4` clamps a small eccentricity up to 1e-6 rather than failing. Run at the steps in the table above, Orekit returns a state every time (printed by `ReferenceEvidenceTest`): for 28872 at 55 minutes the state is at an altitude of minus 24.5 km and for 29141 at 440 minutes at minus 23.9 km, that is below the 6378.135 km equatorial radius, and for 33333 at 25 minutes the position is NaN with no exception. For 33334 Orekit returns a NaN position at every 5 minute step from 0 to 1440 minutes with no exception (printed by `ReferenceEvidenceTest`). Detecting decay is therefore the engine's job, not Orekit's (Section 2.4), and it is tested there. The compliance test does record this library behavior so an Orekit upgrade that changes it is noticed: at the first failing step of 22312, 28350, 28872, and 29141 it asserts that Orekit returns a finite state without an exception.

### 1.3 Tolerance

| Quantity | Tolerance | Applies to | Source |
|---|---|---|---|
| Position error per row, Euclidean norm of (expected minus computed) | 2 mm (0.002 m) | every compared row, the 28 cases and 23599 | O5: `Assertions.assertEquals(0, normDifPos, 2e-3)` with positions in metres |
| Velocity error per row, Euclidean norm | 1e-5 m/s | every compared row, the 28 cases and 23599 | O5: `Assertions.assertEquals(0, normDifVel, 1e-5)` |
| Sum of position errors over rows | 0.026 m | the 481 rows of the 28 cases, against `tforverf.out` | O5: `Assertions.assertEquals(0, cumulated, 0.026)`, kept unchanged; basis below |

**Basis for keeping 0.026 m.** Reproduced on every run: `Sgp4ComplianceTest` prints `SGP4 compliance: 481 rows, summed position error 0.0227 m, largest row 1.974 mm` for Orekit 13.1.8 against `tforverf.out` over the 28 cases (propagated from the GP JSON path, which builds the same element set as the text path, Section 2.2). The comparison with Orekit's own test is a one off verification against Orekit's full `SatCode-results` (O12), which is not committed except for its 23599 rows: there the bound covers all 29 cases, and Orekit 13.1.8's summed error is 0.0230 m, largest single row 1.974 mm at catalog 26900, 9360 minutes. In the same one off comparison, `tforverf.out` and Orekit's reference differ by at most 0.081 mm in any row of the 28 cases and 0.33 mm summed, so dropping 23599 and swapping the reference file moves the sum very little. I keep O5's value because it is the published bound for this library and the rescoped sum sits under it with the same margin Orekit's own test has.

The largest row is 0.026 mm under the 2 mm per row limit and falls at 26900, 9360 minutes (`ReferenceEvidenceTest` prints the case and time). In the one off comparison above, the largest row against Orekit's own reference is the same size at the same row, so this margin is a property of the library on that case, not of SpaceFlux's input handling.

Why this tolerance is meaningful:

* O2 Section IV: because SGP4 is analytical, "comparisons are relatively simple because the output should be the same from each program. Different programming languages (C++, FORTRAN, MATLAB, or Pascal) and compilers produced very small differences, but these were well below the accuracy of two-line element sets". The results are printed to 1e-8 km (0.01 mm) in position and 1e-9 km/s in velocity, so 2 mm is two orders of magnitude above print precision and far below any physical meaning.
* A failure at this tolerance on the 28 cases means an input or unit mistake in SpaceFlux, not a limit of the library.

Time handling follows O5: the propagation date is the element set epoch shifted by `minutes_from_epoch * 60` seconds (`tle.getDate().shiftedBy(minFromStart * 60)`), so the comparison is on time since epoch and does not depend on calendar dates. O2 Appendix E notes that "The dates are adjusted for the leap second for satellite 20413, but this was done outside the SGP4 routine", which is why the calendar columns of the result file are not used.

The test runs every compared case twice at the same per row tolerance, 23599 against its improved mode rows included: once from the text element set, and once through the GP JSON path of Section 2.2.

### 1.4 Known deviation: catalog 23599, AFSPC mode versus Orekit

**What differs.** For 23599 (a deep space object, period about 322 minutes, inclination 6.93 degrees, whose `SGP4-VER.TLE` comment reads "Lyddane bug at > 280.5 min for AcTan()"), Orekit 13.1.8 matches the AFSPC mode rows of `tforverf.out` within 2 mm from 0 to 400 minutes and differs from them at every row from 420 to 720 minutes, the last row, while it matches Orekit's improved mode rows (O12) within 2 mm at all 37 rows (both asserted by `Sgp4ComplianceTest`). From 420 minutes on, the position difference from the AFSPC rows runs from 260.36 m (at 620 minutes) to 963.99 m (at 460 minutes), and the largest velocity difference is 0.3892 m/s (at 620 minutes), as printed by `ReferenceEvidenceTest`.

**Why.** The archive's revision notes (O10, entry dated 2007-11-01) record changes "to make the application more compatible with the AFPSC implementation". One is the sidereal time change, which "introduces sub-mm differences". The other is separate: "there were a few operations where the original code had used user written functions (mod, atan2). These functions returned certain quadrants and in some places in the code, the results were used without a trigonometric argument. [...] simple conditional statements were inserted to simulate the intended behavior." The 2008-09-03 entry then added "two modes of operation", one emulating AFSPC and one "improved". In the deep space periodics routine `dpper`, in the Lyddane branch used when the perturbed inclination is below 0.2 rad (O11, `SGP4UNIT.FOR` and `SGP4.cpp`), those conditionals read `if ((nodep < 0.0) && (opsmode == 'a')) nodep = nodep + twopi;`. The wrapped node then enters `xls` and `dls` directly, not through a sine or cosine, so the wrap changes the result. Orekit 13.1.8 (O6, `DeepSDP4`, the "Apply periodics with Lyddane modification" block) never wraps the node that way: it normalizes only the node increment, `MathUtils.normalizeAngle(FastMath.atan2(alfdp, betdp) - xnode, 0)`, which matches improved mode "i". Orekit's reference file predates the 2007 revision (Section 1.5).

**Evidence that this is the whole cause (one off verification).** I compiled the reference C++ code (O11, `SGP4.cpp`, unmodified, called from a small verification mode driver of my own) and ran it on `SGP4-VER.TLE` in both modes. In mode "a" it matches every propagated row of `tforverf.out` to within 0.081 mm, 23599 included. In mode "i" it matches Orekit's 23599 rows exactly as printed, and differs from `tforverf.out` only on 23599, by the same 964 m. The archive's Java result file (`sgp4/java/JAVA_SGP4_v2/java_sgp4_ver.out`, not committed) also matches Orekit's 23599 rows exactly. So the 23599 difference is the AFSPC node wrap and nothing else. The driver, the compiled runs and the Java file are not part of the build; what the build does reproduce is the size and timing of the deviation above.

**What the test does.** SpaceFlux uses Orekit unchanged and does not patch it to emulate mode "a". The test therefore:

1. asserts that both the committed improved mode file and the 23599 block of `tforverf.out` hold 37 rows;
2. asserts 23599 against Orekit's improved mode rows at the Section 1.3 per row tolerance (2 mm, 1e-5 m/s), all 37 rows, once from the text element set and once through the GP JSON path;
3. asserts against the AFSPC rows in `tforverf.out` (through the GP JSON path): rows 0 to 400 minutes within 2 mm, and rows 420 to 720 minutes each more than 2 mm and less than 1 km away. The 1 km ceiling is the measured largest difference, 963.99 m, rounded up to the next whole kilometre; it is there so that a change in the size of the deviation (an Orekit upgrade, or a change in how SpaceFlux builds the element set) fails the test instead of passing silently.

23599 is not part of the summed check.

**What it means for screening.** See Section 3.2.

### 1.5 Where the files come from, and the terms

* **Primary source.** O9, linked from O1 as "Source code (C++, C#, FORTRAN, Java, MATLAB, Pascal, 1,248,284 bytes)". It was downloaded on 2026-09-27 (HTTP 200, `last-modified: Wed, 10 May 2023 23:17:06 GMT`, SHA256 `3642043b706c76be87cf012db3f22e04da6b80498d00f515e51879e0ffadc115`), and the two files were extracted unmodified. The archive's three copies of `SGP4-VER.TLE` are identical.
* **Terms.** O4, "Are there any Licenses required to use the SGP4 code?", answers "There is no license associated with the code" and says it may be used for any purpose, personal or commercial. It continues: "We ask only that you include citations in your documentation and source code to show the source of the code and provide links to the main page, to facilitate communications regarding any questions on the theory or source code." The provenance file carries that citation and the link to O1. O4 speaks about "the SGP4 code"; it does not separately mention the test files, which ship in the same archive, and the archive has no license file of its own.
* **Orekit's reference rows.** O12 is under the Apache License 2.0, which requires keeping the license and notice when redistributing; the committed 23599 rows sit next to Orekit's `LICENSE` and `NOTICE`. Orekit's `SatCode-results` dates from 2007 (its repository history starts at a 2007-07-11 commit), before the 2007-11-01 revision described in 1.4. On the 28 compared cases it agrees with `tforverf.out` to within 0.081 mm per row (one off verification, Section 1.3), a sub-mm difference consistent with what O10 says of the sidereal time part of that revision; on 23599 it differs by up to 964 m because of the node wrap part.

### 1.6 Constants and model selection

* **Gravity constants: WGS-72.** O2 Appendix E: the reference results use "the WGS-72 constants" and the "'72'" option; O10 gives the same instruction. O4 recommends the same settings "to match the expected operation of the US Air Force Joint Space Operations Center". Orekit's `TLEConstants` (O6) states "This constants are used in the WGS-72 model, compliant with NORAD implementations" and sets `EARTH_RADIUS = 6378.135` km and `XKE = 0.0743669161331734132` with the comment `mu = 3.986008e+14`. SpaceFlux uses Orekit's constants unchanged and never substitutes WGS-84 values.
* **Operation mode.** O10 says the default mode is "a" and describes "i" as a mode "that uses improved processing techniques that AFSPC may not be using". Orekit's deep space periodics behave like "i" (Section 1.4). SpaceFlux accepts that and documents it rather than modifying the library.
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
| `CLASSIFICATION_TYPE` | `classification` | the value's single character; an empty or longer value is rejected | none |
| `OBJECT_ID` (`yyyy-nnnP`) | `launchYear`, `launchNumber`, `launchPiece` | must match `yyyy-nnnP` (four digit year, three digit launch number, one to three piece letters); split into year, launch number, piece letters. A missing `OBJECT_ID` (C1 warns analyst objects may have none) and a malformed one are treated the same way: launch year 0, launch number 0, empty piece; see 2.4 | none |
| `EPHEMERIS_TYPE` | `ephemerisType` | as is | none |
| `ELEMENT_SET_NO` | `elementNumber` | as is | none |
| `EPOCH` | `epoch` | parsed as an ISO 8601 date in the UTC time scale, keeping all fractional digits | `AbsoluteDate` |
| `MEAN_MOTION` (rev/day) | `meanMotion` | multiply by π / 43200 | rad/s |
| `MEAN_MOTION_DOT` | `meanMotionFirstDerivative` | multiply by π / 1.86624e9 | rad/s² |
| `MEAN_MOTION_DDOT` | `meanMotionSecondDerivative` | multiply by π / 5.3747712e13 | rad/s³ |
| `ECCENTRICITY` | `e` | as is | none |
| `INCLINATION` (deg) | `i` | to radians with `FastMath.toRadians` | rad |
| `ARG_OF_PERICENTER` (deg) | `pa` | to radians with `FastMath.toRadians` | rad |
| `RA_OF_ASC_NODE` (deg) | `raan` | to radians with `FastMath.toRadians` | rad |
| `MEAN_ANOMALY` (deg) | `meanAnomaly` | to radians with `FastMath.toRadians` | rad |
| `REV_AT_EPOCH` | `revolutionNumberAtEpoch` | as is | none |
| `BSTAR` (1/earth radii) | `bStar` | as is | 1/earth radii |

Where the three mean motion factors come from: Orekit's own line parser (O6, `TLE(String, String, TimeScale)`) applies exactly these factors to the TLE text fields, with the comment "converted from rev/day, 2 * rev/day^2 and 6 * rev/day^3 to rad/s, rad/s^2 and rad/s^3". π / 43200 is 2π / 86400, one revolution per day in rad/s. The same parser converts the four angles with `FastMath.toRadians`, and the JSON path uses that same function. Using the same factors and the same function means an object built from JSON and an object built from the equivalent text lines are the same object. `GpElementSetsTest` checks this for all 33 element sets of `SGP4-VER.TLE`: each is written out as a GP JSON record, built through the JSON path, and asserted equal to Orekit's parse of the text lines in the epoch and in every element (mean motion and both derivatives, eccentricity, inclination, right ascension of the ascending node, argument of perigee, mean anomaly, B*), compared as exact double values, not within a tolerance.

**Blank international designators.** Two reference element sets, 11801 and 88888, have a blank designator field in line 1. Orekit's text parser reads the blank year as 2000; the JSON path, which receives no `OBJECT_ID`, gives launch year 0 (both give launch number 0 and an empty piece). `ReferenceEvidenceTest` prints both launch years for the two element sets. The launch fields are identification only and never reach SGP4 (see the next paragraph), so this difference cannot change a propagated state, and the equality check above leaves the launch fields out.

**The mean motion derivatives do not affect SGP4 output.** In Orekit 13.1.8 (O6), `TLEPropagator`, `SGP4`, `SDP4`, and `DeepSDP4` read only the epoch, mean motion, eccentricity, inclination, argument of perigee, right ascension of the ascending node, mean anomaly, and B* from the `TLE` (a search of those four files for `getMeanMotionFirstDerivative` and `getMeanMotionSecondDerivative` finds nothing). So the open question of whether CelesTrak's `MEAN_MOTION_DOT` carries the TLE convention (the first derivative already divided by 2, per O2 Appendix B, Figure 11: "The mean motion derivative is already divided by 2, and the second derivative is already divided by 6") or the full derivative cannot change a propagated position. The table above assumes the TLE convention, which only matters if the engine ever regenerates text lines.

### 2.3 Time scales and data

* The `TimeScale utc` argument is Orekit's UTC from the default data context, which needs the leap second table. The risk engine loads that table from its own classpath and builds the UTC time scale as a Spring bean when the application starts, so a missing table fails startup rather than the first screening run, never a silent default.
* Screening uses TEME directly (Section 3). Both objects in a pair are propagated by SGP4 and read out in the same TEME frame at the same `AbsoluteDate`, so relative distance and relative speed need no frame transformation. That keeps Earth orientation parameters out of the screening path. A conversion from TEME to an Earth fixed frame (ground tracks, latitude and longitude on the dashboard) is a separate concern; O2 Section II ("Program Interface Issues") says "We recommend converting TEME to a truly standard coordinate frame before interfacing with other external programs", and O4 notes that "AFSPC has never officially released a method detailing how the TEME coordinate frame is related to other official standard coordinate frames".

### 2.4 Validation before propagation

A GP record is rejected (dead lettered with a reason) rather than propagated when:

* `EPOCH` does not parse as a UTC date;
* `MEAN_MOTION` is not positive, or `ECCENTRICITY` is outside [0, 1). SGP4 is defined for elliptical orbits, and Orekit 13.1.8 raises `TOO_LARGE_ECCENTRICITY_FOR_PROPAGATION_MODEL` when eccentricity leaves the valid range during propagation (O6);
* any numeric field is not finite;
* a field the constructor needs is missing, null, or of the wrong JSON type (an integer field that is not an integer, `CLASSIFICATION_TYPE` that is not a one character string), or `NORAD_CAT_ID` is not positive.

Orekit 13.1.8 does not report decay (Section 1.2). Where the reference code stops with an error, Orekit returns a state (printed by `ReferenceEvidenceTest`): below the Earth's surface for 28872 and 29141, still above it for 22312 and 28350 (geocentric radius 6449.0 km and 6431.2 km, altitude 70.8 km and 53.1 km, at the step where the reference code fails), and a NaN position with no exception for 33333 at 25 minutes (33334 at every 5 minute step from 0 to 1440 minutes, also printed by `ReferenceEvidenceTest`). So the engine checks every propagated state itself. A state is a propagation error when:

* propagation throws any runtime exception, not only Orekit's own `OrekitException`, or any coordinate of position or velocity is not finite; or
* the object has decayed: its geocentric radius is below 6378.135 km (the WGS-72 radius in Orekit's `TLEConstants`, O6) plus an 80 km altitude floor, that is below 6458.135 km.

**The 80 km floor is a convention, not a physical boundary.** None of the sources above publishes an altitude at which an SGP4 element set should be treated as re entering, so the floor is a choice, and I say so. K1 gives the two candidate figures: "The Karman line is the 100km altitude used by FAI and many other organisations", and "Recently published analyses present a compelling scientific case for reduction in this altitude from 100km to 80km", analyses that include "perigee/apogee elliptical analysis of actual satellite orbital lifetimes". FAI still uses 100 km; 80 km is the figure those analyses argue for, not an adopted FAI boundary. I use 80 km because the reference cases rule out 100 km (below). A radius test alone (the reference code's error 6) is not enough: it misses 22312 and 28350, which the reference code stops with error 1 while still above the surface.

**Why not 100 km.** 16925 is a deep space rocket body whose `SGP4-VER.TLE` comment gives a perigee of 82.48 km, and the reference code propagates it to 1440 minutes without an error. Sampled every 10 s, Orekit takes it below 100 km on its perigee passes, first at 254.7 minutes, lowest 94.6 km at 255.3 minutes (printed by `ReferenceEvidenceTest`). A 100 km floor would therefore stop a case the reference code treats as valid. It never goes below 80 km.

**Checked against the reference cases.** Figures printed by `ReferenceEvidenceTest`, altitude being geocentric radius minus 6378.135 km.

* *Cases that run to their end.* In the 25 compared cases that the reference code propagates to their last row (the 24 cases of Section 1.2 that do not stop early, plus 23599), no published row is below 155.3 km (16925 at 840 minutes). Sampled every 10 s over each case's published span, 16925's lowest altitude is 94.6 km and no other of the 25 goes below 123.5 km (28623). `Sgp4PropagatorTest` asserts that the engine's decay check never stops any of the 25 at any 10 s step of its published span.
* *Cases that stop early.* No altitude threshold reproduces where the reference code stops: 22312's last published row (474.2 minutes) is at 70.5 km, and 28350 dips to 59.6 km (1320 minutes) and publishes its last row at 65.7 km (1440 minutes), both already below the floor. The floor is therefore conservative for these: it ends screening before the reference code stops, never after. Sampled every 10 s, each first goes below 80 km at 459.8 minutes (22312, reference error at 494.2), 1050.2 minutes (28350, 1560), 44.2 minutes (28872, 55), and 377.2 minutes (29141, 440). `Sgp4PropagatorTest` asserts that the engine reports decay at each of the four reference error steps.
* *The floor itself.* `Sgp4PropagatorTest` pins the value with 28350: at 1200 minutes it is 79.9 km and the engine stops (the test checks the reported altitude), and at 1080 minutes it is 81.03 km (printed by `ReferenceEvidenceTest`) and the engine does not stop. Lowering the floor below that 79.9 km state, or raising it above the altitude of the 1080 minute state, fails one of the two.

**The floor check is per date, and the screening loop must latch it.** The engine's check answers only whether the state at one requested date is usable. It keeps no memory: a date after a failing date can pass again, because altitude along an SGP4 trajectory is not monotonic. 28350 shows this in the committed tests: it first goes below 80 km at 1050.2 minutes, yet at 1080 minutes it is above the floor and the check passes. Dips below a floor can also be short. On its perigee passes 16925 stays below 100 km for 41 to 90 s at a time (one off verification: Orekit sampled every 1 s over its published span; among the cases that run to their end it is the only one that goes below 100 km, and it never goes below 80 km). Two rules follow for the screening code, and are settled when that code is written: the screening loop, not the per date check, owns the "not screened past T" latch, so the first failing date ends the object's screening even if later dates pass; and its sample step must be short enough to resolve a dip of about a minute, which the 60 s grid of Section 3.3 does not guarantee on its own.

A propagation error at a requested time ends that object's screening at the last good time and is reported as "not screened past T", the way SOCRATES lists "SGP4 Propagation Errors: A list of objects which were not screened because their GP data shows they have already decayed [...] or will decay prior to the end of the computation interval" (C3). A missing or malformed `OBJECT_ID` (C1 says analyst objects "typically will not have ... International Designator (OBJECT_ID)") does not block propagation: the launch fields are set to year 0, number 0 and an empty piece, they are identification only, and none of them reach SGP4 (Section 2.2).

## 3. Close approach screening

### 3.1 What is screened

Each watchlist object is screened against every other object in the ingested catalog. For each pair the engine reports: time of closest approach (TCA), miss distance at TCA, relative speed at TCA, and the element age of each object at TCA. These are the same quantities SOCRATES reports from public GP data with SGP4 (C3: "Min Range (km): The distance between the two conjuncting objects at the time of closest approach.", "Relative Speed (km/sec): Relative speed of the two conjuncting objects at the time of closest approach.", and "Days Since Epoch: The number of days from the epoch of the NORAD GP or SupGP (green) element set used to produce the prediction until the time of closest approach for the conjunction.").

### 3.2 Window

| Setting | Value | Source |
|---|---|---|
| Screening window | from the screening run's start time to 7 days later | C2: SOCRATES looks "for satellite conjunctions over the next seven days"; H1 Table 4, near Earth O/O ephemeris screening, "Period < 225min", propagation "7 days" |
| Regime covered | watchlist objects with period below 225 minutes (the near Earth regime) | H1 Table 4 regime boundary "Period < 225min"; O6 uses the same 225 minute boundary to switch to deep space |

A watchlist object in the deep space regime (period of 225 minutes or more) is rejected with a visible message rather than screened with near Earth settings. H1 Table 4 gives deep space a different window (10 days) and a different volume; supporting it is a separate decision.

**Deep space catalog objects still reach screening.** Only the watchlist side is limited to the near Earth regime. The catalog objects a watchlist object is screened against can be deep space objects, for example a rocket body in a highly elliptical transfer orbit whose perigee passes through low Earth orbit. Orekit propagates those with `DeepSDP4`, so the Section 1.4 deviation can reach a reported miss distance. It applies to deep space objects whose perturbed inclination is below 0.2 rad (about 11.46 degrees), from the time their secularly propagated node, which starts between 0 and 360 degrees at epoch, drifts below zero. For 23599 (node 0.28 degrees at epoch) the two results start to differ between 400 and 420 minutes. The only measured instance is the reference case, where Orekit's position differs from the AFSPC mode reference by up to 964 m (963.99 m, printed by `ReferenceEvidenceTest`) within 12 hours of epoch. I have not bounded it for other objects or longer spans, so 964 m is an observed size, not a limit. That is the same order as the element accuracy Section 4 cites ("about a kilometer or so at epoch") and below the 5 km report threshold, but for a pair close to 5 km it can decide whether the pair is reported. SpaceFlux documents this deviation and does not correct it: the engine uses Orekit unchanged.

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

1. **SGP4 compliance:** the 28 reference cases pass against `tforverf.out`, and 23599 passes against Orekit's improved mode rows, at the Section 1.3 tolerances, with the 23599 deviation from the AFSPC rows inside the Section 1.4 bounds.
2. **Brute force cross check:** on a recorded catalog fixture, a dense scan of |Δr| at 1 s steps over the full 7 day window, for every pair (the recorded stations group has 22 objects), finds no local minimum within D that the prefilter plus detector pipeline missed. Every TCA the pipeline reports matches the dense scan's minimum to within 1 s in time and to within the change of |Δr| over that second.
3. **Formula checks:** relative speed and miss distance computed independently from Δr and Δv at the reported TCA agree with the reported values, and g(TCA) is within the convergence threshold of zero.

### 3.7 Objects that share the watchlist object's orbit

The recorded stations group (captured 2026-09-27) includes ISS modules (POISK, ISS (NAUKA)) and docked vehicles (CREW DRAGON 12, CYGNUS NG-24, PROGRESS-MS 34, SOYUZ-MS 29, PROGRESS-MS 35) whose element sets place them on the ISS's orbit, with identical or near identical epochs. Screened naively, they appear as permanent conjunctions near zero range. GP data does not say whether an object is attached to another, so exclusion uses two mechanisms, and the screening output must never show a docked vehicle as a close approach.

1. **Static exclusion list.** A committed list of NORAD catalog number pairs that are never screened against each other, each with the object names and the date the entry was added. It is maintained by hand as vehicles arrive and depart. This is the primary mechanism.
2. **Co-orbiting fallback.** A pair not on the list is treated as co-orbiting, and suppressed, when its separation |Δr| stays below a fixed bound at every grid sample over the whole screening window, so the pair never opens and closes the way two independent objects do. The bound is chosen against the recorded stations fixture before the screening code is accepted and is recorded here with that evidence. This catches a new arrival before the list is updated.

A suppressed pair is never dropped silently: the screening run lists it with the mechanism that suppressed it, so a stale list entry or a wrong suppression stays visible.

## 4. Element age

O2 Appendix B: "In general, TLE data is accurate to about a kilometer or so at epoch and it quickly degrades (Hartman, 1993)." Every reported close approach therefore carries each object's element age at TCA (C3, "Days Since Epoch"), and the reported miss distance is shown with the understanding that its uncertainty is at least of the order of kilometres and grows with element age.

## 5. How these numbers may be read

* A reported close approach means "two public element sets, propagated with SGP4, come within 5 km of each other". It is not a collision probability and not a prediction that an operator would act on. C2 itself says that "the minimum distance method ignores position covariance information and can lead to an exaggerated assessment of the true risk".
* Miss distances are reported in kilometres with no more precision than the element accuracy supports.
* Nothing in SpaceFlux is an operational conjunction assessment or collision avoidance service.
