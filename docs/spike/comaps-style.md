# Spike (c): estudio del sistema de estilos de CoMaps

Fuente: `~/repos/comaps-spike` (tag `v2026.10.05-19`, solo lectura) y el submódulo `tools/kothic` en el commit fijado `3dd47d5941891ed2e0e554058edf214e9050e678`. El submódulo estaba vacío en el árbol cuando lo leí (el otro agente lo estaba inicializando), así que traje ese commit exacto, con `--depth 1`, a un directorio temporal fuera del repo; el código de `libkomwm.py` citado abajo es de ese commit.

Evidencia: **[L]** leído en código (`archivo:línea`), **[I]** inferido, **[N]** no verificado. No se ha compilado ni renderizado nada: **este informe no puede decir si el resultado "se parece a Apple Maps"**; solo qué controles existen. El juicio visual necesita capturas reales (flujo de medición del spike).

## 1. Cómo funciona el sistema de estilos

Cadena de producción [L]:

```
data/styles/<tema>/{light,dark}/style.mapcss  (+ colors.mapcss, symbols/*.svg)
data/styles/<tema>/include/*.mapcss, priorities_*.prio.txt
        |  tools/unix/generate_drules.sh -> tools/kothic/src/libkomwm.py
        v
data/drules_proto_<tema>_<light|dark>.bin (+ .txt, visibility.txt, classificator.txt, colors.txt, patterns.txt)
        |  tools/unix/generate_symbols.sh (skin_generator)
        v
data/resources-*/symbols.png + symbols.sdf
        |  carga en runtime: libs/indexer/map_style_reader.cpp:156
        v
drape_frontend (stylist.cpp, apply_feature_functors.cpp, rule_drawer.cpp) + shaders/GL/*.glsl
```

- **Temas** (`data/styles/`): `default`, `outdoors`, `vehicle` (navegación), `driving`, `walking`, `cycling`, `public-transport` y sus variantes `_outdoor` (11 directorios). Cada uno tiene `light/` y `dark/` (`docs/STYLES.md`, sección "Styles directories and files"). `generate_drules.sh` compila 22 combinaciones (tema x claro/oscuro). [L: `tools/unix/generate_drules.sh:48-72`]
- **Lenguaje**: un subconjunto de MapCSS 0.2 con extensiones de CoMaps (`docs/STYLES.md`, "Technical details"). Selectores por tipo y zoom: `line|z10-13[highway=motorway]` (`data/styles/default/include/Roads.mapcss:87`). Colores como variables (`@water: #89CDDC;` en `data/styles/default/light/colors.mapcss:42`, `@background0: #F5E8D6` en `:29`).
- **Compilado, no interpretado**: el estilo no se evalúa en el dispositivo; se compila a un protobuf (`libs/indexer/drules_struct.proto`) con una tabla `tipo de feature x zoom -> reglas`. Cambiar el estilo en la app es cambiar de `.bin` (`map_style_reader.cpp:156`) y recargar (`DrapeEngine::UpdateMapStyle`, `drape_engine.cpp:462-469`). [L]
- **Qué se puede expresar** es exactamente lo que cabe en el proto [L: `drules_struct.proto`]:
  - `LineRuleProto` (`:31`): ancho, color (ARGB), patrón de guiones, prioridad, símbolo a lo largo de la línea, `join` y `cap`.
  - `AreaRuleProto` (`:52`): un color de relleno, un borde (`LineDefProto`) y prioridad. Sin degradados ni texturas salvo `pattern-image`/`hatching`.
  - `SymbolRuleProto` (`:59`): nombre del icono, prioridad, `min_distance`.
  - `CaptionDefProto` (`:67`): `height` (entero), `color`, `stroke_color`, `offset_x/y`, `text`, `is_optional`. **No hay familia tipográfica, peso, interletraje, ni ancho de halo.**
  - `DrawElementProto` (`:111`): una regla por `scale` (zoom).
