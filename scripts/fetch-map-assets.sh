#!/bin/bash
# For the MAINTAINER (neither the app nor the automatic flow runs it): downloads sprites and glyphs from the
# protomaps/basemaps-assets project (sprites BSD-3; Noto Sans fonts, SIL OFL 1.1) and generates the styles.
# The app does NOT download anything at runtime. Only the small part is packaged: Latin and punctuation ranges of
# 3 fonts; the other ranges fail without blocking rendering (MapLibre logs the failure and carries on).
#
# Usage: scripts/fetch-map-assets.sh [lang]          (label language, default es)
# Variables: BASEMAPS_MODULES=<dir with node_modules/@protomaps/basemaps> to avoid npm (no network to npm);
#            SKIP_STYLE=1 to skip regenerating the styles (sprites and glyphs only).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/app/src/main/assets/map
LANG_CODE=${1:-es}
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
if [ "${SKIP_STYLE:-0}" != 1 ]; then
  # The ESM import is resolved from the script's directory: it is copied next to node_modules.
  cp "$ROOT/scripts/gen-map-style.mjs" "$W/"
  if [ -n "${BASEMAPS_MODULES:-}" ]; then
    ln -s "$BASEMAPS_MODULES/node_modules" "$W/node_modules"
  else
    (cd "$W" && npm init -y >/dev/null && npm i @protomaps/basemaps@5.7.2 >/dev/null)
  fi
  (cd "$W" && node gen-map-style.mjs "$OUT" "$LANG_CODE")
fi
B=https://protomaps.github.io/basemaps-assets
mkdir -p "$OUT/sprites"
for f in light.json light.png light@2x.json light@2x.png dark.json dark.png dark@2x.json dark@2x.png; do
  curl -sf --max-time 60 -o "$OUT/sprites/$f" "$B/sprites/v4/$f"
done
for font in "Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic"; do
  mkdir -p "$OUT/fonts/$font"
  for r in 0-255 256-511 8192-8447 8448-8703; do
    curl -sf --max-time 60 -o "$OUT/fonts/$font/$r.pbf" "$B/fonts/${font// /%20}/$r.pbf"
  done
done
du -sh "$OUT"
