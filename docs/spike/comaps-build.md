# Spike (a): build CoMaps as is and measure

Date: 2026-10-06. Branch `spike/comaps-build`. Flow (a) of `docs/mapas-04-spike.md`.
Source: CoMaps tag `v2026.10.05-19` (shallow) in `~/repos/comaps-spike` (outside the repo). App version: `2026.10.05-1`, versionCode 26100501.
Device: a single Pixel 8 (Android 17, arm64, 120 Hz, GMS present but not used by the fdroid flavor), connected over wireless adb and **shared with other agents** (see "Limits of the measurements").
Scripts: `spike/comaps-build/`. Raw outputs (text): `spike/comaps-build/traces/`.

## 1. Build

Result: **arm64 fdroid release APK built** (`assembleFdroidRelease -Parm64 -Pnjobs=6`, signed with the project's own debug key because there is no `secure.properties.release`). No GMS: the `fdroid` flavor does not include `play-services`.

- Clean build with everything resolved: 5 min 37 s wall clock, Gradle peak RAM (`/usr/bin/time -v`) 6.07 GB RSS (`traces/03-build.txt`). With `-Dorg.gradle.workers.max=2`, ninja `-j6`.
- Gradle 8.14.4 (wrapper), JDK 21 (Temurin) works; NDK 28.2.13676358 (the one the project pins); CMake 3.31.6 from the SDK.
- **APK size: 46 276 863 bytes (44.1 MiB)**, arm64-v8a only. `liborganicmaps.so` 14.7 MB uncompressed (6.3 MB in the zip). `traces/06-apk-size.txt`.

### Blockers and patches (none touches the engine)

No CoMaps source file has been modified. What was needed for it to build on this machine:

| # | Problem | Solution (no sudo) | Trace |
|---|----------|---------------------|-------|
| 1 | Uninitialized submodules | `git submodule update --init --recursive --depth 1` (2.3 GB on disk) | `01-submodules.txt` |
| 2 | Boost requires `bootstrap.sh` + `b2 headers` (done by `configure.sh`) | Run by hand | `02-boost-bootstrap.txt` |
| 3 | `World.mwm` / `WorldCoasts.mwm` (downloaded by `configure.sh`) | `curl` from `mapgen-fi-1.comaps.app/maps/2026.06.28/261004/` | — |
| 4 | **`uconv` missing** (`icu` package, Fedora, requires sudo): the script `generate_serbian_latin_strings.sh` aborts the Gradle configure | `uconv` built from the ICU in the `3party/icu` submodule in `~/repos/comaps-spike-tools/icu` and added to `PATH`/`LD_LIBRARY_PATH` | `03-build-attempt1-…`, `04-uconv.txt` |
| 5 | **CMake 3.22.1 + NDK 28**: adds `-fuse-ld=gold` under LTO and NDK 28 no longer ships gold → `invalid linker name` | `android/local.properties` with `cmake.dir=…/cmake/3.31.6` (local configuration, not versioned) | `03-build-attempt2-gold-linker.txt` |
| 6 | **OOM in the ThinLTO link** of `liborganicmaps.so` (`clang++: Killed`) with ~5 GB free | `LDFLAGS=-Wl,--thinlto-jobs=2` | `03-build-attempt3-link-oom.txt` |

`spike/comaps-build/03-build.sh` contains the final command. System packages that would still be missing for a "by the book" build: `icu` (uconv) and probably global `ninja`/`cmake` (the SDK's are used).

## 2. Installation and maps

- Installed with `adb install -r` (package `app.comaps.fdroid`). Location and notification permissions granted with `pm grant`.
- Maps downloaded **by the app itself** (the `Android/data/<pkg>/files` folder is not writable by `adb push` on Android 17: `Permission denied`). No network failures. Map data version 261004:
  - **Whole of Spain: 25 regions, 1.9 GB** ("Download all" in the UI, ~13 min) + World (51 MB) and WorldCoasts (8 MB). `traces/05-download-maps.txt` verifies per host that the sizes match `countries.txt`.
  - **Fiji (Oceania): 19 MB**, downloaded from the UI (`traces/16-fiji-download.txt`); renders Suva with streets and POIs (screenshots taken, not saved).
  - Observation: in CoMaps "Spain" is not a single .mwm; it is split into 25 regions (Catalonia in 4 provinces, etc.).

## 3. Measurements

**Refresh rate:** 120 Hz (peak/min refresh 120; `renderFrameRate 120.00001`, `mActiveModeId=2`; `traces/07-startup.refresh.txt`). All fps thresholds are evaluated against 8.3 ms.

### Summary against thresholds

| Metric | Threshold | Measured | Verdict |
|---|---|---|---|
| Interval between map frames during pan, p95 (120 Hz) | ≤ 8.3 ms | pan 8.85 ms; zoom 8.88; rotation 8.87 (p50 8.32-8.33; 119-120 average fps) | No dropped frames (> 12.5 ms: 0 % / 0.16 % / 0.08 %), but the literal p95 exceeds 8.3 because of SurfaceFlinger timestamp jitter. Passes in practice; see method |
| Cold start (`am start -W`, median of 10) | ≤ 1 s | TotalTime **141 ms** (Splash); to `MwmActivity` displayed ≈ **650 ms** (n=5) | Passes. Not measured: first tile painted |
| Search after each key (first batch) | ≤ 100 ms | median **631 ms**, p95 1734 ms (n=31); 1st cold search 6705 ms | **Fails** |
| Route Madrid–Barcelona (car) | ≤ 2 s | **16.50 / 16.64 / 17.39 s** (median 16.64 s), 621.8 km | **Fails** (8x) |
| Urban car route 6.7 km | — | 0.42-0.44 s (n=3) | informative |
| Urban foot route 5.1 km / bike 5.4 km | — | 1.24-1.42 s / 1.13-1.16 s (n=3 each) | informative |
| Sierra de Guadarrama, car 13.8 km | — | 0.66-0.68 s (n=2) | informative |
| Memory | — | Total PSS 503 MB (Graphics 294, Native heap 162, Java 5.7) after gestures in Madrid z15 with 28 mwm | informative |
| APK size | — | 46.3 MB (arm64) | informative |

Mid-range/low-end device classes, no-GMS ROMs and de-Googled ROMs **cannot be measured** (there is only one Pixel 8): the mid-range and "No GMS" thresholds remain **not measured**.

### 3.1 Cold start
Command (`spike/comaps-build/startup.sh`): 10 × (`am force-stop` + HOME + 3 s + `am start -W -n app.comaps.fdroid/app.organicmaps.DownloadResourcesActivity`), `LaunchState: COLD` in all 10.
- TotalTime (ms): 129 130 135 137 140 142 142 144 147 148 → median 141 (WaitTime median 143). `traces/07-startup.txt`.
- That figure is only the `SplashActivity`, which launches `MwmActivity`. With the logcat `Displayed` lines (`07-startup.displayed.txt`), from the start command to `MwmActivity` displayed: 612, 627, 650, 651, 658 ms (only 5 of the 10 runs left the pair of lines; median 650 ms). With 28 mwm installed.

### 3.2 fps / frame time (pan, zoom, rotation)
Commands: `spike/comaps-build/gesture_trace.py <pan|zoom|rotate|idle> 10 <trace>` + `analyze_trace.py`. Gestures injected with real multitouch at 120 Hz from an `app_process` process on the device (`inj/Inj.java`, `InputManagerGlobal.injectInputEvent`), with local timing. Central Madrid z15 view (`geo:` intent), 10 s per gesture. Recorded with Perfetto (`perfetto.cfg`, 8-9 MB binary **not** saved because of size; only the result JSONs).

| Gesture | frames | interval p50 | p90 | p95 | p99 | max | average fps | > 16.7 ms | > 12.5 ms |
|---|---|---|---|---|---|---|---|---|---|
| pan (1 finger, ±500 px, 1 Hz) | 1397 | 8.33 | 8.72 | 8.85 | 9.35 | 12.19 | 120.0 | 0 % | 0 % |
| zoom (pinch 120↔600 px, 0.5 Hz) | 1221 | 8.32 | 8.72 | 8.88 | 9.33 | 91.9 | 118.9 | 0.08 % | 0.16 % |
| rotation (360°/3 s, radius 250 px) | 1231 | 8.32 | 8.70 | 8.87 | 9.24 | 13.2 | 120.0 | 0 % | 0.08 % |
| idle (no gesture) | 271 (short bursts) | 8.34 | 8.62 | 8.71 | 9.15 | 9.40 | — | — | — |

JSON in `traces/09-*.json`. Method and limitations:
- `dumpsys gfxinfo` was **not used**: CoMaps draws into its own GL `SurfaceView`, not through HWUI; moreover Perfetto's frametimeline returned 0 rows for that layer. The proxy used is the interval between buffers latched by SurfaceFlinger for the layer `SurfaceView[app.comaps.fdroid/…MwmActivity](BLAST)` (slice `setBuffer … hasBuffer=true`), taking the longest continuous burst.
- It is **presentation** time, not CPU/GPU time per frame. The p95 of 8.85 ms vs 8.3 ms reflects ±0.5 ms jitter in the timestamps; the robust finding is that there are no dropped frames (≥ 1.5 periods) except 2 intervals in zoom (one of 91.9 ms) and 1 in rotation (13.2 ms).
- The burst includes ~1 s before/after the gesture in which the map also animates. Idle gives short bursts, so the continuous 120 fps does correspond to the gesture.
- A single high-end device, dense urban scene in Madrid z15; tilted 3D and higher z were not tested.

### 3.3 Memory
`dumpsys meminfo app.comaps.fdroid` after the gestures (`traces/10-meminfo-after-gestures.txt`): TOTAL PSS 503 205 KB; Graphics 293 660 KB (EGL+GL mtrack); Native Heap 162 148 KB; Code 21 728 KB; Java Heap 5 684 KB; RSS 619 MB. During the long route calculation with "avoid motorways", `top` showed RES 1.3 GB and 115 % CPU in the app.

### 3.4 Search
There is no exposed measurement API; the core's own logs are used (`search/emitter.hpp`: "Emitting a new batch of results: N, X ms since the search has started", `search/engine.cpp`: "Search ended in X ms"). Command: `search_typing.sh` types `plaza_mayor`, `calle_alcala`, `atocha` character by character (1.5 s between keys) in the search UI, with the 28 mwm loaded and the view on Madrid. `traces/13-search.logcat.txt`, summary `13-search-summary.txt` (`analyze_search.py`):
- 46 searches (1st cold one discarded), 31 with results: **first batch** min 143 / median 631 / p95 1734 / max 2898 ms; search end median 661 ms.
- The first search after startup (index loading) took 6705 ms to the first batch (`comaps://search` intent, `traces`: not saved, value from the test session's log).
- Caveat: the "time" includes the viewport search over the whole of Spain; it was not tried with only Madrid installed. UI latency (key→paint) not measured.

### 3.5 Routes
Triggered by intent `comaps://route?sll=..&dll=..&type=vehicle|pedestrian|bicycle` (`route_probe.sh`); time = the core's `Route found, elapsed seconds` line (`route_times.sh`; `traces/11-*`, `12-*`, `12-route-times.txt`). Cold process each time.
- **Central Madrid → central Barcelona (car): 17.39 / 16.50 / 16.64 s; 621 843 m, ETA 22 033 s.** Two phases: "LeapsOnly" mode ≈ 9-10 s and refinement ≈ 7 s. Far above 2 s.
- A repetition with "avoid tolls" gave the same path and 26.2 s (`14-route-mad-bcn-avoid-toll`), so the noise from the shared device's load is ≥ 50 %.
- Urban and Guadarrama: see the summary table. "Motorcycle" does not exist as a profile.

## 4. Functions tested on the device

| Function | Result | How / evidence |
|---|---|---|
| `geo:` intent | **Works** | `am start -a VIEW -d "geo:40.4168,-3.7038?z=15"` opens Madrid z15 with a place card; `geo:-18.1416,178.4419?z=12` opens Suva |
| Route intent `comaps://route?...` | **Works** (CoMaps-specific; `type=vehicle/pedestrian/bicycle`) | `traces/11-*`, `12-*` |
| Search intent `comaps://search?query=..&map` | **Works** | `traces` (test session) |
| Profiles | Car, foot, bike, public transport and "ruler/straight line". **There is no motorcycle profile** | Route UI selector (screenshots seen, not saved); foot and bike routes measured above |
| Avoid motorways / tolls / ferries / unpaved roads (car); ferries/unpaved/stairs (foot, bike) | **Exist in the UI** and reach the router (`Avoid next roads: RoutingOptions: { toll }` / `{ motorway }`) | "Opciones de la ruta" (route options) screen (screenshot seen); `traces/14-*`. With "avoid motorways" the Madrid–Barcelona route was recalculated and the UI showed **691 km, 11 h 25 min** (vs 621.8 km, 6 h 7 min) after ~3-4 min of calculation (the filtered log does not include the `Route found`; figure read from an unsaved screenshot). "Avoid tolls" returned the same route (probably the default route no longer used a toll) |
| Import GPX | **Works** with `content://` (`am start --grant-read-uri-permission -t application/gpx+xml -d content://media/external/file/<id>`): "Importing bookmarks from content://…", saved as KML in `files/bookmarks/test-spike.kml`, and the track is drawn on the map. With `file:///sdcard/…` it fails with `EACCES` (scoped storage, expected) | `traces/15-gpx-import*.logcat.txt`, `test-spike.gpx` |
| Export GPX | **Not tested** (requires share/save UI) | — |
| Favorites | **Works** (save a place from the place sheet → "Mis lugares" (my places), button changes to "Eliminar" (delete)) | UI via adb, screenshot seen |
| Lanes (lane guidance) | **Not tested at runtime.** Support in code: `routing/lanes/lane_info.hpp`, `FollowingInfo::m_lanes` | Source review |
| Speed limits | **Not tested at runtime.** Support in code: `SpeedLimitView`, preference `pref_speedlimit`, `speed_camera_manager` | Source review |
| Guided navigation | **Not achieved in the spike.** A simulation was set up with a test GPS provider (`nav_sim.py`, `cmd location providers`) and the route UI, but the app took the phone's real location (not the simulated one) and navigation is only available "from the current location"; lanes/limits were never seen | `nav_sim.py` |

## 5. Limits of the measurements (read before using the figures)

- **Shared device:** other agents use it (the `flock` lock only serializes adb, not CPU/memory load). Long routes showed variations from 16.5 to 26.2 s. The route and search figures are probably pessimistic; the startup and fps ones are stable (n=10, bursts of 1200+ frames).
- A single device (Pixel 8, high-end): no mid-range threshold could be evaluated.
- `RelWithDebInfo` build (-O2, ThinLTO) with `-g`; it is not a Play release, but it is the same optimized native code.
- Perfetto binaries and screenshots are not saved (size/privacy: some screenshots showed the phone's real location). The `traces/09-*` JSONs and the logcat filtered to `OMcore` are the evidence.
- The figure for the first cold search (6705 ms) and the "avoid motorways" one (691 km) come from sessions whose log/screenshot was not fully kept; they are marked as such.

## 6. Reading for the A/B/C decision (this flow only)

- It builds, installs and works without GMS; the map at a constant 120 fps in pan/zoom/rotation on a Pixel 8 is solid; startup meets the threshold with margin.
- **Search (~0.6 s per key) and the long route (~17 s, 8x the threshold) do not meet the thresholds** with the engine as is on this device; the urban route (~0.4-1.4 s) does.
- Risk to watch: long-distance routing cost (LeapsOnly mode) and RAM (1.3 GB RES with avoid motorways).
- Motorcycle profile: does not exist; it would have to be added.
