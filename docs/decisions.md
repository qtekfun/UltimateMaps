# Registro de decisiones

Formato: fecha · decisión · motivo · alternativas descartadas · cómo revertirla.

## 2026-10-06 · Bootstrap del repo en local, sin push a `master`
- **Decisión:** `master` se crea solo en local (commit inicial con `docs/` y `CLAUDE.md` copiado de `docs/mapas-CLAUDE.md`). No se empuja nada hasta que el usuario cree `master` en el remoto y configure CI y protección de rama.
- **Motivo:** el remoto `origin` está vacío (sin `master`), así que no se pueden abrir PR. Empujar a `master` está prohibido por CLAUDE.md y crear `.github/workflows/` o la protección de rama es caso de «Cuándo preguntar» nº 5.
- **Descartado:** empujar un commit inicial a `master` (viola el flujo); empujar una rama de spike (GitHub la haría rama por defecto).
- **Revertir:** `git push origin master` tras acordarlo con el usuario; las ramas `spike/*` locales se pueden empujar y abrir como PR a partir de ese momento.

## 2026-10-06 · No se instala `.claude/settings.json` ni `.github/workflows/ci.yml`
- **Decisión:** `mapas-claude-settings.json` y `mapas-ci.yml` quedan sin tocar en la raíz.
- **Motivo:** el primero cambia mis propios permisos y el segundo es un workflow (CLAUDE.md, «Cuándo preguntar» nº 5).
- **Revertir:** copiarlos a `.claude/settings.json` y `.github/workflows/ci.yml` (paso 2 de `docs/mapas-README.md`).

## 2026-10-06 · El código fuente de CoMaps vive fuera del repo
- **Decisión:** se clona en `~/repos/comaps-spike/` (fuera del repo), fijado a un tag estable; el repo solo guarda scripts y resultados en `spike/` y `docs/spike/`.
- **Motivo:** instrucción explícita del usuario; evita mezclar Apache-2.0 con el código propio antes de decidir.
- **Descartado:** submódulo (válido, pero añade ruido a un spike descartable).
- **Revertir:** `git submodule add` más adelante si se elige A o C.

## 2026-10-06 · Dispositivos y concurrencia en el spike
- **Dispositivo disponible:** un único Pixel 8 (Android 17, SDK 37, arm64, 120 Hz, con GMS). Cubre solo la clase «gama alta con GMS».
- **Decisión:** el acceso al dispositivo se serializa con `flock`; compilaciones C++ limitadas a `-j6` por la RAM libre (~5 GB de 30 GB al empezar).
- **Consecuencia:** gama media/baja, ROM china sin GMS y de-Googled quedan «no medido». Sin esas clases no se puede decidir el criterio «Sin GMS» ni los umbrales de gama media.

## 2026-10-06 · La rama principal se llama `master`
- **Decisión:** la rama principal es `master` (no `main`), por indicación del usuario. Donde los documentos del paquete dicen `main` (playbook, CI, `settings.json`), léase `master`.
- **Pendiente para el usuario:** el workflow `mapas-ci.yml` (`branches: [main]`), las reglas deny de `mapas-claude-settings.json` (`git push origin main *`) y la protección de rama deben apuntar a `master`.

## 2026-10-06 · Máximo 4 subagentes simultáneos
- **Decisión:** nunca más de 4 subagentes activos a la vez; el flujo MapLibre (d) se lanza cuando termine uno de los cuatro iniciales.
- **Motivo:** instrucción del usuario (y RAM limitada, ~5 GB libres).
- **Revertir:** solo por indicación del usuario.

## 2026-10-06 · Licencias heredadas de CoMaps que bloquean enlazarlo tal cual en una app GPLv3
- **Decisión:** antes de reutilizar código de CoMaps (opciones A/C) hay que excluir o reemplazar `3party/bsdiff-courgette/bsdiff` (BSD Protection License, GPL-incompatible), la fuente `data/fonts/06_code2000.ttf` (shareware) y los iconos Entypo (CC BY-SA 3.0). Se fija «GPLv3 o posterior», nunca GPLv2-only.
- **Motivo:** ver `docs/spike/verificaciones.md` §1.2. Apache-2.0 sí es compatible con GPLv3.
- **Descartado:** asumir que todo `3party/` es permisivo.
- **Revertir:** si el autor de bsdiff o un asesor legal confirma compatibilidad, retirar la exclusión.

## 2026-10-06 · Código independiente del motor: módulos JVM y parsers sin StAX
- **Decisión:** `:core-geo`, `:core-net`, `:core-map`, `:core-search` y `:core-routing` son módulos Kotlin/JVM (`./gradlew test` sin Android); solo `:app` es Android (sabor `foss`, minSdk 26, `applicationId` provisional `com.qtekfun.mapas`). XML (GPX/KML) con `org.xmlpull.v1.XmlPullParser` (plataforma en Android; kXML2 solo en tests JVM), no StAX, porque `javax.xml.stream` no existe en Android. Takeout GeoJSON con kotlinx-serialization-json; CSV con lector propio.
- **Motivo:** tests rápidos y código conservable con cualquier motor (A/B/C). Si el motor elegido exige que `:core-map` sea módulo Android, se convierte entonces.
- **Detalles:** kXML2 acepta ficheros truncados sin error, por lo que los importadores comprueban `depth == 0` al final. Apple `?ll=...&q=Nombre` se interpreta como pin con etiqueta (no como búsqueda). Un `geo:0,0?q=texto` es búsqueda sin sesgo de posición.
- **Descartado:** StAX (no portable a Android); `org.json` (no disponible en JVM puro).

