#!/usr/bin/env python3
"""Builds the small low-emission-zone polygon file (`zbe-es.bin`) that ships with the weekly data release.

Inputs (nothing is downloaded by this script, the workflow does that):

  --osm-geojsonseq FILE ...  `osmium export -f geojsonseq` of `wr/boundary=low_emission_zone` (one GeoJSON feature per line).
                             Polygon and MultiPolygon features are used; a closed LineString (a way that osmium did not turn
                             into an area) is treated as one ring; anything else is dropped.
  --osm-json FILE ...        Overpass JSON with `out geom;` (ways and multipolygon/boundary relations), for small samples.

Output: `zbe-es.bin`, a compact big-endian binary (format in docs/phase2/zbe-data.md, read by `:core-zbe`), plus an optional
JSON summary (`--summary`) with the counts for the release notes.

What goes in: OpenStreetMap areas tagged `boundary=low_emission_zone` (ODbL). This is the only tag used, on purpose (see
docs/phase2/zbe-data.md): the plain key `low_emission_zone=*` is used on other kinds of objects and on whole-municipality
boundaries, which would paint a whole city as a zone. For each zone the file keeps: name, city (`addr:city`, `is_in:city`,
`city`), an optional restriction text (a `description:es`/`description`/`note`, or else the raw `*:conditional` access tag) and
the simplified polygon (outer rings and holes). Nothing about vehicle labels or stickers is decided here or in the app.

What is dropped: zones with a lifecycle prefix, `access=yes` (the tag says there is no restriction), a `start_date` in the future
or an `end_date` in the past, rings with fewer than three distinct points, and invalid positions. Rings are simplified with
Douglas-Peucker (default 4 m) and capped (`--max-ring-points`) so the file stays small.

Example (no network):
  scripts/build-zbe.py --osm-geojsonseq zbe.geojsonseq --generated 2026-10-08T12:00:00Z -o zbe-es.bin --summary summary.json
"""
import argparse
import datetime
import json
import math
import re
import struct
import sys
import zlib

MAGIC = 0x554D5A42  # "UMZB"
VERSION = 1
SRC_OSM = 1

LIFECYCLE_PREFIXES = ("disused", "construction", "abandoned", "proposed", "razed", "was")
CONDITIONAL_KEYS = ("access:conditional", "motor_vehicle:conditional", "motorcar:conditional", "vehicle:conditional")
CITY_KEYS = ("addr:city", "is_in:city", "city")
RESTRICTION_KEYS = ("description:es", "description", "note:es", "note")
MAX_TEXT = 80
MAX_RESTRICTION = 240
MAX_RING_POINTS = 1500
DEFAULT_TOLERANCE_M = 4.0
METERS_PER_DEGREE = 111_195.0


def valid_pos(lat, lon):
    return (isinstance(lat, (int, float)) and isinstance(lon, (int, float))
            and -90 <= lat <= 90 and -180 <= lon <= 180 and not (lat == 0 and lon == 0))


def clean(s, limit=MAX_TEXT):
    s = re.sub(r"\s+", " ", s or "").strip()
    return s[:limit]


def parse_date(v):
    """`YYYY`, `YYYY-MM` or `YYYY-MM-DD` as a date (first day when partial); None when it is anything else."""
    m = re.fullmatch(r"\s*(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?\s*", v or "")
    if not m:
        return None
    try:
        return datetime.date(int(m.group(1)), int(m.group(2) or 1), int(m.group(3) or 1))
    except ValueError:
        return None


def is_live(tags, today):
    """True when the tags describe a zone that is (as far as OSM says) in force on [today]."""
    if tags.get("boundary") != "low_emission_zone":
        return False
    for k in tags:
        if k.split(":")[0] in LIFECYCLE_PREFIXES:
            return False
    if tags.get("access", "").lower() == "yes":
        return False
    start = parse_date(tags.get("start_date"))
    if start and start > today:
        return False
    end = parse_date(tags.get("end_date"))
    if end and end < today:
        return False
    return True


def first_tag(tags, keys):
    for k in keys:
        v = clean(tags.get(k), MAX_RESTRICTION)
        if v:
            return v
    return ""


def make_zone_meta(tags):
    return dict(
        name=clean(tags.get("name:es") or tags.get("name") or tags.get("ref")),
        city=clean(first_tag(tags, CITY_KEYS)),
        restriction=first_tag(tags, RESTRICTION_KEYS) or first_tag(tags, CONDITIONAL_KEYS),
    )


# ------------------------------------------------------------------------------------------------ geometry

