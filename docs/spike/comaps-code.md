# Spike (b): estudio del código de CoMaps (solo lectura)

Fuente: `~/repos/comaps-spike`, shallow en el tag `v2026.10.05-19` (fuera de este repo; no se ha modificado ni copiado). Las rutas `libs/...`, `android/...`, `3party/...`, `data/...` son relativas a ese árbol.

Convención de evidencia:

- **[L]** leído en código, con `archivo:línea`.
- **[I]** inferido de lo leído (puede fallar; se dice por qué).
- **[N]** no verificado. Este informe es solo lectura de código: no se ha compilado, ejecutado ni medido nada (fps, latencias, arranque). Esas cifras son del flujo de medición.

Los costes en días son estimaciones mías de persona-días de ingeniería con el código ya compilando, no mediciones.

## 1. Veredicto de desacople

**Viable. Coste estimado: 15-20 días para una pantalla Compose que busque y calcule rutas sin la actividad de CoMaps; 30-45 días si además se quiere moto con curvas, servidor de mapas propio y capa de red propia (secciones 4-8).** Confianza: media-alta en la viabilidad (el desacople ya existe en la estructura del proyecto), media en el coste (no he compilado ni probado el arranque de `Framework` sin `app/`).

Por qué es viable [L]:

1. Gradle ya separa `:app` (UI) de `:sdk` (núcleo + JNI): `android/settings.gradle.kts:20-21`. `:sdk` es `com.android.library` (`android/sdk/build.gradle.kts:6`) y compila el CMake raíz con el target `organicmaps` (`android/sdk/build.gradle.kts:36-40`, `:92-96`). La UI de CoMaps (`:app`: 279 ficheros Java/Kotlin, 44 630 líneas) depende de `:sdk` (`android/app/build.gradle.kts:360`), no al revés.
2. `:sdk` no importa `com.google.android.gms` ni `org.microg` (grep sobre `android/sdk/src`: sin resultados). Los imports GMS están solo en `android/app/src/google/...` (p. ej. `LocationProviderFactoryImpl.java:10-11`).
3. Todo el JNI de búsqueda y routing llega a una sola fachada C++, `::Framework` (`libs/map/framework.hpp`), vía `g_framework->NativeFramework()` (`android/sdk/src/main/cpp/app/organicmaps/sdk/search/SearchEngine.cpp:278`) y `frm()->GetRoutingManager()` (`.../sdk/Framework.cpp:1301-1316`).
4. `Framework` se construye sin motor de dibujo: el constructor (`libs/map/framework.cpp:322-377`) inicializa clasificador, país, `SearchAPI` y bookmarks, y `m_drapeEngine` solo se crea con `CreateDrapeEngine(...)` (`libs/map/framework.hpp:505`). El código del routing protege el uso del motor: `m_drapeEngine.SafeCall(...)` (`libs/map/routing_manager.cpp:431, 440, 585`) y `if (m_drapeEngine != nullptr)` en `framework.cpp:188, 238, 244`. [I] Por tanto búsqueda y cálculo de ruta funcionan sin superficie de mapa; el dibujo de la ruta y el seguimiento de cámara sí requieren Drape.
5. Hay una vía aún más baja que evita `Framework`: `IndexRouter` se construye con `DataSource`, `CountryInfoGetter`/callbacks y `NumMwmIds` (`libs/routing/index_router.hpp:69-72`) y es un `IRouter` (`libs/routing/router.hpp:46-75`). Los tests de integración lo usan así [L: `libs/routing/routing_integration_tests/routing_test_tools.cpp:62`]. Útil si se quiere un `RoutingEngine` sin `RoutingManager`, pero se pierde `RoutingSession` (guiado, recálculo, voz).

Riesgos del desacople [L/I]:

