# Maneuvers, lanes and speed limits from the CoMaps core

Branch `feat/nav-maneuvers` (on top of `feat/nav-model`). Core version: `v2026.10.05-19`. **Status: compiles and links; NOT run** (no device; the Pixel 8 is off limits). What follows distinguishes what was read in the code [L], what was tested on the JVM [T] and what is missing [N].

## What actually exists in this version [L]

All in `third_party/comaps/libs/routing/`:

| Data | Where | Notes |
|---|---|---|
| List of route segments | `Route::GetRouteSegments()` (`route.hpp`) | One `RouteSegment` per stretch; `GetPoly()` has one more point than segments and does not deduplicate (`JunctionsToPoints`, `road_graph.hpp:336`; `FollowedPolyline::Update`). Segment *i* joins points *i* and *i+1*. |
| Turn | `RouteSegment::GetTurn()` -> `turns::TurnItem` (`turns.hpp`) | `m_index` = segment index + 1 = index of the `GetPoly()` point where the turn happens (`directions_engine.cpp:366`). `m_turn` (`CarDirection`), `m_pedestrianTurn`, `m_exitNum`, `m_lanes`. `None` = no maneuver. |
| Roundabout | `FixupCarTurns` (`car_directions.cpp:57`) | Deletes the `StayOnRoundAbout` entries and sets the exit on `EnterRoundAbout` and `LeaveRoundAbout` (`SetTurnExits(exitNum + 1)`). |
| Lanes | `TurnItem::m_lanes` (`lanes::LanesInfo`, `lane_info.hpp`) | Filled only if there is a turn (`car_directions.cpp:156-160`) from `FMD_TURN_LANES*`. `SelectRecommendedLanes` marks `recommendedWay != None` on the lanes that lead to the route, or empties the list if it cannot (`lanes_recommendation.cpp:20-44`). Each `LaneInfo.laneWays` is a bitset of `LaneWay`. |
| Destination street | `Route::GetClosestStreetNameAfterIdx(m_index, RoadNameInfo&)` (`route.cpp:228`) | `RoadNameInfo` carries `m_name`, `m_ref`, `m_destination`, `m_junction_ref`... Only the first segment of each loaded stretch carries a name; the function searches forward up to 400 m (`kSteetNameLinkMeters`). |
| Speed limit **per segment** | `RouteSegment::GetSpeedLimit()` -> `SpeedInUnits` (`route.hpp`), filled in `index_router.cpp:1720` from `SingleVehicleWorldGraph::GetSpeedLimit` (`single_vehicle_world_graph.cpp:193`) | **It does exist.** `IsValid()`, `IsNumeric()`, `GetSpeedKmPH()` (converts mph). Non-numeric values: `none` (motorway with no limit), `walk`, `common`. |

## Findings that contradict what was assumed [L]

1. **Bicycle does NOT use `PedestrianDirection`**: `CreateDirectionsEngine` (`index_router.cpp:129-146`) gives `CarDirectionsEngine` to `Bicycle` and `Car`; only `Pedestrian` and `Transit` use `PedestrianDirectionsEngine`. So bicycle produces `CarDirection` (with roundabouts, slight turns and possibly lanes). The bridge looks at both fields whatever the profile, so it does not depend on this assumption.
2. **Speed limits only for car**: `index_router.cpp:1710` fills `SetSpeedLimit` only if `m_vehicleType == VehicleType::Car`. For bicycle and on foot everything is "no data".
3. **Pedestrian** only has `GoStraight`, `TurnRight`, `TurnLeft`, `ReachedYourDestination`: no slight/sharp turns, no lanes, no roundabouts.
4. **There is no departure maneuver** (`DEPART`) except `StartAtEndOfStreet` (rare). The core does not emit "leave via such-and-such street" at point 0 (`GetTurnDirection` returns nothing for `m_index == 2`). If the UI wants it, it has to be synthesised from the geometry and the name of the first street.
5. **No `MERGE`, `ARRIVE_LEFT`, `ARRIVE_RIGHT`**: `CarDirection` has no merge or arrival side (`m_isEndOfRoad` is not a side). `MERGE_LEFT/RIGHT` only exist as a lane direction (`LaneWay::MergeToLeft/Right`). `ARRIVE` is the only arrival code that is emitted.
6. `ExitHighwayToLeft/Right` do exist and are mapped to `EXIT_LEFT/EXIT_RIGHT`.

## Mapping

