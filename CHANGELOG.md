# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- A **Mute** button on the navigation screen (the same switch as Settings, Navigation, Voice guidance): it silences the voice at once and shows "Unmute" while muted.

## [0.1.0-rc.5] - pending

### Added

- Navigation 3D view: tilted "course up" camera with the marker in the lower third, zoom by speed and by the next maneuver, a 2D/3D toggle (also in Settings, Navigation), a route overview button, a heading arrow and, optionally, 3D buildings. Not seen on a device yet; see `docs/phase2/nav-3d.md`.

### Changed

- The route panel has a clear hierarchy: a header with a close icon, one From/To card, an options row that expands, and a single primary action (Start) with Simulate as a secondary one.
- The Settings gear on the map now sits on the same chip as the other map buttons, so it no longer disappears over the map.

## [0.1.0-rc.4] - pending

First release candidate (`rc.1` to `rc.3` only existed as test pre-releases): an MVP under development (see `docs/mvp-plan.md`), not a finished app. Spain only.

### Added

- Offline vector map (MapLibre Native + PMTiles) with light and dark themes, labels and icons, and an always-visible OpenStreetMap attribution.
- Region downloads from "Maps": hierarchical list, resumable downloads verified with SHA-256, pause, resume, delete and update, foreground service and an offline mode. The default catalog lives in `UltimateMaps-data`.
- Offline search for places and addresses with the CoMaps core, with a place card (save, route, share).
- Route preview by car, on foot or by bike, with options to avoid motorways, tolls, ferries and unpaved roads, and intermediate stops (up to 5).
- Saved places and lists, with GPX and KML import and export and a full backup.
- Opening links from Google Maps, Apple Maps, Waze and `geo:`; short links make no network request.
- Location without Google Play Services.
- **Settings screen** (gear on the map): privacy (offline mode, catalog, list of possible connections), petrol stations and navigation.
- **Petrol stations and prices** (optional, off by default): downloads only the fuels you tick (LPG, petrols, diesels, CNG…), draws the price of the chosen fuel over each station, and opens a card on tap with **Go** and **Add stop**. Prices are published by the Spanish Ministry for the Ecological Transition and are unofficial; the location never leaves the device.
- **Navigation screen** (seen working on one device; long trips untested): *Start* and *Simulate* buttons in the route panel; banner with the turn, street and lanes, speed limit with a warning, arrival time, night mode and glove mode, screen kept on, resume after an unexpected close.
- **Voice guidance** (not yet tested on a device): English and Spanish phrases, km/mi, a prioritized queue, audio-focus ducking, and a guide to install a free text-to-speech engine without GMS; voice, volume, units, language and "avoid by default" options in Settings.
- Search box in the maps list and a bottom sheet that is easier to drag.
- The search and routing engine runs in its own process: if it fails, the app keeps running.
- English and Spanish strings.

### Fixed since rc.3

- The status-bar icons (clock, battery, signal) were unreadable on the dark navigation banner; they are now white while navigating.
- Imported places without a name are now called "(unnamed)" instead of a Spanish placeholder.

### Known limitations

- **Speed:** search takes 0.3 to 4 s per query on a Pixel 8 with 7 regions (the target was 0.1 s). Long routes (for example Madrid–Barcelona) return "route not found" with the 7 regions tested.
- The navigation screen has been seen working on one real device (banner, next maneuver, arrival time, speed); the voice, battery use and long trips have only automated tests so far.
- Lanes have only been seen on urban routes; speed limits exist for car routes only; there is no motorcycle profile.
- With many regions installed, map performance has not been measured.
- Tested on a single device (high-end, with GMS). Not tested: Chinese ROMs, devices without Google, mid-range phones.
- The release APK is not minified.
