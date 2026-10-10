#!/usr/bin/env python3
"""Independent reference passes over the GEMINI 3 survey mark at NASA Johnson
Space Center, computed with Skyfield and the sgp4 package.

Reads four recorded CelesTrak GP files from orbit-core/src/test/resources/celestrak
(found from this script's location in the repository), checks the SHA256 of each against
the value recorded in that folder's PROVENANCE.md (it stops if any differs),
and writes src/test/resources/passes/reference-passes.json in the module that
holds this tools/ folder. Every path is derived from this script's location.
No network access: the time scale uses Skyfield's builtin leap second table.

Settings, which must match the service for the comparison to mean anything:

* observer: geodetic latitude 29.557976853, longitude -95.091374225 (east
  positive), height -22.182 m above the WGS84 ellipsoid (NGS PID AW6997);
* UT1 = UTC and no polar motion: load.timescale(builtin=True, delta_t=69.184),
  checked at both ends of every window;
* SGP4: EarthSatellite.from_omm, which calls sgp4init with WGS72 constants in
  improved ('i') mode; TEME to Earth fixed by a single GMST 1982 rotation;
* geometric positions, no refraction, no light time;
* a pass is an interval with elevation at or above 10 degrees. Maxima come
  from find_maxima on the elevation, rises and sets from find_discrete on
  "elevation >= 10", both to a 1 ms bracket, with a 1 s search step.

Run from anywhere with the pinned environment of requirements.txt:
    python reference_passes.py
It prints the output size and SHA256 and a short sanity summary.

    python reference_passes.py --scan-multi-peak
writes nothing. For 27958 and 57036 it searches every window the service
could compute from the element set: starts from 5 minutes before the epoch
(rounded up to the whole second) to 10 days after it (rounded down), every
20 h plus one at the last start, so times run to 11 days after the epoch and
a pass up to 4 h long is whole in at least one window. It first checks a 10 s
grid over that span against the 80 km floor (WGS72 radius 6378.135 km) and
SGP4 errors, and stops the windows before the first failing sample. It prints
every pass with more than one peak.
"""

import datetime as dt
import hashlib
import json
import os
import sys

import numpy as np
import sgp4
import skyfield
from skyfield.api import EarthSatellite, load, wgs84
from sgp4.api import jday
from skyfield.searchlib import find_discrete, find_maxima

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(os.path.dirname(HERE))
REPO = os.path.dirname(MODULE)
OUT_DIR = os.path.join(MODULE, "src", "test", "resources", "passes")
FIXTURES = os.path.join(REPO, "orbit-core", "src", "test", "resources", "celestrak")
OUT = "reference-passes.json"

INPUT_SHA256 = {
    "gp-catnr-25544.json": "7097564cb9924c63a9a180de8fa68b7ac65bc753603ee597be9b8b4a271bb966",
    "gp-stations.json": "d8cb53b7044135e9ae8e7e613d7d4b15f4db21be6f527f987ffc222fe0463abd",
    "gp-catnr-57036.json": "9b8afffc8da9bf84153a3b7586b4c865e70276ed0fca07ee3b80edaf1fe73376",
    "gp-catnr-27958.json": "cc8a3fa0ccc4c52c008cfb1b7304847d653ae16a465c99b81926b75f0686ae03",
}

EXPECTED_VERSIONS = {"skyfield": "1.55", "sgp4": "2.27", "numpy": "2.5.3"}

OBSERVER_LAT_DEG = 29.557976853
OBSERVER_LON_DEG = -95.091374225
OBSERVER_HEIGHT_M = -22.182

THRESHOLD_DEG = 10.0
DELTA_T_S = 69.184
WINDOW_S = 86400
EPSILON_DAYS = 0.001 / 86400.0
STEP_DAYS = 1.0 / 86400.0
NEAR_THRESHOLD_DEG = 0.001
CROSSING_GUARD_DEG = 0.0002
SCAN_OBJECTS = [(27958, "gp-catnr-27958.json"), (57036, "gp-catnr-57036.json")]
SCAN_AGE_DAYS = 10
SCAN_BEFORE_EPOCH_S = 300
SCAN_STEP_H = 20
FLOOR_STEP_S = 10
WGS72_RADIUS_KM = 6378.135
FLOOR_RADIUS_KM = WGS72_RADIUS_KM + 80.0
CUT_MIN_PEAK_DEG = 11.0

