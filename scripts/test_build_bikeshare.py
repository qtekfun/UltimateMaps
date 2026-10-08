#!/usr/bin/env python3
"""Tests for scripts/build-bikeshare.py (no network). Run: python3 -m unittest scripts/test_build_bikeshare.py

The fixtures are tiny and synthetic but use the field spellings seen in the real GBFS 3.0 station_information of Bicing and
BiciMAD (names as lists of {text, language}, `capacity`, `vehicle_docks_capacity`), looked at on 2026-10-08.
"""
import importlib.util
import io
import json
import os
import struct
import tempfile
import unittest
import zlib
from contextlib import redirect_stderr, redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_bikeshare", os.path.join(HERE, "build-bikeshare.py"))
bb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bb)


def v3_name(text):
    return [{"text": text + " (ca)", "language": "ca"}, {"text": text, "language": "es"}, {"text": text + " (en)", "language": "en"}]


def station(i, lat=41.40, lon=2.18, **kw):
    s = {"station_id": str(i), "name": v3_name(f"Calle {i}"), "lat": lat + i * 0.001, "lon": lon, "capacity": 20 + i}
    s.update(kw)
    return s


def doc(stations):
    return {"last_updated": "2026-10-08T14:59:14Z", "ttl": 0, "version": "3.0", "data": {"stations": stations}}


def many(n=25):
    return doc([station(i) for i in range(n)])


class Reader:
    """A minimal reader of the documented layout, to test the writer against."""

    def __init__(self, data):
        self.b, self.p = data, 0

    def u(self, fmt):
        v = struct.unpack_from(">" + fmt, self.b, self.p)
        self.p += struct.calcsize(">" + fmt)
        return v if len(v) > 1 else v[0]

    def utf(self):
        n = self.u("H")
        s = self.b[self.p:self.p + n].decode("utf-8")
        self.p += n
        return s


def read_file(path):
    with open(path, "rb") as f:
        data = f.read()
    assert zlib.crc32(data[:-4]) & 0xFFFFFFFF == struct.unpack(">I", data[-4:])[0]
    r = Reader(data[:-4])
    magic, version, generated, n = r.u("I"), r.u("H"), r.u("q"), r.u("H")
    systems = []
    for _ in range(n):
        sid, name, attr, count = r.utf(), r.utf(), r.utf(), r.u("I")
        st = []
        for _ in range(count):
            st.append((r.utf(), r.utf(), r.u("i") / 1e6, r.u("i") / 1e6, r.u("H")))
        systems.append((sid, name, attr, st))
    assert r.p == len(r.b)
    return magic, version, generated, systems


class ParseTests(unittest.TestCase):
    def test_names_prefer_spanish_then_english_then_first(self):
        self.assertEqual("Calle 1", bb.pick_name(v3_name("Calle 1")))
        self.assertEqual("B", bb.pick_name([{"text": "A", "language": "ca"}, {"text": "B", "language": "en"}]))
        self.assertEqual("A", bb.pick_name([{"text": "A", "language": "ca"}]))
        self.assertEqual("Plain", bb.pick_name("  Plain "))  # GBFS 2.x
        self.assertEqual("", bb.pick_name(None))

    def test_capacity_falls_back_to_docks_and_is_clamped(self):
        self.assertEqual(30, bb.capacity_of({"capacity": 30}))
        self.assertEqual(45, bb.capacity_of({"vehicle_docks_capacity": [{"count": 40}, {"count": 5}]}))
        self.assertEqual(0, bb.capacity_of({}))
        self.assertEqual(65535, bb.capacity_of({"capacity": 10**7}))

    def test_bad_stations_are_dropped(self):
        d = doc([
            station(1),
            station(2, is_virtual_station=True),          # virtual
            station(1),                                    # duplicate id
            {"station_id": "9", "lat": 0, "lon": 0},       # null island
            {"station_id": "10", "lat": 95, "lon": 2},     # out of range
            {"station_id": "11", "lat": "41", "lon": 2},   # not a number
            {"lat": 41.0, "lon": 2.0},                     # no id
            "garbage",
        ])
        self.assertEqual(["1"], [s["id"] for s in bb.parse_stations(d)])

    def test_wrong_document_raises(self):
        for bad in ({}, {"data": {}}, {"data": {"stations": 3}}, []):
            with self.assertRaises(ValueError):
                bb.parse_stations(bad)


