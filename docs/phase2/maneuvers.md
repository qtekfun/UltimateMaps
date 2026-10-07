# Maniobras, carriles y límites de velocidad desde el núcleo de CoMaps

Rama `feat/nav-maneuvers` (sobre `feat/nav-model`). Versión del núcleo: `v2026.10.05-19`. **Estado: compila y enlaza; NO ejecutado** (sin dispositivo; el Pixel 8 está vetado). Lo que sigue distingue lo leído en el código [L], lo probado en la JVM [T] y lo que falta [N].

## Qué existe de verdad en esta versión [L]

Todo en `third_party/comaps/libs/routing/`:

| Dato | Dónde | Notas |
|---|---|---|
| Lista de segmentos de la ruta | `Route::GetRouteSegments()` (`route.hpp`) | Un `RouteSegment` por tramo; `GetPoly()` tiene un punto más que segmentos y no deduplica (`JunctionsToPoints`, `road_graph.hpp:336`; `FollowedPolyline::Update`). El segmento *i* une los puntos *i* e *i+1*. |
| Giro | `RouteSegment::GetTurn()` -> `turns::TurnItem` (`turns.hpp`) | `m_index` = índice de segmento + 1 = índice de punto de `GetPoly()` donde se gira (`directions_engine.cpp:366`). `m_turn` (`CarDirection`), `m_pedestrianTurn`, `m_exitNum`, `m_lanes`. `None` = sin maniobra. |
| Rotonda | `FixupCarTurns` (`car_directions.cpp:57`) | Borra los `StayOnRoundAbout` y pone la salida en `EnterRoundAbout` y `LeaveRoundAbout` (`SetTurnExits(exitNum + 1)`). |
| Carriles | `TurnItem::m_lanes` (`lanes::LanesInfo`, `lane_info.hpp`) | Se rellenan solo si hay giro (`car_directions.cpp:156-160`) desde `FMD_TURN_LANES*`. `SelectRecommendedLanes` marca `recommendedWay != None` en los carriles que llevan a la ruta, o vacía la lista si no puede (`lanes_recommendation.cpp:20-44`). Cada `LaneInfo.laneWays` es un bitset de `LaneWay`. |
| Calle destino | `Route::GetClosestStreetNameAfterIdx(m_index, RoadNameInfo&)` (`route.cpp:228`) | `RoadNameInfo` trae `m_name`, `m_ref`, `m_destination`, `m_junction_ref`... Solo el primer segmento de cada tramo cargado lleva nombre; la función busca hacia delante hasta 400 m (`kSteetNameLinkMeters`). |
| Límite de velocidad **por segmento** | `RouteSegment::GetSpeedLimit()` -> `SpeedInUnits` (`route.hpp`), relleno en `index_router.cpp:1720` desde `SingleVehicleWorldGraph::GetSpeedLimit` (`single_vehicle_world_graph.cpp:193`) | **Sí existe.** `IsValid()`, `IsNumeric()`, `GetSpeedKmPH()` (convierte mph). Valores no numéricos: `none` (autopista sin límite), `walk`, `common`. |

## Hallazgos que contradicen lo supuesto [L]

1. **Bici NO usa `PedestrianDirection`**: `CreateDirectionsEngine` (`index_router.cpp:129-146`) da `CarDirectionsEngine` a `Bicycle` y `Car`; solo `Pedestrian` y `Transit` usan `PedestrianDirectionsEngine`. Por tanto bici produce `CarDirection` (con rotondas, giros suaves y posiblemente carriles). El puente mira los dos campos sea cual sea el perfil, así que no depende de esta suposición.
2. **Límites de velocidad solo en coche**: `index_router.cpp:1710` rellena `SetSpeedLimit` solo si `m_vehicleType == VehicleType::Car`. En bici y a pie todo es «sin dato».
3. **Peatón** solo tiene `GoStraight`, `TurnRight`, `TurnLeft`, `ReachedYourDestination`: sin giros suaves/cerrados, sin carriles, sin rotondas.
4. **No hay maniobra de salida** (`DEPART`) salvo `StartAtEndOfStreet` (rara). El núcleo no emite «sal por tal calle» en el punto 0 (`GetTurnDirection` devuelve nada para `m_index == 2`). Si la UI la quiere, hay que sintetizarla desde la geometría y el nombre de la primera calle.
5. **Sin `MERGE`, `ARRIVE_LEFT`, `ARRIVE_RIGHT`**: `CarDirection` no tiene incorporación ni lado de llegada (`m_isEndOfRoad` no es lado). `MERGE_LEFT/RIGHT` solo existen como dirección de carril (`LaneWay::MergeToLeft/Right`). `ARRIVE` es el único código de llegada que se emite.
6. `ExitHighwayToLeft/Right` sí existen y se mapean a `EXIT_LEFT/EXIT_RIGHT`.

## Mapeo

| `CarDirection` / `PedestrianDirection` | `TurnType` |
|---|---|
| `GoStraight` | `STRAIGHT` |
| `TurnRight/SharpRight/SlightRight` | `RIGHT/SHARP_RIGHT/SLIGHT_RIGHT` (idem izquierda) |
| `UTurnLeft/UTurnRight` | `U_TURN_LEFT/U_TURN_RIGHT` |
| `EnterRoundAbout` / `LeaveRoundAbout` | `ROUNDABOUT_ENTER` / `ROUNDABOUT_LEAVE`, ambos con `roundaboutExit = m_exitNum` |
| `ExitHighwayToLeft/Right` | `EXIT_LEFT/EXIT_RIGHT` |
| `StartAtEndOfStreet` | `DEPART` |
| `ReachedYourDestination` (coche o peatón) | `ARRIVE` |
| `None`, `StayOnRoundAbout` | se omiten |
| Peatón `GoStraight/TurnRight/TurnLeft` | `STRAIGHT/RIGHT/LEFT` |

