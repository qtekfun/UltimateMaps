#!/usr/bin/env python3
"""Tests for scripts/build-cameras.py (no network). Run: python3 -m unittest scripts/test_build_cameras.py

The fixtures are trimmed copies of the real DGT structures (fields and nesting as downloaded on 2026-10-07; values made up
where the test needs a specific case). They live in code so the test needs no files.
"""
import importlib.util
import json
import os
import struct
import tempfile
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_cameras", os.path.join(HERE, "build-cameras.py"))
bc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bc)

RADARS = """<?xml version="1.0" encoding="UTF-8"?>
<d2LogicalModel xmlns="http://datex2.eu/schema/1_0/1_0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><exchange>
<predefinedLocationSet id="a"><predefinedLocation id="P1"><predefinedLocation xsi:type="Point">
  <tpegpointLocation xsi:type="TPEGSimplePoint"><point xsi:type="TPEGNonJunctionPoint"><pointCoordinates><latitude>41.30000</latitude><longitude>-1.90000</longitude></pointCoordinates></point></tpegpointLocation>
  <referencePoint><roadName><value>A-2</value></roadName><roadNumber>A-2</roadNumber><directionRelative>positive</directionRelative><referencePointDistance>202000.0</referencePointDistance></referencePoint>
</predefinedLocation></predefinedLocation>
<predefinedLocation id="P2"><predefinedLocation xsi:type="Point">
  <tpegpointLocation xsi:type="TPEGSimplePoint"><point xsi:type="TPEGNonJunctionPoint"><pointCoordinates><latitude>41.31000</latitude><longitude>-1.90000</longitude></pointCoordinates></point></tpegpointLocation>
  <referencePoint><roadNumber>A-2</roadNumber><directionRelative>negative</directionRelative><referencePointDistance>203000.0</referencePointDistance></referencePoint>
</predefinedLocation></predefinedLocation>
<predefinedLocation id="P3"><predefinedLocation xsi:type="Point">
  <tpegpointLocation xsi:type="TPEGSimplePoint"><point xsi:type="TPEGNonJunctionPoint"><pointCoordinates><latitude>0.0</latitude><longitude>0.0</longitude></pointCoordinates></point></tpegpointLocation>
  <referencePoint><roadNumber>A-9</roadNumber><referencePointDistance>1000.0</referencePointDistance></referencePoint>
</predefinedLocation></predefinedLocation>
<predefinedLocation id="S1"><predefinedLocation xsi:type="Linear"><tpeglinearLocation>
  <to xsi:type="TPEGNonJunctionPoint"><pointCoordinates><latitude>41.4</latitude><longitude>-0.9</longitude></pointCoordinates></to>
  <from xsi:type="TPEGNonJunctionPoint"><pointCoordinates><latitude>41.5</latitude><longitude>-0.95</longitude></pointCoordinates></from>
  </tpeglinearLocation>
  <referencePointLinear><referencePointPrimaryLocation><referencePoint><roadNumber>Z-40</roadNumber><referencePointDistance>26600.0</referencePointDistance></referencePoint></referencePointPrimaryLocation></referencePointLinear>
</predefinedLocation></predefinedLocation>
</predefinedLocationSet></exchange></d2LogicalModel>
"""

REPORT = """PROVINCIA   CARRETERA   TIPO                     PK       SENTIDO       FECHA_ACTUALIZACION_INFORME
Zaragoza    A-2         Radar Fijo           202.0     Creciente                         03/08/2026
Zaragoza    A-2         Radar Fijo           203.0     Decreciente                       03/08/2026
Zaragoza    A-2         Radar Fijo           250.5     Creciente                         03/08/2026
Zaragoza    A-2         Radar Móvil   202.200 - 202.800   Ambos                             03/08/2026
Zaragoza    A-2         Radar Móvil   220.000 - 230.000   Ambos                             03/08/2026
Zaragoza    CM-9        Radar Móvil     5.000 - 9.000     Ambos                             03/08/2026
Zaragoza    Z-40        Radar Tramo          26.6      Decreciente                       03/08/2026
                                            (5.638 m)
"""

