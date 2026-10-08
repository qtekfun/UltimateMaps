#!/usr/bin/env python3
"""Builds the hiking and cycling route overlay file (`routes-es.bin`) that ships with the weekly data release.

Inputs (nothing is downloaded by this script, the workflow does that). Either:

  --relations-opl FILE --ways-geojsonseq FILE
      Both taken from the output of `osmium tags-filter spain.osm.pbf r/route=hiking,foot,bicycle,mtb` (it keeps the member
      ways and nodes): the relations with `osmium cat -t relation -f opl`, the ways with
      `osmium export -c export.json -n -u type_id --geometry-types=linestring -f geojsonseq` where export.json is
      `{"linear_tags": true, "area_tags": false}` (loops stay lines; `-n` keeps untagged ways; `-u type_id` gives ids `w<id>`).
  --osm-json FILE ...
      Overpass JSON of `relation[route~"^(hiking|foot|bicycle|mtb)$"](bbox); out geom;` (small samples and tests).

Output: `routes-es.bin` (format in docs/phase2/osm-routes.md, read by `:core-routes`) plus an optional JSON summary.

What goes in: relations tagged `type=route` with `route=hiking|foot|bicycle|mtb` and a network of the walking family
(lwn, rwn, nwn, iwn) or the cycling family (rcn, ncn, icn; lcn only for mtb). A hiking or foot route without a network is
kept as local when it has a name or a ref. Bicycle routes need a regional or higher network (local city cycle networks are
mostly bike lanes and would drown the map). Dropped: proposed or disused routes (`state=`), lifecycle prefixes, relations
without geometry. Geometry: member ways chained end to end, simplified with Douglas-Peucker (default 15 m) and stored as
delta-coded varints, the whole body deflated.
"""
import argparse
import datetime
import json
import math
import re
import struct
import sys
import zlib

MAGIC = 0x554D5254  # "UMRT"
VERSION = 1

KIND_HIKING, KIND_BICYCLE, KIND_MTB = 0, 1, 2
KIND_BY_ROUTE = {"hiking": KIND_HIKING, "foot": KIND_HIKING, "bicycle": KIND_BICYCLE, "mtb": KIND_MTB}
LEVEL_LOCAL, LEVEL_REGIONAL, LEVEL_NATIONAL, LEVEL_INTERNATIONAL = 0, 1, 2, 3
LEVEL_BY_LETTER = {"l": LEVEL_LOCAL, "r": LEVEL_REGIONAL, "n": LEVEL_NATIONAL, "i": LEVEL_INTERNATIONAL}
FLAG_LENGTH_TAGGED = 1

MAX_TEXT = 80
MAX_SEGMENT_POINTS = 60000
DEFAULT_TOLERANCE_M = 15.0
EARTH_M = 6371008.8
LIFECYCLE_PREFIXES = ("disused", "construction", "abandoned", "proposed", "razed", "was")


def clean(s, limit=MAX_TEXT):
    return re.sub(r"\s+", " ", s or "").strip()[:limit]


def classify(tags):
    """(kind, level) of a relation, or None when it does not belong in the overlay."""
    if tags.get("type") != "route":
        return None
    kind = KIND_BY_ROUTE.get((tags.get("route") or "").lower())
    if kind is None:
        return None
    if (tags.get("state") or "").lower() in ("proposed", "disused", "abandoned"):
        return None
    if any(k.split(":")[0] in LIFECYCLE_PREFIXES for k in tags):
        return None
    net = (tags.get("network") or "").strip().lower()
    letter = net[0] if re.fullmatch(r"[lrni](wn|cn)", net) else None
    walking = bool(letter) and net.endswith("wn")
    cycling = bool(letter) and net.endswith("cn")
    named = bool(tags.get("name") or tags.get("ref"))
    if kind == KIND_BICYCLE:
        if not cycling or letter == "l":
            return None
        return kind, LEVEL_BY_LETTER[letter]
    if kind == KIND_MTB:
        if letter:
            return kind, LEVEL_BY_LETTER[letter]
        return (kind, LEVEL_LOCAL) if named else None
    if walking:
        return kind, LEVEL_BY_LETTER[letter]
    return (kind, LEVEL_LOCAL) if (not net or not letter) and named else None


def title_of(tags):
    name = clean(tags.get("name"))
    if name:
        return name
    a, b = clean(tags.get("from")), clean(tags.get("to"))
    return f"{a} - {b}" if a and b else ""


