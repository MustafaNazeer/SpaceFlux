# Reference passes, provenance

**`reference-passes.json` is computed, not recorded.** It holds satellite passes over the NGS survey mark GEMINI 3 at NASA Johnson Space Center, computed with Skyfield and the sgp4 package from four recorded CelesTrak element sets. It is the independent reference that the pass computation in this module is tested against ([ADR 0013](../../../../../docs/adr/0013-satellite-passes.md)). The Java tests read this file; the build never runs Python. Nothing in it is a prediction anyone published, and none of it may be presented as one.

| Field | Value |
| --- | --- |
| File | `reference-passes.json` |
| Size | 60913 bytes |
| SHA256 | `68171591349419130eae59c88f1a213580c90affa093a8ba36103101cb7bad9a` |
| Generated | 2026-10-07, twice, byte identical |
| Script | [`query-api/tools/passes/reference_passes.py`](../../../../tools/passes/reference_passes.py) |
| Python | 3.13.7, Linux x86_64 |

## Inputs

The element sets are read, unmodified, from the recorded CelesTrak captures in [orbit-core/src/test/resources/celestrak](../../../../../orbit-core/src/test/resources/celestrak/PROVENANCE.md), whose provenance names their source URLs and capture times. The script checks each file's SHA256 against the values below and stops if any differs.

| Object | NORAD | File | SHA256 | EPOCH |
| --- | --- | --- | --- | --- |
| ISS (ZARYA) | 25544 | `gp-catnr-25544.json` | `7097564cb9924c63a9a180de8fa68b7ac65bc753603ee597be9b8b4a271bb966` | 2026-09-27T04:10:50.460096 |
| CSS (TIANHE) | 48274 | `gp-stations.json` (the one record with this NORAD id) | `d8cb53b7044135e9ae8e7e613d7d4b15f4db21be6f527f987ffc222fe0463abd` | 2026-09-26T21:00:21.314016 |
| OBJECT AJ | 57036 | `gp-catnr-57036.json` | `9b8afffc8da9bf84153a3b7586b4c865e70276ed0fca07ee3b80edaf1fe73376` | 2026-09-28T14:10:20.740512 |
| SL-12 DEB | 27958 | `gp-catnr-27958.json` | `cc8a3fa0ccc4c52c008cfb1b7304847d653ae16a465c99b81926b75f0686ae03` | 2026-09-25T22:48:49.415616 |

Observer: NGS PID AW6997, designation GEMINI 3, geodetic latitude 29.557976853 degrees, longitude -95.091374225 degrees (east positive), height -22.182 m above the ellipsoid, from its NGS datasheet (NAD 83(1993), used as WGS84 as ADR 0013 states).

## Method

* **Element sets.** `EarthSatellite.from_omm`, which builds the satellite with `sgp4.omm.initialize`, that is `sgp4init` with the WGS72 constants in improved (`'i'`) mode.
* **Time.** `load.timescale(builtin=True, delta_t=69.184)`: the builtin leap second table, and a constant delta T of 69.184 s, which is TT minus UTC while TAI minus UTC is 37 s, so UT1 equals UTC. No polar motion table is attached, so there is no polar motion. The script checks at both ends of every window that UT1 minus UTC is 0 and that no leap second falls inside.
* **Frames.** Skyfield rotates TEME to the Earth fixed frame by GMST 1982 (AIAA 2006-6753, Appendix C), places the observer with its own WGS84 geodetic formula, and gives elevation and azimuth from `(satellite - observer).at(t).altaz()`: geometric positions, no light time, no refraction. Azimuth is from north, clockwise towards east.
* **Events.** Elevation maxima from `skyfield.searchlib.find_maxima`, and rises and sets from `skyfield.searchlib.find_discrete` on "elevation at or above 10 degrees", both with a 1 s search step (`step_days`) and a 1 ms bracket (`epsilon`). Skyfield's own `EarthSatellite.find_events` is not used because it stops at half a second.
* **A pass** is an interval with elevation at or above 10 degrees inside the window. Its peaks are the maxima inside it; `peak` is the highest, `peak_count` how many there are. A pass already above 10 degrees at the window start has `rise_clipped` true, no `rise`, and the position at the start in `start_edge`; likewise `set_clipped` and `end_edge` at the window end. When a clipped pass has no maximum inside the window, `peak` is the higher of its edge positions and `peak_at_edge` is true.
* **Windows.** 86,400 s from each start. Each object has three: `base`, from the start the risk engine's screening tests already use for these element sets (2026-09-27T05:00:00Z for 25544 and 48274, 2026-09-28T15:00:00Z for 57036 and 27958); `starts_inside_pass`, which starts inside the first base window pass that peaks at 11 degrees or more; and `ends_inside_pass`, which ends inside the last such pass. The cut is halfway from rise to peak (`before`) or from peak to set (`after`), truncated to the whole second, and each window's `cut` field says which. The combination differs per object so that both cases, a clipped pass with its maximum inside the window and one without, occur at both edges.
* **Precision.** Event times come from a bracket at most 1 ms wide, are rounded to the millisecond, and every angle is then evaluated at the rounded time and written to 1e-6 degree. A rise or set time is therefore within about 1.5 ms of the true crossing, and its stored elevation is not exactly 10 (the largest offset here is 0.000103 degree). Tests that check the elevation at a reference time should compare with the stored elevation, not with 10. The same pass seen in two windows can differ by 1 ms for the same reason.
* `maxima_within_0_001_deg_of_threshold` lists any maximum within 0.001 degree of 10, which is ambiguous by definition. There are none in this file.

