# Spike (b): study of the CoMaps code (read-only)

Source: `~/repos/comaps-spike`, shallow at tag `v2026.10.05-19` (outside this repo; not modified or copied). The paths `libs/...`, `android/...`, `3party/...`, `data/...` are relative to that tree.

Evidence convention:

- **[L]** read in code, with `file:line`.
- **[I]** inferred from what was read (may be wrong; the reason is stated).
- **[N]** not verified. This report is code reading only: nothing has been built, run or measured (fps, latencies, startup). Those figures belong to the measurement flow.

The costs in days are my own estimates of engineering person-days with the code already compiling, not measurements.

## 1. Decoupling verdict

**Viable. Estimated cost: 15-20 days for a Compose screen that searches and computes routes without the CoMaps activity; 30-45 days if we also want a motorcycle profile with curves, our own map server and our own network layer (sections 4-8).** Confidence: medium-high on viability (the decoupling already exists in the project structure), medium on cost (I have not built or tested `Framework` startup without `app/`).

Why it is viable [L]:

1. Gradle already separates `:app` (UI) from `:sdk` (core + JNI): `android/settings.gradle.kts:20-21`. `:sdk` is `com.android.library` (`android/sdk/build.gradle.kts:6`) and builds the root CMake with the `organicmaps` target (`android/sdk/build.gradle.kts:36-40`, `:92-96`). CoMaps' UI (`:app`: 279 Java/Kotlin files, 44,630 lines) depends on `:sdk` (`android/app/build.gradle.kts:360`), not the other way round.
2. `:sdk` does not import `com.google.android.gms` or `org.microg` (grep over `android/sdk/src`: no results). The GMS imports are only in `android/app/src/google/...` (e.g. `LocationProviderFactoryImpl.java:10-11`).
3. All the search and routing JNI reaches a single C++ facade, `::Framework` (`libs/map/framework.hpp`), via `g_framework->NativeFramework()` (`android/sdk/src/main/cpp/app/organicmaps/sdk/search/SearchEngine.cpp:278`) and `frm()->GetRoutingManager()` (`.../sdk/Framework.cpp:1301-1316`).
4. `Framework` is built without a drawing engine: the constructor (`libs/map/framework.cpp:322-377`) initializes the classificator, country, `SearchAPI` and bookmarks, and `m_drapeEngine` is only created with `CreateDrapeEngine(...)` (`libs/map/framework.hpp:505`). The routing code guards use of the engine: `m_drapeEngine.SafeCall(...)` (`libs/map/routing_manager.cpp:431, 440, 585`) and `if (m_drapeEngine != nullptr)` in `framework.cpp:188, 238, 244`. [I] Therefore search and route calculation work without a map surface; drawing the route and camera following do require Drape.
5. There is an even lower path that avoids `Framework`: `IndexRouter` is built with `DataSource`, `CountryInfoGetter`/callbacks and `NumMwmIds` (`libs/routing/index_router.hpp:69-72`) and is an `IRouter` (`libs/routing/router.hpp:46-75`). The integration tests use it that way [L: `libs/routing/routing_integration_tests/routing_test_tools.cpp:62`]. Useful if a `RoutingEngine` without `RoutingManager` is wanted, but `RoutingSession` (guidance, recalculation, voice) is lost.

Decoupling risks [L/I]:

- **Global singleton**: `g_framework` / `frm()`; a single `Framework` per process. Compatible with a single-activity app, not with parallel tests.
- **`Framework` is a monolith** (1932 lines of JNI alone in `Framework.cpp`; 114 `JNIEXPORT` functions): it drags in bookmarks, OSM editor, traffic, transit, isolines. Building it whole is mandatory (without splitting `libs/map`); the binary does not get slimmer. [I]
- **Route points are `UserMark`s** tied to the `BookmarkManager` (`routing_manager.hpp:143, 240`), so routing is not usable without bookmarks initialized. [I]
- **The Java `:sdk` still drags in androidx UI** (`material`, `fragment`, `preference`, `recyclerview`: `android/sdk/build.gradle.kts:117-127`) and ~17,000 lines of Java with static state. Acceptable; it is Apache-2.0.
- **Data required at startup**: `Platform` needs the `data/` resources (classificator, `drules_proto*.bin`, fonts, `countries.txt`) and `World.mwm`; in the app they are assets linked per flavor (`android/app/src/google/assets/World.mwm` is a symlink). [L/I]

