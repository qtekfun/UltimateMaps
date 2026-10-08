#!/usr/bin/env python3
"""Generates the region catalog (JSON schema 1, see docs/phase1/regions.md) by joining:

  * the CoMaps hierarchy and .mwm files (`third_party/comaps/data/countries.txt`), and
  * the project's own PMTiles extracts (one `<id>.pmtiles` file per region).

Real fields of `countries.txt` (verified against the version 261004 file):
  root:  {"id": "Countries", "v": 261004, "map_series": "2026.06.28", "g": [...]}
  node:  {"id": "Spain_Community of Madrid", "s": <.mwm bytes>, "sha1_base64": "...",
          "old": [...], "affiliations": [...], "country_name_synonyms": {...}}
  group: {"id": "Spain", "g": [children...]}        (groups carry neither "s" nor "sha1_base64")
`v` is the data version (YYMMDD) and applies to the whole tree. The CoMaps SHA-1 is NOT used: the SHA-256 is
computed from the real files (the project's own catalog is the root of trust for the hash).

A leaf is downloadable only if BOTH files (.mwm and .pmtiles) exist with their SHA-256; the remaining nodes
are still emitted (full hierarchy) but without `assets`, and the app shows them as "not available".

CoMaps ids contain spaces and are not valid as a region id (`[A-Za-z0-9_.-]`): the own id is the
original in lowercase ASCII with the gaps replaced by `-` (`spain_community-of-madrid`); the original goes in
`comapsId` (an extra field the parser ignores) so the engine requests the right .mwm.

Examples (no network):
  scripts/gen-region-catalog.py --mwm-dir ~/mirror/mwm --pmtiles-dir ~/mirror/pmtiles \\
      --mwm-base https://maps.example.org/mwm/ --pmtiles-base https://maps.example.org/pmtiles/ -o catalog.json
  scripts/gen-region-catalog.py -o hierarchy.json          # hierarchy only, nothing downloadable

`--base-dir` adds the `base` block with `World.mwm` and `WorldCoasts.mwm` (they are not a region, but the core requires them
next to each region; the app downloads them once per version). Without it, the catalog has no `base`.

Each region may carry `names` ({"es": "Comunidad de Madrid"}), the optional per-language display names from CoMaps'
`data/countries-strings` (`--names-langs`, default `es`; `name` stays the English one). Old apps ignore it; the app shows
the one that matches the user's language and falls back to `name`. `World` and `WorldCoasts` get no entry (see `--base-dir`).

`--cameras-file` (with `--cameras-base`) adds the optional `cameras` block for the speed-camera file; without it the catalog is
unchanged and the app works without camera data.

`--chargers-file` (with `--chargers-base`) adds the optional `chargers` block for the EV-charging-station file
(`chargers-es.bin`, built by `scripts/build-chargers.py`); without it the catalog is unchanged and the app works without it.

`--zbe-file` (with `--zbe-base`) adds the optional `zbe` block for the low-emission-zone polygon file (`zbe-es.bin`, built by
`scripts/build-zbe.py`); without it the catalog is unchanged and the app works without it.

`--transit-file` (repeatable, with `--transit-base`) adds the optional `transit` array, one entry per city index
`transit-<id>.umti` built by `scripts/build-transit.sh`. Each file needs its sidecar `transit-<id>.json` (written by the same
tool: id, city, timezone, bounds, validFrom, validTo, attribution). Without the option the catalog is unchanged and the app
works without transit data. See docs/phase2/transit.md.

With network, only on explicit request and with a size cap: --fetch-mwm <comapsId> downloads THAT .mwm
(by default <= 20 MB) to --mwm-dir to compute its SHA-256.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request

SHA256_CHUNK = 1 << 20
DEFAULT_MWM_BASE = "https://mapgen-fi-1.comaps.app/maps/{series}/{v}/"  # documented structure, not verified over the network


def slug(comaps_id):
    s = unicodedata.normalize("NFKD", comaps_id).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9_.]+", "-", s.lower()).strip("-")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(SHA256_CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def display_name(comaps_id, parent_id):
    rest = comaps_id[len(parent_id) + 1:] if parent_id and comaps_id.startswith(parent_id + "_") else comaps_id
    return rest.replace("_", " - ")


# World.mwm and WorldCoasts.mwm are listed in countries.txt next to the countries, but they are base files (the `base` block),
# not regions: they never get a catalog entry.
BASE_IDS = ("World", "WorldCoasts")
NAME_LANG_RE = re.compile(r"^[a-z]{2,3}(-[A-Za-z]{2,4})?$")
DASHES = re.compile(r"\s+[\u2013\u2014]\s+")


def load_names(strings_dir, langs, log=lambda m: None):
    """{lang: {comapsId: localized name}} from CoMaps' `data/countries-strings/<lang>.json/localize.json`.

    Names of sub-regions in those files join the parts with an en/em dash ("Andalucia - Granada"); the catalog (and the
    app's search) use " - ", so the dashes are normalized. A missing language file is skipped with a warning."""
    out = {}
    for lang in langs:
        if not NAME_LANG_RE.match(lang):
            raise SystemExit(f"invalid language for --names-langs: {lang}")
        path = os.path.join(strings_dir, lang + ".json", "localize.json")
        if not os.path.isfile(path):
            log(f"WARNING {path} is missing; no `{lang}` names")
            continue
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        out[lang] = {k: DASHES.sub(" - ", v.strip()) for k, v in raw.items()
                     if isinstance(v, str) and v.strip() and not k.endswith((" Short", " Description"))}
    return out


def flatten(root):
    """Pre-order: [(node, parent_comaps_id or None)], without the root node."""
    out = []

    def walk(node, parent):
        out.append((node, parent))
        for child in node.get("g", []):
            walk(child, node["id"])

    for child in root.get("g", []):
        walk(child, None)
    return out


def fetch_mwm(base_url, comaps_id, expected_size, dest_dir, max_bytes):
    if expected_size > max_bytes:
        raise SystemExit(f"{comaps_id}: {expected_size} bytes exceed the download cap ({max_bytes})")
    url = base_url + urllib.parse.quote(comaps_id) + ".mwm"
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, comaps_id + ".mwm")
    with urllib.request.urlopen(url, timeout=60) as r, open(dest, "wb") as f:  # noqa: S310 (https, on request)
        got = 0
        while True:
            b = r.read(SHA256_CHUNK)
            if not b:
                break
            got += len(b)
            if got > max_bytes:
                raise SystemExit(f"{comaps_id}: the response exceeds the download cap")
            f.write(b)
    return dest


