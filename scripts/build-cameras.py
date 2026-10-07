#!/usr/bin/env python3
"""Builds the small speed-camera data file (`speedcams-es.bin`) that ships with the weekly data release.

Inputs (all optional, but at least one source is needed; nothing is downloaded by this script, the workflow does that):

  --dgt-radars FILE       DGT NAP "Radares fijos DGT" (DATEX II v1, `.../PredefinedLocationsPublication/radares/content.xml`).
                          Fixed speed-camera booths (points) and average-speed sections (segments), with coordinates.
  --dgt-report FILE       Text of the DGT report "Puntos y tramos de control de velocidad" (the PDF linked from
                          https://www.dgt.es/conoce-el-estado-del-trafico/vigilancia-y-control/equipos-y-tramos-de-vigilancia/),
                          converted with `pdftotext -layout`. Rows: province, road, type (Radar Fijo / Radar Movil /
                          Radar Tramo), kilometre point or range, sense, report date. It has NO coordinates.
  --dgt-anchors FILE ...  DGT NAP DATEX II v3.7 files (incident snapshot `datex2_v37.xml`, traffic cameras
                          `camaras_datex2_v37.xml`, variable-message panels `vms_datex2_v37.xml`). Used ONLY as a table of
                          (road, kilometre) -> coordinates to place the rows of the report; no incident is stored.
  --osm-json FILE ...     Overpass JSON (`node["highway"="speed_camera"]`), one or more files (tiles).
  --osm-geojsonseq FILE   `osmium export -f geojsonseq` of `n/highway=speed_camera` (one GeoJSON feature per line).

Output: `speedcams-es.bin`, a compact big-endian binary (format in docs/phase2/cameras-data.md, read by
`:core-cameras`), plus an optional JSON summary (`--summary`) with the counts for the release notes.

What goes in, and what does not:
  * Fixed cameras and average-speed sections (DGT + OSM, merged: a DGT and an OSM camera closer than --merge-meters
    become one, keeping the OSM `maxspeed`/`direction` and both source bits).
  * Mobile-radar ZONES, exactly as the DGT publishes them: a road and a kilometre range. They are never turned into
    points. A zone gets a drawable line only when the kilometre points at both ends can be placed from the anchor table
    (see --max-anchor-gap-km); otherwise it stays in the file without geometry (the app shows it only as text).
  * OSM nodes tagged as mobile/temporary (`camera:mobile`, `camera:type=mobile`, `enforcement=mobile...`,
    `disused:`/`construction:`/`abandoned:` prefixes) are dropped. No crowd-sourced reports, no police controls.

Examples (no network):
  scripts/build-cameras.py --dgt-radars radares.xml --dgt-report informe.txt --dgt-anchors datex2_v37.xml camaras_datex2_v37.xml vms_datex2_v37.xml \\
      --osm-json osm-tile-1.json osm-tile-2.json --generated 2026-10-07T12:00:00Z -o speedcams-es.bin --summary summary.json
"""
import argparse
import datetime
import json
import math
import re
import struct
import sys
import unicodedata
import zlib
import xml.etree.ElementTree as ET

MAGIC = 0x554D4341  # "UMCA"
VERSION = 1

SRC_DGT = 1
SRC_OSM = 2

KIND_FIXED = 0
KIND_SECTION = 1
KIND_ZONE = 2

# `sense`: relation between the travel direction that is checked and the axis bearing.
SENSE_BOTH = 0      # both directions of the axis (or unknown)
SENSE_ALONG = 1     # only traffic travelling along `axis` (kilometre points increasing)
SENSE_AGAINST = 2   # only traffic travelling against it

EARTH_RADIUS_M = 6371008.8


