#!/usr/bin/env python3
"""Tests for scripts/build-routes.py (no network). Run: python3 -m unittest scripts/test_build_routes.py

The fixtures are tiny and synthetic, with the tag spellings of real Spanish route relations.
"""
import importlib.util
import json
import os
import struct
import tempfile
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_routes", os.path.join(HERE, "build-routes.py"))
br = importlib.util.module_from_spec(spec)
spec.loader.exec_module(br)


def way(*pts):
    return [(lat, lon) for lat, lon in pts]


# A straight line with a tiny wobble (dropped by simplification) and a real corner (kept).
W1 = way((43.0000, -4.0000), (43.0010, -4.00001), (43.0020, -4.0000), (43.0030, -4.0000))
W2 = way((43.0030, -4.0000), (43.0030, -3.9980))
W3 = way((43.0060, -3.9960), (43.0030, -3.9980))  # meets W2's end, reversed


def tags(**kw):
    t = {"type": "route"}
    t.update(kw)
    return t


class ClassifyTest(unittest.TestCase):
    def test_hiking_networks(self):
        for net, level in (("lwn", 0), ("rwn", 1), ("nwn", 2), ("iwn", 3)):
            self.assertEqual((br.KIND_HIKING, level), br.classify(tags(route="hiking", network=net)))
        self.assertEqual((br.KIND_HIKING, 1), br.classify(tags(route="foot", network="rwn")))

    def test_hiking_without_network_needs_a_name_or_ref(self):
        self.assertEqual((br.KIND_HIKING, 0), br.classify(tags(route="hiking", ref="PR-S 12")))
        self.assertEqual((br.KIND_HIKING, 0), br.classify(tags(route="hiking", name="Ruta del Cares")))
        self.assertIsNone(br.classify(tags(route="hiking")))

    def test_bicycle_needs_regional_or_higher(self):
        self.assertIsNone(br.classify(tags(route="bicycle", network="lcn", name="Anillo")))
        self.assertIsNone(br.classify(tags(route="bicycle", name="x")))
        self.assertIsNone(br.classify(tags(route="bicycle", network="rwn", name="x")))  # walking network on a bike route
        self.assertEqual((br.KIND_BICYCLE, 1), br.classify(tags(route="bicycle", network="rcn")))
        self.assertEqual((br.KIND_BICYCLE, 2), br.classify(tags(route="bicycle", network="ncn")))
        self.assertEqual((br.KIND_BICYCLE, 3), br.classify(tags(route="bicycle", network="icn")))

    def test_mtb(self):
        self.assertEqual((br.KIND_MTB, 0), br.classify(tags(route="mtb", network="lcn")))
        self.assertEqual((br.KIND_MTB, 1), br.classify(tags(route="mtb", network="rcn")))
        self.assertEqual((br.KIND_MTB, 0), br.classify(tags(route="mtb", name="Anillo BTT")))
        self.assertIsNone(br.classify(tags(route="mtb")))

    def test_other_things_are_dropped(self):
        self.assertIsNone(br.classify({"type": "route_master", "route": "hiking", "network": "rwn"}))
        self.assertIsNone(br.classify(tags(route="bus", network="rwn")))
        self.assertIsNone(br.classify(tags(route="hiking", network="rwn", state="proposed")))
        self.assertIsNone(br.classify(tags(route="hiking", network="rwn", **{"disused:name": "x"})))
        self.assertIsNone(br.classify(tags(route="hiking", network="Red local", **{})))


class TextTest(unittest.TestCase):
    def test_title_falls_back_to_from_to(self):
        self.assertEqual("Cares", br.title_of(tags(name=" Cares ")))
        self.assertEqual("Poncebos - Caín", br.title_of(tags(**{"from": "Poncebos", "to": "Caín"})))
        self.assertEqual("", br.title_of(tags(**{"from": "Poncebos"})))

    def test_distance(self):
        self.assertEqual(12500, br.parse_distance_m("12.5"))
        self.assertEqual(12500, br.parse_distance_m("12,5 km"))
        self.assertEqual(12500, br.parse_distance_m("12500 m"))
        self.assertEqual(1609, br.parse_distance_m("1 mi"))
        self.assertEqual(0, br.parse_distance_m("about 12"))
        self.assertEqual(0, br.parse_distance_m("0.01"))
        self.assertEqual(0, br.parse_distance_m("999999"))
        self.assertEqual(0, br.parse_distance_m(None))


class GeometryTest(unittest.TestCase):
    def test_simplify_keeps_ends_and_corners(self):
        s = br.simplify(W1 + W2[1:], 15.0)
        self.assertEqual(W1[0], s[0])
        self.assertEqual(W2[-1], s[-1])
        self.assertIn(W1[-1], s)  # the corner
        self.assertNotIn(W1[1], s)  # the wobble (~0.8 m)
        self.assertEqual(3, len(s))

    def test_simplify_short_lines_untouched(self):
        self.assertEqual(W2, br.simplify(W2, 50.0))

    def test_chain_joins_in_both_directions(self):
        segs = br.chain([W1, W2, W3])
        self.assertEqual(1, len(segs))
        self.assertEqual(W1[0], segs[0][0])
        self.assertEqual(W3[0], segs[0][-1])
        self.assertEqual(2, len(br.chain([W1, way((44.0, -4.0), (44.1, -4.0))])))

    def test_length_is_plausible(self):
        # 0.003 degrees of latitude is about 333 m
        self.assertAlmostEqual(333, br.path_length_m([way((43.0, -4.0), (43.003, -4.0))]), delta=3)