## 2026-10-06 · Motor (A/B/C): sin decisión firme; recomendación provisional C
- **Decisión:** no se declara A, B ni C como decidida. Recomendación provisional C (MapLibre + PMTiles para el render, núcleo de CoMaps para búsqueda/routing). No se inicia la Fase 1.
- **Motivo:** ruta Madrid–Barcelona 17,8-18,0 s en reposo (umbral 2 s) y búsqueda 631 ms (umbral 100 ms, medida con carga) no cumplen; faltan sin-GMS, gama media, desacople en ejecución. La regla de C exige que el motor pase. Ver `docs/spike-informe.md`.
- **Descartado:** A (umbrales y dependencia de microG); B (sin evidencia de bloqueo de desacople o licencias, y coste del pipeline mundial; sin medir Valhalla); decidir en firme con datos críticos ausentes.
- **Revertir/cerrar:** el usuario elige A/B/C o aporta dispositivos y se repiten las mediciones pendientes (informe, «Qué falta»).

## 2026-10-06 · Se integran las ramas del spike en `master` local con squash
- **Decisión:** `spike/*` y `feat/core-geo-skeleton` se integran en `master` local sin PR (no hay remoto ni CI). 83 tests pasados con `./gradlew test --rerun-tasks` como único «check». Los ficheros `mapas-ci.yml` y `mapas-claude-settings.json` quedaron versionados en la raíz como texto (no activan nada).
- **Revertir:** `git reset --hard 559cf42`... (solo local; las ramas originales siguen existiendo).

## 2026-10-06 · El Pixel 8 solo se usa con permiso explícito del usuario
- **Decisión:** ningún comando `adb` contra el Pixel 8 (instalar, medir, `am`, `dumpsys`, `input`, etc.) sin permiso explícito del usuario en cada ocasión.
- **Motivo:** instrucción del usuario.
- **Consecuencia:** las mediciones pendientes (búsqueda en reposo, carriles, MapLibre con SurfaceView) esperan a ese permiso.
- **Revertir:** solo por indicación del usuario.

## 2026-10-06 · Motor decidido por el usuario: opción C (híbrido)
- **Decisión:** opción C. Render con MapLibre Native + PMTiles; búsqueda, routing y datos mundiales con el núcleo de CoMaps (`.mwm`), sin su actividad ni su UI. Se inicia la Fase 1. Sustituye a la «recomendación provisional» anterior.
- **Motivo:** decisión explícita del usuario («C, implementalo»), tras el informe del spike.
- **Riesgos que arrastra (no resueltos):** ruta larga ≈ 18 s y búsqueda ≈ 0,6 s del núcleo de CoMaps (R12), sin medir sin GMS ni gama media (R16), licencias heredadas (R11), doble descarga por región (R17).
- **Descartado:** A y B.
- **Cómo revertir:** cambiar de opción es caso de «Cuándo preguntar» nº 3 de CLAUDE.md. Las interfaces `MapEngine`/`SearchEngine`/`RoutingEngine` aíslan el motor.
- **Restricción vigente:** el Pixel 8 no se usa sin permiso explícito; Fase 1 se desarrolla con compilación y tests en el PC.

## 2026-10-06 · `:core-regions`: catálogo propio con SHA-256 y activación por manifiesto
- **Decisión:** módulo JVM `:core-regions` con catálogo propio (schema 1, dos assets por hoja, SHA-256) en lugar de consumir `countries.txt` (SHA-1, firma Ed25519) directamente. Activación atómica = renombrado de cada fichero verificado + sustitución atómica de `installed.json`. Detalles y mapeo en `docs/phase1/regions.md`.
- **Motivo:** RF-02 pide SHA-256 y la opción C necesita unir PMTiles y `.mwm` en una sola región.
- **Descartado:** reutilizar el catálogo firmado de CoMaps (obliga a nuestra clave Ed25519 y a recompilar; solo documentado, no implementado).

