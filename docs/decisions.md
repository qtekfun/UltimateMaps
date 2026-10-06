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
