# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- **Public transport for more cities (data side).** The Public transport list in Maps can hold several cities, each downloaded only when you choose it (with its download size); a trip between two cities that have separate timetables now says so. Build configs for Barcelona/Catalonia, Valencia, Sevilla and Bilbao from the Spanish National Access Point ("Powered by MITRAMS"); the data files appear once the weekly data release builds them.
- **Third-party notices in About.** Settings, About now has a collapsed *Third-party notices* section with the `NOTICE` text: the licence texts and copyright lines that the libraries inside the app (the CoMaps core and its third-party code, the Artistic License 2.0 of `libkdtree++`, and others) ask to be shipped with the app.

### Fixed

- **Quick chips (Home, Work, Park, SOS)** stay on one line each with less padding and an ellipsis if a label is too long (the label is now "Park" / "Aparcar"), so the row is even in English, Spanish and with a large font.
- **The bottom sheet is opaque** in the light and dark themes: map labels and the navigation buttons no longer show through the card text.
- **`geo:` links with `?q=text`** now run the offline search for the text (the Search tab opens with the results, like typing it); the old "offline search is not available yet" message is gone, replaced by a neutral note only when no map is installed.
- **Place card from a `geo:`/map link** has the same actions as a search result (Save, Route, Share, Set as Home/Work); without a name it uses the coordinates.
- **Over-the-limit warning** no longer fires at exactly the limit (a simulated 50 km/h on a 50 km/h limit): it needs a whole-km/h speed strictly above limit + tolerance, and clears 2 km/h below it.
- **Settings texts** of Speed cameras and traffic (intro, legal note, consent dialogs, alert note) are shorter; the same facts about sources, mobile zones, no live data and no position sent are kept, in both languages.
- **The navigation notification** uses the Settings distance units (feet and miles when imperial), like the status-bar chip.

## [0.1.0-rc.9] - pending

### Added

- **Step-by-step public-transport trips.** The itinerary card has a *Start* button that follows the trip live: a banner says what to do now (walk to the stop and how long, "Board line 27 towards X at 14:32", "Next stop: Y, get off in 3 stops", "Get off at the next stop", "Change here: line 1 towards Z"), the stops of the current line are listed with the position marked and the scheduled times, and a chip says whether you are on the plan or about N min behind or ahead of it (the schedule is theoretical, there is no real-time data). It warns when a connection is at risk or already missed and offers *Re-plan* (a new itinerary from where you are, only when you press it) when you are off the plan. Underground, where there is no GPS, the progress of a metro, tram or train leg is estimated from the timetable and marked as an estimate. It runs with the screen off in a foreground location service (notification with the next instruction and the plan, resumes after the system kills the app, expires after 3 hours), shows over the lock screen, keeps the screen on, has glove mode and the night theme, and on Android 16 shows a status-bar chip (for example "2 stops", "Get off") with the same switch as the navigation distance chip. Prompts (board now, get ready to get off, get off now, change here, connection at risk or missed, off the plan, arrived) are spoken in Spanish or English through the navigation voice, or sound a short chime, or stay silent: Settings, Navigation, *Transit trip prompts*, which is in the settings backup. The navigation Mute silences them. Not tried on a phone or on a real ride yet; every threshold is a documented design choice (`docs/phase2/transit.md`, `docs/phase2/transit-follow-checklist.md`).
- **Language of place information.** Search results and place cards now come out in your language: the category ("Farmacia", not "Pharmacy"), the region and country of the address ("Comunidad de Madrid, España") and the place name (the Spanish name of a place when it has one, otherwise the local one). The cause was that the search wrapper told the CoMaps core that every phone speaks English and only had English category names. New setting Settings, "Language of place information": Automatic (the app language, the default), Spanish, English, or Local names (the name as written locally, for example `Lleida` or `Donostia` in Catalonia and the Basque Country, with the address region in the region's own language where CoMaps has it); categories use the app language in that mode. It is part of the settings backup. Typing a name in any language already found it (the maps store the names of every language and the search indexes all of them); that is unchanged. The Maps list no longer shows `World` and `WorldCoasts` (they are base files, not regions) and shows region names in your language when the catalog carries them: `scripts/gen-region-catalog.py` now writes an optional `names` map per region (Spanish by default, from CoMaps' country strings). Map labels on the tiles do not follow the setting yet. Needs `scripts/comaps-prepare.sh` to be run again (it generates the category tables). Verified by JVM, Robolectric and script tests and a native build only; not seen on a device. See `docs/phase2/local-language.md`.
- **Distance to the next turn in the status bar (Android 16 and later).** The navigation notification now asks to be a promoted ongoing "Live Update": the system can show the distance to the next maneuver (for example "160 m" or "1,2 km") as a chip in the status bar next to the camera, plus a progress bar of the trip in the expanded notification. While off route, recalculating or without a GPS signal the chip shows a short word instead, and the final "You have arrived" notification is a normal one. New switch Settings, Navigation, "Show distance in the status bar" (on by default, only shown on Android 16+, included in the settings backup). You can also turn promoted notifications off for the app in the system settings. Below Android 16 nothing changes. Verified by tests only (JVM and Robolectric); not seen on a device. See `docs/phase2/live-update.md`.

