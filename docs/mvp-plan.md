# Plan del MVP

Fecha: 2026-10-07. Opción C (decisión del usuario, `docs/decisions.md`). Equivale al «hecho cuando» de la Fase 1: **usable como visor y buscador offline en el día a día**.

## Definición del MVP

Una persona instala la app, descarga España, y puede:

1. **Ver el mapa** fuera de línea, fluido, con etiquetas e iconos (hoy faltan sprites y glyphs), día/noche.
2. **Descargar y gestionar regiones** (España primero): progreso, reanudar, verificar, borrar, actualizar. Es lo único que usa red, solo a petición suyas.
3. **Buscar** lugares y direcciones offline mientras escribe, ver el resultado en el mapa y sus datos.
4. **Guardar sitios** en listas (favoritos), verlos en el mapa, importar/exportar GPX y KML.
5. **Abrir enlaces** de Google Maps, Apple Maps, Waze y `geo:` (hecho; falta Waze/Apple probados y limpiar el pin).
6. **Ver una ruta** de coche, a pie o en bici entre dos puntos (vista previa con distancia y tiempo), sin guía giro a giro.

**Fuera del MVP** (siguientes fases): navegación giro a giro con voz, carriles y límites de velocidad, moto y curvas, grabación de tracks, sync WebDAV, Takeout, Android Auto.

## Estado de partida (verificado)

| Pieza | Estado | Dónde |
| --- | --- | --- |
| Visor Compose + MapLibre + PMTiles | Funciona en el Pixel 8 (arranque ≈ 480 ms, Madrid offline, valoración del usuario: «se mueve bien») | `:app`, `docs/phase1/device-test/` |
| Enlaces `geo:`/Google/enlace corto | Probados en el Pixel 8; Apple/Waze solo en tests | `:core-geo`, `:app` |
| Regiones (catálogo, descarga reanudable, SHA-256, atómica) | Lógica JVM con 14 tests; **sin UI ni servicio** | `:core-regions` |
| Sitios, listas, GPX/KML, backup | Lógica JVM con tests; **sin driver Android ni UI** | `:core-data` |
| Búsqueda y rutas (núcleo CoMaps) | Compila y está en el APK debug; **nunca ha dado resultados** (R12) | `:native-comaps` |
| Sprites y glyphs | **Sin empaquetar**: el mapa no tiene etiquetas ni iconos | `scripts/fetch-map-assets.sh` |

## Hitos

| Hito | Contenido | Depende de | Requiere el Pixel 8 |
| --- | --- | --- | --- |
| **M0. Mapa completo** | Empaquetar sprites y glyphs; limpiar el pin al abrir enlace corto | — | solo verificar |
| **M1. Regiones** | Catálogo (script que une `countries.txt` y PMTiles), servicio de descarga en primer plano, pantalla «Mapas», permiso INTERNET solo para descargar, `NetworkPolicy` | M0 | verificar |
| **M2. Búsqueda** | `:native-comaps` en el APK de producción, arranque diferido sobre las regiones instaladas, barra con debounce y lista, resultado → cámara + pin + ficha | M1 | **sí: R12 (latencia)** |
| **M3. Sitios** | Driver SQLite de Android, guardar desde la ficha, pantalla de listas, importar/exportar GPX/KML | M2 (ficha) | verificar |
| **M4. Ruta (vista previa)** | Elegir origen/destino, perfil coche/pie/bici, dibujar la ruta con distancia y tiempo | M2 | **sí: R12 (ruta larga)** |
| **M5. Pulido MVP** | Cadenas es/en completas, ajustes de privacidad (modo sin red), «Acerca de» con atribución y licencias, estados vacíos y errores | todos | verificar |

## Reparto (máx. 2 subagentes a la vez)

- **Agente A, `feat/mvp-regions` (M0 + M1):** sprites y glyphs, pantalla y servicio de regiones, catálogo, permiso de red.
- **Agente B, `feat/mvp-search-places` (M2 + M3, sin ruta):** núcleo en producción, búsqueda y ficha, driver SQLite y listas.
- **M4 y M5** los hago yo (o un agente) cuando uno de los dos termine.

Para no chocar: A toca `app/**/regions/**` y el servicio; B toca `app/**/search/**` y `app/**/places/**`; los ficheros compartidos (`MainActivity`, panel inferior, manifiesto) se editan lo mínimo y en funciones separadas. Yo integro en `master` local con tests.

## Puertas con el teléfono (no se usa sin permiso explícito)

1. **R12 búsqueda:** ≤ 100 ms por tecla (el spike dio 631 ms con el motor completo).
2. **R12 ruta larga:** ≤ 2 s en Madrid–Barcelona (el spike dio ≈ 18 s).
3. Fluidez con etiquetas e iconos, y consumo de memoria con España cargada.

**Si R12 no mejora** con el núcleo desacoplado, se decide con datos: relajar el umbral para rutas largas, acotar regiones cargadas o evaluar Valhalla. Hasta entonces M2/M4 avanzan con el motor tal cual.

## Criterios de «hecho» del MVP

- `./gradlew lint test assembleDebug` en verde, con tests por hito.
- Probado en el Pixel 8 con permiso: arranque, descarga de una región real pequeña, búsqueda, guardar un sitio, ver una ruta.
- Sin dependencias propietarias; `LICENSES.md` al día; licencias heredadas de CoMaps excluidas (R11).
- Estimación: 6-10 semanas a tiempo parcial (orientativa, sin medir velocidad real).
