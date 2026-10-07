# CoMaps core (search + routing + storage + minimal platform), WITHOUT drape,
# drape_frontend, map (Framework), bookmarks or UI. See docs/phase1/native-core.md.
cmake_minimum_required(VERSION 3.22.1)
project(umcomaps C CXX)

get_filename_component(OMIM_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/comaps" ABSOLUTE)
if (NOT EXISTS "${OMIM_ROOT}/CMakeLists.txt")
  message(FATAL_ERROR "Falta el submodulo third_party/comaps: git submodule update --init third_party/comaps")
endif()
set(UM_ROOT "${CMAKE_CURRENT_LIST_DIR}")

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_C_STANDARD 23)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${OMIM_ROOT}/cmake")

# Always a "release" build (CoMaps ASSERTs only with DEBUG) and no LTO:
# ThinLTO linking caused an OOM with ~5 GB free (docs/spike/comaps-build.md).
set(SKIP_TESTS ON)
set(SKIP_TOOLS ON)
set(USE_CCACHE OFF)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION OFF)

include(OmimPlatform)
include(OmimOptions)
include(OmimConfig)
include(OmimHelpers)
include(OmimTesting)

add_compile_options(-ffast-math -O2 -g0 -DRELEASE
  -ffile-prefix-map=${OMIM_ROOT}=.
  -Wno-extern-c-compat -Wno-deprecated-declarations)
add_link_options("LINKER:--build-id=sha1")

find_package(Threads REQUIRED)
find_package(ZLIB REQUIRED)
include_directories("${OMIM_ROOT}" "${OMIM_ROOT}/libs" "${OMIM_ROOT}/tools" "${OMIM_ROOT}/3party/boost")

# ---------------------------------------------------------------- 3party (only what is needed)
set(CMAKE_WARN_DEPRECATED OFF CACHE BOOL "" FORCE)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(CMAKE_POLICY_DEFAULT_CMP0063 NEW)
set(EXPAT_BUILD_TOOLS OFF)
set(EXPAT_BUILD_EXAMPLES OFF)
set(EXPAT_BUILD_TESTS OFF)
set(EXPAT_BUILD_DOCS OFF)
set(EXPAT_BUILD_PKGCONFIG OFF)
set(EXPAT_ENABLE_INSTALL OFF)
set(EXPAT_SHARED_LIBS OFF)
set(EXPAT_GE OFF)
set(EXPAT_DTD OFF)
set(EXPAT_NS ON)
add_subdirectory(${OMIM_ROOT}/3party/expat/expat 3party/expat)

set(JANSSON_BUILD_DOCS OFF)
set(JANSSON_BUILD_MAN OFF)
set(JANSSON_EXAMPLES OFF)
set(JANSSON_INSTALL OFF)
set(JANSSON_WITHOUT_TESTS ON)
add_subdirectory(${OMIM_ROOT}/3party/jansson/jansson 3party/jansson)
target_include_directories(jansson INTERFACE "${PROJECT_BINARY_DIR}/3party/jansson/include")

add_subdirectory(${OMIM_ROOT}/3party/pugixml 3party/pugixml)
add_subdirectory(${OMIM_ROOT}/3party/protobuf 3party/protobuf)
add_subdirectory(${OMIM_ROOT}/3party/icu 3party/icu)

add_library(utf8cpp INTERFACE)
add_library(utf8cpp::utf8cpp ALIAS utf8cpp)
target_include_directories(utf8cpp INTERFACE "${OMIM_ROOT}/3party/utfcpp/source")

add_subdirectory(${OMIM_ROOT}/3party/minizip 3party/minizip)
add_subdirectory(${OMIM_ROOT}/3party/open-location-code 3party/open-location-code)
add_subdirectory(${OMIM_ROOT}/3party/opening_hours 3party/opening_hours)
add_subdirectory(${OMIM_ROOT}/3party/succinct 3party/succinct)
add_subdirectory(${OMIM_ROOT}/3party/monocypher 3party/monocypher)
# NOT built: bsdiff-courgette (BSD Protection License, not GPL), freetype,
# harfbuzz, agg, stb_image, vulkan_wrapper, libtess2, glfw, imgui (drape/desktop only).

# ---------------------------------------------------------------- sustitutos propios
# `drape` was only used for drape/color.hpp (a header). No render code.
add_library(drape INTERFACE)
# `mwm_diff` without bsdiff: diff updates are disabled (always the full mwm).
add_library(mwm_diff STATIC "${UM_ROOT}/stubs/mwm_diff_stub.cpp")
target_link_libraries(mwm_diff PRIVATE base)

# ---------------------------------------------------------------- CoMaps libs (without drape, drape_frontend, map, shaders)
foreach(lib base coding geometry i18n cppjansson descriptions ge0 kml indexer platform
            routing_common routing storage search editor traffic transit)
  add_subdirectory(${OMIM_ROOT}/libs/${lib} libs/${lib})
endforeach()

# ---------------------------------------------------------------- nuestra libreria JNI
add_library(umcomaps SHARED
  um_platform.cpp
  um_core.cpp
  um_jni.cpp)
target_include_directories(umcomaps PRIVATE ${UM_ROOT})
target_compile_options(umcomaps PRIVATE ${OMIM_WARNING_FLAGS})
target_link_libraries(umcomaps PRIVATE
  search routing routing_common storage indexer platform coding geometry base
  traffic descriptions
  log android z)
target_link_options(umcomaps PRIVATE "LINKER:--gc-sections" "LINKER:--exclude-libs,ALL")