- **Singleton global**: `g_framework` / `frm()`; un solo `Framework` por proceso. Compatible con una app de una sola actividad, no con tests en paralelo.
- **`Framework` es un monolito** (1932 líneas solo de JNI en `Framework.cpp`; 114 funciones `JNIEXPORT`): arrastra bookmarks, editor OSM, tráfico, transporte, isolíneas. Compilarlo entero es obligatorio (sin partir `libs/map`); el binario no se adelgaza. [I]
- **Los puntos de ruta son `UserMark`s** ligados al `BookmarkManager` (`routing_manager.hpp:143, 240`), así que el routing no es utilizable sin bookmarks inicializado. [I]
- **El `:sdk` Java sigue arrastrando androidx UI** (`material`, `fragment`, `preference`, `recyclerview`: `android/sdk/build.gradle.kts:117-127`) y ~17 000 líneas de Java con estado estático. Aceptable; es Apache-2.0.
- **Datos a pie de arranque**: `Platform` necesita los recursos de `data/` (clasificador, `drules_proto*.bin`, fuentes, `countries.txt`) y `World.mwm`; en la app van como assets enlazados por sabor (`android/app/src/google/assets/World.mwm` es un symlink). [L/I]

### API mínima a exponer a Kotlin/Compose

Casi toda existe ya como `native` estático en `:sdk` y puede llamarse desde Kotlin tal cual. Propuesta de fachada (nuestras interfaces `core-*`):

| Interfaz nuestra | Qué llama en CoMaps [L] | Notas |
| --- | --- | --- |
| `Core.init(context, dataDir, onReady)` | `OrganicMaps.nativeInitPlatform` / `nativeInitFramework(Runnable)` (`sdk/.../OrganicMaps.java:237, 241`) | Asíncrono; hay que mapear el callback a una corrutina. |
| `SearchEngine.search(query, lang, pos) : Flow<Results>` | `SearchEngine.nativeRunSearch(bytes, isCategory, lang, timestamp, hasPosition, lat, lon)` -> `SearchAPI::SearchEverywhere` (`SearchEngine.cpp:269-282`); resultados por `onResultsUpdate` / `onResultsEnd` (`:231-267`) | El timestamp descarta resultados viejos. `nativeCancelEverywhereSearch` en `:350`. Resultados con `FeatureId` y `Description` (distancia, abierto ahora). |
| `RoutingEngine.setPoints / build / follow / close` | `nativeAddRoutePoint` (`Framework.cpp:1537`), `nativeBuildRoute` (`:1304`), `nativeFollowRoute` (`:1314`), `nativeCloseRouting` (`:1299`), `nativeSetRoutingListener` (`:1491`) | Tipo de router por `RouterType` (`libs/routing/router.hpp:35-42`): coche, a pie, bici, transporte, regla. |
| `RoutingEngine.options(avoid...)` | `RoutingOptions` + `RoutingOptions.java`/`.cpp` | Ver sección 3. |
| `NavigationState : StateFlow<FollowingInfo>` | `nativeGetRouteFollowingInfo` (`Framework.cpp:1349`) -> `RoutingInfo.java:81-86` (carriles, `speedLimitMps`, `speedCamLimitExceeded`) | Hay que sondear (no hay push); el sondeo va al `Service` de navegación. |
| `VoiceGuide` | `nativeGenerateNotifications(announceStreets)` (`Framework.cpp:1324`) devuelve las frases a pronunciar; `TtsPlayer` reproduce | Ver sección 7. |
| `MapEngine` | `MapView` (`sdk/.../MapView.java:24`, un `SurfaceView`) dentro de `AndroidView` de Compose; `Map.java` y `MapController.java` para ciclo de vida | Es lo que mantiene la dependencia del renderizado de CoMaps (opción A). |
| `LocationSource` | `AndroidNativeProvider` (`sdk/.../location/AndroidNativeProvider.java`), sin GMS | Ver sección 6. |

Desglose del coste (días) [I]:

| Tarea | Días |
| --- | --- |
| Proyecto Gradle propio que consume `:sdk` (sin `:app`, sin sabores, NDK/CMake, submódulos) | 3-5 |
| Fachada Kotlin `Core/Search/Routing/Navigation` sobre los `native` y puentes a `Flow` | 6-8 |
| `MapView` en Compose + ciclo de vida de la superficie | 3-4 |
| Pantalla mínima: caja de búsqueda, mapa, ruta A->B, panel de guiado con ruta simulada | 3-4 |
| **Total (sin moto/curvas/red)** | **15-21** |

