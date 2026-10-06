#!/usr/bin/env bash
# Resume "Route found, elapsed seconds" y longitud de cada traza de ruta.
for f in "$@"; do
  t=$(grep -m1 'Route found' "$f" | sed 's/.*elapsed seconds: //')
  l=$(grep -m1 'Route length' "$f" | sed 's/.*Route length: //')
  echo "$(basename "$f") | elapsed_s=${t:-NONE} | $l"
done