- **Prioridades**: ficheros `priorities_{1_BG-by-size,2_BG-top,3_FG,4_overlays}.prio.txt`, regenerados y reordenados por el script. Las superposiciones (iconos, rótulos, escudos) **no se solapan**: gana la de mayor prioridad (`priorities_4_overlays.prio.txt:5-8`), y los rótulos opcionales de un icono solo salen si hay hueco.
- **Iconos**: SVG en `data/styles/<tema>/{light,dark}/symbols/` (1088 ficheros en `default/light/symbols`), regla `icon-image` en `Icons.mapcss`, y `generate_symbols.sh` los compone en el atlas (SDF) (`docs/STYLES.md`, "How to add a new icon"). Es el procedimiento oficial de sustitución. [L]
- **Cambios que exigen regenerar mwm**: si cambia qué features existen o su rango de zoom más allá de los límites de índice (`docs/STYLES.md`, "Testing your changes"). Un retoque de colores/anchos/iconos no los toca. [L]
- **Herramienta**: hay una versión de escritorio "Designer" para iterar (`docs/STYLES.md`), y se pueden copiar `.bin` compilados a `Android/data/<app>/files/styles/` en el móvil sin recompilar la app. [L]

## 2. Tabla del checklist de 10 puntos

"Alcanzable" aquí = se puede conseguir con el sistema de estilos y/o cambios pequeños, no con un motor nuevo. **Esfuerzo** en días de persona (estimación mía, [I]).

