# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- Bike routes can prefer cycle infrastructure: Off, Prefer, Strongly prefer or Only cycle infrastructure. It is a row in the route panel for the bike profile and a default in Settings, Navigation. Off keeps the standard routing (which already favours tagged cycleways and lanes); Prefer and Strongly prefer lower the weight of other roads; Only excludes them and, when it finds nothing, says so and suggests Prefer. It relies on OpenStreetMap tags (a cycle lane, a cycle track and "bicycle=yes" are not told apart) and needs a patch to the CoMaps core, applied by `scripts/comaps-prepare.sh`. Route quality has not been judged on a device.

### Fixed

- Navigation location: on Android 12 and later the app now asks for high-accuracy updates explicitly. It used the legacy request, which for the fused provider is a low-power one, a likely cause of the repeated "no GPS signal, estimated position" while driving (not yet confirmed on a drive).
- **Browse by category.** A row of chips under the search field (pharmacy, supermarket, restaurant, cafe, fuel, ATM, hospital, parking, lodging, toilets). Tapping one lists the nearest places of that category with their straight-line distance and shows them as pins on the map; tapping it again, or typing, clears it. It uses the search core's pure category mode, offline. The core ranks inside a 20 km area, so the list is sorted by distance within what the core returned. Not tried on a device.
- **Alternative routes (by one extra restriction).** In the route panel, "Show alternatives" calculates up to two more routes that avoid one more road type (motorways and tolls by car; unpaved roads and ferries on foot or by bike), draws them lighter and lists them with their time and the difference to your route. Tapping one selects it and "Start" and "Simulate" use it. These are not "next best" routes: the core has no alternative-route search (see `docs/phase2/categories-alternatives.md`). Each one is a full route calculation, so it only runs on request. Not tried on a device.
- **Search by coordinates and Plus Codes**, offline: type `40.4168, -3.7038`, `40.4168N 3.7038W`, `40°26'46"N 3°42'14"W` or a Plus Code such as `8FVC2222+22` (a short code such as `9QCJ+2VX` is completed near the map centre) and the first result jumps to that point. These searches are never stored in the recent searches.
- Every place card shows its **Plus Code** (computed on the device, Open Location Code, Apache-2.0 reference algorithm re-implemented and checked against the published test vectors).
- Place card extras from OpenStreetMap tags: phone (tap opens the dialer, nothing is dialled and no permission is needed), website (opens the browser; only `http`/`https`), wheelchair access and the raw opening hours text with an "open now" line from a small parser of the common simple patterns (`Mo-Fr 08:00-20:00; Sa 09:00-14:00; Su off`, `24/7`, spans past midnight; anything else says "unknown"). **The map core does not return these tags yet**, so the rows stay hidden until the native wrapper is extended (see `docs/decisions.md`).
- Share a place as its name, a `geo:` link and an OpenStreetMap link; share the trip ETA as plain text ("I will arrive at about 18:40 (12 km, 15 min to go).") from the navigation screen. Both go through the system share sheet: no server, no live tracking, the ETA text holds no place and no position.
- A scale bar on the map (metric, 1-2-5 steps) and the compass now also appears when the map is only tilted, so it can bring the view back to flat; near 360 degrees it no longer shows as if the map were rotated. Not seen on a device yet.
- **Add a stop while navigating.** Tapping a petrol station during navigation now offers "Add stop": the trip is planned again from where you are through the stops still ahead and the new one (placed where it adds the least detour) to the same destination, and the navigation, voice and camera carry on without restarting. At most 5 stops ahead; if no route is found the trip goes on as before and says so. Verified with simulated trips and a fake route provider only; not tried on a device.
- **Track recording** (optional, off by default, Settings, Track recording). Start and Stop recording in the Tracks list saves your own trip as a track on the device only: a sparse sample of your fixes (denser when slow, sparser when fast), paused by itself after a long stop, written to a private journal as it goes so a crash loses almost nothing. The track works with the existing GPX export and the "tracks on the map" layer, and recorded tracks can be deleted with one tap (one by one, or all of them in Settings). It records while the app is open or while you navigate; there is no background service for free recording. Verified with simulated fixes and a fake clock only; not tried on a device.
- A **visual alert** for cameras and incidents ahead: a chip with the camera icon, what is ahead, the distance counting down and the posted limit when known. It shows on the map when driving without a navigation and under the maneuver banner during navigation, also when the voice is muted, with a screen-reader description and a bigger size in glove mode. Checklist for the phone: `docs/phase2/camera-alerts-checklist.md`.
- Speed-camera alerts never had data to work with unless the catalog the app had cached already listed the camera file, and the catalog was refreshed only by opening "Maps": turning a camera switch on, "Update now" and the first foreground now refresh the catalog first (same server and rules as the map downloads; offline mode blocks it).
- A navigation resumed by the foreground service after the system killed the process, or a camera switch turned on in the middle of a trip, did not get route-based alerts; the alerts now follow the navigation controller itself.
- Camera and incident voice alerts no longer discard or interrupt a pending turn instruction (new `ADVISORY` voice priority), and are not spoken while a maneuver is about to be announced (the chip still shows).

