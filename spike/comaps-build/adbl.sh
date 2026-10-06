#!/usr/bin/env bash
# adb serializado con el lock compartido del dispositivo. Uso: adbl.sh <args de adb>
exec flock /tmp/work/device.lock adb "$@"
