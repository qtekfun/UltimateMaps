#!/usr/bin/env python3
"""Tests for scripts/build-chargers.py (no network). Run: python3 -m unittest scripts/test_build_chargers.py

The fixtures are synthetic but use the tag spellings seen in a real Overpass sample of Madrid (taken on 2026-10-07).
"""
import importlib.util
import json
import os
import struct
import tempfile
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_chargers", os.path.join(HERE, "build-chargers.py"))
bc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bc)


def point(lon, lat, **tags):
    tags.setdefault("amenity", "charging_station")
    return {"type": "Feature", "properties": tags, "geometry": {"type": "Point", "coordinates": [lon, lat]}}


def polygon(ring, **tags):
    tags.setdefault("amenity", "charging_station")
    return {"type": "Feature", "properties": tags, "geometry": {"type": "Polygon", "coordinates": [ring]}}


FEATURES = [
    # 0: a full record, no feature id (osmium default)
    point(-3.70, 40.40, operator="Iberdrola", network="Iberdrola", name="Plaza Mayor", capacity="4",
          **{"socket:type2": "2", "socket:type2:output": "22 kW", "socket:type2_combo": "1", "socket:type2_combo:output": "50;150 kW",
             "fee": "yes", "access": "yes", "opening_hours": "24/7", "authentication:app": "yes", "authentication:nfc": "yes"}),
    # 1: slow socket, free, customers only, brand instead of network, unitless and W outputs
    point(-3.71, 40.41, brand="Lidl", fee="no", access="customers",
          **{"socket:schuko": "yes", "socket:chademo": "1", "socket:chademo:output": "50", "socket:type2_cable": "3", "socket:type2_cable:output": "7400 W"}),
    # 2: a way (polygon): centre of its bounding box
    polygon([[-3.80, 40.50], [-3.80, 40.52], [-3.78, 40.52], [-3.78, 40.50], [-3.80, 40.50]], operator="Zunder",
            **{"socket:type2_combo": "2", "socket:type2_combo:output": "150000 W"}),
    # 3: disused: dropped
    point(-3.72, 40.42, **{"disused:amenity": "charging_station", "operator": "Old"}),
    # 4: e-bike only: dropped
    point(-3.73, 40.43, bicycle="yes", **{"socket:schuko": "1"}),
    # 5: e-bike station that also has a car socket: kept
    point(-3.74, 40.44, bicycle="yes", **{"socket:type2": "1"}),
    # 6: motorcar=no: dropped
    point(-3.75, 40.45, motorcar="no"),
    # 7: no socket info at all: kept (unknown sockets)
    point(-3.76, 40.46, operator="Unknown sockets"),
    # 8: the very same position as 7: a duplicate
    point(-3.76, 40.46, operator="Unknown sockets"),
    # 9: invalid output / count values are ignored
    point(-3.77, 40.47, **{"socket:type2": "yes", "socket:type2:output": "fast", "socket:chademo": "no", "socket:type1": "0"}),
]


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


class Reader:
    """Minimal independent reader of the format, to check the writer byte by byte."""

    def __init__(self, data):
        self.d = data
        self.o = 0

    def take(self, fmt):
        v = struct.unpack_from(">" + fmt, self.d, self.o)
        self.o += struct.calcsize(">" + fmt)
        return v if len(v) > 1 else v[0]

    def utf(self):
        n = self.take("H")
        s = self.d[self.o:self.o + n].decode("utf-8")
        self.o += n
        return s


def read_all(data):
    assert zlib.crc32(data[:-4]) & 0xFFFFFFFF == struct.unpack(">I", data[-4:])[0]
    r = Reader(data)
    magic, version, generated, flags, n = r.take("I"), r.take("H"), r.take("q"), r.take("B"), r.take("I")
    recs = []
    for _ in range(n):
        lat, lon, fee, access, auth, cap, ns = r.take("iiBBBHB")
        socks = [r.take("BBH") for _ in range(ns)]
        recs.append(dict(lat=lat / 1e6, lon=lon / 1e6, fee=fee, access=access, auth=auth, cap=cap, socks=socks,
                         operator=r.utf(), network=r.utf(), name=r.utf(), hours=r.utf()))
    assert r.o == len(data) - 4, "trailing bytes"
    return dict(magic=magic, version=version, generated=generated, flags=flags, recs=recs)


