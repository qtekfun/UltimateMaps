#!/usr/bin/env bash
# adb serialized with the device's shared lock. Usage: adbl.sh <adb args>
exec flock /tmp/work/device.lock adb "$@"
