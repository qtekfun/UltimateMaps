# Spike (d): MapLibre Native + PMTiles de España en Pixel 8

Paso 8 de `docs/mapas-04-spike.md` (comparativa opcional). Prototipo descartable en `spike/maplibre/`, rama `spike/maplibre`. Fecha de medición: 2026-10-06.

## Montaje

- **Motor:** `org.maplibre.gl:android-sdk:13.6.1` (AAR de Maven Central, BSD-2, sin compilar MapLibre). PMTiles soportado desde 11.7.0 con `pmtiles://file://<ruta>` (documentación vía Context7).
- **Datos:** `pmtiles extract` (go-pmtiles 1.31.2) del build de Protomaps `20261006.pmtiles` (planeta, 138 GB, v4.15.2) con `--bbox=-9.5,35.9,4.5,43.9`: **España peninsular + Baleares, 3,4 GB** (1.114.345 entradas de tile, z0-15). **Canarias, Ceuta y Melilla no incluidas** (el bbox se limitó para no inflar el archivo). Descarga ~3 min.
- **Estilo:** `@protomaps/basemaps` 5.7.2 (BSD-3), flavor `light`, idioma `es`, 71 capas; sprites y glyphs (Noto Sans Regular/Medium/Italic, rangos 0-255, 256-511, 8192-8703) de `basemaps-assets`, copiados al dispositivo (sin red en ejecución). `tools/prep-assets.sh`.
- **App:** `MainActivity` con `MapView` en modo textura (`texture=true`, necesario para que `gfxinfo` vea los frames) o SurfaceView. Extras de `am start`: `style`, `lat`, `lon`, `zoom`, `texture`, `demo`.
- **Dispositivo:** Pixel 8 (`shiba`), API 37 (`getprop ro.build.version.sdk`), panel 1080x2400 con modos 60 y 120 Hz. Todo uso de adb con `flock /tmp/claude-1000/device.lock`.

### Hallazgos de integración

1. **El motor nativo no puede leer `file://` bajo `Android/data/<pkg>/files` (almacenamiento externo):** `Mbgl: Cannot read file ...` para sprites y glyphs, aunque Java sí lee el mismo fichero. Con los datos en `filesDir` (interno, copiados con `run-as`) funciona todo, incluido PMTiles de 3,4 GB. Para producción: descargar mapas a almacenamiento interno o investigar permisos del FileSource nativo.
2. `pmtiles://asset://` no está soportado (la propia documentación lo indica).
3. Los glyphs se piden por rangos no incluidos (cirílico, árabe, etc.) y fallan con error en log sin bloquear el render; en producción habría que empaquetar los 256 rangos o un fallback.

## Cifras

Todas con España completa (`style-es.json`, `es.pmtiles`), Madrid centro (40.4168, -3.7038) z15, modo textura. Trazas crudas en `spike/maplibre/traces/`; scripts en `spike/maplibre/tools/` (`measure.sh` ejecuta todo; `stats.py` y `cold.py` calculan).

| Métrica | 120 Hz | 60 Hz | Comando / traza |
| --- | --- | --- | --- |
| Arranque en frío `am start -W` TotalTime (mediana de 10) | **228 ms** (223-263) | 236 ms (216-255) | `am force-stop` + `am start -W`; `es-*-tex-coldstart.txt` |
| Hasta primer render completo del mapa (`onDidFinishRenderingMap(fully)`, log `SPIKE`) mediana | **936 ms** (888-970) | 890 ms (827-936; n=9, 1 run sin log) | mismo fichero; desde `onCreate` |
| Pan, 12 swipes `input swipe 800 1500 300 700 400` ida/vuelta, 3 repeticiones: frame time p50 / p95 / p99 | 4,4 / **7,3** / 10,4 ms (máx 11,3) | 5,1 / **7,7** / 8,8 ms (máx 19,5) | `es-*-tex-pan-N.txt` |
| Zoom por doble toque (`input tap 540 1200` x2, 4 veces), 3 rep. | 4,4 / **7,0** / 8,4 ms | 4,4 / **8,7** / 12,5 ms (máx 21,0) | `es-*-tex-dtap-N.txt` |
| Zoom ±3 niveles + giro ±120° + inclinación 40°, programático (`--ez demo true`, `easeCamera`, 8,4 s), 3 rep. | 3,9 / **6,8** / 7,9 ms | 5,6 / **8,2** / 9,7 ms | `es-*-tex-demo-N.txt` |
| Frames >16,6 ms / >8,33 ms (de 360 por escenario) | 0 / 3-6 | 0-2 / 11-20 | `stats-120.txt`, `stats-60.txt` |
| Janky frames (gfxinfo) | 0 (0,00 %) en todas | no revisado por escenario | resumen en cada traza |

