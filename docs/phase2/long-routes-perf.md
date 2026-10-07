# Long car routes: where the time goes and the switches to A/B on a phone

Status: analysis by reading the code, plus run-time switches. **Nothing here was run:** the core cannot run on a PC and the Pixel 8 was not used, so this document contains no timings of its own. The only numbers are the owner's measurements in `docs/phase2/device-test/route-bench-2026-10-07.md` (Madrid to Barcelona 620 km about 19 s, Madrid to Lleida 29 to 33 s, Barcelona to Madrid 47 s, Madrid to Zaragoza 312 km 8 to 9 s, short trips 1 to 2 s). Paths below are in `third_party/comaps/libs/routing/` unless noted.

## 1. Call path of a long car route

`Core::Route` (`native-comaps/src/main/cpp/um_core.cpp`) takes the process-wide lock, saves the avoid options into settings and calls `IndexRouter::CalculateRoute` on the per-vehicle router cached in `Core::Impl::routers`.

1. `CalculateRoute` (`index_router.cpp` ~line 520): checks the maps of each checkpoint are loaded, then **`MakeWorldGraph()`** (line 1089). It builds a new `CrossMwmGraph` and a new `IndexGraphLoader` on every request.
2. `PointsOnEdgesSnapping::Snap`: finds the best road segments for start and finish, with a dead-end cache computed from the finish (line ~1126).
3. `CalculateSubroute` (line 693) calls `SetupAlgorithmMode` (line ~1810): for cars, `AreMwmsNear` (line 1751) chooses `Joints` if a start map and a finish map are neighbours in the map tree or the points are closer than `kCloseMwmPointsDistanceM` = 300 km, otherwise `LeapsOnly`.
4. `Joints` mode (Madrid to Zaragoza): one bidirectional A* over the joint graph across the maps involved.
5. `LeapsOnly` mode (`CalculateSubrouteLeapsOnlyMode`, line 790), three stages:
   - **Stage 1, leaps search.** `LeapsGraph` (`leaps_graph.cpp`) is a graph of cross-map transitions. The start vertex connects to every exit of the start map, the finish vertex to every entry of the finish map (`GetEdgesListFromStart` / `GetEdgesListToFinish`, lines 87 to 112), each with an estimated weight. `FindPathBidirectionalEx` keeps emitting routes until `keys[0].size() >= 15 && keys[1].size() >= 15` (line 862, `kMaxVertices`), or until at least one route exists and 30 s of wall clock passed (line 866, `kTimeoutMilliS`). The routes are sorted and filtered to at most 2 per highway category per side (`HighwayCategoryChecker`, ~line 880). The log line `Filtered candidates count = 15` is the end of this stage.
   - **Stage 2, candidate selection** (lines ~935 to 970). `CrossMwmGraph::Purge()` drops the connectors (line 937). Then for **every** candidate two sub-routes are calculated, one in the start map from the start to the candidate's first exit and one in the finish map from the candidate's last entry to the finish, each with `Calc2Times` (a bidirectional A* over `JointSingleMwm`, results cached by segment pair in `RoutesCalculator::m_cache`). The best candidate is the one with the lowest `start leg + middle weight + finish leg`.
   - **Stage 3, final route** (`ProcessLeapsJoints`, line 1454): one `JointSingleMwm` A* per intermediate map between the leaps of the winner (for Madrid to Barcelona: Castilla-La Mancha, Castilla y Leon East, Aragon, Lleida, and so on), loading each map's index graph.
6. `RedressRoute` and the guidance engine build the final `Route`.

## 2. Cost centres (from the code; the log excerpts of the bench are the only evidence of magnitude)

**C1. Stage 2 runs up to 2 A* searches per candidate with no pruning.** With 15 filtered candidates that is up to 30 bidirectional searches (`Calc2Times`, lines 955 and 956), each inside a whole regional map. Candidates that share the same first exit or last entry share cache entries, the others do not. The owner's log shows about 11 to 13 s after `Filtered candidates count = 15` for Madrid to Barcelona, which includes stage 3 too. The code does not allow splitting those two without measuring; the new `UMBENCH` stats line does (section 5).