## 2026-10-06 · Almacenamiento local: androidx.sqlite directo, sin Room
- **Decisión:** `:core-data` es un módulo JVM puro que usa la interfaz `SQLiteDriver` de androidx.sqlite 2.7.0 (Apache-2.0) con un repositorio escrito a mano (`SqlitePlacesRepository`). Tests en el PC con `sqlite-bundled`; en Android la app inyectará el driver de `sqlite-framework`. Esquema versionado con `PRAGMA user_version`.
- **Motivo:** Room necesita KSP y un módulo Android (AGP 9 + Kotlin 2.4 sin verificar), lo que impediría probar en JVM; el esquema es pequeño. Mismas licencias, sin servicios propietarios.
- **Dedup:** sitios por nombre normalizado + posición a ~1 m; tracks por SHA-256 de nombre, tipo y geometría. Copia de seguridad: ZIP con `mapas-backup.json` (formato 1), restauración MERGE o REPLACE atómica. KML no distingue ruta/track: las rutas se reimportan como tracks.
- **Descartado:** Room KMP (riesgo de toolchain); JSON plano sin SQLite (sin consultas).
- **Revertir:** migrar a Room sobre el mismo esquema si hace falta; la interfaz `PlacesRepository` aísla el cambio.
## 2026-10-06 · `:app` visor: sistema de diseño propio, MapLibre y sin permiso INTERNET
- **Decisión:** `:app` usa Compose `ui`+`foundation` (sin Material) con tokens propios (`ui/theme/Theme.kt`), bottom sheet de 3 detents propio (`ui/sheet`, geometría pura testeada en JVM) y atribución OSM fija arriba a la izquierda (RF-13). Motor: MapLibre Native 13.6.1 (`MapLibreEngine`) con PMTiles de `filesDir/maps/` y estilos protomaps light/dark generados a plantilla (`@MAPDIR@`, `@PMTILES@`). Sprites y glyphs se copian de assets a `filesDir/map/` en el primer arranque (el motor nativo no lee `file://` bajo Android/data). El manifiesto quita `INTERNET`: el visor no puede abrir conexiones; se añadirá con la primera función bajo `NetworkPolicy` (descargas). `compileSdk` 37 (exigido por Compose 1.12), `targetSdk` 36. `:app` usa JUnit 4 (Robolectric 4.17, MIT, solo tests) y los módulos `core-*` siguen con Jupiter.
- **Enlaces cortos:** solo se avisa; no se resuelven ni se llama a `NetworkPolicy.authorize` (no se intenta ninguna conexión).
- **Pendiente:** sprites y glyphs NO están empaquetados (acceso bloqueado a su host durante este trabajo): ejecutar `scripts/fetch-map-assets.sh` (necesita red). Sin ellos el mapa no pinta iconos ni etiquetas. Rendimiento y arranque: no medidos (sin dispositivo). Filas de LICENSES.md añadidas.
- **Descartado:** Material3 (se pidió sistema propio), `play-services-location` (prohibido), `LocationComponent` de MapLibre (marcador propio por GeoJSON, sin trabajo por frame).
## 2026-10-06 · Núcleo de CoMaps como módulo nativo propio, sin Framework ni drape
- **Decisión:** `:native-comaps` compila search + routing + storage + indexer + platform de `third_party/comaps` (submódulo en `v2026.10.05-19`) con un CMake propio, sin `drape`, `drape_frontend`, `map` (Framework) ni bookmarks. Fachada C++ (`DataSource` + `search::Engine` + `IndexRouter`) y Kotlin que implementa `SearchEngine`/`RoutingEngine`. Solo arm64-v8a, sin LTO, `-j6` máximo. `Platform` headless sin red (la red es de Kotlin).
- **Licencias:** bsdiff-courgette no se compila (`mwm_diff` sustituido por un stub: sin diffs), Code2000 y Entypo fuera de los assets. Ver `LICENSES.md`.
- **Motivo:** consumir `:sdk` de CoMaps arrastra Framework, Drape, editor, bookmarks y 114 funciones JNI; `IndexRouter` + `search::Engine` están cubiertos por los tests de integración de CoMaps y dejan el binario en 7,7 MB.
- **Descartado:** compilar `libs/map` sin Drape (el constructor de Framework llama a `df::`); parchear CoMaps (el submódulo queda intacto).
- **No verificado:** ejecución (sin dispositivo permitido). Ver `docs/phase1/native-core.md`.

## 2026-10-06 · Integración de `feat/comaps-core-native` verificada solo en parte
- **Decisión:** se integra en `master` local. `./gradlew test --offline --rerun-tasks` da 153 tests en verde (repetido por mí). `assembleDebug` completo **no lo repetí** en `master`: falla porque `third_party/comaps` no está inicializado en este checkout; el agente lo compiló en su worktree (`libumcomaps.so` arm64 7,7 MB). El código nativo no se ha ejecutado nunca (sin dispositivo permitido).
- **Motivo:** no inicializar 2 GB de submódulos ni forzar un build largo con poca RAM sin necesidad; el siguiente paso útil es ejecutarlo.
- **Revertir:** `git revert` del commit de integración.

## 2026-10-07 · Pixel 8 cedido a otra sesión; pausa de las pruebas del núcleo
- **Decisión:** el usuario ordenó dejar de usar el teléfono («eres muy lento») y otra sesión (ultimateVE) lo usa para sus pruebas, con el lock `/tmp/pixel-device.lock`. Esta sesión no ejecuta ningún `adb` hasta nuevo permiso explícito.
- **Estado de la prueba del núcleo:** el banco de pruebas (`app/src/debug/.../CoreBenchActivity.kt`, solo debug) llegó a arrancar en el Pixel 8. Primer fallo real: `CoMaps init: File not found drules_proto_walking_light.bin`; corregido en `scripts/comaps-prepare.sh` y en la lista de assets. El segundo intento no llegó a dar resultados (el dispositivo quedó offline). **No hay cifras de búsqueda ni ruta del núcleo propio.**
- **En el móvil quedan** (no tocar sin avisar): `com.qtekfun.mapas` con `files/maps/madrid.pmtiles` y `files/maps-core/261004/` (World, WorldCoasts y 7 regiones, ≈ 0,8 GB).

