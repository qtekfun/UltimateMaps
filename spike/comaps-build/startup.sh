#!/usr/bin/env bash
# Cold start: N times force-stop + `am start -W` (+ logcat "Displayed" event). All under the device lock.
N=${1:-10}
OUT=${2:-spike/comaps-build/traces/07-startup}
PKG=app.comaps.fdroid
ACT=$PKG/app.organicmaps.DownloadResourcesActivity
exec 9>/tmp/claude-1000/device.lock
flock 9
: > "$OUT.txt"
adb logcat -c
for i in $(seq 1 "$N"); do
  adb shell am force-stop $PKG
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  echo "### run $i" >> "$OUT.txt"
  adb shell am start -W -n $ACT >> "$OUT.txt"
  sleep 4
done
adb logcat -d -s ActivityTaskManager:I | grep -E "Displayed $PKG" > "$OUT.displayed.txt"
adb shell dumpsys display | grep -m3 -E "mActiveModeId|renderFrameRate|fps=" > "$OUT.refresh.txt"
