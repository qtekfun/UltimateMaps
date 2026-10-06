#!/bin/bash
# MapLibre nativo no lee ficheros de Android/data (almacenamiento externo) -> copiar al dir interno via run-as.
# Uso: push-internal.sh <fichero> [...]
for f in "$@"; do
  b=$(basename "$f")
  flock /tmp/work/device.lock adb push "$f" /data/local/tmp/$b
  flock /tmp/work/device.lock adb shell "chmod a+r /data/local/tmp/$b && run-as org.ultimatemaps.spike.maplibre cp /data/local/tmp/$b files/$b && rm /data/local/tmp/$b"
done
flock /tmp/work/device.lock adb shell run-as org.ultimatemaps.spike.maplibre ls -la files
