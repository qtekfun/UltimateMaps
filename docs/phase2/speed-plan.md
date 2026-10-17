# Speed plan: route calculation and search

Status: **design, first measurements done** (2026-10-17: section 7 holds what was measured on the Pixel 8 with the owner's permission and what it changed in the plan; owner's request: "route calculation and search are very, very slow; design creative optimizations that give us much more"). Nothing in this document has been run on a phone. Every number comes from the measurements already in the repo (`docs/phase2/device-test/route-perf-ab-2026-10-08/`, `route-bench-2026-10-07.md`, `docs/phase1/device-test/release-rc1/README.md`, `docs/spike-informe.md`); every gain is an expectation and says what would prove it.

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
- **F3. The search waits for the end marker.** `Core::Search` returns only when the engine says the search is over (`results.IsEndMarker()`). *Measured later (section 7): the end marker comes 6 to 480 ms after the one batch the engine emits, so waiting for it costs almost nothing; this finding is retracted.* The first call also builds an address for each hit (`m_needAddress = true`).
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

**S1. Two-tier search: quick as you type, full on pause or submit. (Retracted by the measurements: the engine emits a single batch, so there is no earlier first batch to return; see section 7. The idea survives only as "fast text first, exact text later", which section 7 replaces with a better one.)** The wrapper returns as soon as the first batch with at least N hits arrives (N = 5, with a soft deadline after the first non-empty batch of about 250 ms) and cancels the engine's search; the full search (today's behaviour) runs when the user pauses for about 600 ms, presses the search key, or browses a category. The list is replaced when the full answer arrives only if it differs (no flicker for identical lists).
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

## 7. Measured on the Pixel 8 (2026-10-17, owner's permission) and what changed

Setup: debug build of master plus the bench; first the seven regions of the long-route A/B, then all of Spain (26 `.mwm` files incl. World, 1.2 GB more copied with `run-as`); the real app driven by `uiautomator` input and the `UMSEARCH`/`UMROUTE`/`UMCORE` logs; the in-process bench `--ez urban true` (new: everyday routes and searches with repeats). Raw logs are in `~/mapas-data/bench/speed/` (not in the repo). Single runs on a phone that other sessions also use; the same query varied by up to 70 % between runs.

**Routes (core alone, Sol to the destination, each repeated 4 times; same with all of Spain)**

| Trip | car | on foot |
|---|---|---|
| Sol to Gran Via, 0.8 km | 220 to 455 ms | 330 ms |
| Sol to Atocha, 3 km | 230 to 250 ms | 430 ms |
| Sol to Chamartin, 12 km | 330 to 410 ms | **1,300 ms** |
| Sol to Barajas, 22 km | 290 to 330 ms | - |
| Sol to Alcala de Henares, 21 km | 360 to 415 ms | - |

In the app (binder, one core process): car 435 to 441 ms, on foot 573 to 639 ms, bike 696 ms for the 470 m Sol to Gran Via trip; it was on screen less than 2 s after the tap.
- A 0.8 km route costs about as much as a 22 km one: **the cost is fixed**. `simpleperf` over 8 s of urban routes: **64.5 % is `routing::DeserializeIndexGraph`** (called from `IndexGraphLoaderImpl::GetIndexGraph`, first reached by the dead-end check of the start snapping, `FillDeadEndsCache`), i.e. the router reads and decodes the whole road graph of the region again for every route, and another 14 to 21 % is `free`/`malloc` of that work. This is the "everything is rebuilt per request" cost C4 of `long-routes-perf.md`, now with a number: it is about 150 to 200 ms of every urban route, and it is also paid by every reroute.
- Having all of Spain installed (26 maps against 7) does not change the route times; core start plus map scan is 0.25 s in the bench (1.2 s in the app on the first search).

**Search (real app, map centred on Madrid)**

| Query | before | with linking words removed (new) |
|---|---|---|
| gran via | 0.76 to 1.06 s | unchanged (no linking word) |
| puerta del sol | 5.1 s (bench 2.2 to 3.6 s) | 1.3 to 1.8 s |
| plaza de espana | 8.9 s (bench 7.2 to 9.1 s) | 1.3 to 2.2 s |
| calle de alcala | about 8 s | 1.5 to 2.7 s |
| paseo del prado | 3.7 s | 1.4 to 2.1 s |