Frame time = `FrameCompleted - IntendedVsync` de `dumpsys gfxinfo org.ultimatemaps.spike.maplibre framestats` (últimos 120 frames por volcado, 3 volcados por escenario = n 360). Verificado el intervalo de frame: 8.33 ms (120 Hz) en las trazas. El resumen de gfxinfo (todo el intervalo, p95 en ms enteros) coincide: p95 = 5-7 ms a 120 Hz.

Frente a umbrales: RNF-01 p95 ≤ 16,6 ms (60 Hz) cumple; p95 ≤ 8,3 ms en 120 Hz cumple (7,0-7,3 ms). RNF-02 (arranque hasta mapa interactivo ≤ 1 s): primer render completo ~0,94 s en 120 Hz, justo bajo el umbral; la ventana abre en 228 ms pero el mapa aparece más tarde.

### Salvedades (importante)

- **Modo textura:** `gfxinfo` solo ve el hilo de UI/HWUI. Con `TextureView` cada frame de MapLibre pasa por HWUI, pero el tiempo de render GL del hilo de render del mapa no está incluido en `FrameCompleted`. Las cifras miden de forma fiable el coste de composición y la cadencia, y **pueden subestimar** el coste de render del mapa. Con SurfaceView `gfxinfo` no registra frames.
- **SurfaceView (modo por defecto de MapLibre): no medido.** `dumpsys SurfaceFlinger --latency "SurfaceView[...](BLAST)#id"` solo devolvió el periodo de refresco (sin marcas por frame) en este Android; no se encontró alternativa en el tiempo. Perfetto no se probó.
- **Giro y zoom por gestos táctiles: no medidos.** `adb input` no genera multitoque; zoom y giro se midieron con doble toque (gesto real) y `easeCamera` programático (no táctil). El pan sí es táctil real.
- 60 Hz forzado temporalmente con `settings put system peak_refresh_rate 60` / `min_refresh_rate 60` y restaurado después (`es-*-tex-env.txt`); `min_refresh_rate` no existía y se borró al terminar.
- Arranque en frío sin vaciar la caché de página del SO (sin root): lecturas del PMTiles pueden estar en caché. Un solo dispositivo (gama alta); gama media no medida (no hay dispositivo).
- Memoria, batería y rutas no medidos (fuera del alcance de este prototipo).
- Comparación con CoMaps: **no hay cifras de CoMaps en esta rama**; esta tabla es la columna MapLibre de la comparativa.

## Aspecto

Capturas (1080x2400) en `spike/maplibre/traces/`: `madrid-z12-es.png`, `madrid-z15-es.png`, `madrid-z17.5-es.png`.

Estilo Protomaps `light`: paleta suave gris-beige, vías con jerarquía clara y bordes finos, edificios en planta (sin extrusión 3D en el estilo por defecto), POI con iconos redondeados por categoría (colores: compras azul, restauración naranja, cultura rosa), etiquetas con halo en Noto Sans y nombres de calle sobre el trazado, ferrocarril/metro punteado visible. Etiquetas en español. Vectorial con giro/inclinación fluidos. Comparado con la checklist «Apple Maps»: paleta suave, halo, jerarquía de carreteras, iconos sustituibles (sprite propio), densidad por zoom y vista inclinada alcanzables; edificios 3D, relieve y día/noche requieren capas/estilo adicionales (no probados). La valoración frente a CoMaps queda para el informe final al comparar capturas.
