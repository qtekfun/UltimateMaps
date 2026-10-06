#!/usr/bin/env bash
set -ex
HERE=$(dirname "$(readlink -f "$0")")
SDK=$HOME/Android/Sdk
OUT=/tmp/claude-1000/inj-build
rm -rf $OUT; mkdir -p $OUT
javac --release 17 -cp $SDK/platforms/android-36/android.jar -d $OUT/classes $HERE/Inj.java
$SDK/build-tools/36.0.0/d8 --release --lib $SDK/platforms/android-36/android.jar --output $OUT $OUT/classes/Inj.class
ls -l $OUT/classes.dex
mv $OUT/classes.dex /tmp/claude-1000/inj.dex
