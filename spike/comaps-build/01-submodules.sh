#!/usr/bin/env bash
# Inicializa submódulos de CoMaps con --depth 1 (mismo comando que usa configure.sh pero poco profundo).
set -x
cd ~/repos/comaps-spike
git submodule update --init --recursive --depth 1 --jobs 4
git submodule status --recursive | head -40
