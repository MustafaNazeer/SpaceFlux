# SGP4 verification cases, provenance

These two files are the SGP4 verification element sets and a published set of expected results from the source code archive that accompanies Vallado, Crawford, Hujsak, and Kelso, "Revisiting Spacetrack Report #3", AIAA 2006-6753. I downloaded the archive once, extracted the two files, and copied them here byte for byte, unmodified (line endings included). The archive itself is not committed. The response headers from the download sit next to them in `AIAA-2006-6753.zip.headers.txt`.

How the risk engine uses these cases (which cases, tolerance, constants, time handling) is set out in [docs/risk/orbital-conventions.md](../../../../../docs/risk/orbital-conventions.md), Section 1.

Capture tool: `curl -sS -D AIAA-2006-6753.zip.headers.txt -o AIAA-2006-6753.zip` with the User-Agent `SpaceFlux-fixture-capture (https://github.com/MustafaNazeer/SpaceFlux)`, then `unzip`.

## Source archive

| Field | Value |
| --- | --- |
| Source URL | https://celestrak.org/publications/AIAA/2006-6753/AIAA-2006-6753.zip |
| Linked from | https://celestrak.org/publications/AIAA/2006-6753/ |
| Captured (UTC) | 2026-09-27T21:01:38Z (server `Date` header) |
| HTTP status | 200 |
| Content-Type | `application/x-zip-compressed` |
| Last-Modified | Wed, 10 May 2023 23:17:06 GMT |
| ETag | `"867397899583d91:0"` |
| Size | 1248284 bytes |
| SHA256 | `3642043b706c76be87cf012db3f22e04da6b80498d00f515e51879e0ffadc115` |
| Entries | 163 (C++, C#, Fortran, Java, MATLAB, and Pascal sources, test drivers, sample outputs, and `sgp4/sgp4_CodeReadme.pdf`) |

## Committed files

| File | Path inside the archive | Archive timestamp | Size | SHA256 |
| --- | --- | --- | --- | --- |
| `SGP4-VER.TLE` | `sgp4/cpp/testsgp4/TestSGP4/SGP4-VER.TLE` | 2012-07-16 12:11 | 8616 bytes | `d246d1d9d768ace445a38a965713fa9ba52d80fd8a41a0502ff83d7acffe2881` |
| `tforverf.out` | `sgp4/for/tforverf.out` | 2015-12-07 12:05 | 142427 bytes | `cbe6f3ed115de11e0fe0a803910a3f99d41ccf2bb678881836b1cb5fee1efec7` |
| `AIAA-2006-6753.zip.headers.txt` | not in the archive; response headers of the download | | 244 bytes | `798e4796b0e232c5c02b10c3a79dbf0c9e1537ad442bb6a33d3681663d321d02` |

The archive holds three identical copies of the element set file (`sgp4/cpp/testsgp4/SGP4-VER.TLE`, `sgp4/cpp/testsgp4/TestSGP4/SGP4-VER.TLE`, `sgp4/mat/SGP4-VER.TLE`, all with the SHA256 above).

### Which expected output file, and why

The paper names the C++ result file `tcppver.out`. That file is not in the archive. The C++ test driver (`sgp4/cpp/testsgp4/TestSGP4/TestSGP4.cpp`) writes `tcppver.out` when it runs, and the archive instead ships its per satellite STK ephemeris files (`sgp4/cpp/testsgp4/TestSGP4/NNNNN.e`, times in seconds). Those are not usable as a complete reference: the driver names each file by catalog number, so the second 20413 element set overwrote the first one's `20413.e`.

The archive carries three consolidated result files in the same layout as `tcppver.out`:

| Path | Archive timestamp | Size | SHA256 |
| --- | --- | --- | --- |
| `sgp4/for/tforverf.out` (Fortran) | 2015-12-07 12:05 | 142427 bytes | `cbe6f3ed115de11e0fe0a803910a3f99d41ccf2bb678881836b1cb5fee1efec7` |
| `sgp4/mat/tmatverDec2015.out` (MATLAB) | 2015-12-02 08:09 | 143430 bytes | `60dc65d67b6713840f4abbe4203f204016d465ad2d74dde73c1aa622df8414b7` |
| `sgp4/java/JAVA_SGP4_v2/java_sgp4_ver.out` (Java) | 2009-06-19 15:24 | 140162 bytes | `c530a31b6244fae9ab0905dd2a1c8961f8ebfa6358ccb95a7d452df60e5ae11f` |

The archive's `sgp4_CodeReadme.pdf` (current version February 20, 2017) tells users to run in verification mode with the "a" (AFSPC) operation mode and the "72" (WGS-72) constants, then to "do a file comparison of your output file "tmatver.out" with "tmatverDec2015.out" from the zip file". I committed the Fortran file rather than the MATLAB one because the MATLAB file ends with a malformed final line after the last case: a row at 120 minutes (`120.00000000   15223.91713658 ...`) followed by further rows with no line breaks, none of which belongs to a case, while the Fortran file ends cleanly on the last case. The Java file disagrees with the Fortran and MATLAB files on catalog number 23599 (rows from 420 to 720 minutes).

## File formats (read from the files)

Both files use CRLF line endings and plain ASCII.

### `SGP4-VER.TLE`

* Lines starting with `#` are comments. The comment lines above each element set give the object name and what the case tests (for example `# Sub-orbital case - Decayed 2005-11-29`).
* Every other pair of lines is a standard two line element set, except that line 2 carries three extra whitespace separated numbers after the revolution number: start time, stop time, and step, all in minutes from epoch. Standard TLE parsers need these three trailing fields removed first.
* 33 element sets in this order: 5, 4632, 6251, 8195, 9880, 9998, 11801, 14128, 16925, 20413, 21897, 22312, 22674, 23177, 23333, 23599, 24208, 25954, 26900, 26975, 28057, 28129, 28350, 28623, 28626, 28872, 29141, 29238, 88888, 33333, 33334, 33335, 20413. Catalog number 20413 appears twice with identical elements and different time spans (1440 to 4320 minutes, and 1844000 to 1845100 minutes).
* Two element sets (11801 and 88888, the original Spacetrack Report #3 cases) have a blank international designator field.

### `tforverf.out`

* 700 lines: 33 case header lines and 667 ephemeris rows, one case per element set in the same order as `SGP4-VER.TLE`.
* A case starts with a header line holding the catalog number followed by `xx` (for example `  5  xx`). Cases are separated only by these header lines.
* The first row of every case is at 0.0 minutes (the element set epoch) and has 7 columns. It is always written, even when the requested span does not start at 0.
* Every later row holds the fields below, written with fixed width Fortran formats. The clock field can contain blanks (for example `19: 3:37.089792`), so splitting a row on whitespace gives 18 to 20 tokens; the first 7 tokens are always the reference state.

| Fields | Content | Unit |
| --- | --- | --- |
| 1 | time since epoch | minutes |
| 2 to 4 | position x, y, z, TEME | km |
| 5 to 7 | velocity x, y, z, TEME | km/s |
| 8 | semi major axis | km |
| 9 | eccentricity | none |
| 10 to 14 | inclination, right ascension of the ascending node, argument of perigee, true anomaly, mean anomaly | degrees |
| 15 to 17 | year, month, day | calendar |
| 18 | `hh:mm:ss.ffffff` | clock time |

The units and field order come from the Fortran driver in the same archive (`sgp4/for/TESTFOR.FOR`, format statements 800 and 801, with the angles multiplied by `rad` before writing). Fields 8 onward are the driver's own conversions, not part of the SGP4 output; only fields 1 to 7 are the reference state.

* Printed precision: position to 1e-8 km, velocity to 1e-9 km/s, time to 1e-8 minutes.
* When propagation fails, the driver stops writing rows for that case, so a case can end before its stop time.

### Rows per case, and where cases stop early

| Catalog | Rows | Note from the file contents |
| --- | --- | --- |
| 22312 | 23 | Requested 54.2028672 to 1440 minutes; rows end at 474.2028672 |
| 28350 | 13 | Requested 0 to 2880 minutes; rows end at 1440 |
| 28872 | 11 | Requested 0 to 60 minutes; rows end at 50 |
| 29141 | 22 | Requested 0 to 440 minutes; rows end at 420 |
| 33333 | 5 | Requested 0 to 150 minutes; rows end at 20 |
| 33334 | 1 | Only the 0 minute row. Its values are identical to the last row of 33333, so it is not a propagated state and must not be used as a reference |
| 33335 | 73 | Full requested span, 0 to 1440 minutes at 20 minute steps |
| 20413 (second entry) | 70 | Requested 1844000 to 1845100 minutes; rows end at 1844340 |

Every other case runs to its requested stop time.

## Terms

The CelesTrak FAQ for this paper (https://celestrak.org/publications/AIAA/2006-6753/faq.php, page stamp "Last updated: 2022 Jul 01 17:15:53 UTC", read 2026-09-27) answers "Are there any Licenses required to use the SGP4 code?" with "There is no license associated with the code" and says it may be used for any purpose, personal or commercial. It continues: "We ask only that you include citations in your documentation and source code to show the source of the code and provide links to the main page, to facilitate communications regarding any questions on the theory or source code."

That answer speaks about the SGP4 code. It does not mention the verification files separately; they ship in the same archive. The archive contains no license file of its own. I include the citation below and the link to the main page (https://celestrak.org/publications/AIAA/2006-6753/) to honor that request.

## Citation

David A. Vallado, Paul Crawford, Richard Hujsak, and T. S. Kelso, "Revisiting Spacetrack Report #3", AIAA 2006-6753 (Revision 3 at https://celestrak.org/publications/AIAA/2006-6753/AIAA-2006-6753-Rev3.pdf). Main page: https://celestrak.org/publications/AIAA/2006-6753/

## Verifying

From this directory:

```
sha256sum SGP4-VER.TLE tforverf.out
```

To check against a fresh download:

```
curl -sS -o AIAA-2006-6753.zip https://celestrak.org/publications/AIAA/2006-6753/AIAA-2006-6753.zip
unzip -p AIAA-2006-6753.zip sgp4/cpp/testsgp4/TestSGP4/SGP4-VER.TLE | sha256sum
unzip -p AIAA-2006-6753.zip sgp4/for/tforverf.out | sha256sum
```

## Orekit reference rows for catalog 23599

`orekit-satcode-results-23599.txt` holds lines 293 to 330 of Orekit's own SGP4 reference results, copied verbatim: the `r 23599 xx` header and its 37 rows. The rest of that file is not included.

| Field | Value |
| --- | --- |
| Source URL | https://raw.githubusercontent.com/CS-SI/Orekit/13.1.8/src/test/resources/tle/extrapolationTest-data/SatCode-results |
| Tag | `13.1.8` |
| Fetched | 2026-09-27 |
| Same at tag `13.1.9` | Checked 2026-10-07: `SatCode-results`, `LICENSE.txt` and `NOTICE.txt` at tag `13.1.9` have the SHA256 values below, so the extract and the license files are unchanged for the pinned Orekit 13.1.9 |
| Source file SHA256 | `157b8035555adce15716baa99880d26dd8061ce7a70c0421428f857fc33ce19c` |
| Extract SHA256 | `648ff8274f061b9817770f966756d798af39bef26370b03d6f83d41e28ec77d3` |
| License | Apache License 2.0, copied from the same tag as `OREKIT-LICENSE.txt` (SHA256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`), with Orekit's `NOTICE.txt` as `OREKIT-NOTICE.txt` (SHA256 `dc8ea1d4aed11cba83d3f38de87518b274260fb529f01072af9d6c553ce8c440`) |

Rows use the same first seven fields as `tforverf.out` (minutes, TEME position in km, TEME velocity in km/s), separated by single spaces. For this case they differ from `tforverf.out`; why, and how the tests use each file, is in [docs/risk/orbital-conventions.md](../../../../../docs/risk/orbital-conventions.md) Section 1.

To check the extract:

```
curl -sS https://raw.githubusercontent.com/CS-SI/Orekit/13.1.8/src/test/resources/tle/extrapolationTest-data/SatCode-results | sed -n '293,330p' | sha256sum
curl -sS https://raw.githubusercontent.com/CS-SI/Orekit/13.1.9/src/test/resources/tle/extrapolationTest-data/SatCode-results | sed -n '293,330p' | sha256sum
```
