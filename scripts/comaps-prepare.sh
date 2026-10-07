#!/usr/bin/env bash
#
# Prepares third_party/comaps for the project's own native build (:native-comaps).
# It is a subset of third_party/comaps/configure.sh WITHOUT: World.mwm download, symbols or drules
# for all styles (rendering is MapLibre's). Idempotent.
#
# Requirements: git, jq, python3 (+venv/pip with PyPI access the first time), bash, curl.
# Does not need `uconv` (it was only used for Google Play listings).
#
# The generated files stay unversioned inside third_party/comaps (the submodule is marked
# with ignore = untracked).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C="$ROOT/third_party/comaps"
cd "$C"

echo "== required submodules (without glfw, imgui, freetype, harfbuzz, vulkan, googletest...)"
git submodule update --init --recursive --depth 1 -- \
  3party/boost 3party/expat 3party/jansson/jansson 3party/pugixml/pugixml 3party/protobuf/protobuf \
  3party/icu/icu 3party/glm 3party/fast_double_parser 3party/utfcpp 3party/glaze 3party/just_gtfs \
  3party/fast_obj 3party/gflags tools/kothic tools/osmctools

if [ ! -d 3party/boost/boost ]; then
  echo "== boost: bootstrap + b2 headers"
  (cd 3party/boost && ./bootstrap.sh && ./b2 headers)
fi

# Local venv with protobuf 3.x (required by kothic)
set +u
source ./tools/unix/activate_venv.sh
set -u

echo "== json strings and categories"
./tools/unix/generate_json_strings.sh
./tools/unix/generate_categories.sh

echo "== desktop UI strings (libs/platform/localized_types_map.cpp)"
./tools/unix/generate_desktop_ui_strings.sh

# The core sets Android's default style (default/light) with SetCurrentStyle and therefore loads
# drules_proto_default_light.bin; without it, CoMaps init fails (verified on the Pixel 8). It is generated BEFORE the
# vehicle style: each libkomwm rewrites classificator.txt/types.txt/visibility.txt and vehicle must come last, as in
# tools/unix/generate_drules.sh.
echo "== drules default/light (required by core startup)"
python3 tools/kothic/src/libkomwm.py --txt \
  -s data/styles/default/light/style.mapcss \
  -o data/drules_proto_default_light \
  -p data/styles/default/include/

# classificator.txt, types.txt, visibility.txt, colors.txt and patterns.txt come from compiling ONE style
# (the vehicle one, as generate_drules.sh does at the end: "produce same visibility.txt & classificator.txt").
echo "== classificator/types/visibility from the vehicle style"
python3 tools/kothic/src/libkomwm.py --txt \
  -s data/styles/vehicle/light/style.mapcss \
  -o data/drules_proto_vehicle_light \
  -p data/styles/vehicle/include/
python3 tools/python/transit/transit_colors_export.py data/colors.txt > /dev/null

echo "Done. Check: ls data/classificator.txt data/categories.txt libs/platform/localized_types_map.cpp"
