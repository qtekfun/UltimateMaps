# Fase 1: nucleo nativo de CoMaps (`:native-comaps`)

Fecha: 2026-10-06. Rama `feat/comaps-core-native`. Opcion C: el render es de MapLibre; busqueda, routing y datos usan el nucleo de CoMaps.

## Que hay

- `third_party/comaps`: submodulo git fijado a `v2026.10.05-19` (`.gitmodules` con `ignore = untracked`, porque `scripts/comaps-prepare.sh` genera ficheros sin versionar dentro).
- `scripts/comaps-prepare.sh`: inicializa solo los submodulos anidados necesarios (boost, expat, jansson, pugixml, protobuf, icu, glm, ...; NO glfw, imgui, freetype, harfbuzz, vulkan, googletest), compila los headers de boost, crea el venv de Python con protobuf 3.x y genera: cadenas json, `categories.txt`, `libs/platform/localized_types_map.cpp` y, desde el estilo `vehicle`, `classificator.txt`, `types.txt`, `visibility.txt`, `colors.txt`, `patterns.txt`. No descarga mapas ni genera simbolos ni los drules de todos los estilos. Necesita `git`, `jq`, `python3` y PyPI la primera vez. Tardo unos 2,5 min (sin contar la descarga de boost, que fue lenta: ~150 submodulos).
- `native-comaps/` (`com.android.library`, solo `arm64-v8a`, NDK 28.2.13676358, CMake 3.31.6 del SDK):
  - `src/main/cpp/CMakeLists.txt`: CMake propio, no el raiz de CoMaps. Compila `base coding geometry i18n cppjansson descriptions ge0 kml indexer platform routing_common routing storage search editor traffic transit` desde el submodulo, sin `drape`, `drape_frontend`, `map` (Framework), `shaders`, bookmarks ni `android/sdk`. Fuerza release (-O2, `-DRELEASE`) sin LTO y limita el pool de ninja a 6 (`-DNJOBS=6`, propiedad Gradle `comaps.njobs`). `drape` es un `INTERFACE` vacio (solo se usaba `drape/color.hpp`).
  - `um_platform.cpp`: `Platform` headless (sin `Context`, sin JNI hacia Java). Sin red: `ConnectionStatus` = ninguna, `GetCurrentNetworkPolicy` = no, `HttpClient::RunHttpRequest` falla y `CreateNativeHttpThread` devuelve null. Toda la red es de Kotlin (`:core-net`). Ubicacion: fuera del modulo.
  - `um_core.cpp`: fachada sin `Framework`: `FrozenDataSource` + `search::Engine` + `routing::IndexRouter` (patron de `routing_integration_tests`). Perfiles coche/pie/bici; evitar autopistas, peajes (solo coche), ferris y sin asfaltar (`RoutingOptions::Motorway/Toll/Ferry/Dirty`), guardadas en `settings` antes de cada ruta porque asi las lee `IndexRouter`. Las opciones son exclusion dura: si el unico camino las necesita la ruta falla.
  - `um_jni.cpp` + `NativeCore.kt` (puente con tipos planos) + `CoMapsCore.kt`: fachada Kotlin que implementa `SearchEngine` y `RoutingEngine` (`DetailedRoutingEngine` anade el codigo de `RouterResultCode`: `NEED_MORE_MAPS`, `ROUTE_NOT_FOUND`...).
  - Assets: lista blanca en `native-comaps/build.gradle.kts` (clasificador, categorias, countries.txt, packed_polygons.bin...). `:app` declara `noCompress` para esas extensiones porque `ZipFileReader` de CoMaps no lee entradas comprimidas.
- Interfaces ampliadas sin romper nada: `RouteOptions` en `RouteRequest` (valor por defecto) y `SearchResult.category`.

## Licencias excluidas (registradas en `LICENSES.md`)

- `3party/bsdiff-courgette`: no se compila. `mwm_diff` se sustituye por `stubs/mwm_diff_stub.cpp` (`ApplyDiff` falla): no hay actualizacion por diff, siempre mwm completo (R17 de doble descarga sigue abierto).
- `data/fonts/06_code2000.ttf` y fuentes en general: fuera de los assets; el nucleo sin render no las carga.
- Iconos Entypo (`data/symbols*`, `styles/`, `search-icons`): fuera de los assets.

## Verificado (salida real, en el PC)

```
./gradlew assembleDebug test -Dorg.gradle.workers.max=2   ->   BUILD SUCCESSFUL
libumcomaps.so (arm64-v8a): 7 719 400 bytes (debug, sin simbolos); sin cadenas bsdiff/courgette
AAR: 114 assets, 13,3 MB (el mayor: packed_polygons.bin, 5,2 MB); sin fonts/symbols/code2000
tests: 8 nuevos (:native-comaps, JVM, puente falso) + 83 previos, 0 fallos
```

## Que NO esta verificado (honesto)

Nada de esto se ha ejecutado: no se puede usar el Pixel 8 (decision del usuario) y no hay otro dispositivo. Se compila y enlaza, no se ha arrancado.

1. Arranque real (`Core.init`): lectura de assets desde el APK, `classificator::Load`, `Storage()` leyendo `countries.txt`, `CountryInfoReader`. Un fallo aqui es probable en el primer intento (rutas, assets sin comprimir). Pendiente de dispositivo o emulador arm64: 0,5-1 dia.
2. Busqueda y ruta contra mwm reales, y su latencia (R12: 0,6 s por busqueda y 17 s Madrid-Barcelona medidas en el spike con el Framework; el desacople no las mejora por si solo).
3. Memoria de compilacion: no se midio el pico de RAM del build (el ThinLTO original hizo OOM; aqui no hay LTO). Compilo con `-j6` y `workers.max=2` sin problemas aparentes.
4. Build `release` (minify/strip) y tamano final del APK.

## Que falta y estimacion (dias, persona; orientativo)

| Tarea | Dias |
| --- | --- |
| Primera ejecucion en dispositivo/emulador y correcciones de arranque | 1-2 |
| Banco de pruebas en el PC (mismo `um_*.cpp` para Linux x86_64 con los mwm del spike) para medir sin dispositivo | 2 |
| Descarga y registro de mapas: `World.mwm`, `WorldCoasts.mwm` y regiones desde Kotlin (`NetworkPolicy`), version/serie de `countries.txt` y firma Ed25519 (clave propia) | 3-4 |
| Busqueda asincrona con cancelacion/debounce y resultados parciales (hoy `search` bloquea hasta el final) | 1-2 |
| Deteccion de mapas ausentes en la ruta (`AbsentRegionsFinder`) y mensaje al usuario | 1 |
| Guiado paso a paso (`RoutingSession`, instrucciones, recalculo, voz) sin `Framework`/`RoutingManager`: ver `docs/spike/comaps-code.md` secciones 1 y 7 | 5-8 |
| Pantalla "Acerca de" con atribucion ODbL y datos de terceros | 0,5 |
| Perfil moto, sinuosidad y evitar "blando": ver `docs/spike/comaps-code.md` seccion 3 | 11-18 |

## Riesgos

- Estado global: un solo `Core` por proceso (singleton inmortal); las opciones de ruta viajan por `settings` global y `Route` esta serializada con un mutex.
- Los textos de tipo ("Cafe", "Pharmacy") salen en ingles de `localized_types_map.cpp`; la traduccion a es/en de la UI queda para Kotlin.
- Actualizar el tag de CoMaps cambia el esquema de mwm (`MAP_SERIES`); subir el submodulo implica rehacer `comaps-prepare.sh` y comprobar que compila.
