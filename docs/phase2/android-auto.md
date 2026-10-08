# Android Auto: analysis and plan

Date: 2026-10-08. Status: plan only, no app code written. Author: research agent. Nothing here was run on a device, on the Desktop Head Unit (DHU) or in a car. Every statement is tagged by how it was obtained:

- **[doc]** read in the official Android developer documentation, the AndroidX release page, the Google Maven POMs or the `android/car-samples` repository on 2026-10-08 (URLs in "Sources").
- **[code]** read in this repository.
- **[memory]** from the author's background knowledge, not re-checked. Verify before relying on it.
- **[unverified]** a guess or an inference.

## 1. Summary and recommendation

1. Use the **Car App Library** (`androidx.car.app`, Apache-2.0) in the existing single `foss` flavor. It needs no Google Play Services on the app side and its POMs pull no GMS artifact **[doc]**. The earlier assumption in `docs/mapas-03-arquitectura.md` ("a `gms` flavor if the Car App Library is needed") does not hold; the file should be updated when stage 0 lands.
2. Keep the glue isolated (package `com.qtekfun.ultimatemaps.auto` plus a pure-Kotlin model module), so that removing Android Auto is one dependency, one manifest block and one package.
3. Reuse the existing navigation: `NavigationController.state` (`NavState`), `NavScreenController.begin(...)`, the foreground `NavigationService`, `VoiceGuide` and the CoMaps routing/search engines. The car side only observes `NavState` and renders templates. No second navigation engine.
4. **Fast path to the DHU this afternoon:** stage 0 (about 3 to 4 hours of work if nothing surprises us): dependency + manifest + `CarAppService` + `Session` + one list screen (saved places and recent searches) that starts a **simulated** navigation and then shows a `NavigationTemplate` with live maneuver, distance and ETA. No map yet (stage 1 adds it). Exact files and commands in section 6.
5. **The biggest risk is not technical, it is distribution.** The docs say Car App Library apps are not covered by Android Auto's "Unknown sources" developer option, and that testing in a real car requires installing from a trusted source such as Google Play (internal test track or Internal App Sharing) **[doc]**. An F-Droid or GitHub-APK build may therefore never show up in a real car's launcher. The DHU is expected to work with an adb-installed debug build (all Google samples are run that way) but this is **[unverified]** here. The owner needs to decide whether a Google Play track (developer account, one-time fee, i.e. spending money, rule 4 of CLAUDE.md) is acceptable only for car testing or at all.

## 2. Findings with sources

### 2.1 Library, versions, licence, dependencies

- Artifacts, all with the same version: `androidx.car.app:app`, `app-projected` (Android Auto), `app-automotive` (Android Automotive OS, not needed), `app-testing` (JVM tests) **[doc: AndroidX release page]**.
- Releases on 2026-10-08 **[doc: release page]**: stable **1.7.0** (2025-07-16, first stable with the CVE-2024-10382 fix); 1.8.0-rc01 (2026-08-26, also contains a security fix); 1.9.0-alpha03 (2026-10-07, introduces Car App API level 9, `SearchHeader` needs level 9). minSdk of 1.7.0 is not stated on the page; 1.8.0-alpha03 raised the default to API 23 **[doc]**. The project minSdk is 26, so either works.
- Recommendation: **1.7.0** for stages 0 to 3 (stable, has all templates we need). Move to 1.8.0 when it is stable (it carries a security fix). Do not use the 1.9 alpha.
- Licence: Apache-2.0 for `app` and `app-projected` **[doc: POMs]**. Compatible with GPLv3.
- Transitive dependencies of `app:1.7.0` **[doc: POM]**: kotlin-stdlib 1.8.22 (managed), jspecify 1.0.0, androidx.lifecycle (common-java8 2.2.0, viewmodel 2.2.0), androidx.core 1.7.0, androidx.activity 1.2.0, androidx.annotation(-experimental), **com.google.guava:guava:31.1-android** (runtime), androidx.media:media 1.6.0. `app-projected:1.7.0` adds only annotation, jspecify and `app`. **No `play-services-*`, no Firebase, no `com.google.android.gms`.** Guava is Apache-2.0. Gradle will resolve most of these to the versions already in the build graph; guava is new for this project (size cost not measured; R8 is not enabled for release today, see `app/build.gradle.kts`).
- The manifest key `com.google.android.gms.car.application` is only a string used by Android Auto to find the app descriptor; it creates no dependency on Play Services **[doc: car-samples mobile manifest]**.
- The `:app` module has no Material or AppCompat; the Car App Library does not use Compose, so it does not conflict with the Compose BOM **[code + memory]**.

### 2.2 Is the host Google-only?

- The library is only the app-side client. The **host** is the proprietary Android Auto app (`com.google.android.projection.gearhead`) from Google Play, which the DHU docs require ("Install Android Auto on the device, or update it"; from Android 10, sign in to the Play Store and update Android Auto) **[doc: DHU page]**. The host itself needs Google Play services on the phone **[memory]**. So: our app side stays FOSS and GMS-free; the feature can only be used by people who have Android Auto, which means mostly Google-certified phones (GrapheneOS with sandboxed Play can run it **[memory, unverified]**). This does not violate CLAUDE.md: we ship no GMS code; we only declare a service a Google app may bind to. It must still be stated honestly in README and F-Droid description.
- Zero telemetry: the host (not us) may log what is shown on the car screen. Anything we put into a template (destination names, street names) goes to Google's host process on the same phone. This is a privacy statement the owner should accept and `PRIVACY.md` should mention. **[inference from architecture, not verified what the host does]**.
- The host validates the caller of our service via `HostValidator`; the library's default behaviour accepts the known Google hosts, and debug builds can allow any host **[memory]**. For release we use the default (allow list of Google signatures); never `ALLOW_ALL_HOSTS_VALIDATOR` in release.

