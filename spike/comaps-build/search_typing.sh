#!/usr/bin/env bash
# Types queries into the CoMaps search UI character by character (input text) and saves a logcat with the lines
# "Search ended in N ms" / "Emitting a new batch ... ms since the search has started" from the core.
# Usage: search_typing.sh <output prefix> <query1> [query2 ...]   (spaces as %s)
P=$1; shift
exec 9>/tmp/claude-1000/device.lock
flock 9
adb shell am force-stop app.comaps.fdroid
sleep 1
adb shell "am start -W -a android.intent.action.VIEW -d 'geo:40.4168,-3.7038?z=15' app.comaps.fdroid" | grep TotalTime
sleep 5
adb shell input tap 1006 1999   # closes the place sheet
sleep 1
adb logcat -c
adb shell input tap 444 2254    # search button
sleep 2
for q in "$@"; do
  echo "### query $q" >> "$P.keys.txt"
  n=${#q}
  for ((i=0;i<n;i++)); do
    c=${q:$i:1}
    echo "key '$c' at $(adb shell date +%H:%M:%S.%N | cut -c1-12)" >> "$P.keys.txt"
    if [ "$c" = "_" ]; then adb shell input keyevent KEYCODE_SPACE; else adb shell input text "$c"; fi
    sleep 1.5
  done
  sleep 1
  # delete the text: 30 backspaces
  adb shell 'for i in $(seq 1 30); do input keyevent KEYCODE_DEL; done'
  sleep 2
done
adb logcat -d -v threadtime > "$P.logcat.txt"
adb exec-out screencap -p > "$P.png"