## [0.1.0-rc.6] - pending

### Added

- The route stays visible over the lock screen while navigating, without unlocking the phone (it does not unlock anything: other apps and actions still need the PIN).
- A **Mute** button on the navigation screen (the same switch as Settings, Navigation, Voice guidance): it silences the voice at once and shows "Unmute" while muted.
- Parked-car marker: "Park here" marks your current position on the map (a distinct marker) and the same chip routes back to it; it can be marked again or cleared. Stored on the device only.
- Home and Work shortcuts: set from a place card ("Set as Home" / "Set as Work"), one tap routes there. Home and Work are included in full backups; the parked car is not.
- Recent searches on the device, listed under the search field with a clear button, and a Settings switch to turn the history off (on by default; turning it off also deletes it). Nothing leaves the device.
- Saved lists can have an emoji, a color and notes ("Customize" in an open list). They were already stored in the database and in backups; GPX/KML exports are unchanged.
- Launcher app shortcuts: Search, Saved places, Downloaded maps and Emergency.
- Emergency screen: current coordinates (shown on screen only, never logged), a button that opens the dialer with 112 (`ACTION_DIAL`, no call permission) and a button that shares the coordinates as text with a `geo:` link through the system share sheet (no SMS permission). Not seen on a device yet.
- **Speed cameras and traffic (all optional, off by default, each with its own switch in Settings).** Fixed cameras and average-speed sections; stretches of road where the DGT says mobile radars may operate (shown as rough dashed stretches, never as points); live traffic incidents; and stopped vehicles with a connected V16 beacon. On the map and as a spoken alert ahead (route-based while navigating, free-driving while the app is on screen), with the posted limit when known. Camera data comes from a small file in the map data release (DGT, CC BY, and OpenStreetMap, ODbL) and the app works without it; traffic data is the DGT national feed, downloaded only when you turn it on, with your position never sent. Verified on the JVM and with downloaded real data only; not seen or heard on a device. See `docs/phase2/cameras-implementation.md` and `docs/phase2/cameras-data.md`.
- The catalog may carry an optional `cameras` entry; `scripts/build-cameras.py` builds the file and `scripts/gen-region-catalog.py --cameras-file` lists it.

### Changed

- The places database moves to schema version 2 (new tables for Home/Work/parked car and recent searches, created through `PRAGMA user_version`; existing data is kept).
- Google Takeout import (offline): in Saved places, "Import" now also accepts a Takeout ZIP or one of its CSV / GeoJSON files and creates one list per file. Places without coordinates are skipped and counted in the result summary. The Takeout layouts are assumptions, not verified against a current real export; see `docs/decisions.md`.
- Imported GPX tracks and routes can be drawn on the map as coloured lines (a MapLibre layer under the route line): Saved places, lists overview, "Tracks on the map", with Show/Hide and "Zoom to track" per track. Works offline. Not seen on a device yet.

### Fixed

- Tapping a petrol station while navigating now shows its card above the navigation screen (it used to stay hidden behind it until the trip was stopped). During a trip the card offers Go (ends the trip and previews the route) and Save; Add stop is not offered because it edits a route preview, not the trip in progress.

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
