# Decision log

Format: date · decision · reason · discarded alternatives · how to revert it.

## 2026-10-06 · Repo bootstrap locally, no push to `master`
- **Decision:** `master` is created only locally (initial commit with `docs/` and `CLAUDE.md` copied from `docs/mapas-CLAUDE.md`). Nothing is pushed until the user creates `master` on the remote and sets up CI and branch protection.
- **Reason:** the `origin` remote is empty (no `master`), so PRs cannot be opened. Pushing to `master` is forbidden by CLAUDE.md, and creating `.github/workflows/` or branch protection is "When to ask" case no. 5.
- **Discarded:** pushing an initial commit to `master` (violates the flow); pushing a spike branch (GitHub would make it the default branch).
- **Revert:** `git push origin master` after agreeing it with the user; the local `spike/*` branches can be pushed and opened as PRs from that moment.

## 2026-10-06 · `.claude/settings.json` and `.github/workflows/ci.yml` are not installed
- **Decision:** `mapas-claude-settings.json` and `mapas-ci.yml` are left untouched in the root.
- **Reason:** the first one changes my own permissions and the second one is a workflow (CLAUDE.md, "When to ask" no. 5).
- **Revert:** copy them to `.claude/settings.json` and `.github/workflows/ci.yml` (step 2 of `docs/mapas-README.md`).

## 2026-10-06 · The CoMaps source code lives outside the repo
- **Decision:** it is cloned into `~/repos/comaps-spike/` (outside the repo), pinned to a stable tag; the repo only keeps scripts and results in `spike/` and `docs/spike/`.
- **Reason:** explicit user instruction; it avoids mixing Apache-2.0 with our own code before deciding.
- **Discarded:** submodule (valid, but adds noise to a throwaway spike).
- **Revert:** `git submodule add` later if A or C is chosen.

## 2026-10-06 · Devices and concurrency in the spike
- **Available device:** a single Pixel 8 (Android 17, SDK 37, arm64, 120 Hz, with GMS). It covers only the "high-end with GMS" class.
- **Decision:** access to the device is serialized with `flock`; C++ builds limited to `-j6` because of free RAM (~5 GB of 30 GB at the start).
- **Consequence:** mid-range/low-end, Chinese ROM without GMS and de-Googled remain "not measured". Without those classes, neither the "Without GMS" criterion nor the mid-range thresholds can be decided.

## 2026-10-06 · The main branch is called `master`
- **Decision:** the main branch is `master` (not `main`), at the user's instruction. Where the package documents say `main` (playbook, CI, `settings.json`), read `master`.
- **Pending for the user:** the `mapas-ci.yml` workflow (`branches: [main]`), the deny rules in `mapas-claude-settings.json` (`git push origin main *`) and branch protection must point to `master`.

## 2026-10-06 · At most 4 simultaneous subagents
- **Decision:** never more than 4 active subagents at a time; the MapLibre workstream (d) is launched when one of the initial four finishes.
- **Reason:** user instruction (and limited RAM, ~5 GB free).
- **Revert:** only at the user's instruction.

## 2026-10-06 · CoMaps inherited licenses that block linking it as is in a GPLv3 app
- **Decision:** before reusing CoMaps code (options A/C), `3party/bsdiff-courgette/bsdiff` (BSD Protection License, GPL-incompatible), the font `data/fonts/06_code2000.ttf` (shareware) and the Entypo icons (CC BY-SA 3.0) must be excluded or replaced. "GPLv3 or later" is pinned, never GPLv2-only.
- **Reason:** see `docs/spike/verificaciones.md` §1.2. Apache-2.0 is indeed compatible with GPLv3.
- **Discarded:** assuming that all of `3party/` is permissive.
- **Revert:** if the author of bsdiff or a legal advisor confirms compatibility, remove the exclusion.

## 2026-10-06 · Engine-independent code: JVM modules and parsers without StAX
- **Decision:** `:core-geo`, `:core-net`, `:core-map`, `:core-search` and `:core-routing` are Kotlin/JVM modules (`./gradlew test` without Android); only `:app` is Android (`foss` flavor, minSdk 26, provisional `applicationId` `com.qtekfun.mapas`). XML (GPX/KML) with `org.xmlpull.v1.XmlPullParser` (platform on Android; kXML2 only in JVM tests), not StAX, because `javax.xml.stream` does not exist on Android. Takeout GeoJSON with kotlinx-serialization-json; CSV with an own reader.
- **Reason:** fast tests and code that can be kept with any engine (A/B/C). If the chosen engine requires `:core-map` to be an Android module, it will be converted then.
- **Details:** kXML2 accepts truncated files without error, so the importers check `depth == 0` at the end. Apple `?ll=...&q=Name` is interpreted as a pin with a label (not as a search). A `geo:0,0?q=text` is a search without position bias.
- **Discarded:** StAX (not portable to Android); `org.json` (not available on plain JVM).

