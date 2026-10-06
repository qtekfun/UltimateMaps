# Informe del spike (fase 0)

Fecha: 2026-10-06. Autor: Claude Code (responsable técnico autónomo). Fuentes detalladas: `docs/spike/comaps-build.md`, `comaps-code.md`, `comaps-style.md`, `maplibre.md`, `verificaciones.md`. Código y trazas en `spike/`.

## Resumen

- **No hay una decisión firme A/B/C.** Faltan datos críticos (sin GMS, desacople en ejecución, clases de dispositivo) y dos umbrales del motor de CoMaps **no se cumplen** (búsqueda y ruta larga). Por la regla del propio spike y por `CLAUDE.md` (cambiar la opción ya decidida exige preguntar), no avanzo a la Fase 1.
- **Recomendación provisional: C (híbrido)**, condicionada a las comprobaciones de la sección «Qué falta». Justificación abajo.
- Hardware: **un solo dispositivo**, Pixel 8 (Android 17, 120 Hz, con GMS). Todo lo medido es «gama alta con GMS».

## Cifras frente a umbrales

Todo medido en el Pixel 8 a 120 Hz, con comando y traza (ver los informes por flujo). «No medido» = sin dato, no un suspenso.

| Métrica | Umbral | CoMaps tal cual | MapLibre + PMTiles | Veredicto |
| --- | --- | --- | --- | --- |
| fps / frame time en gestos | p95 ≤ 8,3 ms (120 Hz) | Intervalo entre buffers de SurfaceFlinger: p95 8,85-8,88 ms, 119-120 fps, 0-0,16 % de intervalos > 12,5 ms (`traces/07..`) | `gfxinfo` p95 6,8-7,3 ms (120 Hz), 0 frames > 16,6 ms | Ambos fluidos. **Métricas no comparables** (CoMaps: proxy de SF, MapLibre: hilo UI con TextureView, puede subestimar). El p95 literal de CoMaps queda 0,5 ms por encima |
| Arranque en frío | ≤ 1 s | 141 ms (Splash, mediana de 10); ≈ 650 ms hasta `MwmActivity` (n=5) | 228 ms (mediana de 10); primer render completo ≈ 0,94 s | Pasan ambos |
| Búsqueda por tecla (primer lote) | ≤ 100 ms | mediana 631 ms, p95 1734 ms (n=31); 1.ª en frío 6705 ms (sin traza íntegra) | no aplica (sin índice) | **No pasa.** Medido con el dispositivo compartido y España completa; **no repetido en reposo** |
| Ruta Madrid–Barcelona, coche | ≤ 2 s | 16,5-17,4 s (agente) y **17,8 / 18,0 / 18,0 s en reposo** (`traces/20-rerun-route-quiet.txt`), 620,6 km | no aplica | **No pasa (≈ 9x), reproducible sin carga** |
| Rutas urbanas (informativo) | — | coche 6,7 km 0,43 s; pie 5,1 km 1,3 s; bici 5,4 km 1,15 s; Guadarrama 13,8 km 0,67 s | — | Aceptables |
| Estilo (checklist 10) | ≥ 8 alcanzables | 5 sí, 4 parcial, 1 no (relieve); 3 de los parciales requieren C++ de Drape. Solo lectura de código, sin capturas | Estilo Protomaps light: paleta suave, jerarquía de vías, POI redondeados, halo (capturas en `spike/maplibre/traces/`). 3D, relieve y día/noche no probados | A: ≥ 8 solo contando parciales, **no demostrado**. MapLibre: control total, parcialmente evidenciado |
| Desacoplar la UI | Pantalla Compose sin la actividad de CoMaps | **Viable por lectura de código**: `:app` y `:sdk` ya separados en Gradle; coste 15-21 persona-días. **No ejecutado** | — | Sin prueba en ejecución |
| Sin GMS | Todo funciona o vía clara | El sabor fdroid de CoMaps depende de `org.microg.gms:play-services-location` (`app/build.gradle.kts:375`), contrario a `CLAUDE.md`; consumiendo solo `:sdk` se evita (`LocationManager`). **No probado** en dispositivo sin GMS | — | **No medido** |
| Rutas con curvas | Vía realista | Sí: `EdgeEstimator::CalcSegmentWeight` virtual, solo aumentar costes; 6-10 días de C++ | — | Pasa (por código) |
| Moto | — | No existe `VehicleType` moto: estimador y router nuevos, 5-8 días | — | Trabajo propio |
| Evitar autopistas/peajes | — | Existe como exclusión dura. «Evitar autopistas» dio 691 km / 11 h 25 min tras ~3-4 min de cálculo (captura no guardada); «evitar peajes» dio la misma ruta que sin evitar | — | Parcial |
| Carriles y límite de velocidad | — | Solo confirmados en código; la navegación simulada falló (usó la ubicación real) | — | **No medido** |
| Memoria / APK | — | PSS 503 MB; APK arm64 46,3 MB | PMTiles España peninsular + Baleares 3,4 GB (vs 1,9 GB de .mwm) | Informativo |

