"""Tests for merge-region-pack.py: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import hashlib
import importlib.util
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location("merge", os.path.join(os.path.dirname(__file__), "merge-region-pack.py"))
merge = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(merge)

OLD = {"url": "https://old/spain_madrid.mwm", "size": 1, "sha256": "a" * 64, "file": "spain_madrid.mwm"}


def catalog():
    return {
        "schema": 1,
        "catalogVersion": "2026-10-26",
        "base": {"version": "261004"},
        "regions": [
            {"id": "spain", "comapsId": "Spain", "name": "Spain", "parent": None, "version": "261004"},
            {"id": "spain_madrid", "comapsId": "Spain_Madrid", "name": "Madrid", "parent": "spain", "version": "261004",
             "assets": {"render": dict(OLD), "search": dict(OLD)}},
            {"id": "china_shanghai", "comapsId": "China_Shanghai", "name": "China - Shanghai", "parent": "china", "version": "261004"},
            {"id": "china_hebei", "comapsId": "China_Hebei", "name": "China - Hebei", "parent": "china", "version": "261004"},
            {"id": "china_gansu", "comapsId": "China_Gansu", "name": "China - Gansu", "parent": "china", "version": "261004"},
        ],
    }


def put(path, data):
    with open(path, "wb") as f:
        f.write(data)


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        put(os.path.join(self.dir, "china_shanghai.pmtiles"), b"tiles")
        put(os.path.join(self.dir, "china_shanghai.mwm"), b"map!")
        put(os.path.join(self.dir, "china_hebei.pmtiles"), b"only tiles")  # no .mwm: not downloadable
        put(os.path.join(self.dir, "spain_madrid.pmtiles"), b"new")
        put(os.path.join(self.dir, "spain_madrid.mwm"), b"new")

    def tearDown(self):
        self.tmp.cleanup()

    def regions(self, cat):
        return {r["id"]: r for r in cat["regions"]}

    def test_a_region_with_both_files_gets_its_assets(self):
        out, added = merge.merge(catalog(), self.dir, "https://host/rel/TAG")
        self.assertEqual(1, added)
        a = self.regions(out)["china_shanghai"]["assets"]
        self.assertEqual("https://host/rel/TAG/china_shanghai.pmtiles", a["render"]["url"])
        self.assertEqual("https://host/rel/TAG/china_shanghai.mwm", a["search"]["url"])
        self.assertEqual(5, a["render"]["size"])
        self.assertEqual(hashlib.sha256(b"map!").hexdigest(), a["search"]["sha256"])
        self.assertEqual("china_shanghai.mwm", a["search"]["file"])

    def test_a_region_with_one_file_or_none_is_left_without_assets(self):
        out, _ = merge.merge(catalog(), self.dir, "https://host/rel/TAG/")
        regions = self.regions(out)
        self.assertNotIn("assets", regions["china_hebei"])
        self.assertNotIn("assets", regions["china_gansu"])
        self.assertNotIn("assets", regions["spain"])

    def test_existing_assets_stay_unless_replaced(self):
        out, _ = merge.merge(catalog(), self.dir, "https://host/rel/TAG")
        self.assertEqual(OLD, self.regions(out)["spain_madrid"]["assets"]["render"])
        out, added = merge.merge(catalog(), self.dir, "https://host/rel/TAG", replace=True)
        self.assertEqual(2, added)
        self.assertEqual("https://host/rel/TAG/spain_madrid.pmtiles", self.regions(out)["spain_madrid"]["assets"]["render"]["url"])

    def test_the_other_blocks_and_the_input_are_untouched(self):
        base = catalog()
        out, _ = merge.merge(base, self.dir, "https://host/rel/TAG", catalog_version="v")
        self.assertEqual({"version": "261004"}, out["base"])
        self.assertEqual("v", out["catalogVersion"])
        self.assertNotIn("assets", self.regions(base)["china_shanghai"])  # the input was not modified


if __name__ == "__main__":
    unittest.main()
