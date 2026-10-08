# 02 · Requirements

Thresholds marked "target" are proposals that the spike must validate or correct.

## v1 scope

**In:** offline map, offline search, offline routing and navigation (car, motorcycle, bike, on foot), motorcycle (avoid motorways/tolls, twisty roads, glove mode, recording), places and lists, import/export, optional Nextcloud/WebDAV sync, opening map links, privacy settings.

**Out (v1):** road traffic data in any form (the real-time public transport of F7 is not road "traffic": it is optional and comes after v1), Google/Waze/Apple as data providers, iOS, reviews and photos of places, public transport, accounts and any own backend. Android Auto, in a later phase.

## Functional requirements

| ID | Requirement | Acceptance criterion |
| --- | --- | --- |
| RF-01 | Offline vector map with rotation, tilt, day/night mode and optional 3D buildings, Apple Maps style | Spike style checklist with ≥ 8 of 10 points |
| RF-02 | Region manager: hierarchical list of the world, sizes, resumable download, hash verification, update and deletion; internal storage or card | Download, interrupt, resume and verify a region without intervention |
| RF-03 | Offline search by name, address and POI category, with results while typing and typo tolerance | First results in ≤ 100 ms (target) |
| RF-04 | Offline routing with car, motorcycle, bike and on-foot profiles; avoid motorways, tolls, ferries and unpaved roads; alternatives and intermediate stops | Madrid–Barcelona route in ≤ 2 s (target) |
| RF-05 | Turn-by-turn navigation with voice, rerouting, lane guidance, speed limit and a warning when exceeding it, automatic night mode and route simulation | Full simulated route without guidance errors |
| RF-06 | Motorcycle: own profile, avoid motorways/tolls, twisty routes (configurable sinuosity level), optional always-on screen, glove mode (large touch targets, high contrast) | Real test on a motorcycle with the usual mount |
| RF-07 | Track recording: foreground service, pause/resume, statistics, GPX export and recovery after an unexpected close | A 2 h recording survives a forced close |
| RF-08 | Places and lists: favorites, lists with color, icon and notes, sorting by distance, visible on the map | Create, edit and search within lists |
| RF-09 | Import and export GPX, KML/KMZ and Google Takeout; full backup | Import a real Takeout and a 10,000-point GPX |
| RF-10 | Optional Nextcloud/WebDAV sync: lists and tracks, with conflict handling and credentials in Android Keystore | The app works the same without sync; two devices converge |
| RF-11 | Open links: `geo:`, Google Maps (long and short), Apple Maps and Waze; resolve short links only if the user enables it; search by name if the link carries no coordinates | Test suite with ≥ 30 real links |
| RF-12 | Privacy: no telemetry, "no network" mode, visible list of possible connections, optional online tile source disabled by default | With the "no network" mode, zero outgoing connections |
| RF-13 | Visible OpenStreetMap attribution | Always present on the map or in "About" as per ODbL |
| RF-14 | Android Auto | **Dropped (2026-10-08, owner):** it needs Google's proprietary host app; see `docs/phase2/android-auto.md` |
| RF-15 | Petrol stations and fuel prices: download the fuels ticked in Settings (LPG, petrol 95/98, diesel, CNG…), **draw the price of the chosen fuel over each station on the map**, open a station card on tap (brand, address, opening hours, prices of every configured fuel) and **add the station to the route** (as destination or as a stop) | With the setting on and a fuel chosen, prices show over the stations when zoomed in; tapping one opens its card with "Go" and "Add stop"; with the setting off there is no connection; the location never leaves the device; the data date is shown and it works offline with the last downloaded data |
| RF-16 | Real-time public transport (F7): next departures and alerts for a **Cercanías** station (the metro is out by the user's decision) | Optional and off by default; if it fails, the app carries on unchanged; shows the time of the last update |
| RF-17 | Settings: a screen with privacy (offline mode, region catalog, list of possible connections with the state of each), navigation preferences (voice, units, avoid by default) and **optional data sources**: turn each on or off; for petrol stations, **which fuels are downloaded**, **which one is shown on the map**, update frequency and the source URL; for Cercanías, the station | Each switch is stored and honored without a restart; turning a source on warns what is sent and to whom; with offline mode on, no source connects |

### Map links (RF-11 detail)

- Schemes and domains to register: `geo:`, `https://www.google.com/maps/*`, `https://maps.google.com/*`, `https://maps.app.goo.gl/*`, `https://goo.gl/maps/*`, `https://maps.apple.com/*`, `https://waze.com/ul*`, `https://www.waze.com/*`.
- On Android 12 and later, an app that is not verified for those domains does not open on its own: the user must enable it under "Open by default → Add links". The app must guide them.
- A short link requires a network request to Google to learn the destination. Setting disabled by default, with a notice of what is sent.

## Non-functional requirements

| ID | Requirement | Target |
| --- | --- | --- |
| RNF-01 | Map fluidity | ≥ 60 fps on mid-range (p95 frame time ≤ 16.6 ms during gestures); on 90/120 Hz displays, p95 ≤ 11.1/8.3 ms on high-end |
| RNF-02 | Cold start to interactive map | ≤ 1 s on mid-to-high-end; ≤ 2 s on low-end |
| RNF-03 | Works without Google Play Services | All v1 features, on Chinese ROMs, LineageOS without GApps, GrapheneOS and microG |
| RNF-04 | F-Droid compatible | No proprietary dependencies in the base flavor; no anti-features |
| RNF-05 | Background navigation reliability | Foreground service with type `location`; guidance and detection of battery-saver exclusion on aggressive ROMs |
| RNF-06 | Privacy | Zero telemetry; no remote crash reports (only a local log that can be exported by hand); logs do not store locations by default |
| RNF-07 | Compatibility | `minSdk` 26 (proposed), target SDK = latest stable, arm64-v8a ABI mandatory |
| RNF-08 | Battery | Measure in the spike and set a threshold; GPS at 1 Hz during navigation and no unnecessary wake locks |
| RNF-09 | Size | App without data < 100 MB (target) |
| RNF-10 | Licenses | GPLv3; compatibility of each dependency recorded in `LICENSES.md` |
| RNF-11 | Quality | Unit tests (link parsers, importers, sync), navigation tests with simulated routes, CI and Baseline Profiles |
| RNF-12 | Accessibility and language | System font size, contrast, basic TalkBack; Spanish and English with externalized strings |