## 2026-10-07 · Release automático en GitHub, como UltimateDeck
- **Decisión:** versión única `appVersion` en `gradle.properties` con `versionCode` derivado (0.1.0-rc.1 → 10001), firma por variables `UM_KEYSTORE_*`, `CHANGELOG.md` y `RELEASING.md`, y `.github/workflows/release.yml` disparado por tag `v*` (tag = `appVersion`, notas obligatorias, APK firmado + `.sha256`, `-rc.N` como pre-release). Referencia revisada: `~/repos/ultimatedeck` (`release.yml`, `RELEASING.md`, `app/build.gradle.kts`).
- **Diferencias a propósito:** el workflow **falla si falta el secreto de firma** (UltimateDeck publicaría un APK sin firmar); `lintFossRelease` en vez de `check` completo; sin minificar de momento; SHAs de las acciones fijados igual que en UltimateDeck.
- **Verificado:** `assembleFossRelease` genera `app-foss-release-unsigned.apk` con `versionCode=10001`, `versionName=0.1.0-rc.1` (`aapt2 dump badging`); YAML válido; la extracción de notas con `awk` funciona. **No verificado:** el workflow en GitHub (no hay remoto ni secretos).
- **Autorización:** el usuario pidió explícitamente este release automático; es la única parte de `.github/workflows/` que se toca. El CI (`ci.yml`) y la protección de rama siguen pendientes de él (CLAUDE.md, «Cuándo preguntar» nº 5).
- **Pendiente del usuario:** crear la clave y los secretos (`RELEASING.md`), `master` en GitHub, la regla de protección (hay una plantilla en `~/repos/ruleset-master.json`) y el primer tag.
- **Revertir:** borrar `.github/workflows/release.yml`.
## 2026-10-07 · M2/M3: búsqueda en producción y sitios guardados (rama `feat/mvp-search-places`)
- **Decisión:** `:native-comaps` pasa a `implementation` de `:app`. El núcleo se arranca en diferido (primera consulta o cambio de regiones), fuera del hilo principal, sobre `filesDir/maps-core/` mediante la interfaz `search.InstalledRegions` (`DirectoryInstalledRegions` escanea `<versión>/World.mwm` y cuenta regiones; el módulo de regiones puede aportar la suya a `PanelHost`). Consultas con debounce de 250 ms; una tecla nueva cancela la anterior y las llamadas nativas se serializan con un `Mutex` (un solo `Core` por proceso). Sin regiones: estado vacío y el núcleo no se arranca.
- **Latencia (para la prueba futura en el móvil, no ejecutada):** logcat `UMSEARCH` con `engine_ready_ms`, y por consulta `qlen`, `results`, `ms` y `first` (primera tras arrancar). Sin texto de consultas ni posiciones. Medida con `SystemClock.elapsedRealtime` alrededor de la llamada nativa (no incluye el debounce).
- **Sitios:** driver `sqlite-framework` (`AndroidSQLiteDriver`) en `databases/places.db`; la lista por defecto («Favoritos») guarda su id en SharedPreferences y se recrea si se borra. Importar/exportar GPX/KML con el selector de documentos (SAF: `OpenDocument`/`CreateDocument`, sin permisos de almacenamiento; formato por extensión y, si no, por contenido; tope de 32 MB). Marcadores de sitios guardados: capa de círculos propia en `MapLibreEngine` (`MapEngine.showMarkers`), sin sprites. Ordenar por distancia usa la última ubicación conocida en memoria o, si no hay, el centro de la cámara.
- **Pendiente:** el botón «Ruta» de la ficha solo avisa (M4); zoom fijo 15 al elegir un resultado (el núcleo no devuelve extensión); medir R12 y fluidez en el móvil.

## 2026-10-07 · El workflow de release prepara el núcleo de CoMaps
- **Decisión:** tras integrar M2, `release.yml` ejecuta `git submodule update --init third_party/comaps` y `scripts/comaps-prepare.sh` antes de compilar (el núcleo ya va en el APK de release).
- **Verificado:** `assembleFossRelease` local con el núcleo: OK, 2 min 51 s, APK sin firmar 42,3 MB. **No verificado:** el workflow en GitHub (sin remoto ni secretos), ni NDK/CMake/tiempo/RAM del runner.
- **Revertir:** quitar el paso «Preparar el núcleo de CoMaps» (el release dejaría de compilar).
## 2026-10-07 · M0 + M1 (rama `feat/mvp-regions`): assets del mapa, pantalla «Mapas» y descargas
- **Hallazgo M0:** `scripts/gen-map-style.mjs` no pasaba `lang` y `@protomaps/basemaps` **no genera capas de etiquetas sin él** (57 capas, 0 `symbol`): el mapa nunca iba a tener texto aunque hubiera glyphs. Ahora `lang=es` por defecto (cae a `name`, el nombre local); 71 capas, 14 `symbol`. Sprites v4 y 3 fuentes Noto Sans (rangos 0-255, 256-511, 8192-8703) empaquetados, ~1,3 MB (OFL/BSD-3). Los rangos y fuentes que no existen (p. ej. Devanagari) devuelven error de fichero y MapLibre solo omite esos glifos. No verificado en dispositivo (nombres de carpeta con espacios en `file://`).
- **Pin:** `LinkOutcome.pinPoint()`; `handleLink` siempre reemplaza o borra el pin (enlace corto/no reconocido/búsqueda lo borran).
- **`countries.txt` (campos reales, verificados):** raíz `{id:"Countries", v:261004, map_series:"2026.06.28", g:[…]}`; hojas `{id, s (bytes), sha1_base64, old, affiliations, country_name_synonyms?}`; grupos `{id, g}`. `v` es global. Los ids llevan espacios y hay 5 nodos repetidos bajo dos padres (Campo de Hielo Sur, Abkhazia, South Ossetia, Jerusalem, Crimea). `scripts/gen-region-catalog.py`: id propio = slug ASCII (`spain_community-of-madrid`), `comapsId` original como campo extra (el parser lo ignora), se conserva la primera aparición de cada nodo, SHA-256 de ficheros reales (`--mwm-dir`, `--pmtiles-dir`); una hoja solo es descargable con ambos ficheros; `--fetch-mwm` descarga UN .mwm bajo petición con tope de 20 MB. La URL base por defecto de los .mwm (`<servidor>/maps/<series>/<v>/<id>.mwm`) sale de la documentación, no se comprobó con red.
- **No hay servidor propio ni catálogo por defecto:** la app no trae ninguna URL. El usuario escribe la del catálogo en «Mapas»; su host (y los de las URLs de los assets del catálogo, que decide ese servidor) se añaden a `NetworkPolicy` como `MAP_DOWNLOAD` y quedan listados en «conexiones posibles». Sin servidor: cero conexiones.
- **INTERNET restaurado** (se quita el `tools:node="remove"`) solo para este flujo; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. «Modo sin red» persistido (`offline_mode`) y aplicado en `Application.onCreate`: probado con servidor local que no llega ninguna petición.
- **Servicio:** `RegionDownloadService` (dataSync) solo muestra la notificación y se para solo; el trabajo lo hace `RegionsController` (cola secuencial, pausa conserva el `.part`, reanuda con `Range`). Android 15: `onTimeout` pausa todo (límite ~6 h de dataSync).
- **Límites conocidos:** el estilo tiene una sola fuente PMTiles, así que con varias regiones instaladas solo se dibuja la primera por id (conviene un extracto PMTiles por país); instalar en tarjeta SD está permitido pero el motor nativo solo se sabe que lee de `filesDir` (aviso en la UI); si una actualización se interrumpe tras el primer fichero, ese fichero se vuelve a bajar (el `.part` ya se movió).
- **Alternativa descartada:** catálogo embebido en la APK (sin URLs de descarga fiables ni SHA-256 reales) y auto-descarga del catálogo al arrancar (la app no conecta sin una acción del usuario).

