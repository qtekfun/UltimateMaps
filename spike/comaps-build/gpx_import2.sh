#!/usr/bin/env bash
exec 9>/tmp/claude-1000/device.lock
flock 9
adb shell input tap 960 1456   # close dialog
adb shell "cmd media_session >/dev/null 2>&1; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/test-spike.gpx >/dev/null"
sleep 3
adb shell "content query --uri content://media/external/file --projection _id:_display_name --where \"_display_name='test-spike.gpx'\""
ID=$(adb shell "content query --uri content://media/external/file --projection _id --where \"_display_name='test-spike.gpx'\"" | head -1 | sed 's/.*_id=\([0-9]*\).*/\1/')
echo "id=$ID"
adb logcat -c
adb shell "am start -W --grant-read-uri-permission -a android.intent.action.VIEW -t application/gpx+xml -d content://media/external/file/$ID app.comaps.fdroid" | head -5
sleep 6
adb logcat -d -v threadtime > spike/comaps-build/traces/15-gpx-import-content.logcat.txt
adb exec-out screencap -p > /tmp/claude-1000/gpx2.png