def parse_distance_m(v):
    """Tagged `distance` in metres: "12.5", "12,5 km", "12500 m", "7 mi"; 0 when absent or implausible."""
    m = re.fullmatch(r"\s*(\d+(?:[.,]\d+)?)\s*(km|m|mi)?\s*", (v or "").lower())
    if not m:
        return 0
    x = float(m.group(1).replace(",", "."))
    unit = m.group(2) or "km"  # OSM: the default unit is km
    metres = x * {"km": 1000.0, "m": 1.0, "mi": 1609.344}[unit]
    return int(round(metres)) if 100 <= metres <= 5_000_000 else 0


# ---------------------------------------------------------------- geometry

def _xy(points):
    lat0 = sum(p[0] for p in points) / len(points)
    kx = math.cos(math.radians(lat0)) * math.pi / 180.0 * EARTH_M
    ky = math.pi / 180.0 * EARTH_M
    return [(p[1] * kx, p[0] * ky) for p in points]


def simplify(points, tol_m):
    """Douglas-Peucker on (lat, lon) points, iterative; the end points are always kept."""
    n = len(points)
    if n <= 2 or tol_m <= 0:
        return list(points)
    xy = _xy(points)
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    t2 = tol_m * tol_m
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        ax, ay = xy[a]
        dx, dy = xy[b][0] - ax, xy[b][1] - ay
        len2 = dx * dx + dy * dy
        best, idx = -1.0, -1
        for i in range(a + 1, b):
            px, py = xy[i][0] - ax, xy[i][1] - ay
            if len2 == 0:
                d2 = px * px + py * py
            else:
                t = max(0.0, min(1.0, (px * dx + py * dy) / len2))
                ex, ey = px - t * dx, py - t * dy
                d2 = ex * ex + ey * ey
            if d2 > best:
                best, idx = d2, i
        if best > t2:
            keep[idx] = True
            stack.append((a, idx))
            stack.append((idx, b))
    return [p for p, k in zip(points, keep) if k]