| # | Punto | Alcanzable | Evidencia | Qué falta / límite | Esfuerzo |
| --- | --- | --- | --- | --- | --- |
| 1 | Paleta suave de fondo y agua | **Sí** | Colores como variables hex por tema y modo: `default/light/colors.mapcss:29, 42` (`@background0`, `@water`); el compilador los codifica a ARGB (`libkomwm.py:104-112`). | Nada de motor. Hay que repetir en `dark/` y en los temas que se usen. | 2-3 |
| 2 | Carreteras con borde fino y jerarquía clara | **Sí** | Ancho, color, opacidad por zoom y clase en `Roads.mapcss:87-100`; borde con `casing-width/-color/-dashes` (`Roads.mapcss:124, 129, 229`), que el compilador emite como línea extra bajo la principal (`libkomwm.py:805-831`). Orden por `priorities_3_FG.prio.txt`. | Sin sombras ni degradados en carretera (el proto de línea no los tiene). Relleno de intersecciones fijo por el renderizador. [I] | 4-6 |
| 3 | Tipografía de etiquetas controlable | **Parcial** | Por regla solo se controla tamaño entero, color, offset y halo (`CaptionDefProto`, `drules_struct.proto:67-75`; `libkomwm.py:923-935`). La lista de fuentes es **global y fija en C++**: `libs/platform/platform.cpp:197-209` (Noto, DejaVu, Droid, Roboto Medium...) más fuentes del sistema (`:211`). Glifos como SDF a 22 px base (`libs/drape/font_constants.hpp:6`). | Se puede cambiar la fuente latina global (sustituir/añadir un TTF en `data/fonts` y la lista): coste bajo. **No** hay pesos por clase (negrita para ciudades, ligera para calles) ni interletraje sin tocar `glyph_manager`/`text_layout` (C++): 8-12 días. No he comprobado la prioridad de resolución entre fuentes [N]. | Global: 2; por clase: 8-12 |
| 4 | Iconos de POI redondeados y sustituibles | **Sí** | Pipeline oficial de sustitución: SVG en `symbols/` + `icon-image` en `Icons.mapcss` + `generate_symbols.sh` (`docs/STYLES.md`). 1088 SVG en `default/light/symbols`. | Es trabajo de diseño más que técnico: un set propio coherente (y su versión `dark/`). Licencia: los iconos actuales vienen de colecciones con licencias distintas (`NOTICE`, `data/copyright.html`); usar set propio evita arrastrarlas. Mecánica: 2 días; set completo: 10-15. | 12-17 |
| 5 | Halo de etiquetas | **Sí** (con límite) | `text-halo-color`, `text-halo-opacity` y `text-halo-radius` se leen en `Roads_label.mapcss:130-134` e `Icons_Label_Colors.mapcss:11`; se serializan como `stroke_color` con alfa (`libkomwm.py:104-112, 928-929`). | **El radio solo actúa como interruptor**: el compilador comprueba `!= 0` y guarda solo el color (`libkomwm.py:928`); el proto no tiene ancho. El halo se dibuja como pasada de contorno del glifo SDF (`render_group.cpp:107-114`, `text_layout.cpp:101`), con un margen SDF fijo (`kSdfBorder = 4`, `drape/font_constants.hpp:5`) [I: que ese margen limite el ancho]. Controlar el ancho = C++. Color y opacidad del halo sí. | 1-2 |
| 6 | Edificios 3D discretos | **Parcial** | Extrusión real: altura de `height` o `building:levels` x 3 m (default 3 m) (`rule_drawer.cpp:61-96`); se activa si `Is3dBuildingsEnabled` (`rule_drawer.cpp:281`); interruptor en `DrapeEngine::Allow3dMode(allowPerspectiveInNavigation, allow3dBuildings)` (`drape_engine.cpp:700-703`); sombreado con una luz fija (`shaders/GL/area3d.vsh.glsl:19`). | Desde el estilo solo se controla el color/opacidad del relleno (`Basemap.mapcss:714-723` define `fill-color`/`fill-opacity` por zoom). Dirección de luz, ambiente, sombras, tejados: constantes en shader/C++ (modificables, es nuestro código). "Discreto" = ajustar color+opacidad+shader: 3-5 días. Sin sombras proyectadas ni tejados. Con `building:levels` ausente salen casi planos (3 m). | 3-5 |
| 7 | Sombreado de relieve opcional | **No** | Búsqueda `hillshade|hill_shade|relief|hillshading` en todo el árbol (sin `3party/`): **ningún resultado**. Lo que sí hay son isolíneas (curvas de nivel) generadas con SRTM (`docs/ISOLINES.md`, `tools/topography_generator`) y altitudes de ruta. | Hillshade necesita capa raster/mesh + shader nuevos en Drape, más datos DEM distribuidos (Sonny/SRTM están ya en la lista de licencias). Coste alto: 15-25 días y mantenimiento de un fork de Drape; o resolverlo con otro motor (opción C). Isolíneas como sucedáneo: 0 días (ya existe). | 15-25 |
| 8 | Transición día/noche | **Parcial** | Pares `light`/`dark` por tema; cambio en caliente con `Framework::SetMapStyle(mapStyle, forceRerendering)` (`framework.cpp:1843-1851`) -> `DrapeEngine::UpdateMapStyle` (`drape_engine.cpp:462`) -> recarga de reglas. | Es un cambio **brusco** de conjunto de reglas; no he encontrado interpolación entre estilos [I; no buscado en profundidad]. Un fundido visual se puede hacer en la capa de UI (cruce de una captura del mapa en Compose): 2-3 días. El modo automático por hora/sensor es de la app, no del estilo. Mantener `dark/` en paridad con `light/` duplica el trabajo de paleta. | 2-3 (+ paridad) |
| 9 | Densidad de etiquetas ajustable por zoom | **Parcial** | Visibilidad por zoom en cada regla (`|zN-M`, p. ej. `Roads.mapcss:87`); prioridades y desplazamiento de solapes (`priorities_4_overlays.prio.txt:5-8`); `min_distance` en símbolos y escudos (`apply_feature_functors.cpp:597-600, 1260`); factor global de fuente (`visual_params.hpp:63-64`, `framework.hpp:806`). | Es **estático por estilo**, afinable offline por tipo y zoom; **no hay control en runtime** de "densidad". Un deslizador de densidad sería un multiplicador en `min_distance`/escala de colisión (C++, 3-5 días [I]). Afinado de los ficheros de prioridades: 4-6 días. | 4-6 (+3-5 slider) |
| 10 | Vista 3D de navegación con cámara inclinada | **Sí** (afinable en C++) | Perspectiva automática al seguir ruta: `EnablePerspective` (`routing_manager.cpp:1357`), ángulo según escala hasta π/4 y 55° con FOV 60° (`libs/geometry/screenbase.cpp:8-10, 92-110`); `Allow3dMode(allowPerspectiveInNavigation, ...)` (`drape_engine.cpp:700`). Tema `vehicle` propio para navegación. | Los ángulos y el FOV son **constantes en C++**, no del estilo. Cambiar la inclinación/posición del coche en pantalla = tocar `screenbase.cpp` y la lógica de seguimiento (2-3 días). El "aspecto Apple" de la cámara depende de ellos y no está validado [N]. | 2-3 |

