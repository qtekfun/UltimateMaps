#!/usr/bin/env bash
#
# Developer/CI path: prepares third_party/comaps for the project's own native build (:native-comaps) from scratch.
# It fetches the nested submodules the build needs (network), REGENERATES the generated files (PyPI: protobuf for kothic,
# plus jq) into native-comaps/generated/, and stages them (scripts/comaps-stage.sh).
#
# You do NOT need this to build: the generated files are committed, so
#   git submodule update --init --recursive third_party/comaps && scripts/comaps-stage.sh
# is enough, and works offline once the submodules are checked out (it is what F-Droid runs). The Gradle build runs the
# stage step by itself. Use this script only to refresh the committed files after bumping the submodule.
#
# Requirements: git, jq, python3 (+venv/pip with PyPI access the first time), bash, curl.
# Does not need `uconv`. Does not download World.mwm and does not generate symbols or drules for all styles
# (rendering is MapLibre's).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C="$ROOT/third_party/comaps"

echo "== required submodules (without glfw, imgui, freetype, harfbuzz, vulkan, googletest...)"
(cd "$C" && git submodule update --init --recursive --depth 1 -- \
  3party/boost 3party/expat 3party/jansson/jansson 3party/pugixml/pugixml 3party/protobuf/protobuf \
  3party/icu/icu 3party/glm 3party/fast_double_parser 3party/utfcpp 3party/glaze 3party/just_gtfs \
  3party/fast_obj 3party/gflags tools/kothic tools/osmctools)

"$ROOT/scripts/comaps-generate.sh"
"$ROOT/scripts/comaps-stage.sh"

echo "Done. Check: ls $C/data/classificator.txt $C/data/categories.txt $C/libs/platform/localized_types_map.cpp"
echo "If the files under native-comaps/generated/ changed, commit them (scripts/comaps-check-generated.sh verifies them)."
