#!/usr/bin/env bash
exec 9>/tmp/claude-1000/device.lock
flock 9
adb push spike/comaps-build/test-spike.gpx /sdcard/Download/test-spike.gpx
adb logcat -c
adb shell "am start -W -a android.intent.action.VIEW -t application/gpx+xml -d file:///sdcard/Download/test-spike.gpx app.comaps.fdroid" | head -5
sleep 6
adb logcat -d -v threadtime > spike/comaps-build/traces/15-gpx-import.logcat.txt
adb exec-out screencap -p > /tmp/claude-1000/gpx1.png