## 2026-10-06 · Engine (A/B/C): no firm decision; provisional recommendation C
- **Decision:** none of A, B or C is declared decided. Provisional recommendation C (MapLibre + PMTiles for rendering, CoMaps core for search/routing). Phase 1 is not started.
- **Reason:** Madrid–Barcelona route 17.8-18.0 s at idle (threshold 2 s) and search 631 ms (threshold 100 ms, measured under load) do not meet the thresholds; without-GMS, mid-range and decoupling at runtime are missing. C's rule requires the engine to pass. See `docs/spike-informe.md`.
- **Discarded:** A (thresholds and microG dependency); B (no evidence of a decoupling or license block, and the cost of the worldwide pipeline; Valhalla not measured); deciding firmly with critical data missing.
- **Revert/close:** the user chooses A/B/C or provides devices and the pending measurements are repeated (report, "What is missing").

## 2026-10-06 · The spike branches are integrated into local `master` with squash
- **Decision:** `spike/*` and `feat/core-geo-skeleton` are integrated into local `master` without a PR (no remote or CI). 83 tests passed with `./gradlew test --rerun-tasks` as the only "check". The files `mapas-ci.yml` and `mapas-claude-settings.json` were versioned in the root as text (they activate nothing).
- **Revert:** `git reset --hard 559cf42`... (local only; the original branches still exist).

## 2026-10-06 · The Pixel 8 is used only with the user's explicit permission
- **Decision:** no `adb` command against the Pixel 8 (install, measure, `am`, `dumpsys`, `input`, etc.) without the user's explicit permission each time.
- **Reason:** user instruction.
- **Consequence:** the pending measurements (search at idle, lanes, MapLibre with SurfaceView) wait for that permission.
- **Revert:** only at the user's instruction.

## 2026-10-06 · Engine decided by the user: option C (hybrid)
- **Decision:** option C. Rendering with MapLibre Native + PMTiles; search, routing and worldwide data with the CoMaps core (`.mwm`), without its activity or its UI. Phase 1 starts. It supersedes the earlier "provisional recommendation".
- **Reason:** explicit user decision ("C, implement it"), after the spike report.
- **Risks it carries (unresolved):** long route ≈ 18 s and search ≈ 0.6 s of the CoMaps core (R12), not measured without GMS or mid-range (R16), inherited licenses (R11), double download per region (R17).
- **Discarded:** A and B.
- **How to revert:** changing option is "When to ask" case no. 3 of CLAUDE.md. The `MapEngine`/`SearchEngine`/`RoutingEngine` interfaces isolate the engine.
- **Current restriction:** the Pixel 8 is not used without explicit permission; Phase 1 is developed with building and tests on the PC.

## 2026-10-06 · `:core-regions`: own catalog with SHA-256 and activation by manifest
- **Decision:** JVM module `:core-regions` with an own catalog (schema 1, two assets per leaf, SHA-256) instead of consuming `countries.txt` (SHA-1, Ed25519 signature) directly. Atomic activation = rename of each verified file + atomic replacement of `installed.json`. Details and mapping in `docs/phase1/regions.md`.
- **Reason:** RF-02 asks for SHA-256 and option C needs to join PMTiles and `.mwm` into a single region.
- **Discarded:** reusing the signed CoMaps catalog (forces our Ed25519 key and a recompile; only documented, not implemented).

