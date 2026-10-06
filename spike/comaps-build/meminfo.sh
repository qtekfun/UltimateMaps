#!/usr/bin/env bash
# dumpsys meminfo del paquete (estado actual) -> $1
exec 9>/tmp/claude-1000/device.lock
flock 9
{
  date -Is
  adb shell dumpsys meminfo app.comaps.fdroid
  echo "--- sistema"
  adb shell cat /proc/meminfo | head -3
} > "$1" 2>&1
