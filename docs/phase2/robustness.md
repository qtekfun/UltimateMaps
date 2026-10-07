# Navigation robustness (agent G, branch `feat/nav-robustness`)

The user explicitly asked for "robustness in navigation". Facts that motivate the work: the native core **did** abort the entire process (SIGABRT from a CoMaps `CHECK`, Pixel 8; see `docs/decisions.md`, entry "the core started with the wrong style"); in navigation that means losing guidance in the middle of the road. Madrid–Barcelona with 7 regions gives `ROUTE_NOT_FOUND` and with 25 it took ≈ 18 s in the spike.

**What is tested and what is not.** Everything below compiles and is tested on the JVM (`./gradlew test`: 435 tests, 0 failures in the last run). **Nothing has been run on a device and the real native core has not been run** with the isolation: the isolation layer was tested against a fake `NativeBridge` and a fake transport. `BinderCoreTransport`, `CoreService`, `NavigationService` and the manifest compile and have manifest/text tests, but their behaviour on the phone remains to be verified.

## 1. Following (`:core-nav`)

| Topic | What it does now |
|---|---|
| Skipped "now" announcement | `RouteTracker.derive` detects the **crossing by position along the route** (maneuver index going from "ahead" to "already passed"), not just being inside the band. The last maneuver crossed without its "now" receives it (`meters = 0`); earlier ones are considered superseded silently; after a resynchronisation (re-engaging at another point) a turn that was already left behind is not announced. An announcement is never repeated (`mAnnounced` per maneuver and level). |
| Intermediate stops | `RouteGuidance.stops: List<Int>` = geometry indices, in route order. **Backwards compatible**: empty default value, `data class` with the parameter last; `GuidanceWire` (agent A) does not change. Reaching a stop emits `NavEvent.StopReached(stopIndex, point)` (flow `NavigationSession.events`) and **continues**; if the user re-engages beyond it without passing (reroute, long loss) it emits `StopSkipped`. `NavState` gains `stopsRemaining` and `nextStopMeters`. `ARRIVED` only happens at the destination. Stops less than 1 m from the origin are ignored. How they are filled: the engine does not report them, so `RoutePlan.withStops(via)` (in `core-nav`) projects the `via` points of the `RouteRequest` onto the geometry; **agent F must call it** with the panel's stops when starting navigation (`controller.start(plan.withStops(via), trip = NavTrip(profile, options))`). On a reroute, the controller requests the route with only the stops still ahead. |
| Corrupt fixes | Non-finite or out-of-range coordinates: discarded. Duplicates and out-of-order fixes (`t <= last`): discarded. A clock that goes backwards for good (> 5 s): after 3 increasing fixes in the past the time is **rebased** (without this all fixes would be discarded forever). Accuracy > 100 m: ignored (already existed). |
| Spikes along the route (multipath) | A forward jump that the speed does not explain (`> 1.6·v·Δt + 3·accuracy + 60 m`) is ignored unless it repeats 3 times; before, it dragged the progress forward forever (progress never goes backwards) and ended in a false "off route". |
| Long loss | After `NO_SIGNAL` or > 30 s without fixes, if the search window (capped at 5 km) does not contain the car, the search covers the **whole route** (at 30 m/s, 4 min = 7.2 km). The first fix can also be at any point (resume). |
| Odd routes | `RoutePlan.sanitized()` removes non-finite points and reassigns indices of maneuvers, limits and stops; a single point is duplicated (zero-length route that arrives instantly); out-of-range indices are clamped. Repeated points and 40,000 points work (test). `LatLon` already prevents NaN in its constructor. |
| Incoherent reroute | `NavigationSession.isSane`: a new route is discarded if it does not start within 500 m of the user, does not end within 500 m of the previous destination, or has < 2 points. It carries on with the old route and retries (retries/cooldown from `RerouteConfig`). |
| Mid-trip route change | `NavigationSession.replaceRoute(plan)` (conflated queue); `routeRevision` goes up; the new tracker has its own announcements. |
| Protected loop | Each message in the session loop is in `try/catch`: an exception increments `internalErrors` and guidance continues. |

Tests (`RobustnessTest`, `NavigationControllerTest`, `NavStateStoreTest`, …): Gaussian noise + spikes of 500 m and 80 m + accuracy 400 m + fixes without speed/heading (6 seeds); duplicates and swapped pairs; clock jumping back 2.5 years and going backwards for 3 fixes; 4 min loss at 30 m/s; degenerate routes; 40,000 points; resume in the middle; absurd reroutes; properties: no repeated announcement, progress does not go backwards except after a loss/off route/route change, no exception.

## 2. Navigation service (`:app`, `nav/`)