class BuildTests(unittest.TestCase):
    def run_main(self, tmp, files, *extra):
        for name, content in files.items():
            with open(os.path.join(tmp, name + ".json"), "w", encoding="utf-8") as f:
                f.write(content if isinstance(content, str) else json.dumps(content))
        out = os.path.join(tmp, "bikeshare-es.bin")
        err, o = io.StringIO(), io.StringIO()
        with redirect_stderr(err), redirect_stdout(o):
            code = bb.main(["--input-dir", tmp, "-o", out, "--generated", "2026-10-08T12:00:00Z", *extra])
        return code, out, err.getvalue()

    def test_two_systems_round_trip(self):
        with tempfile.TemporaryDirectory() as t:
            odd = doc([station(i, name="Cañón de Sant Pau ñ" if i == 0 else "x") for i in range(25)])
            code, out, _ = self.run_main(t, {"bicing": many(30), "bicimad": odd}, "--summary", os.path.join(t, "s.json"))
            self.assertEqual(0, code)
            magic, version, generated, systems = read_file(out)
            self.assertEqual(0x554D424B, magic)
            self.assertEqual(1, version)
            self.assertEqual(1791460800, generated)  # 2026-10-08T12:00:00Z
            self.assertEqual(["bicing", "bicimad"], [s[0] for s in systems])
            self.assertIn("CC BY 4.0", systems[0][2])
            self.assertEqual(30, len(systems[0][3]))
            self.assertEqual("Cañón de Sant Pau ñ", [s for s in systems[1][3] if s[0] == "0"][0][1])
            first = systems[0][3][0]  # sorted by latitude
            self.assertAlmostEqual(41.40, first[2], places=5)
            self.assertEqual(20, first[4])
            with open(os.path.join(t, "s.json")) as f:
                summary = json.load(f)
            self.assertEqual({"bicing": 30, "bicimad": 25}, summary["systems"])

    def test_a_broken_system_is_left_out_but_the_other_is_kept(self):
        with tempfile.TemporaryDirectory() as t:
            code, out, err = self.run_main(t, {"bicing": many(), "bicimad": "{not json"})
            self.assertEqual(0, code)
            self.assertEqual(["bicing"], [s[0] for s in read_file(out)[3]])
            self.assertIn("bicimad", err)

    def test_too_few_stations_leaves_the_system_out(self):
        with tempfile.TemporaryDirectory() as t:
            code, out, _ = self.run_main(t, {"bicing": many(), "bicimad": many(3)})
            self.assertEqual(["bicing"], [s[0] for s in read_file(out)[3]])

    def test_nothing_usable_writes_no_file_and_fails(self):
        with tempfile.TemporaryDirectory() as t:
            code, out, err = self.run_main(t, {"bicing": doc([]), "bicimad": many(2)})
            self.assertEqual(1, code)
            self.assertFalse(os.path.exists(out), "an empty file must never exist")
            self.assertIn("nothing written", err)

    def test_missing_input_dir_files_fail_cleanly(self):
        with tempfile.TemporaryDirectory() as t:
            code, out, _ = self.run_main(t, {})
            self.assertEqual(1, code)
            self.assertFalse(os.path.exists(out))

    def test_long_text_is_cut_on_a_character_boundary(self):
        long_name = "ñ" * 200
        with tempfile.TemporaryDirectory() as t:
            d = doc([station(i, name=long_name if i == 0 else v3_name("x")) for i in range(25)])
            _, out, _ = self.run_main(t, {"bicing": d})
            name = [s for s in read_file(out)[3][0][3] if s[0] == "0"][0][1]
            self.assertTrue(set(name) == {"ñ"} and len(name.encode("utf-8")) <= 120)

    def test_print_urls_lists_every_system_with_https(self):
        o = io.StringIO()
        with redirect_stdout(o):
            self.assertEqual(0, bb.main(["--print-urls"]))
        lines = o.getvalue().strip().splitlines()
        self.assertEqual(len(bb.SYSTEMS), len(lines))
        for line in lines:
            sid, url = line.split(" ")
            self.assertTrue(url.startswith("https://") and url.endswith("/station_information"), line)

    def test_every_system_has_attribution_and_licence(self):
        for m in bb.SYSTEMS:
            self.assertTrue(m["attribution"] and "CC BY 4.0" in m["attribution"], m["id"])


if __name__ == "__main__":
    unittest.main()