## 2026-10-07 · Datos de mapas alojados en GitHub Releases, un PMTiles por región
- **Decisión:** repositorio público `qtekfun/UltimateMaps-data` (creado a petición del usuario: «Dale a GitHub») con releases de datos. Primera release `data-261004-20261006`: 25 regiones de España, un `.pmtiles` y un `.mwm` por región (50 ficheros, 4,34 GB; el mayor, Castilla-La Mancha, 209 MB), más `World.mwm`, `WorldCoasts.mwm`, `catalog.json` y `SHA256SUMS`. Catálogo estable: `https://github.com/qtekfun/UltimateMaps-data/releases/latest/download/catalog.json`.
- **Motivo:** gratis, HTTPS y `Range`, sin cuentas nuevas, reversible (la app solo conoce la URL del catálogo). Partir por regiones deja todos los ficheros muy por debajo del límite de 2 GB y permite descargar solo lo necesario.
- **Cómo se hizo (reproducible):** `.mwm` copiados sin modificar del CDN de CoMaps (datos 261004); PMTiles con `scripts/split-pmtiles.py` (recorte con los polígonos `data/borders/*.poly` de CoMaps sobre el build de Protomaps 20261006); catálogo con `scripts/gen-region-catalog.py --mwm-url-by-slug` (SHA-256 de los ficheros reales). Datos locales en `~/mapas-data/` (fuera del repo).
- **Incidencia corregida:** Canarias, Ceuta y Melilla salieron casi vacíos al cortarlos de un extracto que no los cubría; se rehicieron desde el planeta completo (62, 1,5 y 1,7 MB).
- **Riesgos y avisos:** no hemos encontrado condiciones de uso del CDN de CoMaps (el README lo dice y ofrece retirar los `.mwm` a petición del proyecto); GitHub renombra los espacios de los nombres de asset, por eso las URL de los `.mwm` usan el slug; los datos son ODbL (atribución y compartir igual, en el README).
- **Lagunas conocidas (tareas pendientes, ver `docs/mvp-plan.md`):**
  1. **Enlazar lo descargado con el núcleo:** el catálogo instala el `.mwm` como `<slug>.mwm` en `<root>/<id>/<versión>/`, pero el núcleo exige `maps-core/<versión>/<comapsId>.mwm` (p. ej. `Spain_La Rioja.mwm`). Falta guardar `comapsId` en el modelo y crear el enlace simbólico al instalar y borrar.
  2. **`World.mwm` y `WorldCoasts.mwm`** están en la release pero no en el esquema del catálogo (no son una región).
  3. **URL del catálogo por defecto** en la app (hoy la escribe el usuario) y lista blanca de `NetworkPolicy` para `github.com` y los hosts de redirección de los assets.
  4. **Dibujado multi-región** (agente en curso): el estilo solo dibuja una región; las regiones se solapan en el borde.
