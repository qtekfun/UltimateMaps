#!/bin/bash
# Para el MANTENEDOR (no lo ejecuta la app ni el flujo automático): descarga sprites y glyphs del
# proyecto protomaps/basemaps-assets (sprites BSD-3; fuentes Noto Sans, SIL OFL 1.1) y genera los estilos.
# La app NO descarga nada en ejecución. Solo se empaqueta lo pequeño: rangos latinos y de puntuación de
# 3 fuentes; el resto de rangos falla sin bloquear el render. Uso: scripts/fetch-map-assets.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/app/src/main/assets/map
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
(cd "$W" && npm init -y >/dev/null && npm i @protomaps/basemaps@5.7.2 >/dev/null && node "$ROOT/scripts/gen-map-style.mjs" "$OUT")
B=https://protomaps.github.io/basemaps-assets
mkdir -p "$OUT/sprites"
for f in light.json light.png light@2x.json light@2x.png dark.json dark.png dark@2x.json dark@2x.png; do
  curl -sf -o "$OUT/sprites/$f" "$B/sprites/v4/$f"
done
for font in "Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic"; do
  mkdir -p "$OUT/fonts/$font"
  for r in 0-255 256-511 8192-8447 8448-8703; do
    curl -sf -o "$OUT/fonts/$font/$r.pbf" "$B/fonts/${font// /%20}/$r.pbf"
  done
done
du -sh "$OUT"
