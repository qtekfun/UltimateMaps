#!/usr/bin/env bash
# adb serializado con el lock compartido del dispositivo. Uso: adbl.sh <args de adb>
exec flock /tmp/claude-1000/device.lock adb "$@"