- **Revertir:** `gh release delete data-261004-20261006` y borrar el repo de datos; la app no depende de él hasta que se fije la URL por defecto.
## 2026-10-07 · Render multirregión (rama `feat/multi-region-render`)
- **Decisión:** el estilo se genera en ejecución (`map/MultiRegionStyle.kt`) a partir de la plantilla de una fuente: una fuente `pmtiles://file://…` por región instalada (`protomaps-<i>`, orden por id de región) y una copia de cada capa con fuente por región, en orden capa-mayor (capa 1 de todas las regiones, luego capa 2…) para conservar el z-order entre regiones. Copia 0 con el id original, las demás `<id>@<i>`. `background` una sola vez. Sin regiones: sin fuentes, solo fondo (ya no apunta a un `none.pmtiles` inexistente). MapLibre Native (≥ 11.7, Context7) admite varias fuentes `pmtiles://` y `file://`.
- **Solape en bordes:** rellenos y líneas opacos repintan los mismos píxeles (inocuo). Las capas translúcidas (`buildings` 0,5, `landuse_urban_green` 0,7, `roads_rail` 0,5; `landcover` solo entre z5 y z7) se ven más densas en la franja de solape. Las etiquetas duplicadas (mismo texto y sitio) colisionan entre sí y solo se coloca una (no usan allow-overlap). No se mitiga más: no se sabe qué región «gana» sin conocer los polígonos; pendiente de verlo en el móvil.
- **Coste (estimación, NO medida):** 71 capas por fuente (41 line, 15 fill, 14 symbol, 1 background) → 1 + 70·N capas: 3 regiones = 211, 10 = 701, 25 = 1751 (tope `MAX_SOURCES` = 25; el resto se omite y se registra). El estilo crece ~lineal (≈ 0,3 MB de JSON con 25). Solo cuestan por fotograma las fuentes con teselas en el viewport (normalmente 1–3), pero la lista de capas y la reconciliación de estilo sí crecen con N, y la carga del estilo en el arranque también. Sin dedupe de earth/water: cada extracto solo contiene su polígono, así que una fuente no puede cubrir a las demás. Opciones si mide mal: un PMTiles de baja resolución mundial (z0–z5) como fuente única de tierra/agua más extractos solo desde z6; o más de una región por extracto (país/comunidad).
- **Métrica:** logcat `UMSTYLE` en cada carga de estilo: `sources`, `layers`, `template_layers`, `skipped`, `json_kb`, `build_ms`, `style_load_ms` (sin rutas ni ubicaciones). Fluidez con N regiones: no medida.
- **Recarga:** `refreshTilesIfChanged` compara una firma (ruta + tamaño + mtime de cada PMTiles) en lugar de la primera ruta; al volver de «Mapas» (`onStart`) se recarga si se instaló, borró o reemplazó una región. No hace falta reiniciar.
- **Tests:** `MultiRegionStyleTest` (Robolectric): 0, 1, N regiones, ids únicos, ninguna fuente sin definir ni sin usar, tope y escape de rutas.

## 2026-10-07 · Release de datos publicada y verificada (parcialmente)
- **Estado:** `data-261004-20261006` publicada (no borrador): 54 ficheros, 4,40 GB, en `https://github.com/qtekfun/UltimateMaps-data/releases/tag/data-261004-20261006`.
- **Verificado con descargas reales (curl y urllib):** el catálogo estable `…/releases/latest/download/catalog.json` responde 200 y es byte a byte idéntico al local; `Range` devuelve 206 con los bytes correctos (inicio y a mitad de un PMTiles de Madrid); descargas completas de Canarias, Ceuta, La Rioja y Melilla (render y search, 8 ficheros): tamaño y SHA-256 coinciden con el catálogo.
- **Dato para la lista blanca de red:** la descarga de un asset hace 302 desde `github.com` a **`release-assets.githubusercontent.com`** (URL firmada con caducidad corta). Hay que permitir ambos hosts (agente `feat/mvp-regions-core-link`).
- **No verificado:** los otros 21 pares de ficheros, `World.mwm` y `WorldCoasts.mwm` (están subidos y con hash en `SHA256SUMS`, no re-descargados), ni cuota o límites de ancho de banda de GitHub para tráfico real de usuarios.
## 2026-10-07 · M4: vista previa de ruta (rama `feat/mvp-route-preview`)
- **Decisión:** «Ruta» de la ficha abre `RoutePanel` (origen = ubicación actual, perfil coche/pie/bici, evitar autopistas/peajes/ferris/sin asfaltar con `RouteOptions`, distancia y tiempo, cerrar). Sin ubicación se avisa y se elige el origen buscando o tocando el mapa (`MapEngine.setMapTapListener`, solo mientras se elige). `MapEngine` gana `showRoute`/`clearRoute`/`setMapTapListener` con implementación vacía por defecto; `MapLibreEngine` dibuja una `LineLayer` bajo los puntos y encuadra la ruta con relleno inferior para el panel.
- **Concurrencia:** cada cálculo corre en IO, se cancela al cambiar perfil/opción/origen y se serializa con la búsqueda con un único `Mutex` compartido (un solo `CoMapsCore`). Una llamada nativa no se puede interrumpir: el **timeout (30 s)** solo deja de esperar y descarta el resultado tardío, y se pasa el mismo presupuesto al router nativo; el núcleo ya ocupado retrasa el siguiente cálculo. `CANCELLED` del núcleo se muestra como timeout.
- **Latencia (R12, no medida):** logcat `UMROUTE` con `route profile=<perfil> ms=<n> result=<ok|need_more_maps|start_not_found|end_not_found|route_not_found|timeout|no_regions|internal|cancelled>`. Sin coordenadas ni nombres. Medida desde que se lanza hasta el resultado (incluye la espera del `Mutex` y el arranque en frío del núcleo).
- **Límites:** no hay giro a giro; el origen elegido no tiene marcador propio; el tiempo de las rutas largas sigue sin medirse en el móvil; la lista de regiones que faltan (`NEED_MORE_MAPS`) no se detalla.

