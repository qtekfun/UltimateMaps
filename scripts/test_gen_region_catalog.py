"""Tests for gen-region-catalog.py: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
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
            put(os.path.join(mwm, "Andorra.mwm"), b"12345")  # no pmtiles: not downloadable
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

    def test_optional_cameras_block(self):
        with tempfile.TemporaryDirectory() as t:
            f = os.path.join(t, "speedcams-es.bin")
            put(f, b"c" * 11)
            c = gen.build(COUNTRIES, catalog_version="t", cameras_file=f, cameras_base="https://x/rel")["cameras"]
            self.assertEqual("https://x/rel/speedcams-es.bin", c["url"])
            self.assertEqual(11, c["size"])
            self.assertEqual(hashlib.sha256(b"c" * 11).hexdigest(), c["sha256"])
            self.assertEqual("speedcams-es.bin", c["file"])
            self.assertNotIn("cameras", gen.build(COUNTRIES, catalog_version="t", cameras_file=os.path.join(t, "nope.bin"), cameras_base="https://x"))
        self.assertNotIn("cameras", gen.build(COUNTRIES, catalog_version="t"))

    def test_optional_chargers_block(self):
        with tempfile.TemporaryDirectory() as t:
            f = os.path.join(t, "chargers-es.bin")
            put(f, b"e" * 7)
            c = gen.build(COUNTRIES, catalog_version="t", chargers_file=f, chargers_base="https://x/rel/")["chargers"]
            self.assertEqual("https://x/rel/chargers-es.bin", c["url"])
            self.assertEqual(7, c["size"])
            self.assertEqual(hashlib.sha256(b"e" * 7).hexdigest(), c["sha256"])
            self.assertEqual("chargers-es.bin", c["file"])
            # backward compatible: no option, a missing file, and the cameras block are all independent of it
            self.assertNotIn("chargers", gen.build(COUNTRIES, catalog_version="t", chargers_file=os.path.join(t, "nope.bin"), chargers_base="https://x"))
            with self.assertRaises(SystemExit):
                gen.build(COUNTRIES, catalog_version="t", chargers_file=f)
            cat = gen.build(COUNTRIES, catalog_version="t", chargers_file=f, chargers_base="https://x")
            self.assertNotIn("cameras", cat)
        self.assertNotIn("chargers", gen.build(COUNTRIES, catalog_version="t"))

    def test_optional_transit_block(self):
        import json
        with tempfile.TemporaryDirectory() as t:
            f = os.path.join(t, "transit-madrid.umti")
            put(f, b"u" * 13)
            meta = {"id": "madrid", "city": "Madrid", "timezone": "Europe/Madrid", "validFrom": "2026-10-07",
                    "validTo": "2026-11-05", "bounds": [39.8, -4.6, 41.2, -3.0], "attribution": ["Powered by CRTM"]}
            with open(os.path.join(t, "transit-madrid.json"), "w") as m:
                json.dump(meta, m)
            tr = gen.build(COUNTRIES, catalog_version="t", transit_files=[f], transit_base="https://x/rel")["transit"]
            self.assertEqual(1, len(tr))
            self.assertEqual("https://x/rel/transit-madrid.umti", tr[0]["url"])
            self.assertEqual(13, tr[0]["size"])
            self.assertEqual(hashlib.sha256(b"u" * 13).hexdigest(), tr[0]["sha256"])
            self.assertEqual("2026-11-05", tr[0]["validTo"])
            self.assertEqual(["Powered by CRTM"], tr[0]["attribution"])
            # a missing sidecar skips the file; a sidecar without attribution is an error
            put(os.path.join(t, "transit-other.umti"), b"o")
            self.assertNotIn("transit", gen.build(COUNTRIES, catalog_version="t", transit_files=[os.path.join(t, "transit-other.umti")], transit_base="https://x"))
            del meta["attribution"]
            with open(os.path.join(t, "transit-madrid.json"), "w") as m:
                json.dump(meta, m)
            with self.assertRaises(SystemExit):
                gen.build(COUNTRIES, catalog_version="t", transit_files=[f], transit_base="https://x")
        self.assertNotIn("transit", gen.build(COUNTRIES, catalog_version="t"))

    def test_size_mismatch_with_countries_txt_is_skipped(self):
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, "pm"))
            put(os.path.join(t, "Andorra.mwm"), b"1234")  # countries.txt dice 5
            put(os.path.join(t, "pm", "andorra.pmtiles"), b"x")
            msgs = []
            c = gen.build(COUNTRIES, t, os.path.join(t, "pm"), None, "https://m.example/pm", "t", log=msgs.append)
        self.assertNotIn("assets", c["regions"][0])
        self.assertTrue(any("Andorra" in m for m in msgs))

    def test_names_are_optional_and_per_language(self):
        names = {"es": {"Spain": "Espana", "Spain_Community of Madrid": "Comunidad de Madrid",
                        "Andorra": "Andorra", "Spain_Castile and Leon_West": "Castilla y Leon — Oeste"}}
        c = gen.build(COUNTRIES, catalog_version="t", names=names)
        by = {r["id"]: r for r in c["regions"]}
        self.assertEqual({"es": "Espana"}, by["spain"]["names"])
        self.assertEqual({"es": "Comunidad de Madrid"}, by["spain_community-of-madrid"]["names"])
        self.assertNotIn("names", by["andorra"])  # same as the English name: nothing to add
        self.assertEqual("Community of Madrid", by["spain_community-of-madrid"]["name"])  # `name` is untouched
        plain = gen.build(COUNTRIES, catalog_version="t")
        self.assertTrue(all("names" not in r for r in plain["regions"]))

    def test_load_names_skips_missing_languages_and_short_description_keys(self):
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, "es.json"))
            with open(os.path.join(t, "es.json", "localize.json"), "w", encoding="utf-8") as f:
                f.write('{"Spain": "Espa\\u00f1a", "Spain Short": "x", "Spain Description": "y", "Dash": "A \\u2014 B", "Empty": " "}')
            logs = []
            got = gen.load_names(t, ["es", "gl"], log=logs.append)
        self.assertEqual({"es": {"Spain": "España", "Dash": "A - B"}}, got)
        self.assertEqual(1, len(logs))
        with self.assertRaises(SystemExit):
            gen.load_names(t, ["../x"])

    def test_base_files_get_no_region_entry(self):
        countries = {"id": "Countries", "v": 1, "g": [{"id": "World", "s": 1}, {"id": "WorldCoasts", "s": 1},
                                                      {"id": "Andorra", "s": 5}]}
        c = gen.build(countries, catalog_version="t")
        self.assertEqual(["andorra"], [r["id"] for r in c["regions"]])

    def test_download_cap(self):
        with self.assertRaises(SystemExit):
            gen.fetch_mwm("https://m.example/", "Andorra", 21 << 20, tempfile.gettempdir(), 20 << 20)

    def test_real_countries_file_if_present(self):
        p = os.environ.get("COUNTRIES_TXT") or os.path.join(
            os.path.dirname(__file__), "..", "third_party", "comaps", "data", "countries.txt")
        if not os.path.isfile(p):
            self.skipTest("submodule not initialized")
        import json
        with open(p, encoding="utf-8") as f:
            c = gen.build(json.load(f), catalog_version="t")
        ids = {r["id"] for r in c["regions"]}
        self.assertIn("spain_community-of-madrid", ids)
        self.assertEqual(len(ids), len(c["regions"]))


if __name__ == "__main__":
    unittest.main()
