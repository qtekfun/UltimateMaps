# Spanish speed cameras (radares): analysis

Status: analysis only, 2026-10-07. No code changed. Nothing here is legal advice. Items marked "not verified" were not confirmed.

## 0. Summary

- The CoMaps core already has a complete speed-camera subsystem (routing + voice + UI callbacks) driven by a `speedcams` section inside each `.mwm`. It only works if the `.mwm` files were generated with `generator_tool --generate_cameras`. Whether our `UltimateMaps-data` MWMs include that section is **not verified** (the data pipeline is not in this repo). This is the cheapest path: no new data format, no new layer for route warnings.
- DGT publishes fixed-radar locations as open data (CC-BY, DATEX2 XML, about 2 MB, 737 records). It excludes the Basque Country and Catalonia and municipal radars. It has no speed limit and no real direction.
- OSM (`highway=speed_camera`) has richer tags (`maxspeed`, `direction`) but uneven completeness; it is ODbL, which we already attribute.
- Mobile radars: the DGT says revealing the exact position of a mobile speed control would be illegal. Only fixed radars should ship. No crowd-sourced or mobile data.
- Recommendation: (a) check/enable the CoMaps `speedcams` section for Spain (OSM-derived) for navigation warnings; (b) optionally add a DGT-derived layer (map POIs plus warnings for cameras not in OSM) as a small file in the weekly data release; (c) setting off by default, with an explicit acknowledgement dialog.

## 1. Data sources

### 1.1 OpenStreetMap

- Tag: `highway=speed_camera` on a node ("a fixed road-side or overhead speed camera"). Related tags: `maxspeed` (enforced limit), `direction` (facing, degrees or cardinal), `ref`, `colour`; the `enforcement` relation for complex setups. Status "de facto". Source: https://wiki.openstreetmap.org/wiki/Tag:highway%3Dspeed_camera
- The wiki page says navigation software should ask during installation whether speed cameras are to be included (some jurisdictions restrict warnings). Same source.
- The page, as read, says nothing about mobile or average-speed cameras. Average-speed ("tramo") sections are usually mapped with `enforcement=average_speed` on relations/nodes; **not verified** for Spain here.
- Licence: ODbL 1.0. Compatible with GPLv3 as a separate data work, provided the OSM attribution is visible (the app already shows it). A derived database (e.g. an extracted camera file) must keep attribution and share-alike for the database. We already ship OSM-derived data in `UltimateMaps-data`, so the obligation is the same.
- Coverage/freshness: depends on mappers. Number of Spanish nodes **not verified**: the Overpass servers refused or timed out the query during this analysis (overpass-api.de answered 406 to the scripted request; overpass.private.coffee timed out). It should be measured on our own Spain extract in the data pipeline (a single `osmium tags-filter n/highway=speed_camera` over the `.osm.pbf`).
- Format/size: a Spain extract is a few thousand nodes at most; tens to a few hundred KB. How to obtain without sending the user's location: the pipeline already processes OSM extracts; add the filter there and publish with the weekly data release. No live Overpass query from the app (it would reveal the area).

### 1.2 DGT (Dirección General de Tráfico), National Access Point

- Dataset "Radares fijos DGT": https://nap.dgt.es/dataset/radares-fijos-dgt
  - Description: fixed traffic radars on the state road network, excluding the Basque Country and Catalonia.
  - Licence: Creative Commons Attribution (the dataset page says "Creative Commons Attribution"; the exact version, 4.0 or other, is **not verified**).
  - Format: DATEX2 (XML), HTTP pull: https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml
  - Declared update frequency: hourly. Measured: the file I downloaded today (2026-10-07) has `publicationTime` 2025-12-18T09:56:52+01:00, so either the feed is not refreshed hourly or I was served a stale copy. Treat freshness as **not verified**.
  - Size measured: 2,051,128 bytes, HTTP 200, no key required.