### Minimal API to expose to Kotlin/Compose

Almost all of it already exists as static `native` in `:sdk` and can be called from Kotlin as is. Proposed facade (our `core-*` interfaces):

| Our interface | What it calls in CoMaps [L] | Notes |
| --- | --- | --- |
| `Core.init(context, dataDir, onReady)` | `OrganicMaps.nativeInitPlatform` / `nativeInitFramework(Runnable)` (`sdk/.../OrganicMaps.java:237, 241`) | Asynchronous; the callback has to be mapped to a coroutine. |
| `SearchEngine.search(query, lang, pos) : Flow<Results>` | `SearchEngine.nativeRunSearch(bytes, isCategory, lang, timestamp, hasPosition, lat, lon)` -> `SearchAPI::SearchEverywhere` (`SearchEngine.cpp:269-282`); results through `onResultsUpdate` / `onResultsEnd` (`:231-267`) | The timestamp discards stale results. `nativeCancelEverywhereSearch` at `:350`. Results with `FeatureId` and `Description` (distance, open now). |
| `RoutingEngine.setPoints / build / follow / close` | `nativeAddRoutePoint` (`Framework.cpp:1537`), `nativeBuildRoute` (`:1304`), `nativeFollowRoute` (`:1314`), `nativeCloseRouting` (`:1299`), `nativeSetRoutingListener` (`:1491`) | Router type by `RouterType` (`libs/routing/router.hpp:35-42`): car, foot, bike, transit, ruler. |
| `RoutingEngine.options(avoid...)` | `RoutingOptions` + `RoutingOptions.java`/`.cpp` | See section 3. |
| `NavigationState : StateFlow<FollowingInfo>` | `nativeGetRouteFollowingInfo` (`Framework.cpp:1349`) -> `RoutingInfo.java:81-86` (lanes, `speedLimitMps`, `speedCamLimitExceeded`) | Has to be polled (no push); the polling goes in the navigation `Service`. |
| `VoiceGuide` | `nativeGenerateNotifications(announceStreets)` (`Framework.cpp:1324`) returns the phrases to speak; `TtsPlayer` plays them | See section 7. |
| `MapEngine` | `MapView` (`sdk/.../MapView.java:24`, a `SurfaceView`) inside Compose's `AndroidView`; `Map.java` and `MapController.java` for lifecycle | This is what keeps the dependency on CoMaps' rendering (option A). |
| `LocationSource` | `AndroidNativeProvider` (`sdk/.../location/AndroidNativeProvider.java`), without GMS | See section 6. |

Cost breakdown (days) [I]:

| Task | Days |
| --- | --- |
| Own Gradle project that consumes `:sdk` (without `:app`, without flavors, NDK/CMake, submodules) | 3-5 |
| Kotlin `Core/Search/Routing/Navigation` facade over the `native`s and bridges to `Flow` | 6-8 |
| `MapView` in Compose + surface lifecycle | 3-4 |
| Minimal screen: search box, map, A->B route, guidance panel with simulated route | 3-4 |
| **Total (without motorcycle/curves/network)** | **15-21** |

## 2. C++ layers (what depends on what)

| Layer | Where | Observation [L] |
| --- | --- | --- |
| Search | `libs/search/` (~150 files) | `SearchAPI` in `libs/map/search_api.hpp:85` (`SearchEverywhere`); the engine is `search::Engine` (`libs/search/engine.cpp`), independent of Drape. |
| Routing | `libs/routing/`, `libs/routing_common/` | `IndexRouter` (A* over a segment graph per mwm), `RoutingSession` (guidance), `EdgeEstimator`, `VehicleModel`. No dependency on Drape. |
| Orchestration | `libs/map/framework.*`, `routing_manager.*` | This is where the coupling with Drape lives (route rendering, camera). `RoutingManager::Delegate` only requires `OnRouteFollow` and `RegisterCountryFilesOnRoute` (`routing_manager.hpp:89-96`). |
| Storage | `libs/storage/` | Region download (section 8). |
| JNI | `android/sdk/src/main/cpp/app/organicmaps/sdk/` (10,583 lines of C++) | One Java class with `native` per area (`Framework`, `SearchEngine`, `Router`, `Map`, `MapManager`...). |

