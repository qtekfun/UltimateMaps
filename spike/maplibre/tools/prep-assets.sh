#!/bin/bash
# Prepara style.json (protomaps basemaps 5.7.2, flavor light, lang es, BSD-3), sprites y glyphs locales.
# Uso: prep-assets.sh <dir_trabajo>   (requiere node/npm; descarga assets de protomaps basemaps-assets)
set -e
W=${1:?dir de trabajo}
mkdir -p "$W" && cd "$W"
cp "$(dirname "$(readlink -f "$0")")/gen-style.mjs" .
[ -d node_modules/@protomaps ] || { npm init -y >/dev/null; npm i @protomaps/basemaps >/dev/null; }
B=https://protomaps.github.io/basemaps-assets
for T in es madrid; do
  node gen-style.mjs style-$T.json "file://@DIR@/fonts/{fontstack}/{range}.pbf" "file://@DIR@/sprites/light" "pmtiles://file://@DIR@/$T.pmtiles"
done
mkdir -p assets/sprites assets/fonts
for f in light.json light.png light@2x.json light@2x.png; do curl -sf -o assets/sprites/$f "$B/sprites/v4/$f"; done
for font in "Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic"; do
  mkdir -p "assets/fonts/$font"
  for r in 0-255 256-511 8192-8447 8448-8703; do
    curl -sf -o "assets/fonts/$font/$r.pbf" "$B/fonts/${font// /%20}/$r.pbf"
  done
done