## 2. Capas C++ (qué depende de qué)

| Capa | Dónde | Observación [L] |
| --- | --- | --- |
| Búsqueda | `libs/search/` (~150 ficheros) | `SearchAPI` en `libs/map/search_api.hpp:85` (`SearchEverywhere`); el motor es `search::Engine` (`libs/search/engine.cpp`), independiente de Drape. |
| Routing | `libs/routing/`, `libs/routing_common/` | `IndexRouter` (A* sobre grafo de segmentos por mwm), `RoutingSession` (guiado), `EdgeEstimator`, `VehicleModel`. Sin dependencia de Drape. |
| Orquestación | `libs/map/framework.*`, `routing_manager.*` | Aquí está el acoplamiento con Drape (render de ruta, cámara). `RoutingManager::Delegate` solo exige `OnRouteFollow` y `RegisterCountryFilesOnRoute` (`routing_manager.hpp:89-96`). |
| Almacenamiento | `libs/storage/` | Descarga de regiones (sección 8). |
| JNI | `android/sdk/src/main/cpp/app/organicmaps/sdk/` (10 583 líneas C++) | Una clase Java con `native` por área (`Framework`, `SearchEngine`, `Router`, `Map`, `MapManager`...). |

## 3. Routing: perfiles, evitar, carriles, límites

### Perfiles de vehículo

- `VehicleType` = `Pedestrian, Bicycle, Car, Transit, Decoder` (`libs/routing/vehicle_mask.hpp:10-18`). **No existe moto.** El grep de `motorcycle`/`motorbike` en `libs/routing`, `libs/routing_common` y `libs/map` solo encuentra iconos de POI (`bookmark_helpers.cpp:100, 149`, `search_mark.cpp:238`).
- `RouterType` = `Vehicle, Pedestrian, Bicycle, Transit, Ruler` (`router.hpp:35-42`), mapeado en `routing_manager.cpp:216-225`.
- Cada perfil tiene `VehicleModel` (velocidades/permitidos por `highway=*`: `routing_common/car_model.cpp:30-42`, `bicycle_model.cpp`, `pedestrian_model.cpp`) y un `EdgeEstimator` propio (`edge_estimator.cpp:603` Pedestrian, `:638` Bicycle, `:726` Car; selección en `:817-838`).
- **Moto = trabajo nuevo.** Opción barata [I]: reutilizar los datos del coche (los mwm guardan acceso, restricciones y penalizaciones por `VehicleType`: `index_graph_loader.cpp:210-245`, `road_penalty.hpp` "Number of vehicle types") con un `MotorcycleEstimator` y un `RouterType` nuevo cuyo `GetVehicleType` devuelva `Car` para cargar datos. Evita regenerar los mwm. Una moto real (autopista permitida en más sitios, sendas no) se aproxima ajustando velocidades en un `CarModel` derivado. Coste: 5-8 días.
- Una nueva `VehicleType` de verdad exigiría ampliar `Count` y la serialización de `road_access`/`road_penalty` en los mwm y regenerar datos: no recomendable.

### Evitar autopistas, peajes, ferris, sin asfaltar

- `RoutingOptions::Option` = `Usual, Toll, Motorway, Ferry, Dirty, Steps, Paved` (`routing_options.hpp:17-27`). Máscaras: peatón y bici `Ferry+Dirty+Steps+Paved` (`:32-33`); vehículo `Toll+Motorway+Ferry+Dirty+Paved` (`:34`).
- Se aplican como **exclusión dura**: `RoadGeometry::SuitableForOptions` (`geometry.hpp:80-83`) y su uso en `index_graph.cpp:260, 285` y `single_vehicle_world_graph.cpp:217`. Es decir, "evitar peajes" prohíbe la arista, no la penaliza; en zonas donde solo hay peaje el cálculo falla. [I] Un modo "preferir evitar" requeriría convertirlo en penalización en `CalcSegmentWeight` (ver curvas): 3 días.
- Hay modelo de superficie: `kCarSurface` con factores `paved_good/paved_bad/unpaved_good/unpaved_bad` (`car_model.cpp:82-87`).
- Opciones persistidas con `settings` y expuestas por `RoutingOptions.java`/`RoutingOptions.cpp` (JNI, 57 líneas).
- Paradas intermedias: `RouteMarkType` con puntos intermedios (`routing_manager.hpp:240-250`), `Checkpoints` (`router.hpp`).