## 2026-10-06 · Local storage: androidx.sqlite directly, without Room
- **Decision:** `:core-data` is a pure JVM module that uses the `SQLiteDriver` interface of androidx.sqlite 2.7.0 (Apache-2.0) with a hand-written repository (`SqlitePlacesRepository`). Tests on the PC with `sqlite-bundled`; on Android the app will inject the `sqlite-framework` driver. Schema versioned with `PRAGMA user_version`.
- **Reason:** Room needs KSP and an Android module (AGP 9 + Kotlin 2.4 unverified), which would prevent testing on the JVM; the schema is small. Same licenses, no proprietary services.
- **Dedup:** places by normalized name + position to ~1 m; tracks by SHA-256 of name, type and geometry. Backup: ZIP with `mapas-backup.json` (format 1), atomic MERGE or REPLACE restore. KML does not distinguish route/track: routes are re-imported as tracks.
- **Discarded:** Room KMP (toolchain risk); plain JSON without SQLite (no queries).
- **Revert:** migrate to Room on the same schema if needed; the `PlacesRepository` interface isolates the change.
## 2026-10-06 · `:app` viewer: own design system, MapLibre and no INTERNET permission
- **Decision:** `:app` uses Compose `ui`+`foundation` (without Material) with own tokens (`ui/theme/Theme.kt`), an own 3-detent bottom sheet (`ui/sheet`, pure geometry tested on the JVM) and a fixed OSM attribution at the top left (RF-13). Engine: MapLibre Native 13.6.1 (`MapLibreEngine`) with PMTiles from `filesDir/maps/` and protomaps light/dark styles generated from a template (`@MAPDIR@`, `@PMTILES@`). Sprites and glyphs are copied from assets to `filesDir/map/` on first launch (the native engine does not read `file://` under Android/data). The manifest removes `INTERNET`: the viewer cannot open connections; it will be added with the first feature under `NetworkPolicy` (downloads). `compileSdk` 37 (required by Compose 1.12), `targetSdk` 36. `:app` uses JUnit 4 (Robolectric 4.17, MIT, tests only) and the `core-*` modules stay on Jupiter.
- **Short links:** only a notice is shown; they are not resolved and `NetworkPolicy.authorize` is not called (no connection is attempted).
- **Pending:** sprites and glyphs are NOT bundled (access to their host was blocked during this work): run `scripts/fetch-map-assets.sh` (needs network). Without them the map draws no icons or labels. Performance and startup: not measured (no device). Rows added to LICENSES.md.
- **Discarded:** Material3 (an own system was requested), `play-services-location` (forbidden), MapLibre's `LocationComponent` (own marker via GeoJSON, no per-frame work).
## 2026-10-06 · CoMaps core as an own native module, without Framework or drape
- **Decision:** `:native-comaps` compiles search + routing + storage + indexer + platform from `third_party/comaps` (submodule at `v2026.10.05-19`) with an own CMake, without `drape`, `drape_frontend`, `map` (Framework) or bookmarks. C++ facade (`DataSource` + `search::Engine` + `IndexRouter`) and Kotlin implementing `SearchEngine`/`RoutingEngine`. arm64-v8a only, no LTO, `-j6` at most. Headless `Platform` without network (the network is Kotlin's).
- **Licenses:** bsdiff-courgette is not compiled (`mwm_diff` replaced by a stub: no diffs), Code2000 and Entypo are out of the assets. See `LICENSES.md`.
- **Reason:** consuming CoMaps' `:sdk` drags in Framework, Drape, editor, bookmarks and 114 JNI functions; `IndexRouter` + `search::Engine` are covered by CoMaps' integration tests and leave the binary at 7.7 MB.
- **Discarded:** compiling `libs/map` without Drape (Framework's constructor calls `df::`); patching CoMaps (the submodule stays intact).
- **Not verified:** execution (no device allowed). See `docs/phase1/native-core.md`.

## 2026-10-06 · Integration of `feat/comaps-core-native` only partly verified
- **Decision:** it is integrated into local `master`. `./gradlew test --offline --rerun-tasks` gives 153 green tests (repeated by me). A full `assembleDebug` I **did not repeat** on `master`: it fails because `third_party/comaps` is not initialized in this checkout; the agent built it in its worktree (`libumcomaps.so` arm64 7.7 MB). The native code has never been executed (no device allowed).
- **Reason:** not to initialize 2 GB of submodules or force a long build with little RAM unnecessarily; the next useful step is to run it.
- **Revert:** `git revert` of the integration commit.

## 2026-10-07 · Pixel 8 handed over to another session; core tests paused
- **Decision:** the user ordered to stop using the phone ("you are very slow") and another session (ultimateVE) uses it for its tests, with the lock `/tmp/pixel-device.lock`. This session runs no `adb` until further explicit permission.
- **State of the core test:** the test bench (`app/src/debug/.../CoreBenchActivity.kt`, debug only) did manage to start on the Pixel 8. First real failure: `CoMaps init: File not found drules_proto_walking_light.bin`; fixed in `scripts/comaps-prepare.sh` and in the asset list. The second attempt did not produce results (the device went offline). **There are no search or route figures for our own core.**
- **Left on the phone** (do not touch without warning): `com.qtekfun.mapas` with `files/maps/madrid.pmtiles` and `files/maps-core/261004/` (World, WorldCoasts and 7 regions, ≈ 0.8 GB).

## 2026-10-07 · Automatic release on GitHub, like UltimateDeck
- **Decision:** single `appVersion` in `gradle.properties` with derived `versionCode` (0.1.0-rc.1 → 10001), signing through `UM_KEYSTORE_*` variables, `CHANGELOG.md` and `RELEASING.md`, and `.github/workflows/release.yml` triggered by a `v*` tag (tag = `appVersion`, notes required, signed APK + `.sha256`, `-rc.N` as pre-release). Reference reviewed: `~/repos/ultimatedeck` (`release.yml`, `RELEASING.md`, `app/build.gradle.kts`).
- **Deliberate differences:** the workflow **fails if the signing secret is missing** (UltimateDeck would publish an unsigned APK); `lintFossRelease` instead of the full `check`; no minification for now; action SHAs pinned the same as in UltimateDeck.
- **Verified:** `assembleFossRelease` generates `app-foss-release-unsigned.apk` with `versionCode=10001`, `versionName=0.1.0-rc.1` (`aapt2 dump badging`); valid YAML; the notes extraction with `awk` works. **Not verified:** the workflow on GitHub (no remote or secrets).
- **Authorization:** the user explicitly asked for this automatic release; it is the only part of `.github/workflows/` that is touched. CI (`ci.yml`) and branch protection are still pending on the user (CLAUDE.md, "When to ask" no. 5).
- **Pending for the user:** create the key and the secrets (`RELEASING.md`), `master` on GitHub, the protection rule (there is a template in `~/repos/ruleset-master.json`) and the first tag.
- **Revert:** delete `.github/workflows/release.yml`.
## 2026-10-07 · M2/M3: search in production and saved places (branch `feat/mvp-search-places`)
- **Decision:** `:native-comaps` becomes an `implementation` of `:app`. The core is started lazily (first query or region change), off the main thread, over `filesDir/maps-core/` through the `search.InstalledRegions` interface (`DirectoryInstalledRegions` scans `<version>/World.mwm` and counts regions; the regions module can supply its own to `PanelHost`). Queries with a 250 ms debounce; a new keystroke cancels the previous one and native calls are serialized with a `Mutex` (a single `Core` per process). Without regions: empty state and the core is not started.
- **Latency (for the future test on the phone, not run):** logcat `UMSEARCH` with `engine_ready_ms`, and per query `qlen`, `results`, `ms` and `first` (first after startup). No query text or positions. Measured with `SystemClock.elapsedRealtime` around the native call (does not include the debounce).
- **Places:** `sqlite-framework` driver (`AndroidSQLiteDriver`) in `databases/places.db`; the default list ("Favoritos", i.e. Favorites) stores its id in SharedPreferences and is recreated if deleted. GPX/KML import/export with the document picker (SAF: `OpenDocument`/`CreateDocument`, no storage permissions; format by extension and, failing that, by content; 32 MB cap). Saved place markers: own circle layer in `MapLibreEngine` (`MapEngine.showMarkers`), no sprites. Sorting by distance uses the last known location in memory or, if there is none, the camera center.
- **Pending:** the detail card's "Ruta" (Route) button only shows a notice (M4); fixed zoom 15 when choosing a result (the core returns no extent); measure R12 and fluidity on the phone.

## 2026-10-07 · The release workflow prepares the CoMaps core
- **Decision:** after integrating M2, `release.yml` runs `git submodule update --init third_party/comaps` and `scripts/comaps-prepare.sh` before building (the core now ships in the release APK).
- **Verified:** local `assembleFossRelease` with the core: OK, 2 min 51 s, unsigned APK 42.3 MB. **Not verified:** the workflow on GitHub (no remote or secrets), nor the runner's NDK/CMake/time/RAM.
- **Revert:** remove the "Prepare the CoMaps core" step (the release would stop building).
## 2026-10-07 · M0 + M1 (branch `feat/mvp-regions`): map assets, "Mapas" screen and downloads
- **M0 finding:** `scripts/gen-map-style.mjs` did not pass `lang` and `@protomaps/basemaps` **does not generate label layers without it** (57 layers, 0 `symbol`): the map was never going to have text even with glyphs. Now `lang=es` by default (falls back to `name`, the local name); 71 layers, 14 `symbol`. Sprites v4 and 3 Noto Sans fonts (ranges 0-255, 256-511, 8192-8703) bundled, ~1.3 MB (OFL/BSD-3). Ranges and fonts that do not exist (e.g. Devanagari) return a file error and MapLibre just omits those glyphs. Not verified on a device (folder names with spaces in `file://`).
- **Pin:** `LinkOutcome.pinPoint()`; `handleLink` always replaces or clears the pin (short links/unrecognized/search clear it).
- **`countries.txt` (real fields, verified):** root `{id:"Countries", v:261004, map_series:"2026.06.28", g:[…]}`; leaves `{id, s (bytes), sha1_base64, old, affiliations, country_name_synonyms?}`; groups `{id, g}`. `v` is global. The ids contain spaces and there are 5 nodes repeated under two parents (Campo de Hielo Sur, Abkhazia, South Ossetia, Jerusalem, Crimea). `scripts/gen-region-catalog.py`: own id = ASCII slug (`spain_community-of-madrid`), original `comapsId` as an extra field (the parser ignores it), the first appearance of each node is kept, SHA-256 of real files (`--mwm-dir`, `--pmtiles-dir`); a leaf is downloadable only with both files; `--fetch-mwm` downloads ONE .mwm on request with a 20 MB cap. The default base URL of the .mwm (`<server>/maps/<series>/<v>/<id>.mwm`) comes from the documentation, it was not checked with network.
- **There is no own server or default catalog:** the app ships no URL. The user types the catalog's URL in "Mapas" (Maps); its host (and those of the catalog's asset URLs, which that server decides) are added to `NetworkPolicy` as `MAP_DOWNLOAD` and are listed under "possible connections". Without a server: zero connections.
- **INTERNET restored** (the `tools:node="remove"` is removed) only for this flow; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. "No network mode" persisted (`offline_mode`) and applied in `Application.onCreate`: tested with a local server that no request arrives.
- **Service:** `RegionDownloadService` (dataSync) only shows the notification and stops by itself; the work is done by `RegionsController` (sequential queue, pause keeps the `.part`, resumes with `Range`). Android 15: `onTimeout` pauses everything (dataSync limit ~6 h).
- **Known limits:** the style has a single PMTiles source, so with several regions installed only the first by id is drawn (one PMTiles extract per country is advisable); installing on an SD card is allowed but the native engine is only known to read from `filesDir` (warning in the UI); if an update is interrupted after the first file, that file is downloaded again (the `.part` was already moved).
- **Discarded alternative:** a catalog embedded in the APK (no reliable download URLs or real SHA-256) and auto-downloading the catalog at startup (the app does not connect without a user action).

## 2026-10-07 · Map data hosted on GitHub Releases, one PMTiles per region
- **Decision:** public repository `qtekfun/UltimateMaps-data` (created at the user's request: "Give it to GitHub") with data releases. First release `data-261004-20261006`: 25 regions of Spain, one `.pmtiles` and one `.mwm` per region (50 files, 4.34 GB; the largest, Castilla-La Mancha, 209 MB), plus `World.mwm`, `WorldCoasts.mwm`, `catalog.json` and `SHA256SUMS`. Stable catalog: `https://github.com/qtekfun/UltimateMaps-data/releases/latest/download/catalog.json`.
- **Reason:** free, HTTPS and `Range`, no new accounts, reversible (the app only knows the catalog URL). Splitting by regions leaves all files well below the 2 GB limit and allows downloading only what is needed.
- **How it was done (reproducible):** `.mwm` copied unmodified from the CoMaps CDN (data 261004); PMTiles with `scripts/split-pmtiles.py` (clipping with CoMaps' `data/borders/*.poly` polygons over the Protomaps build 20261006); catalog with `scripts/gen-region-catalog.py --mwm-url-by-slug` (SHA-256 of the real files). Local data in `~/mapas-data/` (outside the repo).
- **Incident fixed:** Canarias, Ceuta and Melilla came out almost empty when cut from an extract that did not cover them; they were redone from the full planet (62, 1.5 and 1.7 MB).
- **Risks and warnings:** we have not found terms of use for the CoMaps CDN (the README says so and offers to remove the `.mwm` at the project's request); GitHub renames the spaces in asset names, which is why the `.mwm` URLs use the slug; the data is ODbL (attribution and share-alike, in the README).
- **Known gaps (pending tasks, see `docs/mvp-plan.md`):**
  1. **Link what was downloaded to the core:** the catalog installs the `.mwm` as `<slug>.mwm` in `<root>/<id>/<version>/`, but the core requires `maps-core/<version>/<comapsId>.mwm` (e.g. `Spain_La Rioja.mwm`). `comapsId` must be stored in the model and the symbolic link created on install and delete.
  2. **`World.mwm` and `WorldCoasts.mwm`** are in the release but not in the catalog schema (they are not a region).
  3. **Default catalog URL** in the app (today the user types it) and `NetworkPolicy` allowlist for `github.com` and the asset redirect hosts.
  4. **Multi-region drawing** (agent in progress): the style only draws one region; regions overlap at the border.
- **Revert:** `gh release delete data-261004-20261006` and delete the data repo; the app does not depend on it until the default URL is set.
## 2026-10-07 · Multi-region rendering (branch `feat/multi-region-render`)
- **Decision:** the style is generated at runtime (`map/MultiRegionStyle.kt`) from the single-source template: one `pmtiles://file://…` source per installed region (`protomaps-<i>`, ordered by region id) and a copy of each layer with a per-region source, in layer-major order (layer 1 of all regions, then layer 2…) to preserve z-order across regions. Copy 0 with the original id, the others `<id>@<i>`. `background` only once. Without regions: no sources, only background (it no longer points to a nonexistent `none.pmtiles`). MapLibre Native (≥ 11.7, Context7) supports several `pmtiles://` and `file://` sources.
- **Overlap at borders:** opaque fills and lines repaint the same pixels (harmless). Translucent layers (`buildings` 0.5, `landuse_urban_green` 0.7, `roads_rail` 0.5; `landcover` only between z5 and z7) look denser in the overlap strip. Duplicate labels (same text and place) collide with each other and only one is placed (they do not use allow-overlap). Not mitigated further: it is not known which region "wins" without knowing the polygons; pending to be seen on the phone.
- **Cost (estimate, NOT measured):** 71 layers per source (41 line, 15 fill, 14 symbol, 1 background) → 1 + 70·N layers: 3 regions = 211, 10 = 701, 25 = 1751 (`MAX_SOURCES` cap = 25; the rest is omitted and logged). The style grows ~linearly (≈ 0.3 MB of JSON with 25). Only sources with tiles in the viewport cost per frame (normally 1–3), but the layer list and style reconciliation do grow with N, and so does style loading at startup. No dedup of earth/water: each extract contains only its polygon, so one source cannot cover the others. Options if it measures badly: a low-resolution worldwide PMTiles (z0–z5) as the single land/water source and extracts only from z6; or more than one region per extract (country/community).
- **Metric:** logcat `UMSTYLE` on each style load: `sources`, `layers`, `template_layers`, `skipped`, `json_kb`, `build_ms`, `style_load_ms` (no paths or locations). Fluidity with N regions: not measured.
- **Reload:** `refreshTilesIfChanged` compares a signature (path + size + mtime of each PMTiles) instead of the first path; when returning from "Mapas" (Maps) (`onStart`) it reloads if a region was installed, deleted or replaced. No restart needed.
- **Tests:** `MultiRegionStyleTest` (Robolectric): 0, 1, N regions, unique ids, no undefined or unused source, cap and path escaping.

## 2026-10-07 · Data release published and verified (partially)
- **State:** `data-261004-20261006` published (not a draft): 54 files, 4.40 GB, at `https://github.com/qtekfun/UltimateMaps-data/releases/tag/data-261004-20261006`.
- **Verified with real downloads (curl and urllib):** the stable catalog `…/releases/latest/download/catalog.json` answers 200 and is byte-for-byte identical to the local one; `Range` returns 206 with the correct bytes (start and middle of a Madrid PMTiles); full downloads of Canarias, Ceuta, La Rioja and Melilla (render and search, 8 files): size and SHA-256 match the catalog.
- **Datum for the network allowlist:** downloading an asset does a 302 from `github.com` to **`release-assets.githubusercontent.com`** (signed URL with a short expiry). Both hosts must be allowed (agent `feat/mvp-regions-core-link`).
- **Not verified:** the other 21 file pairs, `World.mwm` and `WorldCoasts.mwm` (they are uploaded and hashed in `SHA256SUMS`, not re-downloaded), nor GitHub's quota or bandwidth limits for real user traffic.
## 2026-10-07 · M4: route preview (branch `feat/mvp-route-preview`)
- **Decision:** the detail card's "Ruta" (Route) opens `RoutePanel` (origin = current location, car/foot/bike profile, avoid motorways/tolls/ferries/unpaved with `RouteOptions`, distance and time, close). Without a location a notice is shown and the origin is chosen by searching or tapping the map (`MapEngine.setMapTapListener`, only while choosing). `MapEngine` gains `showRoute`/`clearRoute`/`setMapTapListener` with an empty default implementation; `MapLibreEngine` draws a `LineLayer` under the points and frames the route with bottom padding for the panel.
- **Concurrency:** each computation runs on IO, is cancelled when the profile/option/origin changes and is serialized with search through a single shared `Mutex` (a single `CoMapsCore`). A native call cannot be interrupted: the **timeout (30 s)** only stops waiting and discards the late result, and the same budget is passed to the native router; an already busy core delays the next computation. The core's `CANCELLED` is shown as a timeout.
- **Latency (R12, not measured):** logcat `UMROUTE` with `route profile=<profile> ms=<n> result=<ok|need_more_maps|start_not_found|end_not_found|route_not_found|timeout|no_regions|internal|cancelled>`. No coordinates or names. Measured from launch to result (includes the wait for the `Mutex` and the core's cold start).
- **Limits:** there is no turn-by-turn; the chosen origin has no marker of its own; the time of long routes is still not measured on the phone; the list of missing regions (`NEED_MORE_MAPS`) is not detailed.

## 2026-10-07 · Intermittent `RoutePanelTest` test: fixed in the test, not in the code
- **Symptom:** after integrating M4, `explainsNeedMoreMaps` failed in ~1 of every 2 full suites and 0 of 3 in isolation.
- **Diagnosis (with a semantics tree dump on failure):** the controller state was `ERROR/NEED_MORE_MAPS`, but the screen still showed "Calculating route…". The test started the computation on background threads (`Dispatchers.Default/IO`) before composing and waited for a recomposition triggered from another thread; in a JVM with many Compose/Robolectric tests that recomposition did not always arrive.
- **Discarded attempts (did not work, 2 of 4 and 0 of 6):** waiting for the node instead of the state; forcing `Snapshot.sendApplyNotifications()`; making the controller work on `Dispatchers.Main`.
- **Fix:** the test waits (without touching the UI) for the computation to finish and composes the screen afterwards, so the first composition reads the final state. Nothing is weakened: the same texts, profiles and the close button are still checked. Result: 6 full suites in a row green.
- **What it does not cover:** the test no longer checks that the UI recomposes when a result arrives from another thread; the logic with real threads stays in `RoutePreviewControllerTest`. If the app showed "Calculando…" (Calculating…) after a result on the device, these tests would not detect it: check it on the phone.
## 2026-10-07 · Link with the core, World and default catalog (branch `feat/mvp-regions-core-link`)
- **Decision:** `Region.comapsId` and `installed.json` (backward compatible); `base` block in the catalog (World/WorldCoasts, once per version, `<root>/.base/<v>/`); `CoreMapsLinker` (symbolic links, hard links as a fallback, `.links.json` ledger) that leaves `filesDir/maps-core/<v>/<comapsId>.mwm` + `World*.mwm` at startup and after install, update or delete; default catalog `https://github.com/qtekfun/UltimateMaps-data/releases/latest/download/catalog.json` with `github.com`, `release-assets.githubusercontent.com` and `objects.githubusercontent.com` in the allowlist (only when using a github.com catalog). Details in `docs/phase1/regions.md`. They close gaps 1, 2 and 3 of the previous entry.
- **Reason for the symlink:** it works towards the SD card (another volume) and does not duplicate 4 GB; the hard link is the fallback. The core reads with `stat`/`fopen` (they follow links).
- **Finding (core):** `RefreshMaps` registers new maps and newer versions, but there is no unregistration: after **deleting** a region the core keeps serving it until the app is restarted (singleton). The UI warns about it (`restartNeeded`). An update needs no restart.
- **Verified:** JVM tests (`:core-regions`, `:app`): linker, catalog/base, 302 redirects to another authorized host and not, catalog -> install with World -> `maps-core/<v>/` structure with exact names (`Spain_La Rioja.mwm`) -> update -> delete integration. Redirect chain of our release checked with HEAD. **Not verified:** that the core really reads through links on Android (nor on the card), the real download of the release, nor real search with these files (no phone).
- **Limits:** if a card is not mounted at startup, its regions are unlinked until the next startup or install; a catalog without `base` cannot download World (the link uses the already installed base, if there is one); a single `World*.mwm` per version in each storage.
- **Discarded alternative:** copying the .mwm to `maps-core` (duplicates space and does not work on the card) and downloading the catalog at startup (the app does not connect without a user action).

## 2026-10-07 · First real test of the core on the Pixel 8: it starts and searches; long route unresolved
- **What was done:** with the user's permission ("use it now"), release APK signed with the debug key (only to update over the installed app and keep data), with the lock `/tmp/pixel-device.lock`. Details and screenshots in `docs/phase1/device-test/release-rc1/`.
- **Fixed:** the core aborted at startup (wrong style classifier, see the README). Now `SetCurrentStyle(kDefaultMapStyle)`; `scripts/comaps-prepare.sh` generates `drules_proto_default_light.bin` before the vehicle style; `um_core.cpp` hooks CoMaps' log and `CHECK` to logcat (tag `UMCORE`) so it does not abort silently again.
- **Measured (R12):** warm search 484-4201 ms (n=6, threshold 100 ms): fails. Madrid–Barcelona route: `route_not_found` in 549 ms with 7 regions: no valid measurement against the 2 s threshold. In the spike (25 regions) it was ≈ 18 s.
- **Pending decision (the user's):** R12 is still open; option C is already chosen (changing it is "When to ask" no. 3). The next useful measurement: the same route with all 25 regions installed, and search with 1-2 regions, to separate the effect of the number of regions.
- **Risk:** the test APK (`~/mapas-data/test-builds/`) is signed with the debug key: it is not installable as an update over a release signed with the project key.

## 2026-10-07 · Preparation of release 0.1.0-rc.1 (without the Pixel 8, which the user withdrew)
- **Decision:** everything that needs neither the phone nor secrets is prepared: `LICENSE` (GPL-3.0, copy of UltimateDeck's), `README.md`, `PRIVACY.md` (es/en), `CHANGELOG.md` with real notes and limitations, `fastlane/metadata` (es-ES and en-US), draft `fdroid/com.qtekfun.mapas.yml` without `Builds`, `usesCleartextTraffic="false"`, and the `release.yml` workflow with explicit installation of NDK 28.2 and CMake 3.31.6. `RELEASING.md` lists what remains: the user's, the phone's and F-Droid risks.
- **License finding:** `kdtree++` IS compiled (included by `libs/geometry/tree4d.hpp`; 16 strings in the `.so`) and its license is Artistic License 2.0 according to the headers (`function.hpp:83`), compatible with GPLv3. `LICENSES.md` said the opposite ("not compiled here"): fixed. Its text still has to be included in a `NOTICE` or in "About".
- **Verified:** `network permissions` (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) come from MapLibre 13.6.1 (manifest merger report); 267 green JVM tests; valid workflow YAML. **Not verified:** the workflow on GitHub, `lint`/`assembleRelease` after these changes (see below), nor F-Droid.
- **Reproducibility test NOT completed:** I launched two clean builds of the unsigned APK to compare entries, but the system reached low free memory (27 of 30 GB) and stopped my wait command; I stopped my build processes so as not to harm the rest of the machine. There is no result, neither positive nor negative. Repeat when there is free memory and at the user's request: `./gradlew clean :app:assembleFossRelease` twice and compare `unzip -v`. A reproducible APK is an F-Droid requirement (as in UltimateDeck).
- **Side effect:** `gradle clean` deleted `app/build` and `native-comaps/build`; the next native build will take a while (≈ 3-5 min).
- **Revert:** `git revert` of this commit; it does not change app code except the manifest line.