## 3. Routing: profiles, avoidance, lanes, limits

### Vehicle profiles

- `VehicleType` = `Pedestrian, Bicycle, Car, Transit, Decoder` (`libs/routing/vehicle_mask.hpp:10-18`). **There is no motorcycle.** Grepping `motorcycle`/`motorbike` in `libs/routing`, `libs/routing_common` and `libs/map` only finds POI icons (`bookmark_helpers.cpp:100, 149`, `search_mark.cpp:238`).
- `RouterType` = `Vehicle, Pedestrian, Bicycle, Transit, Ruler` (`router.hpp:35-42`), mapped in `routing_manager.cpp:216-225`.
- Each profile has a `VehicleModel` (speeds/permissions per `highway=*`: `routing_common/car_model.cpp:30-42`, `bicycle_model.cpp`, `pedestrian_model.cpp`) and its own `EdgeEstimator` (`edge_estimator.cpp:603` Pedestrian, `:638` Bicycle, `:726` Car; selection at `:817-838`).
- **Motorcycle = new work.** Cheap option [I]: reuse the car data (the mwm files store access, restrictions and penalties per `VehicleType`: `index_graph_loader.cpp:210-245`, `road_penalty.hpp` "Number of vehicle types") with a `MotorcycleEstimator` and a new `RouterType` whose `GetVehicleType` returns `Car` to load data. It avoids regenerating the mwm files. A real motorcycle (motorway allowed in more places, tracks not) is approximated by adjusting speeds in a derived `CarModel`. Cost: 5-8 days.
- A genuinely new `VehicleType` would require extending `Count` and the serialization of `road_access`/`road_penalty` in the mwm files and regenerating data: not recommended.

### Avoiding motorways, tolls, ferries, unpaved roads

- `RoutingOptions::Option` = `Usual, Toll, Motorway, Ferry, Dirty, Steps, Paved` (`routing_options.hpp:17-27`). Masks: pedestrian and bike `Ferry+Dirty+Steps+Paved` (`:32-33`); vehicle `Toll+Motorway+Ferry+Dirty+Paved` (`:34`).
- They are applied as a **hard exclusion**: `RoadGeometry::SuitableForOptions` (`geometry.hpp:80-83`) and its use in `index_graph.cpp:260, 285` and `single_vehicle_world_graph.cpp:217`. That is, "avoid tolls" forbids the edge, it does not penalize it; in areas where there is only a toll road the calculation fails. [I] A "prefer to avoid" mode would require turning it into a penalty in `CalcSegmentWeight` (see curves): 3 days.
- There is a surface model: `kCarSurface` with factors `paved_good/paved_bad/unpaved_good/unpaved_bad` (`car_model.cpp:82-87`).
- Options persisted with `settings` and exposed by `RoutingOptions.java`/`RoutingOptions.cpp` (JNI, 57 lines).
- Intermediate stops: `RouteMarkType` with intermediate points (`routing_manager.hpp:240-250`), `Checkpoints` (`router.hpp`).

### Lanes (lane guidance)

- Supported in the core [L]: lanes are read from the feature's `FMD_TURN_LANES(_FORWARD/_BACKWARD)` metadata (`directions_engine.cpp:45-55`), parsed in `routing/lanes/lanes_parser.cpp:56`, recommended (`lanes_recommendation.cpp`, called in `car_directions.cpp:119`) and arrive in `FollowingInfo::m_lanes` (`following_info.hpp:53`) and in Java `RoutingInfo.lanes` (`RoutingInfo.java:81`). Car only (`car_directions`); not for foot.
- Quality depends on the mwm carrying those tags [I]; it has to be checked in central Madrid with the real map (measurement flow).

### Speed limits

