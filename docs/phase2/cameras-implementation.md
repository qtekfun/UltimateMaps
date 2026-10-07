# Speed cameras, mobile-radar zones, traffic incidents and V16 beacons: implementation

Status: 2026-10-07, branch `feat/cameras-incidents`. Built on the analysis in `speed-cameras-analysis.md` after the owner decided
to implement all of it, optional and off by default. **Nothing was run on a device** (no phone was used): everything below is
verified by JVM tests, Robolectric tests, or by downloading and parsing the real public data; what is not verified is listed in
section 7. Data format and the weekly-release recipe: `cameras-data.md`.

## 1. What exists

Four independent switches in Settings > "Speed cameras and traffic" (all off by default):

| Switch | What it shows and announces | Data | Network |
|---|---|---|---|
| Fixed speed cameras | fixed cameras and average-speed sections; alert ahead, the enforced limit when known | `speedcams-es.bin` (DGT CC BY + OSM ODbL) | one small file from the catalog's server, on enabling, "Update now", or foreground when the catalog names a different file |
| Mobile-radar zones | stretches of road where the DGT says mobile radars may operate: dashed rough lines, a card with road and kilometres; alert on entering a drawable zone | same file | same |
| Traffic incidents | accidents, closures, slow traffic, obstacles, bad weather (roadworks only with their own sub-switch) | DGT NAP DATEX II v3.7 national feed | explicit opt-in dialog; one national file; TTL 5 min to 1 h |
| V16 beacons | stopped vehicles with a connected V16 beacon | same feed | same |

The two camera switches need the user to accept a notice first (`acknowledged`, stored; a camera layer cannot be on without it,
even from a hand-edited preferences file). The traffic switches show their own consent dialog (what is downloaded, from where,
that nothing about position is sent, that offline mode blocks it). A short legal note is always visible in the section.

Code:

- `:core-cameras` (pure JVM): `CameraFile` (reader), `DatexIncidentParser`, `BoundedHttp` (the only HTTP code: every hop goes
  through `NetworkPolicy`, https only, redirect hops authorized one by one, size cap applied after gzip decompression, timeouts),
  `CameraDataManager` / `IncidentDataManager` (settings, policy, cache, TTL, offline), `IncidentCache`, `AlertWarner`,
  `AlertPhrases` / `AlertVoice`, `NavAlertFeed`, `FreeDrivingFeed`, `CameraAttribution`, `CameraSettings`.
- `:core-net`: new `ConnectionPurpose.TRAFFIC_INCIDENTS` (listed in the visible connection list as "Traffic incidents (DGT)").
- `:core-regions`: optional `cameras` block of the catalog (`RegionCatalog.cameras`); `scripts/gen-region-catalog.py --cameras-file`.
- `:core-map`: `HazardKind`, `HazardPin`, `HazardLine`, `MapEngine.showHazards` / `setHazardTapListener`.
- `:core-voice`: `InstructionText.lead` (the "In 300 meters" lead for non-maneuver prompts).
- `:app`: `HazardMapLayer` (modelled on `FuelMapLayer`), `HazardIcons` (shape and colour per kind), MapLibre layers and tap in
  `MapLibreEngine`, `HazardCard`, `CamerasSettingsSection`, `CameraAlerts` + `AlertNavSink`, wiring in `MapasApp`.
- `scripts/build-cameras.py` (+ `scripts/test_build_cameras.py`), run by the weekly data job (not changed here).

## 2. The alert logic (`AlertWarner`)

Pure and deterministic: fixes and the time come in as arguments. Two entry points, one set of rules:

- **Route-based** (navigating): fed by the `NavigationController` state. A target counts only when it lies within 35 m of the
  route and ahead along it; the distance is measured along the route. A camera on a parallel road is ignored.