### Changed

- **New application id `com.qtekfun.ultimatemaps`** (the app is called UltimateMaps). It installs next to the old test builds instead of updating them: export your settings from the old app, install the new one and import the file (see `docs/decisions.md`).
- **Settings redesigned as a hub.** Settings now opens on a short list of categories (Navigation and voice, Alerts, Petrol stations, Maps and network, Data, About), each with an icon and a one-line summary of its current state, plus a search field. Each category has its own screen with a back arrow; rarely changed items (source addresses, refresh intervals) are under a closed "Advanced" group and delete actions are set apart. No setting was removed or renamed. See `docs/phase2/settings-hub.md`.
- **Settings button moved** to the right-hand map button column, under my location (and the compass when it shows). The OpenStreetMap mark stays top-left.

## [0.1.0-rc.8] - pending

### Added

- **Camera and incident alerts now chime instead of speaking, by default, with one choice per category.** Settings, Speed cameras and traffic has two radio rows, "Speed camera alerts" (fixed cameras, average-speed sections, mobile-radar zones) and "Incident alerts" (accidents, closures, slow traffic, obstacles, weather, roadworks, V16), each shown while its category is on: *Sound (a short chime)*, the default, *Voice (spoken warning)*, which is the old behaviour, or *Silent (on-screen alert only)*. The chime is two short tones generated in code (no audio file): cameras go up in pitch, incidents go down and are lower, so you can tell them apart without looking. It plays as navigation guidance with the same transient "may duck" audio focus as the voice (music is lowered for about a quarter of a second) and at the navigation voice volume. It is never played while the navigation Mute is on, and it is skipped, like the spoken alert, when a turn instruction is imminent; the on-screen chip and banner always show. The old single "Voice alerts" switch is replaced: if you had set it, true becomes Voice and false becomes Silent for both categories (the old key is only read); otherwise both start on Sound. The navigation-screen button "Mute alerts" / "Unmute alerts" now silences the sound and the voice of both categories at once and keeps your per-category choices (it has its own flag, which a settings export does not carry). The two modes are part of the settings backup. The chime pitches and volume have not been tried on a device; verified by JVM tests only.
- Camera and incident alerts have their own mute (the Settings switch of this entry was replaced by the per-category sound modes above; the button stays). The navigation screen has a "Mute alerts" / "Unmute alerts" button next to Mute (shown only while a category is on). Muting alerts silences only their voice: the chip keeps showing, and the navigation Mute still silences everything. Alerts still work only while the app is on screen in free driving (no background service). Verified by tests only, not on a device.
- **Backup and restore of the settings** (Settings, Backup and restore) to move to a new phone. *Export settings* saves a small JSON file (`ultimatemaps-settings-YYYYMMDD.json`) where you choose, through the system file picker; *Import settings* reads one, shows what will change (per group) and only then writes it, then says how many values were restored and how many skipped. It carries the navigation, fuel, camera and incident preferences, the search-history and recording switches, offline mode, the catalog address and the list of installed maps (ids only). It never carries locations (map camera, parked car), recorded tracks, search history, caches, downloaded data or any credential. After an import the Maps screen offers to download the same maps again; nothing downloads by itself and offline mode is respected. Switches that start a connection or need a notice (speed cameras, mobile zones, incidents, V16, roadworks, petrol stations) are NOT turned on by an import: they are shown as "to turn on again" and the camera notice must be accepted again. *Export everything* writes one ZIP with the places backup and the settings; importing it restores both (places are added, nothing is deleted). The file format is versioned (`schema` 1): unknown keys are ignored, wrong values are skipped and counted, a file from a newer format or a corrupt one is refused without changing anything. A test fails when a new preference key is neither exported nor in an explicit exclusion list. Verified with JVM tests only (no device).

