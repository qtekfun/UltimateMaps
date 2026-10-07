#!/usr/bin/env bash
# Builds one city's public-transport index (transit-<id>.umti + transit-<id>.json) from GTFS zips that are ALREADY on disk.
# Nothing is downloaded here: the weekly data workflow fetches the zips first (URLs in docs/phase2/transit.md), then calls
#   scripts/build-transit.sh scripts/transit/madrid.json INPUT_DIR OUTPUT_DIR [YYYY-MM-DD]
# and finally passes the outputs to scripts/gen-region-catalog.py (--transit-file OUTPUT_DIR/transit-madrid.umti --transit-base URL).
# The optional date is the build date (default: today); feeds whose calendar ended before it are left out and reported.
set -euo pipefail
[ $# -ge 3 ] || { echo "usage: $0 MANIFEST.json INPUT_DIR OUTPUT_DIR [BUILD_DATE]" >&2; exit 2; }
root="$(cd "$(dirname "$0")/.." && pwd)"
manifest="$(realpath "$1")"
input="$(realpath "$2")"
mkdir -p "$3"
output="$(realpath "$3")"
extra=()
if [ $# -ge 4 ]; then extra+=("-PtransitToday=$4"); fi
exec "$root/gradlew" -p "$root" --no-daemon -Dorg.gradle.workers.max=2 -Dorg.gradle.jvmargs=-Xmx1536m \
  :core-transit:buildTransit "-Pmanifest=$manifest" "-PtransitInput=$input" "-PtransitOut=$output" ${extra[@]+"${extra[@]}"}