### 2.3 F-Droid angle

- F-Droid builds from source with its scanner, which rejects known proprietary binaries/SDKs. `androidx.car.app` is open source (AOSP, Apache-2.0) and not a Play Services SDK; I know of no scanner rule against it **[unverified]**.
- Precedent: the F-Droid package page of **Organic Maps** lists `androidx.car.app.ACCESS_SURFACE` and `androidx.car.app.NAVIGATION_TEMPLATES` among its permissions, i.e. an F-Droid app already ships the Car App Library (found via web search 2026-10-08; whether it works for users from an F-Droid install is not established: a GrapheneOS forum thread reports Organic Maps from Aurora Store not appearing in Android Auto, unresolved).
- AndroidX libraries already come from Google's Maven repo in this build (Compose, activity), so F-Droid's tolerance for that repo is already exercised (`docs/decisions.md` has the F-Droid readiness work). Adding more AndroidX artifacts does not change the category of the risk. Check `fdroid/` metadata for an anti-feature note; none is expected.
- **Recommendation: no new flavor.** Keep one `foss` flavor, with the dependency in `:app`. If F-Droid ever objects, the fallback is a second flavor `foss` (without) and `car` (with) using a `src/car` source set; the isolation in section 3.4 keeps that cheap. Per CLAUDE.md rule 1 this is not a "doubtful licence" case, so it does not need owner approval; record it in `docs/decisions.md` and `LICENSES.md` (rows for car-app, car-app-projected, car-app-testing (tests only), guava 31.1-android, jspecify, androidx.media, plus the NOTICE texts, since the app shows `NOTICE` in About).

### 2.4 Manifest and permissions

From the official navigation guide and the `car-samples` navigation sample **[doc]**:

- `<uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES"/>` (navigation apps only; never together with `MAP_TEMPLATES`, the app would be rejected).
- `<uses-permission android:name="androidx.car.app.ACCESS_SURFACE"/>` to draw the map on the host surface.
- `<service ... android:exported="true">` with `<action android:name="androidx.car.app.CarAppService"/>` and `<category android:name="androidx.car.app.category.NAVIGATION"/>`; optional `<category android:name="androidx.car.app.category.FEATURE_CLUSTER"/>` (Car App API level 6, only `NavigationTemplate`, map tiles only).
- `<meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1"/>` inside `<application>` (sample uses 1).
- For Android Auto, `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc"/>` and `res/xml/automotive_app_desc.xml` containing `<automotiveApp><uses name="template"/></automotiveApp>` (sample). The automotive-OS variant uses `com.android.automotive` instead; not needed.
- Navigation intents: the sample service also carries an intent filter `androidx.car.app.action.NAVIGATE` with `geo:` data; the navigation guide says navigation intents are **required** for quality item NF-6, to be read in both `Session.onCreateScreen()` and `Session.onNewIntent()`. The Assistant's own navigation URIs (`google.navigation:`) are described on a different page I did not read **[unverified]**.
- Android 12+: `android:exported` explicit on `CarAppService`; Android 14: foreground service type declared (we already have `NavigationService` with type `location`); Android 16: if the "safer intents" feature is used, `android:intentMatchingFlags="allowNullAction"` on the `CarAppService` `<service>` **[doc: platform releases page]**.
- Our existing permissions cover location and foreground service. `ACCESS_FINE_LOCATION` must be granted on the phone before the car session can navigate; the library offers `CarContext.requestPermissions()` (1.7.0-alpha01 and later on Android 14) to prompt on the phone **[doc]**.

### 2.5 Templates (important: several are deprecated)

**[doc: navigation guide]**

| Template | Status | Use for us |
| --- | --- | --- |
| `NavigationTemplate` | current | active guidance: travel estimate, `Trip` is separate, map surface behind |
| `MapWithContentTemplate` (Car App API 7) | current | replaces the three below |
| `MapTemplate`, `PlaceListNavigationTemplate`, `RoutePreviewNavigationTemplate` | deprecated since API 7, still supported | simplest for stage 0 and 2 on hosts at API 6 and below |
| `SearchTemplate`, `ListTemplate`, `PaneTemplate`, `MessageTemplate` | current **[memory]** | search entry, favourites, errors |

Decision: write stage 0 to 3 with the **classic templates** (`PlaceListNavigationTemplate`, `RoutePreviewNavigationTemplate`, `NavigationTemplate`) because they work on every host level, and wrap them behind a tiny `CarScreens` factory so a later migration to `MapWithContentTemplate` (host level 7+, gated with `carContext.carAppApiLevel >= 7`) touches one file. Which Android Auto versions map to which Car App API level was not found (the "releases" page does not contain the table) **[unverified]**; the plan feature-gates on `carAppApiLevel` at run time instead of trusting a table.

