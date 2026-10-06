#!/usr/bin/env bash
# Busqueda por intent comaps://search?query=...&map  -> logcat completo
Q=$1; P=$2
exec 9>/tmp/claude-1000/device.lock
flock 9
adb logcat -c
adb shell am force-stop app.comaps.fdroid
sleep 1
adb shell "am start -W -a android.intent.action.VIEW -d 'comaps://search?query=$Q&map' app.comaps.fdroid" | grep -E "TotalTime"
sleep 8
adb logcat -d -v threadtime > "$P.logcat.txt"
adb exec-out screencap -p > "$P.png"
