#!/bin/bash
# Screenshots of central Madrid at z12/15/17.5 with es.pmtiles (each adb call under flock)
cd "$(dirname "$(readlink -f "$0")")/.."
for z in 12 15 17.5; do
  bash tools/launch.sh --es style style-es.json --ed zoom $z | grep TotalTime
  sleep 5
  flock /tmp/claude-1000/device.lock adb exec-out screencap -p > traces/madrid-z$z-es.png
done
flock /tmp/claude-1000/device.lock adb shell am force-stop org.ultimatemaps.spike.maplibre
