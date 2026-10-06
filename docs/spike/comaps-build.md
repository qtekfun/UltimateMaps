# Spike (a): compilar CoMaps tal cual y medir

Fecha: 2026-10-06. Rama `spike/comaps-build`. Flujo (a) de `docs/mapas-04-spike.md`.
Fuente: CoMaps tag `v2026.10.05-19` (shallow) en `~/repos/comaps-spike` (fuera del repo). Versión de app: `2026.10.05-1`, versionCode 26100501.
Dispositivo: un único Pixel 8 (Android 17, arm64, 120 Hz, GMS presente pero no usado por el sabor fdroid), conectado por adb inalámbrico y **compartido con otros agentes** (ver «Límites de las medidas»).
Scripts: `spike/comaps-build/`. Salidas crudas (texto): `spike/comaps-build/traces/`.

## 1. Compilación

Resultado: **APK arm64 fdroid release compilado** (`assembleFdroidRelease -Parm64 -Pnjobs=6`, firmado con la clave debug del propio proyecto porque no hay `secure.properties.release`). Sin GMS: el sabor `fdroid` no incluye `play-services`.

- Build limpio con todo resuelto: 5 min 37 s de reloj, pico de RAM de Gradle (`/usr/bin/time -v`) 6,07 GB RSS (`traces/03-build.txt`). Con `-Dorg.gradle.workers.max=2`, ninja `-j6`.
- Gradle 8.14.4 (wrapper), JDK 21 (Temurin) vale; NDK 28.2.13676358 (el que fija el proyecto); CMake 3.31.6 del SDK.
- **Tamaño del APK: 46 276 863 bytes (44,1 MiB)**, solo arm64-v8a. `liborganicmaps.so` 14,7 MB sin comprimir (6,3 MB en el zip). `traces/06-apk-size.txt`.

### Bloqueos y parches (ninguno toca el motor)

No se ha modificado ningún fichero fuente de CoMaps. Lo necesario para que compile en esta máquina:

| # | Problema | Solución (sin sudo) | Traza |
|---|----------|---------------------|-------|
| 1 | Submódulos sin inicializar | `git submodule update --init --recursive --depth 1` (2,3 GB en disco) | `01-submodules.txt` |
| 2 | Boost requiere `bootstrap.sh` + `b2 headers` (lo hace `configure.sh`) | Ejecutado a mano | `02-boost-bootstrap.txt` |
| 3 | `World.mwm` / `WorldCoasts.mwm` (los descarga `configure.sh`) | `curl` desde `mapgen-fi-1.comaps.app/maps/2026.06.28/261004/` | — |
| 4 | **Falta `uconv`** (paquete `icu`, Fedora, requiere sudo): el script `generate_serbian_latin_strings.sh` aborta el configure de Gradle | Compilado `uconv` desde el ICU del submódulo `3party/icu` en `~/repos/comaps-spike-tools/icu` y añadido al `PATH`/`LD_LIBRARY_PATH` | `03-build-attempt1-…`, `04-uconv.txt` |
| 5 | **CMake 3.22.1 + NDK 28**: añade `-fuse-ld=gold` en LTO y el NDK 28 ya no trae gold → `invalid linker name` | `android/local.properties` con `cmake.dir=…/cmake/3.31.6` (configuración local, no versionada) | `03-build-attempt2-gold-linker.txt` |
| 6 | **OOM en el enlace ThinLTO** de `liborganicmaps.so` (`clang++: Killed`) con ~5 GB libres | `LDFLAGS=-Wl,--thinlto-jobs=2` | `03-build-attempt3-link-oom.txt` |

`spike/comaps-build/03-build.sh` contiene el comando final. Paquetes de sistema que seguirían faltando para un build «de manual»: `icu` (uconv) y probablemente `ninja`/`cmake` globales (se usan los del SDK).

## 2. Instalación y mapas

- Instalado con `adb install -r` (paquete `app.comaps.fdroid`). Permisos de ubicación y notificaciones concedidos con `pm grant`.
- Mapas descargados **por la propia app** (la carpeta `Android/data/<pkg>/files` no es escribible por `adb push` en Android 17: `Permission denied`). Red sin fallos. Datos de mapas versión 261004:
  - **España completa: 25 regiones, 1,9 GB** (descarga «Descargar todos» en la UI, ~13 min) + World (51 MB) y WorldCoasts (8 MB). `traces/05-download-maps.txt` verifica por host que los tamaños coinciden con `countries.txt`.
  - **Fiyi (Oceanía): 19 MB**, descargado desde la UI (`traces/16-fiji-download.txt`); renderiza Suva con calles y POIs (capturas hechas, no guardadas).
  - Observación: en CoMaps «España» no es un único .mwm; se divide en 25 regiones (Cataluña en 4 provincias, etc.).