## 2026-10-07 · Test `RoutePanelTest` intermitente: corregido en el test, no en el código
- **Síntoma:** tras integrar M4, `explainsNeedMoreMaps` fallaba ~1 de cada 2 suites completas y 0 de 3 aislado.
- **Diagnóstico (con volcado del árbol de semántica al fallar):** el estado del controlador era `ERROR/NEED_MORE_MAPS`, pero la pantalla seguía mostrando «Calculating route…». El test arrancaba el cálculo en hilos de fondo (`Dispatchers.Default/IO`) antes de componer y esperaba una recomposición provocada desde otro hilo; en una JVM con muchas pruebas de Compose/Robolectric esa recomposición no siempre llegaba.
- **Intentos descartados (no funcionaron, 2 de 4 y 0 de 6):** esperar al nodo en vez de al estado; forzar `Snapshot.sendApplyNotifications()`; hacer que el controlador trabaje sobre `Dispatchers.Main`.
- **Arreglo:** el test espera (sin tocar la UI) a que el cálculo termine y compone la pantalla después, así la primera composición lee el estado final. No se debilita nada: se siguen comprobando los mismos textos, perfiles y el botón de cerrar. Resultado: 6 suites completas seguidas en verde.
- **Qué no cubre:** el test ya no prueba que la UI se recomponga al llegar un resultado desde otro hilo; la lógica con hilos reales sigue en `RoutePreviewControllerTest`. Si la app mostrara «Calculando…» tras un resultado en el dispositivo, estos tests no lo detectarían: comprobarlo en el móvil.
## 2026-10-07 · Enlace con el núcleo, World y catálogo por defecto (rama `feat/mvp-regions-core-link`)
- **Decisión:** `Region.comapsId` e `installed.json` (retrocompatibles); bloque `base` del catálogo (World/WorldCoasts, una vez por versión, `<root>/.base/<v>/`); `CoreMapsLinker` (enlaces simbólicos, duros como respaldo, ledger `.links.json`) que deja `filesDir/maps-core/<v>/<comapsId>.mwm` + `World*.mwm` al arrancar y tras instalar, actualizar o borrar; catálogo por defecto `https://github.com/qtekfun/UltimateMaps-data/releases/latest/download/catalog.json` con `github.com`, `release-assets.githubusercontent.com` y `objects.githubusercontent.com` en la lista blanca (solo al usar un catálogo de github.com). Detalles en `docs/phase1/regions.md`. Cierran las lagunas 1, 2 y 3 de la entrada anterior.
- **Motivo del simbólico:** funciona hacia la tarjeta SD (otro volumen) y no duplica 4 GB; el duro es el respaldo. El núcleo lee con `stat`/`fopen` (siguen enlaces).
- **Hallazgo (núcleo):** `RefreshMaps` registra mapas nuevos y versiones más nuevas, pero no hay desregistro: tras **borrar** una región el núcleo la sigue sirviendo hasta reiniciar la app (singleton). La UI lo avisa (`restartNeeded`). Una actualización no necesita reinicio.
- **Verificado:** tests JVM (`:core-regions`, `:app`): enlazador, catálogo/base, redirecciones 302 a otro host autorizado y no, integración catálogo -> instalar con World -> estructura `maps-core/<v>/` con nombres exactos (`Spain_La Rioja.mwm`) -> actualizar -> borrar. Cadena de redirecciones de nuestra release comprobada con HEAD. **No verificado:** que el núcleo lea de verdad a través de enlaces en Android (ni en la tarjeta), la descarga real de la release, ni la búsqueda real con estos ficheros (sin móvil).
- **Límites:** si una tarjeta no está montada al arrancar, sus regiones se desenlazan hasta el siguiente arranque o instalación; un catálogo sin `base` no puede descargar World (el enlace usa la base ya instalada, si la hay); un único `World*.mwm` por versión en cada almacenamiento.
- **Alternativa descartada:** copiar los .mwm a `maps-core` (duplica espacio y no sirve en la tarjeta) y descargar el catálogo al arrancar (la app no conecta sin acción del usuario).

## 2026-10-07 · Primera prueba real del núcleo en el Pixel 8: arranca y busca; ruta larga sin resolver
- **Qué se hizo:** con permiso del usuario («úsalo ahora»), APK de release firmado con la clave de depuración (solo para actualizar encima de la app instalada y conservar datos), con el lock `/tmp/pixel-device.lock`. Detalle y capturas en `docs/phase1/device-test/release-rc1/`.
- **Corregido:** el núcleo abortaba al arrancar (clasificador del estilo equivocado, ver el README). Ahora `SetCurrentStyle(kDefaultMapStyle)`; `scripts/comaps-prepare.sh` genera `drules_proto_default_light.bin` antes del estilo vehicle; `um_core.cpp` enlaza log y `CHECK` de CoMaps a logcat (etiqueta `UMCORE`) para no volver a abortar en silencio.
- **Medido (R12):** búsqueda en caliente 484-4201 ms (n=6, umbral 100 ms): no cumple. Ruta Madrid–Barcelona: `route_not_found` en 549 ms con 7 regiones: sin medida válida contra el umbral de 2 s. En el spike (25 regiones) fueron ≈ 18 s.
- **Decisión pendiente (del usuario):** R12 sigue abierto; la opción C ya está elegida (cambiarla es «Cuándo preguntar» nº 3). Siguiente medida útil: la misma ruta con las 25 regiones instaladas, y la búsqueda con 1-2 regiones, para separar el efecto del número de regiones.
- **Riesgo:** el APK de prueba (`~/mapas-data/test-builds/`) está firmado con la clave de depuración: no es instalable como actualización sobre un release firmado con la clave del proyecto.

