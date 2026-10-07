#!/usr/bin/env bash
# Waits (without holding the lock) for a file ending in $1 to appear on the device inside the maps folder
HERE=$(dirname "$0")
D=/sdcard/Android/data/app.comaps.fdroid/files/261004
for i in $(seq 1 ${2:-60}); do
  o=$(bash "$HERE/adbl.sh" shell "ls -l $D" | tr '\n' ' ')
  case "$o" in *"$1.mwm "*) break;; esac
  sleep 5
done
echo "$o"
