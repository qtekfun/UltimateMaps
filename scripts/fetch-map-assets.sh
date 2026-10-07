#!/bin/bash
# Para el MANTENEDOR (no lo ejecuta la app ni el flujo automático): descarga sprites y glyphs del
# proyecto protomaps/basemaps-assets (sprites BSD-3; fuentes Noto Sans, SIL OFL 1.1) y genera los estilos.
# La app NO descarga nada en ejecución. Solo se empaqueta lo pequeño: rangos latinos y de puntuación de
# 3 fuentes; el resto de rangos falla sin bloquear el render (MapLibre registra el fallo y sigue).
#
# Uso: scripts/fetch-map-assets.sh [lang]          (lang de las etiquetas, por defecto es)
# Variables: BASEMAPS_MODULES=<dir con node_modules/@protomaps/basemaps> para no usar npm (sin red a npm);
#            SKIP_STYLE=1 para no regenerar los estilos (solo sprites y glyphs).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/app/src/main/assets/map
LANG_CODE=${1:-es}
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
if [ "${SKIP_STYLE:-0}" != 1 ]; then
  # El import ESM se resuelve desde el directorio del script: se copia junto a node_modules.
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
