# Phase 1: CoMaps native core (`:native-comaps`)

Date: 2026-10-06. Branch `feat/comaps-core-native`. Option C: rendering is done by MapLibre; search, routing and data use the CoMaps core.

## What is there

- `third_party/comaps`: git submodule pinned to `v2026.10.05-19` (`.gitmodules` with `ignore = untracked`, because `scripts/comaps-prepare.sh` generates unversioned files inside it).
- `scripts/comaps-prepare.sh`: initializes only the nested submodules that are needed (boost, expat, jansson, pugixml, protobuf, icu, glm, ...; NOT glfw, imgui, freetype, harfbuzz, vulkan, googletest), builds the boost headers, creates the Python venv with protobuf 3.x and generates: json strings, `categories.txt`, `libs/platform/localized_types_map.cpp` and, from the `vehicle` style, `classificator.txt`, `types.txt`, `visibility.txt`, `colors.txt`, `patterns.txt`. It does not download maps, nor generate symbols or the drules of all styles. It needs `git`, `jq`, `python3` and PyPI the first time. It took about 2.5 min (not counting the boost download, which was slow: ~150 submodules).
- `native-comaps/` (`com.android.library`, `arm64-v8a` only, NDK 28.2.13676358, CMake 3.31.6 from the SDK):
  - `src/main/cpp/CMakeLists.txt`: its own CMake, not the CoMaps root one. It builds `base coding geometry i18n cppjansson descriptions ge0 kml indexer platform routing_common routing storage search editor traffic transit` from the submodule, without `drape`, `drape_frontend`, `map` (Framework), `shaders`, bookmarks or `android/sdk`. It forces release (-O2, `-DRELEASE`) without LTO and limits the ninja pool to 6 (`-DNJOBS=6`, Gradle property `comaps.njobs`). `drape` is an empty `INTERFACE` (only `drape/color.hpp` was used).
  - `um_platform.cpp`: headless `Platform` (no `Context`, no JNI back to Java). No network: `ConnectionStatus` = none, `GetCurrentNetworkPolicy` = no, `HttpClient::RunHttpRequest` fails and `CreateNativeHttpThread` returns null. All networking is Kotlin's (`:core-net`). Location: outside the module.
  - `um_core.cpp`: facade without `Framework`: `FrozenDataSource` + `search::Engine` + `routing::IndexRouter` (pattern from `routing_integration_tests`). Car/foot/bike profiles; avoid motorways, tolls (car only), ferries and unpaved roads (`RoutingOptions::Motorway/Toll/Ferry/Dirty`), stored in `settings` before each route because that is how `IndexRouter` reads them. The options are hard exclusions: if the only path needs them, the route fails.
  - `um_jni.cpp` + `NativeCore.kt` (bridge with flat types) + `CoMapsCore.kt`: Kotlin facade that implements `SearchEngine` and `RoutingEngine` (`DetailedRoutingEngine` adds the `RouterResultCode` code: `NEED_MORE_MAPS`, `ROUTE_NOT_FOUND`...).
  - Assets: whitelist in `native-comaps/build.gradle.kts` (classificator, categories, countries.txt, packed_polygons.bin...). `:app` declares `noCompress` for those extensions because CoMaps' `ZipFileReader` does not read compressed entries.
- Interfaces extended without breaking anything: `RouteOptions` in `RouteRequest` (with a default value) and `SearchResult.category`.

## Excluded licenses (recorded in `LICENSES.md`)

- `3party/bsdiff-courgette`: not compiled. `mwm_diff` is replaced by `stubs/mwm_diff_stub.cpp` (`ApplyDiff` fails): there is no diff-based update, always a full mwm (the double-download R17 remains open).
- `data/fonts/06_code2000.ttf` and fonts in general: left out of the assets; the core without rendering does not load them.
- Entypo icons (`data/symbols*`, `styles/`, `search-icons`): left out of the assets.

## Verified (real output, on the PC)

```
./gradlew assembleDebug test -Dorg.gradle.workers.max=2   ->   BUILD SUCCESSFUL
libumcomaps.so (arm64-v8a): 7 719 400 bytes (debug, no symbols); no bsdiff/courgette strings
AAR: 114 assets, 13.3 MB (the largest: packed_polygons.bin, 5.2 MB); no fonts/symbols/code2000
tests: 8 new (:native-comaps, JVM, fake bridge) + 83 previous, 0 failures
```

## What is NOT verified (honestly)

None of this has been run: the Pixel 8 cannot be used (user's decision) and there is no other device. It compiles and links; it has not been started.

1. Real startup (`Core.init`): reading assets from the APK, `classificator::Load`, `Storage()` reading `countries.txt`, `CountryInfoReader`. A failure here is likely on the first attempt (paths, uncompressed assets). Pending a device or arm64 emulator: 0.5-1 day.
2. Search and route against real mwm files, and their latency (R12: 0.6 s per search and 17 s Madrid-Barcelona measured in the spike with the Framework; decoupling does not improve them by itself).
3. Build memory: the peak build RAM was not measured (the original ThinLTO went OOM; here there is no LTO). It built with `-j6` and `workers.max=2` with no apparent problems.
4. `release` build (minify/strip) and final APK size.

## What is missing and estimate (days, person; indicative)

| Task | Days |
| --- | --- |
| First run on a device/emulator and startup fixes | 1-2 |
| Test bench on the PC (same `um_*.cpp` for Linux x86_64 with the spike's mwm files) to measure without a device | 2 |
| Map download and registration: `World.mwm`, `WorldCoasts.mwm` and regions from Kotlin (`NetworkPolicy`), version/series of `countries.txt` and Ed25519 signature (own key) | 3-4 |
| Asynchronous search with cancellation/debounce and partial results (today `search` blocks until the end) | 1-2 |
| Detection of maps missing along the route (`AbsentRegionsFinder`) and a message to the user | 1 |
| Turn-by-turn guidance (`RoutingSession`, instructions, recalculation, voice) without `Framework`/`RoutingManager`: see `docs/spike/comaps-code.md` sections 1 and 7 | 5-8 |
| "About" screen with ODbL attribution and third-party data | 0.5 |
| Motorcycle profile, sinuosity and avoiding "soft" roads: see `docs/spike/comaps-code.md` section 3 | 11-18 |

## Risks

- Global state: a single `Core` per process (immortal singleton); the route options travel through global `settings` and `Route` is serialized with a mutex.
- Type texts ("Cafe", "Pharmacy") come out in English from `localized_types_map.cpp`; translation to es/en in the UI is left to Kotlin.
- Updating the CoMaps tag changes the mwm schema (`MAP_SERIES`); bumping the submodule means redoing `comaps-prepare.sh` and checking that it compiles.