### Carriles (lane guidance)

- Soportado en el núcleo [L]: los carriles se leen de los metadatos `FMD_TURN_LANES(_FORWARD/_BACKWARD)` de la feature (`directions_engine.cpp:45-55`), se parsean en `routing/lanes/lanes_parser.cpp:56`, se recomiendan (`lanes_recommendation.cpp`, llamado en `car_directions.cpp:119`) y llegan en `FollowingInfo::m_lanes` (`following_info.hpp:53`) y en Java `RoutingInfo.lanes` (`RoutingInfo.java:81`). Solo para coche (`car_directions`); a pie no.
- Calidad dependiente de que el mwm lleve esos tags [I]; hay que comprobarlo en Madrid centro con el mapa real (flujo de medición).

### Límites de velocidad

- Hay sección `maxspeeds` en el mwm (`maxspeeds_serialization.hpp:31`), usada por el modelo de velocidad (`geometry.cpp:105, 185`).
- Durante el guiado: `RoutingSession::GetCurrentSpeedLimit` (`routing_session.hpp:101`, rellena `FollowingInfo::m_speedLimitMps` en `routing_session.cpp:484`, `-1` si no hay dato: `following_info.hpp:96-97`) y llega a Java en `RoutingInfo.speedLimitMps` (`RoutingInfo.java:86`).
- **El "aviso al superar el límite" de nuestro RF-05 no viene hecho**: `speedCamLimitExceeded` solo se refiere a cámaras (`speed_camera_manager.hpp`, modo `Auto/Always/Never` en `:30-37`). La comparación velocidad-límite la hace la UI. Coste: 1 día en Kotlin. [L/I]
- Cámaras de velocidad: soportadas (`speed_camera*.cpp`).

### Rutas con curvas (sinuosidad)

Veredicto: **hay una vía realista, con trabajo de C++ (6-10 días).** Evidencia:

1. **El coste de arista es editable en un punto limpio.** `EdgeEstimator` es una clase abstracta con `CalcSegmentWeight(Segment, RoadGeometry, Purpose)`, `GetTurnPenalty(...)`, `GetUTurnPenalty` virtuales (`edge_estimator.hpp:50-54`). `Purpose` distingue `Weight` (lo que optimiza A*) de `ETA` (el tiempo mostrado) (`:26-30`), así que el peso puede divergir del tiempo estimado sin falsear el ETA. [L]
2. **La geometría está disponible en el cálculo**: `RoadGeometry::GetPoint(i)`, `GetPointsCount()`, `GetDistance(segIdx)` (`geometry.hpp:59-64`) y `GetHighwayType()` (`:48`). Se puede calcular curvatura local (ángulo entre segmentos adyacentes) o sinuosidad por vía (longitud/cuerda) dentro de `CalcSegmentWeight`. [L: API; I: que el coste en CPU sea aceptable]
3. **Restricción de A\***: la heurística usa velocidad máxima del modelo (`EdgeEstimator::CalcHeuristic`, `edge_estimator.cpp:523`; `m_maxModelSpeed` en `car_model.cpp:109-112`). Mientras los cambios solo **aumenten** el peso (penalizar tramos rectos y rápidos, no premiar curvas), la heurística sigue siendo admisible. Un "premio" (peso negativo) rompería el A*. [I, razonamiento sobre el algoritmo; no lo he ejecutado]
4. **Nivel configurable**: un factor por tipo de vía y por sinuosidad, parametrizado desde Kotlin (un `std::atomic`/struct de ajustes leído por el estimador). Hay precedente de tabla configurable de penalización de giro por par de tipos de vía (`m_turnPenaltyMap`, `edge_estimator.hpp:68`; datos en `edge_estimator.cpp:202-261`).
5. **Pasar por vías forzadas**:
   - Puntos intermedios (checkpoints) ya soportados (`routing_manager.hpp:240`). Funciona como "vía de paso" puntual, no como "usa esta carretera".
   - Existe además un mecanismo de **"guides" (pistas GPS a seguir)**: `GuidesTracks` (`router.hpp:23`), `AsyncRouter::SetGuidesTracks` (`async_router.cpp:182, 297`), `IndexRouter::SetGuides` (`index_router.cpp:343`), conexión pista-OSM (`:417-487`). **No está cableado en `RoutingManager`** (solo `RoutingSession::SetGuidesForTests`, `routing_session.hpp:176`). [L] Cablearlo permitiría "sigue este GPX con curvas y reengancha al OSM": 3-5 días, riesgo medio (código de uso poco probado, [I]).