| `CarDirection` / `PedestrianDirection` | `TurnType` |
|---|---|
| `GoStraight` | `STRAIGHT` |
| `TurnRight/SharpRight/SlightRight` | `RIGHT/SHARP_RIGHT/SLIGHT_RIGHT` (same for left) |
| `UTurnLeft/UTurnRight` | `U_TURN_LEFT/U_TURN_RIGHT` |
| `EnterRoundAbout` / `LeaveRoundAbout` | `ROUNDABOUT_ENTER` / `ROUNDABOUT_LEAVE`, both with `roundaboutExit = m_exitNum` |
| `ExitHighwayToLeft/Right` | `EXIT_LEFT/EXIT_RIGHT` |
| `StartAtEndOfStreet` | `DEPART` |
| `ReachedYourDestination` (car or pedestrian) | `ARRIVE` |
| `None`, `StayOnRoundAbout` | omitted |
| Pedestrian `GoStraight/TurnRight/TurnLeft` | `STRAIGHT/RIGHT/LEFT` |

Lanes (`LaneWay` -> `LaneDirection`): `ReverseLeft` and `ReverseRight` -> `U_TURN`; `SharpLeft/Left/SlightLeft/Through/SlightRight/Right/SharpRight` one to one; `MergeToLeft/Right` -> `MERGE_LEFT/MERGE_RIGHT`; `None` = unrestricted lane = empty set. `Lane.recommended = recommendedWay != None`. Order: the core's (left to right).

Street: `m_name`, if empty `m_ref`, otherwise `m_destination`; empty if there is none.

Limits: runs of segments with the same numeric value are grouped into `SpeedLimit(startIndex, endIndex, kmh)` with point indices (`[i, j+1]`). `kmh = null` for "no data" and also for `none`/`walk`/`common` (they are not numeric; the model does not distinguish "no limit"). If no segment has data, the list is empty (so bicycle and on foot do not carry a single useless null stretch). Fake stretches (the jump from the start point to the road) come out as no data.

## API

- C++: `um::Core::Route(profile, pts, avoid, timeoutSec, bool withGuidance = false)`. With `false` the executed code is the same as before. `FillGuidance` (`um_core.cpp`) walks the segments once (linear cost, negligible compared with A*) and has its own `try/catch`: if it fails, the route continues without guidance.
- JNI: `nativeRoute` untouched; new `nativeRouteGuidance` -> `Object[3] { double[] route (same format), double[] guidance, String[] names }`.
- Kotlin: `CoMapsCore.routingEngine(timeoutSec, withGuidance = false)`. By default the preview does not change (it does not call the new method). With `true`, `RoutePlan.guidance` is filled. If the guidance arrives malformed, the route is kept with `RouteGuidance.EMPTY` and `RouteOutcome.guidanceError` (new field with a default value) explains it; the route part is still validated strictly as before.
- **`Guidance.kt` was not touched**, nor any class of `:core-routing`.

### Guidance array format (version 1, every integer exact in a `double`)

```
[version=1, nManeuvers, nLimits,
 per maneuver: geometryIndex, turn, roundaboutExit(-1), nameIndex(-1), nLanes, (laneWayMask, recommended 0/1) * nLanes,
 per limit:    from, to, kmh(-1)]
```

`turn` = `um::WireTurn` (same order as `TurnType`; in Kotlin it is translated with an explicit `when`, not by ordinal). `laneWayMask`: bit *i* = `LaneWay` with value *i*. `nameIndex` points into the names table. Empty array = no guidance.

Validation in `GuidanceWire.decode` (`:native-comaps`): version, header, bounded counts (lanes <= 16), exact integers (rejects NaN and fractional values), `geometryIndex` and stretches inside the geometry, name index inside the table, known turn, 12-bit mask, recommended flag 0/1, `kmh` in 1..400 or -1, no leftover or truncated data.

## Verified

- [T] JVM tests in `GuidanceWireTest` (hand-made arrays): no turns, simple turn, turn without a name, roundabout with exit (enter and leave), exit on a non-roundabout turn ignored, lanes with recommended ones and order, unrestricted lane and the two U-turns, null limit, the 18 turn codes, 20 malformed arrays, and the facade (does not ask for guidance unless `withGuidance`; broken guidance keeps the route; route not found).
- [L] All the signatures and semantics in the table above, with file:line.
- `:native-comaps:assembleDebug` compiles and links (see the commit and the output below): **compiles, not run**.

## Not verified [N]

- That `FillGuidance` does not trigger any core `CHECK` with real routes (`GetClosestStreetNameAfterIdx` does `m_poly.GetIterToIndex`; it is only called with `m_index < number of segments`).
- That the maneuver indices land on the right point on the real geometry (deduced from the code, not from a route).
- How many real routes carry lanes (depends on OSM having `turn:lanes` on that road) and the quality of the names (`m_name` is the default name, not the localised one).
- The real cost: expected to be negligible. Measure with `CoreBenchActivity` (`am start -n com.qtekfun.ultimatemaps/.bench.CoreBenchActivity --ez guidance true`, tag `UMBENCH`): it dumps, per profile, `plain_ms` versus `guided_ms`, the number of maneuvers/limits and each maneuver with its lanes.
- `kmh` for `none`/`walk` and for limits in mph (the conversion is done by the core, `GetSpeedKmPH`).