BASE_FILES = (("world", "World.mwm"), ("worldCoasts", "WorldCoasts.mwm"))


def build_base(version, base_dir, base_url, log=lambda m: None):
    """`base` block (World.mwm and WorldCoasts.mwm, which the core requires next to each region) or None if either is missing."""
    if not base_dir:
        return None
    if not base_url:
        raise SystemExit("--base-dir needs --base-url (or --mwm-base)")
    base_url = base_url if base_url.endswith("/") else base_url + "/"
    out = {"version": version}
    for key, name in BASE_FILES:
        path = os.path.join(base_dir, name)
        if not os.path.isfile(path):
            log(f"WARNING {path} is missing; the catalog will not carry `base`")
            return None
        out[key] = {"url": base_url + name, "size": os.path.getsize(path), "sha256": sha256_of(path), "file": name}
    return out


def build_static_file(block, option, path, base, log=lambda m: None):
    """Optional small-file block (`cameras`, `chargers`): url, size, sha256, file. None when there is no file."""
    if not path:
        return None
    if not os.path.isfile(path):
        log(f"WARNING {path} is missing; the catalog will not carry `{block}`")
        return None
    if not base:
        raise SystemExit(f"--{option}-file needs --{option}-base")
    base = base if base.endswith("/") else base + "/"
    name = os.path.basename(path)
    return {"url": base + name, "size": os.path.getsize(path), "sha256": sha256_of(path), "file": name}


def build_cameras(cameras_file, cameras_base, log=lambda m: None):
    """Optional `cameras` block for `speedcams-es.bin` (see scripts/build-cameras.py) or None when there is no file."""
    return build_static_file("cameras", "cameras", cameras_file, cameras_base, log)


def build_chargers(chargers_file, chargers_base, log=lambda m: None):
    """Optional `chargers` block for `chargers-es.bin` (see scripts/build-chargers.py) or None when there is no file."""
    return build_static_file("chargers", "chargers", chargers_file, chargers_base, log)


def build_zbe(zbe_file, zbe_base, log=lambda m: None):
    """Optional `zbe` block for `zbe-es.bin` (see scripts/build-zbe.py) or None when there is no file."""
    return build_static_file("zbe", "zbe", zbe_file, zbe_base, log)


TRANSIT_META_KEYS = ("id", "city", "timezone", "validFrom", "validTo", "attribution")


