#!/usr/bin/env bash
# Launches a route via the comaps://route intent and saves the full logcat and a screenshot. Usage: route_probe.sh <type> <slat,slon> <dlat,dlon> <prefix>
TYPE=$1; S=$2; D=$3; P=$4
exec 9>/tmp/claude-1000/device.lock
flock 9
adb logcat -c
[ "$6" = warm ] || adb shell am force-stop app.comaps.fdroid
sleep 1
adb shell "am start -W -a android.intent.action.VIEW -d 'comaps://route?sll=$S&saddr=A&dll=$D&daddr=B&type=$TYPE' app.comaps.fdroid" | grep -E "TotalTime|Status"
sleep ${5:-12}
adb logcat -d -v threadtime > "$P.logcat.txt"
adb exec-out screencap -p > "$P.png"
