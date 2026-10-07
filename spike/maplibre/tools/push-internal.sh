#!/bin/bash
# Native MapLibre does not read files from Android/data (external storage) -> copy to the internal dir via run-as.
# Usage: push-internal.sh <file> [...]
for f in "$@"; do
  b=$(basename "$f")
  flock /tmp/claude-1000/device.lock adb push "$f" /data/local/tmp/$b
  flock /tmp/claude-1000/device.lock adb shell "chmod a+r /data/local/tmp/$b && run-as org.ultimatemaps.spike.maplibre cp /data/local/tmp/$b files/$b && rm /data/local/tmp/$b"
done
flock /tmp/claude-1000/device.lock adb shell run-as org.ultimatemaps.spike.maplibre ls -la files