- **Free driving** (no navigation, app on screen, a switch on, location permission granted): fed by a `LocationSource` of its
  own (never the navigation's: a second `start` would replace its listener). A target counts when it is within 35 degrees of the
  direction of travel (the fix's bearing, or derived from the last 15 m of movement).
- Both: a target with a known road axis counts only when the travel direction agrees (both, along, or against the axis, within
  50 degrees; incidents with a named direction within 60 degrees).
- Look-ahead = 30 s of travel, clamped to 400 to 1000 m. Below 2.5 m/s (9 km/h) nothing is announced.
- One FAR announcement per target group per approach (the two ends of a zone or incident are one group); the nearest eligible
  target per fix; at least 6 s between announcements; a target already closer than 60 m is passed silently; a group is
  forgotten at 1.6 x look-ahead so the next approach warns again.
- NEAR announcement at 250 m for cameras and sections only when the known limit is exceeded ("Slow down"). "Warn only if
  speeding" silences FAR for cameras with a known limit that is not exceeded; cameras with no known limit always warn.
- The voice goes through the navigation `VoiceGuide` / `SpeechDirector` (`NORMAL` priority, key per group, 6 s max age) and the
  navigation voice settings (on/off, language, units, volume). Wording is cautious ("possible fixed speed camera"; zones are
  "stretch where mobile speed cameras may operate"; V16 is "stopped vehicle with a V16 beacon").
- The numbers above are design values, not measured. Since the alert fix (section 9) there is also a visual chip.

Free-driving alerts only work with the app on screen: there is no background service for them (navigation has its own foreground
service, and route-based alerts work there). With every switch off no location listener exists at all.

## 3. Real data (downloaded 2026-10-07)

- **DGT incident feed** (`https://nap.dgt.es/datex2/v3/dgt/SituationPublication/datex2_v37.xml`): HTTP 200, no key, 6,359,487
  bytes uncompressed and 203,179 gzipped (`Accept-Encoding: gzip`), `cache-control: max-age=36..40`, `publicationTime`
  2026-10-07T15:28 (fresh; dataset page: "update frequency 1 min", licence "Creative Commons Attribution", terms link
  https://www.dgt.es/contenido/aviso-legal/ not read). 1,437 `situationRecord`s, parsed in 115 to 227 ms on the JVM:
  **1,426 shown, 11 skipped**, of which ROADWORKS 800, CLOSURE 170, OBSTACLE 275, CONGESTION 43, ACCIDENT 35, WEATHER 4,
  **V16 99**.
- **V16**: there is no separate V16 feed in the NAP catalog (searched the dataset list). The beacons are in the same incident
  feed: records whose `situationRecordCreationReference` starts with `V16_`, source `DGT3.0`, cause
  `vehicleObstruction/vehicleStuck`, a point location with a travel direction. That identification is an **inference from the
  data** (99 such records all fit); the DGT does not document it here (not verified), so a change in their naming would hide
  the beacons rather than show wrong things.
- **DGT fixed radars, report, anchors**: counts in `cameras-data.md` section 4.
- **OSM speed cameras**: Overpass timed out for a whole-country query (504) and for a large bounding box, but answered for small ones, so Spain was
  queried in 3-degree tiles restricted to the `ISO3166-1=ES` area (48 tiles, several retried; 5 ocean-only tiles never answered and
  a land tile was redone in four sub-tiles): **2,526 `highway=speed_camera` nodes inside Spain**, 28 more dropped by the
  mobile/disused filter, 2,236 of the kept fixed cameras have a usable `maxspeed` after the merge (OSM often has it, the DGT never does). Completeness of the
  tiles is not guaranteed (a failed tile would silently be missing), so treat the count as "at least". Merged with the DGT
  booths (within 100 m): **608 merged, 2,608 fixed cameras in total** (690 DGT booths + OSM), 47 average-speed sections, 1,325
  mobile-radar zones (25 drawable), output **80,526 bytes**; the Kotlin reader reads that real file (`REAL_CAMERAS` check).

## 4. Mobile radars: what is shown and the legal caveat

The only official public information that exists is the DGT's own report of roads and kilometre ranges (1,325 rows on
2026-08-03). The app shows it at exactly that precision: a road and a kilometre range, a rough dashed line only where the
kilometre points could be placed (25 of 1,325 now), the rest only as text in the data. It never shows a point, never takes
live or crowd-sourced reports, and the card says the DGT publishes a stretch, not a position. The DGT has said that revealing the
exact position of a mobile control would be illegal, and that warning of fixed radars is legal because it publishes their
locations (see the analysis, section 2; sources read there, not legal advice). The settings switch has a notice and a short
legal note; a lawyer should still confirm the whole feature for a public F-Droid app (owner decision, section 8).

## 5. Why CoMaps' own speed-camera mode is not enabled

The CoMaps core has a complete `SpeedCameraManager` that works from a `speedcams` section in each `.mwm`, only on the planned
route. It is **not** enabled: (1) whether our published `.mwm` files contain that section (generated with
`--generate_cameras`) is not verified, and the data pipeline is not in this repository; (2) our JNI never sets its mode or
consumes its callbacks, and nothing was run on a device to check that it works, so enabling it blind could produce silent or
duplicate warnings; (3) our own warner covers the route case (using the DGT/OSM data, with the same switch, voice and attribution)
and the free-driving case the core cannot; (4) upstream's stats hooks in that manager were not audited against the zero-telemetry
rule. Revisit only after checking an `.mwm` for the section and testing on a device.

## 6. Tests

- `:core-cameras`: file reader (cross-checked with the Python converter's output), real DGT excerpt and the whole real feed
  when `UM_REAL_DGT_FEED` points at it (optional, skipped otherwise), `AlertWarner` driven by `SimulatedLocationSource` along
  straight roads (approaches at 50, 90 and 120 km/h, once per camera, again on the next approach, wrong direction, crossing road,
  per-category switches, slow speed, derived heading, known limit with NEAR and "only if speeding", group dedupe, spacing,
  passed-silently, route-based on the route, parallel road ignored, direction), managers against a local HTTP server (zero
  connections while off, opt-in, TTL, offline mode, gzip, cap after decompression, hash mismatch, absent catalog entry, cache
  loaded offline, failures keep the last good data, no query or position in the request), cache damage, phrases es/en, settings
  normalisation, attribution.
- `:core-regions`: the optional `cameras` catalog block. Python: `scripts/test_build_cameras.py`, `scripts/test_gen_region_catalog.py`.
- `:app` (Robolectric/JVM): `HazardMapLayerTest` (per-category drawing, off draws nothing, zoom floor, redraw on data change),
  `CamerasSettingsTest` (off by default, acknowledgement and traffic consent flows, offline mode refuses and logs the attempt,
  sub-switches appear only with their parent, preferences normalise tampering, es/en string parity, card texts and attribution).
- Tests use virtual time or counters; none depends on the real clock or on recomposition from other threads (the layer test
  uses the same real-dispatcher pattern as `FuelMapLayerTest`, with waits on conditions).

## 7. Not verified

- Anything on a device: marker look and legibility in light and dark, tap targets, dashed line rendering, voice quality and
  timing at real speeds, battery cost of the free-driving location listener, GPS bearing quality at low speed, the card in the
  sheet, the settings layout on a phone. The map code (`MapLibreEngine`, icons) compiles but was never rendered.
- Whether the DGT feeds are "refreshed every minute" in practice for long (the incident feed looked fresh; the fixed-radar file
  was 9 months old despite its "hourly" label), the exact CC BY version, the DGT legal notice, the PDF report's licence.
- The V16 identification (section 3), and the meaning of the DGT "cabina" (a place where a radar may be, not that one is on).
- OSM `direction` semantics for speed cameras; OSM coverage and quality (many nodes lack `maxspeed`).
- Catalonia, the Basque Country and municipal radars (not in the DGT data); Madrid and Catalonia open data not added.
- That our `.mwm` files carry or lack a `speedcams` section; the CoMaps stats hooks.
- Legal acceptability of the feature for a public app, and store policies other than F-Droid.
- Alert timing was designed, not measured; no field test; the 30 s look-ahead, 35 degree cone and 6 s spacing are guesses.

## 8. Owner decisions needed

1. Legal review of the whole feature (fixed cameras from the administration's own list are tolerated; mobile zones are a gray
   area; the Fiscalia reform outcome was never verified). Ship mobile zones at all?
2. Confirm the DGT licence terms (CC BY version, legal notice, the report PDF) before the first release that includes the file.
3. Who changes the weekly workflow in `UltimateMaps-data` to build and list `speedcams-es.bin` (recipe in `cameras-data.md`).
4. Whether to also ship OSM cameras (more coverage and `maxspeed`, uneven quality) or DGT only (the safest basis).
5. Mobile zones: only 25 of 1,325 can be drawn because most roads (regional) have no kilometre reference in any open DGT
   dataset. A better road geometry source (OSM road refs with kilometre posts, or a dataset with PK geometry) is needed to make
   the rest useful; until then they are text only.
6. Whether free-driving alerts should also work with the screen off (needs a foreground service and a visible notification).
7. Whether roadworks and weather should ever be announced (a visual alert banner now exists, section 9).

## 9. Alert fix and visual alert (2026-10-07, branch `fix/camera-alerts`, no phone)

The owner used rc.6 while driving and heard no warnings. Cause 1 (the data file was not in the data release) is outside the code.
Reading the code and driving it with tests found these defects, all fixed:

1. **The catalog could never name the camera file.** `CameraDataManager` looks the file up in the cached catalog, and the
   catalog was refreshed only by opening "Maps". A phone with a catalog cached before the `cameras` block existed answered
   "no catalog entry" forever (silently on foreground; an error only under "Update now"). Now `refresh` calls `syncCatalog` first
   (`RegionsController.syncCatalog`): forced for "enable" and "Update now", once per process on foreground, never in offline mode,
   and a failure keeps the cached catalog. The request is the same catalog request the Maps screen makes (same server, same policy).
2. **Route alerts depended on a screen event.** Only `NavScreenController` told `CameraAlerts` that a trip began (`AlertNavSink`).
   A trip resumed by `NavigationService` after the process was killed (null intent), or a switch turned on during a trip (the alerts
   object did not exist when the trip began), got no alerts. `CameraAlerts` now also follows `NavigationController.state`.
3. **Voice priority.** Alerts were `NORMAL`, which discards pending far turn prompts and interrupts a far prompt being spoken, and
   a camera sentence queued ahead of a near turn prompt delayed it. New `VoicePriority.ADVISORY`: speaks only when no instruction
   waits, never interrupts or discards anything, is interrupted by `NORMAL`/`URGENT`, is dropped first when the queue is full.
   In addition `ManeuverGuard` stops the alert from being spoken while the next maneuver is closer than the navigation's own
   "near" band (10 s of travel, 60 to 400 m) at the current speed: the driver is about to be told what to do; the chip still shows.
   The alert is already marked as warned, so it is not repeated (a camera right after a turn may therefore be silent: visual only).
4. **Free-driving permission.** Answering the location permission dialog did not re-evaluate the free-driving feed until the app
   was next foregrounded; `MainActivity` now calls `refreshCameraAlerts()` from the permission result.

Answers to the audit questions (verified by `CameraAlertsWiringTest`, `CameraAlertsEndToEndTest`, `DataManagersTest`):

- **When are alerts active?** A camera/incident switch on (camera switches also need the stored acknowledgement) AND, for the
  camera layers, data loaded (cache or download) AND either a navigation is running (route-based, works with the screen locked or the
  app in the background because it is fed by the navigation foreground service) or the app is on screen with the location permission
  (free driving). With no navigation and the app in the background there is nothing, by design.
- **Download and consent.** Enabling a camera switch shows the one-time notice (`cam_confirm_*`); until accepted the switch stays off
  (normalisation enforces it). After acceptance the catalog is refreshed and the file is fetched through `NetworkPolicy`
  (`MAP_DOWNLOAD` purpose, https, hash checked). The data card in Settings shows "never" or the error when it failed.
- **Mute.** Alerts use the navigation voice switch, so Mute and "Voice guidance off" silence them (intended). "Important prompts only"
  does not: it filters maneuver prompts only. The chip appears in every case.

Visual alert: `AlertBannerTracker` (core, pure) holds what the warner last announced and counts the distance down (route mode: by
progress along the route; free mode: straight distance, dismissed when the driver is more than 40 m beyond the closest approach;
that slack is a design value). `CameraAlertBanner` draws it: red chip for cameras and zones, brown for incidents, the icon, a title,
the distance and a round limit sign when known; one content description with a polite live region; 52 dp tall, 72 dp in glove mode.
It sits under the maneuver banner on the navigation screen and under the map controls otherwise. Distances in the chip are metric
(like the route panel), while the voice follows the navigation units setting.

Not verified: everything on a device (see `camera-alerts-checklist.md`); how the chip looks over real maps; whether the 10 s guard
is the right threshold; the timing numbers of section 2.
