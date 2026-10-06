#!/bin/bash
# Uso: launch.sh [extras de am start...]  (cold start: force-stop antes). Todo bajo flock.
A="flock /tmp/work/device.lock adb shell"
flock /tmp/work/device.lock adb shell am force-stop org.ultimatemaps.spike.maplibre
flock /tmp/work/device.lock adb shell am start -W -n org.ultimatemaps.spike.maplibre/org.ultimatemaps.spike.MainActivity "$@"