def haversine(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def bearing(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def norm_road(r):
    """`N-II`, `n-2`, `A-7 ` -> comparable key: ASCII, upper case, no spaces."""
    s = unicodedata.normalize("NFKD", r or "").encode("ascii", "ignore").decode()
    return re.sub(r"\s+", "", s).upper()


def local(tag):
    return tag.rsplit("}", 1)[-1].split(":")[-1]


def child(el, name):
    for c in el:
        if local(c.tag) == name:
            return c
    return None


def descend(el, *names):
    for n in names:
        el = child(el, n) if el is not None else None
    return el


def text_of(el):
    return el.text.strip() if el is not None and el.text else None


def to_float(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def valid_pos(lat, lon):
    return lat is not None and lon is not None and -90 <= lat <= 90 and -180 <= lon <= 180 and not (lat == 0 and lon == 0)


# --------------------------------------------------------------------------------------------- DGT DATEX II v1 radars

def parse_dgt_radars(path):
    """Returns (points, sections). point: dict(road, km, lat, lon, sense); section: dict(road, km_start, start, end)."""
    root = ET.parse(path).getroot()
    points, sections = [], []
    for loc in root.iter():
        if local(loc.tag) != "predefinedLocation":
            continue
        kind = None
        for k, v in loc.attrib.items():
            if local(k) == "type":
                kind = local(v)
        if kind == "Point":
            pt = descend(loc, "tpegpointLocation", "point", "pointCoordinates")
            lat, lon = to_float(text_of(child(pt, "latitude"))) if pt is not None else None, None
            if pt is not None:
                lon = to_float(text_of(child(pt, "longitude")))
            ref = child(loc, "referencePoint")
            road = text_of(child(ref, "roadNumber")) if ref is not None else None
            dist = to_float(text_of(child(ref, "referencePointDistance"))) if ref is not None else None
            rel = text_of(child(ref, "directionRelative")) if ref is not None else None
            if valid_pos(lat, lon) and road:
                points.append(dict(road=road, km=None if dist is None else dist / 1000.0, lat=lat, lon=lon,
                                   sense={"positive": SENSE_ALONG, "negative": SENSE_AGAINST}.get(rel, SENSE_BOTH)))
        elif kind == "Linear":
            lin = child(loc, "tpeglinearLocation")
            ends = {}
            for which in ("from", "to"):
                e = child(lin, which) if lin is not None else None
                c = descend(e, "pointCoordinates") if e is not None else None
                if c is not None:
                    ends[which] = (to_float(text_of(child(c, "latitude"))), to_float(text_of(child(c, "longitude"))))
            ref = descend(loc, "referencePointLinear", "referencePointPrimaryLocation", "referencePoint")
            road = text_of(child(ref, "roadNumber")) if ref is not None else None
            dist = to_float(text_of(child(ref, "referencePointDistance"))) if ref is not None else None
            if len(ends) == 2 and all(valid_pos(*p) for p in ends.values()) and road:
                sections.append(dict(road=road, km=None if dist is None else dist / 1000.0, start=ends["from"], end=ends["to"]))
    return points, sections


# --------------------------------------------------------------------------------------------- DGT report (text of the PDF)

ROW = re.compile(r"^\s*(?P<prov>\S.*?)\s{2,}(?P<road>\S+)\s+Radar\s+(?P<type>Fijo|M[oó]vil|Tramo)\s+(?P<pk>\d[\d.,]*(?:\s*-\s*\d[\d.,]*)?)\s+(?P<sense>Creciente|Decreciente|Ambos)\b")
LEN = re.compile(r"^\s*\(([\d.]+)\s*m\)\s*$")


def parse_km(s):
    # The report writes kilometre points with a dot as decimal separator ("50.601", "127.006", "17.300 - 34.800").
    return float(s.replace(",", "."))


def parse_dgt_report(path):
    """Returns a list of dict(province, road, type, km_from, km_to, sense, length_m)."""
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = ROW.match(line)
            if m:
                pk = m.group("pk")
                parts = [p.strip() for p in pk.split("-")]
                try:
                    a = parse_km(parts[0])
                    b = parse_km(parts[1]) if len(parts) > 1 else a
                except ValueError:
                    continue
                t = {"Fijo": "fixed", "Tramo": "section"}.get(m.group("type"), "mobile")
                rows.append(dict(province=m.group("prov").strip(), road=m.group("road"), type=t, km_from=min(a, b), km_to=max(a, b),
                                 sense={"Creciente": SENSE_ALONG, "Decreciente": SENSE_AGAINST}.get(m.group("sense"), SENSE_BOTH),
                                 length_m=None))
                continue
            m = LEN.match(line)
            if m and rows and rows[-1]["type"] == "section":
                rows[-1]["length_m"] = float(m.group(1).replace(".", ""))  # "5.638 m": the dot is a thousands separator
    return rows


# --------------------------------------------------------------------------------------------- Anchors: (road, km) -> position

class Anchors:
    """Table of kilometre points with known coordinates, per road. Linear interpolation between neighbours."""

    def __init__(self, max_gap_km):
        self.max_gap_km = max_gap_km
        self.by_road = {}
        self._sorted = False

    def add(self, road, km, lat, lon):
        if km is None or not valid_pos(lat, lon):
            return
        self.by_road.setdefault(norm_road(road), []).append((km, lat, lon))
        self._sorted = False

    def _prepare(self):
        if self._sorted:
            return
        for r, lst in self.by_road.items():
            lst.sort()
            out = []
            for a in lst:  # drop duplicates of the same kilometre (both carriageways): keep the first
                if not out or a[0] - out[-1][0] > 1e-6:
                    out.append(a)
            self.by_road[r] = out
        self._sorted = True

    def _bracket(self, road, km):
        self._prepare()
        lst = self.by_road.get(norm_road(road))
        if not lst:
            return None
        lo = hi = None
        for a in lst:
            if a[0] <= km:
                lo = a
            if a[0] >= km and hi is None:
                hi = a
        return lo, hi

    def position(self, road, km, max_gap_km=None):
        """(lat, lon, gap_km) or None. gap_km is the kilometre distance between the two anchors used (0 for an exact hit)."""
        gap_limit = self.max_gap_km if max_gap_km is None else max_gap_km
        b = self._bracket(road, km)
        if not b:
            return None
        lo, hi = b
        if lo and abs(lo[0] - km) < 0.02:
            return lo[1], lo[2], 0.0
        if hi and abs(hi[0] - km) < 0.02:
            return hi[1], hi[2], 0.0
        if not lo or not hi:
            return None
        gap = hi[0] - lo[0]
        if gap <= 0 or gap > gap_limit:
            return None
        t = (km - lo[0]) / gap
        return lo[1] + (hi[1] - lo[1]) * t, lo[2] + (hi[2] - lo[2]) * t, gap

    def axis(self, road, km, window_km=2.0):
        """Bearing (0..360) of the road in the direction of increasing kilometres around `km`, or None.

        Uses the farthest-apart pair of anchors within `window_km` on either side that are at least 100 m apart on the
        ground. A rough local direction (a straight line over at most 2 * window_km), good enough to tell a parallel
        road from a crossing one; it is NOT a published value.
        """
        self._prepare()
        near = [a for a in self.by_road.get(norm_road(road), []) if abs(a[0] - km) <= window_km]
        if len(near) < 2:
            return None
        lo, hi = near[0], near[-1]
        if hi[0] - lo[0] <= 0 or haversine((lo[1], lo[2]), (hi[1], hi[2])) < 100:
            return None
        return bearing((lo[1], lo[2]), (hi[1], hi[2]))

    def between(self, road, km_from, km_to):
        """Anchors strictly inside the range, for drawing a zone line."""
        self._prepare()
        return [(k, la, lo) for (k, la, lo) in self.by_road.get(norm_road(road), []) if km_from < k < km_to]


def parse_anchor_file(path, anchors):
    """Adds (road, km) -> (lat, lon) from every location of a DATEX II v3.7 situation or device publication. Stores nothing else."""
    count = 0
    for ev, el in ET.iterparse(path, events=("end",)):
        if local(el.tag) not in ("situationRecord", "device"):
            continue
        road = None
        for r in el.iter():
            if local(r.tag) == "roadName":
                road = text_of(r)
                break
        # Each TpegNonJunctionPoint (`to`, `from`, `point`) holds its coordinates and its extension with the kilometre point.
        for node in el.iter():
            if local(node.tag) in ("to", "from", "point"):
                c = descend(node, "pointCoordinates")
                e = descend(node, "_tpegNonJunctionPointExtension", "extendedTpegNonJunctionPoint")
                km = to_float(text_of(child(e, "kilometerPoint"))) if e is not None else None
                if c is not None and km is not None and road:
                    anchors.add(road, km, to_float(text_of(child(c, "latitude"))), to_float(text_of(child(c, "longitude"))))
                    count += 1
        el.clear()
    return count


# --------------------------------------------------------------------------------------------- OSM

MOBILE_TAGS = ("camera:mobile", "mobile", "temporary", "camera:temporary")


def osm_is_fixed(tags):
    if tags.get("highway") != "speed_camera":
        return False
    for k, v in tags.items():
        if k.split(":")[0] in ("disused", "construction", "abandoned", "proposed", "razed", "was"):
            return False
        if k in MOBILE_TAGS and v.lower() not in ("no", "false", "0"):
            return False
        if k in ("camera:type", "enforcement", "speed_camera", "device") and re.search(r"mobile|temporary|portable|tripod", v, re.I):
            return False
    return True


CARDINALS = {"N": 0, "NNE": 22.5, "NE": 45, "ENE": 67.5, "E": 90, "ESE": 112.5, "SE": 135, "SSE": 157.5, "S": 180, "SSW": 202.5,
             "SW": 225, "WSW": 247.5, "W": 270, "WNW": 292.5, "NW": 315, "NNW": 337.5}


def parse_direction(v):
    if v is None:
        return None
    v = v.strip()
    if v.upper() in CARDINALS:
        return float(CARDINALS[v.upper()])
    if re.fullmatch(r"\d{1,3}(\.\d+)?", v):
        d = float(v)
        return d % 360 if d <= 360 else None
    return None  # "forward", "backward", "both", ranges: not an angle


def parse_maxspeed(v):
    if v is None:
        return None
    m = re.fullmatch(r"\s*(\d{2,3})\s*(km/h)?\s*", v)
    if m:
        s = int(m.group(1))
        return s if 10 <= s <= 200 else None
    return None  # "mph", "signals", "none"...


def load_osm(json_files, geojsonseq_files):
    seen, nodes, dropped = set(), [], 0
    for p in json_files:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
        for e in data.get("elements", []):
            if e.get("type") != "node" or e["id"] in seen:
                continue
            seen.add(e["id"])
            tags = e.get("tags", {})
            if not osm_is_fixed(tags) or not valid_pos(e.get("lat"), e.get("lon")):
                dropped += 1
                continue
            nodes.append(dict(id=e["id"], lat=e["lat"], lon=e["lon"], tags=tags))
    for p in geojsonseq_files:
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.lstrip("\x1e").strip()
                if not line:
                    continue
                ft = json.loads(line)
                g = ft.get("geometry") or {}
                tags = ft.get("properties") or {}
                if g.get("type") != "Point":
                    continue
                osm_id = str(ft.get("id", ft.get("properties", {}).get("@id", "")))
                if osm_id in seen:
                    continue
                seen.add(osm_id)
                lon, lat = g["coordinates"][:2]
                if not osm_is_fixed(tags) or not valid_pos(lat, lon):
                    dropped += 1
                    continue
                nodes.append(dict(id=osm_id, lat=lat, lon=lon, tags=tags))
    return nodes, dropped


# --------------------------------------------------------------------------------------------- Assembly

def build(args):
    anchors = Anchors(args.max_anchor_gap_km)
    stats = {}
    points, sections = ([], [])
    if args.dgt_radars:
        points, sections = parse_dgt_radars(args.dgt_radars)
        for p in points:
            anchors.add(p["road"], p["km"], p["lat"], p["lon"])
        for s in sections:
            if s["km"] is not None:
                anchors.add(s["road"], s["km"], *s["start"])
    stats["dgt_points"] = len(points)
    stats["dgt_sections"] = len(sections)
    stats["anchors_from_v37_files"] = sum(parse_anchor_file(p, anchors) for p in (args.dgt_anchors or []))
    report = parse_dgt_report(args.dgt_report) if args.dgt_report else []
    stats["report_rows"] = len(report)

    fixed = []  # dict(lat, lon, road, maxspeed, axis, sense, src)
    # DGT booths: sense/axis from the report when a row matches (same road, same kilometre within 20 m).
    report_fixed = [r for r in report if r["type"] == "fixed"]
    used_report = set()
    for p in points:
        axis = anchors.axis(p["road"], p["km"]) if p["km"] is not None else None
        sense = p["sense"]
        for i, r in enumerate(report_fixed):
            if p["km"] is not None and norm_road(r["road"]) == norm_road(p["road"]) and abs(r["km_from"] - p["km"]) < 0.02:
                used_report.add(i)
                if r["sense"] != SENSE_BOTH:
                    sense = r["sense"]
                break
        if axis is None:
            sense = SENSE_BOTH
        fixed.append(dict(lat=p["lat"], lon=p["lon"], road=p["road"], maxspeed=0, axis=axis, sense=sense, src=SRC_DGT))
    # Fixed radars that appear in the report but have no coordinates are NOT placed: interpolating a kilometre point
    # between neighbours is off by hundreds of metres (measured), too coarse for a "camera ahead" alert.
    stats["report_fixed_without_coordinates_dropped"] = sum(1 for i in range(len(report_fixed)) if i not in used_report)

    sects = [dict(start=s["start"], end=s["end"], road=s["road"], maxspeed=0, src=SRC_DGT) for s in sections]

    # OSM, merged into DGT fixed cameras when closer than --merge-meters.
    osm_nodes, osm_dropped = ([], 0)
    if args.osm_json or args.osm_geojsonseq:
        osm_nodes, osm_dropped = load_osm(args.osm_json or [], args.osm_geojsonseq or [])
    stats["osm_fixed_nodes"] = len(osm_nodes)
    stats["osm_dropped_mobile_or_disused"] = osm_dropped
    merged = 0
    dgt_fixed = list(fixed)
    grid = {}
    for i, c in enumerate(dgt_fixed):
        grid.setdefault((int(c["lat"] * 100), int(c["lon"] * 100)), []).append(i)
    for n in osm_nodes:
        ms = parse_maxspeed(n["tags"].get("maxspeed"))
        d = parse_direction(n["tags"].get("direction"))
        near = None
        gx, gy = int(n["lat"] * 100), int(n["lon"] * 100)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for i in grid.get((gx + dx, gy + dy), []):
                    dist = haversine((n["lat"], n["lon"]), (dgt_fixed[i]["lat"], dgt_fixed[i]["lon"]))
                    if dist <= args.merge_meters and (near is None or dist < near[0]):
                        near = (dist, i)
        axis = None if d is None else d % 180.0
        if near:
            c = dgt_fixed[near[1]]
            c["src"] |= SRC_OSM
            if ms and not c["maxspeed"]:
                c["maxspeed"] = ms
            if axis is not None:
                c["axis"], c["sense"] = axis, SENSE_BOTH
            merged += 1
        else:
            fixed.append(dict(lat=n["lat"], lon=n["lon"], road=n["tags"].get("ref", ""), maxspeed=ms or 0, axis=axis, sense=SENSE_BOTH, src=SRC_OSM))
    stats["merged_dgt_osm"] = merged

    # Mobile zones: as published (road + kilometre range). Geometry only when both ends can be placed.
    zones = []
    for r in report:
        if r["type"] != "mobile":
            continue
        a = anchors.position(r["road"], r["km_from"])
        b = anchors.position(r["road"], r["km_to"])
        line = []
        if a and b:
            inner = anchors.between(r["road"], r["km_from"], r["km_to"])
            line = [(a[0], a[1])] + [(la, lo) for (_k, la, lo) in inner] + [(b[0], b[1])]
            # Drop zones whose line is absurd (a kilometre range of N km must not be drawn much shorter or longer).
            span_km = r["km_to"] - r["km_from"]
            length_km = sum(haversine(line[i], line[i + 1]) for i in range(len(line) - 1)) / 1000.0
            if span_km > 0 and not (0.5 * span_km <= length_km <= 1.3 * span_km + 0.5):
                line = []
        zones.append(dict(road=r["road"], province=r["province"], km_from=r["km_from"], km_to=r["km_to"], line=line))
    stats["mobile_zones"] = len(zones)
    stats["mobile_zones_with_line"] = sum(1 for z in zones if z["line"])

    stats["fixed"] = len(fixed)
    stats["fixed_with_axis"] = sum(1 for c in fixed if c["axis"] is not None)
    stats["fixed_with_maxspeed"] = sum(1 for c in fixed if c["maxspeed"])
    stats["sections"] = len(sects)
    return fixed, sects, zones, stats


def write_file(path, generated_epoch, fixed, sects, zones):
    """Layout (big endian). See docs/phase2/cameras-data.md.

    u32 magic, u16 version, i64 generated (epoch seconds), u8 source_flags, u32 n_fixed, u32 n_sections, u32 n_zones,
    fixed:   i32 lat_e6, i32 lon_e6, u8 src, u8 maxspeed(0=unknown), i16 axis_deg(-1=unknown), u8 sense, utf road
    section: i32 lat_e6, i32 lon_e6, i32 lat_e6, i32 lon_e6, u8 src, u8 maxspeed, utf road
    zone:    utf road, utf province, i32 km_from_m, i32 km_to_m, u16 n_points, n x (i32 lat_e6, i32 lon_e6)
    u32 crc32 of everything before it.  `utf` = u16 length + UTF-8 bytes (Java's writeUTF for BMP text).
    """
    out = bytearray()
    flags = 0
    for c in fixed:
        flags |= c["src"]
    for s in sects:
        flags |= s["src"]
    if zones:
        flags |= SRC_DGT

    def utf(s):
        b = (s or "").encode("utf-8")[:60000]
        out.extend(struct.pack(">H", len(b)) + b)

    def e6(v):
        return int(round(v * 1_000_000))

    out.extend(struct.pack(">IHqBIII", MAGIC, VERSION, generated_epoch, flags, len(fixed), len(sects), len(zones)))
    for c in fixed:
        axis = -1 if c["axis"] is None else int(round(c["axis"])) % 360
        out.extend(struct.pack(">iiBBhB", e6(c["lat"]), e6(c["lon"]), c["src"], c["maxspeed"], axis, c["sense"]))
        utf(c["road"])
    for s in sects:
        out.extend(struct.pack(">iiiiBB", e6(s["start"][0]), e6(s["start"][1]), e6(s["end"][0]), e6(s["end"][1]), s["src"], s["maxspeed"]))
        utf(s["road"])
    for z in zones:
        utf(z["road"])
        utf(z["province"])
        out.extend(struct.pack(">iiH", int(round(z["km_from"] * 1000)), int(round(z["km_to"] * 1000)), len(z["line"])))
        for la, lo in z["line"]:
            out.extend(struct.pack(">ii", e6(la), e6(lo)))
    out.extend(struct.pack(">I", zlib.crc32(bytes(out)) & 0xFFFFFFFF))
    with open(path, "wb") as f:
        f.write(out)
    return len(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dgt-radars")
    ap.add_argument("--dgt-report")
    ap.add_argument("--dgt-anchors", nargs="*")
    ap.add_argument("--osm-json", nargs="*")
    ap.add_argument("--osm-geojsonseq", nargs="*")
    ap.add_argument("--generated", help="ISO 8601 UTC time of the build (default: now)")
    ap.add_argument("--merge-meters", type=float, default=100.0)
    ap.add_argument("--max-anchor-gap-km", type=float, default=15.0, help="largest kilometre gap bridged to place a zone end")
    ap.add_argument("-o", "--output", required=True)
    ap.add_argument("--summary")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    if not (args.dgt_radars or args.dgt_report or args.osm_json or args.osm_geojsonseq):
        ap.error("at least one source is required")
    gen = (datetime.datetime.fromisoformat(args.generated.replace("Z", "+00:00")) if args.generated
           else datetime.datetime.now(datetime.timezone.utc))
    fixed, sects, zones, stats = build(args)
    size = write_file(args.output, int(gen.timestamp()), fixed, sects, zones)
    stats["bytes"] = size
    stats["generated"] = gen.strftime("%Y-%m-%dT%H:%M:%SZ")
    if args.summary:
        with open(args.summary, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=2, sort_keys=True)
    if not args.quiet:
        print(json.dumps(stats, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