def _point_segment_m(p, a, b, kx):
    """Distance in metres from point [p] to segment [a, b]; points are (lat, lon), [kx] = cos(latitude)."""
    ax, ay = a[1] * kx * METERS_PER_DEGREE, a[0] * METERS_PER_DEGREE
    bx, by = b[1] * kx * METERS_PER_DEGREE, b[0] * METERS_PER_DEGREE
    px, py = p[1] * kx * METERS_PER_DEGREE, p[0] * METERS_PER_DEGREE
    dx, dy = bx - ax, by - ay
    n = dx * dx + dy * dy
    t = 0.0 if n == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / n))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def simplify_open(points, tol_m):
    """Douglas-Peucker on an open polyline (iterative: rings can have thousands of points)."""
    n = len(points)
    if n <= 2:
        return list(points)
    kx = max(math.cos(math.radians(sum(p[0] for p in points) / n)), 0.01)
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        lo, hi = stack.pop()
        far, idx = -1.0, -1
        for i in range(lo + 1, hi):
            d = _point_segment_m(points[i], points[lo], points[hi], kx)
            if d > far:
                far, idx = d, i
        if idx >= 0 and far > tol_m:
            keep[idx] = True
            stack.append((lo, idx))
            stack.append((idx, hi))
    return [p for p, k in zip(points, keep) if k]