## Licencias (de `verificaciones.md`)

- El código de CoMaps es Apache-2.0, compatible con GPLv3 (hay que fijar «GPLv3 o posterior», nunca GPLv2-only).
- Tres elementos **bloquean publicar tal cual**: `3party/bsdiff-courgette/bsdiff` (BSD Protection License, GPL-incompatible), la fuente `06_code2000.ttf` (shareware, no libre, NonFreeAssets) y los iconos Entypo (CC BY-SA 3.0). Son excluibles o reemplazables sin tocar el motor de ruta/búsqueda, pero cuestan trabajo. `gb-postcode-data` (GPLv2) solo afecta si generamos mapas de GB; `kdtree++` (Artistic) sin fichero de licencia. Licencias de submódulos tomadas de `copyright.html`, no leídas en cada submódulo.
- Datos: OSM bajo ODbL con atribución. No se encontraron condiciones de uso del CDN de CoMaps: hay que preguntar al proyecto antes de montar un espejo público.

## Recomendación: C (provisional)

**Por qué no A:** no todos los umbrales pasan (ruta 9x, búsqueda 6x), el estilo depende de tocar C++ de Drape para llegar a 8/10, y el sabor fdroid arrastra una dependencia de microG.

**Por qué no B (todavía):** la regla de B (núcleo no desacoplable, o licencias/formatos bloqueantes) no se activa: el desacople es viable por código y las licencias problemáticas son excluibles. B costaría el pipeline mundial (R3), sin ninguna cifra de Valhalla.

**Por qué C:** MapLibre pinta España fluido (p95 ≈ 7 ms, arranque 0,23 s) con control total del estilo, y el desacople de CoMaps (búsqueda + routing + datos mundiales) está evaluado como viable.

**Debilidad abierta de C (importante):** C reutiliza el núcleo de routing y búsqueda de CoMaps, justo lo que **no cumple** los umbrales. La regla del spike para C exige que el motor pase; aquí no pasa. Antes de comprometerse hay que saber si los 18 s y los 0,6 s son estructurales o se deben a medir con España entera cargada en frío (por ejemplo con solo las regiones necesarias), o si habrá que relajar el umbral de 2 s para rutas de 600 km (decisión de producto, no mía). Si resultara estructural y el umbral irrenunciable, la alternativa es B con Valhalla, sin medir.

## Qué falta (bloquea decidir en firme)

1. Búsqueda en reposo y con un subconjunto de regiones (hipótesis no evaluada).
2. Un dispositivo sin GMS y otro de-Googled: ubicación en frío, voz, servicio en segundo plano 30 min.
3. Un dispositivo de gama media para los umbrales de gama media.
4. Desacople ejecutado: pantalla Compose mínima sobre `:sdk`.
5. Capturas de CoMaps para comparar el aspecto con MapLibre (no hay).
6. Carriles y límites de velocidad con una ruta simulada.
7. Medición de SurfaceView en MapLibre y de multitoque real.

## Estimación revisada del roadmap

Persona-días del estudio de código (estimaciones, no medidas): desacople 15-21 d; con moto (5-8 d), curvas (6-10 d), servidor de mapas propio y `NetworkPolicy`: 40-58 d. Para C se suma la integración de dos motores y dos descargas por región (≈ 5,3 GB para España con PMTiles 3,4 GB + mwm 1,9 GB). Fase 1: 2-3 meses → **3-4 meses** a tiempo parcial. El resto de fases no se modifica sin la decisión firme (ver `docs/mapas-05-roadmap.md`).

## Riesgos nuevos (añadidos al roadmap, R11-R17)

R11 licencias heredadas (bsdiff, code2000, Entypo); R12 latencia de ruta y búsqueda de CoMaps 6-9x sobre umbral; R13 `countries.txt` firmado con Ed25519 y SHA-1 por región: un espejo propio exige recompilar con nuestra clave y no cumple RF-02 (SHA-256) sin cambios; R14 sabor fdroid de CoMaps con dependencia de microG; R15 Android 17 impide `adb push` a `Android/data` y el motor de MapLibre no lee `file://` allí (los datos van en `filesDir`); R16 un solo dispositivo de prueba; R17 volumen de datos de C (≈ 5,3 GB para España).

## Estado de git

Todo está en `master` local (squash de las ramas `spike/*` y `feat/core-geo-skeleton`; 83 tests unitarios en verde, repetidos en la consolidación). **Nada empujado**: `origin` está vacío y no existen CI ni protección de rama (ver `docs/decisions.md`).
