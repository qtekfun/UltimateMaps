#!/usr/bin/env python3
"""Builds the small bike-share station file (`bikeshare-es.bin`) that ships with the weekly data release.

Only systems whose licence was confirmed at the SOURCE page and allows redistribution with attribution are listed in
SYSTEMS below (the others, and why, are in docs/phase2/bike-share.md). Adding a system means adding a row here after
checking its licence; nothing is picked up automatically from the GBFS catalogue.

Nothing is downloaded by this script, the workflow does that:

  scripts/build-bikeshare.py --print-urls                    one line per system: "<id> <station_information url>"
  scripts/build-bikeshare.py --input-dir DIR -o bikeshare-es.bin --generated 2026-10-08T12:00:00Z

`--input-dir` holds one `<id>.json` per system: the GBFS `station_information` document, v2.x or v3.x (names are a plain
string in v2 and a list of {text, language} in v3; the Spanish name is preferred, then English, then the first one).
Virtual stations and invalid positions are dropped. A system whose file is missing, unreadable or has fewer than
MIN_STATIONS usable stations is left out with a warning (a half-broken feed must not replace good data in the app); when no
system is left the script exits with status 1 and writes NOTHING, so an empty file can never be published.

Layout (big endian; read by `:core-bikeshare`, see docs/phase2/bike-share.md):

  u32 magic "UMBK", u16 version, i64 generated (epoch seconds), u16 n_systems
  system: utf id, utf name, utf attribution, u32 n_stations,
          n_stations x (utf station_id, utf name, i32 lat_e6, i32 lon_e6, u16 capacity)
  u32 crc32 of everything before it.   `utf` = u16 length + UTF-8 bytes (Java's writeUTF for BMP text).
"""
import argparse
import datetime
import json
import os
import struct
import sys
import zlib

MAGIC = 0x554D424B  # "UMBK"
VERSION = 1
MIN_STATIONS = 20
MAX_TEXT = 120

# Confirmed 2026-10-08 (docs/phase2/bike-share.md): both publish the station data under CC BY 4.0 at the city / operator
# open-data portal; the GBFS feed is public without a key.
SYSTEMS = [
    {
        "id": "bicing",
        "name": "Bicing (Barcelona)",
        "attribution": "Bicing: Ajuntament de Barcelona, Open Data BCN, CC BY 4.0",
        "station_information": "https://barcelona.publicbikesystem.net/customer/gbfs/v3.0/station_information",
    },
    {
        "id": "bicimad",
        "name": "BiciMAD (Madrid)",
        "attribution": "BiciMAD: Empresa Municipal de Transportes de Madrid, S.A. (datos.madrid.es), CC BY 4.0",
        "station_information": "https://madrid.publicbikesystem.net/customer/gbfs/v3.0/station_information",
    },
]


def valid_pos(lat, lon):
    return (isinstance(lat, (int, float)) and isinstance(lon, (int, float)) and not isinstance(lat, bool)
            and not isinstance(lon, bool) and -90 <= lat <= 90 and -180 <= lon <= 180 and not (lat == 0 and lon == 0))


def pick_name(v):
    if isinstance(v, str):
        return v.strip()
    if isinstance(v, list):
        items = [x for x in v if isinstance(x, dict) and isinstance(x.get("text"), str) and x["text"].strip()]
        for lang in ("es", "en"):
            for x in items:
                if x.get("language") == lang:
                    return x["text"].strip()
        if items:
            return items[0]["text"].strip()
    return ""


def capacity_of(s):
    c = s.get("capacity")
    if isinstance(c, int) and not isinstance(c, bool) and c > 0:
        return min(c, 65535)
    total = 0
    for d in s.get("vehicle_docks_capacity") or []:
        if isinstance(d, dict) and isinstance(d.get("count"), int):
            total += max(0, d["count"])
    return min(total, 65535)


