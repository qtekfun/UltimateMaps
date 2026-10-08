# MVP plan

Date: 2026-10-07. Option C (user decision, `docs/decisions.md`). It is equivalent to the "done when" of Phase 1: **usable day to day as an offline viewer and search tool**.

## MVP definition

A person installs the app, downloads Spain, and can:

1. **View the map** offline, fluid, with labels and icons (sprites and glyphs are missing today), day/night.
2. **Download and manage regions** (Spain first): progress, resume, verify, delete, update. This is the only thing that uses the network, only at their request.
3. **Search** places and addresses offline while typing, see the result on the map and its details.
4. **Save places** in lists (favorites), see them on the map, import/export GPX and KML.
5. **Open links** from Google Maps, Apple Maps, Waze and `geo:` (done; Waze/Apple still need to be tested and the pin cleaned up).
6. **View a route** by car, on foot or by bike between two points (preview with distance and time), without turn-by-turn guidance.

**Out of the MVP** (later phases): turn-by-turn navigation with voice, lanes and speed limits, motorcycle and twisty roads, track recording, WebDAV sync, Takeout, Android Auto.

## Starting state (verified)

| Piece | State | Where |
| --- | --- | --- |
| Compose viewer + MapLibre + PMTiles | Works on the Pixel 8 (startup ≈ 480 ms, Madrid offline, user's assessment: "it moves well") | `:app`, `docs/phase1/device-test/` |
| `geo:`/Google/short link links | Tested on the Pixel 8; Apple/Waze only in tests | `:core-geo`, `:app` |
| Regions (catalog, resumable download, SHA-256, atomic) | JVM logic with 14 tests; **no UI or service** | `:core-regions` |
| Places, lists, GPX/KML, backup | JVM logic with tests; **no Android driver or UI** | `:core-data` |
| Search and routes (CoMaps core) | Compiles and is in the debug APK; **has never returned results** (R12) | `:native-comaps` |
| Sprites and glyphs | **Not bundled**: the map has no labels or icons | `scripts/fetch-map-assets.sh` |

## Milestones

| Milestone | Content | Depends on | Requires the Pixel 8 |
| --- | --- | --- | --- |
| **M0. Complete map** | Bundle sprites and glyphs; clean up the pin when opening a short link | — | only to verify |
| **M1. Regions** | Catalog (script that joins `countries.txt` and PMTiles), foreground download service, "Maps" screen, INTERNET permission only for downloading, `NetworkPolicy` | M0 | verify |
| **M2. Search** | `:native-comaps` in the production APK, deferred startup over the installed regions, bar with debounce and list, result → camera + pin + detail card | M1 | **yes: R12 (latency)** |
| **M3. Places** | Android SQLite driver, save from the detail card, lists screen, import/export GPX/KML | M2 (detail card) | verify |
| **M4. Route (preview)** | Choose origin/destination, car/foot/bike profile, draw the route with distance and time | M2 | **yes: R12 (long route)** |
| **M5. MVP polish** | Complete es/en strings, privacy settings ("no network" mode), "About" with attribution and licenses, empty and error states | all | verify |

## Split (max. 2 subagents at a time)

- **Agent A, `feat/mvp-regions` (M0 + M1):** sprites and glyphs, regions screen and service, catalog, network permission.
- **Agent B, `feat/mvp-search-places` (M2 + M3, without route):** core in production, search and detail card, SQLite driver and lists.
- **M4 and M5** I do myself (or an agent) when one of the two finishes.

To avoid collisions: A touches `app/**/regions/**` and the service; B touches `app/**/search/**` and `app/**/places/**`; shared files (`MainActivity`, bottom panel, manifest) are edited minimally and in separate functions. I integrate into local `master` with tests.

## Gates involving the phone (not used without explicit permission)

1. **R12 search:** ≤ 100 ms per keystroke (the spike gave 631 ms with the full engine).
2. **R12 long route:** relaxed on 2026-10-08 to tiers (≤ 2 s up to 150 km, ≤ 10 s up to 350 km, ≤ 20 s up to 650 km); see `docs/mapas-02-requisitos.md` RF-04. Measured: 12 to 15 s Madrid to Barcelona.
3. Fluidity with labels and icons, and memory consumption with Spain loaded.

**If R12 does not improve** with the decoupled core, we decide with data: relax the threshold for long routes, limit the loaded regions or evaluate Valhalla. Until then M2/M4 move forward with the engine as is.

## MVP "done" criteria

- `./gradlew lint test assembleDebug` green, with tests per milestone.
- Tested on the Pixel 8 with permission: startup, download of a small real region, search, saving a place, viewing a route.
- No proprietary dependencies; `LICENSES.md` up to date; licenses inherited from CoMaps excluded (R11).
- Estimate: 6-10 weeks part-time (rough, without measuring real velocity).
