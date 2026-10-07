#!/usr/bin/env bash
# Initialises the CoMaps submodules with --depth 1 (same command configure.sh uses, but shallow).
set -x
cd ~/repos/comaps-spike
git submodule update --init --recursive --depth 1 --jobs 4
git submodule status --recursive | head -40
