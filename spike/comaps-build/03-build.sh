#!/usr/bin/env bash
# Compila CoMaps (flavor fdroid, sin GMS) para arm64, release (firmado con la clave debug del propio proyecto).
export ANDROID_HOME=$HOME/Android/Sdk
export LD_LIBRARY_PATH=$HOME/repos/comaps-spike-tools/icu/lib
export PATH=$HOME/repos/comaps-spike-tools/icu/bin:$ANDROID_HOME/cmake/3.31.6/bin:$PATH
export LDFLAGS="-Wl,--thinlto-jobs=2"  # el enlace ThinLTO fue OOM-killed con todos los hilos
cd ~/repos/comaps-spike/android
date -Is
/usr/bin/time -v ./gradlew assembleFdroidRelease -Parm64 -Pnjobs=6 --no-daemon \
  -Dorg.gradle.jvmargs=-Xmx2g -Dorg.gradle.workers.max=2 "$@"
echo "exit=$?"
date -Is
