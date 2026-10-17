# Speed plan: route calculation and search

Status: **design** (2026-10-17, owner's request: "route calculation and search are very, very slow; design creative optimizations that give us much more"). Nothing in this document has been run on a phone. Every number comes from the measurements already in the repo (`docs/phase2/device-test/route-perf-ab-2026-10-08/`, `route-bench-2026-10-07.md`, `docs/phase1/device-test/release-rc1/README.md`, `docs/spike-informe.md`); every gain is an expectation and says what would prove it.

The earlier analysis (`long-routes-perf.md`) tried run-time switches inside the CoMaps router and concluded that nothing cheap reaches the target. This plan looks at the parts it did not: **what the app does around the core** (serialization, restarts of caches, waiting for results nobody needs yet) and **work the router repeats without need** (a reroute recomputes a route that is 99 % unchanged; the candidate stage runs one search per candidate).

## 1. What is slow, with the numbers we have

### Route (Pixel 8, seven Spanish regions, car, release-grade native build)

Baseline matrix, first run of each pair (`matrix-baseline-clean-2026-10-08.log`, stage split from the `stats[...]` lines of that patch, now removed):

| Pair | km | total | engine mode | stage 1 leaps | stage 2 candidates | stage 3 final |
|---|---|---|---|---|---|---|
| Madrid to Guadalajara | 59 | 0.9 s | Joints | - | - | - |
| Madrid to Zaragoza | 312 | 7.5 s | Joints | - | - | - |
| Lleida to Barcelona | 160 | 6.7 s | Joints | - | - | - |
| Zaragoza to Lleida | 152 | 1.9 s | Joints | - | - | - |
| Madrid to Tarragona | 541 | 10.6 s | LeapsOnly | 5.2 s (524 routes) | 3.3 s | 1.3 s |
| Madrid to Barcelona | 620 | 14.4 s | LeapsOnly | 5.3 s (464 routes) | 6.6 s | 1.6 s |
| Madrid to Lleida | 461 | 23.8 s | LeapsOnly | 4.4 s (391 routes) | **17.9 s** | 0.9 s |
| Barcelona to Madrid | 620 | 32.6 s | LeapsOnly | 6.7 s (526 routes) | **24.1 s** | 1.0 s |

Also measured: urban car routes 0.25 to 0.45 s; during a simulated drive Madrid to Barcelona with nine reroutes, reroutes took 11.3, 11.9, 31.0, 27.6, 7.5, 6.5, 2.9, 1.1 and 0.5 s (`route-perf-reroute-2026-10-08/`), and the way back 28 s.

Reading the table:

1. **Stage 2 (candidate selection) is the outlier**: 46 % to 75 % of the long routes, and it is the whole explanation of the two worst cases (Madrid to Lleida, Barcelona to Madrid). The "reverse trip is twice as slow" mystery of `long-routes-perf.md` (C3) is a stage-2 mystery, not a stage-1 one.
2. Stage 1 is a flat 4 to 7 s and it extracts **391 to 526 routes** to find 15 distinct first and 15 distinct last road features.
3. Stage 3 is 1 to 1.6 s: not worth touching.
4. Routes under about 300 km use the single `Joints` search, and its time is erratic for the distance (Lleida to Barcelona 160 km takes 6.7 s, Zaragoza to Lleida 152 km takes 1.9 s): the A* heuristic is the straight-line distance at the maximum speed, a weak bound.

### Search

- rc.1 on the Pixel 8: first search 1.8 s, warm searches 0.48, 0.78, 1.0, **3.97, 4.2, 3.9 s** (n = 6, whole query typed at once). Later bench: 0.3 to 3.3 s; first search after starting the core process 3.5 s (core start 0.77 s included); cold 6.7 s in the spike. Spike (first batch, loaded phone, all of Spain): median 631 ms, p95 1.7 s. Target: 100 ms.
- The spread (0.5 s versus 4 s for similar queries) is the sign of something waiting for more than it needs; see F3.

## 2. Findings from reading the code today (new)

None of these was in `long-routes-perf.md`.

- **F1. Search and route share one lane end to end.** `IsolatedCore.call` holds `callLock` for the whole exchange, and `CoreHost.dispatch` is one `synchronized(lock)` around every operation. A reroute of 11 to 31 s therefore blocks every search, category browse and even `OP_PING` behind it. The native `Core::Search` does not take the core mutex, so the serialization is ours, not the engine's. While navigating, any search the user types (a fuel stop, "add a stop") waits for the reroute.
- **F2. Every reroute and every guided launch calls `refreshMaps()`.** `CoreRouteProvider.route` and `NavLauncher.launchGuided` go through `prepareCore`, which re-scans the maps. `Core::RefreshMaps` is not a cheap check: it clears all routers (they are rebuilt, including the map tree), clears the search engine caches, reloads city boundaries and the countries tree and re-caches the world localities. Nothing changed on disk in the usual case. It also throws away the search warm state, so the first search after a route is slower.
- **F3. The search waits for the end marker.** `Core::Search` returns only when the engine says the search is over (`results.IsEndMarker()`), after `Mode::Everywhere` has widened beyond the 20 km viewport. The first batch is what matters for typing. The first call also builds an address for each hit (`m_needAddress = true`), which loads the street and house indexes.
- **F4. A stale search cannot be cancelled.** A new keystroke cancels the coroutine, but the native call of the previous query keeps running to its end (and holds the lane, F1), so quick typing queues behind searches nobody wants.
- **F5. A reroute recomputes everything.** The new route is computed from the position to the far destination as if the previous one did not exist, so a 600 km trip costs the full 11 to 31 s for a deviation of a few hundred meters. The follower keeps the old route meanwhile (so it is not unsafe), but the new line and the new guidance arrive late (reported by the owner for rc.18 and rc.19).
- **F6. The kill-to-cancel contract is expensive.** Cancelling a reroute kills the `:core` process (the only cancel the host has); the restart is about 1.1 s plus a cold cache (F2 again). There is no cooperative cancel although `RouterDelegate` has one.
- **F7. Nothing starts work before the user asks.** The core process starts on the first call; the first search pays process start, init, refresh and cold index pages (the 3.5 to 6.7 s).

## 3. The designs

Each item says: idea, why it should work, expected effect, exactness (does the route stay identical?), risk, how to prove it.

### 3.1 Route

**R1. Splice reroute (the big one for navigation).** A deviation does not invalidate the rest of the route. On a reroute, compute not "position to destination" but "position to a point ahead on the old route", and keep the old route from there.
- How: candidate rejoin points on the old polyline at 0.8, 2, 5, 15 and 40 km ahead of the projection of the position (only those that exist). For each, one short routing call (59 km took 0.9 s, 153 km 1.9 s; a 5 km connector is far below that). Total estimate = connector time + old remaining time from that point (the plan already carries the time). Pick the minimum, stopping early when it rises twice. Reject a rejoin where the connector arrives against the old direction (compare the last connector heading with the old route heading, 45 degrees) because the end snap does not know the direction. Join the guidance (`RoutePlan.splice`, pure and unit-tested: connector plus old plan from the join index, the last connector maneuver becomes "continue").
- Then **refine in the background**: start the full recalculation after adopting the spliced route, with a cooperative cancel (R3), and offer the result through the existing faster-route card flow only if it saves at least 5 min and 10 % (the same thresholds as traffic), never within 300 m of a maneuver.
- Expected: the 11 to 31 s reroutes become about 1 to 3 s. Short routes (already 0.3 s) keep the plain path: use the splice only when the remaining distance is above about 100 km.
- Exactness: not identical by design (it can be slightly worse than a full recompute), which is why the background refinement exists. The same trick serves "add a stop" (the leg A to B is reused) and a traffic detour.
- Risk: the join (heading, one-way streets, a rejoin inside a tunnel or on a motorway ramp); mitigations above plus tests with fake route providers and a golden set from the bench activity.
- Proof: JVM tests for `splice` and the chooser; on the phone, the existing `--ez reroute true` drive (nine reroutes) with total and per-reroute time, and routes compared with the full recompute.

**R2. Stage 2 as one-to-many instead of 2 x 15 point-to-point searches (the big one for long routes).** The candidate legs share one end: all start-side legs begin at the start, all finish-side legs end at the finish, and the other ends are the 15 distinct border segments. Today each leg is its own bidirectional A* (`Calc2Times`: a failed `JointSingleMwm` attempt is followed by a second, `Joints`, attempt, and a failed search explores the whole region before giving up). The exits are scattered along the region border, so the union of fifteen A* ellipses is roughly the whole region anyway.
- How: one forward Dijkstra from the start inside the start map that stops when all start-side targets are settled (or the frontier is empty), and one backward Dijkstra from the finish. The weights are the same graph weights, so each leg weight equals the A* optimum (consistent heuristic), and the best candidate is chosen with the same formula (`start leg + middle + finish leg`). Candidates not reached are discarded like a failed leg today. The winner's legs are kept for stage 3 so they are not computed again.
- Expected: stage 2 from 3 to 24 s to about one or two region sweeps, 2 to 4 s (my estimate from the sizes; the real figure is the first thing to measure). Total for Barcelona to Madrid about 32 s to about 12 s; Madrid to Barcelona about 14 s to about 9 s. This is the change that should also remove the direction asymmetry.
- Exactness: the same weights, so the same winner except exact ties; verify with the 12-pair matrix comparing length `m=` and duration `s=`, as the A/B did.
- Risk: it is a patch to `index_router.cpp` (CoMaps patch 0005), a new small search written against `IndexGraphStarterJoints` and its edge-list API. Maintenance cost on every CoMaps update. Medium.
- Fallback if it proves too invasive: **R2b, parallel legs**: run the start-side and the finish-side legs on two threads, each with its own `WorldGraph` from `MakeWorldGraph()` (the `MwmDataSource` is already safe for concurrent readers; the search engine uses it from several threads). Deterministic reduction by candidate index. At best 2x on stage 2, plus memory for a second set of loaders.

**R3. Cooperative cancel and two lanes (enabler for R1 and the search items).** Add a `OP_CANCEL` that sets the `RouterDelegate` cancel flag and the search handle's `Cancel()`, handled outside the host's big lock; split the client lock and the host lock into a route lane and a search lane (and a short lane for ping/cancel). Native side: route keeps `mu`; search is already outside it; `RefreshMaps` and `Init` become exclusive against both (a shared lock for in-flight searches).
- Effect: a search while a route is being computed answers at its own speed instead of waiting up to 30 s (F1); cancelling a reroute no longer kills the process and no longer costs a restart and a cold cache (F6).
- Risk: concurrency inside the core process. `ApplyPlaceLocale` touches global state that routing may read for street names; keep the search locale change under the exclusive lock. Needs a stress test on the phone (search while a long route runs) and a crash check.

**R4. Do not refresh when nothing changed (cheap, certain).** `prepareCore` compares a fingerprint of the maps directory (file names, sizes, modification times) with the last refresh and skips `refreshMaps()` when equal; a download, an update or a delete changes it. Fixes F2, keeps the search warm after routes, and makes the next reroute a few hundred ms cheaper. Expected effect small for routing, noticeable for "search right after a route". Exact: yes. This is the one item that is implemented together with this plan.

**R5. Stage 1 stops earlier (quality for time, needs an A/B).** It extracts 391 to 526 routes to get 15 and 15 distinct road features. Candidate stop rule: at least 6 distinct on each side and the next route's cost already above the best found by a margin that the legs cannot plausibly recover (the legs are bounded by the border-to-border spread of one region). Expected: stage 1 from 4 to 7 s to 2 to 3 s. Not exact: a candidate that would have won through very short legs can be missed; compare `m=`/`s=` on the matrix and keep it only if identical on all pairs.

**R6. Remember the legs of places the user keeps leaving from (after R2).** The result of the start-side one-to-many (a table of cost to each border segment of the start map) depends only on the snapped start segment and the map version. Keep the last N tables for the start points the user uses most (home, work, current parking spot, saved places), in memory and a small private file, invalidated by the map fingerprint (R4). A second route from the same place then skips half of stage 2, and a reroute from near a table (within one block, by adding the connector cost to its nearest junction) can use it too. It is approximate for the candidate choice only; stage 3 stays exact. Defer until R2 is measured.

**R7. Better lower bound for the `Joints` search (research).** Landmark lower bounds (ALT: precompute the distance from a few landmark transitions in each map, bound by the triangle inequality) typically shrink the explored area of a road search several times compared with the straight-line bound. It needs per-map landmark tables built at data time (the weekly data workflow) or lazily on the device and cached, and a new heuristic plugged into `IndexGraphStarterJoints`. Highest effort, uncertain gain on the 150 to 300 km band (2 to 9 s); only if R1 to R3 leave that band slow.

### 3.2 Search

**S1. Two-tier search: quick as you type, full on pause or submit.** The wrapper returns as soon as the first batch with at least N hits arrives (N = 5, with a soft deadline after the first non-empty batch of about 250 ms) and cancels the engine's search; the full search (today's behaviour) runs when the user pauses for about 600 ms, presses the search key, or browses a category. The list is replaced when the full answer arrives only if it differs (no flicker for identical lists).
- Expected: the 3 to 4 s tail disappears for typing; the typical case follows the spike's first-batch figure (median 0.6 s, p95 1.7 s on a loaded phone) and then the items below. Needs: a `quick` flag in the protocol (`SearchArgs`), the native early return, and a UI that treats a quick result as provisional.
- Risk: the first batch ranks differently from the final list; mitigated by replacing when the full answer arrives.