Template restrictions (task stack depth, refresh limits) are described in the library's "template restrictions" page: `NavigationTemplate` is a "reset" template, i.e. the host resets the quota when it is reached **[doc]**. The documented stack depth for most templates is 5 **[memory]**. Rate of template refreshes is limited by the host; the plan therefore invalidates the navigation screen at most once per second and only when a shown value changes (the same idea as `NavNotificationThrottle` in `:core-nav`).

### 2.6 Navigation metadata, voice, notification, simulation

**[doc: navigation guide]**

- `NavigationManager`: call `navigationStarted()` when guidance starts, `updateTrip(Trip)` during guidance (steps, destination, `TravelEstimate`; the DHU shows the `Step` but not the `Destination`), `navigationEnded()` only when the user finishes (use `Trip.Builder.setLoading(true)` while rerouting). Implement `NavigationManagerCallback.onStopNavigation()` (host asks us to stop cluster info, notifications and voice) and `onAutoDriveEnabled()`.
- **Auto-drive is mandatory for Play review**: when `onAutoDriveEnabled()` fires, the app must simulate the drive to the chosen destination. Our `NavScreenController.begin(..., simulate = true)` and `RouteSimulator` already do this. Test command from the docs: `adb shell dumpsys activity service <CarAppService class> AUTO_DRIVE`.
- Voice: request audio focus with `AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. The navigation audio channel may only carry navigation instructions (quality item). Our `SpeechDirector` already uses audio focus with `AndroidAudioFocus`/`AndroidSpeechEngine` **[code]**; stage 4 verifies the usage attribute and the stream (whether the TTS output goes to the car's media-over-USB/Bluetooth path or the phone speaker is a device test, not verifiable here).
- Turn-by-turn notification (rail widget and heads-up): `NotificationCompat` with `setOngoing(true)`, `setCategory(CATEGORY_NAVIGATION)` and `.extend(CarAppExtender...)`. Our `NavigationService.build(...)` already sets ongoing and `CATEGORY_NAVIGATION` **[code]**; stage 3 adds the `CarAppExtender` (title, content intent, importance).
- `Alert` (inside `NavigationTemplate` only) fits camera warnings later.

### 2.7 Drawing the map

**[doc: draw-maps page]**

- Register `SurfaceCallback` with `carContext.getCarService(AppManager::class.java).setSurfaceCallback(cb)`; the host hands a `Surface` (width, height, dpi) in `onSurfaceAvailable`, and reports `onVisibleAreaChanged` / `onStableAreaChanged` (areas not covered by template UI). Gesture callbacks (`onScroll`, `onFling`, `onScale`, `onClick`) exist but were not on the page I fetched **[memory]**.
- Two documented ways: draw with `Canvas` on the `Surface`, or `DisplayManager.createVirtualDisplay(..., surface, 0)` + `Presentation` + any `View` (also a `ComposeView` with `Session` as `LifecycleOwner`/`SavedStateRegistryOwner`).
- Quality rules: the app draws **only map content** on the surface (NF-2); instructions, lanes and ETA go in template components. Dark mode must follow `CarContext.isDarkMode` / `Session.onCarConfigurationChanged` (MR-1).
- Apps must redraw for day/night; our `MapEngine.setTheme(MapTheme)` covers that.

**How MapLibre would fit** (inference, **[unverified]** on any device):

- `MapLibreEngine` builds a `MapView` from a `Context` and owns the style, layers, camera and route line **[code, `MapLibreEngine.kt` lines 87-170]**. A second instance created with the `Presentation`'s context and attached to a `VirtualDisplay` is the documented generic approach. `MapView` runs its own GL renderer; its output is composited by the virtual display into the host surface. Cost: a second GL context and style load in the same process next to the phone activity's `MapView`, plus one extra composition copy. Memory is the concern (the repo notes "little RAM"): the phone activity's map should be paused/destroyed while the car owns the screen, or the engine instance shared (see below).
- Choose `MapLibreMapOptions.textureMode(true)` (TextureView) for the car instance: `SurfaceView` inside a `Presentation` on a virtual display is a common source of black frames **[memory]**. Spike item S1 below.
- Not viable: handing the host `Surface` directly to MapLibre Native. I know of no public API for that **[unverified]**; the `MapView` is the supported entry point.
- Frame rate: the DHU default is 30 fps (`framerate = 30` in the sample `headunit.ini`) **[doc]**. The project's 60/120 fps requirement is for the phone; the car target is 30 fps stable, which should be recorded in `docs/decisions.md` with the reason (host-imposed).
- Reuse of the style: the engine reads local PMTiles from `MapFiles`, so the car instance needs no network; it must use its own `CameraStateStore` implementation (in-memory) so it does not overwrite the phone's saved camera.
- Own-renderer alternative (Canvas, drawing the route polyline and a basic base from our data): far more work and worse looks; rejected.

### 2.8 DHU

**[doc: DHU page]**

- Install: Android Studio, SDK Manager, SDK Tools, "Android Auto Desktop Head Unit Emulator" (installs to `<SDK>/extras/google/auto/`). On this machine the SDK is at `~/Android/Sdk` and `extras/google` does **not exist yet** (checked 2026-10-08), so DHU must be installed first. The CLI package id is probably `extras;google;auto` **[memory]**. Linux needs GLIBC 2.32 or later and `libc++1 libc++abi1` on Debian-like systems (this machine runs Fedora, so the Fedora package names are `libcxx`/`libcxxabi` **[unverified]**).
- Phone side: Android 9+, developer options on; Android Auto installed and updated; sign in to the Play Store (Android 10+). Enable Android Auto developer mode: Android Auto settings, About, tap "Version" 10 times, OK. Then in the overflow menu: "Start head unit server". In Android Auto settings, "Previously connected cars", make sure "Add new cars to Android Auto" is on.
- Run (ADB tunnelling, option B of the page):
  ```
  adb forward tcp:5277 tcp:5277
  cd ~/Android/Sdk/extras/google/auto && chmod +x desktop-head-unit && ./desktop-head-unit
  ```
  or USB accessory mode `./desktop-head-unit --usb`. Config in `~/.android/headunit.ini` (`resolution`, `dpi`, `framerate`, `[sensors] location = true`, optional `[display:cluster]` on the beta channel).
- DHU console: `day`, `night`, `screenshot file.png`, `restrict all`, `focus video toggle`, `help`.
- Unknown sources: the docs state the toggle does not apply to Car App Library apps; see risk R1.

## 3. Architecture

### 3.1 Existing pieces to reuse (all **[code]**)

- `NavigationController` (`core-nav`): `state: StateFlow<NavState?>`, `route: StateFlow<RoutePlan?>`, `problem`, `announcements`, `events`; owned by `MapasApp.navigation`. Lives in the main process and survives the activity.
- `NavState`: status (`ON_ROUTE`, `OFF_ROUTE`, `REROUTING`, `ARRIVED`, `NO_SIGNAL`), `remainingMeters/Seconds`, `nextManeuver` and `followingManeuver` (`ManeuverInfo(maneuver, distanceMeters)`), `lanes`, `speedLimitKmh`, `overSpeedLimit`, `stopsRemaining`, `position`, `bearingDegrees`.
- `Maneuver`: `TurnType` (18 values: DEPART, STRAIGHT, slight/normal/sharp left/right, U-turns, ROUNDABOUT_ENTER/LEAVE, EXIT_LEFT/RIGHT, MERGE, ARRIVE, ARRIVE_LEFT/RIGHT), `streetName`, `roundaboutExit`, `lanes: List<Lane(directions: Set<LaneDirection>, recommended)>`.
- `NavScreenController` (`app/.../nav`): `begin(plan, via, trip, simulate)`, `stop()`, `addStop(...)`, `resume()`, `ui` StateFlow; sinks (`VoiceNavSink`, `AlertNavSink`) attach voice and camera alerts. `begin()` also starts `NavigationService` for real trips, which keeps the process foreground. Starting from the car calls this same method, so voice, recording, camera alerts, persistence and resume work unchanged.
- Route calculation with guidance: `CoMapsGuidedRouteBackend` + `RouteRunner` + `InstalledRegions` + one native lock, today wired inside `PanelHost` (Activity-bound, with Compose-state controllers). `CoreRouteProvider` already computes guided plans from a Context without UI (used for reroutes). **Gap:** there is no UI-free "plan a guided route from A to B with the user's options and the shared lock" service; stage 0 uses `CoreRouteProvider`-style code; stage 2 extracts a `RoutePlanner` (see 3.3).
- Search: `SearchEngine.search(query, near, limit)` and `searchCategory(...)` via `CoMapsSearchBackend.prepareCore(...)` (blocking, off the main thread); `SearchCoordinator` is Compose-state based and Activity-bound, so the car uses `SearchEngine` directly through the same backend and the same native lock.
- Favourites: `PlacesRepository` (`places()`, `lists()`, `special(slot)` for Home/Work, `recentSearches()`) in `core-data`, opened by `openPlacesService`.
- Voice: `VoiceModule` (singleton), `VoiceGuide`, `SpeechDirector`; prompts come from `NavigationController.announcements`, so they play whether the UI is the phone or the car.
- Map: `MapEngine` / `MapLibreEngine` (see 2.7). The native routing/search core runs in its own process `:core` (`native-comaps/.../AndroidManifest.xml`), so a car session in the main process adds no native-core cost.

### 3.2 Components (package `com.qtekfun.ultimatemaps.auto`, in `:app`)

```
UltimateMapsCarAppService : CarAppService
  createHostValidator()  -> default allow list (debug: allow all)
  onCreateSession(sessionInfo) -> CarSession

