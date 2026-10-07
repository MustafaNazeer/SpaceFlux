# ADR 0013: Satellite passes over a fixed observer

* **Status:** accepted
* **Date:** 2026-10-07

## Context

The dashboard shows each watchlist object's close approaches and the space weather around them, but not when the object is overhead. Pass times are the most common question asked of a satellite tracker, and they come from the same element sets the screening already uses.

`query-api` already stores each catalog object's newest GP element set in `catalog_object` ([mysql-schema.md](../data/mysql-schema.md#catalog_object)), so passes need no new feed. The SGP4 propagation, the GP to TLE conversion, the Orekit data setup and the element age and decay rules live in `risk-engine` and were verified there against the published SGP4 test cases ([orbital-conventions.md](../risk/orbital-conventions.md)).

A pass needs an observer. No authoritative source publishes a single surveyed point for NASA Johnson Space Center, so the observer is a citable geodetic mark on the center's grounds.

## Decision

1. **Computed on demand in `query-api`**, for watchlist objects only, from the stored element set. Nothing is precomputed or stored, and no topic carries passes.
2. **One fixed observer:** the NGS survey mark GEMINI 3 (PID AW6997) at NASA Johnson Space Center, latitude 29.557976853, longitude -95.091374225, ellipsoidal height -22.182 m, from its NGS datasheet (NAD 83(1993), used as WGS84; the two differ by about 2 m, under 0.0001 degree as seen from a pass). The three values are constants in code and in the reference run.
3. **Geometric passes only.** A pass is the time the object is at or above 10 degrees elevation. Visibility, illumination and refraction are not modelled, and the dashboard says so.
4. **Window:** from the request time truncated to the second, for 86,400 SI seconds. A pass already in progress at the start, or still in progress at the end, is clipped to the window, flagged as clipped, and reports the elevation at the edge.
5. **One entry per pass:** rise, highest peak and set times with azimuths, the peak elevation, and a count of the peaks when a pass has more than one.
6. **Frames and Earth orientation:** TEME to ITRF (IERS 2010 conventions) on a WGS84 ellipsoid. No Earth orientation data is loaded: UT1 is taken as UTC and polar motion as zero, which the SGP4 reference paper (AIAA 2006-6753) places within SGP4's own uncertainty. A test pins this, so loading EOP data later is a visible change.
7. **Event detection in Orekit:** an elevation extremum detector (60 s maximum check) and a 10 degree elevation detector (10 s maximum check), both with a 1e-6 s threshold, 100 iterations and handlers that continue rather than stop. A dense elevation scan in the tests guards against a missed short pass.
8. **Element set rules are the screening's:** the same maximum element age, the same decay latch, and the same near Earth limit as [orbital-conventions.md](../risk/orbital-conventions.md). An object outside them gets no passes and a stated reason.
9. **Display precision:** times to the second and angles to 0.1 degree, in line with element accuracy of about a kilometre at epoch.
10. **A shared module for the orbit code.** `GpElementSets`, `OrekitData` and `ObjectTrack`, with what they depend on, move from `risk-engine` into a new plain library module, `orbit-core` (package `io.github.mustafanazeer.spaceflux.orbit`), as `kafka-contracts` did for the event contracts ([ADR 0011](0011-shared-event-contracts-module.md)). Both services depend on it.
11. **Orekit 13.1.9.** The parent POM moves from 13.1.8 before any pass code lands, and the risk engine's SGP4 verification tests are rerun on it.
12. **Verified against an independent implementation.** Expected values come from Skyfield 1.55 with sgp4 2.27 (Vallado's SGP4, a GMST 1982 rotation, Skyfield's own geodetic formula), run once on recorded element sets for the ISS, Tianhe, a sun synchronous object and an eccentric debris object, plus windows that start and end inside a pass. The generating script and its output are committed; the Java tests read the output and never run Python. Tolerances are 0.001 degree in elevation at the reference times, 0.002 degree in rise and set azimuth, 0.05 s in rise and set time for passes peaking at 11 degrees or more, and 1 s in peak time. They are provisional: the first run must show every difference at a tenth of its tolerance or less, or the cause is found before the tolerance is kept.

## Alternatives considered

* **Precompute passes in `risk-engine` and publish them.** Fits the event driven pipeline, but adds a topic, a schema and a table for data that is cheap to compute and only read on demand.
* **A bundled IERS Earth orientation file.** Slightly more exact, but needs a refresh process and a coverage check for an effect below SGP4's own error.
* **Extend clipped passes to their true rise or set.** Gives whole passes, but answers a question outside the window the request asked about.
* **Copy the orbit classes into `query-api`.** No change to `risk-engine`, but two copies of verified code that are free to drift.
* **Use a published pass prediction as the reference.** None found states the element set it was computed from, so it cannot be reproduced.

## Consequences

* `query-api` gains Orekit and its startup cost, and each pass request propagates up to the watchlist size over 24 hours.
* The orbit code is changed in one place and verified by one set of tests for both services.
* A different observer, a visibility model, or EOP data are each a new decision recorded against this one.
