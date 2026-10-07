# Gasolineras en el mapa y ruta con paradas (RF-15) — rama `feat/fuel-map-route`

Fecha: 2026-10-07. Solo consume las interfaces de `:core-fuel` (`FuelRepository`, `FuelSettingsStore`); los datos y los Ajustes son del agente E.

## Qué hay

**Capa del mapa** (`app/.../map/FuelMapLayer.kt`, `FuelIcons.kt`, `MapLibreEngine`)
- `FuelMapLayer` (Kotlin puro, probado en JVM) decide qué se dibuja: el motor avisa al terminar un gesto de cámara (`MapEngine.setViewportListener`, nunca por fotograma); tras 250 ms sin cambios consulta `stationsIn(bounds, mapFuel, limit)` en un hilo de fondo y llama a `render` solo si el resultado difiere de lo ya dibujado.
- Zoom mínimo 11; límite por vista según zoom (80/150/250/400); por debajo de zoom 14 se queda la más barata de cada celda de 48 dp (agrupar). Apagado, sin combustible elegido o por debajo del zoom: no se dibuja nada y, si no hay nada dibujado, ni siquiera se consulta.
- Un cambio de `mapFuel`, de `enabled` o de `lastUpdateMillis` del repositorio recalcula. Los cambios de otros ajustes (p. ej. minutos de refresco) no.
- MapLibre: `GeoJsonSource` + `SymbolLayer` (icono de surtidor dibujado con Canvas una vez por carga de estilo + etiqueta «1,154 €»). La etiqueta cede ante colisiones (`text-optional`), el icono siempre se ve. Orden de colisión por precio (`symbol-sort-key`).
- Más baratas (10 % de la vista, entre 1 y 5, nunca todas): icono más grande (38 dp frente a 28), doble anillo y triángulo «hacia abajo», etiqueta mayor. No dependen solo del color. Tema claro/oscuro: colores de icono, texto y halo según el tema al cargar el estilo.
- Toque: `queryRenderedFeatures` en un cuadrado de 48 dp; gana el más cercano; si hay un marcador guardado/pin/usuario justo bajo el dedo (14 dp) y ninguna gasolinera, el toque no es de gasolinera. Mientras se elige el origen de ruta, tocar una gasolinera la elige como origen.

**Ficha** (`app/.../fuel/FuelStationCard.kt`, `FuelCardController.kt`): marca, dirección, municipio, horario, precios de todos los combustibles descargados (el elegido, primero y en negrita, «(en el mapa)»), y bajo ellos siempre la atribución «Fuente: Ministerio para la Transición Ecológica y el Reto Demográfico · actualizado hace…» y «Precio publicado por el Ministerio; no oficial. Compruébalo en el surtidor.». La atribución vive en **una sola función** (`fuelAttributionText`) y en `strings_fuel_map.xml`: sustituir por `FuelAttribution.text(lastUpdateMillis)` de `:core-fuel` cuando E lo integre. Botones (≥ 48 dp): **Ir** (ruta nueva, sin paradas), **Añadir parada** (solo con ruta activa; si se rechaza, la ficha dice por qué), **Guardar** (reutiliza `PlacesService` mediante `PlacesController.toggleSaved(info)`). La ficha se muestra también sobre el panel de ruta.

**Ruta con paradas** (`RoutePreviewController`, `RoutePanel`): lista [salida, paradas…, destino]; `addStop` (antes del destino), `removeStop`, `moveStop(i, ±1)`; recalcula cada vez; máximo 5 (`MAX_STOPS`). `StopResult`: `ADDED`, `DUPLICATE`, `SAME_AS_DESTINATION` (a ≤ 30 m), `LIMIT`, `NO_ROUTE`. Distancia y tiempo mostrados son los totales de toda la ruta. `INTERMEDIATE_NOT_FOUND` del núcleo ahora es `RouteError.STOP_NOT_FOUND` con mensaje propio (antes «ruta no encontrada»; se actualizó ese caso de `RoutePreviewControllerTest`). UMROUTE: `route profile=car stops=2 ms=… result=ok` (`stops=` solo si hay paradas; solo un número).

## ¿`CoMapsCore.route` pasa `via` al núcleo nativo?

Sí. `CoMapsCore.route` construye `[from] + via + [to]` en un `DoubleArray`; `um_jni.cpp` lo pasa tal cual a `Core::Route`, que exige ≥ 2 puntos y crea `routing::Checkpoints(todos los puntos)` para `IndexRouter::CalculateRoute`, que enruta por tramos entre checkpoints. **No hace falta encadenar tramos.** No verificado en un dispositivo (sin móvil): el tiempo de una ruta con paradas largas puede crecer con cada tramo (un tramo Madrid–Barcelona ya era el riesgo R12).

## Interfaces añadidas (fuera de mi propiedad estricta, mínimas)

- `:core-map` `MapEngine`: `showFuel(List<FuelPin>)`, `setFuelTapListener`, `setViewportListener`, más `FuelPin` y `GeoBounds` (todo con implementación vacía por defecto).
- `app/build.gradle.kts`: una línea, `implementation(project(":core-fuel"))` (E probablemente añade la misma: conflicto trivial).
- `PanelHost`: parámetros `fuelRepository` (por defecto `NoFuelData`), `fuelSettings` (por defecto `StaticFuelSettings`, apagado) y `fuelName` (id -> nombre). **E debe pasar sus implementaciones reales y el nombre del combustible** (`FuelType.displayName`). `FuelType(id, displayName)` se construye aquí con `displayName = id` porque solo hay id en los ajustes; el repositorio debe buscar por `id`.
- `PlacesController.isSaved/toggleSaved(info)`; `SheetPanel(fuel = FuelCardHost?)`.
- No se cambió ninguna firma de `:core-fuel`.

## Tests (JVM y Robolectric; 174 en `:app` + `:core-map` + `:core-fuel`, 0 fallos)

`FuelMapLayerTest` (11: etiqueta y orden, más baratas, apagado/sin datos sin consulta, zoom mínimo, límite por zoom, cambio de combustible, apagado limpia, sin redibujar si no cambia, antirrebote, datos nuevos, agrupar), `FuelStationCardTest` (10: toque → ficha con todos los precios y atribución, orden, ≥ 48 dp, Ir, Añadir parada, duplicada, igual al destino, Guardar, origen por toque, ficha desconocida), `RouteStopsTest` (8: sin ruta, `via`, duplicada, destino, límite, quitar/reordenar, nuevo destino, parada inalcanzable, log) y `RoutePanelTest` (lista, totales, subir/bajar/quitar).

## No medido / no verificado

- Apariencia real (iconos, tamaños, colisiones de etiquetas, contraste en claro/oscuro), fluidez al hacer zoom y pan, y el coste de `setGeoJson` con 400 estaciones: **no medidos** (sin Pixel 8 ni adb). La capa MapLibre (`SymbolLayer`, expresión `switchCase`, `queryRenderedFeatures`, `addImage` con `bitmap.density`) solo está **compilada**, no ejecutada; el pixelRatio del icono depende de `bitmap.density` y podría salir al doble o a la mitad de tamaño.
- Los 48 dp táctiles del mapa se comprueban por construcción (cuadrado de 48 dp), no con un dedo.
- La fuente «Noto Sans Medium» del `textFont` existe en los assets; que el `€` (U+20AC, rango 8192-8447) se dibuje no se ha visto.
- El texto «no oficial» y la atribución siguen las instrucciones del coordinador; revisar la redacción legal.
