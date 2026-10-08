#!/usr/bin/env python3
"""Tests for scripts/build-zbe.py (no network). Run: python3 -m unittest scripts/test_build_zbe.py

The fixture is synthetic; the tag spellings follow the OSM wiki page of boundary=low_emission_zone.
"""
import importlib.util
import json
import os
import struct
import tempfile
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_zbe", os.path.join(HERE, "build-zbe.py"))
bz = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bz)


def square(lon, lat, size):
    return [[lon, lat], [lon + size, lat], [lon + size, lat + size], [lon, lat + size], [lon, lat]]


def feature(geometry, **tags):
    tags.setdefault("boundary", "low_emission_zone")
    return {"type": "Feature", "properties": tags, "geometry": geometry}


def poly(*rings):
    return {"type": "Polygon", "coordinates": list(rings)}


# a square with many collinear points on one side: simplification must drop them
NOISY = [[-3.70, 40.40]] + [[-3.70 + i * 0.001, 40.40 + (0.00001 if i % 2 else 0.0)] for i in range(1, 10)] + \
        [[-3.69, 40.40], [-3.69, 40.41], [-3.70, 40.41], [-3.70, 40.40]]

FEATURES = [
    # 0: a full record with a hole
    feature(poly(square(-3.70, 40.40, 0.02), square(-3.69, 40.41, 0.005)), name="Distrito Centro", **{"addr:city": "Madrid", "description:es": "Zona de bajas emisiones de especial protección"}),
    # 1: a MultiPolygon (two parts) with a conditional access tag only
    feature({"type": "MultiPolygon", "coordinates": [[square(2.10, 41.38, 0.03)], [square(2.20, 41.40, 0.01)]]},
            name="ZBE Rondes", **{"is_in:city": "Barcelona", "access:conditional": "no @ (fuel=diesel AND emissions<euro_4)"}),
    # 2: closed LineString (a way osmium did not make an area)
    feature({"type": "LineString", "coordinates": square(-0.40, 39.45, 0.02)}, name="ZBE Valencia"),
    # 3: not closed LineString: dropped
    feature({"type": "LineString", "coordinates": [[-1.0, 38.0], [-1.1, 38.1], [-1.2, 38.0]]}, name="Open way"),
    # 4: a point: dropped
    feature({"type": "Point", "coordinates": [-1.0, 38.0]}, name="A node"),
    # 5: future start date: dropped
    feature(poly(square(-5.99, 37.38, 0.02)), name="Sevilla future", start_date="2027-01-01"),
    # 6: ended: dropped
    feature(poly(square(-4.42, 36.72, 0.02)), name="Malaga old", end_date="2020-06"),
    # 7: lifecycle prefix: dropped
    feature(poly(square(-8.40, 43.36, 0.02)), name="Coruna", **{"proposed:boundary": "low_emission_zone"}),
    # 8: access=yes (no restriction): dropped
    feature(poly(square(-1.65, 42.81, 0.02)), name="Pamplona open", access="yes"),
    # 9: a noisy side to simplify
    feature(poly(NOISY), name="Noisy", city="Getafe"),
    # 10: a duplicate id of 0
    dict(feature(poly(square(-3.70, 40.40, 0.02)), name="Distrito Centro"), id="x"),
    dict(feature(poly(square(-3.70, 40.40, 0.02)), name="Distrito Centro"), id="x"),
    # 11: degenerate ring: dropped
    feature(poly([[-3.0, 40.0], [-3.0, 40.0], [-3.0, 40.0], [-3.0, 40.0]]), name="Degenerate"),
    # 12: the wrong tag value
    feature(poly(square(-6.0, 38.0, 0.02)), name="Other", boundary="administrative"),
]


class Reader:
    def __init__(self, data):
        self.d, self.o = data, 0

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
    zones = []
    for _ in range(n):
        name, city, restriction = r.utf(), r.utf(), r.utf()
        polys = []
        for _p in range(r.take("B")):
            rings = []
            for _r in range(r.take("B")):
                np = r.take("H")
                rings.append([(r.take("i") / 1e6, r.take("i") / 1e6) for _ in range(np)])
            polys.append(rings)
        zones.append(dict(name=name, city=city, restriction=restriction, polys=polys))
    assert r.o == len(data) - 4, "trailing bytes"
    return dict(magic=magic, version=version, generated=generated, flags=flags, zones=zones)