- There is a `maxspeeds` section in the mwm (`maxspeeds_serialization.hpp:31`), used by the speed model (`geometry.cpp:105, 185`).
- During guidance: `RoutingSession::GetCurrentSpeedLimit` (`routing_session.hpp:101`, fills `FollowingInfo::m_speedLimitMps` in `routing_session.cpp:484`, `-1` if there is no data: `following_info.hpp:96-97`) and reaches Java in `RoutingInfo.speedLimitMps` (`RoutingInfo.java:86`).
- **Our RF-05's "warning when exceeding the limit" does not come ready-made**: `speedCamLimitExceeded` only refers to cameras (`speed_camera_manager.hpp`, mode `Auto/Always/Never` at `:30-37`). The speed-versus-limit comparison is done by the UI. Cost: 1 day in Kotlin. [L/I]
- Speed cameras: supported (`speed_camera*.cpp`).

### Routes with curves (sinuosity)

Verdict: **there is a realistic path, with C++ work (6-10 days).** Evidence:

1. **The edge cost is editable at a clean point.** `EdgeEstimator` is an abstract class with virtual `CalcSegmentWeight(Segment, RoadGeometry, Purpose)`, `GetTurnPenalty(...)`, `GetUTurnPenalty` (`edge_estimator.hpp:50-54`). `Purpose` distinguishes `Weight` (what A* optimizes) from `ETA` (the displayed time) (`:26-30`), so the weight can diverge from the estimated time without falsifying the ETA. [L]
2. **The geometry is available in the calculation**: `RoadGeometry::GetPoint(i)`, `GetPointsCount()`, `GetDistance(segIdx)` (`geometry.hpp:59-64`) and `GetHighwayType()` (`:48`). Local curvature (angle between adjacent segments) or per-road sinuosity (length/chord) can be computed inside `CalcSegmentWeight`. [L: API; I: that the CPU cost is acceptable]
3. **A\* constraint**: the heuristic uses the model's maximum speed (`EdgeEstimator::CalcHeuristic`, `edge_estimator.cpp:523`; `m_maxModelSpeed` in `car_model.cpp:109-112`). As long as the changes only **increase** the weight (penalize straight, fast stretches, do not reward curves), the heuristic stays admissible. A "bonus" (negative weight) would break A*. [I, reasoning about the algorithm; I did not run it]
4. **Configurable level**: a factor per road type and per sinuosity, parameterized from Kotlin (a `std::atomic`/settings struct read by the estimator). There is precedent for a configurable turn-penalty table per pair of road types (`m_turnPenaltyMap`, `edge_estimator.hpp:68`; data in `edge_estimator.cpp:202-261`).
5. **Passing through forced roads**:
   - Intermediate points (checkpoints) are already supported (`routing_manager.hpp:240`). It works as a one-off "via point", not as "use this road".
   - There is also a **"guides" (GPS tracks to follow)** mechanism: `GuidesTracks` (`router.hpp:23`), `AsyncRouter::SetGuidesTracks` (`async_router.cpp:182, 297`), `IndexRouter::SetGuides` (`index_router.cpp:343`), track-to-OSM connection (`:417-487`). **It is not wired into `RoutingManager`** (only `RoutingSession::SetGuidesForTests`, `routing_session.hpp:176`). [L] Wiring it would allow "follow this GPX with curves and rejoin OSM": 3-5 days, medium risk (little-tested code path, [I]).
6. **Alternative without touching the core**: generate the route with curves outside (another algorithm or an imported GPX route) and pass it as a guide or as a list of intermediate points.

Estimate: sinuosity penalty + motorcycle profile: 6-10 days; guides wiring: 3-5 days; real "fun routes" quality: iterative, requires testing in Guadarrama (measurement flow).

## 4. .mwm format

- Section container (`FilesContainerR`, `MwmVersion`, `libs/platform/mwm_version.hpp:16-39`, formats up to `v8` and later). CoMaps' mwm files carry separate sections: geometry per scale, search index, routing index per vehicle, `maxspeeds`, `cross_mwm`, altitudes, etc. (`libs/routing/*serialization*.hpp`). [L]
- **Tied to the version scheme**: the app only accepts maps of its series: `MAP_SERIES "2026.06.28"` (`private.h:22`), used in the download URL (`libs/platform/downloader_utils.cpp:28`) and in the version check (`libs/storage/storage.cpp:358, 398`). [L]
- The mwm files are generated by CoMaps' `generator` from OSM (`generator/`, `tools/python/maps_generator`, `docs/MAPS.md`). Producing our own requires that pipeline; reusing the official ones avoids the cost but ties us to their calendar. The generator is in the tree (we do not have to write it), but running it for the world is costly in CPU/disk/days [I; not measured].
- Official maps cover the world; the styles (`drules_proto*.bin`) are applied in the app, so changing the style does **not** require regenerating mwm files unless which features exist or their zoom range changes (`docs/STYLES.md`: section "Testing your changes"). [L]

