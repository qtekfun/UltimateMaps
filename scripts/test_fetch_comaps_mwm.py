"""Tests for fetch-comaps-mwm.py (no network): python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import importlib.util
import os
import unittest

_spec = importlib.util.spec_from_file_location("fetch", os.path.join(os.path.dirname(__file__), "fetch-comaps-mwm.py"))
fetch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fetch)

COUNTRIES = {
    "id": "Countries", "v": 261004, "map_series": "2026.06.28",
    "g": [
        {"id": "Andorra", "s": 5, "sha1_base64": "x"},
        {"id": "People's Republic of China", "g": [
            {"id": "China_Shanghai", "s": 7, "sha1_base64": "a"},
            {"id": "China_Tibet Autonomous Region", "s": 9, "sha1_base64": "b"},
        ]},
        {"id": "Spain", "g": [{"id": "Spain_Aragon", "s": 3, "sha1_base64": "c"}]},
    ],
}


class SelectTest(unittest.TestCase):
    def test_every_leaf_of_a_group(self):
        ids = [n["id"] for n in fetch.select(COUNTRIES, "People's Republic of China")]
        self.assertEqual(["China_Shanghai", "China_Tibet Autonomous Region"], ids)

    def test_a_leaf_is_a_group_of_one(self):
        self.assertEqual(["Andorra"], [n["id"] for n in fetch.select(COUNTRIES, "Andorra")])

    def test_only_limits_the_leaves(self):
        ids = [n["id"] for n in fetch.select(COUNTRIES, "People's Republic of China", ["China_Shanghai"])]
        self.assertEqual(["China_Shanghai"], ids)

    def test_an_unknown_group_is_an_error(self):
        with self.assertRaises(KeyError):
            fetch.select(COUNTRIES, "Atlantis")


if __name__ == "__main__":
    unittest.main()