class BuildZbeTest(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.tmp = self.td.name
        self.seq = os.path.join(self.tmp, "z.geojsonseq")
        with open(self.seq, "w", encoding="utf-8") as f:
            f.write("".join("\x1e" + json.dumps(x) + "\n" for x in FEATURES))

    def tearDown(self):
        self.td.cleanup()

    def run_build(self, extra=()):
        out = os.path.join(self.tmp, "zbe-es.bin")
        summary = os.path.join(self.tmp, "s.json")
        bz.main(["--osm-geojsonseq", self.seq, "--generated", "2026-10-08T12:00:00Z", "-o", out, "--summary", summary, "--quiet", *extra])
        with open(out, "rb") as f:
            data = f.read()
        with open(summary, encoding="utf-8") as f:
            return data, json.load(f)

    def test_keeps_only_live_zones_and_dedupes(self):
        data, stats = self.run_build()
        z = read_all(data)
        names = [x["name"] for x in z["zones"]]
        self.assertEqual(sorted(names), ["Distrito Centro", "Distrito Centro", "Noisy", "ZBE Rondes", "ZBE Valencia"])
        self.assertEqual(stats["zones"], 5)
        self.assertEqual(stats["duplicates"], 1)
        self.assertEqual(stats["dropped_not_live"], 5)  # future, ended, lifecycle, access=yes, wrong boundary value
        self.assertEqual(stats["dropped_no_polygon"], 3)  # open way, point, degenerate

    def test_header_and_crc(self):
        data, _ = self.run_build()
        z = read_all(data)
        self.assertEqual(z["magic"], bz.MAGIC)
        self.assertEqual(z["version"], 1)
        self.assertEqual(z["flags"], bz.SRC_OSM)
        self.assertEqual(z["generated"], 1791460800)

    def test_fields(self):
        z = {x["name"]: x for x in read_all(self.run_build()[0])["zones"]}
        self.assertEqual(z["ZBE Rondes"]["city"], "Barcelona")
        self.assertIn("fuel=diesel", z["ZBE Rondes"]["restriction"])
        self.assertEqual(z["Noisy"]["city"], "Getafe")
        self.assertEqual(z["ZBE Valencia"]["restriction"], "")

    def test_hole_and_multipolygon_structure(self):
        zones = read_all(self.run_build()[0])["zones"]
        centro = next(x for x in zones if x["name"] == "Distrito Centro" and x["restriction"])
        self.assertEqual([len(p) for p in centro["polys"]], [2])  # one polygon, outer + hole
        rondes = next(x for x in zones if x["name"] == "ZBE Rondes")
        self.assertEqual([len(p) for p in rondes["polys"]], [1, 1])
        for p in rondes["polys"]:
            self.assertEqual(len(p[0]), 4)  # closing point not stored

    def test_simplification_drops_collinear_points(self):
        zones = read_all(self.run_build()[0])["zones"]
        noisy = next(x for x in zones if x["name"] == "Noisy")
        # 14 distinct vertices, but the noisy side is within 4 m of a straight line (1 mm of latitude is 1.1 m)
        self.assertLessEqual(len(noisy["polys"][0][0]), 5)
        self.assertGreaterEqual(len(noisy["polys"][0][0]), 4)
        with_tight = read_all(self.run_build(["--tolerance-m", "0.01"])[0])["zones"]
        self.assertGreater(len(next(x for x in with_tight if x["name"] == "Noisy")["polys"][0][0]), 8)

    def test_ring_cap(self):
        circle = [[-3.7 + 0.01 * __import__("math").cos(a / 100.0 * 6.2831853), 40.4 + 0.01 * __import__("math").sin(a / 100.0 * 6.2831853)] for a in range(100)]
        circle.append(circle[0])
        with open(self.seq, "w", encoding="utf-8") as f:
            f.write(json.dumps(feature(poly(circle), name="Round")) + "\n")
        zones = read_all(self.run_build(["--tolerance-m", "0.001", "--max-ring-points", "30"])[0])["zones"]
        self.assertLessEqual(len(zones[0]["polys"][0][0]), 30)
        self.assertGreaterEqual(len(zones[0]["polys"][0][0]), 4)

    def test_overpass_json(self):
        el = [
            {"type": "way", "id": 1, "tags": {"boundary": "low_emission_zone", "name": "Way zone"},
             "geometry": [{"lat": 40.0, "lon": -3.0}, {"lat": 40.0, "lon": -2.99}, {"lat": 40.01, "lon": -2.99}, {"lat": 40.0, "lon": -3.0}]},
            {"type": "relation", "id": 2, "tags": {"boundary": "low_emission_zone", "type": "boundary", "name": "Rel zone"},
             "members": [
                 {"type": "way", "role": "outer", "geometry": [{"lat": 41.0, "lon": 2.0}, {"lat": 41.0, "lon": 2.1}, {"lat": 41.1, "lon": 2.1}]},
                 {"type": "way", "role": "outer", "geometry": [{"lat": 41.1, "lon": 2.1}, {"lat": 41.1, "lon": 2.0}, {"lat": 41.0, "lon": 2.0}]},
                 {"type": "way", "role": "inner", "geometry": [{"lat": 41.04, "lon": 2.04}, {"lat": 41.04, "lon": 2.06}, {"lat": 41.06, "lon": 2.06}, {"lat": 41.04, "lon": 2.04}]},
             ]},
        ]
        p = os.path.join(self.tmp, "o.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump({"elements": el}, f)
        out = os.path.join(self.tmp, "o.bin")
        bz.main(["--osm-json", p, "--generated", "2026-10-08T12:00:00Z", "-o", out, "--quiet"])
        with open(out, "rb") as f:
            zones = {x["name"]: x for x in read_all(f.read())["zones"]}
        self.assertEqual(set(zones), {"Way zone", "Rel zone"})
        self.assertEqual([len(r) for r in zones["Rel zone"]["polys"][0]], [4, 3])

    def test_requires_a_source(self):
        with self.assertRaises(SystemExit):
            bz.main(["-o", os.path.join(self.tmp, "x.bin")])


if __name__ == "__main__":
    unittest.main()
