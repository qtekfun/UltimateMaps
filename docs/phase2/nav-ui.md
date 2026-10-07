# Navigation screen (agent N1, branch `feat/nav-ui`)

Status: **compiles and is tested on the JVM/Robolectric; nothing has been run on a device** (Pixel 8 off limits). The real appearance, camera smoothness and power consumption remain **unmeasured**. Only the drawing of the icons and of the screen rendered with Robolectric (native graphics) has been seen, not on a real screen.

## What is there

| Component | Where | Notes |
|---|---|---|
| "Start" and "Simulate" buttons | `route/RoutePanel` (`navStart`), `nav/NavLauncher` | They request the route **with guidance** (`routingEngine(timeout, withGuidance = true)`) under `RouteRunner` (40 s deadline, bounded retry) and the same native lock as search and preview. Failures are explained with `RouteFailureMessages`. `RoutePreviewController.currentRequest()` provides the request. |
| Guidance through the isolated core | `IsolatedCoreTest` (+2 tests) | **Verified**: `withGuidance` travels in `RouteArgs`, `CoreHost` honours it and `RoutePlanCodec` returns maneuvers, lanes, limits and stops; a guidance of ~3000 maneuvers is chunked under the Binder limit. No code change was needed. The real core with a real Binder has still not been run. |
| Intermediate stops | `NavScreenController.begin` | Calls `plan.withStops(via)`; the session emits `StopReached` and the screen shows "Stop reached" ("Parada alcanzada") for 6 s. |
| Model | `nav/NavScreenController` (`MapasApp.navScreen`) | Lives with the application: the activity can die without losing anything. States: `ON_ROUTE, OFF_ROUTE, REROUTING, NO_SIGNAL, STOP_REACHED, ARRIVED` (`NavPhase`). The arrival summary survives the service stopping the controller. |
| Screen | `nav/NavScreen`, `ui/NavIcons`, `ui/theme/NavTheme` | Banner (icon, distance, street, second maneuver, lanes with the recommended one highlighted), status strip, limit and speed (warning with text, not just colour), ETA, remaining, stop, recenter, simulation bar, summary, resume offer. OSM attribution inside the bottom panel. Own vector icons (48x48, strokes) for the 18 `TurnType` and the 10 `LaneDirection`; the left ones are the right ones mirrored. |
| Glove mode (RF-06) | `NavUi.glove`, `SharedNavUiPrefs` (`nav_ui`/`glove`) | Buttons ≥ 56 dp (≥ 48 dp normal), black/white/yellow, larger text, no fine gestures. "Gloves" switch on the screen itself; Settings will be able to link the same key. |
| Camera | `nav/NavCamera`, `nav/NavHost` | Route heading, 45° tilt, zoom 17.5→15 depending on speed (steps of 0.25; +0.5 near a turn). At most 1 move every 0.8 s and only if it changes by ≥ 6 m, 4° or 0.25 of zoom: stopped car = 0 work. A user gesture (`MapEngine.setCameraGestureListener`, new) stops following and shows "Recenter". |
| Screen on | `nav/KeepScreenOn` | The window's `FLAG_KEEP_SCREEN_ON` (not a wake lock), only while navigating (not in the summary). |
| Simulation (RF-05) | `nav/NavSimulation`, `nav/SwitchableLocationSource` | `RouteSimulator` at 10/30/50/90/130 km/h, adjustable live. Turns off the real source, starts no service and asks for no permission, **is not saved** (`NavigationController.start(persist = false)`, minimal change in `core-nav`; it also clears any previously saved state). |
| Leave and come back | `NavScreenController`, `AndroidNavServiceControl` | The service keeps following; when the activity is recreated `NavHost` repaints the same state. After the process dies: card "Navigation interrupted: Resume / Discard" (`refreshResumable` in `onStart`). Stop: clears route, saved state, map line and service (`stopService`, never `startForegroundService` with the STOP action, which hangs if it does not call `startForeground`). |

## Hook for voice (N2)

`nav/NavEventSink`: `onNavigationStarted`, `onAnnouncement(Announcement)` (no signature changes, each announcement once), `onEvent(NavEvent)`, `onNavigationEnded(arrived)`. Registration: `(application as MapasApp).navScreen.addSink(sink)`. It is called from a background thread; it must return quickly.

## Changes outside its own folder (minimal)

`core-nav`: `NavigationController.start(..., persist)`. `core-map`/`MapLibreEngine`: `setCameraGestureListener`. `MapScreen`: `navigating`/`overlay` parameters (hides the sheet and buttons; by default everything is the same). `PanelHost`, `SheetPanel`, `MainActivity`, `MapasApp`: wiring (≈ 25 lines). `RouteRunner.timeoutMillis` becomes public. String `nav_onto_street` in Spanish: "a" → "en".

## Tests (JVM/Robolectric)

States and phases, full simulation up to arrival, intermediate stop, off route with no route and reroute with a fake engine, no signal, resume/discard, stop from outside, activity recreation (screen flag, camera, line), banner with the 18 turns, lanes, limit and excess, gloves ≥ 56 dp, es/en, Start button. The model tests run on a single thread with a manual clock and the simulation yields (`yield`) between fixes: no time-based waits (repeated 3 times without failures).

## Not verified

- Real appearance and smoothness; battery consumption of the camera at 1 Hz; legibility in sunlight and with real gloves.
- `startForegroundService` and the notice on Android 14 from the screen; location permission when pressing "Start" if it had not been granted yet (it is requested and the problem strip warns; the result is not awaited).
- The camera does not shift the user to the lower third (it is centred on the position); the route line is drawn in full, not only what remains.
- Real guidance with the real `:core` (lanes on a motorway untested); reroute with the real core while driving.
- Voice: out of scope (N2).