**C2. Stage 1 stops on a count of distinct transitions, not on route quality.** Both sets of distinct feature ids must reach 15. In a map with few relevant exits or entries, or in a destination far from the motorway network, the second set may never fill: the search then keeps expanding the cross-map graph until the 30 s wall-clock cap. Note that this cap is real time, so the result also depends on the speed of the phone.
- Evidence for the hypothesis, not proof: Madrid to Lleida took 28.9 s and 33.2 s in two runs, about the 30 s cap and slower than Madrid to Tarragona (13.9 s) or to Barcelona (about 19 s), which are farther away. Barcelona to Madrid took 47 s, which is consistent with about 30 s in stage 1 plus 17 s of stages 2 and 3. The new stats line reports `leaps_ms` and `leaps_routes` to confirm or reject this.

**C3. Direction asymmetry (Barcelona to Madrid 47 s vs 19 s).** The algorithm is bidirectional but not symmetric in what it must collect: `keys[0]` counts distinct first features (exits of the start map) and `keys[1]` distinct last features (entries of the finish map), and both must reach 15. The two trips need different sets (exits of Barcelona and entries of Madrid, against exits of Madrid and entries of Barcelona), and a one-way carriageway only counts in its legal direction, so the sets fill at different speeds. Other candidates for the difference: `FillDeadEndsCache(finish)` starts from the finish (line 1126), and the start segment is strict-forward for cars (`isStartSegmentStrictForward`). **The cause is not proven by reading the code**; C2's timing split per stage would show whether the extra time is stage 1 (cap reached) or stages 2 and 3.

