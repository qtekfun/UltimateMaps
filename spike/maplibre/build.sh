#!/bin/bash
# Compila el APK debug con gradle 9.8 (sin daemon, memoria moderada).
cd "$(dirname "$0")"
export ANDROID_HOME=$HOME/Android/Sdk
G=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.8.0-bin/*/gradle-9.8.0)/bin/gradle
"$G" --no-daemon -q :app:assembleDebug
ls -la app/build/outputs/apk/debug