### Recuento

- **Sí: 4** (1, 2, 4, 5) más el 10, que es sí pero con constantes en C++: **5**.
- **Parcial: 4** (3, 6, 8, 9).
- **No: 1** (7).

Contra el umbral del spike ("≥ 8 alcanzables"):

- Contando solo los **Sí**: 5/10, **no se llega**.
- Contando **Sí + Parcial** como alcanzables: 9/10, se llega, **pero** tres de los cuatro parciales (3, 6, 9) dependen de modificar C++ de Drape o de aceptar un resultado menos fino. Mi lectura honesta: el sistema de estilos cubre bien paleta, carreteras, iconos y halos (los controles "de superficie"); los controles que definen el carácter "Apple" (pesos tipográficos, relieve, edificios con sombra, transición animada) están **fuera del alcance del estilo** y dependen de un fork de Drape o de otro motor.
- Si el criterio es "alcanzable sin tocar C++", el recuento es **5 o 6 de 10** (1, 2, 4, 5, y 9 y 8 parcialmente).

La decisión A/C del spike depende de cómo se ponga ese listón y de la comparación visual, que no he hecho. Recomiendo que el informe final no cuente "parcial" como "sí" sin una captura que lo respalde.

## 3. Esfuerzo total (si se va por A, afinando estilo y Drape)

| Bloque | Días |
| --- | --- |
| Paleta (claro/oscuro, `default` + `vehicle`) | 2-3 |
| Carreteras | 4-6 |
| Iconos propios | 12-17 |
| Halo, densidad (estático), fundido día/noche | 7-11 |
| Edificios 3D discretos (shader + colores) | 3-5 |
| Cámara de navegación | 2-3 |
| Tipografía global (fuente única) | 2 |
| **Subtotal sin C++ profundo** | **~32-47** |
| Tipografía por peso (C++) | 8-12 |
| Hillshade (C++/datos) | 15-25 |
| Slider de densidad en runtime (C++) | 3-5 |
| **Con los tres extras** | **~58-89** |

## 4. Riesgos propios del estilo

- **Mantener un fork de Drape**: cualquier cambio de shaders/constantes (puntos 3, 6, 7, 10) nos ata a rebasar contra CoMaps en cada versión (la serie de mapas cambia: `MAP_SERIES` en `private.h:22`). [I]
- **Compilador de estilos en Python (kothic)** con dependencia de `protobuf` en Python y script que modifica `data/` en el sitio; el árbol de `data/` se regenera y es grande (1088 iconos, 22 variantes). [L/I]
- **Licencias de iconos y fuentes**: ver `comaps-code.md`, sección 5 (Code2000 es shareware y debe retirarse; los iconos de colecciones externas hay que auditarlos si no se sustituyen todos).
- **Tema "vehicle" y los temas por modo** (a pie, bici, coche, transporte) multiplican el trabajo de estilo por 11 si se quiere coherencia; conviene decidir cuántos temas se mantienen (propuesta: `default` y `vehicle`, claro y oscuro = 4 compilados). [I]
