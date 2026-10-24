"""Tests for gen_alerts.py. Run with python3 -I deploy/load/test_gen_alerts.py from anywhere; needs jsonschema."""
import json
import os
import subprocess
import sys
import unittest

import jsonschema

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "gen_alerts.py")
SCHEMA = os.path.join(HERE, "..", "..", "schemas", "alerts", "v1.schema.json")
EXAMPLES = os.path.join(HERE, "..", "..", "schemas", "alerts", "examples")
sys.path.insert(0, HERE)

import gen_alerts  # noqa: E402


def run(*args):
    out = subprocess.run([sys.executable, "-I", SCRIPT, *args], check=True, capture_output=True, text=True)
    return out.stdout


def lines(count, seed, *extra):
    return [line.split("\t", 1) for line in run("--count", str(count), "--seed", str(seed), *extra).splitlines()]


class GenAlertsTest(unittest.TestCase):

    def test_every_event_matches_the_alerts_schema(self):
        with open(SCHEMA) as f:
            validator = jsonschema.Draft202012Validator(json.load(f))
        for _, value in lines(2000, 1):
            validator.validate(json.loads(value))

    def test_event_ids_are_unique(self):
        ids = [json.loads(value)["event_id"] for _, value in lines(10000, 1)]
        self.assertEqual(len(ids), len(set(ids)))

    def test_the_same_seed_gives_the_same_bytes_and_another_seed_differs(self):
        self.assertEqual(run("--count", "500", "--seed", "7"), run("--count", "500", "--seed", "7"))
        self.assertNotEqual(run("--count", "500", "--seed", "7"), run("--count", "500", "--seed", "8"))

    def test_the_space_weather_share_is_within_one_point(self):
        events = [json.loads(value) for _, value in lines(10000, 1, "--space-weather-share", "0.1")]
        share = sum(e["kind"] == "space_weather_level" for e in events) / len(events)
        self.assertAlmostEqual(share, 0.1, delta=0.01)

    def test_keys_are_the_ones_the_risk_engine_writes(self):
        for key, value in lines(500, 1):
            event = json.loads(value)
            if event["kind"] == "close_approach":
                self.assertEqual(key, event["close_approach"]["run_id"])
            else:
                self.assertEqual(key, "space_weather." + event["space_weather_level"]["scale"])

    def test_each_id_is_built_from_the_event_fields(self):
        for _, value in lines(500, 1):
            event = json.loads(value)
            if event["kind"] == "close_approach":
                p = event["close_approach"]
                expected = "/".join(["close_approach", str(event["rules_version"]), p["run_id"],
                                     str(p["watchlist_object"]["catalog_number"]),
                                     str(p["other_object"]["catalog_number"]), p["time_of_closest_approach"]])
            else:
                p = event["space_weather_level"]
                expected = "/".join(["space_weather_level", str(event["rules_version"]), p["scale"],
                                     str(p.get("satellite", "-")), p["time_tag"], p["fetched_at"]])
            self.assertEqual(event["event_id"], expected)

    def test_the_builders_reproduce_each_committed_example_id(self):
        for name in gen_alerts.SPACE_WEATHER:
            example = gen_alerts.load(name)
            self.assertEqual(gen_alerts.space_weather(example, 0)[1]["event_id"], example["event_id"])
        example = gen_alerts.load("valid-close-approach.json")
        other = str(example["close_approach"]["other_object"]["catalog_number"])
        expected = example["event_id"].replace("/" + other + "/", "/" + str(gen_alerts.FIRST_OTHER_NUMBER) + "/")
        self.assertEqual(gen_alerts.close_approach(example, 0)[1]["event_id"], expected)

    def test_a_count_of_zero_prints_nothing(self):
        self.assertEqual(run("--count", "0", "--seed", "1"), "")


if __name__ == "__main__":
    unittest.main()
