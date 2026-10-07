#!/usr/bin/env bash
#
# Makes third_party/comaps buildable WITHOUT network and WITHOUT PyPI, from the generated files that are committed in
# native-comaps/generated/ (see scripts/comaps-generate.sh for how they are produced). This is what F-Droid's `prebuild`
# runs, and what the Gradle build runs by itself when the submodule has not been prepared.
#
# What it does (idempotent, offline):
#   1. checks that the submodule and the nested submodules the build needs are checked out (it never fetches them);
#   2. copies the committed generated files into the submodule tree;
#   3. builds data/countries-strings/ from data/translations/ (the same conversion as CoMaps' generate_json_strings.sh,
#      in plain python3, no jq);
#   4. generates the Boost headers (`b2 headers`: symlinks; compiles b2 locally, no network);
#   5. applies native-comaps/patches/*.patch.
#
# Usage: scripts/comaps-stage.sh [--comaps DIR]      (default: third_party/comaps)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C="$ROOT/third_party/comaps"
if [ "${1:-}" = "--comaps" ]; then C="$(cd "$2" && pwd)"; fi
GEN="$ROOT/native-comaps/generated"

if [ ! -f "$C/CMakeLists.txt" ]; then
  echo "ERROR: $C is empty: run 'git submodule update --init third_party/comaps' (or clone with --recurse-submodules)" >&2
  exit 1
fi
for need in 3party/boost/bootstrap.sh 3party/expat/expat/CMakeLists.txt 3party/jansson/jansson/CMakeLists.txt \
            3party/pugixml/pugixml/src/pugixml.cpp 3party/protobuf/protobuf/src/google/protobuf/arena.cc 3party/icu/icu \
            3party/glm 3party/fast_double_parser 3party/utfcpp 3party/glaze 3party/just_gtfs 3party/fast_obj; do
  if [ ! -e "$C/$need" ]; then
    echo "ERROR: nested submodule file missing: $need. Fetch them with: (cd $C && git submodule update --init --recursive --depth 1)" >&2
    echo "       (scripts/comaps-prepare.sh fetches only the ones the build needs)" >&2
    exit 1
  fi
done

echo "== committed generated files -> $C"
cp "$GEN"/data/* "$C/data/"
cp "$GEN"/platform/localized_types_map.cpp "$C/libs/platform/localized_types_map.cpp"

echo "== data/countries-strings (from data/translations)"
python3 - "$C/data" <<'PY'
import json, os, sys

data = sys.argv[1]
src = os.path.join(data, "translations")
n = 0
for root, _dirs, files in os.walk(src):
    for name in files:
        if name != "localize.json":
            continue
        path = os.path.join(root, name)
        dst = os.path.join(data, os.path.relpath(path, src))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.lexists(dst):
            os.remove(dst)
        if os.path.islink(path):  # symlinks are all relative: copy them verbatim
            os.symlink(os.readlink(path), dst)
        else:
            with open(path, encoding="utf-8") as f:
                obj = json.load(f)
            with open(dst, "w", encoding="utf-8") as f:
                json.dump({k: v["defaultMessage"] for k, v in obj.items()}, f, ensure_ascii=False, separators=(",", ":"))
                f.write("\n")
        n += 1
print(f"{n} files")
PY

if [ ! -d "$C/3party/boost/boost" ]; then
  echo "== boost: bootstrap + b2 headers"
  (cd "$C/3party/boost" && ./bootstrap.sh && ./b2 headers)
fi

# Project patches to the CoMaps core, applied idempotently: a patch that is already applied (it reverses cleanly) is
# skipped; one that neither applies nor reverses fails the script.
echo "== project patches"
for patch in "$ROOT"/native-comaps/patches/*.patch; do
  [ -e "$patch" ] || continue
  if patch -p1 -d "$C" --dry-run -s -f < "$patch" >/dev/null 2>&1; then
    patch -p1 -d "$C" -s < "$patch"
    echo "applied $(basename "$patch")"
  elif patch -p1 -d "$C" -R --dry-run -s -f < "$patch" >/dev/null 2>&1; then
    echo "already applied: $(basename "$patch")"
  else
    echo "ERROR: $(basename "$patch") neither applies nor is already applied; the submodule diverged" >&2
    exit 1
  fi
done
echo "Done."
