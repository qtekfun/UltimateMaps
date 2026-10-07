#!/usr/bin/env bash
# Blocker: Fedora does not ship uconv (`icu` package, requires sudo). uconv is built from the ICU in the submodule
# 3party/icu (no sudo) into a prefix outside the CoMaps repo and added to PATH.
set -ex
ICU=~/repos/comaps-spike/3party/icu/icu/icu4c
ls $ICU
ls $ICU/source | head -5
ls $ICU/source/data/in 2>&1 | head -3
PFX=$HOME/repos/comaps-spike-tools/icu
mkdir -p $PFX /tmp/claude-1000/icu-build
cd /tmp/claude-1000/icu-build
$ICU/source/runConfigureICU Linux --prefix=$PFX --disable-tests --disable-samples --disable-static --enable-shared
make -j6
make install
ls $PFX/bin