def build_transit(transit_files, transit_base, log=lambda m: None):
    """Optional `transit` array: one entry per `.umti` that has its `.json` sidecar (see scripts/build-transit.sh)."""
    out = []
    if not transit_files:
        return out
    if not transit_base:
        raise SystemExit("--transit-file needs --transit-base")
    transit_base = transit_base if transit_base.endswith("/") else transit_base + "/"
    for path in transit_files:
        meta_path = os.path.splitext(path)[0] + ".json"
        if not os.path.isfile(path) or not os.path.isfile(meta_path):
            log(f"WARNING {path} or its sidecar {meta_path} is missing; the catalog will not carry it")
            continue
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        missing = [k for k in TRANSIT_META_KEYS if not meta.get(k)]
        if missing:
            raise SystemExit(f"{meta_path}: missing {', '.join(missing)}")
        name = os.path.basename(path)
        entry = {"id": meta["id"], "city": meta["city"], "url": transit_base + name, "size": os.path.getsize(path),
                 "sha256": sha256_of(path), "file": name, "validFrom": meta["validFrom"], "validTo": meta["validTo"],
                 "timezone": meta["timezone"]}
        if meta.get("bounds"):
            entry["bounds"] = meta["bounds"]
        entry["attribution"] = meta["attribution"]
        out.append(entry)
    return out


