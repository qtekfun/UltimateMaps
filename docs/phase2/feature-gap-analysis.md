# Feature gap analysis: Google Maps and Apple Maps vs UltimateMaps

Status: analysis only, 2026-10-07. No code changed. All effort numbers are rough **estimates** in person-days (one developer who knows the codebase, includes tests and strings, excludes device-testing queue time). Claims about competitors marked "not verified" come from general knowledge, not from a page read in this session.

## 0. Method and sources

Read in the repo: `CLAUDE.md`, `README.md`, `CHANGELOG.md`, `docs/mvp-plan.md`, `docs/phase2/speed-cameras-analysis.md`.

Competitor pages fetched (summaries came through a fetch tool, so wording is paraphrased):

- Google Maps overview: https://www.google.com/maps/about/ (Gemini "Ask Maps", immersive navigation, route options such as toll-free, community insights, Lists with sharing).
- Google offline maps limits: https://support.google.com/maps/answer/6291838 (offline: driving only; transit, bicycling and walking directions, traffic and alternative routes are unavailable offline; the full route must lie inside the downloaded area; not available in some countries).
- Google Maps news hub: https://blog.google/products/maps/ (only a hub page; no dated feature list obtained).
- Apple Maps: https://www.apple.com/ios/maps/ (traffic and speed limits, lane guidance, EV routing with charging stops, cycling with elevation, transit, Look Around, Flyover, 3D city detail, Guides, filtered search, place cards, favourites/frequent places, cross-device sync, CarPlay, Watch haptics, no sign-in, on-device personalization).
- Apple iOS 26 page (https://www.apple.com/ios/ios-26/) returned 404; iOS 26 Maps items (Preferred Routes, Visited Places, offline maps, Wallet) are **not verified**.

Everything else about Google or Apple (Live View, Timeline on device, Android Auto details, crowd-sourced incidents, parking save, widgets) is **not verified** here.

## 1. What UltimateMaps already has (do not re-propose)

Offline vector map (MapLibre + PMTiles), region downloads, offline search (CoMaps core), route preview car/foot/bike with avoid options and up to 5 stops, saved places and lists, GPX/KML import/export and backup, link opening (Google/Apple/Waze/geo), optional fuel prices (Spain), navigation screen (banner, lanes, speed limit warning, ETA, night/glove mode, simulator), offline voice (system TTS, EN/ES), 3D navigation camera and optional 3D buildings (not yet seen on a device), settings. Speed cameras are analysed in `docs/phase2/speed-cameras-analysis.md` (not implemented).

Known gaps from the README: motorcycle profile and curvy routes, track recording, Nextcloud/WebDAV sync, Takeout import, Android Auto. Open risks: search latency (0.3 to 4 s), long routes returning `ROUTE_NOT_FOUND`.

## 2. Priority legend

- **now**: do in the next phase; high value, feasible, low risk.
- **next**: after the current navigation work is verified on a device.
- **later**: valuable but large, speculative or data-dependent.
- **never**: conflicts with privacy or non-negotiables (or not possible offline).

Core dependency column: **CoMaps** = needs C++ core work or data-generation changes; **MapLibre** = rendering/style only; **App** = Kotlin/Compose only; **Data** = data pipeline in `UltimateMaps-data`.

## 3. Navigation

| # | Feature | Who has it | Offline/private? | Data and licence | Effort (est.) | Dependencies | Priority and reason |
|---|---|---|---|---|---|---|---|
| N1 | Fix long-route failures (`ROUTE_NOT_FOUND`) and route latency | both | yes | none | 8-20 (uncertain) | CoMaps, Data (cross-region routing files) | **now**: a navigation app that cannot route Madrid-Barcelona is not usable; blocks everything else |
| N2 | Alternative routes shown side by side | both (Google: not offline, per its support page) | yes, if the engine can return several routes | OSM | 5-10 | CoMaps (the stock core returns one route; second route needs penalised re-route or a different engine; not verified) | **next**: frequent expectation, engine work unknown |
| N3 | Rich avoid options (highways, tolls, ferries, unpaved done; add "avoid stairs", "avoid steep") | both (not verified for steep) | yes | OSM | 2-4 for stairs/steep on foot/bike, if the core exposes them | CoMaps | **later**: low demand |
| N4 | Lane guidance polish (beyond banner) and junction view | both | yes | OSM `turn:lanes`, `lanes` | 4-8 for highway junction pictograms | App, CoMaps lane data | **next**: lanes exist; verify on highways first |
| N5 | Speed limits beyond cars (and on map while driving) | both | yes | OSM `maxspeed` | 2-4 | CoMaps already supplies for car | **next**: extend and verify; mostly UI |
| N6 | Speed-camera warnings (fixed only) | Apple/Google not verified; Waze-like apps yes | yes | OSM `highway=speed_camera` (ODbL), DGT NAP (CC-BY) | 3-6 following `speed-cameras-analysis.md` | CoMaps `speedcams` section, Data | **next**: setting off by default with an acknowledgement dialog; no mobile radars |
| N7 | EV routing: range-aware routing and charging stops | Apple yes (page above), Google yes (not verified) | partly: charger locations offline yes; live availability/price no | OSM `amenity=charging_station` (plugs, capacity, operator), ODbL; Spanish open data possible (not verified) | 10-20 for charger layer + filter by plug (3-5) ; 25-40 for true range-aware routing | CoMaps (consumption model not present; a custom planner over route + chargers is possible in the app) | **later**: show chargers and plug filter **next** (cheap); range planning later |
| N8 | Motorcycle profile and curvy/scenic routes | Google has two-wheeler in some countries (not verified); Calimoto-like apps | yes | OSM `curvature` computed in Data pipeline | 8-15 | CoMaps (vehicle model) or custom penalty; Data | **next**: already in roadmap, clear audience |
| N9 | Bike: elevation profile and bike-specific routing (cycleways preferences) | Apple yes (page above) | yes with DEM | SRTM/Copernicus DEM (public domain / free licence terms, check) | 5-8 profile only | App + elevation data in Data | **next**: profile is cheap if contours/DEM are shipped (see M3) |
| N10 | Public transit directions | both; Google offline: unavailable | partly: static GTFS works offline, real-time does not | GTFS feeds with open licences per operator; Spain NAP (not verified) | 40-80+ (a RAPTOR planner, feed ingestion per city) | not in CoMaps (it has limited transit display only); a new engine (e.g. an on-device RAPTOR) | **later**: biggest effort; only schedule-based, city by city |
| N11 | Multi-stop (reorder, optimise order) | both | yes | none | 2-4 for reorder/drag, 4-6 for optimisation (small TSP) | App | **now**: stops exist; reordering UI is quick |
| N12 | ETA sharing | both | not private by default: needs a server | none | n/a | would need a relay server | **never** (as live sharing via a server). Alternative: share a static message "arriving about 18:40" via the Android share sheet: 1 day, **quick win** |
| N13 | Android Auto | Google; Apple has CarPlay | yes (Car App Library) | Jetpack `androidx.car.app` (Apache-2.0, not GMS; the host app on the phone is Google's; works on head units, not tested here) | 20-35 | App (navigation template, map surface rendering with MapLibre onto the car surface is hard); not verified: F-Droid/Android Auto sideload constraints (needs "unknown sources" developer setting) | **later**: high value for drivers, but large and needs a car for testing |
| N14 | Offline voice, better | both | yes | system TTS or Piper/Sherpa models (MIT/Apache; check model licences) | 5-8 for bundled neural voice | App | **later**: current TTS works; installing an engine is the friction |
| N15 | Hands-free voice assistant / Gemini / Siri | both | no (cloud) | n/a | n/a | n/a | **never** for cloud assistants; basic offline voice commands (Vosk, Apache-2.0) possible later (10-15) |
| N16 | Re-route on deviation, lane-change timing, background resilience | both | yes | none | in progress | App | **now** (verify on real long trips; already listed as untested) |
| N17 | Trip summary (distance, time, avg speed) after navigation | Google not verified | yes | none | 2 | App | **quick win** |

## 4. Search

| # | Feature | Who | Offline/private? | Data and licence | Effort (est.) | Dependencies | Priority and reason |
|---|---|---|---|---|---|---|---|
| S1 | Search latency to 100 ms | both | yes | none | 5-15 | CoMaps | **now**: stated requirement, currently 0.3 to 4 s |
| S2 | Category browse ("pharmacies near me", nearby chips: fuel, food, ATM, hospital) | both | yes, uses the local index | OSM tags | 3-5 | CoMaps already has category search (not verified in our build); App UI | **now**: biggest daily-use gain; location stays on device |
| S3 | Opening hours: "open now" badge and filter | both | yes | OSM `opening_hours` (ODbL); parse with an OSM opening_hours library (e.g. a Kotlin/Java port; check licence) | 3-5 | CoMaps exposes raw field; App parser | **next** (badge **now** if the core returns the raw string) |
| S4 | Ratings and reviews | both | not from local data; crowd reviews need a server | OSM has no ratings; Wikidata/Wikipedia link is possible | n/a | n/a | **never** for ratings/reviews (needs accounts/servers). Offer instead: phone, website, wheelchair and cuisine tags: 2-3 days |
| S5 | Place details enriched (phone, website, wheelchair, payment methods) | both | yes | OSM tags | 2-3 | CoMaps place info | **quick win** (if fields are exposed) |
| S6 | Indoor maps (malls, airports) | both (Apple airports/malls, Google; not verified) | yes if data is bundled | OSM indoor tags (`indoor=*`, `level`), ODbL | 15-30 | MapLibre style with a level filter; CoMaps does not route indoors; Data size | **later**: sparse OSM coverage, speculative |
| S7 | Photos on place cards | both | not offline (server) | Wikimedia Commons possible but network use | n/a | `NetworkPolicy` | **never** by default (fetching reveals interest); opt-in later is possible |
| S8 | Curated guides | Apple | offline only if bundled | Wikivoyage (CC BY-SA, share-alike, check compatibility) | 15-25 | Data | **later**: content burden |
| S9 | Search history and recents on device | both | yes | none | 1-2 | App | **quick win**, with a "clear" button and off switch |
| S10 | Natural language / AI search | Google (Gemini) | no (cloud) | n/a | n/a | n/a | **never** (cloud). Fuzzy typo tolerance and multilingual names may be tuned instead (3-5) |
| S11 | Search by coordinates / plus codes / what3words-like | Google (plus codes) | yes | Open Location Code (Apache-2.0) | 1-2 | App | **quick win** (coordinates and Plus Codes) |

## 5. Map layers and rendering

| # | Feature | Who | Offline/private? | Data and licence | Effort (est.) | Dependencies | Priority and reason |
|---|---|---|---|---|---|---|---|
| M1 | 3D buildings, tilt | both | yes | OSM | done (nav) | MapLibre | done; verify on device and extend to the browse view (2) |
| M2 | Satellite/aerial imagery | both | offline only if downloaded; imagery is large (GBs) | Free options: Sentinel-2 cloudless (EOX, CC BY-NC-SA for the 2016-2018 mosaic, **not compatible with some uses**, check), national orthophotos (Spain PNOA, CC BY 4.0, large, not verified) | 10-20 (pipeline) | MapLibre raster source, Data hosting cost | **later**: huge size; opt-in per region; licence must be checked first |
| M3 | Terrain: hillshade and contour lines | both partly (Apple elevation in 3D) | yes | Copernicus DEM / SRTM; OSM contour tiles via OpenTopoMap-like generation (contours in OSM-derived vector tiles: `contour` layer from DEM) | 8-12 for hillshade + contours in the PMTiles pipeline | MapLibre (hillshade/raster-dem and contour layers), Data | **next**: top feature for hikers; also enables elevation profiles (N9) |
| M4 | Cycling and hiking overlays (route relations, MTB, cycleways highlighted, `sac_scale`) | Google bike layer, Apple cycling (not verified for hiking) | yes | OSM route relations (`route=bicycle/hiking`), ODbL | 5-10 | Data (tiles layer), MapLibre style | **next**: cheap and distinctive |
| M5 | Traffic layer | both | needs live data | n/a | n/a | n/a | **never** (project decision: no traffic). Substitute: typical-hours warnings are not offline-possible; skip |
| M6 | Transit lines layer (static) | both | yes | OSM `route=bus/train/subway` or GTFS | 5-8 | Data, MapLibre | **later**: pairs with N10 |
| M7 | Street-level imagery (Look Around, Street View) | both | no (server, imagery proprietary) | Mapillary is cloud; KartaView ODbL-ish (not verified) | n/a | n/a | **never** (no proprietary service, network). Possible far future: opt-in Mapillary/Panoramax viewer: not planned |
| M8 | Map style variants (high contrast, outdoor, driving) | both (not verified) | yes | none | 3-5 | MapLibre style | **next** |
| M9 | Compass, scale bar, rotate-to-north, zoom buttons | both | yes | none | 1-2 | MapLibre/App | **quick win** (check which exist) |
| M10 | Place labels in local and preferred language | both | yes | OSM `name:*` | 2-3 | Data/style | **next** |

## 6. Personal features

| # | Feature | Who | Offline/private? | Data/licence | Effort (est.) | Dependencies | Priority and reason |
|---|---|---|---|---|---|---|---|
| P1 | Saved places and lists (exists) + icons/colours/notes/emoji, sort by distance | Google Lists | yes | none | 2-3 | App | **quick win** (emoji/colour/notes) |
| P2 | Home/Work shortcuts and one-tap "go home" | both | yes, stored locally | none | 1-2 | App | **quick win** |
| P3 | Parking location: save where the car was left, one-tap return | both | yes | none | 1-2 for manual "I parked here"; 4-6 for automatic via Bluetooth disconnect/activity recognition (activity recognition is GMS, so use Bluetooth `ACL_DISCONNECTED` only) | App | **now** (manual, quick win); automatic **later** |
| P4 | On-device history and "recent" places | both | yes | none | 2 (also S9) | App | **quick win** |
| P5 | Timeline / visited places | Google Timeline (now on device, not verified); Apple Visited Places (not verified) | yes, if strictly local, encrypted | none | 15-25 (background location service, battery, UI, retention controls) | App; conflicts with "locations never written to logs by default" unless opt-in | **later**, strictly opt-in, local only, with auto-expiry; sensitive. Track recording (below) is the safer first step |
| P6 | Track recording (GPX) | not core in Google/Apple; Strava-like | yes | GPX | 8-12 (foreground service, simplification, pause, export, stats) | App | **next**: already on README gaps; shares code with trip log |
| P7 | Share location (as `geo:`/link/text/QR; no server) | both | yes (no server involved) | none | 1-2 (share a `geo:` URI and an OSM link; check that no network request is made) | App | **quick win** (verify the place card share does exactly this) |
| P8 | Live location sharing | both | needs server | n/a | n/a | n/a | **never** (server); alternative: manual snapshots |
| P9 | Home-screen widgets (e.g. "go home", saved place shortcut, current speed) | Google (not verified) | yes | none | 4-6 (Glance, Apache-2.0, no GMS; check dependency) | App | **later**: modest value |
| P10 | App shortcuts (long-press launcher: Home, Work, Search) | Android | yes | none | 1 | App | **quick win** |
| P11 | Wearables: Wear OS companion, haptic turn cues | Apple Watch (page above), Wear OS (not verified) | partly; Wear OS data layer is GMS (Wearable Data Layer API), conflicts with the `foss` flavor | n/a | 20-40 | Would need a non-GMS bridge | **never** for the foss flavor (relies on GMS). Android notification actions mirrored to a watch work for free: nothing to build |
| P12 | Sync across devices | Apple iCloud, Google account | with user-owned WebDAV/Nextcloud only | WebDAV (RFC 4918) | 8-12 (see D3) | App, Keystore | **next** |
| P13 | Collaborative lists | Google Lists (page above) | needs a server | n/a | n/a | n/a | **never** server-based; file sharing of a list (GPX/KML) already covers the offline case |
| P14 | Calendar integration (suggest travel time to events) | Google (not verified) | would read calendar | n/a | n/a | n/a | **never**: new sensitive permission with little gain |

## 7. Safety

| # | Feature | Who | Offline/private? | Data/licence | Effort (est.) | Dependencies | Priority and reason |
|---|---|---|---|---|---|---|---|
| X1 | Fixed speed-camera warnings | Waze-like; Google/Apple vary by country (not verified) | yes | OSM, DGT CC-BY (analysis file) | 3-6 | CoMaps `speedcams`, Data | **next** (see N6) |
| X2 | Live incidents/crashes/police reports | Google, Waze, Apple | needs crowd + server | n/a | n/a | n/a | **never** (server, traffic). Legal risk for police-control reports in Spain (see speed-camera analysis) |
| X3 | Emergency info: show coordinates, nearest hospital, 112 button, "share my position by SMS" | Apple (Emergency SOS, not verified), Google (not verified) | yes | OSM `amenity=hospital`, `emergency=*` (defibrillators `emergency=defibrillator`) | 3-5 | CoMaps search categories, App | **now** (coordinates + share via SMS composer: 1-2 days, quick win) |
| X4 | Road hazard warnings from static data (sharp curves, level crossings, school zones) | not verified | yes | OSM (`railway=level_crossing`, `hazard=*`) | 3-5 | Data, voice | **later** |
| X5 | Drowsy/long-drive break suggestion | Android Auto not verified | yes | none | 1-2 | App | **quick win-ish**, low value |
| X6 | Emergency / offline map of shelters, water points | outdoor apps | yes | OSM `amenity=drinking_water`, `emergency=assembly_point` | 2-3 | App categories | **next** (as search category) |

## 8. Data

| # | Feature | Who | Offline/private? | Data/licence | Effort (est.) | Dependencies | Priority and reason |
|---|---|---|---|---|---|---|---|
| D1 | GPX/KML import-export (exists) + GPX tracks/routes displayed as polylines, KMZ | Google import KML | yes | formats | 2-4 for KMZ and track display | App | **quick win** (tracks display) |
| D2 | Google Takeout import (Saved places, starred places, labeled places, Timeline) | n/a (migration) | yes | Takeout JSON/CSV, user's own data | 4-6 (parsers exist as a plan; format drifts, **not verified** current layout) | App | **now**: key switching feature; no network; README lists it as missing |
| D3 | Backup and sync via user-owned WebDAV/Nextcloud | Apple iCloud | yes; user holds credentials in Android Keystore | WebDAV (client lib licence to check; OkHttp Apache-2.0 would suffice) | 8-12 (conflict policy: last-write-wins + tombstones; encryption at rest optional) | App, `NetworkPolicy` entry only for the user-entered host | **next** |
| D4 | Encrypted local backup file with schedule | n/a | yes | AES-GCM, Android Keystore | 3-4 | App | **next** |
| D5 | Region updates (diff updates) | Google auto-updates offline | yes with the catalog | bsdiff already inherited (licence flagged in CLAUDE.md) | 5-10 | Data | **later**: full re-download works |
| D6 | More countries in the catalogue | both | yes | Geofabrik extracts (ODbL) | per country 1-3 once the pipeline is stable | Data | **now/next**: Spain only today; the biggest adoption blocker |
| D7 | Import from Apple Maps / Waze / OsmAnd / Organic Maps (GPX/KML/`.kmz`/OsmAnd favourites) | n/a | yes | formats | 2-3 each | App | **quick win** for OsmAnd/Organic Maps (KML/GPX already) |
| D8 | Export/print route as PDF or turn list | Google (not verified) | yes | none | 2-3 | App | **later** |

## 9. Quick wins (<= 2 days each, estimates)

1. Manual "parked here" marker with return button (P3), 1-2 d.
2. Home and Work shortcuts (P2), 1-2 d.
3. Share location as `geo:` URI + OSM link, and ETA as plain text via the share sheet (P7, N12 alternative), 1-2 d.
4. On-device recent searches with clear/off switch (S9/P4), 1-2 d.
5. Emergency screen: current coordinates, 112 button, share by SMS (X3), 1-2 d.
6. App shortcuts on the launcher (P10), 1 d.
7. Plus Codes / coordinate search (S11), 1-2 d.
8. List customisation: emoji, colour, notes (P1), 2 d.
9. Trip summary after navigation (N17), 2 d.
10. Place card extras from OSM tags: phone, website, wheelchair, opening hours raw text (S5), 2 d, if the core already returns them.
11. Compass/scale/north-up controls audit (M9), 1-2 d.
12. Show GPX tracks as polylines (D1 part), 2 d.

## 10. Recommended order

1. **Now** (usability blockers and cheap daily wins): N1 and S1 (core latency and long routes, R12), N16 (verify navigation on long trips), S2 category browse, D6 more regions, D2 Takeout import, N11 stop reordering, X3, P3 manual, quick wins above.
2. **Next**: speed cameras (N6/X1), motorcycle/curvy (N8), terrain and contours (M3) with elevation profile (N9), cycling/hiking overlays (M4), track recording (P6), WebDAV sync and encrypted backup (D3/D4), opening hours (S3), EV charger layer with plug filter (N7 part), alternative routes (N2), lane polish (N4), style variants (M8), label language (M10).
3. **Later**: Android Auto (N13), transit (N10), indoor maps (S6), satellite (M2), Timeline (P5, opt-in only), neural offline voice (N14), widgets (P9), guides (S8), range-aware EV planning, diff updates (D5).
4. **Never** (privacy/non-negotiables): live traffic (M5), live incidents and police reports (X2), live location sharing and ETA via server (P8, N12), ratings/reviews and photos from servers (S4, S7), cloud assistants and AI search (N15, S10), Street View-style imagery (M7), GMS-dependent Wear OS companion in the foss flavor (P11), calendar integration (P14), collaborative lists via servers (P13).

## 11. Cross-cutting risks and open questions

- Almost every "next" item that touches routing, categories or opening hours depends on what the CoMaps core already exposes through our JNI wrapper; a one-day spike per item should confirm before committing the estimate.
- Data-pipeline items (contours, overlays, speed cameras, curvature, EV chargers, more countries) are in `UltimateMaps-data`, which is outside this repo; estimates assume the pipeline can add layers to the PMTiles and MWM builds.
- Any new data licence (Sentinel mosaic, Wikivoyage, national open data) must be recorded in `LICENSES.md` before shipping; share-alike terms (CC BY-SA, ODbL derived databases) need a compatibility check against GPLv3.
- Location-history features (P5) must stay opt-in and local, consistent with the privacy rules in `CLAUDE.md`.
- Android Auto and widgets add new libraries: check each for GMS dependency before use in the `foss` flavor.