## 3. Mediciones

**Refresh rate:** 120 Hz (peak/min refresh 120; `renderFrameRate 120.00001`, `mActiveModeId=2`; `traces/07-startup.refresh.txt`). Todos los umbrales de fps se evalúan contra 8,3 ms.

### Resumen frente a umbrales

| Métrica | Umbral | Medido | Veredicto |
|---|---|---|---|
| Intervalo entre frames del mapa durante pan, p95 (120 Hz) | ≤ 8,3 ms | pan 8,85 ms; zoom 8,88; giro 8,87 (p50 8,32-8,33; 119-120 fps medios) | Sin frames perdidos (> 12,5 ms: 0 % / 0,16 % / 0,08 %), pero el p95 literal supera 8,3 por el jitter del sello de tiempo de SurfaceFlinger. Pasa en la práctica; ver método |
| Arranque en frío (`am start -W`, mediana de 10) | ≤ 1 s | TotalTime **141 ms** (Splash); a `MwmActivity` mostrada ≈ **650 ms** (n=5) | Pasa. No medido: primer tile pintado |
| Búsqueda tras cada tecla (primer lote) | ≤ 100 ms | mediana **631 ms**, p95 1734 ms (n=31); 1.ª búsqueda en frío 6705 ms | **No pasa** |
| Ruta Madrid–Barcelona (coche) | ≤ 2 s | **16,50 / 16,64 / 17,39 s** (mediana 16,64 s), 621,8 km | **No pasa** (8x) |
| Ruta urbana coche 6,7 km | — | 0,42-0,44 s (n=3) | informativo |
| Ruta urbana a pie 5,1 km / bici 5,4 km | — | 1,24-1,42 s / 1,13-1,16 s (n=3 c/u) | informativo |
| Sierra de Guadarrama, coche 13,8 km | — | 0,66-0,68 s (n=2) | informativo |
| Memoria | — | PSS total 503 MB (Gráficos 294, Native heap 162, Java 5,7) tras gestos en Madrid z15 con 28 mwm | informativo |
| Tamaño APK | — | 46,3 MB (arm64) | informativo |

Las clases de dispositivo gama media/baja, ROM sin GMS y de-Googled **no se pueden medir** (solo hay un Pixel 8): los umbrales de gama media y «Sin GMS» quedan **no medidos**.

### 3.1 Arranque en frío
Comando (`spike/comaps-build/startup.sh`): 10 × (`am force-stop` + HOME + 3 s + `am start -W -n app.comaps.fdroid/app.organicmaps.DownloadResourcesActivity`), `LaunchState: COLD` en las 10.
- TotalTime (ms): 129 130 135 137 140 142 142 144 147 148 → mediana 141 (WaitTime mediana 143). `traces/07-startup.txt`.
- Esa cifra es solo la `SplashActivity`, que lanza `MwmActivity`. Con las líneas `Displayed` de logcat (`07-startup.displayed.txt`), de la orden de arranque a `MwmActivity` mostrada: 612, 627, 650, 651, 658 ms (solo 5 de las 10 ejecuciones dejaron la pareja de líneas; mediana 650 ms). Con 28 mwm instalados.

### 3.2 fps / frame time (pan, zoom, giro)
Comandos: `spike/comaps-build/gesture_trace.py <pan|zoom|rotate|idle> 10 <traza>` + `analyze_trace.py`. Gestos inyectados con multitouch real a 120 Hz desde un proceso `app_process` en el dispositivo (`inj/Inj.java`, `InputManagerGlobal.injectInputEvent`), con temporización local. Vista Madrid centro z15 (intent `geo:`), 10 s por gesto. Grabado con Perfetto (`perfetto.cfg`, binario 8-9 MB **no** guardado por tamaño; solo los JSON de resultados).

| Gesto | frames | intervalo p50 | p90 | p95 | p99 | máx | fps medio | > 16,7 ms | > 12,5 ms |
|---|---|---|---|---|---|---|---|---|---|
| pan (1 dedo, ±500 px, 1 Hz) | 1397 | 8,33 | 8,72 | 8,85 | 9,35 | 12,19 | 120,0 | 0 % | 0 % |
| zoom (pinch 120↔600 px, 0,5 Hz) | 1221 | 8,32 | 8,72 | 8,88 | 9,33 | 91,9 | 118,9 | 0,08 % | 0,16 % |
| giro (360°/3 s, radio 250 px) | 1231 | 8,32 | 8,70 | 8,87 | 9,24 | 13,2 | 120,0 | 0 % | 0,08 % |
| reposo (sin gesto) | 271 (ráfagas cortas) | 8,34 | 8,62 | 8,71 | 9,15 | 9,40 | — | — | — |