Carriles (`LaneWay` -> `LaneDirection`): `ReverseLeft` y `ReverseRight` -> `U_TURN`; `SharpLeft/Left/SlightLeft/Through/SlightRight/Right/SharpRight` uno a uno; `MergeToLeft/Right` -> `MERGE_LEFT/MERGE_RIGHT`; `None` = carril sin restricción = conjunto vacío. `Lane.recommended = recommendedWay != None`. Orden: el del núcleo (izquierda a derecha).

Calle: `m_name`, si está vacía `m_ref`, si no `m_destination`; vacía si no hay.

Límites: se agrupan rachas de segmentos con el mismo valor numérico en `SpeedLimit(startIndex, endIndex, kmh)` con índices de punto (`[i, j+1]`). `kmh = null` para «sin dato» y también para `none`/`walk`/`common` (no son numéricos; el modelo no distingue «sin límite»). Si ningún segmento tiene dato, la lista es vacía (así bici y a pie no traen un único tramo nulo inútil). Los tramos falsos (salto desde el punto de partida a la carretera) salen como sin dato.

## API

- C++: `um::Core::Route(profile, pts, avoid, timeoutSec, bool withGuidance = false)`. Con `false` el código ejecutado es el mismo de antes. `FillGuidance` (`um_core.cpp`) recorre los segmentos una vez (coste lineal, despreciable frente a A*) y va en su propio `try/catch`: si falla, la ruta sigue sin guiado.
- JNI: `nativeRoute` intacto; nuevo `nativeRouteGuidance` -> `Object[3] { double[] ruta (mismo formato), double[] guiado, String[] nombres }`.
- Kotlin: `CoMapsCore.routingEngine(timeoutSec, withGuidance = false)`. Por defecto la vista previa no cambia (no llama al método nuevo). Con `true`, `RoutePlan.guidance` se rellena. Si el guiado llega mal formado, la ruta se conserva con `RouteGuidance.EMPTY` y `RouteOutcome.guidanceError` (campo nuevo con valor por defecto) lo explica; la parte de ruta sigue validándose estrictamente como antes.
- **No se tocó `Guidance.kt`** ni ninguna clase de `:core-routing`.

### Formato del array de guiado (versión 1, todo entero exacto en `double`)

```
[version=1, nManiobras, nLimites,
 por maniobra: indiceGeometria, giro, salidaRotonda(-1), indiceNombre(-1), nCarriles, (mascaraLaneWay, recomendado 0/1) * nCarriles,
 por límite:   desde, hasta, kmh(-1)]
```

`giro` = `um::WireTurn` (mismo orden que `TurnType`; en Kotlin se traduce con un `when` explícito, no por ordinal). `mascaraLaneWay`: bit *i* = `LaneWay` de valor *i*. `indiceNombre` apunta a la tabla de nombres. Array vacío = sin guiado.

Validación en `GuidanceWire.decode` (`:native-comaps`): versión, cabecera, recuentos acotados (carriles <= 16), enteros exactos (rechaza NaN y fraccionarios), `indiceGeometria` y tramos dentro de la geometría, índice de nombre dentro de la tabla, giro conocido, máscara de 12 bits, marca de recomendado 0/1, `kmh` en 1..400 o -1, sin datos sobrantes ni truncados.

## Verificado

- [T] Tests JVM en `GuidanceWireTest` (arrays hechos a mano): sin giros, giro simple, giro sin nombre, rotonda con salida (entrar y salir), salida en giro no rotonda ignorada, carriles con recomendados y orden, carril sin restricción y los dos U-turn, límite nulo, los 18 códigos de giro, 20 arrays mal formados, y la fachada (no pide guiado salvo `withGuidance`; guiado roto conserva la ruta; ruta no encontrada).
- [L] Todas las firmas y semánticas de la tabla anterior, con archivo:línea.
- Compila y enlaza `:native-comaps:assembleDebug` (ver el commit y la salida abajo): **compila, no ejecutado**.

## No verificado [N]

- Que `FillGuidance` no dispare ningún `CHECK` del núcleo con rutas reales (`GetClosestStreetNameAfterIdx` hace `m_poly.GetIterToIndex`; solo se llama con `m_index < nº de segmentos`).
- Que los índices de maniobra caigan en el punto correcto sobre la geometría real (se deduce del código, no de una ruta).
- Cuántas rutas reales traen carriles (depende de que OSM tenga `turn:lanes` en esa vía) y la calidad de los nombres (`m_name` es el nombre por defecto, no el localizado).
- El coste real: se espera despreciable. Medir con `CoreBenchActivity` (`am start -n com.qtekfun.mapas/.bench.CoreBenchActivity --ez guidance true`, etiqueta `UMBENCH`): vuelca, por perfil, `plain_ms` frente a `guided_ms`, número de maniobras/límites y cada maniobra con sus carriles.
- `kmh` de `none`/`walk` y de límites en mph (la conversión la hace el núcleo, `GetSpeedKmPH`).
