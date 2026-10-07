#!/bin/bash
# Installs the APK and pushes style/sprites/glyphs/pmtiles to the device (all under flock).
# Usage: push.sh <style_dir> <madrid|es> <file.pmtiles>
W=$1; T=$2; P=$3
L="flock /tmp/claude-1000/device.lock"
PKG=org.ultimatemaps.spike.maplibre
D=/sdcard/Android/data/$PKG/files
APK="$(dirname "$(readlink -f "$0")")/../app/build/outputs/apk/debug/app-debug.apk"
$L adb install -r "$APK" || exit 1
$L adb shell mkdir -p $D
$L adb push "$W/style-$T.json" $D/style-$T.json
$L adb push "$W/assets/sprites" $D/
$L adb push "$W/assets/fonts" $D/
[ -n "$P" ] && $L adb push "$P" $D/$T.pmtiles
