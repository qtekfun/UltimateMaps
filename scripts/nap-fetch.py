#!/usr/bin/env python3
"""Downloads the GTFS zips of one transit manifest (scripts/transit/<city>.json) from the Spanish National Access Point.

Usage: nap-fetch.py MANIFEST.json OUTPUT_DIR [--base-url URL]

Every feed that has a `nap` file id is fetched from <base>/api/Fichero/download/<id> with the HTTP header `ApiKey: <key>`;
the key comes ONLY from the environment variable NAP_API_KEY (the data repo keeps it as a secret) and is never printed.
Feeds without a `nap` id are ignored (the workflow downloads those itself, as for Madrid).

Never fails the build: a missing key, an HTTP error or a response that is not a zip is reported and that feed is skipped
(TransitBuild then skips a missing file, and an expired one). The exit status is 0 unless the usage is wrong. The last
line printed is `RESULT <city id> fetched=<n> failed=<n>`; with GITHUB_STEP_SUMMARY set, one table row per feed is appended.
Network: the only host is nap.transportes.gob.es, only when the data workflow runs this script; the app never does.
"""
import json
import os
import sys
import urllib.error
import urllib.request
import zipfile

BASE = "https://nap.transportes.gob.es"
HEADER = "ApiKey"  # header name published in the Mobility Database catalog entries of the NAP feeds
MAX_BYTES = 400 << 20


def fetch_one(base, nap_id, key, dest, timeout=300):
    """Returns (ok, message). Writes `dest` only when the response is a readable zip with stop_times.txt."""
    req = urllib.request.Request(f"{base.rstrip('/')}/api/Fichero/download/{nap_id}", headers={HEADER: key})
    part = dest + ".part"
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r, open(part, "wb") as f:
            total = 0
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_BYTES:
                    return False, "larger than the allowed maximum"
                f.write(chunk)
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}"
    except (urllib.error.URLError, OSError) as e:
        return False, f"network error ({type(e).__name__})"
    try:
        if not zipfile.is_zipfile(part):
            return False, "response is not a zip file"
        with zipfile.ZipFile(part) as z:
            if "stop_times.txt" not in z.namelist():
                return False, "zip has no stop_times.txt"
    except zipfile.BadZipFile:
        return False, "damaged zip"
    os.replace(part, dest)
    return True, f"{os.path.getsize(dest)} bytes"


def run(manifest_path, out_dir, key, base=BASE, log=print, summary=None):
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    os.makedirs(out_dir, exist_ok=True)
    fetched = failed = 0
    for feed in manifest["feeds"]:
        nap_id = feed.get("nap")
        if nap_id is None:
            continue
        dest = os.path.join(out_dir, feed["file"])
        if not key:
            ok, msg = False, "NAP_API_KEY is not set"
        else:
            ok, msg = fetch_one(base, nap_id, key, dest)
        if not ok:
            for leftover in (dest, dest + ".part"):
                if os.path.exists(leftover):
                    os.remove(leftover)
        log(f"{'OK  ' if ok else 'FAIL'} {manifest['id']}: {feed['label']} (NAP {nap_id}): {msg}")
        if summary is not None:
            summary.append(f"| {manifest['id']} | {feed['label']} | {'fetched' if ok else 'FAILED'} | {msg} |")
        fetched += ok
        failed += not ok
    log(f"RESULT {manifest['id']} fetched={fetched} failed={failed}")
    return fetched, failed


def main(argv):
    args = argv[1:]
    base = BASE
    if "--base-url" in args:
        i = args.index("--base-url")
        base = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    summary = []
    run(args[0], args[1], os.environ.get("NAP_API_KEY", ""), base, summary=summary)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path and summary:
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n".join(summary) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
