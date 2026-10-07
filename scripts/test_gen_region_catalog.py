"""Pruebas de gen-region-catalog.py: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import hashlib
import importlib.util
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location("gen", os.path.join(os.path.dirname(__file__), "gen-region-catalog.py"))
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)

COUNTRIES = {
    "id": "Countries", "v": 261004, "map_series": "2026.06.28",
    "g": [
        {"id": "Andorra", "s": 5, "sha1_base64": "x", "old": ["Andorra"], "affiliations": ["Andorra"]},
        {"id": "Spain", "g": [
            {"id": "Spain_Community of Madrid", "s": 3, "sha1_base64": "y"},
            {"id": "Spain_Castile and Leon_West", "s": 4, "sha1_base64": "z"},
        ]},
    ],
}


def put(path, data):
    with open(path, "wb") as f:
        f.write(data)


class GenCatalogTest(unittest.TestCase):
    def test_hierarchy_without_files_has_no_assets(self):
        c = gen.build(COUNTRIES, catalog_version="t")
        ids = [r["id"] for r in c["regions"]]
        self.assertEqual(["andorra", "spain", "spain_community-of-madrid", "spain_castile-and-leon_west"], ids)
        self.assertTrue(all("assets" not in r for r in c["regions"]))
        by = {r["id"]: r for r in c["regions"]}
        self.assertIsNone(by["spain"]["parent"])
        self.assertEqual("spain", by["spain_community-of-madrid"]["parent"])
        self.assertEqual("Community of Madrid", by["spain_community-of-madrid"]["name"])
        self.assertEqual("Castile and Leon - West", by["spain_castile-and-leon_west"]["name"])
        self.assertEqual("261004", by["spain"]["version"])
        self.assertEqual(1, c["schema"])

    def test_both_files_give_sha256_and_urls(self):
        with tempfile.TemporaryDirectory() as t:
            mwm, pm = os.path.join(t, "mwm"), os.path.join(t, "pm")
            os.makedirs(mwm)
            os.makedirs(pm)
            put(os.path.join(mwm, "Spain_Community of Madrid.mwm"), b"abc")
            put(os.path.join(pm, "spain_community-of-madrid.pmtiles"), b"0123456789")
            put(os.path.join(mwm, "Andorra.mwm"), b"12345")  # sin pmtiles: no descargable
            c = gen.build(COUNTRIES, mwm, pm, "https://m.example/mwm", "https://m.example/pm", "t")
        by = {r["id"]: r for r in c["regions"]}
        a = by["spain_community-of-madrid"]["assets"]
        self.assertEqual(hashlib.sha256(b"abc").hexdigest(), a["search"]["sha256"])
        self.assertEqual(3, a["search"]["size"])
        self.assertEqual("https://m.example/mwm/Spain_Community%20of%20Madrid.mwm", a["search"]["url"])
        self.assertEqual("spain_community-of-madrid.mwm", a["search"]["file"])
        self.assertEqual(hashlib.sha256(b"0123456789").hexdigest(), a["render"]["sha256"])
        self.assertEqual("https://m.example/pm/spain_community-of-madrid.pmtiles", a["render"]["url"])
        self.assertNotIn("assets", by["andorra"])
        self.assertNotIn("assets", by["spain_castile-and-leon_west"])

    def test_mwm_url_by_slug(self):
        with tempfile.TemporaryDirectory() as t:
            mwm, pm = os.path.join(t, "mwm"), os.path.join(t, "pm")
            os.makedirs(mwm)
            os.makedirs(pm)
            put(os.path.join(mwm, "Spain_Community of Madrid.mwm"), b"abc")
            put(os.path.join(pm, "spain_community-of-madrid.pmtiles"), b"0123456789")
            c = gen.build(COUNTRIES, mwm, pm, "https://m.example/d", "https://m.example/d", "t", mwm_url_by_slug=True)
        a = {r["id"]: r for r in c["regions"]}["spain_community-of-madrid"]["assets"]
        self.assertEqual("https://m.example/d/spain_community-of-madrid.mwm", a["search"]["url"])

    def test_base_block_with_world_files(self):
        with tempfile.TemporaryDirectory() as t:
            put(os.path.join(t, "World.mwm"), b"w" * 7)
            self.assertNotIn("base", gen.build(COUNTRIES, catalog_version="t", base_dir=t, base_url="https://x/rel"))
            put(os.path.join(t, "WorldCoasts.mwm"), b"c" * 9)
            b = gen.build(COUNTRIES, catalog_version="t", base_dir=t, base_url="https://x/rel")["base"]
            self.assertEqual("261004", b["version"])
            self.assertEqual("https://x/rel/World.mwm", b["world"]["url"])
            self.assertEqual(hashlib.sha256(b"w" * 7).hexdigest(), b["world"]["sha256"])
            self.assertEqual(9, b["worldCoasts"]["size"])
            self.assertEqual("WorldCoasts.mwm", b["worldCoasts"]["file"])
        self.assertNotIn("base", gen.build(COUNTRIES, catalog_version="t"))

    def test_size_mismatch_with_countries_txt_is_skipped(self):
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, "pm"))
            put(os.path.join(t, "Andorra.mwm"), b"1234")  # countries.txt dice 5
            put(os.path.join(t, "pm", "andorra.pmtiles"), b"x")
            msgs = []
            c = gen.build(COUNTRIES, t, os.path.join(t, "pm"), None, "https://m.example/pm", "t", log=msgs.append)
        self.assertNotIn("assets", c["regions"][0])
        self.assertTrue(any("Andorra" in m for m in msgs))

    def test_download_cap(self):
        with self.assertRaises(SystemExit):
            gen.fetch_mwm("https://m.example/", "Andorra", 21 << 20, tempfile.gettempdir(), 20 << 20)

    def test_real_countries_file_if_present(self):
        p = os.environ.get("COUNTRIES_TXT") or os.path.join(
            os.path.dirname(__file__), "..", "third_party", "comaps", "data", "countries.txt")
        if not os.path.isfile(p):
            self.skipTest("submódulo sin inicializar")
        import json
        with open(p, encoding="utf-8") as f:
            c = gen.build(json.load(f), catalog_version="t")
        ids = {r["id"] for r in c["regions"]}
        self.assertIn("spain_community-of-madrid", ids)
        self.assertEqual(len(ids), len(c["regions"]))


if __name__ == "__main__":
    unittest.main()
