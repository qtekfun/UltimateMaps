#!/usr/bin/env bash
# Bloqueo: Fedora no trae uconv (paquete `icu`, requiere sudo). Se compila uconv desde el ICU del submodulo
# 3party/icu (sin sudo) en un prefijo fuera del repo de CoMaps y se pone en PATH.
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
