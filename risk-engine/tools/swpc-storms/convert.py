#!/usr/bin/env python3
"""Convert archived NOAA storm period data into raw.swpc events.

Reads the archive files in the fixture folder's archive/, writes one JSON Lines
file per product into the fixture folder, and validates every event against
schemas/raw.swpc/v1.schema.json. The fixture folder is
src/test/resources/swpc-storms of the module that holds this tools/ folder, and
the schema is found by walking up from this script's folder, so both are
derived from this script's location. No network access. See PROVENANCE.md in
the fixture folder for sources and the mapping.
"""

import csv
import gzip
import datetime as dt
import email.utils
import hashlib
import json
import math
import os
import re
import sys

import jsonschema
import netCDF4
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(os.path.dirname(HERE))
FIXTURES = os.path.join(MODULE, "src", "test", "resources", "swpc-storms")
ARCHIVE = os.path.join(FIXTURES, "archive")

KP_FILES = ["20240510dayind.txt", "20240511dayind.txt", "20240512dayind.txt"]
KP_URL = "https://www.ngdc.noaa.gov/stp/space-weather/swpc-products/daily_reports/space_weather_indices/2024/05/{}"

XRS_FILE = "dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc"
XRS_URL = "https://data.ngdc.noaa.gov/platforms/solar-space-observing-satellites/goes/goes16/l2/data/xrsf-l2-avg1m/2024/05/" + XRS_FILE
XRS_WINDOW = (dt.datetime(2024, 5, 10, 3, 0), dt.datetime(2024, 5, 10, 9, 0))

PROTON_FILE = "g13_epead_cpflux_5m_20170901_20170930.csv"
PROTON_ARCHIVE = PROTON_FILE + ".gz"
PROTON_URL = "https://www.ncei.noaa.gov/data/goes-space-environment-monitor/access/avg/2017/09/goes13/csv/" + PROTON_FILE
PROTON_WINDOW = (dt.datetime(2017, 9, 10, 16, 0), dt.datetime(2017, 9, 10, 22, 0))
PROTON_THRESHOLDS_MEV = [1, 5, 10, 30, 50, 60, 100]

OUT_KP = "kp-2024-05-10-to-12.jsonl"
OUT_XRS = "goes16-xrays-2024-05-10T03-09.jsonl"
OUT_PROTONS = "goes13-protons-2017-09-10T16-22.jsonl"

GOES_R_EPOCH = dt.datetime(2000, 1, 1, 12, 0, 0)


def find_schema():
    d = HERE
    while True:
        p = os.path.join(d, "schemas", "raw.swpc", "v1.schema.json")
        if os.path.isfile(p):
            return p
        parent = os.path.dirname(d)
        if parent == d:
            sys.exit("schemas/raw.swpc/v1.schema.json not found above " + HERE)
        d = parent


def fetched_at(archive_name):
    """The capture time is the Date header saved next to the archive file."""
    with open(os.path.join(ARCHIVE, archive_name + ".headers.txt"), encoding="ascii") as f:
        for line in f:
            if line.lower().startswith("date:"):
                t = email.utils.parsedate_to_datetime(line.split(":", 1)[1].strip())
                return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    sys.exit("no Date header for " + archive_name)


def envelope(product, fetched, url, record):
    return {
        "schema_version": 1,
        "source": "swpc",
        "product": product,
        "fetched_at": fetched,
        "source_url": url,
        "record": record,
    }


def kp_events():
    events = []
    for name in KP_FILES:
        with open(os.path.join(ARCHIVE, name), encoding="ascii") as f:
            lines = f.read().splitlines()
        start = next(i for i, l in enumerate(lines) if l.startswith(":Geomagnetic_Indices:"))
        day = dt.datetime.strptime(lines[start].split(":", 2)[2].strip(), "%Y %b %d")
        planetary = next(i for i in range(start, len(lines)) if "Planetary" in lines[i])
        data = next(l for l in lines[planetary + 1:] if not l.startswith("#"))
        values = data.split()[-8:]
        if len(values) != 8:
            sys.exit("unexpected planetary K line in " + name + ": " + data)
        fetched = fetched_at(name)
        for i, v in enumerate(values):
            kp = float(v)
            if kp < 0:
                continue
            record = {"time_tag": (day + dt.timedelta(hours=3 * i)).strftime("%Y-%m-%dT%H:%M:%S"), "Kp": kp}
            events.append(envelope("swpc.kp", fetched, KP_URL.format(name), record))
    return events