JSON en `traces/09-*.json`. Método y limitaciones:
- `dumpsys gfxinfo` **no se usó**: CoMaps pinta en un `SurfaceView` GL propio, no por HWUI; además el frametimeline de Perfetto devolvió 0 filas para esa capa. El proxy usado es el intervalo entre buffers enganchados por SurfaceFlinger para la capa `SurfaceView[app.comaps.fdroid/…MwmActivity](BLAST)` (slice `setBuffer … hasBuffer=true`), tomando la ráfaga continua más larga.
- Es tiempo de **presentación**, no tiempo de CPU/GPU por frame. El p95 de 8,85 ms vs 8,3 ms refleja jitter de ±0,5 ms en los sellos; el dato robusto es que no hay frames perdidos (≥ 1,5 periodos) salvo 2 intervalos en zoom (uno de 91,9 ms) y 1 en giro (13,2 ms).
- La ráfaga incluye ~1 s antes/después del gesto en el que el mapa también anima. El reposo da ráfagas cortas, así que el 120 fps continuo sí corresponde al gesto.
- Un solo dispositivo de gama alta, escena urbana densa en Madrid z15; no se probó 3D inclinado ni z más altos.

### 3.3 Memoria
`dumpsys meminfo app.comaps.fdroid` tras los gestos (`traces/10-meminfo-after-gestures.txt`): TOTAL PSS 503 205 KB; Graphics 293 660 KB (EGL+GL mtrack); Native Heap 162 148 KB; Code 21 728 KB; Java Heap 5 684 KB; RSS 619 MB. Durante el cálculo largo de ruta con «evitar autopistas», `top` mostró RES 1,3 GB y 115 % CPU en la app.

### 3.4 Búsqueda
No hay API de medida expuesta; se usan los logs del propio núcleo (`search/emitter.hpp`: «Emitting a new batch of results: N, X ms since the search has started», `search/engine.cpp`: «Search ended in X ms»). Comando: `search_typing.sh` teclea `plaza_mayor`, `calle_alcala`, `atocha` carácter a carácter (1,5 s entre teclas) en la UI de búsqueda, con las 28 mwm cargadas y la vista en Madrid. `traces/13-search.logcat.txt`, resumen `13-search-summary.txt` (`analyze_search.py`):
- 46 búsquedas (descartada la 1.ª en frío), 31 con resultados: **primer lote** mín 143 / mediana 631 / p95 1734 / máx 2898 ms; fin de búsqueda mediana 661 ms.
- La primera búsqueda tras arrancar (carga de índices) tardó 6705 ms al primer lote (intent `comaps://search`, `traces`: no guardada, valor del log de la sesión de prueba).
- Caveat: el «tiempo» incluye la búsqueda por viewport sobre España completa; no se probó con solo Madrid instalado. Latencia UI (tecla→pintado) no medida.

### 3.5 Rutas
Disparadas por intent `comaps://route?sll=..&dll=..&type=vehicle|pedestrian|bicycle` (`route_probe.sh`); tiempo = línea `Route found, elapsed seconds` del núcleo (`route_times.sh`; `traces/11-*`, `12-*`, `12-route-times.txt`). Proceso en frío cada vez.
- **Madrid centro → Barcelona centro (coche): 17,39 / 16,50 / 16,64 s; 621 843 m, ETA 22 033 s.** Dos fases: modo «LeapsOnly» ≈ 9-10 s y refinado ≈ 7 s. Muy por encima de 2 s.
- Una repetición con «evitar peajes» dio el mismo recorrido y 26,2 s (`14-route-mad-bcn-avoid-toll`), así que el ruido por carga del dispositivo compartido es ≥ 50 %.
- Urbana y Guadarrama: ver tabla resumen. «Moto» no existe como perfil.

## 4. Funciones probadas en el dispositivo

