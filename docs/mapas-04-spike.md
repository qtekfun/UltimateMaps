# 04 · Plan del spike (fase 0)

**Duración:** 1-2 semanas. **Objetivo:** decidir con datos si el proyecto va por A (derivar de CoMaps), C (híbrido) o B (ensamblar). El spike no toca la arquitectura final.

## Principio

Lo menos costoso primero: compilar CoMaps sin modificar el motor y medir. Si pasa los umbrales, seguimos por A; si no, se reemplaza.

## Pasos

1. **Entorno.** Android SDK y NDK, CMake, Python y Git. Clonar `codeberg.org/comaps/comaps` y fijar la última versión estable.
2. **Compilar CoMaps tal cual** para Android y descargar el mapa de España (y una región pequeña de otro continente para probar el catálogo mundial).
3. **Medir** (ver tabla de umbrales) en los dispositivos de la matriz.
4. **Probar el estilo:** cuánto se acerca su sistema de estilos al aspecto Apple Maps y qué queda fuera (checklist abajo).
5. **Probar las funciones requeridas:** carriles, límites de velocidad, evitar autopistas y peajes, perfiles moto/bici/a pie, importar y exportar GPX, favoritos, intent `geo:`.
6. **Probar el núcleo sin su interfaz:** una pantalla mínima en Compose que llame a búsqueda y routing sin la actividad de CoMaps. Estimar el coste de desacoplar.
7. **Probar sin GMS:** ubicación en frío, voz, servicio en segundo plano 30 minutos con pantalla apagada, en un dispositivo sin GMS y en uno de-Googled.
8. **Comparativa opcional (si queda tiempo):** MapLibre Native pintando PMTiles de España en el mismo dispositivo, para comparar fps y aspecto. Valhalla queda fuera del spike.
9. **Informe** con los resultados y la recomendación A, B o C.

## Matriz de dispositivos

| Clase | Qué comprobar |
| --- | --- |
| Gama alta con GMS (120 Hz si es posible) | fps, arranque, búsqueda, rutas |
| Gama media o baja con GMS | fps, memoria, rutas largas |
| ROM china sin GMS | Ubicación, voz, servicio en segundo plano, ahorro de batería |
| De-Googled (GrapheneOS, LineageOS sin GApps o microG) | Ubicación, voz, instalación sin Play |

Asignar a cada clase un dispositivo real antes de empezar.

## Rutas de prueba

- Madrid–Barcelona (larga, con autopistas y peajes).
- Urbana: Madrid centro, 5 km con giros y carriles.
- Montaña para moto: sierra de Guadarrama (curvas).
- A pie y en bici: ruta urbana de 3 km.

## Umbrales y criterio de éxito

| Métrica | Cómo medir | Umbral para seguir con A |
| --- | --- | --- |
| fps del mapa | `dumpsys gfxinfo` / Perfetto durante pan, zoom y giro | p95 ≤ 16,6 ms en gama media; ≤ 8,3 ms en 120 Hz si hay panel |
| Arranque en frío | `am start -W` (mediana de 10) | ≤ 1 s en gama media-alta |
| Búsqueda | Tiempo hasta primeros resultados tras cada tecla | ≤ 100 ms |
| Ruta Madrid–Barcelona | Tiempo de cálculo en el dispositivo | ≤ 2 s |
| Estilo | Checklist de 10 puntos | ≥ 8 alcanzables |
| Desacoplar la UI | Pantalla mínima que llama a búsqueda y routing | Funciona sin la actividad de CoMaps |
| Sin GMS | Ubicación, voz y segundo plano | Todo funciona o hay vía clara de solución |
| Rutas con curvas | Ver si se puede puntuar la sinuosidad o forzar paso por vías | Hay una vía realista |

### Checklist de estilo «Apple Maps»

1. Paleta suave de fondo y agua. 2. Carreteras con borde fino y jerarquía clara. 3. Tipografía de etiquetas controlable. 4. Iconos de POI redondeados y sustituibles. 5. Halo de etiquetas. 6. Edificios 3D discretos. 7. Sombreado de relieve opcional. 8. Transición día/noche. 9. Densidad de etiquetas ajustable por zoom. 10. Vista 3D de navegación con cámara inclinada.

## Regla de decisión

- **A:** todos los umbrales pasan.
- **C:** el motor (búsqueda, routing, sin GMS, desacople) pasa, pero estilo o fps no.
- **B:** el núcleo no se puede desacoplar, o sus licencias o formatos bloquean.

## Entregables

1. `docs/spike-informe.md` con tablas de resultados, capturas y trazas.
2. Recomendación A, B o C con la justificación.
3. Estimación revisada del roadmap.
4. Lista de riesgos nuevos.
5. Actualización de `docs/decisions.md`.

## Autonomía durante el spike

Claude Code trabaja sin pedir aprobación para compilar, medir, instalar en dispositivos conectados y modificar el código del spike. Solo se detiene para las condiciones de «Cuándo preguntar» de `CLAUDE.md`.