CarSession : Session
  onCreateScreen(intent)  -> HomeScreen (or NavigationScreen if a navigation is already active)
  onNewIntent(intent)     -> geo:/NAVIGATE/google.navigation -> plan route to the point
  onCarConfigurationChanged -> MapSurface.setTheme(day/night)
  owns: CarMapSurface (SurfaceCallback), CarNavigationBridge

HomeScreen         : PlaceListNavigationTemplate (favourites, Home/Work, recents) + action strip (Search)
SearchScreen       : SearchTemplate -> results list screen
RoutePreviewScreen : RoutePreviewNavigationTemplate (one to three route choices, ETA, "Start")
NavigationScreen   : NavigationTemplate (Trip step, travel estimate, action strip: mute, stop, overview)
ArrivalScreen      : MessageTemplate

CarNavigationBridge  (no screen): collects MapasApp.navigation.state, throttles, maps it with
                       CarNavMapper (pure) to a CarNavModel, calls NavigationManager.updateTrip and
                       screen.invalidate() on the main thread; calls navigationStarted/Ended;
                       handles onStopNavigation and onAutoDriveEnabled.
CarMapSurface        : SurfaceCallback; Presentation + MapLibreEngine(textureMode); draws route + user
                       arrow from NavState; follows camera like NavCameraPlanner.