def parse_stations(doc):
    """Usable stations of a GBFS station_information document: list of dicts. Raises ValueError on a wrong document."""
    try:
        stations = doc["data"]["stations"]
    except (KeyError, TypeError):
        raise ValueError("no data.stations")
    if not isinstance(stations, list):
        raise ValueError("data.stations is not a list")
    out, seen = [], set()
    for s in stations:
        if not isinstance(s, dict) or s.get("is_virtual_station") is True:
            continue
        sid = s.get("station_id")
        if not isinstance(sid, (str, int)) or isinstance(sid, bool) or str(sid) == "" or str(sid) in seen:
            continue
        lat, lon = s.get("lat"), s.get("lon")
        if not valid_pos(lat, lon):
            continue
        name = pick_name(s.get("name")) or pick_name(s.get("short_name")) or str(s.get("address") or "")
        seen.add(str(sid))
        out.append({"id": str(sid), "name": name.strip(), "lat": float(lat), "lon": float(lon), "capacity": capacity_of(s)})
    return out


def _cut(text, limit):
    """`text` shortened by whole characters until its UTF-8 form fits in `limit` bytes."""
    text = text or ""
    while len(text.encode("utf-8")) > limit:
        text = text[:-1]
    return text.encode("utf-8")


def write_file(path, generated_epoch, systems):
    """systems: list of (meta dict, stations list)."""
    out = bytearray()

    def utf(s, limit=MAX_TEXT):
        b = _cut(s, limit)
        out.extend(struct.pack(">H", len(b)) + b)

    def e6(v):
        return int(round(v * 1_000_000))

    out.extend(struct.pack(">IHqH", MAGIC, VERSION, generated_epoch, len(systems)))
    for meta, stations in systems:
        utf(meta["id"])
        utf(meta["name"])
        utf(meta["attribution"], 300)
        out.extend(struct.pack(">I", len(stations)))
        for s in stations:
            utf(s["id"])
            utf(s["name"])
            out.extend(struct.pack(">iiH", e6(s["lat"]), e6(s["lon"]), s["capacity"]))
    out.extend(struct.pack(">I", zlib.crc32(bytes(out)) & 0xFFFFFFFF))
    with open(path, "wb") as f:
        f.write(out)
    return len(out)


def build(input_dir, log):
    systems, stats = [], {"systems": {}, "dropped_systems": {}}
    for meta in SYSTEMS:
        p = os.path.join(input_dir, meta["id"] + ".json")
        try:
            with open(p, encoding="utf-8") as f:
                stations = parse_stations(json.load(f))
        except (OSError, ValueError) as e:  # json.JSONDecodeError is a ValueError
            log(f"WARNING {meta['id']}: {e}; system left out")
            stats["dropped_systems"][meta["id"]] = str(e)
            continue
        if len(stations) < MIN_STATIONS:
            log(f"WARNING {meta['id']}: only {len(stations)} usable stations (< {MIN_STATIONS}); system left out")
            stats["dropped_systems"][meta["id"]] = f"only {len(stations)} stations"
            continue
        stations.sort(key=lambda s: (s["lat"], s["lon"], s["id"]))
        systems.append((meta, stations))
        stats["systems"][meta["id"]] = len(stations)
    return systems, stats


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--print-urls", action="store_true", help="print '<id> <url>' per system and exit")
    ap.add_argument("--input-dir")
    ap.add_argument("--generated", help="ISO 8601 UTC time of the build (default: now)")
    ap.add_argument("-o", "--output")
    ap.add_argument("--summary")
    args = ap.parse_args(argv)
    if args.print_urls:
        for m in SYSTEMS:
            print(m["id"], m["station_information"])
        return 0
    if not (args.input_dir and args.output):
        ap.error("--input-dir and -o are required")
    gen = (datetime.datetime.fromisoformat(args.generated.replace("Z", "+00:00")) if args.generated
           else datetime.datetime.now(datetime.timezone.utc))
    systems, stats = build(args.input_dir, lambda m: print(m, file=sys.stderr))
    if not systems:
        print("ERROR: no system has usable stations; nothing written", file=sys.stderr)
        return 1
    stats["stations"] = sum(len(s) for _m, s in systems)
    stats["bytes"] = write_file(args.output, int(gen.timestamp()), systems)
    stats["generated"] = gen.strftime("%Y-%m-%dT%H:%M:%SZ")
    if args.summary:
        with open(args.summary, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=2, sort_keys=True)
    print(json.dumps(stats, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