- Content measured by parsing the file:
  - Two sets: `CabinasCinemometro` with 690 point records, and `CinemometrosVelocidadMedia` with 47 linear (segment) records (average-speed sections, start and end points). Total 737, matching the "737 fixed radars" figure in search results.
  - Per record: WGS84 latitude/longitude, road name (e.g. `A-2`), province name, province INE code, `directionRelative`, kilometre reference (`referencePointDistance`, metres), `directionNamed` (a destination such as ZARAGOZA), road type ("AUTOPISTA / AUTOVÍA").
  - Not present: speed limit and facing direction in degrees (all `tpegDirection` values are `unknown`). A "cabina" (booth) is a place where a radar may be installed; whether a device is inside at any moment is not stated (the DGT's own wording is about "cabinas"; **not verified** how the DGT defines this).
- Other public sources:
  - Madrid city fixed radars: https://datos.madrid.es/dataset/300049-0-radares-fijos-moviles (CC BY 4.0; fixed only despite the URL; update frequency "undefined"; format not confirmed).
  - Catalonia fixed radars (Servei Català de Trànsit): https://datos.gob.es/en/catalogo/a09002970-radares-fijos-de-cataluna (plain text file, daily, licence per Generalitat open-data page: terms not read, **not verified** compatibility with GPLv3; resource http://transit.gencat.cat/web/.content/documents/seguretat_viaria/radars.txt, UTM coordinates).
  - Basque Country: not searched (**not verified**; the Basque government has its own competences).
  - A search showed many commercial lists (Coyote, suradar.com, autopista.es maps): proprietary or unclear licence, not usable.
- Obtaining without leaking location: the national file is only 2 MB and has no per-area parameter, so even a live fetch would reveal only the IP address, not the position. Still, the preferred route is to download in CI, convert, and ship in the weekly data release; the app then fetches it with the same mechanism as the maps (already in the `NetworkPolicy` allow-list for github.com).
- Attribution text needed (CC-BY): "Source: Dirección General de Tráfico (nap.dgt.es), CC BY". Reuse conditions beyond CC-BY (the national NAP terms) were not read: **not verified**.

### 1.3 Comparison

| Source | Licence | Coverage | Direction / speed | Size | Freshness |
| --- | --- | --- | --- | --- | --- |
| OSM `highway=speed_camera` | ODbL (attribution already shown) | Whole of Spain, completeness unknown | `maxspeed`, `direction` when mapped | small (count not verified) | weekly, with our extract |
| DGT NAP | CC-BY (version not verified) | State roads, no Basque Country/Catalonia/municipal | none usable | 2 MB XML, 737 records | declared hourly, measured stale (Dec 2025) |
| Madrid open data | CC BY 4.0 | Madrid city | not checked | not checked | undefined |
| Catalonia | Generalitat terms (not read) | Catalonia | speed limit per section | small | daily |

## 2. Legal position in Spain (not legal advice)

Sources read:

- DGT magazine, "Avisar es legal; detectar e inhibir, no" (2019): https://revista.dgt.es/es/reportajes/2019/08AGOSTO/0806-Avisar-legal-detectar-no.shtml. As summarised by the fetch tool: warning about fixed radars (with an app/GPS database) is legal because the DGT publishes their exact locations; detectors and inhibitors are illegal; for mobile radars, "revelar la posición exacta de un control de velocidad sí sería ilegal"; the Traffic Prosecutor's Office was preparing an opinion on apps that warn of alcohol/drug/mobile-radar controls. The summary did not give article numbers.
- Real Decreto Legislativo 6/2015 (Ley de Tráfico), BOE: https://www.boe.es/buscar/act.php?id=BOE-A-2015-11722. The fetch tool reported article 13.6: "Se prohíbe instalar o llevar en los vehículos inhibidores de radares o cinemómetros o cualesquiera otros instrumentos encaminados a eludir o a interferir en el correcto funcionamiento de los sistemas de vigilancia del tráfico", with an exception for systems that only inform about the location of surveillance equipment. Article number and the exception wording should be re-read directly in the BOE (the tool summarised a 289 KB page and was inconsistent about the sanction annex).
- Press coverage (secondary): https://www.eleconomista.es/motor/noticias/13711889/01/26/que-pasa-si-usas-detectores-y-avisadores-de-radar-al-conducir-esto-dice-la-ley-en-espana.html (avisadores legal if based on DGT-published fixed radars; detectors fined; sanction amounts differ between outlets, 200 or 500 euros and 3 points, so **not verified**); https://www.motorpasion.com/seguridad/fiscalia-pide-a-policia-controles-velocidad-alcoholemia-pone-punto-mira-apps-como-waze and https://www.autopista.es/noticias-motor/las-aplicaciones-que-avisan-de-controles-y-radares-podrian-ser-ilegales_154087_102.html (the Fiscalía de Seguridad Vial has asked police about apps that reveal controls and was working on a sanctioning reform; outcome **not verified**).

Practical reading (to confirm with a lawyer before release):

1. Warning from a database of fixed radars: tolerated/legal, and the DGT itself publishes the locations.
2. Detecting radar signals: not applicable (no hardware), do not add.
3. Mobile radars and police controls (including user reports): legally risky; do not ship, do not accept user reports.
4. The DGT dataset is the safest basis because it is the administration's own publication. OSM-sourced cameras are also fixed devices, but OSM may contain mapped "mobile spots" by mistake; filter them (exclude nodes with `enforcement` or `camera:type` values indicating mobile/temporary, and anything without a fixed `highway=speed_camera` on a road).
5. Other countries: CoMaps prohibits camera data entirely in Germany, Macedonia, Switzerland, Turkey and Bosnia and Herzegovina and partly in France (`libs/routing/speed_camera_prohibition.cpp`). The pipeline for Spain-only data does not hit these, but if the app ever covers other countries this list must be respected.

## 3. CoMaps core: what already exists

Searched `third_party/comaps` (outputs verified by reading the sources):

- Data: `generator/collector_camera.cpp`, `camera_info_collector.cpp`, `generator_tool.cpp`: flag `--generate_cameras` ("Generate section with speed cameras info", default false). The collector reads `highway=speed_camera` nodes with `maxspeed`, attaches them to the related ways and serialises a `speedcams` section into the MWM. `libs/indexer/ftypes_matcher.*` and `data/mapcss-mapping.csv:1128` (`highway|speed_camera`) also classify it as a feature type for the map style.
- Routing: `libs/routing/index_graph_loader.cpp` (`ReadSpeedCamsFromMwm`, `GetSpeedCameraInfo`), `index_router.cpp:1714` (cameras attached to route segments unless the country is prohibited), `speed_camera.hpp` (`SpeedCameraOnRoute`: distance from route start, max speed, position).
- Navigation warnings: `libs/routing/speed_camera_manager.hpp/.cpp`. Mode enum `Auto` (warn only if the driver risks exceeding the limit), `Always`, `Never`. Looks ahead 2000 m on the route, highlights within 1000 m, beep zone about 2 s before the 450 m influence zone, one voice notification per camera (`GenerateNotifications`, `ShouldPlayBeepSignal`). Voice texts come from `turns_tts_text.cpp` / `turns_notification_manager.cpp`.
- UI bridge (Android sample app, reusable): `android/sdk/.../routing/RoutingInfo.hpp` exposes `isSpeedCamLimitExceeded` and `shouldPlaySignal`; `SpeedCameraMode.java`; `Framework.java/.cpp` set/get the mode; `NavigationService.java` and `VoiceInstructionsSettingsFragment.java` consume them. Upstream telemetry hooks in the manager (`SendNotificationStat`) log to a stats sink; confirm they are inert in our build (zero telemetry rule).
- Limits of the core's approach: cameras are only known **on the planned route**. There is no "nearby cameras while free-driving" warning and no on-map layer except the camera mark shown near the route. Our own layer would be needed for those.
- In our repo: `docs/spike/comaps-code.md:96` records "Speed cameras: supported (speed_camera*.cpp)" and `docs/spike/comaps-build.md:108` notes speed limits not tested at runtime. `grep` of `app/`, `core-*`, `native-comaps/` for camera/radar finds nothing: the app does not use it yet.
- Unknown: whether the MWMs published in `data-261004-20261006` contain the `speedcams` section. Check: run the core's reader over a Spain MWM (or look for the section in the MWM header with the CoMaps `mwm_tool`), or look at the generation command used for `UltimateMaps-data`.

## 4. Proposed design

### 4.1 Data pipeline (in `qtekfun/UltimateMaps-data`, weekly release)

1. MWM generation: add `--generate_cameras` for Spain regions (if not already) so the core can warn on routes. Cost: a few KB per region.
2. New small asset `speedcams-es.bin` (or `.json`) per release, listed in `catalog.json` with sha256 like the other files:
   - fixed radars from OSM (`highway=speed_camera`, node coordinates, `maxspeed`, `direction`), excluding anything tagged mobile or temporary;
   - fixed radars and average-speed sections from the DGT file (CI downloads `content.xml`, converts to compact records: lat, lon, road ref, kind `point|section`, section end point);
   - dedupe: a DGT point within about 100 m of an OSM node on the same road is merged; keep OSM `maxspeed`/`direction` and mark `source` bits;
   - header with version, generation time, source attribution flags. Estimated size: under 150 KB.
3. Attribution file shipped in the release notes and in the app (ODbL for OSM-derived nodes, CC-BY for DGT).
4. No mobile radars, no user reports, no third-party commercial data.

### 4.2 App

- Module `:core-cameras` (pure JVM, like `:core-fuel`): `SpeedCameraStore` (loader, grid index of 0.1 degrees, `near(lat, lon, radius, heading)` without allocations in the hot path), `CameraSettings` (+ `CameraSettingsStore`, `PrefsCameraSettingsStore` in `app/.../cameras`, SharedPreferences `mapas_cameras`, normalised like fuel), `CameraAttribution`.
- Download: reuse the regional catalogue mechanism (asset in the data release, through `NetworkPolicy`, sha256 verification). No new host. Never at startup; only on enabling or on "Update now" and when the data release changes.
- Map layer: `SpeedCameraMapLayer` modelled on `FuelMapLayer` (GeoJSON source plus symbol layer, visible from zoom about 11-12, small icon, tap card with source and "reported speed limit (if known)", plus attribution). Off unless enabled.
- Navigation warning, two tiers:
  1. Route-based: set the core's `SpeedCameraManagerMode` from our setting (Never when off; Auto or Always when on) and consume `shouldPlaySignal` and the show/clear callbacks already in the SDK. Voice goes through our `VoiceGuide` (new phrase "Speed camera ahead", plus the limit when known, es/en strings).
  2. Free-driving / data not in MWM (DGT-only cameras): our own `CameraWarner` fed by `LocationSource`: heading-aware lookahead (about 600-1000 m depending on speed), one warning per camera passage, hysteresis, cooldown, quiet when speed is below the known limit and mode is Auto. The same `VoiceGuide`.
- Settings (RF-17 area): section "Speed cameras", switch off by default, confirmation dialog on enabling that says: only fixed cameras, data source and date, no mobile radar or police control data, local only, check the traffic rules where you drive, and that the warning is informational. Options: warn always or only if speeding, beep or voice, show on map. Attribution text and data date shown in Settings, like fuel.
- Zero-telemetry check: the core's `SendNotificationStat`/`SendEnterZoneStat` must not leave the device (confirm the stats sink is a no-op in our build).

### 4.3 Tests

- JVM: DGT DATEX2 converter (a fixture trimmed from the real file, including a linear section and a missing field), OSM filter (mobile/temporary excluded), merge/dedupe, binary format round trip with CRC, settings normalisation, store loading (corrupt file means no data), grid `near` with heading and wrap-around.
- `CameraWarner` with a simulated `LocationSource`: approach at 50/90/120 km/h, once per camera, wrong direction ignored, off by default (no warnings, no downloads), offline mode.
- Robolectric: Settings confirmation flow and attribution text (es/en); `SpeedCameraMapLayer` source/layer ids, like `FuelMapLayerTest`.
- Device test (only with the owner's permission each time): not part of this analysis.
- Offline policy test: with the feature off, zero connections; enabling goes through `NetworkPolicy`.

### 4.4 Effort (estimate, person-days, not measured)

| Task | Estimate |
| --- | --- |
| Verify/enable `--generate_cameras`, rebuild Spain MWMs, check the core warns on a simulated route | 1 |
| Data pipeline (OSM filter, DGT converter, merge, catalogue entry, CI) | 2 |
| `:core-cameras` store, settings, download, attribution | 2 |
| Map layer + tap card | 1.5 |
| Navigation bridge to the core mode + voice + `CameraWarner` | 3 |
| Settings UI + dialog + strings (es/en), docs, LICENSES/PRIVACY | 1.5 |
| Tests | 2 |
| Total | about 13 (about 8-9 if only route-based warnings from the core are shipped first) |

Suggested order: (1) route-based warnings through the core (cheapest, no new data file), (2) map layer from the data file, (3) free-driving warner.

## 5. Risks and open questions for the owner

1. Legal: confirm with a lawyer that the warning feature (fixed only, from public administration/OSM data) is acceptable for a public GPLv3/F-Droid app. The Fiscalía sanctioning reform outcome was not verified; the rules may change. Decide whether to ship only DGT data (safest) or also OSM.
2. Mobile radars/controls: confirm the decision to never ship them or accept user reports (recommended).
3. Licences: DGT CC-BY version and NAP terms of reuse were not read in full; the Catalonia and Basque data licences were not verified; ODbL/CC-BY go in `LICENSES.md`.
4. DGT feed freshness: the downloaded file is dated 2025-12-18 despite "hourly"; ask nap@dgt.es or poll for a few days before relying on it. The "cabina" semantics (a booth, not always a live radar) may produce false positives; the UI wording should be "possible fixed speed camera".
5. Coverage gaps: Basque Country, Catalonia and municipal radars are not in the DGT file; OSM may fill part of it. Is partial coverage acceptable, and should Madrid/Catalonia sets be added later?
6. Do the current MWMs contain the `speedcams` section? Is the data-generation command under our control?
7. Direction: DGT has no heading, so a DGT-only warner would also warn drivers on the opposite carriageway. Mitigation: use the road name/km and route matching, or accept false positives on dual carriageways.
8. Store policy: F-Droid generally accepts this; Google Play restrictions on "speed-trap" features are **not verified** (the project targets F-Droid and GitHub).
9. Product: warn off by default and per-session? Voice-only or also beep? Show cameras while not navigating? Show the speed limit when unknown (OSM often lacks `maxspeed`)?
10. Telemetry: verify the CoMaps speed-camera stat functions do nothing in our build.

## 6. What was and was not verified

Verified: DGT dataset page contents and the real XML (downloaded and parsed: 2,051,128 bytes, 690 + 47 records, fields listed above, publication time 2025-12-18); the CoMaps source tree findings in section 3; the OSM wiki tag description.
Not verified: OSM node count for Spain; DGT feed refresh rate; CC-BY version; Basque/Catalan/Madrid licence details beyond the portal pages; exact legal articles and sanction amounts; whether our MWMs contain cameras; Play Store policy; any device behaviour.