## 2026-10-07 · Preparación de la release 0.1.0-rc.1 (sin Pixel 8, que el usuario retiró)
- **Decisión:** se prepara todo lo que no necesita el teléfono ni secretos: `LICENSE` (GPL-3.0, copia de la de UltimateDeck), `README.md`, `PRIVACY.md` (es/en), `CHANGELOG.md` con notas reales y limitaciones, `fastlane/metadata` (es-ES y en-US), borrador `fdroid/com.qtekfun.mapas.yml` sin `Builds`, `usesCleartextTraffic="false"`, y el workflow `release.yml` con instalación explícita de NDK 28.2 y CMake 3.31.6. `RELEASING.md` lista lo que queda: tuyo, del teléfono y riesgos de F-Droid.
- **Hallazgo de licencias:** `kdtree++` SÍ se compila (lo incluye `libs/geometry/tree4d.hpp`; 16 cadenas en el `.so`) y su licencia es Artistic License 2.0 según las cabeceras (`function.hpp:83`), compatible con GPLv3. `LICENSES.md` decía lo contrario («no se compila aquí»): corregido. Falta incluir su texto en un `NOTICE` o en «Acerca de».
- **Verificado:** `permisos de red` (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) vienen de MapLibre 13.6.1 (informe del manifest merger); 267 tests JVM en verde; YAML del workflow válido. **No verificado:** el workflow en GitHub, `lint`/`assembleRelease` tras estos cambios (ver abajo), ni F-Droid.
- **Prueba de reproducibilidad NO completada:** lancé dos compilaciones limpias del APK sin firmar para comparar entradas, pero el sistema alcanzó poca memoria libre (27 de 30 GB) y detuvo mi comando de espera; paré mis procesos de compilación para no perjudicar al resto de la máquina. No hay resultado, ni positivo ni negativo. Repetir cuando haya memoria libre y bajo petición del usuario: `./gradlew clean :app:assembleFossRelease` dos veces y comparar `unzip -v`. Un APK reproducible es requisito de F-Droid (como en UltimateDeck).
- **Efecto secundario:** `gradle clean` borró `app/build` y `native-comaps/build`; la próxima compilación nativa tardará (≈ 3-5 min).
- **Revertir:** `git revert` de este commit; no cambia código de la app salvo la línea del manifiesto.

## 2026-10-07 · El código se publica en GitHub (primer push a `master`, autorizado por el usuario)
- **Decisión:** `git push origin master` (excepción única a «nunca empujes a `master`»), tras preguntar al usuario («¿subo ya el código?» → «Si»). 29 commits, repositorio público `qtekfun/UltimateMaps`, rama por defecto `master`; local y remoto idénticos (`aa9ebab`).
- **Antes de publicar se escaneó** lo versionado (sin `third_party`): sin claves, tokens, números de serie del móvil, IP privadas ni correos personales. Se publican: el autor `Qtekfun <qtekfun@gmail.com>` de los commits, 19 `logcat` de las pruebas del spike (sin datos personales encontrados) y los enlaces a la sesión de Claude de los mensajes de commit.
- **No se hizo:** quitar los logs del historial (exigiría reescribirlo: «Cuándo preguntar» nº 5). Quedan solo en local las ramas `spike/*` y `feat/*`.
- **Pendiente del usuario:** protección de `master` y CI (sin checks, las PR no se mergean solas); los secretos `UM_*`.
- **Desde ahora:** una rama y una PR por tarea; esta misma entrada va por PR.

## 2026-10-07 · Error mío: el catálogo publicado no traía `World.mwm`; corregido
- **Síntoma (reportado por el usuario con la pre-release `test-v0.1.0-rc.1`):** descargó una región desde la app (la descarga y el dibujado funcionan) pero la búsqueda decía «no hay mapas, descarga uno».
- **Causa:** el núcleo exige `World.mwm` en `maps-core/<versión>/`, que baja el bloque `base` del catálogo. El `catalog.json` de la release `data-261004-20261006` se generó **antes** de que existiera ese bloque y no se regeneró al integrar la rama que lo añadió (lo había apuntado como laguna nº 2 y se me pasó). Sin `base`, la app nunca descarga `World`.
- **Corrección:** catálogo regenerado con `--base-dir` (solo cambia el bloque `base`; las 1.303 regiones son idénticas; hashes de World iguales a `SHA256SUMS`) y reemplazado en la release (`gh release upload --clobber`). La URL `…/releases/latest/download/catalog.json` tardó unos 90 s en servir la copia nueva por caché.
- **Qué debe hacer quien ya tiene una región instalada:** la app solo baja `World` al **instalar** una región cuando aún no lo tiene; una región ya instalada no lo dispara. Basta descargar cualquier otra región (p. ej. Ceuta, 1,5 MB, + 62 MB de World) o borrar y volver a bajar la región.
- **Salvaguarda:** `scripts/gen-region-catalog.py` avisa ahora si hay regiones descargables pero no `--base-dir`.
- **Lo que no detectaron los tests:** ninguno cubría «catálogo real publicado → buscar»; el circuito descarga → enlace → búsqueda no se probó de punta a punta con la release real. Pendiente (con el teléfono, cuando el usuario lo permita).