# (NORAD id, fixture file, base window start UTC, start cut, end cut).
# A start cut puts the window start inside the first pass of the base window
# whose peak is at least 11 degrees; an end cut puts the window end inside the
# last such pass. "before" is halfway from rise to the highest peak, "after"
# halfway from that peak to set, each truncated to the whole second. The
# combinations differ per object so both clipped cases (a true maximum inside
# the window, and none) occur at both edges.
OBJECTS = [
    (25544, "gp-catnr-25544.json", "2026-09-27T05:00:00", "after", "after"),
    (48274, "gp-stations.json", "2026-09-27T05:00:00", "before", "before"),
    (57036, "gp-catnr-57036.json", "2026-09-28T15:00:00", "after", "before"),
    (27958, "gp-catnr-27958.json", "2026-09-28T15:00:00", "before", "after"),
]


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def parse_utc(text):
    return dt.datetime.strptime(text, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=dt.timezone.utc)


def iso_ms(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (moment.microsecond // 1000)


class Window:
    def __init__(self, ts, sat, observer, start):
        self.ts = ts
        self.sat = sat
        self.topocentric = sat - observer
        self.start = start
        self.end = start + dt.timedelta(seconds=WINDOW_S)
        self.t0 = self.sky_time(start)
        self.t1 = self.sky_time(self.end)
        for t in (self.t0, self.t1):
            if abs(float(t.dut1)) > 1e-9 or abs(float(t.delta_t) - DELTA_T_S) > 1e-9:
                sys.exit("UT1 is not UTC at %s" % t.utc_iso())
        span = (self.t1.whole - self.t0.whole + self.t1.tt_fraction - self.t0.tt_fraction) * 86400.0
        if abs(span - WINDOW_S) > 1e-6:
            sys.exit("a leap second falls inside the window from %s" % iso_ms(start))

    def sky_time(self, moment):
        return self.ts.utc(moment.year, moment.month, moment.day, moment.hour,
                           moment.minute, moment.second + moment.microsecond / 1e6)

    def altaz(self, t):
        position = self.topocentric.at(t)
        message = position.message
        messages = message if isinstance(message, list) else [message]
        if any(m is not None for m in messages):
            sys.exit("SGP4 reported an error inside the window: %s" % message)
        alt, az, _ = position.altaz()
        return alt.degrees, az.degrees

    def elevation(self, t):
        return self.altaz(t)[0]

    def to_moment(self, t):
        """The UTC instant of a Skyfield time, rounded to the millisecond."""
        offset_s = ((t.whole - self.t0.whole) + (t.tt_fraction - self.t0.tt_fraction)) * 86400.0
        return self.start + dt.timedelta(milliseconds=int(round(offset_s * 1000.0)))

    def point(self, moment):
        alt, az = self.altaz(self.sky_time(moment))
        return {"time": iso_ms(moment), "elevation_deg": round(float(alt), 6),
                "azimuth_deg": round(float(az), 6)}

    def compute(self):
        def elevation(t):
            return self.elevation(t)
        elevation.step_days = STEP_DAYS

        def above(t):
            return self.elevation(t) >= THRESHOLD_DEG
        above.step_days = STEP_DAYS

        t_max, el_max = find_maxima(self.t0, self.t1, elevation,
                                                       epsilon=EPSILON_DAYS)
        t_cross, after = find_discrete(self.t0, self.t1, above,
                                                          epsilon=EPSILON_DAYS)
        maxima = [(self.to_moment(t_max[i]), float(el_max[i])) for i in range(len(t_max))]
        crossings = [(self.to_moment(t_cross[i]), bool(after[i])) for i in range(len(t_cross))]

        start_above = bool(above(self.t0))
        intervals = []
        open_at = self.start if start_above else None
        for moment, now_above in crossings:
            if now_above:
                if open_at is not None:
                    sys.exit("two rises in a row at %s" % iso_ms(moment))
                open_at = moment
            else:
                if open_at is None:
                    sys.exit("a set without a rise at %s" % iso_ms(moment))
                intervals.append((open_at, moment, not intervals and start_above, False))
                open_at = None
        if open_at is not None:
            intervals.append((open_at, self.end, not intervals and start_above, True))

        passes = []
        for rise, set_, rise_clipped, set_clipped in intervals:
            inside = [(m, e) for m, e in maxima if rise <= m <= set_]
            entry = {
                "rise": None if rise_clipped else self.point(rise),
                "rise_clipped": rise_clipped,
                "start_edge": self.point(self.start) if rise_clipped else None,
                "set": None if set_clipped else self.point(set_),
                "set_clipped": set_clipped,
                "end_edge": self.point(self.end) if set_clipped else None,
                "peak_count": len(inside),
                "peaks": [self.point(m) for m, _ in inside],
            }
            if inside:
                best = max(inside, key=lambda me: me[1])[0]
                entry["peak"] = self.point(best)
                entry["peak_at_edge"] = False
            else:
                edges = [p for p in (entry["start_edge"], entry["end_edge"]) if p is not None]
                if not edges:
                    sys.exit("an unclipped pass with no maximum at %s" % iso_ms(rise))
                entry["peak"] = max(edges, key=lambda p: p["elevation_deg"])
                entry["peak_at_edge"] = True
            passes.append(entry)

        near = [self.point(m) for m, e in maxima
                if abs(e - THRESHOLD_DEG) <= NEAR_THRESHOLD_DEG]
        self.maxima = maxima
        self.passes = passes
        return {
            "start": iso_ms(self.start),
            "end": iso_ms(self.end),
            "elevation_at_start_deg": round(float(self.elevation(self.t0)), 6),
            "elevation_at_end_deg": round(float(self.elevation(self.t1)), 6),
            "pass_count": len(passes),
            "passes": passes,
            "maxima_within_0_001_deg_of_threshold": near,
        }


def parse_time(text):
    return dt.datetime.strptime(text, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=dt.timezone.utc)


def cut_time(entry, where):
    rise = parse_time(entry["rise"]["time"])
    peak = parse_time(entry["peak"]["time"])
    set_ = parse_time(entry["set"]["time"])
    moment = rise + (peak - rise) / 2 if where == "before" else peak + (set_ - peak) / 2
    return moment.replace(microsecond=0)


def dense_scan(window):
    """Sanity only: every 1 s sample at or above 10 degrees lies in a pass."""
    count = WINDOW_S + 1
    jd = window.t0.tt + np.arange(count) / 86400.0
    el = window.elevation(window.ts.tt_jd(jd))
    spans = []
    for p in window.passes:
        a = parse_time(p["rise"]["time"]) if p["rise"] else window.start
        b = parse_time(p["set"]["time"]) if p["set"] else window.end
        spans.append(((a - window.start).total_seconds(), (b - window.start).total_seconds()))
    problems = []
    for i in np.flatnonzero(el >= THRESHOLD_DEG):
        if not any(a - 0.002 <= i <= b + 0.002 for a, b in spans):
            problems.append("sample %d s at %.4f deg outside every pass" % (i, el[i]))
    for a, b in spans:
        if b - a >= 2 and not np.any(el[int(np.ceil(a)):int(np.floor(b)) + 1] >= THRESHOLD_DEG):
            problems.append("pass from %.3f s has no sample above threshold" % a)
    peaks = np.flatnonzero((el[1:-1] > el[:-2]) & (el[1:-1] >= el[2:])) + 1
    max_offsets = [(m - window.start).total_seconds() for m, _ in window.maxima]
    for i in peaks:
        if el[i] >= THRESHOLD_DEG and not any(abs(i - m) <= 1.0 for m in max_offsets):
            problems.append("sampled maximum at %d s (%.4f deg) not found" % (i, el[i]))
    return problems


def floor_latch(sat, first, last):
    """First 10 s sample from first to last below the 80 km floor or in error."""
    count = int((last - first).total_seconds() // FLOOR_STEP_S) + 1
    moments = [first + dt.timedelta(seconds=FLOOR_STEP_S * i) for i in range(count)]
    parts = [jday(m.year, m.month, m.day, m.hour, m.minute, m.second + m.microsecond / 1e6)
             for m in moments]
    errors, positions, _ = sat.model.sgp4_array(np.array([a for a, _ in parts]),
                                                np.array([b for _, b in parts]))
    radius = np.linalg.norm(positions, axis=1)
    failing = np.flatnonzero((errors != 0) | ~(radius >= FLOOR_RADIUS_KM))
    lowest = float(np.nanmin(radius)) - WGS72_RADIUS_KM
    return (moments[failing[0]] if len(failing) else None), lowest, count


def scan_multi_peak(ts, observer):
    for norad, name in SCAN_OBJECTS:
        record = load_record(name, norad)
        sat = EarthSatellite.from_omm(ts, record)
        epoch = dt.datetime.strptime(record["EPOCH"], "%Y-%m-%dT%H:%M:%S.%f").replace(
            tzinfo=dt.timezone.utc)
        first = epoch - dt.timedelta(seconds=SCAN_BEFORE_EPOCH_S)
        if first.microsecond:
            first = first.replace(microsecond=0) + dt.timedelta(seconds=1)
        last = (epoch + dt.timedelta(days=SCAN_AGE_DAYS)).replace(microsecond=0)
        latch, lowest_km, samples = floor_latch(
            sat, first, last + dt.timedelta(seconds=WINDOW_S))
        print("%d floor check: %d samples every %d s from %s, lowest altitude %.1f km, %s"
              % (norad, samples, FLOOR_STEP_S, iso_ms(first), lowest_km,
                 "first failing sample %s" % iso_ms(latch) if latch else "no failing sample"))
        if latch is not None:
            last = min(last, latch - dt.timedelta(seconds=FLOOR_STEP_S + WINDOW_S))
        starts = []
        start = first
        while start < last:
            starts.append(start)
            start += dt.timedelta(hours=SCAN_STEP_H)
        starts.append(last)
        passes = 0
        found = {}
        for start in starts:
            out = Window(ts, sat, observer, start).compute()
            passes += out["pass_count"]
            for p in out["passes"]:
                if p["peak_count"] > 1:
                    found[p["peaks"][0]["time"]] = (out["start"], p)
        print("%d epoch %s window starts %s to %s, times to %s, %d windows, "
              "%d passes (with overlap), %d with more than one peak"
              % (norad, record["EPOCH"], iso_ms(first), iso_ms(last),
                 iso_ms(last + dt.timedelta(seconds=WINDOW_S)), len(starts), passes,
                 len(found)))
        for key in sorted(found):
            window_start, p = found[key]
            print("    window %s: %s" % (window_start, json.dumps(p)))


def load_record(name, norad):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as f:
        records = [r for r in json.load(f) if r["NORAD_CAT_ID"] == norad]
    if len(records) != 1:
        sys.exit("%s holds %d records for %d" % (name, len(records), norad))
    return records[0]


def main():
    versions = {"skyfield": skyfield.__version__, "sgp4": sgp4.__version__,
                "numpy": np.__version__}
    if versions != EXPECTED_VERSIONS:
        sys.exit("unexpected versions %s, need %s" % (versions, EXPECTED_VERSIONS))

    for name, expected in INPUT_SHA256.items():
        actual = sha256(os.path.join(FIXTURES, name))
        if actual != expected:
            sys.exit("SHA256 of %s is %s, expected %s" % (name, actual, expected))

    ts = load.timescale(builtin=True, delta_t=DELTA_T_S)
    observer = wgs84.latlon(OBSERVER_LAT_DEG, OBSERVER_LON_DEG, elevation_m=OBSERVER_HEIGHT_M)
    if sys.argv[1:] == ["--scan-multi-peak"]:
        scan_multi_peak(ts, observer)
        return
    if sys.argv[1:]:
        sys.exit("usage: reference_passes.py [--scan-multi-peak]")

    objects = []
    summary = []
    for norad, name, start_text, start_cut, end_cut in OBJECTS:
        record = load_record(name, norad)
        sat = EarthSatellite.from_omm(ts, record)

        base = Window(ts, sat, observer, parse_utc(start_text))
        base_out = base.compute()
        eligible = [p for p in base_out["passes"]
                    if not p["rise_clipped"] and not p["set_clipped"]
                    and p["peak"]["elevation_deg"] >= CUT_MIN_PEAK_DEG]
        if not eligible:
            sys.exit("no pass of %d peaks at %s degrees or more" % (norad, CUT_MIN_PEAK_DEG))
        start_at = cut_time(eligible[0], start_cut)
        end_at = cut_time(eligible[-1], end_cut)
        cut_start = Window(ts, sat, observer, start_at)
        cut_end = Window(ts, sat, observer, end_at - dt.timedelta(seconds=WINDOW_S))
        windows = []
        for kind, window, out, note in (
                ("base", base, base_out, None),
                ("starts_inside_pass", cut_start, cut_start.compute(),
                 "start %s the highest peak of the base window pass rising at %s"
                 % (start_cut, eligible[0]["rise"]["time"])),
                ("ends_inside_pass", cut_end, cut_end.compute(),
                 "end %s the highest peak of the base window pass rising at %s"
                 % (end_cut, eligible[-1]["rise"]["time"]))):
            out = dict({"kind": kind, "cut": note}, **out)
            windows.append(out)
            summary.append((norad, kind, window, out, dense_scan(window)))

        objects.append({
            "norad_cat_id": norad,
            "object_name": record["OBJECT_NAME"],
            "epoch": record["EPOCH"],
            "source_file": "orbit-core/src/test/resources/celestrak/" + name,
            "source_sha256": INPUT_SHA256[name],
            "windows": windows,
        })

    document = {
        "description": "Reference satellite passes over NGS mark GEMINI 3 (PID AW6997), "
                       "computed with Skyfield and the sgp4 package; see PROVENANCE.md.",
        "generator": "query-api/tools/passes/reference_passes.py",
        "versions": versions,
        "observer": {"name": "GEMINI 3", "ngs_pid": "AW6997",
                     "latitude_deg": OBSERVER_LAT_DEG, "longitude_deg": OBSERVER_LON_DEG,
                     "height_m": OBSERVER_HEIGHT_M, "ellipsoid": "WGS84"},
        "settings": {"threshold_deg": THRESHOLD_DEG, "window_s": WINDOW_S,
                     "delta_t_s": DELTA_T_S, "ut1_minus_utc_s": 0.0, "polar_motion": "none",
                     "sgp4_gravity_model": "WGS72", "sgp4_opsmode": "i",
                     "teme_to_earth_fixed": "GMST 1982 rotation", "refraction": "none",
                     "search_step_s": 1.0, "search_epsilon_s": 0.001,
                     "times": "UTC, rounded to the millisecond; angles are evaluated at the "
                              "rounded time and rounded to 1e-6 degree"},
        "objects": objects,
    }
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, OUT)
    data = (json.dumps(document, indent=2, ensure_ascii=True) + "\n").encode("ascii")
    with open(path, "wb") as f:
        f.write(data)

    failed = False
    worst_crossing = 0.0
    for norad, kind, window, out, problems in summary:
        passes = out["passes"]
        peaks = [p["peak"]["elevation_deg"] for p in passes if not p["peak_at_edge"]]
        multi = sum(1 for p in passes if p["peak_count"] > 1)
        print("%6d %-19s %s  passes %2d  multi peak %d  lowest true peak %s  near threshold %d"
              % (norad, kind, out["start"], len(passes), multi,
                 "%.3f" % min(peaks) if peaks else "none",
                 len(out["maxima_within_0_001_deg_of_threshold"])))
        last_end = None
        for p in passes:
            a = p["rise"]["time"] if p["rise"] else out["start"]
            b = p["set"]["time"] if p["set"] else out["end"]
            if last_end is not None and a <= last_end:
                problems.append("overlapping passes at %s" % a)
            if not p["peak_at_edge"] and p["peak"]["elevation_deg"] < THRESHOLD_DEG:
                problems.append("peak below threshold at %s" % p["peak"]["time"])
            for key in ("rise", "set"):
                point = p[key]
                if point and abs(point["elevation_deg"] - THRESHOLD_DEG) > CROSSING_GUARD_DEG:
                    problems.append("%s elevation %.6f at %s is not within %s deg of 10"
                                    % (key, point["elevation_deg"], point["time"],
                                       CROSSING_GUARD_DEG))
                if point:
                    worst_crossing = max(worst_crossing,
                                         abs(point["elevation_deg"] - THRESHOLD_DEG))
            for k in p["peaks"]:
                if abs(k["elevation_deg"] - THRESHOLD_DEG) <= NEAR_THRESHOLD_DEG:
                    problems.append("stored maximum %.6f at %s within %s deg of 10"
                                    % (k["elevation_deg"], k["time"], NEAR_THRESHOLD_DEG))
            last_end = b
        if out["maxima_within_0_001_deg_of_threshold"]:
            problems.append("a maximum lies within %s deg of 10" % NEAR_THRESHOLD_DEG)
        for line in problems:
            failed = True
            print("    PROBLEM: " + line)
    print("guard: largest stored rise or set offset from 10 deg is %.6f (limit %s)"
          % (worst_crossing, CROSSING_GUARD_DEG))
    print("%s %d bytes sha256 %s" % (OUT, len(data), hashlib.sha256(data).hexdigest()))
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
