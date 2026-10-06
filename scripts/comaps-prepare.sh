#!/usr/bin/env bash
#
# Prepara third_party/comaps para el build nativo propio (:native-comaps).
# Es un subconjunto de third_party/comaps/configure.sh SIN: descarga de World.mwm, simbolos ni drules
# de todos los estilos (el render es de MapLibre). Idempotente.
#
# Requisitos: git, jq, python3 (+venv/pip con acceso a PyPI la primera vez), bash, curl.
# No necesita `uconv` (solo servia para fichas de Google Play).
#
# Los ficheros generados quedan sin versionar dentro de third_party/comaps (el submodulo se marca
# con ignore = untracked).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C="$ROOT/third_party/comaps"
cd "$C"

echo "== submodulos necesarios (sin glfw, imgui, freetype, harfbuzz, vulkan, googletest...)"
git submodule update --init --recursive --depth 1 -- \
  3party/boost 3party/expat 3party/jansson/jansson 3party/pugixml/pugixml 3party/protobuf/protobuf \
  3party/icu/icu 3party/glm 3party/fast_double_parser 3party/utfcpp 3party/glaze 3party/just_gtfs \
  3party/fast_obj 3party/gflags tools/kothic tools/osmctools

if [ ! -d 3party/boost/boost ]; then
  echo "== boost: bootstrap + b2 headers"
  (cd 3party/boost && ./bootstrap.sh && ./b2 headers)
fi

# Venv local con protobuf 3.x (lo exige kothic)
set +u
source ./tools/unix/activate_venv.sh
set -u

echo "== cadenas json y categorias"
./tools/unix/generate_json_strings.sh
./tools/unix/generate_categories.sh

echo "== cadenas de UI de escritorio (libs/platform/localized_types_map.cpp)"
./tools/unix/generate_desktop_ui_strings.sh

# classificator.txt, types.txt, visibility.txt, colors.txt y patterns.txt salen de compilar UN estilo
# (el de vehiculo, como hace generate_drules.sh al final: «produce same visibility.txt & classificator.txt»).
echo "== classificator/types/visibility desde el estilo vehicle"
python3 tools/kothic/src/libkomwm.py --txt \
  -s data/styles/vehicle/light/style.mapcss \
  -o data/drules_proto_vehicle_light \
  -p data/styles/vehicle/include/
python3 tools/python/transit/transit_colors_export.py data/colors.txt > /dev/null

echo "Listo. Comprueba: ls data/classificator.txt data/categories.txt libs/platform/localized_types_map.cpp"