## 5. Licenses

### CoMaps' own code

- **Apache-2.0** (`LICENSE:1-3`; copyright My.com, Organic Maps Contributors, CoMaps Contributors). Apache-2.0 is compatible with GPLv3 in one direction: we can incorporate it into a GPLv3 work (the whole becomes GPLv3). It is not compatible with GPLv2-only. [I, general FSF criterion; not legal advice]
- `NOTICE` warns that `3party/` and `tools/` are under their own licenses, and that some icons may be (C) My.com. The full list is in `data/copyright.html`.

### Dependencies (list in `data/copyright.html`; versions from `.gitmodules`)

| Component | Declared license | Evidence | Risk with GPLv3 / F-Droid |
| --- | --- | --- | --- |
| boost, expat, jansson, pugixml, glm, imgui, glaze, fast_obj, ankerl (MIT/Boost) | MIT/Boost | `copyright.html:148-240`; `3party/ankerl/unordered_dense.h` (SPDX MIT) | Low |
| FreeType | FTL (BSD with attribution) | `copyright.html:171` | Low (FTL is compatible with GPLv3, not with GPLv2) [I] |
| ICU | ICU License | `copyright.html:183` | Low |
| harfbuzz, utfcpp, glfw (zlib), minizip (zlib) | permissive | `copyright.html` | Low |
| AGG | own permissive license ("Permission to copy, use, modify, sell and distribute... provided this copyright notice appears") | `3party/agg/agg_basics.h` header; `copyright.html:149` | Low-medium: not a standard OSI license; record the text in `LICENSES.md` |
| libtess2 | SGI Free Software License B 2.0 | `3party/libtess2/LICENSE.txt` | Low-medium: non-standard permissive license |
| bsdiff/courgette | BSD | `3party/bsdiff-courgette/bsdiff/bsdiff.h` | Low |
| monocypher | BSD-2 / CC0 (dual) | `3party/monocypher/LICENCE.md` | Low |
| succinct, open-location-code, vulkan_wrapper, Vulkan-Headers | Apache-2.0 | headers and `3party/open-location-code/LICENSE` | Low |
| robust (predicates.c, Shewchuk) | "Placed in the public domain" | `3party/robust/predicates.c:9` | Low-medium: public domain declared by the author; some distributions treat it with caution. Record the quote. |
| **kdtree++** | Artistic License (version not stated) | `copyright.html:191-192`; **the `3party/kdtree++/` directory includes no license file or license header** (`kdtree.hpp` opens without one) | **Medium: license declared only in an HTML file.** Artistic 2.0 is compatible with GPLv3; Artistic 1.0 is not clearly so (the FSF treats the original version as non-free). The version has to be verified with upstream (libkdtree). [I] |
| **gb-postcode-data** (data) | **GPL v2.0** | `copyright.html:256-257` | **Medium-high if it is GPLv2-only**: incompatible with GPLv3. It is data that goes into the GB mwm files, not the binary, but it enters the data distribution. Not verified whether it is "v2 only" or "v2+". |
| **Code2000 font** | **"Shareware"** | `copyright.html:323-324`; loaded in `libs/platform/platform.cpp:209` (`fonts/06_code2000.ttf`) and present in `data/fonts/` | **High: it is not free software; incompatible with GPLv3/F-Droid.** It has to be removed and replaced (broad Unicode coverage: Noto). Cost: 1-2 days. |
| Khmer OS (font) | LGPL | `copyright.html:320` | Medium-low: LGPL on a font loaded at runtime; review the embedded-font exception |
| DejaVu, Roboto, Droid Sans Fallback, Jomolhari, Padauk | Bitstream/Apache/OFL | `copyright.html:66-79` | Low |
| Data: US Zip Codes (CC BY 4.0), Code-Point Open and FHRS (OGL v3), Wikipedia (CC BY-SA 4.0), Mangrove (CC BY/BY-SA), SRTM/TIGER (public domain), Sonny LiDAR (CC BY 4.0) | various | `copyright.html:247-274` | Medium: attribution and share-alike obligations on data included in the mwm files. They have to be reflected on the "About" screen. If the data goes inside the mwm files, BY/BY-SA has to be met for that data. |
| OSM (data) | ODbL | `copyright.html:98` | Visible attribution (already requirement RF-13) |
| androidx.car.app (Android Auto) and Material | Apache-2.0 | `android/gradle/libs.versions.toml:56, 67` | Low; using the Car App Library clashes with F-Droid only if it drags in GMS; decide in the Auto phase |