```

Threading: screens are touched on the main thread (the library requires it); `NavState` is published from `Dispatchers.Default`, so the bridge hops with `Dispatchers.Main` and calls `invalidate()` only when the mapped model changed and at most once per second. Search/route calls go to `Dispatchers.IO` under the existing native lock.

### 3.3 Pure-Kotlin layer (testable on the JVM)

New Gradle module `:core-auto` (JVM only, no androidx.car.app types), depends on `:core-nav`, `:core-routing`, `:core-geo`, `:core-voice`:

- `CarNavModel` (immutable): `maneuverKind` (own enum, 1:1 with what the car library can show), `roundaboutExit`, `distanceToManeuverMeters`, `streetName`, `lanes`, `remainingMeters`, `remainingSeconds`, `arrivalEpochMillis`, `status` (on route, rerouting, no signal, arrived), `speedLimitKmh`.
- `CarNavMapper.map(NavState, now): CarNavModel` and `ManeuverKindMapper` (`TurnType` to car maneuver kind, with the roundabout direction decided by the driving side, which is a function of the region and must be an explicit parameter: right-hand traffic rotates counter-clockwise).
- `CarThrottle`: emit-if-changed-and-1s rule.
- `CarPlaceListModel` (favourites/recents/results to rows with distance text via `DistanceText` of `:core-voice`).
- Only the thin classes in `:app/.../auto` import `androidx.car.app.*` and translate models to `Row`, `Step`, `Maneuver`, `Lane`, `TravelEstimate`. A JVM unit test of the translation is possible with `app-testing` (`TestCarContext`, `TestScreenManager`) under Robolectric, which the project already uses.

### 3.4 Dependency, flavor and isolation

- `gradle/libs.versions.toml`: `androidxCarApp = "1.7.0"`, libraries `androidx-car-app`, `androidx-car-app-projected`, `androidx-car-app-testing`.
- `app/build.gradle.kts`: `implementation` of the first two, `testImplementation` of the third. No flavor change. All car code under `auto/`; the manifest block in one commented section.
- If a future requirement says the `foss` flavor must not contain it, add flavor `car` with the code in `src/car/`; not now.

## 4. Staged roadmap

Estimates are working days for one developer including tests, excluding DHU/device iteration time that depends on the owner. They are guesses, not measurements.

### Stage 0: appears on the DHU and shows guidance (target: this afternoon, about 3 to 4 hours)

Goal: install a debug build on the phone, start the DHU, see "UltimateMaps" in the launcher, open it, pick a saved place or a fixed demo destination, start a **simulated** drive and see the maneuver, distance and ETA update on the car screen. No map behind (blank/dark background), no search, no voice work beyond what already happens.

Files and changes (branch `feat/android-auto-stage0`):

1. `gradle/libs.versions.toml` and `app/build.gradle.kts`: the three artifacts above.
2. `app/src/main/AndroidManifest.xml`, inside `<manifest>`:
   ```xml
   <uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />
   <uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />
   ```
   inside `<application>`:
   ```xml
   <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />
   <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1" />
   <service
       android:name=".auto.UltimateMapsCarAppService"
       android:exported="true">
       <intent-filter>
           <action android:name="androidx.car.app.CarAppService" />
           <category android:name="androidx.car.app.category.NAVIGATION" />
       </intent-filter>
       <intent-filter>
           <action android:name="androidx.car.app.action.NAVIGATE" />
           <category android:name="android.intent.category.DEFAULT" />
           <data android:scheme="geo" />
       </intent-filter>
   </service>
   ```
   (No `foregroundServiceType` on the car service: the sample declares it only on its own navigation service, and ours is the existing `NavigationService`. Do not add `FEATURE_CLUSTER` yet.)
3. `app/src/main/res/xml/automotive_app_desc.xml`: `<automotiveApp><uses name="template" /></automotiveApp>`.
4. `app/src/main/kotlin/com/qtekfun/ultimatemaps/auto/UltimateMapsCarAppService.kt`: `CarAppService` with `createHostValidator()` (`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` **only when `BuildConfig.DEBUG`**, otherwise `HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)`; check the exact API for 1.7.0 with context7 before coding, I did not verify it) and `onCreateSession()`.
5. `auto/CarSession.kt`: `Session`; `onCreateScreen` returns `HomeScreen`.
6. `auto/HomeScreen.kt`: `PlaceListNavigationTemplate` listing up to 6 places from `PlacesRepository` (Home/Work first) plus one fixed row "Demo: simulate drive" for when there are no places; click calls the route planner then `app.navScreen.begin(plan, via, trip, simulate = true)` and pushes `NavigationScreen`. Needs the template to show a loading state while the route is computed (`setLoading(true)`).
7. `auto/CarRouteStarter.kt` (stage 0 version of the planner): origin from `AndroidLocationSource` last fix (or the demo origin) to the destination via the `CoreRouteProvider.route(...)` call; this skips `RouteRunner` retries on purpose. Stage 2 replaces it.
8. `auto/NavigationScreen.kt`: `NavigationTemplate` built from `CarNavMapper` output: `NavigationTemplate.NavigationInfo` with `RoutingInfo` (`Step` with `Maneuver`, distance, road name), `TravelEstimate`, action strip with Stop. Bridge: `lifecycleScope` of the screen collects `navigation.state`, throttled.
9. `auto/CarNavigationBridge.kt`: `NavigationManager.navigationStarted/updateTrip/navigationEnded`, `setNavigationManagerCallback` with `onStopNavigation` (stops navigation through `navScreen.stop()`) and `onAutoDriveEnabled` (no-op in stage 0 since stage 0 only simulates).
10. `core-auto/` (new module) with `CarNavModel`, `CarNavMapper`, `ManeuverKindMapper`, `CarThrottle` and JVM tests (can start as plain files in `:core-nav` if the module setup costs time; move later).
11. `LICENSES.md` rows, one line in `docs/decisions.md` (date, decision, reason, discarded alternative: a `gms` flavor), `strings.xml` and `values-es/strings.xml` for the car labels.

Build: `./gradlew assembleDebug -Dorg.gradle.workers.max=2` (native core already prepared in a configured clone; serialize native builds with `flock /tmp/claude-1000/native-build.lock`), then install on the phone **only with the owner's explicit permission** and through `flock /tmp/pixel-device.lock`.

DHU commands (owner or agent with permission), after installing the DHU from SDK Manager:

```
# phone: Android Auto -> tap Version 10 times -> developer mode -> overflow menu -> "Start head unit server"
adb forward tcp:5277 tcp:5277
cd ~/Android/Sdk/extras/google/auto
chmod +x desktop-head-unit
./desktop-head-unit            # or: ./desktop-head-unit --usb
# in the DHU console:
night                          # test dark theme
screenshot ~/mapas-data/dhu-stage0.png
```

Optional `~/.android/headunit.ini` for a larger view: `resolution = 1280x720`, `dpi = 160`, `[sensors] location = true`.

Checklist for the afternoon: (a) app icon appears in the DHU launcher; (b) HomeScreen shows places; (c) tapping starts the simulation; (d) maneuver icon, distance, street and ETA change as the simulation advances; (e) Stop returns to HomeScreen; (f) logcat shows no host-validation rejection (`adb logcat | grep -i carapp` only with permission).

If the app does not appear: check risk R1 (installer/sideload), `minCarApiLevel` versus the host, Android Auto version, "Add new cars" setting, DHU restart sequence from the DHU page troubleshooting section.

### Stage 1: map on the car screen (about 2 to 3 days)

- `ACCESS_SURFACE` is already declared. `CarMapSurface`: `SurfaceCallback` + `VirtualDisplay` + `Presentation` + `MapLibreEngine` created with the presentation's context, `textureMode(true)`, in-memory `CameraStateStore`, theme from `CarContext.isDarkMode`.
- Draw the route (`showRoute`), user arrow (`showUserLocation` + `setUserHeading`) and follow camera. Reuse `NavCameraPlanner` with the visible area from `onVisibleAreaChanged` converted to `CameraPadding`.
- Spike S1 (first 2 hours): black-frame check in the DHU with TextureView vs SurfaceView, and memory/frame time with the phone activity open vs closed. Spike S2: lifecycle (`onSurfaceDestroyed` must release the display and the engine, `Session` lifecycle `ON_DESTROY` closes everything).
- Panning/zooming from the car touch screen: map `onScroll`/`onScale` to `MapEngine.animateTo` (the `SurfaceCallback` gesture methods; verify API names).
- Quality gate NF-2: draw only map content on the surface.

### Stage 2: search, route preview, start from the car (about 3 days)

- Extract a UI-free `RoutePlanner` from `PanelHost`/`NavLauncher` (same `RouteRunner`, `InstalledRegions`, one native lock owned by `MapasApp`), used by both the phone panel and the car. This is the only change to existing phone code; keep it a pure refactor with the existing tests passing.
- `SearchScreen` with `SearchTemplate` (`SearchCallback.onSearchTextChanged/onSearchSubmitted`), results in `PlaceListNavigationTemplate`, using `SearchEngine.search(query, near, limit)` off the main thread under the lock, `near` = last fix. Keep the phone's coordinate/Plus-code handling by routing through the same `CoordinateQuery` code if cheap. Show a message row for "no maps installed" using the same `SearchStatus.NO_REGIONS` condition.
- `RoutePreviewScreen` with `RoutePreviewNavigationTemplate` (route choices from `RouteAlternative` when available, ETA and distance, Start button; note the template allows only a few choices **[memory]**). "Start" calls `navScreen.begin(plan, via, trip)` for a real trip.
- Handle `onNewIntent` for `geo:` (already parsed by `link/` on the phone: reuse the parser), `androidx.car.app.action.NAVIGATE` and the Assistant's navigation URIs (read `developer.android.com/develop/devices/assistant/intents-assistant-nav-app` first; not read for this plan).
- Error states: route failures map from `RouteFailure`/`RouteError` (reuse the texts), no GPS permission (`CarContext.requestPermissions`).

### Stage 3: full turn-by-turn quality (about 3 days)

- Maneuver icons for all 18 `TurnType`s via the car `Maneuver` types (including roundabout with angle or exit number, on/off ramp, merge, U-turn, destination left/right) with a table-driven test over every `TurnType`. The car library requires its own icon style; if a type has no good match fall back to `TYPE_UNKNOWN` plus the text.
- Lanes: `Lane`/`LaneDirection` from `NavState.lanes`, with `recommended` mapped to the recommended direction; shown only when the next maneuver has lanes (same condition as the phone).
- `followingManeuver` as the "next step" (`RoutingInfo.setNextStep`), `Trip` with all remaining steps for the cluster, `TravelEstimate` (remaining distance and time, arrival time with time zone), speed limit text and over-limit colour.
- Rerouting: `Trip.setLoading(true)` while `REROUTING`/`NO_SIGNAL`; never `navigationEnded` for a reroute.
- `CarAppExtender` on the `NavigationService` notification; arrival screen; `onAutoDriveEnabled` (route simulation to the chosen destination, with `navScreen.begin(simulate = true)`).
- Optional `FEATURE_CLUSTER` session (`SessionInfo.DISPLAY_TYPE_CLUSTER`): map tiles plus route only; separate `CarContext` per session so communicate through `MapasApp`. Skip if time is short.

### Stage 4: voice through the car (about 1 to 2 days, mostly device time)

- Confirm `AndroidAudioFocus` uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` **[docs say these are what to request; the current attributes were not checked in this pass]**; fix if different.
- Honour `onStopNavigation` (stop voice, notifications, cluster).
- Voice mute toggle on the car action strip bound to `navScreen.setVoice(...)`.
- Check that camera advisory prompts (`VoicePriority.ADVISORY`) do not violate "navigation audio channel only for instructions" (quality guideline). They are navigation-related; decide and record.
- DHU does not output audio the way a car does; real-car test needed.

