#!/usr/bin/env python3
"""Builds the small EV-charging-station data file (`chargers-es.bin`) that ships with the weekly data release.

Inputs (at least one; nothing is downloaded by this script, the workflow does that):

  --osm-geojsonseq FILE ...  `osmium export -f geojsonseq` of `nw/amenity=charging_station` (one GeoJSON feature per line).
                             Nodes are Points; ways come out as Polygon/LineString and are reduced to the centre of their
                             bounding box. `n/amenity=charging_station` (nodes only) works too.
  --osm-json FILE ...        Overpass JSON (`node[...]` and `way[...]` with `out center tags;`), for small test samples.

Output: `chargers-es.bin`, a compact big-endian binary (format in docs/phase2/ev-chargers-data.md, read by `:core-chargers`),
plus an optional JSON summary (`--summary`) with the counts for the release notes.

What goes in: OpenStreetMap features tagged `amenity=charging_station` (ODbL), with the tags that matter to a driver:
operator, network (or brand), name, capacity, `socket:*` (type, count, `:output` in kW), fee, access, authentication:*,
opening_hours. What is dropped: lifecycle prefixes (`disused:`, `construction:`, `abandoned:`, `proposed:`, `razed:`,
`was:`), bicycle-only stations, stations with `motorcar=no` and invalid positions.

Example (no network):
  scripts/build-chargers.py --osm-geojsonseq chargers.geojsonseq --generated 2026-10-07T12:00:00Z -o chargers-es.bin --summary summary.json
"""
import argparse
import datetime
import json
import re
import struct
import sys
import zlib

MAGIC = 0x554D4556  # "UMEV"
VERSION = 1
SRC_OSM = 1

# Socket type codes (stable: the app reads them).
SOCKET_TYPE2 = 1
SOCKET_CCS = 2
SOCKET_CHADEMO = 3
SOCKET_SCHUKO = 4
SOCKET_TYPE1 = 5
SOCKET_TESLA = 6
SOCKET_OTHER = 7

# OSM `socket:<key>` -> code. Any other `socket:<key>` (typee, cee_blue...) is OTHER; `:output`, `:current`... suffixes are not sockets.
SOCKET_KEYS = {
    "type2": SOCKET_TYPE2, "type2_cable": SOCKET_TYPE2,
    "type2_combo": SOCKET_CCS, "type1_combo": SOCKET_CCS,
    "chademo": SOCKET_CHADEMO,
    "schuko": SOCKET_SCHUKO,
    "type1": SOCKET_TYPE1,
    "tesla_supercharger": SOCKET_TESLA, "tesla_destination": SOCKET_TESLA, "tesla_standard": SOCKET_TESLA,
}
# Sockets that prove a station is for cars (a lone Schuko next to `bicycle=yes` is an e-bike charger).
CAR_SOCKET_KEYS = tuple(k for k in SOCKET_KEYS if k != "schuko")
SOCKET_SUFFIXES = ("output", "current", "voltage", "frequency", "ampere")

FEE_UNKNOWN, FEE_FREE, FEE_PAID = 0, 1, 2
ACCESS_UNKNOWN, ACCESS_PUBLIC, ACCESS_CUSTOMERS, ACCESS_PRIVATE = 0, 1, 2, 3
AUTH_NONE, AUTH_APP, AUTH_CARD, AUTH_NFC = 1, 2, 4, 8

LIFECYCLE_PREFIXES = ("disused", "construction", "abandoned", "proposed", "razed", "was")
MAX_TEXT = 80
MAX_HOURS = 255
MAX_SOCKETS = 15
MIN_KW, MAX_KW = 0.1, 1000.0


def valid_pos(lat, lon):
    return (isinstance(lat, (int, float)) and isinstance(lon, (int, float))
            and -90 <= lat <= 90 and -180 <= lon <= 180 and not (lat == 0 and lon == 0))


def is_live(tags):
    if tags.get("amenity") != "charging_station":
        return False
    for k in tags:
        if k.split(":")[0] in LIFECYCLE_PREFIXES:
            return False
    return True


def is_for_cars(tags):
    if tags.get("motorcar", "").lower() == "no":
        return False
    if tags.get("bicycle", "").lower() == "yes" and tags.get("motorcar", "").lower() != "yes":
        # An e-bike charger. Keep it only when it also declares a car socket.
        return any(k.startswith("socket:") and k.split(":")[1] in CAR_SOCKET_KEYS for k in tags)
    return True