def haversine_m(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_M * math.asin(math.sqrt(h))


def path_length_m(segments):
    return sum(haversine_m(s[i], s[i + 1]) for s in segments for i in range(len(s) - 1))


def chain(ways):
    """Joins ways that meet end to end (same point, either direction) into longer lines; the rest stay separate."""
    segs = []
    for w in ways:
        if len(w) < 2:
            continue
        w = list(w)
        merged = False
        for s in segs:
            if s[-1] == w[0]:
                s.extend(w[1:])
            elif s[-1] == w[-1]:
                s.extend(reversed(w[:-1]))
            elif s[0] == w[-1]:
                s[:0] = w[:-1]
            elif s[0] == w[0]:
                s[:0] = list(reversed(w[1:]))
            else:
                continue
            merged = True
            break
        if not merged:
            segs.append(w)
    return segs


def split_long(seg):
    if len(seg) <= MAX_SEGMENT_POINTS:
        return [seg]
    return [seg[i:i + MAX_SEGMENT_POINTS] for i in range(0, len(seg) - 1, MAX_SEGMENT_POINTS - 1) if len(seg[i:i + MAX_SEGMENT_POINTS]) > 1]


def valid_pos(lat, lon):
    return (isinstance(lat, (int, float)) and isinstance(lon, (int, float))
            and -90 <= lat <= 90 and -180 <= lon <= 180 and not (lat == 0 and lon == 0))


# ---------------------------------------------------------------- inputs

def _opl_decode(s):
    return re.sub(r"%([0-9a-fA-F]+)%", lambda m: chr(int(m.group(1), 16)), s)


def parse_opl_relation(line):
    """(id, tags, [way ids]) of one `r...` OPL line, or None for other lines."""
    if not line.startswith("r"):
        return None
    parts = line.rstrip("\n").split(" ")
    try:
        rid = int(parts[0][1:])
    except ValueError:
        return None
    tags, ways = {}, []
    for p in parts[1:]:
        if p.startswith("T") and len(p) > 1:
            for kv in p[1:].split(","):
                k, _, v = kv.partition("=")
                if k:
                    tags[_opl_decode(k)] = _opl_decode(v)
        elif p.startswith("M") and len(p) > 1:
            for m in p[1:].split(","):
                ref = m.partition("@")[0]
                if ref.startswith("w") and ref[1:].isdigit():
                    ways.append(int(ref[1:]))
    return rid, tags, ways


def load_opl(opl_path, ways_path, tol_m, stats):
    """Yields (id, tags, [way point lists]) for the relations in an OPL file, streaming the ways once."""
    rels = []
    wanted = set()
    with open(opl_path, encoding="utf-8") as f:
        for line in f:
            r = parse_opl_relation(line)
            if r is None:
                continue
            stats["relations_seen"] += 1
            if classify(r[1]) is None:
                continue
            rels.append(r)
            wanted.update(r[2])
    geoms = {}
    with open(ways_path, encoding="utf-8") as f:
        for line in f:
            line = line.lstrip("\x1e").strip()
            if not line:
                continue
            ft = json.loads(line)
            fid = str(ft.get("id") or "")
            if not fid.startswith("w") or not fid[1:].isdigit() or int(fid[1:]) not in wanted:
                continue
            g = ft.get("geometry") or {}
            if g.get("type") != "LineString":
                continue
            pts = [(c[1], c[0]) for c in g.get("coordinates", []) if len(c) >= 2 and valid_pos(c[1], c[0])]
            if len(pts) >= 2:
                geoms[int(fid[1:])] = simplify(pts, tol_m)
    for rid, tags, way_ids in rels:
        yield rid, tags, [geoms[w] for w in way_ids if w in geoms]


def load_overpass(json_paths):
    for p in json_paths:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
        for e in data.get("elements", []):
            if e.get("type") != "relation":
                continue
            ways = []
            for m in e.get("members", []):
                if m.get("type") != "way":
                    continue
                pts = [(g["lat"], g["lon"]) for g in m.get("geometry") or [] if g and valid_pos(g.get("lat"), g.get("lon"))]
                if len(pts) >= 2:
                    ways.append(pts)
            yield e.get("id"), e.get("tags") or {}, ways


def make_records(relations, tol_m, stats):
    seen, records = set(), []
    for rid, tags, ways in relations:
        stats["relations"] += 1
        c = classify(tags)
        if c is None:
            stats["dropped_not_wanted"] += 1
            continue
        if rid in seen:
            stats["duplicates"] += 1
            continue
        seen.add(rid)
        segs = []
        for s in chain(ways):
            s = simplify(s, tol_m)
            if len(s) >= 2:
                segs.extend(split_long(s))
        if not segs:
            stats["dropped_no_geometry"] += 1
            continue
        tagged = parse_distance_m(tags.get("distance"))
        records.append(dict(
            kind=c[0], level=c[1], flags=FLAG_LENGTH_TAGGED if tagged else 0,
            length_m=tagged or int(round(path_length_m(segs))),
            name=title_of(tags), ref=clean(tags.get("ref"), 40), operator=clean(tags.get("operator")),
            segments=segs,
        ))
    # Stable order, higher levels first: the app draws and trims in this order.
    records.sort(key=lambda r: (-r["level"], r["kind"], r["ref"], r["name"]))
    return records


# ---------------------------------------------------------------- output

def e6(v):
    return int(round(v * 1_000_000))


def _varint(out, v):
    v = (v << 1) ^ (v >> 63)  # zigzag
    while v > 0x7F:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


def _utf(out, s):
    b = (s or "").encode("utf-8")[:60000]
    while True:
        try:
            b.decode("utf-8")
            break
        except UnicodeDecodeError:
            b = b[:-1]
    out.extend(struct.pack(">H", len(b)) + b)


def encode_body(records):
    """Per route: u8 kind, u8 level, u8 flags, u32 length_m, utf name, utf ref, utf operator, u16 n_segments,
    per segment: varint n_points, then zigzag varints: lat_e6, lon_e6 of the first point, then deltas of both."""
    out = bytearray()
    for r in records:
        out.extend(struct.pack(">BBBI", r["kind"], r["level"], r["flags"], r["length_m"]))
        _utf(out, r["name"])
        _utf(out, r["ref"])
        _utf(out, r["operator"])
        out.extend(struct.pack(">H", len(r["segments"])))
        for s in r["segments"]:
            _varint(out, len(s))
            plat = plon = 0
            for lat, lon in s:
                a, b = e6(lat), e6(lon)
                _varint(out, a - plat)
                _varint(out, b - plon)
                plat, plon = a, b
    return bytes(out)


def write_file(path, generated_epoch, records):
    """Layout (big endian): u32 magic, u16 version, i64 generated, u32 n_routes, u32 raw_length, deflate(body), u32 crc32 of
    everything before it. The body is `encode_body`."""
    body = encode_body(records)
    out = bytearray(struct.pack(">IHqII", MAGIC, VERSION, generated_epoch, len(records), len(body)))
    out.extend(zlib.compress(body, 9))
    out.extend(struct.pack(">I", zlib.crc32(bytes(out)) & 0xFFFFFFFF))
    with open(path, "wb") as f:
        f.write(out)
    return len(out), len(body)


def _read_varint(buf, pos):
    shift = v = 0
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if not b & 0x80:
            return (v >> 1) ^ -(v & 1), pos
        shift += 7


def read_file(path):
    """Reference reader (used by the tests and by `--dump`): list of dicts like the ones `make_records` builds."""
    with open(path, "rb") as f:
        data = f.read()
    if zlib.crc32(data[:-4]) & 0xFFFFFFFF != struct.unpack(">I", data[-4:])[0]:
        raise ValueError("checksum mismatch")
    magic, version, generated, n, raw = struct.unpack(">IHqII", data[:22])
    if magic != MAGIC or version != VERSION:
        raise ValueError("not a routes file")
    body = zlib.decompress(data[22:-4])
    if len(body) != raw:
        raise ValueError("bad length")
    pos, out = 0, []
    for _ in range(n):
        kind, level, flags, length = struct.unpack_from(">BBBI", body, pos)
        pos += 7
        texts = []
        for _t in range(3):
            (ln,) = struct.unpack_from(">H", body, pos)
            texts.append(body[pos + 2:pos + 2 + ln].decode("utf-8"))
            pos += 2 + ln
        (ns,) = struct.unpack_from(">H", body, pos)
        pos += 2
        segs = []
        for _s in range(ns):
            cnt, pos = _read_varint(body, pos)
            lat = lon = 0
            pts = []
            for _p in range(cnt):
                d, pos = _read_varint(body, pos)
                d2, pos = _read_varint(body, pos)
                lat, lon = lat + d, lon + d2
                pts.append((lat / 1e6, lon / 1e6))
            segs.append(pts)
        out.append(dict(kind=kind, level=level, flags=flags, length_m=length, name=texts[0], ref=texts[1],
                        operator=texts[2], segments=segs))
    if pos != len(body):
        raise ValueError("trailing bytes")
    return out


def build(args, tol_m=None):
    tol_m = DEFAULT_TOLERANCE_M if tol_m is None else tol_m
    stats = dict(relations_seen=0, relations=0, duplicates=0, dropped_not_wanted=0, dropped_no_geometry=0)

    def source():
        if args.relations_opl:
            yield from load_opl(args.relations_opl, args.ways_geojsonseq, tol_m, stats)
        if args.osm_json:
            yield from load_overpass(args.osm_json)

    records = make_records(source(), tol_m, stats)
    stats["routes"] = len(records)
    for name, k in (("hiking", KIND_HIKING), ("bicycle", KIND_BICYCLE), ("mtb", KIND_MTB)):
        stats[f"kind_{name}"] = sum(1 for r in records if r["kind"] == k)
    for name, lv in (("local", 0), ("regional", 1), ("national", 2), ("international", 3)):
        stats[f"level_{name}"] = sum(1 for r in records if r["level"] == lv)
    stats["segments"] = sum(len(r["segments"]) for r in records)
    stats["points"] = sum(len(s) for r in records for s in r["segments"])
    stats["with_name"] = sum(1 for r in records if r["name"])
    stats["with_ref"] = sum(1 for r in records if r["ref"])
    stats["with_tagged_length"] = sum(1 for r in records if r["flags"] & FLAG_LENGTH_TAGGED)
    return records, stats


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--relations-opl")
    ap.add_argument("--ways-geojsonseq")
    ap.add_argument("--osm-json", nargs="*")
    ap.add_argument("--tolerance", type=float, default=DEFAULT_TOLERANCE_M, help="simplification tolerance in metres")
    ap.add_argument("--generated", help="ISO 8601 UTC time of the build (default: now)")
    ap.add_argument("-o", "--output", required=True)
    ap.add_argument("--summary")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    if bool(args.relations_opl) != bool(args.ways_geojsonseq):
        ap.error("--relations-opl and --ways-geojsonseq go together")
    if not (args.relations_opl or args.osm_json):
        ap.error("at least one source is required")
    gen = (datetime.datetime.fromisoformat(args.generated.replace("Z", "+00:00")) if args.generated
           else datetime.datetime.now(datetime.timezone.utc))
    records, stats = build(args, args.tolerance)
    stats["bytes"], stats["raw_bytes"] = write_file(args.output, int(gen.timestamp()), records)
    stats["generated"] = gen.strftime("%Y-%m-%dT%H:%M:%SZ")
    if args.summary:
        with open(args.summary, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=2, sort_keys=True)
    if not args.quiet:
        print(json.dumps(stats, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
