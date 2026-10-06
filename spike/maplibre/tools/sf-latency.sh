#!/bin/bash
# SurfaceView (texture=false): gfxinfo no ve los frames del SurfaceView -> usar SurfaceFlinger --latency de la capa SurfaceView.
# Ejecutar bajo lock: flock /tmp/claude-1000/device.lock bash sf-latency.sh <tag>
TAG=$1
PKG=org.ultimatemaps.spike.maplibre
ACT=$PKG/org.ultimatemaps.spike.MainActivity
T="$(dirname "$(readlink -f "$0")")/../traces"
for r in 1 2 3; do
  adb shell am force-stop $PKG; sleep 2
  adb shell am start -W -n $ACT --es style style-es.json --ez texture false >/dev/null; sleep 6
  adb shell dumpsys SurfaceFlinger --list > "$T/$TAG-layers-$r.txt"
  L=$(grep -m1 "SurfaceView\[$PKG.*(BLAST)#" "$T/$TAG-layers-$r.txt" | head -1)
  L=$(echo "$L" | sed -E 's/^RequestedLayerState\{[0-9a-f]+ //; s/ parentId=.*//')
  echo "$L" > "$T/$TAG-layer-$r.txt"
  adb shell 'for i in 1 2 3 4 5 6; do input swipe 800 1500 300 700 400; input swipe 300 700 800 1500 400; done' &
  sleep 3.5
  adb shell dumpsys SurfaceFlinger --latency "\"$L\"" > "$T/$TAG-pan-$r.txt"
  wait
done
adb shell am force-stop $PKG
