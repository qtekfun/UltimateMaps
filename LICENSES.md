# Dependency licences

The project is under GPLv3. Every dependency must be compatible and be listed here before it is added.

| Dependency | Version | Licence | Compatible with GPLv3 | Use |
|---|---|---|---|---|
| kotlinx-serialization-json | 1.11.0 | Apache-2.0 | Yes | `:core-geo`, reading Takeout GeoJSON |
| kotlinx-coroutines (`core` and `test`) | 1.11.0 | Apache-2.0 | Yes | `:core-nav` (StateFlow/SharedFlow of route following); `test` only in tests |
| kXML2 (`net.sf.kxml:kxml2`, includes `org.xmlpull`) | 2.3.0 | BSD-style / public domain (xmlpull) | Yes | Only `compileOnly` and `:core-geo` tests: on Android `org.xmlpull.v1` is already provided by the platform, it is not packaged |
| androidx.sqlite `sqlite` (`SQLiteDriver` interfaces, etc.) | 2.7.0 | Apache-2.0 | Yes | `:core-data` (API); on Android `sqlite-framework` will be provided (Apache-2.0, uses the platform's SQLite) |
| androidx.sqlite `sqlite-framework` (`AndroidSQLiteDriver`, platform SQLite) | 2.7.0 | Apache-2.0 | Yes | `:app` (places and lists database) |
| androidx.sqlite `sqlite-bundled` (includes SQLite, public domain) | 2.7.0 | Apache-2.0 + SQLite public domain | Yes | Only JVM tests of `:core-data` (not distributed) |
| Kotlin stdlib | 2.4.20 | Apache-2.0 | Yes | All modules |
| JUnit Jupiter / Platform | 6.1.3 | EPL-2.0 | Yes (tests only, not distributed) | Tests |
| MapLibre Native Android (`org.maplibre.gl:android-sdk`) | 13.6.1 | BSD-2-Clause | Yes | `:app`, map rendering and local PMTiles reading |
| AndroidX Compose (BOM) and Activity Compose | 2026.09.00 / 1.13.0 | Apache-2.0 | Yes | `:app`, UI (only `ui` and `foundation`; no Material) |
| `@protomaps/basemaps` | 5.7.2 | BSD-3-Clause | Yes | Development only: `scripts/gen-map-style.mjs` generates the light/dark styles included in `app/src/main/assets/map/` |
| Sprites and fonts from `basemaps-assets` (Protomaps; Noto Sans, SIL OFL 1.1) | v4 | BSD-3 (sprites) / OFL-1.1 (fonts) | Yes | Packaged in `app/src/main/assets/map` (sprites v4, 3 fonts, ranges 0-255, 256-511, 8192-8703; ~1.3 MB) with `scripts/fetch-map-assets.sh` |
| Robolectric | 4.17 | MIT | Yes (tests only, not distributed) | UI tests on the PC |
| JUnit 4 | 4.13.2 | EPL-2.0 | Yes (tests only, not distributed) | `:app` tests (required by Robolectric and Compose test) |
| OpenStreetMap data | n/a | ODbL 1.0 | Yes (data, not code; visible attribution, RF-13) | Local PMTiles tiles |
| CoMaps (`third_party/comaps`, tag `v2026.10.05-19`) | v2026.10.05-19 | Apache-2.0 (copyright My.com, Organic Maps and CoMaps Contributors) | Yes (Apache-2.0 is compatible with GPLv3; the combined work ends up GPLv3-or-later) | `:native-comaps`: search, routing, indexer, storage and minimal platform. No drape/render |
| Compiled CoMaps 3party: boost (Boost), expat and jansson (MIT), pugixml (MIT), protobuf (BSD-3), ICU (ICU License), succinct, open-location-code (Apache-2.0), monocypher (BSD-2/CC0), utfcpp (BSL), opening_hours | per submodule | Permissive | Yes | `:native-comaps` |
| `kdtree++` (libkdtree++, headers only; included by `libs/geometry/tree4d.hpp`) | the CoMaps one | Artistic License 2.0 (as stated by the headers: `3party/kdtree++/function.hpp:83`, `allocator.hpp:90`, `kdtree.hpp:1092`; there is no `COPYING` file in the submodule) | Yes (the FSF considers it compatible with the GPL) | `:native-comaps`, search spatial structures |

### Deliberately excluded from our own build (CoMaps)

| Component | Licence | Reason | Where it is excluded |
|---|---|---|---|
| `3party/bsdiff-courgette/bsdiff` | BSD Protection License | Incompatible with GPLv3 | `native-comaps/src/main/cpp/CMakeLists.txt` does not `add_subdirectory` it; `mwm_diff` is replaced by `stubs/mwm_diff_stub.cpp` (no diff updates: the full mwm is downloaded) |
| `data/fonts/06_code2000.ttf` (Code2000) | Shareware, not free | Incompatible with GPLv3/F-Droid | Asset allowlist in `native-comaps/build.gradle.kts` (`fonts/**` left out); the render-less core does not load fonts |
| Entypo icons (`data/symbols*`, `data/styles/**`, `search-icons`) | CC BY-SA 3.0 | Share-alike obligations; only used by CoMaps' renderer | Outside the asset allowlist; rendering is done by MapLibre |
| freetype, harfbuzz, agg, stb_image, libtess2, glfw, imgui, vulkan_wrapper | various | Drape/desktop only | Not compiled |
| Map data .mwm (OSM + third parties) | ODbL and others (see `data/copyright.html`) | Downloaded at runtime, not packaged | Attribution on the "About" screen (pending) |

Inherited pending items (they do not block the build): `gb-postcode-data` (GPLv2) only affects GB maps that we generate ourselves (not the case); the text of `kdtree++`'s Artistic License 2.0 is not in the submodule and will have to be included in the "About" screen or in a `NOTICE` before F-Droid. See `docs/spike/comaps-code.md` section 5 and `docs/spike/verificaciones.md`.

No `play-services-*`, Firebase or proprietary SDKs.
