# Licencias de dependencias

Proyecto bajo GPLv3. Toda dependencia debe ser compatible y constar aquí antes de añadirse.

| Dependencia | Versión | Licencia | Compatible con GPLv3 | Uso |
|---|---|---|---|---|
| kotlinx-serialization-json | 1.11.0 | Apache-2.0 | Sí | `:core-geo`, lectura de Takeout GeoJSON |
| kXML2 (`net.sf.kxml:kxml2`, incluye `org.xmlpull`) | 2.3.0 | BSD-style / dominio público (xmlpull) | Sí | Solo `compileOnly` y tests de `:core-geo`: en Android `org.xmlpull.v1` ya lo aporta la plataforma, no se empaqueta |
| androidx.sqlite `sqlite` (interfaces `SQLiteDriver`, etc.) | 2.7.0 | Apache-2.0 | Sí | `:core-data` (API); en Android se aportará `sqlite-framework` (Apache-2.0, usa el SQLite de la plataforma) |
| androidx.sqlite `sqlite-framework` (`AndroidSQLiteDriver`, SQLite de la plataforma) | 2.7.0 | Apache-2.0 | Sí | `:app` (base de datos de sitios y listas) |
| androidx.sqlite `sqlite-bundled` (incluye SQLite, dominio público) | 2.7.0 | Apache-2.0 + SQLite dominio público | Sí | Solo tests JVM de `:core-data` (no se distribuye) |
| Kotlin stdlib | 2.4.20 | Apache-2.0 | Sí | Todos los módulos |
| JUnit Jupiter / Platform | 6.1.3 | EPL-2.0 | Sí (solo tests, no se distribuye) | Tests |
| MapLibre Native Android (`org.maplibre.gl:android-sdk`) | 13.6.1 | BSD-2-Clause | Sí | `:app`, render del mapa y lectura PMTiles local |
| AndroidX Compose (BOM) y Activity Compose | 2026.09.00 / 1.13.0 | Apache-2.0 | Sí | `:app`, UI (solo `ui` y `foundation`; sin Material) |
| `@protomaps/basemaps` | 5.7.2 | BSD-3-Clause | Sí | Solo en desarrollo: `scripts/gen-map-style.mjs` genera los estilos light/dark incluidos en `app/src/main/assets/map/` |
| Sprites y fuentes de `basemaps-assets` (Protomaps; Noto Sans, SIL OFL 1.1) | v4 | BSD-3 (sprites) / OFL-1.1 (fuentes) | Sí | Empaquetados en `app/src/main/assets/map` (sprites v4, 3 fuentes, rangos 0-255, 256-511, 8192-8703; ~1,3 MB) con `scripts/fetch-map-assets.sh` |
| Robolectric | 4.17 | MIT | Sí (solo tests, no se distribuye) | Tests de UI en el PC |
| JUnit 4 | 4.13.2 | EPL-2.0 | Sí (solo tests, no se distribuye) | Tests de `:app` (requerido por Robolectric y Compose test) |
| Datos de OpenStreetMap | n/a | ODbL 1.0 | Sí (datos, no código; atribución visible, RF-13) | Teselas PMTiles locales |
| CoMaps (`third_party/comaps`, tag `v2026.10.05-19`) | v2026.10.05-19 | Apache-2.0 (copyright My.com, Organic Maps y CoMaps Contributors) | Si (Apache-2.0 es compatible con GPLv3; el conjunto queda GPLv3-o-posterior) | `:native-comaps`: busqueda, routing, indexer, storage y platform minima. Sin drape/render |
| 3party de CoMaps compilados: boost (Boost), expat y jansson (MIT), pugixml (MIT), protobuf (BSD-3), ICU (ICU License), succinct, open-location-code (Apache-2.0), monocypher (BSD-2/CC0), utfcpp (BSL), opening_hours | segun submodulo | Permisivas | Si | `:native-comaps` |
| `kdtree++` (libkdtree++, solo cabeceras; lo incluye `libs/geometry/tree4d.hpp`) | la de CoMaps | Artistic License 2.0 (lo dicen las cabeceras: `3party/kdtree++/function.hpp:83`, `allocator.hpp:90`, `kdtree.hpp:1092`; no hay fichero `COPYING` en el submódulo) | Sí (la FSF la considera compatible con la GPL) | `:native-comaps`, estructuras espaciales de búsqueda |

### Excluido a proposito de la build propia (CoMaps)

| Componente | Licencia | Motivo | Donde se excluye |
|---|---|---|---|
| `3party/bsdiff-courgette/bsdiff` | BSD Protection License | Incompatible con GPLv3 | `native-comaps/src/main/cpp/CMakeLists.txt` no hace `add_subdirectory` de el; `mwm_diff` se sustituye por `stubs/mwm_diff_stub.cpp` (sin actualizaciones por diff: se descarga el mwm completo) |
| `data/fonts/06_code2000.ttf` (Code2000) | Shareware, no libre | Incompatible con GPLv3/F-Droid | Lista blanca de assets en `native-comaps/build.gradle.kts` (`fonts/**` fuera); el nucleo sin render no carga fuentes |
| Iconos Entypo (`data/symbols*`, `data/styles/**`, `search-icons`) | CC BY-SA 3.0 | Obligaciones de compartir igual; solo los usa el render de CoMaps | Fuera de la lista blanca de assets; el render es de MapLibre |
| freetype, harfbuzz, agg, stb_image, libtess2, glfw, imgui, vulkan_wrapper | varias | Solo drape/escritorio | No se compilan |
| Datos de mapa .mwm (OSM + terceros) | ODbL y otras (ver `data/copyright.html`) | Se descargan en ejecucion, no se empaquetan | Atribucion en la pantalla "Acerca de" (pendiente) |

Pendientes heredados (no bloquean la build): `gb-postcode-data` (GPLv2) solo afecta a mapas de GB que generemos nosotros (no es el caso); el texto de la Artistic License 2.0 de `kdtree++` no viene en el submódulo y habrá que incluirlo en el «Acerca de» o en un `NOTICE` antes de F-Droid. Ver `docs/spike/comaps-code.md` sección 5 y `docs/spike/verificaciones.md`.

Sin `play-services-*, Firebase ni SDK propietarios.
