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
| OpenStreetMap data | n/a | ODbL 1.0 | Yes (data, not code; visible attribution, RF-13) | Local PMTiles tiles; also `highway=speed_camera` nodes in the optional `speedcams-es.bin` (attribution shown in Settings and on the cards; the derived camera file stays under ODbL) |
| DGT open data (`nap.dgt.es`): "Radares fijos DGT", DATEX II incident feed v3.7, traffic-camera and panel locations (used only as a kilometre-point to coordinate table), and the report "Puntos y tramos de control de velocidad" | n/a | Creative Commons Attribution (as stated on the NAP dataset pages; the exact CC BY version and the DGT legal notice at dgt.es/contenido/aviso-legal were NOT verified) | Yes (data, not code; attribution "Data: Dirección General de Tráfico (nap.dgt.es), CC BY" shown in Settings and on the cards) | Optional speed-camera layer (static file built by `scripts/build-cameras.py`) and optional live traffic and V16 layer (`:core-cameras`) |
| CRTM static open data (Consorcio Regional de Transportes de Madrid: EMT, interurban and urban buses, Metro Ligero, Metro de Madrid when current), licence "Licencia de datos estaticos del CRTM" (`https://www.crtm.es/licencia-de-uso`) | n/a | Data licence stated by the CRTM (read on 2026-10-07 from the licence page and the item metadata). Commercial and non-commercial reuse allowed. Obligations: cite CRTM as the source, say that the data is processed, show "Powered by CRTM" with a link to `http://www.crtm.es/`, keep the update date and reuse conditions, no suggestion of CRTM endorsement, no distortion; copies of the data are shared under the same licence. **Open owner question:** the clause that the information shown "must always be up to date" (see `docs/decisions.md`) | Yes (data, not code; not bundled in the APK, downloaded like the maps and kept as a separate file) | Optional public-transport timetables: `transit-<city>.umti` built by `:core-transit:buildTransit` (`scripts/build-transit.sh`). Attribution "Powered by CRTM ... Processed data" with the link is shown in the itinerary and in the About dialog; the feed validity dates are shown and the app refuses to plan after them |
| Renfe Cercanias GTFS (`data.renfe.com`, "Horarios de Cercanias") | n/a | Creative Commons Attribution 4.0 (stated on the dataset page, read on 2026-10-07) | Yes (data; attribution "Renfe Operadora, CC BY 4.0", changes indicated: filtered to the Madrid area and converted to a compact index) | Same optional timetable file; the feed is a rolling 30-day window, so the file is rebuilt weekly |
| Open Location Code algorithm and test data (google/open-location-code) | n/a (specification and reference algorithm; no code copied) | Apache-2.0 | Yes | `:core-geo` `OpenLocationCode.kt` is an independent Kotlin implementation of the published algorithm; `OpenLocationCodeTest` embeds a selection of rows of the project's `test_data/*.csv` (Apache-2.0) as test data |
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