License conclusion: **none blocks the project** but there are three mandatory actions before publishing: (1) remove Code2000, (2) clarify `gb-postcode-data` (GPLv2 yes/no) and decide whether that data is included, (3) verify the Artistic License version of `kdtree++` and add its text to the repo. In addition, the `3party/` submodules were not initialized in the tree when I read it (the other agent was downloading them), so the licenses of boost, expat, glm, etc. are the ones **declared** in `copyright.html`, **not read from the submodule's LICENSE file** [N].

## 6. Location without GMS

Important finding [L]: **CoMaps' `fdroid` flavor is not free of `play-services-*`.**

- `android/app/build.gradle.kts:375`: `implementation(libs.microg.services.location)` for **all** flavors, which resolves to `org.microg.gms:play-services-location` (`android/gradle/libs.versions.toml:45`, version `0.3.14.250932`, `:11`). It is microG's free reimplementation (Apache-2.0) with the `com.google.android.gms.*` API, but the artifact is named `play-services-location`, and this project's `CLAUDE.md` forbids `play-services-*` in `foss`.
- `android/app/src/fdroid/java/app/organicmaps/location` is a symlink to `../../../../google/java/app/organicmaps/location`, that is, the fdroid flavor builds `GoogleFusedLocationProvider` and `LocationProviderFactoryImpl` (they import `com.google.android.gms.common.GoogleApiAvailability` and `...location.FusedLocationProviderClient`).
- At runtime it picks GMS only if `isGooglePlayServicesAvailable(...) == SUCCESS` **and** `Config.useGoogleServices()`; otherwise, `AndroidNativeProvider` (`LocationProviderFactoryImpl.java:19-33`).
- **The no-GMS provider already exists and lives in `:sdk`**: `AndroidNativeProvider` uses `LocationManager` and checks `LocationUtils.FUSED_PROVIDER` first (`sdk/.../location/AndroidNativeProvider.java:91`), with `GPS_PROVIDER` as a fallback (`:99`). It matches the design in `docs/mapas-03-arquitectura.md`.
- Consequence: if we consume `:sdk` and **not** `:app`, our app is GMS-free by construction. Cost of verifying it and wiring `LocationSource`: 2-3 days. Verification on devices without GMS and de-Googled ones belongs to the measurement flow [N].

## 7. Voice (TTS)

- The text of each instruction is generated in C++ (`RoutingManager::GenerateNotifications`, `libs/routing/turns_notification_manager.cpp`, `turns_tts_text*.cpp`) and passed to Java in `nativeGenerateNotifications` (`Framework.cpp:1324-1336`). The language is set by `nativeSetTurnNotificationsLocale` (`android/sdk/src/main/cpp/app/organicmaps/sdk/sound/tts.cpp`).
- Playback is Android's `TextToSpeech` (`sdk/.../sound/TtsPlayer.java:11, 210`) with audio focus (`AudioFocusManager`). Without GMS it depends on a TTS engine being installed. If initialization fails it sets `mUnavailable` (`:211-215`, `:280`). [L]
- **I did not find** in `TtsPlayer.java` a flow to guide the user to install a free engine (eSpeak NG, RHVoice) nor a bundled voice (grepping `INSTALL`, `getEngines`, `isLanguageAvailable` returns nothing). `docs/mapas-03` asks for it; it is our work: 2-3 days for detection + install intent; own bundled voice, 10+ days (not estimated in depth).

