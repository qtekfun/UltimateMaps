# Route following (`:core-nav`)

Covers RF-05 (turn-by-turn, rerouting, lanes, speed limit, route simulation) and the logic part of RNF-05
(the foreground service only feeds and observes this engine). Pure Kotlin JVM module: `./gradlew :core-nav:test`
runs in seconds and without Android.

## Components

| Component | What it does |
|---|---|
| `RouteGeometry` | Measured polyline (haversine distances, local flat projection per segment). Allocation-free window search. |
| `RouteTracker` | Synchronous, deterministic core: `onFix`, `onTick`, `snapshot`. Time comes only from fixes and ticks. Not thread-safe. |
| `NavigationSession` | Coroutine wrapper: reads a `LocationSource`, publishes `state: StateFlow<NavState>`, `announcements: SharedFlow<Announcement>` and `route: StateFlow<RoutePlan>`. Launches and cancels reroutes. |
| `RouteSimulator` | Fixes of someone walking the route at constant speed, with reproducible Gaussian noise (`java.util.Random(seed)`) and gaps. Used by the tests and by the app's route simulation. |
| `NavConfig` | All thresholds (below). |

`NavStatus`: `ON_ROUTE`, `OFF_ROUTE`, `REROUTING`, `ARRIVED`, `NO_SIGNAL` (identifiers in English like the rest of the code).

The tracker is only touched from one coroutine (a single consumer of a fix queue that drops the oldest fix if it
backs up): there are no locks. The result of a reroute comes back through another conflated channel and is applied by that same coroutine.

## Progress along the route

1. **Window.** The nearest segment is searched only between `anchor - (40 m + 2·accuracy)` and
   `anchor + 80 m + 3·accuracy + max(2·v, 15 m/s)·Δt`, where `anchor` is the progress of the last good fix and Δt the time
   since it (capped at 5 km). This is what keeps a route with loops or crossings from jumping to another pass through the same point: the other pass
   falls outside the window. The first fix uses 2 km (the route may start where it ends, and the lower progress wins a tie).
2. **Heading.** The score is `distance + 25 m · heading_difference/180°` (only with heading and speed ≥ 1.5 m/s). It acts as a
   tie-breaker on overlapping or opposite stretches (U-turns, out and back along the same street). It discards nothing: it only penalises.
3. **Alpha-beta filter of the progress.** Prediction `anchor + v·Δt`; correction `α = 1/(1 + (accuracy/8)²)` (accuracy 5 m → 0.72;
   20 m → 0.14). With 20 m of noise the progress is smoothed instead of jumping; with 5 m it follows with almost no lag. If the fix carries
   a speed it is used (moving average 0.5); otherwise the speed is learned from the innovation.
4. **Monotonicity.** `progress = max(anchor, filtered)`. The only time it may go backwards is when resynchronising (first fix,
   after NO_SIGNAL, after >10 s without fixes, or on re-engaging), because the estimate may have overshot.
5. **Stopped.** With a fix speed < 0.5 m/s the progress does not move: without this the `max()` makes the progress drift
   forward with noise (tested: 600 fixes of 8 m noise while stopped leave the progress within ±1 m).

## Off route (hysteresis)

Threshold `max(30 m, 2·accuracy)`: 30 m covers the road width and the typical error of a good GPS; 2·accuracy (Android's
accuracy is a 68 % radius) leaves out the noise tail in an urban canyon.

A fix is "off" if `distance > threshold` or if it goes the opposite way (heading more than 135° from the segment at ≥ 3 m/s). `OFF_ROUTE`
is confirmed when any of the following holds:

- ≥ 5 consecutive off fixes **and** ≥ 3 s since the first (the normal case; 3 s keeps a burst of repeated fixes from counting as 5);
- ≥ 2 fixes and ≥ 10 s (sparse fixes: 1 every 5 s would take 25 s with the previous rule);
- ≥ 3 consecutive fixes at more than 3× the threshold (clear deviation: no need to wait longer).

A fix at ≤ 0.7·threshold resets the count (hysteresis band: between 0.7 and 1× the threshold it neither counts nor resets). An isolated garbage
fix (3 km away) counts as one and the next good one clears it; a fix with accuracy > 100 m is ignored entirely (it neither
moves the progress nor hides a signal loss). While there are unconfirmed "off" fixes, the progress stays still.

**Re-engage:** in `OFF_ROUTE`/`REROUTING`, a fix at ≤ 0.7·threshold goes back to `ON_ROUTE` and resynchronises (the window grows with
the time spent off route, so it covers shortcuts). The session then cancels the reroute in progress and discards its result.

## Reroute (`NavigationSession`)

Injected `suspend (from: LatLon, heading: Float?) -> RoutePlan?`. Only one at a time. Up to 3 attempts per cycle, 2 s between
them (plus however long each takes); if all fail it goes back to `OFF_ROUTE` and waits 8 s before another cycle while
still off route. An exception counts as a failure; so does a route with < 2 points. `CancellationException` is propagated (`stop()`
cancels). On success a new `RouteTracker` is created (`routeRevision + 1`) and the last fix is forwarded to it.