### Stage 5: favourites, 3D, night, polish (about 2 to 3 days)

- Favourites tab with lists, Home/Work shortcuts and "add stop" while navigating (`NavigationController.addStop`) from a search result.
- Day/night follows the host (`isDarkMode`), including `MapTheme`; 3D tilt (`CameraState.tilt`, `Buildings3d`) on the car surface only after S1 shows an acceptable frame time at 30 fps; otherwise 2D only on the car.
- Alerts: `Alert` in `NavigationTemplate` for camera warnings (off by default like on the phone).
- Settings entry: a switch "Show in Android Auto" is **not** needed (declaring the service is enough), but a short About/README text is.

### Total

About 14 to 17 working days for stages 0 to 5; stage 0 alone is the afternoon target. Stage 4 and parts of 3 and 5 cannot be finished without a real car.

## 5. Tests

JVM (no device, deterministic, no real time; per CLAUDE.md the simulated `LocationSource` and a fake clock):

- `CarNavMapperTest`: feed hand-built `NavState`s (every `NavStatus`, with and without `followingManeuver`, lanes, speed limit) and assert the `CarNavModel`.
- `ManeuverKindMapperTest`: all `TurnType` values map to a kind; roundabout direction for right-hand and left-hand traffic.
- `CarThrottleTest`: with a fake clock, at most one emission per second and only on change; reroute and arrival always pass.
- `CarPlaceListModelTest`: ordering (Home/Work, favourites by distance, recents), distance text units, empty states.
- `NavigationBridgeTest` (Robolectric, `app-testing`'s `TestCarContext`): a simulated `NavigationController` (existing `Fixtures` in `core-nav` tests, `RouteSimulator`) drives `navigationStarted`, `updateTrip` counts and `navigationEnded`; `onStopNavigation` stops the controller; `onAutoDriveEnabled` starts a simulated trip.
- `HomeScreenTest` / `SearchScreenTest` with `TestScreenManager` and `TestCarContext`: template type and row count; a click starts the planner (fake) and pushes the right screen. Searches use a fake `SearchEngine`.
- Existing phone tests must stay green; the `RoutePlanner` extraction is covered by the current route/nav tests plus new unit tests for the planner.

DHU checklist (manual, owner or agent with permission), stored in `docs/phase2/android-auto-checklist.md` once stage 0 exists:

1. Launcher shows the app; cold start to first screen under 10 s (quality DR/AC criteria).
2. Home screen: favourites, recents, Search action.
3. Search by text with results; open a result; preview shows ETA; Start.
4. Simulated drive: maneuver icon, distance, street, lanes, ETA update; reroute shows loading; arrival screen.
5. `adb shell dumpsys activity service com.qtekfun.ultimatemaps.auto.UltimateMapsCarAppService AUTO_DRIVE` starts a simulated trip.
6. `night` and `day` in the DHU console switch the map theme.
7. Disconnecting the DHU mid-trip leaves the phone navigation running; reconnecting returns to `NavigationScreen`.
8. Stop from the car stops the phone navigation, voice and notification.
9. No crash with maps not installed, location off, permission denied.
10. Frame time of the car surface (target a steady 30 fps), memory with phone map open vs closed.

## 6. Risks

| # | Risk | Likelihood / impact | Mitigation |
| --- | --- | --- | --- |
| R1 | Android Auto only lists Car App Library apps installed from a trusted source (Play); F-Droid/GitHub APKs may not appear in a real car, and an adb-installed build may not appear on the DHU. Docs: "Unknown sources ... doesn't apply to apps built using the Android for Cars App Library"; "to test in real vehicles you must install from a trusted source such as Google Play" | Medium for DHU, **high for real cars** / feature unusable for F-Droid users | First thing to test in stage 0 (the whole afternoon depends on it). If the DHU does not list the debug build: try Play Internal App Sharing / internal test track (needs a Play developer account: owner decision, spending money) |
| R2 | Second `MapView` plus GL context next to the phone's: memory, black frames, low frame rate | Medium / stage 1 slips | Spike S1, TextureView, destroy the phone map while the car owns the screen, 2D only |
| R3 | Host template refresh limits throttle updates or reject quick changes | Medium / laggy guidance | Throttle to 1 Hz on change; use `NavigationTemplate` (the intended continuous refresh template) |
| R4 | Native routing/search latency (known risk) and `ROUTE_NOT_FOUND` on long routes show up on a slow car UI | Known, medium | Show loading state with a timeout message; reuse `RouteRunner` retry policy in stage 2 |
| R5 | Google Play review / quality criteria if we ever publish there (NF-2, NF-6, VC-1 Assistant intents, DR response times, AC-1 five screens at most) | Only if published on Play | Design to them from stage 2; keep the Play question with the owner |
| R6 | Car App Library vulnerability history (CVE-2024-10382) | Low | Pin 1.7.0 or later; update on releases |
| R7 | Privacy: places and street names are sent to the Android Auto host (Google app) | Certain by design / needs owner acceptance | State it in `PRIVACY.md` and README; it is on the same device, it is not our network access, but the host's behaviour is unknown to us |
| R8 | Guava and other transitive dependencies add APK size and method count | Low | Measure after stage 0; R8 is currently off |
| R9 | Template/API differences across host versions (API level table not found) | Medium | Gate by `carAppApiLevel` at run time; classic templates as baseline |
| R10 | Roundabout and U-turn icon semantics depend on driving side; wrong icons are worse than none | Low / quality | Explicit driving-side parameter, tests, fall back to `TYPE_UNKNOWN` plus text |

## 7. What needs the owner

- Permission to use the Pixel 8 for the DHU (CLAUDE.md: each time). The DHU needs a phone with Android Auto, developer mode and the head unit server; nothing here was run.
- Install the DHU through Android Studio's SDK Manager (not present in `~/Android/Sdk/extras/google` on 2026-10-08), or authorize an agent to fetch it with `sdkmanager`.
- A real car (or a head unit) for stage 4 and for the real-world answer to R1.
- Decision on Google Play: a developer account to use internal testing/Internal App Sharing for car tests, and whether the app will ever be published there. This is spending money and an irreversible action outside the repo (rule 4); nothing in this plan assumes a yes.
- Accept the privacy and "host is proprietary" statements (R7, section 2.2).

## 8. What I could not verify

- Any behaviour on a device, the DHU or a car; whether a debug build installed with adb appears on the DHU (the docs only imply it through the sample workflow).
- Whether Car App Library apps installed outside Play can ever appear in a real car (the docs say the "Unknown sources" setting does not cover them).
- The exact `HostValidator` API for 1.7.0 and the template quota numbers (stack depth, refresh limits); the Car App API level to Android Auto version table; `SurfaceCallback` gesture method names; names of the car `Maneuver` type constants.
- That MapLibre Native renders correctly into a `Presentation` on a `VirtualDisplay` (text view mode, performance, memory).
- F-Droid scanner behaviour towards `androidx.car.app` (only a precedent from Organic Maps' permission list).
- Whether the Android Auto host works without Google Play services (assumed not).
- The Assistant navigation URI format (`google.navigation:`), the quality criteria numbering beyond what the pages quoted, and whether the Play "navigation app" review has extra requirements beyond the quality page and auto-drive.
- Effort numbers are estimates.

## Sources

- Navigation apps guide: https://developer.android.com/training/cars/apps/navigation
- Draw maps: https://developer.android.com/training/cars/apps/library/draw-maps
- Testing and developer mode, Allow unknown sources: https://developer.android.com/training/cars/testing
- Desktop Head Unit: https://developer.android.com/training/cars/testing/dhu
- Platform releases (Android 12 to 16 changes): https://developer.android.com/training/cars/platforms/releases
- Car app quality criteria: https://developer.android.com/docs/quality-guidelines/car-app-quality
- Car App Library release notes: https://developer.android.com/jetpack/androidx/releases/car-app
- POMs: https://dl.google.com/dl/android/maven2/androidx/car/app/app/1.7.0/app-1.7.0.pom and https://dl.google.com/dl/android/maven2/androidx/car/app/app-projected/1.7.0/app-projected-1.7.0.pom
- Sample manifests and descriptor: https://github.com/android/car-samples/tree/main/car_app_library/navigation
- Context7: `/websites/developer_android_training_cars_apps`, `/android/car-samples`
- Organic Maps on F-Droid (permissions list): https://f-droid.org/packages/app.organicmaps
- GrapheneOS thread on Organic Maps/OsmAnd not visible in Android Auto: https://discuss.grapheneos.org/d/29673-unable-to-see-organic-maps-or-osmand-in-android-auto
