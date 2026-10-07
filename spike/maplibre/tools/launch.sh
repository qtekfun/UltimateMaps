#!/bin/bash
# Usage: launch.sh [am start extras...]  (cold start: force-stop first). All under flock.
A="flock /tmp/claude-1000/device.lock adb shell"
flock /tmp/claude-1000/device.lock adb shell am force-stop org.ultimatemaps.spike.maplibre
flock /tmp/claude-1000/device.lock adb shell am start -W -n org.ultimatemaps.spike.maplibre/org.ultimatemaps.spike.MainActivity "$@"
