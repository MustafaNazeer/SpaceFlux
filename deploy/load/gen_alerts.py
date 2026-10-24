"""Writes a fixed set of schema valid alert events for load tests, one "key<TAB>value" line each.

Every event is a committed example from schemas/alerts/examples/ with a few fields changed so its event_id, which
is built from those fields as topics.md describes, is unique: a close approach gets its own other object catalog
number, a space weather level its own fetched_at. The same --seed always gives the same bytes. The events are
generated, not observed, and are for load tests only.
"""

import argparse
import copy
import json
import os
import random
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
EXAMPLES = os.path.join(HERE, "..", "..", "schemas", "alerts", "examples")
SPACE_WEATHER = ["valid-r-level.json", "valid-s-level.json", "valid-g-level.json"]
FIRST_OTHER_NUMBER = 10_000_000


def load(name):
    with open(os.path.join(EXAMPLES, name)) as f:
        return json.load(f)


def utc(text):
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def close_approach(template, index):
    e = copy.deepcopy(template)
    p = e["close_approach"]
    p["other_object"]["catalog_number"] = FIRST_OTHER_NUMBER + index
    e["event_id"] = "/".join(["close_approach", str(e["rules_version"]), p["run_id"],
                              str(p["watchlist_object"]["catalog_number"]),
                              str(p["other_object"]["catalog_number"]), p["time_of_closest_approach"]])
    return p["run_id"], e


def space_weather(template, index):
    e = copy.deepcopy(template)
    p = e["space_weather_level"]
    p["fetched_at"] = (utc(p["fetched_at"]) + timedelta(seconds=index)).strftime("%Y-%m-%dT%H:%M:%SZ")
    e["event_id"] = "/".join(["space_weather_level", str(e["rules_version"]), p["scale"],
                              str(p.get("satellite", "-")), p["time_tag"], p["fetched_at"]])
    return "space_weather." + p["scale"], e


def generate(count, seed, share):
    """Yields (key, compact JSON value) for count events, about share of them space weather levels."""
    rng = random.Random(seed)
    approach = load("valid-close-approach.json")
    levels = [load(name) for name in SPACE_WEATHER]
    for index in range(count):
        if rng.random() < share:
            # random() is the one sequence Python keeps the same across versions for a seed.
            key, event = space_weather(levels[int(rng.random() * len(levels))], index)
        else:
            key, event = close_approach(approach, index)
        yield key, json.dumps(event, separators=(",", ":"))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--space-weather-share", type=float, default=0.1)
    args = parser.parse_args(argv)
    if args.count < 0 or not 0 <= args.space_weather_share <= 1:
        parser.error("--count must be 0 or more and --space-weather-share between 0 and 1")
    out = sys.stdout
    for key, value in generate(args.count, args.seed, args.space_weather_share):
        out.write(key + "\t" + value + "\n")


if __name__ == "__main__":
    main(sys.argv[1:])