def finite(x):
    return x is not np.ma.masked and math.isfinite(float(x))


def xrs_events():
    ds = netCDF4.Dataset(os.path.join(ARCHIVE, XRS_FILE))
    ds.set_auto_mask(True)
    satellite = int(re.fullmatch(r"g(\d+)", ds.platform).group(1))
    times = ds["time"][:]
    bands = [
        ("0.05-0.4nm", ds["xrsa_flux"][:], ds["xrsa_flux_observed"][:], ds["xrsa_flux_electrons"][:]),
        ("0.1-0.8nm", ds["xrsb_flux"][:], ds["xrsb_flux_observed"][:], ds["xrsb_flux_electrons"][:]),
    ]
    fetched = fetched_at(XRS_FILE)
    events, skipped = [], 0
    for k, seconds in enumerate(times):
        t = GOES_R_EPOCH + dt.timedelta(seconds=float(seconds))
        if not (XRS_WINDOW[0] <= t < XRS_WINDOW[1]):
            continue
        for energy, flux, observed, electrons in bands:
            if not (finite(flux[k]) and finite(observed[k]) and finite(electrons[k])):
                skipped += 1
                continue
            record = {
                "time_tag": t.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "satellite": satellite,
                "flux": float(flux[k]),
                "observed_flux": float(observed[k]),
                "electron_correction": float(electrons[k]),
                "energy": energy,
            }
            events.append(envelope("swpc.goes.xrays", fetched, XRS_URL, record))
    ds.close()
    return events, skipped


def proton_events():
    with gzip.open(os.path.join(ARCHIVE, PROTON_ARCHIVE), "rt", encoding="ascii", newline="") as f:
        lines = f.read().splitlines()
    sat_line = next(l for l in lines if l.startswith(":satellite_id"))
    satellite = int(re.search(r'"GOES-(\d+)"', sat_line).group(1))
    start = next(i for i, l in enumerate(lines) if l.startswith("data:"))
    rows = csv.DictReader(lines[start + 1:])
    fetched = fetched_at(PROTON_FILE)
    events, skipped = [], 0
    for row in rows:
        if not row.get("time_tag"):
            break
        t = dt.datetime.strptime(row["time_tag"], "%Y-%m-%d %H:%M:%S.%f")
        if not (PROTON_WINDOW[0] <= t < PROTON_WINDOW[1]):
            continue
        for mev in PROTON_THRESHOLDS_MEV:
            flux = float(row["ZPGT%dE" % mev])
            if flux == -99999.0 or not math.isfinite(flux):
                skipped += 1
                continue
            record = {
                "time_tag": t.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "satellite": satellite,
                "flux": flux,
                "energy": ">=%d MeV" % mev,
            }
            events.append(envelope("swpc.goes.protons", fetched, PROTON_URL, record))
    return events, skipped


def write(name, events, validator):
    for n, e in enumerate(events, 1):
        errors = sorted(validator.iter_errors(e), key=lambda err: list(err.path))
        if errors:
            sys.exit("%s event %d invalid: %s" % (name, n, errors[0].message))
    body = "".join(json.dumps(e, separators=(",", ":")) + "\n" for e in events).encode("ascii")
    with open(os.path.join(FIXTURES, name), "wb") as f:
        f.write(body)
    print("%s  %d events  %d bytes  sha256 %s" % (name, len(events), len(body), hashlib.sha256(body).hexdigest()))


def main():
    if not os.path.isdir(ARCHIVE):
        sys.exit("archive folder not found: " + ARCHIVE)
    with open(find_schema(), encoding="utf-8") as f:
        schema = json.load(f)
    validator = jsonschema.Draft202012Validator(schema)
    write(OUT_KP, kp_events(), validator)
    xrs, xrs_skipped = xrs_events()
    write(OUT_XRS, xrs, validator)
    protons, protons_skipped = proton_events()
    write(OUT_PROTONS, protons, validator)
    print("skipped non finite or missing values: xrays %d, protons %d" % (xrs_skipped, protons_skipped))


if __name__ == "__main__":
    main()