## 8. Downloading maps from another server

There is partial support, with a signature trap [L]:

- `Platform::SetCustomMapServerUrl` / `CustomMapServerUrl` (`libs/platform/platform.cpp:154-163`; JNI `Framework.cpp:1654`; UI in `DownloadResourcesLegacyActivity.java:284` with `CustomMapServerDialog`). If there is a custom URL, **the metaserver is skipped** (`libs/storage/map_files_downloader.cpp:81-86, 192`).
- The URL structure is `<server>/<MAP_SERIES>/<dataVersion>/<file>` (`libs/platform/downloader_utils.cpp:28`). It only partly respects the path `/maps/YYMMDD/<region>.mwm` that `docs/mapas-03` assumes: the real format carries `MAP_SERIES/dataVersion`.
- There is a metaserver and CDN list **embedded in `private.h`**: `METASERVER_URL "https://cdn-us-1.comaps.app"` (`:13`), `DEFAULT_URLS_JSON` with 7 mirrors (`:14`), `COUNTRIES_TXT_SIGNATURE_HEX` (`:19`), `MAP_SERIES` (`:22`). Changing them means editing one file (`private.h`), without touching the logic.
- **`countries.txt` signature**: the region list is verified with Ed25519 against the public key in `private.h` (`libs/storage/storage.cpp:406-433`); without a valid signature the update is ignored. Our own server must either mirror the official files with their signature, or sign with **our key** and rebuild with our `COUNTRIES_TXT_SIGNATURE_HEX`. The private key must not live in the repo (rule in `CLAUDE.md`).
- **mwm integrity**: SHA-1 (base64) per region compared with the one in `countries.txt` (`libs/storage/storage.cpp:1084`), not SHA-256 as `docs/mapas-03` asks (RF-02). Switching to SHA-256 means touching C++ and the `countries.txt` generator: 2-3 days.
- Official documentation for an own server: `docs/DEPLOY_OWN_MAP_SERVER.md` (community tools that serve the official files).
- Privacy [L]: the client contacts the metaserver and `libs/storage/pinger.cpp` exists and, by its name and its inclusion of `http_client`, probes servers [I; I did not read its body]; both are network egress that has to be audited under `NetworkPolicy`. Android networking goes through Java: `sdk/.../util/HttpClient.java`, `sdk/.../downloader/ChunkTask.java` and `sdk/.../editor/OsmOAuth.java`. In C++ the HTTP client is implemented via JNI (`sdk/src/main/cpp/.../platform/HttpThread.cpp`) and there are `editor/osm_auth.cpp`, `traffic/traffic_info.cpp` (empty URL in `private.h`: `TRAFFIC_DATA_BASE_URL ""`). [I] A central `NetworkPolicy` can wrap those three Java points; 3-4 days. I have not searched exhaustively for telemetry: I found no alohalytics or similar in `libs/` (grepping `alohalytics` gave no results), but that does not guarantee total absence [N].

Cost of an own map server: 2-3 days of code (key, `private.h`, SHA-256) + the operational cost of generating or mirroring mwm files (not estimated).

## 9. Cost summary (days, estimate)

| Block | Days |
| --- | --- |
| Decoupling + Kotlin facade + minimal screen (section 1) | 15-21 |
| Motorcycle (estimator, router type) | 5-8 |
| Sinuosity + soft "prefer to avoid" options | 6-10 |
| Guides wiring (follow GPS track) | 3-5 |
| Speed-limit exceeded alert (Kotlin) | 1 |
| TTS: detection and install guidance | 2-3 |
| Own server, signature, SHA-256 | 2-3 |
| `NetworkPolicy` wrapping HttpClient/ChunkTask/OsmOAuth | 3-4 |
| License cleanup (Code2000, kdtree++, GB postcodes) | 2-3 |
| **Indicative total** | **~40-58** |

What is **not** in this report and decides option A/B/C: fps, startup, search latency and route time on device (measurement flow), and the visual resemblance to Apple Maps (see `comaps-style.md`).