**C4. Everything is rebuilt per request.** `MakeWorldGraph` creates a new `IndexGraphLoader` (geometry and `IndexGraph` per map) and a new `CrossMwmGraph` (connectors) for every route (line 1089 and following). This is why every route logs `routing section for X loaded in ...` for each map it touches (`index_graph_loader.cpp:162`; 0.15 s per map in the owner's log, so about 1 s of a 7-map route). It matters mostly for repeated routes in the same area: rerouting while navigating and the round trip in the benchmark. Connectors are additionally purged on purpose in stage 2 (`CrossMwmConnector` keeps a weights matrix and "takes a lot of memory", line 935), so they are reloaded for stage 3.

**C5. Logging is not a measurable cost any more.** A route emits only a handful of `LINFO` lines (`Routing in mode`, `Avoid next roads`, `Filtered candidates count`, `Route build` timer, one `routing section ... loaded` per map, `Route length`); the `LDEBUG` lines (`Process leaps`, `Calculating sub-route`) are skipped by the level check before any formatting because the release default level is `LINFO` (`base/logging.cpp`, `GetDefaultLogLevel`). The 28,147 warnings of the earlier run came from the `World` registration that is already fixed. A quiet mode is provided anyway (the filter is a global level check, so a suppressed line costs no formatting), but do not expect more than noise from it.

**C6. Stage 3 and loading are inherent.** One A* per intermediate map plus reading each map's routing section: not reducible without changing the algorithm or the data (the contraction-hierarchy style precomputation is a data-format change, out of scope).

**C7. Release flags.** The core is built `-O2 -ffast-math -DRELEASE`, no LTO (`native-comaps/src/main/cpp/CMakeLists.txt`). `-O3`, PGO or ThinLTO could help the A* loops but ThinLTO ran out of memory on this PC (`docs/spike/comaps-build.md`); not tried here.

## 3. Options ranked (benefit is an expectation from the code, not a measurement)

| # | Option | Expected effect | Risk | Routes identical to today? | Implemented |
|---|---|---|---|---|---|
| 1 | Fewer leaps candidates (collect 8, 5 or 3 distinct transitions instead of 15) | Largest: shortens stage 1 and shrinks stage 2 proportionally | May miss the best candidate: slightly worse route in rare geometries | No | switch `cand8`, `cand5`, `cand3` |
| 2 | Shorter wall-clock cap for stage 1 (10, 5, 2 s instead of 30 s) | Removes the 30 s plateau of C2 when it is the cause | Same as 1; also makes the result depend on device speed (already true today) | No | switch `tmo10`, `tmo5`, `tmo2` |
| 3 | Prune candidates in stage 2 with a lower bound | Skips the legs of candidates that cannot win | Low: see the argument below; depends on an admissible heuristic | Yes (argued, to be confirmed with the exact `m=` and `s=` in the bench log) | switch `prune` |
| 4 | Keep the car `IndexGraphLoader` between routes | Saves the `routing section loaded` reads on repeated routes (rerouting, round trips) | Memory: keeps the graphs of every map used alive until the loader is dropped (10 min, or a change of avoid options, or a map refresh); the loader clock is fixed at creation, so it is renewed every 10 min | Yes | switch `cache` |
| 5 | Quiet engine log during a route | Noise only (C5) | None | Yes | switch `quiet` |
| 6 | Also keep the cross-map connectors between routes | Saves reloading connector weights | High memory (the reason for the existing `Purge`); needs a change of how `WorldGraph` owns the `CrossMwmGraph` | Yes | **not implemented** |
| 7 | Progress indicator plus a time cap in the UI | Perceived speed, does not shorten the work | Product decision | n/a | not implemented |
| 8 | Heavier build flags (`-O3`, PGO, ThinLTO) | Unknown, a few tens of percent at best on A* loops | Build memory; flaky OOM | Yes | not tried |
| 9 | Precomputed long-route index (contraction hierarchy or a coarse "highway graph") | Could reach the 2 s target | A data-format and tooling project | n/a | out of scope; needs a product decision |

Honest assessment: **2 s for 620 km is very unlikely to be reached by options 1 to 5**; they remove waste (stage 2 repeats, the 30 s plateau, reloads) but stage 3 and the loading remain. If the target stays at 2 s the only route is option 9. Options 1 and 2 are product decisions because they trade route quality for time.

### Why `prune` keeps the same route
A candidate replaces the current best only if its total is strictly smaller (`RouteWeight::operator<` compares `GetIntegratedWeight()` and then transit time, which is zero for cars). Each part of the total is at least its lower bound: the middle weight is known, and each leg is at least the straight-line distance at the model's maximum speed, which is the heuristic the leg's own A* uses (`EdgeEstimator::CalcHeuristic`), and the penalty terms of the integrated weight are never negative. So when `lower bound of leg 1 + middle + lower bound of leg 2` is already above the best total, that candidate cannot win and its legs are skipped; the same test is applied between the two legs with the real first leg. The bound uses the front of the first segment and the back of the last one so it holds whichever end of a segment the A* charges. Candidates are visited in the same order, so ties resolve as before. What is **not** known is how many candidates this skips: a straight-line bound is loose, and the benefit may be small.

## 4. What was implemented

### 4.1 CoMaps patch `native-comaps/patches/0002-long-route-perf-switches.patch`
Applied idempotently by `scripts/comaps-prepare.sh` (the loop applies every `*.patch`). It only touches `libs/routing/index_router.{hpp,cpp}`; the defaults reproduce the stock code.
- `RoutePerfConfig` with `SetRoutePerfConfig` / `GetRoutePerfConfig`: `m_leapsMaxVertices` (stock 15), `m_leapsTimeoutMs` (stock 30000), `m_pruneCandidates` (false), `m_persistGraphs` (false). The two stage-1 constants became reads of these values.
- Candidate pruning in stage 2 (section 3).
- `IndexRouter::MakeWorldGraph` can hand the world graph a forwarding loader backed by a loader owned by the router (cars only; same avoid options; renewed after 10 minutes; `RefreshMaps` destroys the routers, so a map change drops it).
- `RoutePerfStats`: stage timings (`leaps_ms`, `candidates_ms`, `final_ms`), counts (`leaps_routes`, `candidates`, `legs_pruned`, `subroutes`, `subroutes_cached`), `graphs_reused`, and the engine mode (1 Joints, 2 NoLeaps, 3 LeapsOnly). Timers read the clock only for logging; nothing in the algorithm depends on them.

### 4.2 Wrapper and Kotlin
- `um_core.cpp`: `Core::SetPerfMode(flags)` and `Core::LastRouteStats()`. On **every** route the wrapper applies the configuration (so `0` restores today's behaviour and nothing leaks), and with `quiet` it raises the engine log level to error for the duration of the call with `base::ScopedLogLevelChanger`. JNI: `nativeSetPerfMode`, `nativeLastRouteStats`.
- The bit layout (also in `um_core.hpp` and `RoutePerfMode.kt`): bit 0 `quiet`, bit 1 `prune`, bit 2 `cache`, bits 3 to 4 candidates (0 = 15, 1 = 8, 2 = 5, 3 = 3), bits 5 to 6 stage-1 cap (0 = 30 s, 1 = 10 s, 2 = 5 s, 3 = 2 s).
- `RoutePerfMode.parse("quiet,prune,cache,cand5,tmo5")` with presets `default` (0), `safe` (`quiet,prune,cache`, expected identical routes) and `fast` (`safe,cand5,tmo5`).
- The switch is **not** wired into the production binder protocol or the settings: the app never calls `setPerfMode`, so production behaviour is unchanged. (A user-facing switch would need a settings key, the isolated-core protocol and a decision on options 1 and 2.)

## 5. How to A/B on the phone (debug build only; the owner runs it)

The bench activity takes `--es perf <tokens>` and `--ei runs <n>` (default 1) together with `--ez matrix true`. Each matrix line carries the mode, the exact distance `m=` (meters), duration `s=` and point count `pts=`, and the native stats:

```
matrix perf=<mode> run=<i> Madrid->Barcelona code=0 ms=... km=... m=... s=... pts=... absent=[] stats[perf=... engine_mode=3 total_ms=... leaps_ms=... leaps_routes=... candidates=... candidates_ms=... legs_pruned=... final_ms=... subroutes=... subroutes_cached=... graphs_reused=0]
```

Suggested sequence (each run is a fresh `am start`, which restarts the process only if it was killed; use `am force-stop` first so the first route of each run is cold). The activity id is `com.qtekfun.ultimatemaps/.bench.CoreBenchActivity`; the Pixel 8 rules in `CLAUDE.md` apply (permission, `flock /tmp/pixel-device.lock`, foreground check):

1. Baseline: `am force-stop com.qtekfun.ultimatemaps && am start -n com.qtekfun.ultimatemaps/.bench.CoreBenchActivity --ez matrix true --ei runs 2`
2. Safe switches: same with `--es perf safe`. Check that `m=` and `s=` equal the baseline for every pair (identical routes) and that `run=1` (the second route of each pair) shows `graphs_reused=1`.
3. One option at a time to attribute the gain: `--es perf prune`, `--es perf cache --ei runs 2`, `--es perf quiet`.
4. Quality-for-time options: `--es perf cand8`, `--es perf cand5`, `--es perf tmo5`, `--es perf fast`. Compare `m=` and `s=` with the baseline: a larger `s=` means a worse route.
5. Read the stats of the slow pairs in the baseline (Madrid to Lleida, Barcelona to Madrid, Madrid to Barcelona): if `leaps_ms` is about 30000 the plateau of C2 is confirmed; if `candidates_ms` dominates, C1; if `final_ms` dominates, only option 9 helps.

## 6. Not verified
- Everything about timing and gains. The C++ was compiled but never executed; no unit test covers it (CoMaps routing tests need the full test data).
- The pruning argument assumes `EdgeEstimator::CalcHeuristic` is a true lower bound of every leg, which the A* already relies on; a violation would show as a different `m=` or `s=` with `prune`.
- Whether the loader kept by `cache` interacts badly with a map being deleted while it is held: the held map handles keep the mapped file alive; `RefreshMaps` drops the routers (and with them the loader), but a deletion without a refresh was not exercised.
- C3, the cause of the direction asymmetry, is a hypothesis.