ANCHORS = """<?xml version="1.0" encoding="UTF-8"?>
<d2:payload xmlns:d2="d2" xmlns:ns2="s" xmlns:loc="l" xmlns:lse="e">
<ns2:device id="1"><ns2:pointLocation><loc:supplementaryPositionalDescription><loc:roadInformation><loc:roadName>A-2</loc:roadName></loc:roadInformation></loc:supplementaryPositionalDescription>
 <loc:tpegPointLocation><loc:point><loc:pointCoordinates><loc:latitude>41.305</loc:latitude><loc:longitude>-1.9</loc:longitude></loc:pointCoordinates>
 <loc:_tpegNonJunctionPointExtension><loc:extendedTpegNonJunctionPoint><lse:kilometerPoint>202.5</lse:kilometerPoint></loc:extendedTpegNonJunctionPoint></loc:_tpegNonJunctionPointExtension></loc:point></loc:tpegPointLocation></ns2:pointLocation></ns2:device>
</d2:payload>
"""


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def read_header(data):
    magic, version, generated, flags, nf, ns, nz = struct.unpack_from(">IHqBIII", data, 0)
    return dict(magic=magic, version=version, generated=generated, flags=flags, fixed=nf, sections=ns, zones=nz)


class ConverterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.radars = os.path.join(self.tmp, "r.xml")
        self.report = os.path.join(self.tmp, "r.txt")
        self.anchors = os.path.join(self.tmp, "a.xml")
        write(self.radars, RADARS)
        write(self.report, REPORT)
        write(self.anchors, ANCHORS)

    def run_build(self, extra=()):
        out = os.path.join(self.tmp, "out.bin")
        summary = os.path.join(self.tmp, "s.json")
        bc.main(["--dgt-radars", self.radars, "--dgt-report", self.report, "--dgt-anchors", self.anchors,
                 "--generated", "2026-10-07T12:00:00Z", "--quiet", "-o", out, "--summary", summary, *extra])
        with open(out, "rb") as f:
            data = f.read()
        with open(summary) as f:
            return data, json.load(f)

    def test_dgt_points_report_and_invalid_position(self):
        pts, secs = bc.parse_dgt_radars(self.radars)
        self.assertEqual(2, len(pts), "the (0,0) record is dropped")
        self.assertEqual(1, len(secs))
        self.assertAlmostEqual(202.0, pts[0]["km"])
        rows = bc.parse_dgt_report(self.report)
        self.assertEqual(["fixed", "fixed", "fixed", "mobile", "mobile", "mobile", "section"], [r["type"] for r in rows])
        self.assertEqual(5638.0, rows[-1]["length_m"])
        self.assertEqual((202.2, 202.8), (rows[3]["km_from"], rows[3]["km_to"]))

    def test_file_header_crc_and_counts(self):
        data, stats = self.run_build()
        h = read_header(data)
        self.assertEqual(bc.MAGIC, h["magic"])
        self.assertEqual(1, h["version"])
        self.assertEqual(1791374400, h["generated"])
        self.assertEqual(2, h["fixed"])
        self.assertEqual(1, h["sections"])
        self.assertEqual(3, h["zones"])
        self.assertEqual(bc.SRC_DGT, h["flags"])
        self.assertEqual(zlib.crc32(data[:-4]) & 0xFFFFFFFF, struct.unpack(">I", data[-4:])[0])
        self.assertEqual(len(data), stats["bytes"])

    def test_fixed_radar_without_coordinates_is_not_invented(self):
        _, stats = self.run_build()
        self.assertEqual(1, stats["report_fixed_without_coordinates_dropped"], "A-2 km 250.5 has no coordinates")
        self.assertEqual(2, stats["fixed"])

    def test_zone_line_only_when_both_ends_can_be_placed(self):
        _, stats = self.run_build()
        self.assertEqual(3, stats["mobile_zones"])
        self.assertEqual(1, stats["mobile_zones_with_line"], "only A-2 202.2-202.8 lies between anchors; the 220-230 zone is past the last anchor and CM-9 has none (no extrapolation)")

    def test_axis_and_sense_from_report(self):
        fixed, _, _, _ = bc.build(self._args())
        by_km = {round(c["lat"], 3): c for c in fixed}
        a = by_km[41.3]
        self.assertIsNotNone(a["axis"])
        self.assertAlmostEqual(0.0, min(a["axis"], 360 - a["axis"]), delta=5, msg="the road runs north in increasing km")
        self.assertEqual(bc.SENSE_ALONG, a["sense"])
        self.assertEqual(bc.SENSE_AGAINST, by_km[41.31]["sense"])

    def _args(self, **kw):
        import argparse
        d = dict(dgt_radars=self.radars, dgt_report=self.report, dgt_anchors=[self.anchors], osm_json=None, osm_geojsonseq=None,
                 merge_meters=100.0, max_anchor_gap_km=15.0)
        d.update(kw)
        return argparse.Namespace(**d)

    def test_osm_mobile_and_disused_are_dropped_and_merge_keeps_osm_values(self):
        osm = {"elements": [
            {"type": "node", "id": 1, "lat": 41.30030, "lon": -1.90000, "tags": {"highway": "speed_camera", "maxspeed": "90", "direction": "0"}},
            {"type": "node", "id": 2, "lat": 40.0, "lon": -3.0, "tags": {"highway": "speed_camera", "camera:type": "mobile"}},
            {"type": "node", "id": 3, "lat": 40.1, "lon": -3.0, "tags": {"highway": "speed_camera", "enforcement": "mobile_speed_camera"}},
            {"type": "node", "id": 4, "lat": 40.2, "lon": -3.0, "tags": {"disused:highway": "speed_camera", "highway": "speed_camera"}},
            {"type": "node", "id": 5, "lat": 40.3, "lon": -3.0, "tags": {"highway": "speed_camera", "maxspeed": "50 mph"}},
            {"type": "node", "id": 5, "lat": 40.3, "lon": -3.0, "tags": {"highway": "speed_camera"}},
            {"type": "way", "id": 6, "tags": {"highway": "speed_camera"}},
        ]}
        p = os.path.join(self.tmp, "osm.json")
        write(p, json.dumps(osm))
        fixed, _, _, stats = bc.build(self._args(osm_json=[p]))
        self.assertEqual(2, stats["osm_fixed_nodes"] , "node 1 and node 5 survive")
        self.assertEqual(3, stats["osm_dropped_mobile_or_disused"])
        self.assertEqual(1, stats["merged_dgt_osm"])
        self.assertEqual(3, len(fixed), "2 DGT (one merged with OSM node 1) + OSM node 5")
        merged = [c for c in fixed if c["src"] == bc.SRC_DGT | bc.SRC_OSM]
        self.assertEqual(1, len(merged))
        self.assertEqual(90, merged[0]["maxspeed"])
        osm_only = [c for c in fixed if c["src"] == bc.SRC_OSM]
        self.assertEqual(0, osm_only[0]["maxspeed"], "'50 mph' is not a km/h limit")

    def test_osm_direction_parsing(self):
        self.assertEqual(90.0, bc.parse_direction("E"))
        self.assertEqual(120.0, bc.parse_direction("120"))
        self.assertIsNone(bc.parse_direction("forward"))
        self.assertIsNone(bc.parse_direction("120;300"))
        self.assertEqual(70, bc.parse_maxspeed("70"))
        self.assertIsNone(bc.parse_maxspeed("signals"))

    def test_geojsonseq_input(self):
        p = os.path.join(self.tmp, "o.geojsonseq")
        feat = {"type": "Feature", "id": "node/9", "properties": {"highway": "speed_camera", "maxspeed": "80"},
                "geometry": {"type": "Point", "coordinates": [-3.5, 40.5]}}
        write(p, "\x1e" + json.dumps(feat) + "\n")
        nodes, dropped = bc.load_osm([], [p])
        self.assertEqual(1, len(nodes))
        self.assertEqual(0, dropped)

    def test_geojsonseq_without_feature_ids_keeps_every_node(self):
        # `osmium export` writes no feature id by default; an empty id used to make every node a duplicate of the first.
        p = os.path.join(self.tmp, "n.geojsonseq")
        feats = [
            {"type": "Feature", "properties": {"highway": "speed_camera"}, "geometry": {"type": "Point", "coordinates": [-3.5 + i / 100, 40.5]}}
            for i in range(3)
        ]
        feats.append(feats[0])  # the same position twice is still a duplicate
        write(p, "".join("\x1e" + json.dumps(f) + "\n" for f in feats))
        nodes, dropped = bc.load_osm([], [p])
        self.assertEqual(3, len(nodes))

    def test_no_source_is_an_error(self):
        with self.assertRaises(SystemExit):
            bc.main(["-o", os.path.join(self.tmp, "x.bin")])

    def test_write_sample_for_kotlin_when_asked(self):
        target = os.environ.get("UM_WRITE_CAMERAS_SAMPLE")
        if not target:
            self.skipTest("only used to regenerate the Kotlin test resource")
        data, _ = self.run_build()
        with open(target, "wb") as f:
            f.write(data)


if __name__ == "__main__":
    unittest.main()