class BuildChargersTest(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.tmp = self.td.name
        self.seq = os.path.join(self.tmp, "c.geojsonseq")
        write(self.seq, "".join("\x1e" + json.dumps(f) + "\n" for f in FEATURES))

    def tearDown(self):
        self.td.cleanup()

    def run_build(self, args=None):
        out = os.path.join(self.tmp, "chargers-es.bin")
        summary = os.path.join(self.tmp, "s.json")
        bc.main((args or ["--osm-geojsonseq", self.seq]) + ["--generated", "2026-10-07T12:00:00Z", "-o", out, "--summary", summary, "--quiet"])
        with open(out, "rb") as f:
            data = f.read()
        with open(summary) as f:
            return data, json.load(f)

    def test_counts_and_drops(self):
        data, s = self.run_build()
        self.assertEqual(10, s["features"])
        self.assertEqual(1, s["duplicates"])
        self.assertEqual(1, s["dropped_not_live"])
        self.assertEqual(2, s["dropped_not_for_cars"], "the e-bike station and motorcar=no")
        self.assertEqual(6, s["chargers"])
        self.assertEqual(len(data), s["bytes"])

    def test_features_without_ids_are_not_all_duplicates_of_the_first(self):
        # `osmium export` writes no feature id by default: the key is the position, so different nodes all survive.
        _, s = self.run_build()
        self.assertEqual(1, s["duplicates"], "only the repeated position")
        self.assertGreater(s["chargers"], 1)

    def test_explicit_ids_win_over_positions(self):
        a = point(-3.0, 40.0, operator="A")
        b = point(-3.0, 40.0, operator="B")  # same position, different feature: kept because the ids differ
        a["id"], b["id"] = "node/1", "node/2"
        p = os.path.join(self.tmp, "ids.geojsonseq")
        write(p, "".join(json.dumps(f) + "\n" for f in (a, b, a)))
        recs, stats = bc.load_osm([], [p])
        self.assertEqual(2, len(recs))
        self.assertEqual(1, stats["duplicates"])

    def test_record_content(self):
        data, _ = self.run_build()
        f = read_all(data)
        self.assertEqual(bc.MAGIC, f["magic"])
        self.assertEqual(bc.VERSION, f["version"])
        self.assertEqual(1_791_374_400, f["generated"])
        self.assertEqual(bc.SRC_OSM, f["flags"])
        a, b, way, e_bike, unknown = f["recs"][:5]
        self.assertEqual((40.40, -3.70), (a["lat"], a["lon"]))
        self.assertEqual("Iberdrola", a["operator"])
        self.assertEqual("Plaza Mayor", a["name"])
        self.assertEqual(4, a["cap"])
        self.assertEqual(bc.FEE_PAID, a["fee"])
        self.assertEqual(bc.ACCESS_PUBLIC, a["access"])
        self.assertEqual("24/7", a["hours"])
        self.assertEqual(bc.AUTH_APP | bc.AUTH_NFC, a["auth"])
        self.assertEqual([(bc.SOCKET_TYPE2, 2, 220), (bc.SOCKET_CCS, 1, 1500)], a["socks"], "22 kW and the largest of '50;150 kW'")
        self.assertEqual("Lidl", b["network"], "brand is the network when there is none")
        self.assertEqual(bc.FEE_FREE, b["fee"])
        self.assertEqual(bc.ACCESS_CUSTOMERS, b["access"])
        self.assertEqual([(bc.SOCKET_TYPE2, 3, 74), (bc.SOCKET_CHADEMO, 1, 500), (bc.SOCKET_SCHUKO, 0, 0)], b["socks"])
        self.assertAlmostEqual(40.51, way["lat"], places=6)
        self.assertAlmostEqual(-3.79, way["lon"], places=6)
        self.assertEqual([(bc.SOCKET_CCS, 2, 1500)], way["socks"], "150000 W is 150 kW")
        self.assertEqual([(bc.SOCKET_TYPE2, 1, 0)], e_bike["socks"])
        self.assertEqual([], unknown["socks"])
        self.assertEqual(bc.FEE_UNKNOWN, unknown["fee"])
        self.assertEqual(bc.ACCESS_UNKNOWN, unknown["access"])

    def test_bad_values_are_ignored(self):
        data, _ = self.run_build()
        last = read_all(data)["recs"][-1]
        self.assertEqual([(bc.SOCKET_TYPE2, 0, 0)], last["socks"], "'yes' = present, 'fast' = no power, 'no' and '0' = no socket")

    def test_power_parser(self):
        self.assertEqual(220, bc.parse_power_dkw("22 kW"))
        self.assertEqual(220, bc.parse_power_dkw("22"))
        self.assertEqual(430, bc.parse_power_dkw("22kW;43kW"))
        self.assertEqual(37, bc.parse_power_dkw("3,7 kW"))
        self.assertEqual(74, bc.parse_power_dkw("7400 W"))
        self.assertEqual(0, bc.parse_power_dkw("5000 kW"), "above the sane maximum")
        self.assertEqual(0, bc.parse_power_dkw("0"))
        self.assertEqual(0, bc.parse_power_dkw(None))
        self.assertEqual(0, bc.parse_power_dkw("Type 2"))

    def test_overpass_json_input_with_way_centre(self):
        osm = {"elements": [
            {"type": "node", "id": 1, "lat": 40.1, "lon": -3.1, "tags": {"amenity": "charging_station", "socket:type2": "1"}},
            {"type": "way", "id": 1, "center": {"lat": 40.2, "lon": -3.2}, "tags": {"amenity": "charging_station"}},
            {"type": "node", "id": 2, "lat": 40.3, "lon": -3.3, "tags": {"amenity": "bench"}},
        ]}
        p = os.path.join(self.tmp, "o.json")
        write(p, json.dumps(osm))
        recs, stats = bc.load_osm([p], [])
        self.assertEqual(2, len(recs), "node 1 and way 1 have different keys")
        self.assertEqual(1, stats["dropped_not_live"], "not a charging station")

    def test_text_is_bounded_and_utf8(self):
        long = "ñ" * 200
        recs, _ = bc.load_osm([], [self._seq([point(-3.0, 40.0, operator=long, opening_hours="x" * 400)])])
        self.assertEqual(bc.MAX_TEXT, len(recs[0]["operator"]))
        self.assertEqual(bc.MAX_HOURS, len(recs[0]["hours"]))
        out = os.path.join(self.tmp, "u.bin")
        bc.write_file(out, 0, recs)
        with open(out, "rb") as f:
            self.assertEqual(long[:bc.MAX_TEXT], read_all(f.read())["recs"][0]["operator"])

    def _seq(self, feats):
        p = os.path.join(self.tmp, "x.geojsonseq")
        write(p, "".join(json.dumps(f) + "\n" for f in feats))
        return p

    def test_empty_result_still_writes_a_valid_file(self):
        data, s = self.run_build(["--osm-geojsonseq", self._seq([point(-3.0, 40.0, motorcar="no")])])
        self.assertEqual(0, s["chargers"])
        self.assertEqual(0, read_all(data)["flags"])

    def test_no_source_is_an_error(self):
        with self.assertRaises(SystemExit):
            bc.main(["-o", os.path.join(self.tmp, "x.bin")])

    def test_write_sample_for_kotlin_when_asked(self):
        target = os.environ.get("UM_WRITE_CHARGERS_SAMPLE")
        if not target:
            self.skipTest("only used to regenerate the Kotlin test resource")
        data, _ = self.run_build()
        with open(target, "wb") as f:
            f.write(data)


if __name__ == "__main__":
    unittest.main()