- `NavigationController` (in `:core-nav`, pure JVM, so it can be tested): `state: StateFlow<NavState?>` (null = no navigation), `route`, `problem`, `announcements`, `events`, `start(plan, startAlongMeters, trip)`, `stop()`, `resume()`, `replaceRoute()`, `hasResumable()`. The app holds it in `MapasApp.navigation`.
- `NavigationService`: foreground service `foregroundServiceType="location"`, permission `FOREGROUND_SERVICE_LOCATION` (verified against the Android documentation: Android 14 requires the permission and the type, and the runtime location permission when calling `startForeground`). Notification (silent, `CATEGORY_NAVIGATION`) with the next maneuver and distance, remaining distance and time, and Open and Stop actions. es/en texts in `strings_nav.xml`. No locations in the notification or in logs.
- **Persistent state** (`NavStateStore`): a file in `noBackupFilesDir`, atomic write (temp + `fsync` + `ATOMIC_MOVE`), versioned format with `RoutePlanCodec` (bounded counts: a corrupt file only yields `IOException`; tested with 400 mutations), **expires after 3 h**, deleted on stop, on arrival and on detecting corruption. It is saved on start, on route change and at most every 10 s (30 s in battery saver). It also saves the profile and avoid options, so that reroutes after resuming use the same ones.
- **Resume** if the system kills the process: `START_STICKY` + `intent == null` → `controller.resume()`; if there is no valid state the service stops. If Android prevents starting in the foreground (Android 12+ from the background, or Android 14 without permission) the service gives up quietly: the state stays on disk and the UI must offer `NavigationService.resume(context)` on return (the navigation screen is out of scope).
- **Permission revoked / GPS off**: the controller watches it every 2 s (`NavProblem`), the notification says so, and when the cause disappears listening is restarted (`restartLocation`). With no fixes, the tracker switches on its own to `NO_SIGNAL` with dead reckoning.
- **Battery saving**: position reading stays at 1 Hz (it is the reason for the service); disk writes are reduced and the notification is limited to 1 every 5 s (`NavNotificationThrottle`). No battery-optimisation exemption is requested.
- The `:core` process also creates an `Application`: `MapasApp.onCreate` returns early if it is not the main process (otherwise it would repeat `CoreLinks.sync` and the settings read).

## 3. Isolated core in `:core` (`:native-comaps`, `isolation/`)

```
main process                                    :core process (android:process=":core")
 SearchEngine / DetailedRoutingEngine            CoreService (Binder)
   └ IsolatedCore ──CoreTransport──Binder────►     └ CoreHost ──► CoMapsCore ──JNI──► libumcomaps.so
        (deadline, restart, 1 retry)                 (serialises, init once, chunks responses)
```