## Checks run with the generation

These are printed by the script, which exits non zero if any fails; they are not tests.

* A dense scan of every window at 1 s: every sample at or above 10 degrees lies inside a pass, every pass of 2 s or more holds such a sample, and every sampled local maximum at or above 10 degrees lies within 1 s of a found maximum. All passed.
* No two passes overlap, and no pass whose peak is a true maximum peaks below 10 degrees. Both held.
* Guard on the stored values: every stored rise and set elevation is within 0.0002 degree of 10 (the largest offset is 0.000103 degree), and no stored maximum is within 0.001 degree of 10. Both held.
* Separately, the event sequence of every window matched Skyfield's own `find_events` (half second precision): the same rises, peaks and sets in the same order, every rise and set within 0.232 s.

| Object | base | starts_inside_pass | ends_inside_pass | Lowest true peak (degrees) |
| --- | --- | --- | --- | --- |
| 25544 | 4 | 4 | 5 | 12.265 |
| 48274 | 5 | 6 | 5 | 10.309 |
| 57036 | 3 | 4 | 3 | 11.066 |
| 27958 | 5 | 5 | 6 | 16.099 |

Every pass in this file has a single peak, so no window exercises `peak_count` above 1.

## Search for a pass with more than one peak

Before settling that, I ran the same pass logic (`reference_passes.py --scan-multi-peak`, which writes nothing) over every window the service could compute from two of the element sets, SL-12 DEB 27958 (eccentric) and OBJECT AJ 57036 (sun synchronous): window starts from 5 minutes before the epoch (the catalog refuses an `EPOCH` more than 5 minutes after its fetch, and a window starts at a request time after that fetch) to 10 days after it (the maximum element age), so times from 5 minutes before the epoch to 11 days after it. Starts are rounded inwards to the whole second, spaced 20 hours apart with one more at the last start, so every pass up to 4 hours long lies whole inside at least one window. First the script checks a 10 s grid over the whole span against the 80 km decay floor (geocentric radius at least 6458.135 km, the WGS72 radius plus 80 km) and for SGP4 errors, so that the scan would stop at the first failing sample; neither element set has one.

| Object | EPOCH | Window starts | Times to | Windows | Passes (overlaps counted twice) | Lowest altitude on the 10 s grid (radius minus 6378.135 km) | With more than one peak |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 27958 | 2026-09-25T22:48:49.415616 | 2026-09-25T22:43:50Z to 2026-10-05T22:48:49Z | 2026-10-06T22:48:49Z | 14 | 68 | 405.8 km | 0 |
| 57036 | 2026-09-28T14:10:20.740512 | 2026-09-28T14:05:21Z to 2026-10-08T14:10:20Z | 2026-10-09T14:10:20Z | 14 | 43 | 495.5 km | 0 |

No pass with more than one peak exists in either span, so this file holds no recorded multiple peak pass. On a 1 s search step that rules out any second maximum at least 2 s from the first.

## Environment

Installed into a fresh virtual environment from PyPI with `pip install --require-hashes --only-binary=:all: -r requirements.txt`. `requirements.txt` next to the script pins each version with the SHA256 of every file PyPI lists for it, taken from PyPI's JSON API. The files installed on Linux x86_64 with Python 3.13 were:

| Package | File | SHA256 |
| --- | --- | --- |
| skyfield 1.55 | `skyfield-1.55-py3-none-any.whl` | `9f98964855067460c94aa81a337194136f4a97a62ba8bbbfac1b8556f2b66ad4` |
| sgp4 2.27 | `sgp4-2.27-cp311-abi3-manylinux1_x86_64.manylinux_2_28_x86_64.manylinux_2_5_x86_64.whl` | `4d3775313120dcb6239535fa0c94cb6d5b089fd84bbfb15220923ae38dec7296` |
| numpy 2.5.3 | `numpy-2.5.3-cp313-cp313-manylinux_2_27_x86_64.manylinux_2_28_x86_64.whl` | `a5fa86b80fd24bcd1aff83ad23be44ea323de3f787be8f8b15d4a65621e25321` |
| jplephem 2.24 | `jplephem-2.24-py3-none-any.whl` | `2de15608a0f13010a71a0a8af8765646d5884402006dac0dd7639d7db13629ac` |
| certifi 2026.7.22 | `certifi-2026.7.22-py3-none-any.whl` | `62f22742b58a1a33014a2b6b706588a8d7e2a88ae7bd1a6ebe8c992928483775` |

jplephem and certifi are Skyfield's declared dependencies; nothing here loads an ephemeris or downloads a file. The script refuses to run unless skyfield, sgp4 and numpy report the versions above.

## How to rerun

From the repository root, with the virtual environment kept outside the repository:

```
python3 -m venv /path/outside/repo/venv
/path/outside/repo/venv/bin/pip install --require-hashes --only-binary=:all: -r query-api/tools/passes/requirements.txt
/path/outside/repo/venv/bin/python -I query-api/tools/passes/reference_passes.py
/path/outside/repo/venv/bin/python -I query-api/tools/passes/reference_passes.py --scan-multi-peak
```

It derives every path from its own location, uses no network, overwrites `reference-passes.json`, and prints a line per window, then the output size and SHA256, which must match the values above. Each takes several minutes.
