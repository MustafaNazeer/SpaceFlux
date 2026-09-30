#!/usr/bin/env python3
"""Cut the eclipse zero run fixture out of a full capture of SWPC's
json/goes/primary/xrays-7-day.json.

Usage: eclipse_subset.py <path to the full capture>

The full capture is not in the repository (4.5 MB). This script checks its
SHA256 against the one recorded in PROVENANCE.md, keeps every record whose
time_tag lies in the window below, and writes those records' original bytes,
unchanged and in file order, as a JSON array with the separators of the source
file. It then checks that the output parses to exactly the selected records and
validates each one, wrapped as a raw.swpc event, against
schemas/raw.swpc/v1.schema.json. The fixture folder is
src/test/resources/swpc-xrays of the module that holds this tools/ folder, and
the schema is found by walking up from this script's folder, so every path is
derived from this script's location. No network access.
"""

import hashlib
import json
import os
import sys

import jsonschema

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(os.path.dirname(HERE))
FIXTURES = os.path.join(MODULE, "src", "test", "resources", "swpc-xrays")

SOURCE_URL = "https://services.swpc.noaa.gov/json/goes/primary/xrays-7-day.json"
SOURCE_SHA256 = "79fa1874e9a2d9db39b4791447ef36721c4a6b872a920491c683b20b3a6219f2"
FETCHED_AT = "2026-09-30T19:18:36Z"

# The zero run on GOES-18 from 2026-09-24T08:27:00Z to 09:32:00Z, plus 60 minutes
# on each side. time_tag strings of this fixed form compare in time order.
WINDOW = ("2026-09-24T07:27:00Z", "2026-09-24T10:32:00Z")
OUT = "goes18-xrays-7-day-eclipse-2026-09-24.json"


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


def record_spans(text):
    """Yield (start, end, record) for each element of the top level array."""
    decoder = json.JSONDecoder()
    i = text.index("[") + 1
    while True:
        while text[i] in " \r\n\t,":
            i += 1
        if text[i] == "]":
            return
        record, end = decoder.raw_decode(text, i)
        yield i, end, record
        i = end


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], "rb") as f:
        raw = f.read()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != SOURCE_SHA256:
        sys.exit("capture SHA256 %s does not match the recorded %s" % (digest, SOURCE_SHA256))
    text = raw.decode("ascii")
    full = json.loads(text)

    kept, n = [], 0
    for start, end, record in record_spans(text):
        if record != full[n]:
            sys.exit("span %d does not parse to record %d" % (n, n))
        n += 1
        if WINDOW[0] <= record["time_tag"] <= WINDOW[1]:
            kept.append((text[start:end], record))
    if n != len(full):
        sys.exit("found %d spans for %d records" % (n, len(full)))

    body = ("[" + ", ".join(s for s, _ in kept) + "]").encode("ascii")
    if json.loads(body) != [r for _, r in kept]:
        sys.exit("output does not parse to the selected records")

    with open(find_schema(), encoding="utf-8") as f:
        validator = jsonschema.Draft202012Validator(json.load(f))
    for k, (_, record) in enumerate(kept, 1):
        event = {
            "schema_version": 1,
            "source": "swpc",
            "product": "swpc.goes.xrays",
            "fetched_at": FETCHED_AT,
            "source_url": SOURCE_URL,
            "record": record,
        }
        errors = list(validator.iter_errors(event))
        if errors:
            sys.exit("record %d invalid: %s" % (k, errors[0].message))

    with open(os.path.join(FIXTURES, OUT), "wb") as f:
        f.write(body)
    print("%s  %d of %d records  %d bytes  sha256 %s"
          % (OUT, len(kept), len(full), len(body), hashlib.sha256(body).hexdigest()))


if __name__ == "__main__":
    main()