def parse_count(v):
    v = (v or "").strip().lower()
    if v in ("yes", "true"):
        return 0  # present, number unknown
    if re.fullmatch(r"\d{1,3}", v):
        n = int(v)
        return n if 1 <= n <= 99 else None
    return None  # "no", "0", free text


def parse_power_dkw(v):
    """Largest value of a `socket:*:output` string in tenths of a kW, 0 when unknown. "22 kW", "22", "22kW;43kW", "7400 W"."""
    best = 0.0
    for part in (v or "").replace(",", ".").split(";"):
        m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(kw|w)?\s*", part.lower())
        if not m:
            continue
        kw = float(m.group(1)) / (1000.0 if m.group(2) == "w" else 1.0)
        if MIN_KW <= kw <= MAX_KW:
            best = max(best, kw)
    return int(round(best * 10))


def parse_sockets(tags):
    """List of (type, count, power_dkw), one per type; same-type tags (type2 and type2_cable) are merged."""
    merged = {}
    for k, v in tags.items():
        parts = k.split(":")
        if len(parts) != 2 or parts[0] != "socket" or parts[1] in SOCKET_SUFFIXES:
            continue
        count = parse_count(v)
        if count is None:
            continue
        code = SOCKET_KEYS.get(parts[1], SOCKET_OTHER)
        power = parse_power_dkw(tags.get(f"socket:{parts[1]}:output"))
        old = merged.get(code)
        if old is None:
            merged[code] = [count, power]
        else:
            old[0] = old[0] + count if old[0] and count else max(old[0], count)
            old[1] = max(old[1], power)
    return [(code, c, p) for code, (c, p) in sorted(merged.items())][:MAX_SOCKETS]


def parse_fee(tags):
    v = tags.get("fee", "").lower()
    return FEE_PAID if v == "yes" else FEE_FREE if v == "no" else FEE_UNKNOWN


def parse_access(tags):
    v = tags.get("access", "").lower()
    if v in ("yes", "permissive", "public"):
        return ACCESS_PUBLIC
    if v in ("customers", "delivery"):
        return ACCESS_CUSTOMERS
    if v in ("private", "no", "members", "permit"):
        return ACCESS_PRIVATE
    return ACCESS_UNKNOWN


def parse_auth(tags):
    mask = 0
    for key, bit in (("none", AUTH_NONE), ("app", AUTH_APP), ("membership_card", AUTH_CARD), ("nfc", AUTH_NFC)):
        if tags.get(f"authentication:{key}", "").lower() == "yes":
            mask |= bit
    return mask


def parse_capacity(tags):
    v = tags.get("capacity", "").strip()
    return int(v) if re.fullmatch(r"\d{1,4}", v) and 1 <= int(v) <= 9999 else 0


def clean(s, limit=MAX_TEXT):
    s = re.sub(r"\s+", " ", s or "").strip()
    return s[:limit]


def make_record(lat, lon, tags):
    network = tags.get("network") or tags.get("brand") or ""
    return dict(
        lat=lat, lon=lon,
        fee=parse_fee(tags), access=parse_access(tags), auth=parse_auth(tags), capacity=parse_capacity(tags),
        sockets=parse_sockets(tags),
        operator=clean(tags.get("operator")), network=clean(network), name=clean(tags.get("name")),
        hours=clean(tags.get("opening_hours"), MAX_HOURS),
    )


def flatten(coords):
    if coords and isinstance(coords[0], (int, float)):
        yield coords
    else:
        for c in coords or []:
            yield from flatten(c)


def geometry_center(g):
    """(lat, lon) of a Point, or of the bounding box of any other geometry; None when unusable."""
    if not g:
        return None
    pts = [c for c in flatten(g.get("coordinates")) if len(c) >= 2]
    if not pts:
        return None
    if g.get("type") == "Point":
        return pts[0][1], pts[0][0]
    lons = [c[0] for c in pts]
    lats = [c[1] for c in pts]
    return (min(lats) + max(lats)) / 2.0, (min(lons) + max(lons)) / 2.0