def build(countries, mwm_dir=None, pmtiles_dir=None, mwm_base=None, pmtiles_base=None, catalog_version=None,
          fetch=(), max_download_bytes=20 << 20, log=lambda m: None, mwm_url_by_slug=False,
          base_dir=None, base_url=None, cameras_file=None, cameras_base=None,
          transit_files=(), transit_base=None, names=None, chargers_file=None, chargers_base=None,
          zbe_file=None, zbe_base=None):
    version = str(countries["v"])
    series = countries.get("map_series", "")
    mwm_base = (mwm_base or DEFAULT_MWM_BASE.format(series=series, v=version))
    mwm_base = mwm_base if mwm_base.endswith("/") else mwm_base + "/"
    if pmtiles_base and not pmtiles_base.endswith("/"):
        pmtiles_base += "/"
    nodes = flatten(countries)
    slugs = {}
    seen = set()
    for node, _ in nodes:
        s = slug(node["id"])
        if node["id"] not in slugs and s in slugs.values():
            raise SystemExit(f"id collision after normalizing: {node['id']} -> {s}")
        slugs[node["id"]] = s
    regions = []
    for node, parent in nodes:
        cid = node["id"]
        if cid in BASE_IDS and parent is None:
            log(f"{cid}: base file (the `base` block), not a region; skipped")
            continue
        if cid in seen:  # countries.txt repeats some nodes under two parents (e.g. Campo de Hielo Sur)
            log(f"WARNING {cid}: repeated under another parent; the first occurrence is kept")
            continue
        seen.add(cid)
        rid = slugs[cid]
        region = {
            "id": rid,
            "comapsId": cid,
            "name": display_name(cid, parent),
            "parent": slugs[parent] if parent else None,
            "version": version,
        }
        if names:
            local = {lang: table[cid] for lang, table in sorted(names.items())
                     if cid in table and table[cid] != region["name"]}
            if local:
                region["names"] = local
        is_leaf = "g" not in node
        if is_leaf and "s" in node:
            mwm = os.path.join(mwm_dir, cid + ".mwm") if mwm_dir else None
            if cid in fetch:
                if not mwm_dir:
                    raise SystemExit("--fetch-mwm needs --mwm-dir")
                mwm = fetch_mwm(mwm_base, cid, node["s"], mwm_dir, max_download_bytes)
            pm = os.path.join(pmtiles_dir, rid + ".pmtiles") if pmtiles_dir else None
            if mwm and pm and os.path.isfile(mwm) and os.path.isfile(pm):
                msize = os.path.getsize(mwm)
                if msize != node["s"]:
                    log(f"WARNING {cid}: the .mwm is {msize} bytes and countries.txt says {node['s']}; skipped")
                else:
                    if not pmtiles_base:
                        raise SystemExit("--pmtiles-dir needs --pmtiles-base")
                    region["assets"] = {
                        "render": {"url": pmtiles_base + rid + ".pmtiles", "size": os.path.getsize(pm),
                                   "sha256": sha256_of(pm), "file": rid + ".pmtiles"},
                        "search": {"url": mwm_base + (rid if mwm_url_by_slug else urllib.parse.quote(cid)) + ".mwm", "size": msize,
                                   "sha256": sha256_of(mwm), "file": rid + ".mwm"},
                    }
        regions.append(region)
    cat = {
        "schema": 1,
        "catalogVersion": catalog_version or datetime.date.today().isoformat(),
    }
    base = build_base(version, base_dir, base_url or mwm_base, log)
    if base:
        cat["base"] = base
    cams = build_cameras(cameras_file, cameras_base, log)
    if cams:
        cat["cameras"] = cams
    chargers = build_chargers(chargers_file, chargers_base, log)
    if chargers:
        cat["chargers"] = chargers
    zbe = build_zbe(zbe_file, zbe_base, log)
    if zbe:
        cat["zbe"] = zbe
    transit = build_transit(transit_files, transit_base, log)
    if transit:
        cat["transit"] = transit
    cat["regions"] = regions
    return cat


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--countries", default=os.path.join(os.path.dirname(__file__), "..", "third_party", "comaps",
                                                         "data", "countries.txt"))
    ap.add_argument("--mwm-dir", help="directory with <comapsId>.mwm (its SHA-256 is computed)")
    ap.add_argument("--pmtiles-dir", help="directory with <own id>.pmtiles")
    ap.add_argument("--mwm-base", help="base URL of the .mwm files (by default the CoMaps structure)")
    ap.add_argument("--pmtiles-base", help="base URL of the .pmtiles files (required with --pmtiles-dir)")
    ap.add_argument("--catalog-version", help="by default, today's date (YYYY-MM-DD)")
    ap.add_argument("--fetch-mwm", action="append", default=[], metavar="COMAPS_ID",
                    help="downloads that .mwm to --mwm-dir (network; only on request)")
    ap.add_argument("--max-download-mb", type=int, default=20)
    ap.add_argument("--base-dir", help="directory with World.mwm and WorldCoasts.mwm (the catalog's `base` block)")
    ap.add_argument("--base-url", help="base URL of World*.mwm (by default, --mwm-base)")
    ap.add_argument("--cameras-file", help="speedcams-es.bin from scripts/build-cameras.py (adds the optional `cameras` block)")
    ap.add_argument("--cameras-base", help="base URL of the cameras file (required with --cameras-file)")
    ap.add_argument("--chargers-file", help="chargers-es.bin from scripts/build-chargers.py (adds the optional `chargers` block)")
    ap.add_argument("--chargers-base", help="base URL of the chargers file (required with --chargers-file)")
    ap.add_argument("--zbe-file", help="zbe-es.bin from scripts/build-zbe.py (adds the optional `zbe` block)")
    ap.add_argument("--zbe-base", help="base URL of the zbe file (required with --zbe-file)")
    ap.add_argument("--transit-file", action="append", default=[], help="transit-<id>.umti (repeatable; adds the optional `transit` array)")
    ap.add_argument("--transit-base", help="base URL of the transit files (required with --transit-file)")
    ap.add_argument("--names-dir", default=os.path.join(os.path.dirname(__file__), "..", "third_party", "comaps", "data",
                                                        "countries-strings"),
                    help="CoMaps countries-strings directory: adds the optional per-language `names` of each region")
    ap.add_argument("--names-langs", default="es", help="comma-separated languages for `names` (default: es; empty = none)")
    ap.add_argument("--mwm-url-by-slug", action="store_true",
                    help="the .mwm URL uses the own id (`<slug>.mwm`), e.g. on GitHub Releases, which renames spaces")
    ap.add_argument("-o", "--output", default="-")
    a = ap.parse_args(argv)
    with open(a.countries, encoding="utf-8") as f:
        countries = json.load(f)
    langs = [l.strip() for l in a.names_langs.split(",") if l.strip()]
    names = load_names(a.names_dir, langs, log=lambda m: print(m, file=sys.stderr)) if langs else None
    cat = build(countries, a.mwm_dir, a.pmtiles_dir, a.mwm_base, a.pmtiles_base, a.catalog_version,
                set(a.fetch_mwm), a.max_download_mb << 20, log=lambda m: print(m, file=sys.stderr),
                mwm_url_by_slug=a.mwm_url_by_slug, base_dir=a.base_dir, base_url=a.base_url,
                cameras_file=a.cameras_file, cameras_base=a.cameras_base,
                chargers_file=a.chargers_file, chargers_base=a.chargers_base,
                zbe_file=a.zbe_file, zbe_base=a.zbe_base,
                transit_files=a.transit_file, transit_base=a.transit_base, names=names)
    text = json.dumps(cat, indent=1, ensure_ascii=False) + "\n"
    if a.output == "-":
        sys.stdout.write(text)
    else:
        with open(a.output, "w", encoding="utf-8") as f:
            f.write(text)
    if "base" not in cat and any("assets" in r for r in cat["regions"]):
        print("WARNING: the catalog has downloadable regions but NOT the `base` block (World.mwm and WorldCoasts.mwm): "
              "the app will download them, but search will say \"no maps\". Pass --base-dir.", file=sys.stderr)
    n = len(cat["regions"])
    d = sum(1 for r in cat["regions"] if "assets" in r)
    print(f"{n} regions, {d} downloadable", file=sys.stderr)


if __name__ == "__main__":
    main()