**S2. Native cancel of stale searches (R3's cancel).** A new keystroke cancels the native search of the previous text instead of letting it run to the end and hold the lane.

**S3. Warm up before the first keystroke.** Today the first search pays process start (about 0.8 s), init, the map scan and cold index pages. Start the core and run a throwaway query near the last known position (a) when the search field gets focus (during the keyboard animation) and (b) a couple of seconds after the first frame when regions are installed, the phone is not in battery saver and is not thermally limited; add `posix_fadvise(WILLNEED)` on the search sections of the nearest regions in a low-priority thread to fill the page cache. The cold-start budget of 1 s is not touched because it runs after the first frame. Cost: about 170 MB of `:core` process memory earlier than needed; a Settings switch is not planned unless the owner prefers one.
- Expected: removes the 3.5 to 6.7 s first-search cases. Proof: logcat `UMSEARCH first=true` before and after.

**S4. Addresses on demand.** Run the as-you-type search without `m_needAddress` (name, category, position and distance are enough to choose a row) and ask for the addresses of the visible rows in a second, cheap call, or fill them only for the full search of S1. Gain unknown until measured (the same query with `needAddress` on and off is the experiment).

**S5. Optimistic local filtering.** While the real search runs, show the previous list filtered by the new text (prefix match on name and address) at once. Never final: the engine is fuzzy, so a longer text can match results the shorter one did not, and the real list replaces it. Also instant: recents, saved places and favourites matching the text, merged on top, with no core call.

**S6. Our own type-ahead index (the big rock, only if S1 to S5 are not enough).** A compact, memory-mapped prefix index per region built in the weekly data workflow (normalized names of places, streets and POIs with category, coordinates and an importance rank; a few MB per region), read by a Kotlin reader like `:core-fuel`, answering the first characters in single-digit milliseconds without the engine. The CoMaps engine stays for addresses, house numbers, fuzzy matches and the final list. Cost: a new data artifact and builder, about 3 to 5 % more data, and two result sources to keep consistent.

**S7. Engine settings to measure.** `numThreads` 2 to 3 (a stale and a new query no longer share a thread), a first pass with a 5 km viewport before the 20 km one, `limit` 8 for the quick tier instead of 20.

### 3.3 Perceived speed (cheap, no core change)

**P1. Speculative route on the place card.** When a place card opens and the straight line to it is under about 100 km, compute the route (the user's last profile) in the background at once; when "Directions" is tapped the answer is already there. Urban routes (0.25 to 0.45 s) become instant; a 10 s route has 3 to 5 s head start. A one-route budget per card, dropped if the card closes first (cancel from R3; without it, the call is capped at a 4 s native timeout).

**P2. Honest progress.** For routes above about 150 km show a determinate bar and "Long route, usually N seconds" using the measured tiers (2 s to 150 km, 10 s to 350 km, 20 s to 650 km) instead of an indeterminate wait; with R3 the real `RouterDelegate` progress can be polled.

**P3. Never blank.** Keep the previous list on screen while the next search runs (already the case for typed queries) and use a skeleton row for the first one instead of a "Searching" text.

## 4. Order of work

| Wave | Items | Needs | What it should change |
|---|---|---|---|
| 0 (now) | R4, this document | nothing | search stays warm after routes; the plan is agreed |
| 1: measure | Instrumentation: per-stage and per-leg milliseconds for a route (counts and ms only, no positions, like `UMROUTE` today); first-batch versus end-marker time, and `needAddress` on/off, for a search; the 12-pair matrix and a search script with fixed queries | **owner's permission for the Pixel 8**, regions on the phone (Madrid, Castilla-La Mancha, Castilla y Leon East, Aragon, Lleida, Barcelona, Tarragona plus World files, as in the A/B) | the real split behind R2, S1, S4 |
| 2: client side | R3 (two lanes, cooperative cancel), S2, S3, S5, P1, P2, P3 | Wave 1 for the numbers; phone stress test of R3 | search never waits for a route; first search warm; instant reactions |
| 3: core patches | R2 (or R2b), S1 (`quick`), R1 (splice reroute with refinement), R5 | Waves 1 and 2; a native build; the matrix to prove identical routes | 600 km routes from 14 to 32 s toward 8 to 12 s; reroutes from 11 to 31 s toward 1 to 3 s |
| 4: only if needed | R6, R7, S6 | owner's decision | the remaining distance to the targets |

A faster loop: all the Spanish regions are already on this PC (`~/mapas-data/release-data-261004-20261006`), but the PC has no Qt 6 and no CMake in the path, and the CoMaps integration tests need a desktop platform layer. A host harness (a Linux platform shim for the core, built with the SDK's CMake and Ninja) would let us iterate on R2 and R5 with 14 cores and compare routes bit for bit without the phone, then confirm timings on the Pixel. It is about a day of work and is the best investment if Wave 3 goes ahead; the numbers on a PC would be several times better than on the phone, so only ratios and route equality would be trusted.

## 5. What we will not do (and why)

- **Contraction hierarchies or any new routing graph format**: a data-format project (`long-routes-perf.md` option 9); R1 and R2 attack the same waste inside the existing format.
- **Dropping candidates or quality by default** (`cand*`, `tmo*`): they trade route quality for time and were never measured; R5 is only kept if it returns identical routes.
- **Persisting the loaded graphs between routes (`cache`)**: measured, no gain on a drive and +340 MB.
- **Server-side routing or search**: against the project's rule that everything runs on the device.

## 6. Owner's decisions needed

1. Permission to use the Pixel 8 for Wave 1 (install a build with the extra timing lines, copy the seven regions, run the matrix and a fixed list of searches, restore the phone).
2. Whether to warm the core in the background (S3b) without a switch, with a switch, or only on focus of the search field (S3a).
3. For R1: is a slightly longer route right after a reroute (refined a few seconds later) acceptable, and should the refinement be silent or offered with the faster-route card.