- **Contract**: `CoreHandle` (`init`, `refreshMaps`, `searchEngine`, `routingEngine`) is implemented by `CoMapsCore` (in process) and `IsolatedCore`. `CoMapsSearchBackend.prepareCore` returns `CoreHandle`, so `CoMapsRouteBackend` (agent F) compiles unchanged. Safety valve: the preference `core.isolated = false` goes back to the in-process core (until the isolation has been seen on a phone).
- **Lazy, once-only `init`**: `IsolatedCore.init` only remembers the arguments; the process starts on the first real call; the remote `INIT` is sent once per connection and `CoreHost` ignores a second one; after a restart the client reinitialises on its own and repeats the `refreshMaps` if it had been requested. Changing the maps directory restarts the core.
- **Death** (`DeathRecipient`, `DeadObjectException`, `onServiceDisconnected`): it reconnects, reinitialises and **retries ONCE** within the deadline; a second death → `CoreException(CRASHED)`; three deaths in 60 s open a 30 s **circuit breaker** (`UNAVAILABLE`, fails without starting the process) so that a poisoned request does not cause a crash loop.
- **Structured errors**: searches throw `CoreException(kind)` (the `SearchCoordinator` already shows its error state); routes return `RouteOutcome` with codes `CORE_CRASHED`, `CORE_UNAVAILABLE`, `CORE_INTERNAL`, and `CANCELLED` for timeout (F's UI already shows it as "took too long"). The server only sends the exception's **type**, never its message (it could echo a query).
- **Reliable deadline and cancellation**: the blocking call goes to a worker thread and the client waits with a deadline (`native timeout + slack`, +12 s of restart budget if the process dies). If it expires, or the calling thread is interrupted (coroutine cancellation via `runInterruptible`), **the `:core` process is killed** (`OP_KILL`, oneway): it is the only way to stop a native computation, and it frees the worker thread. This way abandoned computations do not pile up (at most one call in flight). The next use starts a clean `:core` (cost: reloading maps).
- **Large results**: Binder limits all in-flight transactions of a process to ≈ 1 MB. Responses > 192 KB are held by the host (max. 4) and the client requests them in 192 KB chunks within the same deadline; if the core dies midway everything is repeated. Tested with 40,000 points (≈ 640 KB) with a fake transport that rejects transactions > 250 KB.
- **Memory**: `:core` loads the `.so` and the `.mwm` files; the main process no longer does. `CoMapsCore()` is only constructed in `:core` (`libumcomaps` is loaded only there). Cost: one more process (~ tens of MB of baseline) and route serialisation. Unmeasured.
- **Security**: service `exported="false"`, `onTransact` checks `getCallingUid() == myUid()`, valid codes only.
- **Tests** (`IsolatedCoreTest`, 20, repeated 3 times without failures): lazy start and single init; refresh remembered after restart; death during the call, during `INIT` and midway through a chunked response; two deaths → structured error; hung call → deadline and kill; cancellation; no accumulation; huge response; circuit breaker; init rejected; Java exceptions and malformed requests; concurrency (8 threads); directory change.

## 4. Failed-route policy (`RouteFailurePolicy`, `RouteRunner`)

| Result | Classification | Advice | Automatic retry |
|---|---|---|---|
| `NEED_MORE_MAPS` | missing maps | download (with region names if the router gave them) | no (deterministic) |
| `START/END/INTERMEDIATE_NOT_FOUND` | point off the road | change the point | no |
| `ROUTE_NOT_FOUND` | no route with these options | change profile/options/points | no |
| `CANCELLED` / deadline | took too long | retry (user decides) | no (it would happen again) |
| `CORE_CRASHED` | the engine restarted | retry | **1** time after 1.5 s |
| `CORE_UNAVAILABLE` | circuit open / does not start | retry later | no |
| other / exception | internal | retry | **1** time |

`RouteRunner.run` never blocks the thread (it suspends), never throws (except cancellation), bounds the **whole** execution including retries to 40 s, and interrupts the thread when it expires or is cancelled (with the isolated core that kills the stuck computation; tested with a fake engine that records the interruption). es/en messages in `RouteFailureMessages`.

**Missing regions.** The catalogue has no geometry, so which regions are missing **cannot be deduced** from it alone. `Route::GetAbsentCountries()` does exist in the core; `RouteOutcome.absentCountries` (new, with a default value) and `RouteFailurePolicy.missingRegionNames` (CoMaps ids → catalogue regions by `comapsId`) are ready, but the value **still arrives empty** because it has yet to be exposed through JNI (a function `nativeLastAbsentCountries()` that reads `route.GetAbsentCountries()` in `Core::Route`; this was not done because recompiling the native core in this environment is very costly and there would be no way to run it). Meanwhile the message is the generic "maps are missing… download them" ("faltan mapas… descárgalos").

**Madrid–Barcelona.** `route_not_found` with 7 regions is expected if intermediate regions are missing (CoMaps needs all the mwm files along the way); with the router giving no names, the app can only say that maps are missing. F's `RoutePreviewController` has its own 30 s deadline and does `work.cancel()` on a blocking call that is not interrupted: with the isolation that call ends by itself at `timeout + 5 s` at the latest (the client kills the core), so they do not pile up; to really cut it at 30 s, F can adopt `RouteRunner`.

## Remaining risks

1. **None of this has been run on a phone.** Typical failures of untested code: real Binder (`bindService` from a worker thread without a `Looper`, `onNullBinding`), `:core` start-up time (mwm loading) versus the 12 s budget, `startForeground` rejected on Android 14, `START_STICKY` under background restrictions. There is a valve (`core.isolated=false`) but no UI for it.
2. **Killing `:core` to cancel is expensive**: after an expired deadline, the next search/route pays the core's cold start (seconds).
3. **Re-queueing of reroutes while `:core` comes back to life**: the session carries on with the last route and retries (3 attempts, 2 s, cooldown 8 s) but there is no core "warm-up" after a death: the first reroute pays the restart.
4. **A deterministic `CHECK` in the same request** (poisoned request) takes `:core` down twice; the user sees an error and the circuit breaker prevents the loop, but that request is neither identified nor blocked.
5. **`GetAbsentCountries` not exposed through JNI** (above): generic "maps are missing" message.
6. **The resumable state saves the route (positions) in `noBackupFilesDir`**, expires after 3 h and is not encrypted; consistent with `allowBackup=false`. No locations in logs.
7. **The UI does not use any of this yet**: there is no navigation screen; `NavigationController.start` must be called with `plan.withStops(via)` and `NavigationService.start(context)` from a visible activity.
8. `CoreLinks.restartNeeded` (a deleted region keeps answering until restart) could now be solved by killing `:core`; it was not touched.
9. The new thresholds (60 m slack on jumps, 30 s long loss, 500 m for reroutes) come from reasoning and synthetic tests, not from real traces.
