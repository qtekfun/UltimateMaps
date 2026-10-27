#!/usr/bin/env python3
"""Downloads the CoMaps .mwm files of a group of regions and checks each one (size and SHA-1) against countries.txt.

The weekly pipeline does this for Spain with curl. For a pack of another country (see docs/phase2/china.md) use:
  scripts/fetch-comaps-mwm.py --group "People's Republic of China" --out mwm/
`--group` is the id of a node of `countries.txt` (every leaf below it is downloaded) and `--only` limits it to some leaves.
Files already present with the right size are kept, so the command can be repeated after an interruption. A file that does not
match is deleted and the command stops with an error. `--cdn` is the CoMaps map server (see the weekly workflow).
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

DEFAULT_CDN = "https://cdn-fi-1.comaps.app/maps"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_COUNTRIES = os.path.join(HERE, "..", "third_party", "comaps", "data", "countries.txt")


def leaves(node):
    if "g" in node:
        for child in node["g"]:
            yield from leaves(child)
    else:
        yield node


def find(node, wanted):
    if node.get("id") == wanted:
        return node
    for child in node.get("g", []):
        found = find(child, wanted)
        if found:
            return found
    return None


def select(countries, group, only=()):
    """The leaves of [group] (all of them, or the ids in [only]); raises KeyError for an unknown group."""
    node = find(countries, group)
    if node is None:
        raise KeyError(group)
    return [n for n in leaves(node) if "s" in n and (not only or n["id"] in only)]


def fetch(node, url_base, out_dir):
    dest = os.path.join(out_dir, node["id"] + ".mwm")
    if os.path.exists(dest) and os.path.getsize(dest) == node["s"]:
        return "have"
    tmp = dest + ".part"
    sha1 = hashlib.sha1()
    with urllib.request.urlopen(url_base + urllib.parse.quote(node["id"]) + ".mwm", timeout=120) as r, open(tmp, "wb") as f:
        for chunk in iter(lambda: r.read(1 << 20), b""):
            sha1.update(chunk)
            f.write(chunk)
    if os.path.getsize(tmp) != node["s"] or base64.b64encode(sha1.digest()).decode() != node["sha1_base64"]:
        os.remove(tmp)
        raise SystemExit(f"{node['id']}: size or SHA-1 does not match countries.txt")
    os.rename(tmp, dest)
    return "ok"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--group", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", nargs="*", default=[])
    ap.add_argument("--countries", default=DEFAULT_COUNTRIES)
    ap.add_argument("--cdn", default=DEFAULT_CDN)
    args = ap.parse_args(argv)
    with open(args.countries, encoding="utf-8") as f:
        countries = json.load(f)
    try:
        nodes = select(countries, args.group, args.only)
    except KeyError:
        sys.exit(f"no node '{args.group}' in {args.countries}")
    os.makedirs(args.out, exist_ok=True)
    base = "%s/%s/%s/" % (args.cdn.rstrip("/"), countries["map_series"], countries["v"])
    total = 0
    for node in nodes:
        print(fetch(node, base, args.out), node["id"], node["s"], flush=True)
        total += node["s"]
    print(f"{len(nodes)} files, {total} bytes")


if __name__ == "__main__":
    main()