def simplify_ring(ring, tol_m, max_points):
    """`ring` is a closed list of (lat, lon) (first == last). Returns a closed ring with >= 4 points, or None.

    A ring is split at its two farthest-apart extreme vertices so the simplification never collapses it to a line; the
    tolerance doubles until the ring fits [max_points].
    """
    pts = ring[:-1] if len(ring) > 1 and ring[0] == ring[-1] else list(ring)
    dedup = [p for i, p in enumerate(pts) if i == 0 or p != pts[i - 1]]
    if len(dedup) < 3:
        return None
    # split at the westernmost and the easternmost vertex: two open chains
    i0 = min(range(len(dedup)), key=lambda i: dedup[i][1])
    i1 = max(range(len(dedup)), key=lambda i: dedup[i][1])
    if i0 == i1:
        i1 = (i0 + len(dedup) // 2) % len(dedup)
    rot = dedup[i0:] + dedup[:i0]
    k = (i1 - i0) % len(dedup)
    a, b = rot[:k + 1], rot[k:] + [rot[0]]
    tol = tol_m
    while True:
        sa, sb = simplify_open(a, tol), simplify_open(b, tol)
        out = sa[:-1] + sb[:-1]
        if len(out) <= max_points or tol > 5000:
            break
        tol *= 2
    if len(out) < 3:
        return None
    return out + [out[0]]


def ring_area_deg2(ring):
    s = 0.0
    for (y0, x0), (y1, x1) in zip(ring, ring[1:]):
        s += x0 * y1 - x1 * y0
    return abs(s) / 2.0


def to_polygons(geom):
    """GeoJSON geometry -> list of polygons; a polygon is a list of rings (outer first); a ring is a list of (lat, lon)."""
    if not geom:
        return []
    t, c = geom.get("type"), geom.get("coordinates")

    def ring(r):
        return [(p[1], p[0]) for p in r if isinstance(p, (list, tuple)) and len(p) >= 2]

    if t == "Polygon":
        return [[ring(r) for r in c]]
    if t == "MultiPolygon":
        return [[ring(r) for r in poly] for poly in c]
    if t == "LineString":
        r = ring(c)
        return [[r]] if len(r) >= 4 and r[0] == r[-1] else []
    return []


def clean_polygon(poly, tol_m, max_points, stats):
    rings = []
    for i, r in enumerate(poly):
        if any(not valid_pos(lat, lon) for lat, lon in r):
            stats["dropped_bad_position"] += 1
            if i == 0:
                return None
            continue
        s = simplify_ring(r, tol_m, max_points)
        if s is None:
            if i == 0:
                return None
            continue
        rings.append(s)
    return rings or None


def overpass_polygons(el):
    """Overpass `out geom;` element -> polygons (closed way, or the outer/inner rings of a relation joined end to end)."""
    def pts(geom):
        return [(g["lat"], g["lon"]) for g in geom or [] if g]

    if el.get("type") == "way":
        r = pts(el.get("geometry"))
        return [[r]] if len(r) >= 4 and r[0] == r[-1] else []
    outers, inners = [], []
    for m in el.get("members", []):
        if m.get("type") == "way" and m.get("geometry"):
            (inners if m.get("role") == "inner" else outers).append(pts(m["geometry"]))
    outers, inners = join_rings(outers), join_rings(inners)
    return [[o] + [i for i in inners if i] for o in outers] if outers else []


def join_rings(ways):
    """Joins way fragments end to end into closed rings; fragments that do not close are dropped."""
    ways = [w for w in ways if len(w) >= 2]
    rings = []
    while ways:
        cur = ways.pop(0)
        while cur[0] != cur[-1]:
            for i, w in enumerate(ways):
                if w[0] == cur[-1]:
                    cur = cur + w[1:]
                elif w[-1] == cur[-1]:
                    cur = cur + w[::-1][1:]
                elif w[-1] == cur[0]:
                    cur = w + cur[1:]
                elif w[0] == cur[0]:
                    cur = w[::-1] + cur[1:]
                else:
                    continue
                ways.pop(i)
                break
            else:
                cur = None
                break
        if cur and len(cur) >= 4:
            rings.append(cur)
    return rings


def load_osm(json_files, geojsonseq_files, today, tol_m=DEFAULT_TOLERANCE_M, max_points=MAX_RING_POINTS):
    """Returns (zones, stats). A zone is {name, city, restriction, polygons}. The same feature id (or the same name and first
    point when there is no id) is kept once."""
    seen, zones = set(), []
    stats = dict(features=0, duplicates=0, dropped_not_live=0, dropped_no_polygon=0, dropped_bad_position=0)

    def add(key, tags, polygons):
        stats["features"] += 1
        if key in seen:
            stats["duplicates"] += 1
            return
        seen.add(key)
        if not is_live(tags, today):
            stats["dropped_not_live"] += 1
            return
        polys = [p for p in (clean_polygon(p, tol_m, max_points, stats) for p in polygons) if p]
        if not polys:
            stats["dropped_no_polygon"] += 1
            return
        zones.append(dict(make_zone_meta(tags), polygons=polys))

    for p in json_files:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
        for e in data.get("elements", []):
            if e.get("type") in ("way", "relation"):
                add(f'{e["type"]}/{e.get("id")}', e.get("tags") or {}, overpass_polygons(e))
    for p in geojsonseq_files:
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.lstrip("\x1e").strip()
                if not line:
                    continue
                ft = json.loads(line)
                tags = ft.get("properties") or {}
                polys = to_polygons(ft.get("geometry"))
                first = polys[0][0][0] if polys and polys[0] and polys[0][0] else None
                key = str(ft.get("id") or tags.get("@id") or f"{tags.get('name', '')}@{first}")
                add(key, tags, polys)
    zones.sort(key=lambda z: (z["name"].lower(), z["city"].lower()))
    return zones, stats


def write_file(path, generated_epoch, zones):
    """Layout (big endian). See docs/phase2/zbe-data.md.

    u32 magic, u16 version, i64 generated (epoch seconds), u8 source_flags, u32 n_zones
    zone: utf name, utf city, utf restriction, u8 n_polygons,
          polygon: u8 n_rings (first = outer ring, others = holes),
                   ring: u16 n_points (the first point is NOT repeated at the end), n_points x (i32 lat_e6, i32 lon_e6)
    u32 crc32 of everything before it.  `utf` = u16 length + UTF-8 bytes (Java's writeUTF for BMP text).
    """
    out = bytearray()

    def utf(s):
        b = (s or "").encode("utf-8")
        while len(b) > 60000:
            b = b[:-1]
        out.extend(struct.pack(">H", len(b)) + b)

    def e6(v):
        return int(round(v * 1_000_000))

    out.extend(struct.pack(">IHqBI", MAGIC, VERSION, generated_epoch, SRC_OSM if zones else 0, len(zones)))
    for z in zones:
        utf(z["name"])
        utf(z["city"])
        utf(z["restriction"])
        polys = z["polygons"][:255]
        out.extend(struct.pack(">B", len(polys)))
        for poly in polys:
            rings = poly[:255]
            out.extend(struct.pack(">B", len(rings)))
            for r in rings:
                pts = r[:-1]  # drop the closing point
                out.extend(struct.pack(">H", len(pts)))
                for lat, lon in pts:
                    out.extend(struct.pack(">ii", e6(lat), e6(lon)))
    out.extend(struct.pack(">I", zlib.crc32(bytes(out)) & 0xFFFFFFFF))
    with open(path, "wb") as f:
        f.write(out)
    return len(out)


def build(args, today):
    zones, stats = load_osm(args.osm_json or [], args.osm_geojsonseq or [], today, args.tolerance_m, args.max_ring_points)
    stats["zones"] = len(zones)
    stats["with_name"] = sum(1 for z in zones if z["name"])
    stats["with_city"] = sum(1 for z in zones if z["city"])
    stats["with_restriction_text"] = sum(1 for z in zones if z["restriction"])
    stats["with_holes"] = sum(1 for z in zones if any(len(p) > 1 for p in z["polygons"]))
    stats["points"] = sum(len(r) - 1 for z in zones for p in z["polygons"] for r in p)
    return zones, stats


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--osm-json", nargs="*")
    ap.add_argument("--osm-geojsonseq", nargs="*")
    ap.add_argument("--generated", help="ISO 8601 UTC time of the build (default: now)")
    ap.add_argument("--tolerance-m", type=float, default=DEFAULT_TOLERANCE_M, help="simplification tolerance in metres")
    ap.add_argument("--max-ring-points", type=int, default=MAX_RING_POINTS)
    ap.add_argument("-o", "--output", required=True)
    ap.add_argument("--summary")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    if not (args.osm_json or args.osm_geojsonseq):
        ap.error("at least one source is required")
    gen = (datetime.datetime.fromisoformat(args.generated.replace("Z", "+00:00")) if args.generated
           else datetime.datetime.now(datetime.timezone.utc))
    zones, stats = build(args, gen.date())
    stats["bytes"] = write_file(args.output, int(gen.timestamp()), zones)
    stats["generated"] = gen.strftime("%Y-%m-%dT%H:%M:%SZ")
    if args.summary:
        with open(args.summary, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=2, sort_keys=True)
    if not args.quiet:
        print(json.dumps(stats, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
