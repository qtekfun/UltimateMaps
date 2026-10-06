#!/usr/bin/env bash
APK=$HOME/repos/comaps-spike/android/app/build/outputs/apk/fdroid/release/CoMaps-26100501-fdroid-release.apk
set -x
stat -c '%n %s bytes' "$APK"
$HOME/Android/Sdk/build-tools/36.0.0/aapt2 dump badging "$APK" | grep -E "^package|launchable|sdkVersion|native-code"
unzip -lv "$APK" | sort -k1 -n -r | head -12
unzip -l "$APK" | grep "\.so"
