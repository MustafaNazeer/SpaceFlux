#!/usr/bin/env python3
"""Pair each X-ray class in SWPC's event report for 2024 May 10 with the GOES-16
long band flux at the maximum minute the report gives.

Reads two committed files of the storm fixtures, the event report
evidence/20240510dayevt.txt and the NCEI netCDF file
archive/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc, after checking the SHA256 of
each against the value recorded in the storm fixtures' PROVENANCE.md (it stops
if either differs), and writes one row per report line that carries an X-ray
class. The storm fixture folder and the output folder
are src/test/resources/swpc-storms and src/test/resources/swpc-xrays of the
module that holds this tools/ folder, so every path is derived from this
script's location. No network access.
"""

import datetime as dt
import hashlib
import json
import math
import os
import re
import sys

import netCDF4
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(os.path.dirname(HERE))
RESOURCES = os.path.join(MODULE, "src", "test", "resources")
STORMS = os.path.join(RESOURCES, "swpc-storms")
OUT_DIR = os.path.join(RESOURCES, "swpc-xrays")

REPORT = "evidence/20240510dayevt.txt"
XRS = "archive/dn_xrsf-l2-avg1m_g16_d20240510_v2-2-1.nc"
OUT = "goes16-flare-classes-2024-05-10.json"

INPUT_SHA256 = {
    REPORT: "aa14b697252c06a0b33373038e8f920469a78b7b82ebb91dbd5fd1c49ed48274",
    XRS: "8a79071bf6d38e4e726522505195b49ee249c8eb9461d4c3d89fb1ca0afad063",
}

GOES_R_EPOCH = dt.datetime(2000, 1, 1, 12, 0, 0)
EVENT_LINE = re.compile(r"^ (\d{4}) (\d{4}) (\d{4}) ")
XRAY_CLASS = re.compile(r"(?<![A-Za-z0-9])([ABCMX]\d+\.\d)(?![0-9])")


def report_rows():
    with open(os.path.join(STORMS, REPORT), encoding="ascii", newline="") as f:
        lines = f.read().split("\n")
    header = next(i for i, l in enumerate(lines) if l.startswith(":Energetic_Solar_Events:"))
    day = dt.datetime.strptime(lines[header].split(":", 2)[2].strip(), "%Y %b %d")
    rows = []
    for i in range(header + 1, len(lines)):
        m = EVENT_LINE.match(lines[i])
        if not m:
            continue
        classes = XRAY_CLASS.findall(lines[i])
        if not classes:
            continue
        if len(classes) != 1:
            sys.exit("more than one X-ray class on report line %d" % (i + 1))
        hhmm = m.group(2)
        rows.append({
            "report_line": i + 1,
            "report_text": lines[i],
            "swpc_class": classes[0],
            "max_time": day + dt.timedelta(hours=int(hhmm[:2]), minutes=int(hhmm[2:])),
        })
    return rows


def check_inputs():
    for name, expected in INPUT_SHA256.items():
        with open(os.path.join(STORMS, name), "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        if digest != expected:
            sys.exit("%s SHA256 %s does not match the recorded %s" % (name, digest, expected))


def main():
    check_inputs()
    rows = report_rows()
    ds = netCDF4.Dataset(os.path.join(STORMS, XRS))
    ds.set_auto_mask(True)
    satellite = int(re.fullmatch(r"g(\d+)", ds.platform).group(1))
    times = [GOES_R_EPOCH + dt.timedelta(seconds=float(s)) for s in ds["time"][:]]
    index = {t: k for k, t in enumerate(times)}
    flux = ds["xrsb_flux"]
    if flux.dtype != np.float32:
        sys.exit("xrsb_flux is %s, not float32" % flux.dtype)
    values, flags = flux[:], ds["xrsb_flag"][:]

    out = []
    for r in rows:
        k = index.get(r["max_time"])
        if k is None:
            sys.exit("no netCDF minute at " + r["max_time"].isoformat())
        v = values[k]
        if v is np.ma.masked or not math.isfinite(float(v)):
            sys.exit("no valid xrsb_flux at " + r["max_time"].isoformat())
        out.append({
            "time_tag": r["max_time"].strftime("%Y-%m-%dT%H:%M:%SZ"),
            "satellite": satellite,
            "flux": float(v),
            "xrsb_flag": int(flags[k]),
            "swpc_class": r["swpc_class"],
            "report_line": r["report_line"],
            "report_text": r["report_text"],
        })
    ds.close()

    body = ("[\n" + ",\n".join(json.dumps(o, separators=(", ", ": ")) for o in out) + "\n]\n").encode("ascii")
    with open(os.path.join(OUT_DIR, OUT), "wb") as f:
        f.write(body)
    print("%s  %d rows  %d bytes  sha256 %s" % (OUT, len(out), len(body), hashlib.sha256(body).hexdigest()))


if __name__ == "__main__":
    main()
