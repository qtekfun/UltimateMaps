# Bike: prefer or require cycle infrastructure (spike and design)

Date: 2026-10-07. Branch `feat/bike-cycleways`. Source read: `third_party/comaps` (the pinned CoMaps core). Nothing here was run on a device.

## 1. Which tags survive into the `.mwm`

The generator (`generator/osm2type.cpp`, highway block around line 1160) turns OSM bicycle tags into `hwtag` classificator types. These are the only cycle-related facts the routing core can see; the raw `cycleway=*` tags are not stored.

| OSM input | Stored type | Notes |
|---|---|---|
| `highway=cycleway` | `highway-cycleway` | Also produced by conversion: a `footway`/`path` with `bicycle=designated` and `foot=no` becomes `cycleway`; a segregated shared path becomes `cycleway` + `footway` (`osm2type.cpp` lines 675 to 761). |
| `bicycle=*` positive (yes, designated, permissive...) on a non-designated highway | `hwtag-yesbicycle` | "Positive" means anything except no/none/false/use_sidepath/separate/unknown/dismount/private. |
| `bicycle_road=*`, `cyclestreet=*` | `hwtag-yesbicycle` | Same type. |
| `cycleway`, `cycleway:both`, `cycleway:left`, `cycleway:right` with a positive value (lane, track, shared_lane, opposite_lane...) | `hwtag-yesbicycle` | Only when `bicycle=*` is not set (cycleway is the secondary flag). Lane, track and shared lane are NOT told apart. |
| `cycleway=no/none` or `use_sidepath/separate` (and `cycleway:both` likewise) | `hwtag-nocycleway` | Explicitly no infrastructure on this road (for `use_sidepath` a separately mapped cycleway exists beside it). |
| `bicycle=no` | `hwtag-nobicycle` | Road is not routable by bike (`IsRoad` false). |
| `oneway:bicycle`, `cycleway=opposite` | `hwtag-bidir_bicycle` / `hwtag-onedir_bicycle` | Direction only. |

Consequence 1: the earlier note in `next-plan.md` that the core "cannot distinguish painted cycle lanes" is only half right. A lane or track on a normal road IS visible, as `hwtag-yesbicycle`, but merged with "bicycle=yes" (permission without infrastructure) and with cycle streets. There is no way to separate a protected track from a painted lane or a shared lane from the data in the `.mwm`; that would need a generator change and a new data pipeline (`UltimateMaps-data`), out of scope here.

Consequence 2: `bicycle=designated` on `path` or `footway` with foot allowed is stored as `yesbicycle` (not as `cycleway`), so it counts as cycle infrastructure through the same type.

Definition used by this feature, "cycle infrastructure": a way whose types include `highway-cycleway` or `hwtag-yesbicycle`. Ferries and offroad connections are neither infrastructure nor scaled.

## 2. How weights are applied today

- `libs/routing_common/bicycle_model.cpp` holds per-country `BicycleModel` instances (`BicycleModelFactory`, built once). `kDefaultSpeeds` gives each highway class a `{weight, eta}` speed in and out of cities. Cycleway is 21/23 (weight) versus 10 to 14 for primary, secondary, tertiary and 12 to 14 for residential.
- `hwtag-yesbicycle` is registered as an additional road type with speed = 0.9 x the cycleway in-city speed (about 18.9 weight). `VehicleModel::GetTypeSpeedImpl` takes `max(highway speed, additional road speed)` for bicycle and pedestrian. So a residential street with a bike lane already weighs about 18.9 versus 12 to 14 without, and a primary road with a lane about 18.9 versus 10 to 12. **Stock CoMaps already favours tagged lanes strongly**; only the lane type is not distinguished and untagged roads are not penalised beyond the small `nocycleway` factor 0.95 and surface factors.
- `RoadGeometry::Load` (`routing/geometry.cpp`) calls `GetSpeed` once per road feature and caches `m_forwardSpeed/m_backwardSpeed` (`weight` drives the shortest-path cost, `eta` the displayed time). The geometry cache lives in the `IndexGraph` created by `IndexRouter::MakeWorldGraph()`, which runs at the start of every `CalculateRoute`, so a model setting read in `GetSpeed` and `IsRoad` is picked up by the next route without rebuilding routers.
- `m_valid = vehicleModel.IsRoad(types)`: an invalid road gets no edges. Start and end snapping only consider valid roads.
- The A* heuristic uses the model's maximum speed (`CalcMaxSpeed`, computed when the router is built). Scaling weights only DOWN keeps the heuristic admissible; scaling up would not. The design therefore only reduces the weight of non-infrastructure roads and never raises that of infrastructure.
- Routing options (`RoutingOptions`: Ferry, Dirty, Steps, Paved) are hard filters via `Road::SuitableForOptions` and are loaded from settings per route (`RoutingOptions::LoadOptionsFromSettings`). There is no bit for cycleways, and adding one would need a new classificator-to-option mapping in `RoutingOptionsClassifier`, which only maps types that are present on the feature (an absence cannot be expressed). So the options mechanism is not suitable; a model-level setting is.