6. **Alternativa sin tocar el núcleo**: generar la ruta con curvas fuera (otro algoritmo o una ruta GPX importada) y pasarla como guide o como lista de puntos intermedios.

Estimación: penalización de sinuosidad + perfil moto: 6-10 días; guides cableado: 3-5 días; calidad "rutas divertidas" real: iterativo, requiere pruebas en Guadarrama (flujo de medición).

## 4. Formato .mwm

- Contenedor de secciones (`FilesContainerR`, `MwmVersion`, `libs/platform/mwm_version.hpp:16-39`, formatos hasta `v8` y posteriores). Los mwm de CoMaps llevan secciones separadas: geometría por escala, índice de búsqueda, índice de routing por vehículo, `maxspeeds`, `cross_mwm`, altitudes, etc. (`libs/routing/*serialization*.hpp`). [L]
- **Atadura al esquema de versiones**: la app solo acepta mapas de su serie: `MAP_SERIES "2026.06.28"` (`private.h:22`), usada en la URL de descarga (`libs/platform/downloader_utils.cpp:28`) y en la comprobación de versión (`libs/storage/storage.cpp:358, 398`). [L]
- Los mwm los genera el `generator` de CoMaps desde OSM (`generator/`, `tools/python/maps_generator`, `docs/MAPS.md`). Producir los nuestros exige ese pipeline; reutilizar los oficiales evita el coste pero nos ata a su calendario. El generador está en el árbol (no hay que escribirlo), pero ejecutarlo para el mundo es costoso en CPU/disco/días [I; no medido].
- Mapas oficiales cubren el mundo; los estilos (`drules_proto*.bin`) se aplican en la app, así que cambiar el estilo **no** exige regenerar mwm salvo que cambie qué features existen o su rango de zoom (`docs/STYLES.md`: sección "Testing your changes"). [L]

## 5. Licencias

### Código propio de CoMaps

- **Apache-2.0** (`LICENSE:1-3`; copyright My.com, Organic Maps Contributors, CoMaps Contributors). Apache-2.0 es compatible con GPLv3 en sentido único: podemos incorporarlo en un trabajo GPLv3 (el conjunto resulta GPLv3). No es compatible con GPLv2-only. [I, criterio general de la FSF; no es asesoría legal]
- `NOTICE` avisa de que `3party/` y `tools/` están bajo licencias propias de cada una, y que algunos iconos pueden ser (C) My.com. La lista completa está en `data/copyright.html`.

### Dependencias (lista `data/copyright.html`; versiones por `.gitmodules`)