## Arrival

`ARRIVED` (final) if ≤ 25 m of route remain (the radius of a doorway/parking spot) or if ≤ 60 m remain and it has been stopped for ≥ 8 s
(< 0.8 m/s): parking before the door also counts as arriving. It is measured on the projected progress, not the distance to the point, so that
a route that ends where it starts does not "arrive" at the departure. On arrival the pending `NOW` announcement of the last maneuver is emitted.

## Signal loss

With no usable fix for 5 s (`onTick`, which the session calls every 1 s) → `NO_SIGNAL`, `estimated = true`, progress = last progress
+ speed · time (capped at 30 s of estimation, never going past the last metre: you do not arrive inside a tunnel). Announcements
keep coming out using the estimate. When a fix returns the real one wins and it resynchronises. If it was already off route it does not switch to
`NO_SIGNAL`.

## Voice announcements

Three levels per maneuver, `clamp(v·seconds, min, max)`:

| Level | seconds | min | max | At 10 m/s | At 36 m/s | On foot (1.4 m/s) |
|---|---|---|---|---|---|---|
| `FAR` | 30 | 200 m | 2000 m | 300 m | 1080 m | 200 m |
| `NEAR` | 10 | 60 m | 400 m | 100 m | 360 m | 60 m |
| `NOW` | 3 | 20 m | 80 m | 30 m | 80 m | 20 m |

Each level is emitted at most once per maneuver and only moves up in urgency. If a fix crosses several thresholds at once
(fast, or with gaps) only the most urgent is emitted and the less urgent ones are dropped: "in 500 m" is not read out at 60 m.
Only the next maneuver is announced; `DEPART` and maneuvers already passed are considered done.
Known limit: if between two fixes the car clears the whole `NOW` band (80 m at most, i.e. ~2.2 s at 36 m/s) and the
maneuver is left behind, its `NOW` is not emitted; the 36 m/s test with fixes every 2 s (72 m) checks that this does not happen yet.

## Speed limit

Per segment (filled once when the route is loaded; `kmh = null` clears the limit). Exceeding it uses a configurable tolerance
(0 by default) and 2 km/h of hysteresis so the warning does not flicker with the noise in the GPS speed.

## Performance

Microbenchmark (`TrackerBenchmarkTest`, `./gradlew :core-nav:test --tests '*Benchmark*' -i`). 100 km route (5001
points, 250 maneuvers), 3995 fixes at 25 m/s with 5 m noise. Only the per-fix loop is measured (without building the tracker).
Output pasted from one run, on this PC (Intel Core Ultra 7 265U, JDK 21, JIT warm after 40 passes):

```
BENCH route=5001 points, 250 maneuvers, 3995 fixes/pass
BENCH onFix only:      516 ns/fix, 0,0 B/fix allocated
BENCH onFix+snapshot:  818 ns/fix, 167,9 B/fix allocated
```

Other runs gave 620-756 ns (`onFix` only): the run-to-run noise is ±30 %. This is a desktop CPU with a JVM;
it is **not** a measurement of the Pixel 8 or of ART. `onFix` does not allocate; the published `NavState` (≈ 170 B, one per fix) is the only
per-fix allocation, and it happens in the publisher, not in the search. Building this route's geometry (once) allocates ≈ 400 KB.

## Tests

`RouteTrackerTest` (synchronous): straight, L, roundabout, U, loop with two passes through the same stretch (with and without heading), 5 m and
20 m noise (20 seeds each, without leaving the route, error < 15 m / 45 m), isolated garbage fix, off-route confirmation
(by fixes, by time and by clear deviation), reset by a good fix, accuracy that widens the threshold, re-engage, opposite direction,
arrival (radius and stopped), route that ends where it starts, fast announcements (36 m/s with fixes every 2 s) and on foot, nearby
maneuvers, first fix mid-route, limit and excess with hysteresis, lanes, tunnel, stopped with noise.
`NavigationSessionTest` (coroutines with virtual time): full following, reroute (false from a bad fix; real; retries and
wait without overlapping; exception; unusable route; cancellation on stop; cancellation on re-engage), tunnel and initial silence.
`RouteSimulatorTest`: speed, reproducibility, standard deviation of the noise, gaps.

## Pending / limits

- The remaining-time estimate is linear in distance (`duration · remaining/total`); it uses neither the real speed nor the limits.
- No road map: the window and the heading resolve overlaps, but a route that passes twice through the same place
  *within the same 100-150 m window* (e.g. a very tight figure-8 crossing) may pick the wrong pass until it moves away.
- Persisting the state to survive process death (architecture, "Navigation service") and the foreground service
  are left for the Android layer; `NavigationSession(plan, …)` can be resumed with the last known fix (first fix: 2 km window).
- The voice strings and their translation (es/en) belong to the `VoiceGuide` layer; only `Announcement` comes out here.