## [0.1.0-rc.7] - pending

### Added

- Place cards now get phone, website, wheelchair access and opening hours from the map data: the native search wrapper reads those tags for the results it returns (not for every ranked candidate). The card already knew how to show them. Not measured on a device: search latency and how many places carry these tags.
- Bike routes can prefer cycle infrastructure: Off, Prefer, Strongly prefer or Only cycle infrastructure. It is a row in the route panel for the bike profile and a default in Settings, Navigation. Off keeps the standard routing (which already favours tagged cycleways and lanes); Prefer and Strongly prefer lower the weight of other roads; Only excludes them and, when it finds nothing, says so and suggests Prefer. It relies on OpenStreetMap tags (a cycle lane, a cycle track and "bicycle=yes" are not told apart) and needs a patch to the CoMaps core, applied by `scripts/comaps-prepare.sh`. Route quality has not been judged on a device.

- **Traffic incidents on your route appear as a temporary banner while navigating.** An incident that lies on the planned route ahead (slow traffic, accident, closure, obstacle, V16 stopped vehicle, bad weather, and roadworks when enabled) shows for 5 seconds under the camera chip with its text ("Slow traffic ahead"), the distance and a circular clock counting the seconds; it disappears by itself and a tap dismisses it. The same incident is not shown twice on a trip, several wait their turn (nearest first, at most 3 waiting), and it follows the incidents switch, so nothing shows while it is off. It is visual only (the voice alerts are unchanged) and bigger in glove mode. While navigating, incidents no longer also use the camera chip. Verified with simulated trips and a fake clock only; not tried on a device.
- **Public transport (theoretical routes), Madrid first.** A fourth travel mode, "Transit", next to Car, Walk and Bike in the route panel. Choose "Leave now" or a day and time, and the app lists up to three itineraries with total time, transfers, walking distance and line chips in the line colours; tapping one opens the itinerary card (each leg with line, direction, boarding stop, number of stops, alighting stop, scheduled times and walking legs) and draws it on the map (rides in the line colour, walking dashed, straight lines between stops because the data has no shapes yet). Everything is planned on the phone from timetables (GTFS) that you download yourself in Maps, under "Public transport", only when you press Download; the catalog's optional `transit` block lists them. Times are theoretical (no delays, no real time) and the card says so. The data validity is shown, the app refuses to plan outside it with a clear message (the published Metro de Madrid feed had expired on 2026-05-27, so it is left out until a current one exists), and the required attribution ("Powered by CRTM", "Renfe Operadora, CC BY 4.0", processed data, with links) is in the itinerary and in the About dialog. Verified with JVM and Robolectric tests on made-up feeds and on a planner run over the real feeds; not tried on a device. Step-by-step following of an itinerary is the next task. See `docs/phase2/transit.md`.

### Fixed

- **Long routes are found:** trips that cross several regions (for example Madrid to Barcelona, 620 km) used to end in "route not found"; the cause was the world base maps being registered as routable maps. All twelve long city pairs tested on a phone now return a route (about 19 s for Madrid to Barcelona on a Pixel 8; the 2 s speed target is not met).
- Tapping a camera, mobile-radar zone or incident marker while navigating opened its card in the bottom sheet, which is hidden during navigation, so nothing seemed to happen. The card now opens above the navigation screen (like the petrol-station card) and its Close button works.
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