| Componente | Licencia declarada | Evidencia | Riesgo con GPLv3 / F-Droid |
| --- | --- | --- | --- |
| boost, expat, jansson, pugixml, glm, imgui, glaze, fast_obj, ankerl (MIT/Boost) | MIT/Boost | `copyright.html:148-240`; `3party/ankerl/unordered_dense.h` (SPDX MIT) | Bajo |
| FreeType | FTL (BSD con atribución) | `copyright.html:171` | Bajo (FTL es compatible con GPLv3, no con GPLv2) [I] |
| ICU | ICU License | `copyright.html:183` | Bajo |
| harfbuzz, utfcpp, glfw (zlib), minizip (zlib) | permisivas | `copyright.html` | Bajo |
| AGG | licencia propia permisiva ("Permission to copy, use, modify, sell and distribute... provided this copyright notice appears") | `3party/agg/agg_basics.h` cabecera; `copyright.html:149` | Bajo-medio: no es una licencia OSI estándar; registrar el texto en `LICENSES.md` |
| libtess2 | SGI Free Software License B 2.0 | `3party/libtess2/LICENSE.txt` | Bajo-medio: licencia permisiva no estándar |
| bsdiff/courgette | BSD | `3party/bsdiff-courgette/bsdiff/bsdiff.h` | Bajo |
| monocypher | BSD-2 / CC0 (doble) | `3party/monocypher/LICENCE.md` | Bajo |
| succinct, open-location-code, vulkan_wrapper, Vulkan-Headers | Apache-2.0 | cabeceras y `3party/open-location-code/LICENSE` | Bajo |
| robust (predicates.c, Shewchuk) | "Placed in the public domain" | `3party/robust/predicates.c:9` | Bajo-medio: dominio público declarado por el autor; algunas distribuciones lo tratan con cautela. Registrar la cita. |
| **kdtree++** | Artistic License (versión no indicada) | `copyright.html:191-192`; **el directorio `3party/kdtree++/` no incluye fichero de licencia ni cabecera de licencia** (`kdtree.hpp` abre sin ella) | **Medio: licencia declarada solo en un HTML.** Artistic 2.0 es compatible con GPLv3; Artistic 1.0 no lo es claramente (la FSF lo trata como no libre en su versión original). Hay que verificar la versión con upstream (libkdtree). [I] |
| **gb-postcode-data** (datos) | **GPL v2.0** | `copyright.html:256-257` | **Medio-alto si es GPLv2-only**: incompatible con GPLv3. Es un dato que entra en los mwm de GB, no en el binario, pero entra en la distribución de datos. No verificado si es "v2 only" o "v2+". |
| **Fuente Code2000** | **"Shareware"** | `copyright.html:323-324`; cargada en `libs/platform/platform.cpp:209` (`fonts/06_code2000.ttf`) y presente en `data/fonts/` | **Alto: no es software libre; incompatible con GPLv3/F-Droid.** Hay que retirarla y sustituirla (cobertura Unicode amplia: Noto). Coste: 1-2 días. |
| Khmer OS (fuente) | LGPL | `copyright.html:320` | Medio-bajo: LGPL en una fuente cargada en runtime; revisar excepción de fuente embebida |
| DejaVu, Roboto, Droid Sans Fallback, Jomolhari, Padauk | Bitstream/Apache/OFL | `copyright.html:66-79` | Bajo |
| Datos: US Zip Codes (CC BY 4.0), Code-Point Open y FHRS (OGL v3), Wikipedia (CC BY-SA 4.0), Mangrove (CC BY/BY-SA), SRTM/TIGER (dominio público), Sonny LiDAR (CC BY 4.0) | varias | `copyright.html:247-274` | Medio: obligaciones de atribución y de compartir igual en datos incluidos en los mwm. Hay que reflejarlas en la pantalla "Acerca de". Si los datos van dentro de los mwm hay que cumplir BY/BY-SA para esos datos. |
| OSM (datos) | ODbL | `copyright.html:98` | Atribución visible (ya es requisito RF-13) |
| androidx.car.app (Android Auto) y Material | Apache-2.0 | `android/gradle/libs.versions.toml:56, 67` | Bajo; el uso de Car App Library choca con F-Droid solo si arrastra GMS; decidir en la fase de Auto |