- **The cause of the multi-second searches is the linking words.** Same engine, same map: `plaza espana` 0.85 s against `plaza de espana` 9.0 s; `puerta sol` 0.78 s against `puerta del sol` 3.6 s; `calle alcala` 0.9 s against `calle de alcala` 7.8 s; `alcala` 0.77 s; `plaza de` alone 4.6 s against `plaza` 0.77 s. Every Spanish place name has "de" or "del"; the engine has no stop-word handling for names (only for house numbers). `simpleperf` of the slow search: `coding::SparseCBV::GetBit` (intersecting very large candidate sets), `IntervalIndex::ForEachLeaf`, `ReaderCache::ReadPage` and plain `read`/`lseek` syscalls (about 25 % of the samples), plus allocator churn.
- Dropping the words and putting the names that contain the typed phrase first gives the same first results (`plaza de espana`: same top four; `calle de alcala`, `puerta del sol`: same top six; `paseo del prado`: top five; `plaza de cibeles`: top four), so it **was implemented** (`QueryParticles`, `PlaceLocaleSearchEngine`, 13 JVM tests). The tail of the list differs from the exact search. This replaces S1's "two tiers": there is no early batch to return, and an exact second tier would cost 5 to 9 s and, without cancel (R3), would hold the lane.
- The engine emits **one batch** and the end marker follows 6 to 480 ms later (UMCORE `Emitting a new batch`), so F3 and S1 were wrong about a cheaper first batch.
- Engine settings tried through a temporary file read by the native search (removed, not shipped): without addresses 40 % faster on fast queries (gran via 386 to 238 ms, atocha 367 to 202 ms) but nothing on slow ones, and the list needs the address line; asking for 8 results instead of 20: no difference (the cost is in finding candidates, not in ranking); viewport-only mode with a 20 km or 5 km radius: slow queries about half (plaza de espana 8.2 to 4.6 and 3.3 s) but fewer results for far or specific queries (`gran via 30`: 5 to 6 results instead of 20). None adopted.
- Typing a query in the real app sent a single search (the field's 250 ms debounce works).

**Consequences for the plan**
1. Search: done for the linking words. Left: the first search after starting the app (engine ready 1.2 s, cities boundaries load 0.3 s, then 0.8 s of search) which S3 (warm-up) targets, and the remaining 0.4 to 0.8 s of normal queries.
2. A new item for route, **R8: keep the deserialized road graph between routes, bounded**. It is the same idea as the removed `cache` switch (measured then: urban 304 to 84 ms, reroute 9 508 to 289 ms) but limited to the two most recently used maps and dropped after a few idle minutes, so it does not pin 340 MB after a long trip. Expected: urban routes and reroutes inside a region from about 220 ms to about 85 ms (2 to 3 times), on foot from 330 to about 150 ms. It needs the CoMaps patches of the old switch (persistent loader and the handle fix) restored in a bounded form.
3. R1 to R3 stay as they are; they target the long trips (14 to 32 s), not the everyday ones.

## 8. R8 done: a bounded road-graph cache (CoMaps patch 0005)

What it is: `IndexRouter` keeps one `IndexGraphLoader` between routes (same avoid options, at most 10 minutes old because its clock for conditional access is fixed at creation). After every route it keeps the decoded graph and geometry loader of the **two most recently used maps** and drops the rest, and frees the handles of the dropped maps only (`MwmDataSource::FreeHandlesExcept`). The graphs point into the handles, so they are never freed while a graph needs them (the bug that crashed the old `cache` switch). `Core::TrimCaches` (try-lock: it does nothing while a route runs) drops everything; the `:core` service calls it from `onTrimMemory` when the system is low or the process is cached. A map refresh destroys the routers and with them the cache.

Measured on the Pixel 8, all of Spain installed (26 maps), same device and session, patch off against on:

| Trip (Sol to ...) | car before | car now | on foot before | on foot now |
|---|---|---|---|---|
| Gran Via 0.8 km | 220 ms | 65 ms | 330 ms | 33 ms |
| Atocha 3 km | 240 ms | 65 ms | 430 ms | 100 ms |
| Chamartin 12 km | 340 ms | 140 ms | 1,300 ms | 980 ms |
| Barajas 22 km | 300 ms | 93 ms | - | - |
| Alcala 21 km | 380 ms | 150 ms | - | - |

- Real app (one core process, the 470 m trip): car 441 to 117-144 ms, on foot 573-639 to 104-180 ms, bike 696 to 107 ms. The first route of each vehicle after the core starts, or after a trim, still reads the graph (car 367-420 ms, on foot 495-618 ms, bike 589 ms).
- **Same routes**: all 12 pairs of the long-route matrix give exactly the same length, duration and point count as the earlier baseline; every step of the simulated Madrid to Barcelona drive (initial, nine reroutes, way back) matches the unpatched run.
- Long routes, matrix first run, ms (patch off to on): Madrid to Guadalajara 1,286 to 1,099; Medinaceli 2,821 to 1,836; Zaragoza 13,287 to 10,640; Lleida 9,982 to 9,547; Tarragona 17,662 to 14,474; Barcelona 23,437 to 20,625; Zaragoza to Lleida 2,581 to 1,989; Zaragoza to Barcelona 15,325 to 12,381; Lleida to Barcelona 11,310 to 7,823; Barcelona to Madrid 46,865 to 39,476. The drive with nine reroutes: 208.7 s to 200.3 s in total (reroute 9: 783 to 448 ms; the urban step at the end 358 to 105 ms).
- Memory (bench, in-process, the simulated drive): native heap 125-138 MB to 197-253 MB, PSS 350-379 MB to 414-473 MB, i.e. 60 to 100 MB more, bounded (the removed switch held 340 MB). In the app (all three vehicles used): core process PSS 380 MB; after `onTrimMemory` (moderate) 279 MB and native heap 280 to 175 MB; the next routes worked and re-read their graphs.
- **A finding for the long routes:** with all of Spain installed the long routes are much slower than in the seven-region A/B (Madrid to Barcelona 23.4 s against 15 s, Barcelona to Madrid 46.9 s against 31.7 s, Madrid to Zaragoza 13.3 s against 7.5 s): more maps give the leaps stage more candidates. The owner's phone has all of Spain, so R2 (one-to-many candidate legs) and R1 (splice reroute) matter more than the first table suggested.
- Not verified: behaviour under a real low-memory kill; a download or map update while the cache is held (the refresh drops it, tested only by reading the code); conditional access (opening hours of roads) after ten minutes of the cache (the loader is recreated, not tested with such data).

## 9. Where a long route spends its time with all of Spain, and the leg budget

Method: the engine's own log lines (`Routing in mode`, `Filtered candidates count`, `Route build time`, forwarded to logcat as `UMCORE`) plus, in a throwaway build, one log line per candidate leg (`RoutesCalculator::Calc2Times`: milliseconds of the single-map attempt, whether it was retried in Joints mode, total). The throwaway line is not in the code.

Split of the long routes (Pixel 8, 26 maps, graph cache on):

| Trip | leaps stage (to "Filtered candidates") | candidate legs and final route |
|---|---|---|
| Madrid to Lleida | 7.0 s | 2.0 s |
| Madrid to Tarragona | 7.8 s | 4.5 s |
| Madrid to Barcelona | 7.9 s | 9.8 s (one leg failed in 4 ms and took 2.1 s more in Joints mode) |
| Barcelona to Madrid | 12.5 s | **32 s: one leg searched 22,988 ms inside one map, failed, and the Joints retry then took 232 ms** |

- A typical successful leg takes 0.1 to 1.1 s. So stage 2 is not 15 slow searches as assumed in R2: it is a few fast legs and, sometimes, **one leg whose single-map search cannot reach its target and only stops after visiting the whole map**. That one leg is 23 of the 45 s of Barcelona to Madrid, and it is the whole "reverse trip is twice as slow" mystery. R2's one-to-many rewrite is not needed for this case.
- **Done (CoMaps patch 0005, second part):** the single-map first attempt of a leg gets a 3 s budget (`BudgetedCancellable`: the route's own cancellation plus a deadline); when it runs out the leg continues in Joints mode, the path the code already takes after a failure. Barcelona to Madrid 42 s to 22 s in two runs, exactly the same route (length, duration, points); the other 11 pairs return the same routes (their times moved by up to +-40 % between runs on the shared phone, so no other gain is claimed).
- **Tried and discarded:** forcing the LeapsOnly mode for trips above 120 km and ignoring the neighbouring-maps test (to avoid the slow single Joints search of 150 to 312 km). It was worse: Madrid to Medinaceli 1.9 s to 45 s, Zaragoza to Barcelona 10.9 s to 31 s, and Zaragoza to Lleida came out 200 km instead of 152 km. The routes of Madrid to Zaragoza, Lleida to Barcelona and Medinaceli differed in duration by a second or so.
- What is left for the long routes: the leaps stage (7 to 12.5 s, it reads 400 to 500 routes to find 15 and 15 distinct road features; more maps make it longer), the 150 to 312 km trips in Joints mode (Madrid to Zaragoza 10 s, Zaragoza to Barcelona 10.5 s, Lleida to Barcelona 7.2 s: a weak straight-line bound), and the connectors read again per route. R5 (earlier stop of the leaps stage), a better bound for Joints (R7) and keeping connectors are the next candidates; R1 (splice reroute) removes the long routes from navigation altogether.
