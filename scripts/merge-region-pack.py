#!/usr/bin/env python3
"""Adds a pack of extra regions (for example the provinces of China) to an existing catalog.

The weekly pipeline publishes the Spain regions. A pack is built by hand for another part of the world with the same tools
(`fetch-comaps-mwm.py`, `split-pmtiles.py`) and published as its own release. The catalog of that release is the catalog of
the latest weekly release plus the download entries of the pack: this script writes it. The hierarchy of the catalog already
lists every CoMaps region, so nothing is added to it; each region whose two files are in `--pack-dir` gets its `assets`
(`<id>.pmtiles` and `<id>.mwm`, `<id>` being the catalog id) with the URL, size and SHA-256 of each file.

Usage:
  scripts/merge-region-pack.py --base-catalog catalog.json --pack-dir out/ \\
      --url-base https://github.com/OWNER/REPO/releases/download/TAG/ -o catalog-with-pack.json

A region that already has assets in the base catalog is left alone unless `--replace` is given. Every other block of the
catalog (base files, cameras, transit...) is copied unchanged. Prints one line per region added.
"""
import argparse
import datetime
import hashlib
import json
import os
import sys

SHA256_CHUNK = 1 << 20


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(SHA256_CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def merge(catalog, pack_dir, url_base, replace=False, catalog_version=None, log=lambda m: None):
    """Returns a new catalog with `assets` filled for every region that has both files in [pack_dir]."""
    if not url_base.endswith("/"):
        url_base += "/"
    out = json.loads(json.dumps(catalog))
    added = 0
    for region in out["regions"]:
        rid = region["id"]
        if "assets" in region and not replace:
            continue
        pm = os.path.join(pack_dir, rid + ".pmtiles")
        mwm = os.path.join(pack_dir, rid + ".mwm")
        if not (os.path.isfile(pm) and os.path.isfile(mwm)):
            continue
        region["assets"] = {
            "render": {"url": url_base + rid + ".pmtiles", "size": os.path.getsize(pm), "sha256": sha256_of(pm), "file": rid + ".pmtiles"},
            "search": {"url": url_base + rid + ".mwm", "size": os.path.getsize(mwm), "sha256": sha256_of(mwm), "file": rid + ".mwm"},
        }
        added += 1
        log(f"added {rid}")
    out["catalogVersion"] = catalog_version or (datetime.date.today().isoformat() + "-pack")
    return out, added


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-catalog", required=True)
    ap.add_argument("--pack-dir", required=True)
    ap.add_argument("--url-base", required=True, help="where the release assets will be (ends with the tag, a slash is added)")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--replace", action="store_true", help="also replace the assets of regions that already have them")
    ap.add_argument("--catalog-version")
    args = ap.parse_args(argv)
    with open(args.base_catalog, encoding="utf-8") as f:
        catalog = json.load(f)
    merged, added = merge(catalog, args.pack_dir, args.url_base, args.replace, args.catalog_version, log=print)
    if not added:
        sys.exit("no region of the catalog has both files in --pack-dir")
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, separators=(",", ":"))
    total = sum(1 for r in merged["regions"] if "assets" in r)
    print(f"{added} regions added; the catalog now has {total} downloadable regions")


if __name__ == "__main__":
    main()