Conclusión de licencias: **ninguna bloquea el proyecto** pero hay tres acciones obligatorias antes de publicar: (1) quitar Code2000, (2) aclarar `gb-postcode-data` (GPLv2 sí/no) y decidir si se incluye ese dato, (3) verificar la versión de la Artistic License de `kdtree++` y añadir su texto al repo. Además, los submódulos de `3party/` no estaban inicializados en el árbol cuando lo leí (el otro agente los estaba descargando), así que las licencias de boost, expat, glm, etc. son las **declaradas** en `copyright.html`, **no las leídas en el fichero LICENSE del submódulo** [N].

## 6. Ubicación sin GMS

Hallazgo importante [L]: **el sabor `fdroid` de CoMaps no está libre de `play-services-*`.**

- `android/app/build.gradle.kts:375`: `implementation(libs.microg.services.location)` para **todos** los sabores, que resuelve a `org.microg.gms:play-services-location` (`android/gradle/libs.versions.toml:45`, versión `0.3.14.250932`, `:11`). Es la reimplementación libre de microG (Apache-2.0) con la API `com.google.android.gms.*`, pero el artefacto se llama `play-services-location`, y el `CLAUDE.md` de este proyecto prohíbe `play-services-*` en `foss`.
- `android/app/src/fdroid/java/app/organicmaps/location` es un symlink a `../../../../google/java/app/organicmaps/location`, es decir, el sabor fdroid compila `GoogleFusedLocationProvider` y `LocationProviderFactoryImpl` (importan `com.google.android.gms.common.GoogleApiAvailability` y `...location.FusedLocationProviderClient`).
- En ejecución elige GMS solo si `isGooglePlayServicesAvailable(...) == SUCCESS` **y** `Config.useGoogleServices()`; si no, `AndroidNativeProvider` (`LocationProviderFactoryImpl.java:19-33`).
- **El proveedor sin GMS ya existe y vive en `:sdk`**: `AndroidNativeProvider` usa `LocationManager` y comprueba `LocationUtils.FUSED_PROVIDER` primero (`sdk/.../location/AndroidNativeProvider.java:91`), con `GPS_PROVIDER` como respaldo (`:99`). Coincide con el diseño de `docs/mapas-03-arquitectura.md`.
- Consecuencia: si consumimos `:sdk` y **no** `:app`, nuestra app queda sin GMS por construcción. Coste de verificarlo y cablear `LocationSource`: 2-3 días. La verificación en dispositivos sin GMS y de-Googled es del flujo de medición [N].

## 7. Voz (TTS)

- El texto de cada indicación se genera en C++ (`RoutingManager::GenerateNotifications`, `libs/routing/turns_notification_manager.cpp`, `turns_tts_text*.cpp`) y se pasa a Java en `nativeGenerateNotifications` (`Framework.cpp:1324-1336`). El idioma lo fija `nativeSetTurnNotificationsLocale` (`android/sdk/src/main/cpp/app/organicmaps/sdk/sound/tts.cpp`).
- La reproducción es el `TextToSpeech` de Android (`sdk/.../sound/TtsPlayer.java:11, 210`) con enfoque de audio (`AudioFocusManager`). Sin GMS depende de que haya un motor TTS instalado. Si falla la inicialización marca `mUnavailable` (`:211-215`, `:280`). [L]
- **No he encontrado** en `TtsPlayer.java` un flujo para guiar al usuario a instalar un motor libre (eSpeak NG, RHVoice) ni una voz empaquetada (el grep de `INSTALL`, `getEngines`, `isLanguageAvailable` no devuelve nada). El `docs/mapas-03` lo pide; es trabajo nuestro: 2-3 días para detección + intent de instalación; voz propia empaquetada, 10+ días (no estimado a fondo).

## 8. Descargar mapas desde otro servidor

Hay soporte parcial, con una trampa de firma [L]:

- `Platform::SetCustomMapServerUrl` / `CustomMapServerUrl` (`libs/platform/platform.cpp:154-163`; JNI `Framework.cpp:1654`; UI en `DownloadResourcesLegacyActivity.java:284` con `CustomMapServerDialog`). Si hay URL propia, **se salta el metaserver** (`libs/storage/map_files_downloader.cpp:81-86, 192`).
- La estructura de URL es `<servidor>/<MAP_SERIES>/<dataVersion>/<fichero>` (`libs/platform/downloader_utils.cpp:28`). Respeta la ruta `/maps/YYMMDD/<región>.mwm` que asume `docs/mapas-03` solo en parte: el formato real lleva `MAP_SERIES/dataVersion`.
- Hay metaserver y lista de CDN **embebidos en `private.h`**: `METASERVER_URL "https://cdn-us-1.comaps.app"` (`:13`), `DEFAULT_URLS_JSON` con 7 espejos (`:14`), `COUNTRIES_TXT_SIGNATURE_HEX` (`:19`), `MAP_SERIES` (`:22`). Cambiarlos es editar un fichero (`private.h`), sin tocar la lógica.
- **Firma de `countries.txt`**: la lista de regiones se verifica con Ed25519 contra la clave pública de `private.h` (`libs/storage/storage.cpp:406-433`); sin firma válida se ignora la actualización. Un servidor propio debe, o bien reflejar los ficheros oficiales con su firma, o bien firmar con **nuestra clave** y recompilar con nuestra `COUNTRIES_TXT_SIGNATURE_HEX`. La clave privada no debe vivir en el repo (norma del `CLAUDE.md`).
- **Integridad de mwm**: SHA-1 (base64) por región comparado con el de `countries.txt` (`libs/storage/storage.cpp:1084`), no SHA-256 como pide `docs/mapas-03` (RF-02). Cambiar a SHA-256 es tocar C++ y el generador de `countries.txt`: 2-3 días.
- Documentación oficial para servidor propio: `docs/DEPLOY_OWN_MAP_SERVER.md` (herramientas de la comunidad que sirven los ficheros oficiales).
- Privacidad [L]: el cliente contacta con el metaserver y `libs/storage/pinger.cpp` existe y por su nombre y su inclusión de `http_client` hace sondeos de servidores [I; no leí su cuerpo]; ambos son salida de red que hay que auditar bajo `NetworkPolicy`. La red de Android pasa por Java: `sdk/.../util/HttpClient.java`, `sdk/.../downloader/ChunkTask.java` y `sdk/.../editor/OsmOAuth.java`. En C++ el cliente HTTP se implementa vía JNI (`sdk/src/main/cpp/.../platform/HttpThread.cpp`) y hay `editor/osm_auth.cpp`, `traffic/traffic_info.cpp` (URL vacía en `private.h`: `TRAFFIC_DATA_BASE_URL ""`). [I] Una `NetworkPolicy` central puede envolver esos tres puntos Java; 3-4 días. No he buscado telemetría exhaustivamente: no encontré alohalytics ni similares en `libs/` (grep de `alohalytics` sin resultados), pero no garantiza ausencia total [N].

Coste de servidor de mapas propio: 2-3 días de código (clave, `private.h`, SHA-256) + el coste operativo de generar o reflejar mwm (no estimado).

## 9. Resumen de costes (días, estimación)

| Bloque | Días |
| --- | --- |
| Desacople + fachada Kotlin + pantalla mínima (sección 1) | 15-21 |
| Moto (estimador, router type) | 5-8 |
| Sinuosidad + opciones "preferir evitar" blandas | 6-10 |
| Guides cableado (seguir pista GPS) | 3-5 |
| Alerta de exceso de velocidad (Kotlin) | 1 |
| TTS: detección y guía de instalación | 2-3 |
| Servidor propio, firma, SHA-256 | 2-3 |
| `NetworkPolicy` envolviendo HttpClient/ChunkTask/OsmOAuth | 3-4 |
| Limpieza de licencias (Code2000, kdtree++, GB postcodes) | 2-3 |
| **Total orientativo** | **~40-58** |

Lo que **no** está en este informe y decide la opción A/B/C: fps, arranque, latencia de búsqueda y tiempo de ruta en dispositivo (flujo de medición), y el parecido visual con Apple Maps (ver `comaps-style.md`).