class OplTest(unittest.TestCase):
    def test_relation_line(self):
        line = ("r42 v3 dV c1 t2024-01-01T00:00:00Z i1 uX "
                "Ttype=route,route=hiking,name=Ruta%20%del%20%Cares,ref=PR-S%20%12 Mw10@,w11@forward,n5@,r6@")
        rid, t, ways = br.parse_opl_relation(line)
        self.assertEqual(42, rid)
        self.assertEqual("Ruta del Cares", t["name"])
        self.assertEqual("PR-S 12", t["ref"])
        self.assertEqual([10, 11], ways)
        self.assertIsNone(br.parse_opl_relation("w1 v1 Nn1,n2"))


def overpass_fixture():
    def m(pts):
        return {"type": "way", "ref": 1, "role": "", "geometry": [{"lat": a, "lon": b} for a, b in pts]}
    return {"elements": [
        {"type": "relation", "id": 1, "tags": tags(route="hiking", network="nwn", name="GR 99", ref="GR 99", operator="FEDME",
                                                   distance="12"),
         "members": [m(W1), m(W2), m(W3), {"type": "node", "ref": 5, "role": "guidepost"}]},
        {"type": "relation", "id": 2, "tags": tags(route="bicycle", network="rcn", ref="CV-1"), "members": [m(W1)]},
        {"type": "relation", "id": 3, "tags": tags(route="bicycle", network="lcn", name="city loop"), "members": [m(W1)]},
        {"type": "relation", "id": 4, "tags": tags(route="hiking", network="lwn", name="empty"), "members": []},
        {"type": "relation", "id": 1, "tags": tags(route="hiking", network="nwn", name="GR 99 dup"), "members": [m(W1)]},
        {"type": "node", "id": 9, "lat": 1, "lon": 1},
    ]}


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def path(self, name):
        return os.path.join(self.tmp.name, name)

    def run_overpass(self):
        src = self.path("o.json")
        with open(src, "w") as f:
            json.dump(overpass_fixture(), f)
        out = self.path("routes-es.bin")
        self.assertEqual(0, br.main(["--osm-json", src, "-o", out, "--generated", "2026-10-08T00:00:00Z",
                                     "--summary", self.path("s.json"), "--quiet"]))
        return out

    def test_overpass_roundtrip(self):
        out = self.run_overpass()
        routes = br.read_file(out)
        self.assertEqual(2, len(routes))  # city loop (lcn bicycle), empty geometry and the duplicate id are gone
        gr, cv = routes  # higher level first
        self.assertEqual((br.KIND_HIKING, 2, "GR 99", "GR 99", "FEDME", 12000), (gr["kind"], gr["level"], gr["name"], gr["ref"], gr["operator"], gr["length_m"]))
        self.assertEqual(br.FLAG_LENGTH_TAGGED, gr["flags"])
        self.assertEqual((br.KIND_BICYCLE, 1, "", "CV-1", 0), (cv["kind"], cv["level"], cv["name"], cv["ref"], cv["flags"]))
        self.assertGreater(cv["length_m"], 300)  # computed from the geometry
        self.assertEqual(1, len(gr["segments"]))  # three ways chained into one line
        seg = gr["segments"][0]
        self.assertAlmostEqual(W1[0][0], seg[0][0], places=6)
        self.assertAlmostEqual(W3[0][1], seg[-1][1], places=6)
        summary = json.load(open(self.path("s.json")))
        self.assertEqual(2, summary["routes"])
        self.assertEqual(1, summary["duplicates"])
        self.assertEqual(1, summary["dropped_no_geometry"])

    def test_output_is_deterministic_and_checksummed(self):
        a = open(self.run_overpass(), "rb").read()
        b = open(self.run_overpass(), "rb").read()
        self.assertEqual(a, b)
        self.assertEqual(br.MAGIC, struct.unpack(">I", a[:4])[0])
        self.assertEqual(zlib.crc32(a[:-4]) & 0xFFFFFFFF, struct.unpack(">I", a[-4:])[0])
        bad = bytearray(a)
        bad[30] ^= 0xFF
        with open(self.path("bad.bin"), "wb") as f:
            f.write(bad)
        with self.assertRaises(ValueError):
            br.read_file(self.path("bad.bin"))

    def test_opl_and_geojsonseq_path(self):
        opl = self.path("r.opl")
        with open(opl, "w") as f:
            f.write("n1 v1 x1 y2\n")
            f.write("r7 v1 Ttype=route,route=hiking,network=rwn,name=Camino%20%Norte Mw1@,w2@,w3@\n")
            f.write("r8 v1 Ttype=route,route=bus,network=rwn Mw1@\n")
        seq = self.path("w.geojsonseq")
        with open(seq, "w") as f:
            for wid, pts in ((1, W1), (2, W2), (3, W3), (4, W1)):
                feature = {"type": "Feature", "id": f"w{wid}", "properties": {},
                           "geometry": {"type": "LineString", "coordinates": [[lon, lat] for lat, lon in pts]}}
                f.write("\x1e" + json.dumps(feature) + "\n")
            f.write(json.dumps({"type": "Feature", "id": "n5", "properties": {}, "geometry": {"type": "Point", "coordinates": [0, 1]}}) + "\n")
        out = self.path("o.bin")
        self.assertEqual(0, br.main(["--relations-opl", opl, "--ways-geojsonseq", seq, "-o", out, "--generated", "2026-10-08T00:00:00Z", "--quiet"]))
        routes = br.read_file(out)
        self.assertEqual(1, len(routes))
        self.assertEqual(("Camino Norte", 1, 1), (routes[0]["name"], routes[0]["level"], len(routes[0]["segments"])))

    def test_arguments(self):
        with self.assertRaises(SystemExit):
            br.main(["-o", self.path("x.bin")])
        with self.assertRaises(SystemExit):
            br.main(["--relations-opl", "a", "-o", self.path("x.bin")])


if __name__ == "__main__":
    unittest.main()
