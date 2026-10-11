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
        self.assertEqual(bc.SENSE_ALONG, merged[0]["sense"], "the OSM axis must not erase the DGT travel sense")
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


M_LAT = 1.0 / 111195.0  # degrees of latitude per metre


class EnforcementTest(unittest.TestCase):
    """OSM `type=enforcement` relations -> enforced direction. Synthetic extracts: a straight north-south road at
    lon -2.0 around lat 40.0, nodes every 25 m, the camera (device) node at 0 m."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.nodes = {}
        self.ways = {}
        self.rels = []
        self.cams = []

    def road(self, base_id, south_m, north_m, lon=-2.0):
        """Node ids base_id.. from south_m to north_m (metres north of lat 40), every 25 m; returns the id list."""
        ids = []
        m = south_m
        while m <= north_m:
            nid = base_id + len(ids)
            self.nodes[nid] = (40.0 + m * M_LAT, lon)
            ids.append(nid)
            m += 25
        return ids

    def way(self, wid, ids, **tags):
        self.ways[wid] = dict(nodes=list(ids), tags=tags)

    def rel(self, rid, members, **tags):
        t = {"type": "enforcement", "enforcement": "maxspeed"}
        t.update(tags)
        self.rels.append(dict(id=rid, tags=t, members=members))

    def camera(self, nid, m_north, lon=-2.0, **tags):
        """An OSM camera node (and the device node of the relations)."""
        self.nodes[nid] = (40.0 + m_north * M_LAT, lon)
        t = {"highway": "speed_camera"}
        t.update(tags)
        self.cams.append(dict(type="node", id=nid, lat=40.0 + m_north * M_LAT, lon=lon, tags=t))

    def write_enforcement(self, as_json=False):
        path = os.path.join(self.tmp, "enf.json" if as_json else "enf.osm")
        if as_json:
            els = [dict(type="node", id=i, lat=la, lon=lo) for i, (la, lo) in self.nodes.items()]
            els += [dict(type="way", id=i, nodes=w["nodes"], tags=w["tags"]) for i, w in self.ways.items()]
            els += [dict(type="relation", id=r["id"], tags=r["tags"],
                         members=[dict(type=t, ref=ref, role=role) for (t, ref, role) in r["members"]]) for r in self.rels]
            write(path, json.dumps(dict(elements=els)))
            return path
        out = ['<?xml version="1.0" encoding="UTF-8"?><osm version="0.6">']
        for i, (la, lo) in self.nodes.items():
            out.append(f'<node id="{i}" lat="{la:.7f}" lon="{lo:.7f}"/>')
        for i, w in self.ways.items():
            out.append(f'<way id="{i}">' + "".join(f'<nd ref="{n}"/>' for n in w["nodes"])
                       + "".join(f'<tag k="{k}" v="{v}"/>' for k, v in w["tags"].items()) + "</way>")
        for r in self.rels:
            out.append(f'<relation id="{r["id"]}">' + "".join(f'<member type="{t}" ref="{ref}" role="{role}"/>' for (t, ref, role) in r["members"])
                       + "".join(f'<tag k="{k}" v="{v}"/>' for k, v in r["tags"].items()) + "</relation>")
        out.append("</osm>")
        write(path, "\n".join(out))
        return path

    def build(self, as_json=False):
        osm = os.path.join(self.tmp, "cams.json")
        write(osm, json.dumps(dict(elements=self.cams)))
        enf = self.write_enforcement(as_json)
        out = os.path.join(self.tmp, "o.bin")
        summary = os.path.join(self.tmp, "s.json")
        bc.main(["--osm-json", osm, "--osm-enforcement", enf, "--generated", "2026-10-11T12:00:00Z", "--quiet",
                 "-o", out, "--summary", summary])
        with open(out, "rb") as f:
            data = f.read()
        with open(summary) as f:
            return data, json.load(f)

    @staticmethod
    def fixed_records(data):
        """[(src, maxspeed, axis, sense_byte)] of the fixed cameras, in file order."""
        n = struct.unpack_from(">I", data, 15)[0]
        pos, out = 27, []
        for _ in range(n):
            _la, _lo, src, ms, axis, sense = struct.unpack_from(">iiBBhB", data, pos)
            pos += 13
            (ln,) = struct.unpack_from(">H", data, pos)
            pos += 2 + ln
            out.append((src, ms, axis, sense))
        return out

    def straight_case(self, north_to_south=False, **cam_tags):
        """A way before and a way after the camera, joined at the camera node (a way split at the camera, as mapped)."""
        south = self.road(100, -200, -25)
        north = self.road(200, 25, 200)
        self.camera(9000, 0, **cam_tags)
        self.way(1, south + [9000])
        self.way(2, [9000] + north)
        return (2, 1) if north_to_south else (1, 2)

    def one(self, data):
        recs = self.fixed_records(data)
        self.assertEqual(1, len(recs))
        return recs[0]

    def test_northbound_relation_gives_along_and_flag(self):
        f, t = self.straight_case(direction="180")
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        data, stats = self.build()
        src, _, axis, sense = self.one(data)
        self.assertEqual(0, axis)
        self.assertEqual(bc.SENSE_ALONG | bc.SENSE_FLAG_RELATION, sense)
        self.assertEqual(bc.SRC_OSM, src)
        self.assertEqual(bc.HEADER_FLAG_RELATION | bc.SRC_OSM, read_header(data)["flags"])
        self.assertEqual(1, stats["fixed_with_relation_direction"])
        self.assertEqual(1, stats["enforcement_devices_matched_to_camera_nodes"])
        self.assertEqual(1, stats["enforcement_resolved"])

    def test_southbound_relation_gives_against(self):
        f, t = self.straight_case(north_to_south=True, direction="0")
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        data, _ = self.build()
        _, _, axis, sense = self.one(data)
        self.assertEqual(0, axis)
        self.assertEqual(bc.SENSE_AGAINST | bc.SENSE_FLAG_RELATION, sense)

    def test_camera_without_axis_gets_the_relation_bearing_and_the_axis_only_flag(self):
        f, t = self.straight_case(north_to_south=True)  # no `direction` tag
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        data, _ = self.build()
        _, _, axis, sense = self.one(data)
        self.assertEqual(180, axis)
        self.assertEqual(bc.SENSE_ALONG | bc.SENSE_FLAG_RELATION | bc.SENSE_FLAG_AXIS_ONLY, sense)

    def test_both_directions_are_flagged_both(self):
        f, t = self.straight_case(direction="180")
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        self.rel(51, [("node", 9000, "device"), ("way", t, "from"), ("way", f, "to")])
        data, stats = self.build()
        _, _, axis, sense = self.one(data)
        self.assertEqual(0, axis)
        self.assertEqual(bc.SENSE_BOTH | bc.SENSE_FLAG_RELATION, sense)
        self.assertEqual(1, stats["enforcement_cameras_relation_both"])

    def test_missing_member_leaves_the_camera_exactly_as_before(self):
        f, t = self.straight_case(direction="180")
        del self.ways[t]  # the `to` way is not in the extract
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        data, stats = self.build()
        src, _, axis, sense = self.one(data)
        self.assertEqual((0, bc.SENSE_BOTH), (axis, sense))
        self.assertEqual(bc.SRC_OSM, read_header(data)["flags"])
        self.assertEqual({"missing_member": 1}, stats["enforcement_unresolved"])

    def test_missing_from_role_is_unresolved(self):
        f, t = self.straight_case(direction="180")
        self.rel(50, [("node", 9000, "device"), ("way", t, "to")])
        _, stats = self.build()
        self.assertEqual({"missing_role": 1}, stats["enforcement_unresolved"])
        self.assertEqual(0, stats["fixed_with_relation_direction"])

    def test_role_variants_case_and_extra_members(self):
        f, t = self.straight_case(direction="180")
        self.rel(50, [("node", 9000, "Device"), ("way", f, " FROM "), ("way", t, "To"), ("node", 9000, "force"), ("way", t, "via")])
        data, _ = self.build()
        _, _, _, sense = self.one(data)
        self.assertEqual(bc.SENSE_ALONG | bc.SENSE_FLAG_RELATION, sense)

    def test_relation_that_is_not_a_speed_enforcement_is_ignored(self):
        f, t = self.straight_case(direction="180")
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")], enforcement="traffic_signals")
        self.rel(51, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")], enforcement="average_speed")
        data, stats = self.build()
        _, _, _, sense = self.one(data)
        self.assertEqual(bc.SENSE_BOTH, sense)
        self.assertEqual(0, stats["enforcement_maxspeed_relations"])
        self.assertEqual(2, stats["enforcement_relations"])

    def test_camera_without_relation_is_unchanged(self):
        f, t = self.straight_case(direction="180")
        self.camera(9100, 1000, direction="90", maxspeed="80")
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        data, stats = self.build()
        other = [r for r in self.fixed_records(data) if r[1] == 80][0]
        self.assertEqual((90, bc.SENSE_BOTH), (other[2], other[3]))
        self.assertEqual(1, stats["fixed_with_relation_direction"])

    def test_axis_contradicting_the_relation_is_not_forced(self):
        f, t = self.straight_case(direction="90")  # an east-west axis on a north-south relation
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        data, stats = self.build()
        _, _, axis, sense = self.one(data)
        self.assertEqual((90, bc.SENSE_BOTH), (axis, sense))
        self.assertEqual(1, stats["enforcement_relation_axis_conflict"])

    def test_node_to_node_members(self):
        self.camera(9000, 0)
        self.nodes[1] = (40.0 + 100 * M_LAT, -2.0)
        self.nodes[2] = (40.0 - 100 * M_LAT, -2.0)
        self.rel(50, [("node", 9000, "device"), ("node", 1, "from"), ("node", 2, "to")])
        data, _ = self.build()
        _, _, axis, sense = self.one(data)
        self.assertEqual((180, bc.SENSE_ALONG | bc.SENSE_FLAG_RELATION | bc.SENSE_FLAG_AXIS_ONLY), (axis, sense))

    def test_same_way_needs_oneway(self):
        ids = self.road(100, -200, 200)
        self.camera(9000, 0)
        self.way(1, ids)
        self.rel(50, [("node", 9000, "device"), ("way", 1, "from"), ("way", 1, "to")])
        _, stats = self.build()
        self.assertEqual({"same_way_not_oneway": 1}, stats["enforcement_unresolved"])
        self.ways[1]["tags"]["oneway"] = "yes"
        data, _ = self.build()
        _, _, axis, sense = self.one(data)
        self.assertEqual(0, axis)
        self.assertEqual(bc.SENSE_ALONG | bc.SENSE_FLAG_RELATION | bc.SENSE_FLAG_AXIS_ONLY, sense)

    def test_overpass_json_input_matches_osm_xml(self):
        f, t = self.straight_case(direction="180")
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        a, _ = self.build(as_json=False)
        b, _ = self.build(as_json=True)
        self.assertEqual(self.fixed_records(a), self.fixed_records(b))

    def test_geojsonseq_without_ids_matches_the_device_by_position(self):
        f, t = self.straight_case(direction="180")
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        gj = os.path.join(self.tmp, "c.geojsonseq")
        la, lo = self.nodes[9000]
        write(gj, "\x1e" + json.dumps({"type": "Feature", "properties": {"highway": "speed_camera", "direction": "180"},
                                       "geometry": {"type": "Point", "coordinates": [lo, la]}}) + "\n")
        out = os.path.join(self.tmp, "g.bin")
        bc.main(["--osm-geojsonseq", gj, "--osm-enforcement", self.write_enforcement(), "--generated", "2026-10-11T12:00:00Z",
                 "--quiet", "-o", out])
        with open(out, "rb") as fh:
            _, _, _, sense = self.one(fh.read())
        self.assertEqual(bc.SENSE_ALONG | bc.SENSE_FLAG_RELATION, sense)

    def test_dgt_report_sense_is_kept_and_not_flagged(self):
        # A DGT booth with a report sense at the camera position; the relation says southbound (the report says "Creciente", so the DGT sense must win).
        radars = os.path.join(self.tmp, "r.xml")
        write(radars, RADARS.replace("41.30000", "40.00000").replace("41.31000", "40.01000").replace("-1.90000", "-2.00000"))
        report = os.path.join(self.tmp, "r.txt")
        write(report, REPORT)
        anchors = os.path.join(self.tmp, "a.xml")
        write(anchors, ANCHORS.replace("41.305", "40.005").replace("-1.9<", "-2.0<"))
        f, t = self.straight_case(north_to_south=True, direction="180")  # the relation says southbound
        self.rel(50, [("node", 9000, "device"), ("way", f, "from"), ("way", t, "to")])
        osm = os.path.join(self.tmp, "cams.json")
        write(osm, json.dumps(dict(elements=self.cams)))
        out = os.path.join(self.tmp, "d.bin")
        summary = os.path.join(self.tmp, "d.json")
        bc.main(["--dgt-radars", radars, "--dgt-report", report, "--dgt-anchors", anchors, "--osm-json", osm,
                 "--osm-enforcement", self.write_enforcement(), "--generated", "2026-10-11T12:00:00Z", "--quiet",
                 "-o", out, "--summary", summary])
        with open(summary) as fh:
            stats = json.load(fh)
        with open(out, "rb") as fh:
            recs = self.fixed_records(fh.read())
        merged = [r for r in recs if r[0] == bc.SRC_DGT | bc.SRC_OSM]
        self.assertEqual(1, len(merged))
        self.assertEqual(bc.SENSE_ALONG, merged[0][3], "the report's sense wins and is not flagged")
        self.assertEqual(1, stats["enforcement_relation_kept_dgt_sense"])
        self.assertEqual(1, stats["enforcement_relation_disagrees_with_dgt"])

if __name__ == "__main__":
    unittest.main()