def load_osm(json_files, geojsonseq_files):
    """Returns (records, stats). Duplicates (same feature id, or same position when there is no id) are kept once."""
    seen, records = set(), []
    stats = dict(features=0, duplicates=0, dropped_not_live=0, dropped_not_for_cars=0, dropped_bad_position=0)

    def add(key, lat, lon, tags):
        stats["features"] += 1
        if key in seen:
            stats["duplicates"] += 1
            return
        seen.add(key)
        if not is_live(tags):
            stats["dropped_not_live"] += 1
        elif not is_for_cars(tags):
            stats["dropped_not_for_cars"] += 1
        elif not valid_pos(lat, lon):
            stats["dropped_bad_position"] += 1
        else:
            records.append(make_record(lat, lon, tags))

    for p in json_files:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
        for e in data.get("elements", []):
            if e.get("type") not in ("node", "way", "relation"):
                continue
            if e["type"] == "node":
                pos = (e.get("lat"), e.get("lon"))
            else:
                pos = ((e.get("center") or {}).get("lat"), (e.get("center") or {}).get("lon"))
            add(f'{e["type"]}/{e.get("id")}', pos[0], pos[1], e.get("tags") or {})
    for p in geojsonseq_files:
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.lstrip("\x1e").strip()
                if not line:
                    continue
                ft = json.loads(line)
                tags = ft.get("properties") or {}
                c = geometry_center(ft.get("geometry"))
                if c is None:
                    stats["features"] += 1
                    stats["dropped_bad_position"] += 1
                    continue
                # `osmium export` adds no feature id unless asked (--add-unique-id): key on the position then, never on an
                # empty id (every feature would look like a duplicate of the first one).
                key = str(ft.get("id") or tags.get("@id") or f"{c[0]:.7f},{c[1]:.7f}")
                add(key, c[0], c[1], tags)
    return records, stats


def write_file(path, generated_epoch, records):
    """Layout (big endian). See docs/phase2/ev-chargers-data.md.

    u32 magic, u16 version, i64 generated (epoch seconds), u8 source_flags, u32 n
    record: i32 lat_e6, i32 lon_e6, u8 fee, u8 access, u8 auth, u16 capacity, u8 n_sockets,
            n_sockets x (u8 type, u8 count, u16 power_dkw), utf operator, utf network, utf name, utf hours
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

    out.extend(struct.pack(">IHqBI", MAGIC, VERSION, generated_epoch, SRC_OSM if records else 0, len(records)))
    for r in records:
        out.extend(struct.pack(">iiBBBHB", e6(r["lat"]), e6(r["lon"]), r["fee"], r["access"], r["auth"], r["capacity"], len(r["sockets"])))
        for t, c, p in r["sockets"]:
            out.extend(struct.pack(">BBH", t, c, p))
        utf(r["operator"])
        utf(r["network"])
        utf(r["name"])
        utf(r["hours"])
    out.extend(struct.pack(">I", zlib.crc32(bytes(out)) & 0xFFFFFFFF))
    with open(path, "wb") as f:
        f.write(out)
    return len(out)


def build(args):
    records, stats = load_osm(args.osm_json or [], args.osm_geojsonseq or [])
    stats["chargers"] = len(records)
    stats["with_sockets"] = sum(1 for r in records if r["sockets"])
    stats["with_power"] = sum(1 for r in records if any(p for _t, _c, p in r["sockets"]))
    stats["with_operator"] = sum(1 for r in records if r["operator"])
    stats["with_fee_info"] = sum(1 for r in records if r["fee"])
    stats["with_hours"] = sum(1 for r in records if r["hours"])
    stats["restricted_access"] = sum(1 for r in records if r["access"] in (ACCESS_CUSTOMERS, ACCESS_PRIVATE))
    return records, stats


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--osm-json", nargs="*")
    ap.add_argument("--osm-geojsonseq", nargs="*")
    ap.add_argument("--generated", help="ISO 8601 UTC time of the build (default: now)")
    ap.add_argument("-o", "--output", required=True)
    ap.add_argument("--summary")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    if not (args.osm_json or args.osm_geojsonseq):
        ap.error("at least one source is required")
    gen = (datetime.datetime.fromisoformat(args.generated.replace("Z", "+00:00")) if args.generated
           else datetime.datetime.now(datetime.timezone.utc))
    records, stats = build(args)
    stats["bytes"] = write_file(args.output, int(gen.timestamp()), records)
    stats["generated"] = gen.strftime("%Y-%m-%dT%H:%M:%SZ")
    if args.summary:
        with open(args.summary, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=2, sort_keys=True)
    if not args.quiet:
        print(json.dumps(stats, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
