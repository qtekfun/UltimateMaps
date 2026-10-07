#!/bin/bash
# Usage: launch.sh [am start extras...]  (cold start: force-stop first). All under flock.
A="flock /tmp/work/device.lock adb shell"
flock /tmp/work/device.lock adb shell am force-stop org.ultimatemaps.spike.maplibre
flock /tmp/work/device.lock adb shell am start -W -n org.ultimatemaps.spike.maplibre/org.ultimatemaps.spike.MainActivity "$@"