## 3. Parameterising the vehicle model at runtime from JNI

Models are shared immutable objects created once per country, so the level cannot be a constructor argument. Chosen: a process-wide `std::atomic<int>` in `routing_common` (`routing::SetBicycleCycleInfra` / `GetBicycleCycleInfra`), read inside `BicycleModel::GetSpeed` and `BicycleModel::IsRoad`. `um::Core::Route` sets it under its existing mutex right before `CalculateRoute` on every call (so a stale level from a previous route can never leak), and the router is synchronous in that thread. The level travels in the existing `avoidFlags` integer (bits 4 and 5), so the JNI function signatures and the isolated-core wire protocol need no change beyond what already carries `avoidFlags`.

Alternatives considered: a new `RoutingOptions` bit (see above, cannot express absence); one model instance per level (the factory is built once inside the router and every country has its own list; would need patching the router and factory); re-weighting in `EdgeEstimator` (does not have the feature types, only the already computed speeds).

## 4. Design

Levels (`BikeCycleways` in `:core-routing`): `OFF`, `PREFER`, `STRONGLY_PREFER`, `ONLY`.

| Level | Non-infrastructure road weight factor | Infrastructure | Note |
|---|---|---|---|
| Off | 1.0 | unchanged | Exactly stock CoMaps. |
| Prefer | 0.8 | unchanged | Tunable starting value. |
| Strongly prefer | 0.5 | unchanged | Tunable starting value. |
| Only | road is not routable | unchanged | `IsRoad` returns false for ways that are not infrastructure and not ferries. |

The factor applies to the weight only, never to the eta, so the displayed duration stays realistic. **The 0.8 and 0.5 factors are not measured**: they are the first values to try, chosen to be clearly stronger than the existing 0.9 relationship between yesbicycle and cycleway and to fit the weight scale (a residential street at 12 to 14 becomes about 10 or 7 against 18.9 for a lane). They need tuning on real routes, which needs a device or the desktop routing tests with real maps (neither available to this task).

**Default: Off.** The spike shows stock CoMaps already prefers tagged lanes (section 2), so "Off" is not "ignore cycle infrastructure"; it is the stock behaviour that the owner has been using. Prefer would change today's routes, which should be an owner choice after trying it.

### The "Only" failure result

With `ONLY`, ordinary addresses often sit on streets without a lane, so start or end snapping fails (codes 5/6), and cycle networks are discontinuous, so the search fails (code 8). The native layer maps codes 5, 6, 8 and 12 to a new own code `NO_CYCLE_ROUTE` (1004) when the level is Only and the profile is bike. The UI shows "No route using only cycle infrastructure was found. Try Prefer." and keeps the previous options so the user can switch. Known limitation: because start and end snapping also uses only infrastructure roads, "Only" fails whenever an endpoint is not near a lane, even if the rest could be ridden on cycle infrastructure. A softer version (allow ordinary roads for the first and last few hundred metres) needs a different mechanism in the router and is left as an owner decision.

### Other facts and limits

- The patch changes only `bicycle_model.hpp/.cpp`. Pedestrian and car models are untouched, and `RoutingOptions` is untouched.
- The `.mwm` cross-mwm connections were generated with the stock bicycle model; in Only mode a connection on a non-infrastructure road simply has an invalid road and the route fails, never a wrong route.
- The patch is carried as `native-comaps/patches/0001-bicycle-cycle-infrastructure.patch` and applied by `scripts/comaps-prepare.sh` (idempotent: it checks whether the patch is already applied, applies it, or fails loudly if the submodule has diverged).
- Not verified: route quality, whether 0.8 and 0.5 give sensible detours, and how "Only" behaves on real city networks. The upstream C++ tests (`bicycle_route_test`, `vehicle_model_test`) need real maps and the desktop test build, which this project does not build.