| Función | Resultado | Cómo / evidencia |
|---|---|---|
| Intent `geo:` | **Funciona** | `am start -a VIEW -d "geo:40.4168,-3.7038?z=15"` abre Madrid z15 con ficha de lugar; `geo:-18.1416,178.4419?z=12` abre Suva |
| Intent de ruta `comaps://route?...` | **Funciona** (CoMaps-específico; `type=vehicle/pedestrian/bicycle`) | `traces/11-*`, `12-*` |
| Intent de búsqueda `comaps://search?query=..&map` | **Funciona** | `traces` (sesión de prueba) |
| Perfiles | Coche, a pie, bici, transporte público y «regla/línea recta». **No hay perfil moto** | Selector de la UI de ruta (capturas vistas, no guardadas); rutas a pie y bici medidas arriba |
| Evitar autopistas / peajes / ferris / caminos sin pavimentar (coche); ferris/sin pavimentar/escaleras (pie, bici) | **Existen en la UI** y llegan al router (`Avoid next roads: RoutingOptions: { toll }` / `{ motorway }`) | Pantalla «Opciones de la ruta» (captura vista); `traces/14-*`. Con «evitar autopistas» la ruta Madrid–Barcelona se recalculó y la UI mostró **691 km, 11 h 25 min** (frente a 621,8 km, 6 h 7 min) tras ~3-4 min de cálculo (el log filtrado no incluye el `Route found`; dato leído de captura no guardada). «Evitar peajes» devolvió la misma ruta (probablemente la ruta por defecto ya no usaba peaje) |
| Importar GPX | **Funciona** con `content://` (`am start --grant-read-uri-permission -t application/gpx+xml -d content://media/external/file/<id>`): «Importing bookmarks from content://…», guardado como KML en `files/bookmarks/test-spike.kml`, y el track se dibuja en el mapa. Con `file:///sdcard/…` falla por `EACCES` (almacenamiento con ámbito, esperado) | `traces/15-gpx-import*.logcat.txt`, `test-spike.gpx` |
| Exportar GPX | **No probado** (requiere UI de compartir/guardar) | — |
| Favoritos | **Funciona** (guardar un lugar desde la hoja de lugar → «Mis lugares», botón pasa a «Eliminar») | UI vía adb, captura vista |
| Carriles (lane guidance) | **No probado en ejecución.** Soporte en el código: `routing/lanes/lane_info.hpp`, `FollowingInfo::m_lanes` | Revisión de fuente |
| Límites de velocidad | **No probado en ejecución.** Soporte en código: `SpeedLimitView`, preferencia `pref_speedlimit`, `speed_camera_manager` | Revisión de fuente |
| Navegación guiada | **No lograda en el spike.** Se montó una simulación con provider GPS de prueba (`nav_sim.py`, `cmd location providers`) y la UI de ruta, pero la app tomó la ubicación real del teléfono (no la simulada) y la navegación solo está disponible «desde la ubicación actual»; no se llegó a ver carriles/límites | `nav_sim.py` |

## 5. Límites de las medidas (leer antes de usar las cifras)

- **Dispositivo compartido:** otros agentes lo usan (el lock `flock` solo serializa adb, no la carga de CPU/memoria). Las rutas largas mostraron variaciones de 16,5 a 26,2 s. Las cifras de rutas y búsqueda son probablemente pesimistas; las de arranque y fps, estables (n=10, ráfagas de 1200+ frames).
- Un único dispositivo (Pixel 8, gama alta): ningún umbral de gama media se ha podido evaluar.
- Build `RelWithDebInfo` (-O2, ThinLTO) con `-g`; no es un release de Play, pero es el mismo código nativo optimizado.
- Los binarios de Perfetto y las capturas no se guardan (tamaño/privacidad: algunas capturas mostraban la ubicación real del teléfono). Los JSON de `traces/09-*` y los logcat filtrados a `OMcore` son la evidencia.
- La cifra de la primera búsqueda en frío (6705 ms) y la del «evitar autopistas» (691 km) vienen de sesiones cuyo log/captura no se conservó íntegro; se marcan como tales.

## 6. Lectura para la decisión A/B/C (solo de este flujo)

- Compila, instala y funciona sin GMS; el mapa a 120 fps constantes en pan/zoom/giro en un Pixel 8 es sólido; el arranque cumple con margen.
- **Búsqueda (~0,6 s por tecla) y ruta larga (~17 s, 8x el umbral) no cumplen los umbrales** con el motor tal cual en este dispositivo; la ruta urbana (~0,4-1,4 s) sí.
- Riesgo a vigilar: coste de enrutado de larga distancia (modo LeapsOnly) y RAM (1,3 GB RES con evitar autopistas).
- Perfil moto: no existe; habría que añadirlo.
