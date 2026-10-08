# Transit routing spike (schedule-based, Madrid)

Status: spike (`spike/transit-madrid`), then built into a feature on `feat/transit` (section 7), 2026-10-07. Covers rows N10 (public transit directions) and M6 (static
transit layer) of `feature-gap-analysis.md`. Goal chosen by the owner: **theoretical (timetable) routes computed fully
offline on the device from static GTFS. Real time is out of scope.** The spike itself built no UI; section 7 describes what was built afterwards.

Rule for this document: every number below comes from a run on the real feeds (log files kept in
`docs/phase2/transit-bench-2026-10-07.txt` (decimal commas in that log are the JVM locale)) or from a file I read; anything else is marked *not measured* or *not verified*.
Constants of the walking model are assumptions, labelled as such.

## 1. Real data for Madrid

All feeds were downloaded on 2026-10-07 into `~/mapas-data/transit/raw/` (outside `/tmp`).

| Feed | URL (verified, HTTP 200) | Size (zip) | Calendar validity (read from the feed) | Notes |
|---|---|---|---|---|
| CRTM Metro de Madrid | `http://crtm.maps.arcgis.com/sharing/rest/content/items/5c7f2951962540d69ffe8f640d94c246/data` | 1,503,773 B | 2025-05-27 .. **2026-05-27 (expired)**; `feed_version` 20250527; portal item last modified 2025-05-30 | 13 routes, 120 trips, **frequency based** (`frequencies.txt`, 790 windows), 2,216 stop_times |
| CRTM Metro Ligero / tranvia | `.../items/aaed26cc0ff64b0c947ac0bc3e033196/data` | 417,242 B | 2026-07-22 .. 2027-07-22; version 20260722; modified 2026-07-29 | 4 routes, 3,001 scheduled trips, 38,983 stop_times |
| CRTM EMT Madrid buses | `.../items/868df0e58fca47e79b942902dffd7da0/data` | 21,060,726 B | 2026-09-29 .. 2026-12-31 (+ `calendar_dates`); no `feed_info`; modified 2026-09-30 | 235 routes, 86,871 trips, 2,233,315 stop_times, **frequency based** (one `frequencies.txt` row per trip) |
| CRTM interurban buses | `.../items/885399f83408473c8d815e40c5e702b7/data` | 74,153,792 B | mostly 2026-09-10 .. 2027-10-08; version 20260910; modified 2026-09-30 | 354 routes, 56,118 trips, 1,448,978 stop_times, scheduled |
| CRTM other urban buses | `.../items/357e63c2904f43aeb5d8a267a64346d8/data` | 9,119,523 B | 2026-09-12 .. 2027-10-10; version 20260910; modified 2026-09-30 | 117 routes, 16,022 trips, 436,295 stop_times |
| CRTM Cercanias | `.../items/1a25440bf66f499bae2657ec7fb40144/data` | 6,042 B | 2024-07-17 .. 2025-07-17 | **Unusable stub**: 1 trip, 1 stop_time (only stops and routes). Use Renfe's own feed |
| Renfe Cercanias (all nuclei of Spain) | `https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip` (linked from `https://data.renfe.com/dataset/horarios-cercanias`) | 14,118,007 B (242,699,280 B of `stop_times.txt` unzipped) | 450 one-day services, **2026-10-07 .. 2026-11-05: a rolling 30-day window**; zip entries dated 2026-10-07; portal says last update 2026-10-07 | 1,141 stops (114 inside the Madrid bounding box), 565,730 stop_times kept for the Madrid box, has route-specific `transfers.txt`. Fields are space-padded (the reader trims them) |

(`.../items/` is shorthand for `http://crtm.maps.arcgis.com/sharing/rest/content/items/`.) The CRTM item ids come from the
CRTM open-data portal (`https://data-crtm.opendata.arcgis.com/`, collection API query "GTFS"); the same portal hosts the
non-GTFS shapefile layers. Transitland also lists the consortium feed (`f-ezjm-consorcioregionaldetransportesdemadrid`),
pointing to the NAP licence page.

**Update frequency.** CRTM: the portal items were modified on 2025-05-30 (Metro), 2024-08-27 (Cercanias), 2026-07-29
(Metro Ligero) and 2026-09-30 (EMT, interurban, other urban): irregular, roughly per timetable change; the Metro feed has
not been refreshed in 16 months and its calendar has lapsed (see section 5). Renfe: regenerated daily, each file covers the next
30 days, so a consumer must refresh at least monthly.

**National Access Point (`nap.transportes.gob.es`).** Reachable; the licence page was read. Downloads need a registered
account (a probe of the file API returned HTTP 401), so *which Madrid feeds NAP lists and whether they differ from the CRTM
portal is not verified*. `mobilidata` was not checked. NAP matters for other cities later, not for Madrid.

### Licences and GPLv3 distribution (LICENSES.md-style notes)

| Data | Licence (source) | Obligations | Compatibility note |
|---|---|---|---|
| CRTM feeds | "Licencia de datos estaticos del CRTM" (`https://www.crtm.es/licencia-de-uso`; the item metadata says licence = that URL, credit "CRTM") | Commercial and non-commercial reuse allowed, copy/modify/combine allowed. Must cite CRTM as source, say whether data is raw or processed, show "Powered by CRTM" with a link to `http://www.crtm.es/` on digital platforms; keep the update-date and reuse-condition metadata; no suggestion of CRTM endorsement; do not distort or defame; copies of the data are shared under the same licence, derivative works may use other licences; CRTM may block abusive access | It is a data licence, not a code licence, so it does not touch the GPLv3 of the app, exactly like ODbL for the OSM data: the transit index is a separate data file with its own notice. Open point: the clause "guarantee that the information shown is always up to date" is hard to meet for an offline app with weekly data. Mitigation: show the feed validity date in the UI and refuse to route on an expired index. **Needs the owner's reading before shipping** |
| Renfe Cercanias | CC BY 4.0 (data.renfe.com dataset page: "Licencia: Creative Commons Attribution 4.0") | Attribution to Renfe Operadora, indicate changes | Fine as data. Attribution text goes in About and in the itinerary footer |
| NAP / MITRAMS | "Licencia de datos abiertos del MITRAMS" (`https://nap.transportes.gob.es/licencia-datos`) | Same shape as CRTM's, with "Powered by MITRAMS" and a link to `https://www.transportes.gob.es/` | Same data-licence reasoning |

Rows to add to `LICENSES.md` when this graduates (data, not code, not bundled in the APK; downloaded like the maps): CRTM
open data (LDA, attribution "Powered by CRTM", processed data); Renfe Cercanias GTFS (CC BY 4.0). `:core-transit` has no
third-party dependency (JUnit only in tests).

## 2. What was built

Module `:core-transit` (pure JVM, depends only on `:core-geo` for `LatLon`; registered in `settings.gradle.kts`).

| File | Role |
|---|---|
| `Csv.kt` | Streaming CSV reader: BOM, CRLF, quotes, trims every field (Renfe pads), by-name columns, `H:MM:SS` times above 24 h |
| `Gtfs.kt` | `GtfsReader` (stops, routes, trips, stop_times, calendar, calendar_dates, transfers, frequencies, feed_info) from a directory or a zip; optional stop filter (bounding box) and an `ignoreCalendarRange` switch; fills blank times by interpolation |
| `TransitIndexBuilder.kt` | Merges several feeds (stops merged by `namespace:stop_id`), groups trips into patterns, keeps frequency windows as frequency trips |
| `TransitIndex.kt` | Immutable RAPTOR layout: flat `IntArray`s, patterns with trips sorted by first departure |
| `TransitIndexIo.kt` | Binary file format `UMTI` v2 (read/write) |
| `TransitPlanner.kt` | RAPTOR earliest-arrival planner, walking access/egress/transfers, Pareto set over (arrival, rides), next-departures fallback |
| `src/test/.../TransitTest.kt`, `Fixtures.kt` | 18 tests on synthetic feeds |
| `src/test/.../bench/TransitBench.kt` | Measured run on the real feeds (`./gradlew :core-transit:transitBench -PtransitData=...`) |

### Index format: custom binary, not SQLite

Choice: a flat binary file loaded into primitive arrays. Reasons: RAPTOR scans trips of a pattern sequentially, so
contiguous `IntArray`s beat per-row SQL access by a wide margin; no JNI or driver dependency in a pure-JVM module; the
file is a single artifact that fits the existing "one file per region + sha256 in the catalog" delivery. Cost: the whole
index sits on the heap (section 3); a SQLite or memory-mapped layout would lower that and is future work.

Layout (big-endian, LEB128 varints): header `UMTI`+version; feed provenance (label, version, calendar range,
attribution text, whether the range was ignored: this keeps the metadata the CRTM licence asks to preserve); headsign
table; stops (name, lat/lon in micro-degrees, station group); lines (short/long name, colours, route_type); services
(weekday mask, range, added/removed dates); patterns; transfers. Per pattern, each distinct *time profile* (run time and
dwell per stop) is stored once and each trip is a tuple `(service, headsign, profile, first arrival, headway, runs)`;
trips of one pattern are mostly time-shifted copies, so this is cheap.

### Frequency feeds: why windows are kept, not expanded

My first version expanded every `frequencies.txt` window into explicit trips (needed by a naive RAPTOR scan). Measured on
the real feeds that produced **2,060,199 trips and 44,381,461 stop_times** (a 14.0 MB file, 358 MB of heap when loaded),
almost all from EMT (the literal reading gives 1.9 million EMT departures across its service ids). The shipped design keeps
each window as one *frequency trip* (stored times of its first run, headway, number of runs) and computes the next run in
closed form during the scan: **199,496 trips and 4,738,657 stop_times**, a 3.35 MB file, 45 MB of heap. Only the
scheduled trips are binary-searched; frequency trips are scanned per boarding (a few hundred per pattern at most).

### Planner behaviour

- RAPTOR rounds = vehicle rides (default up to 5 rides, 4 transfers). Output is the Pareto set over (arrival time, number of
  rides). Each leg is `Walk(from, to, depart, arrive, metres)` or `Ride(line, headsign, from, to, depart, arrive, stopCount)`,
  with times in seconds from the midnight of the query service day (values above 86400 mean "after midnight").
- Calendar: trips of the previous, current and next service day are considered, so trips that run past midnight and
  journeys that cross midnight work (tested). `calendar_dates` additions and removals are honoured.
- Walking (assumptions, `PlannerConfig`): straight-line distance (equirectangular) times 1.3 (street detour factor) at
  1.25 m/s (4.5 km/h); access/egress to the 30 nearest stops within 800 m; stop-to-stop transfers within 300 m; same
  `parent_station` platforms take at least 120 s; 60 s safety slack when changing vehicle; GTFS `transfers.txt` type 3
  blocks a pair and a minimum time is respected. A walk-only itinerary is offered when it takes at most 30 min and no transit
  option is faster.
- Fallback "next departures": `planNextDepartures(count)` restarts the search one minute after the previous itinerary's first
  boarding; returns the best itinerary for each of the next N distinct departures.
- Not modelled: real time, pickup/drop-off restrictions, route-specific transfers, fares, accessibility, arrive-by queries,
  bikes, trip shapes.

## 3. Measurements on the real Madrid feeds

Machine: Intel Core Ultra 7 265U laptop CPU (14 logical cores), 32 GB RAM, OpenJDK 21.0.12 (64-bit server VM), desktop
Linux, JVM with a 3 GB maximum heap. **Not measured on an Android device**: expect slower numbers there.

Query date 2026-10-14 (a Wednesday). Metro de Madrid's calendar had expired (2026-05-27), so for the Metro feed only the
calendar date range was ignored (the weekday pattern and holiday exceptions are used); this is a spike shortcut, flagged in
the index provenance. All other feeds were inside their validity.

| What | Result |
|---|---|
| Feeds merged | Metro, Metro Ligero, EMT, interurban, other urban, Renfe Cercanias (Madrid bounding box lat 39.8..41.2, lon -4.6..-3.0) |
| Index content | 13,185 stops, 721 lines, 2,585 patterns, 199,496 trips (scheduled + frequency windows), 4,738,657 stop_times; 83,270 walking transfer edges |
| Build time (read 6 feeds from zip + merge + finalise) | 2.7 s total (EMT 0.8 s, interurban 0.7 s, Renfe 0.9 s of it, incl. streaming the 242 MB national `stop_times.txt`) |
| Peak process memory during the build | 660 MB resident (VmHWM of the JVM) |
| Index file on disk | 3.35 MB raw, 1.20 MB gzip (the 6 zip inputs total about 120 MB, decimal) |
| Write / load time | 0.1 s / 0.09 s |
| Heap held by the loaded index | about 45 MB (difference of used heap after forced GCs, so approximate); planner structures add about 4 MB, built in 0.04 s |
| Query time, 20 origin/destination pairs, 5 passes after a warm-up pass (100 samples) | **median 5.2 ms, p95 10.1 ms**, min 0.5 ms, max 10.7 ms |
| Cold first queries (JIT not warm), 5 sanity trips | 4.3 to 19.5 ms |
| "Next 4 departures" query (Sol to Atocha) | 8.1 ms |

For scale: the Madrid region data release is 102.6 MB (`.mwm`) plus 84.2 MB (`.pmtiles`); the transit index adds about 1.2
MB gzip.

The 20 pairs (coordinates of well-known places from memory, except Alcala, Colmenar Viejo, Puente de Vallecas and Rivas,
which use station coordinates read from the feeds; an earlier run with my remembered coordinates for those four reported
"no route" or odd paths only because no stop was inside the 800 m access radius): Sol-Atocha, Chamartin-Nuevos Ministerios,
Sol-Principe Pio, Plaza de Castilla-Moncloa, Atocha-T4, Chamartin-Atocha, Moncloa-Vallecas, Bernabeu-Retiro, Sol-Getafe,
Alcobendas-Sol, Leganes-Nuevos Ministerios, Las Rozas-Atocha, Alcala-Chamartin, Torrejon-Sol, Elliptica-Plaza de Castilla,
Alcorcon-Moncloa, Fuenlabrada-Atocha, Mostoles-Principe Pio, Rivas-Vallecas, Colmenar Viejo-Chamartin. All 20 found a route.

### Sanity check of known trips (departure 09:00 on 2026-10-14)

My expectations come from general knowledge of the network, not from an authoritative source.

| Trip | Planner result | Expectation | Verdict |
|---|---|---|---|
| Sol to Atocha | Cercanias C4a Sol-Atocha, 5 min ride, door to door 6 min; Metro L1 Sol-Atocha 4 stops, 6 min as the later alternative | Metro L1 ride or a Cercanias hop of a few minutes | match |
| Chamartin to Nuevos Ministerios | Metro L10, 4 stops, 6 min | Metro L10 (also Cercanias) about 5-7 min | match; no Cercanias alternative shown because the Metro arrives earlier |
| Atocha to Airport T4 | Cercanias C2 to Nuevos Ministerios then Metro 8, 31 min (1 transfer); bus 203, 47 min direct | C2 + Metro 8 about 30-35 min | match |
| Sol to Principe Pio | Metro 2 to Opera then line R, 8 min, 1 transfer; bus 46 as alternative | Metro 2 + R, under 10 min | match |
| Alcala to Chamartin | Cercanias C2, direct, 54 min | roughly 45-55 min on C2 | plausible |

Mismatches and data problems found:

1. **Interurban bus 223 (Alcala to Madrid Av. America)**: the trip in the CRTM feed covers about 28 km in 12-13 minutes
   (20 stops, e.g. 07:37 to 07:50), which is not physically plausible, and produced a bogus 13 min bus leg in one
   itinerary. Across the feed, 571 of 55,320 interurban trips (1.0%) have a straight-line average above 100 km/h (20 above
   150 km/h) and 798 (1.4%) have zero or negative duration; for "other urban" 1,513 of 14,509 trips have zero or negative
   duration (probably circular or untimed trips, cause not checked). EMT and Metro have none (measured with a separate script
   over `stop_times.txt`). A production pipeline needs a plausibility filter or a bug report to CRTM.
2. **EMT frequency semantics are unresolved.** Trips such as `FE0010011`, `FE0010021`, `FE0010031` of one line and direction
   share the window 07:00-07:59 and a 1,920 s headway, and their own first stop_time differs (07:00, 07:00, 07:25). By the
   GTFS specification all of them leave together at the window start. I followed the specification. Read literally, the
   weekday service has about 1.08 million EMT departures, far more than a city fleet can run; I could not verify against
   EMT's published timetables what the feed intends (for example one trip per bus). EMT itineraries may therefore show
   unrealistically frequent buses. Needs a check with CRTM/EMT before any claim of accuracy.
3. **Metro de Madrid feed is expired** (above) and frequency based: Metro departures are "every N seconds" inside windows,
   not timetable times.
4. Interurban/urban stop ids are shared by several CRTM feeds, so they are merged by id; Metro, Metro Ligero, Cercanias and
   buses are connected only by the 300 m walking radius and the 120 s platform rule, not by an official interchange table.
5. The planner was checked by synthetic tests and the sanity trips above. It has **not** been cross-validated against a
   reference planner (OpenTripPlanner, Navitia) on the real feeds.

## 4. What a UI would need (not built)

- Route panel: a fourth mode "Transit" next to car/walk/bike; origin/destination as today; "Leave at" time and date (the
  planner takes any epoch day and seconds; "Arrive by" is not implemented).
- Itinerary card: depart-arrive, total minutes, number of transfers, walking minutes; a row of line chips (short name on
  the route colour); expandable legs: walk (metres, minutes), ride (line chip, headsign "towards ...", boarding stop and time,
  alighting stop and time, number of stops); a "next departures" list from `planNextDepartures`.
- Line colours: the index already carries `route_color` / `route_text_color` per line (Metro, Metro Ligero, EMT, interurban
  and Renfe all provide them) and the GTFS `route_type` for an icon; fall back to a default colour per type.
- Map: highlight the selected itinerary. The index has no shapes (the feeds do: `shapes.txt` is large) so a first version
  draws straight stop-to-stop segments; size of simplified shapes in the index: *not measured*.
- Static lines layer (M6): the same index can feed it (patterns give stop sequences per line); not built.
- Attribution and honesty: "Scheduled times, no real time" label; "Powered by CRTM" / Renfe attribution in About and in the
  itinerary footer; the feed validity date; refuse to route past the index's last valid day.
- Strings: English default and Spanish translation from day one, as for the rest of the UI.
- Threading: the planner is synchronized and reuses a workspace; run queries on one worker thread.

## 5. Recommendation: GO for a Madrid-only first release, with three conditions

Why go: a full Madrid transit index builds in seconds, ships in 1.2 MB gzip (about 1% of the region download), loads in
0.1 s and answers a query in about 5 ms on a laptop CPU, with no new third-party dependency, and the sanity trips match the
network. The risky part is data quality and licensing, not the algorithm.

Conditions before shipping:

1. **Metro de Madrid data.** The published Metro feed expired on 2026-05-27 and was last refreshed in May 2025. Either
   obtain a current feed (CRTM, or NAP if it carries one: not verified) or ship Madrid without Metro-accurate dates. The
   `ignoreCalendarRange` switch used in this spike is only acceptable for testing.
2. **Data checks with the publishers**: EMT frequency semantics and the interurban implausible trips (section 3), plus the
   CRTM "always up to date" clause for an offline product (owner's call; see licence table).
3. **Freshness rule**: index valid-until date from the feeds (Renfe lasts 30 days), shown in the UI, with the app declining to
   plan after expiry.

Effort estimate for production (my estimate, not measured), Madrid first:

| Item | Hours |
|---|---|
| Data pipeline script: fetch, validate (calendar, plausibility filter, expired feed detection), build `.umti`, per-city bounding box and stop-id namespaces, run inside the weekly data release | 16-24 |
| Release/catalog: new per-region `transit` entry (url, size, sha256, `validFrom`/`validUntil`, attribution text, schema version) in `catalog.json` and `core-regions` parsing, download/verify/delete with the existing region flow | 10-14 |
| Engine hardening: arrive-by, pickup/drop-off, route-specific transfers, memory-mapped or lower-heap index for the device, validation against a reference planner on the real feeds, more tests | 24-32 |
| UI: transit mode, itinerary card, legs, line chips, map highlight, settings entry, strings, accessibility | 24-32 |
| Device measurement (cold load, query, heap), licensing notes in `LICENSES.md`, About screen attribution, docs | 8-12 |
| **Total for Madrid** | **82-114** |
| Each further city after the pipeline exists | 8-16 (feed discovery, licence, validation) |

This is above the 40-80+ hours in the gap analysis because it now includes the data pipeline, the release flow and
device measurement; the core engine itself (what this spike proves) is the smaller part.

### Proposed schema and release shape

- One index per city (or region), `transit_<region>.umti` (+ `.sha256`), built by the weekly data release from the feeds
  and published beside the `.mwm`/`.pmtiles`. Madrid: about 3.35 MB raw, 1.2 MB gzip.
- Catalog: per region an optional `transit` object `{ "url", "size", "sha256", "schema": 2, "validFrom", "validUntil",
  "attribution": ["Powered by CRTM ...", "Renfe Operadora, CC BY 4.0"] }`. `validUntil` is the minimum last service day over
  the feeds that matter (Renfe forces at most 30 days). The index file already carries the same provenance
  (`FeedSource`), so the app can show it offline.
- Rebuild weekly (cheap: 2.7 s) so the Renfe 30-day window never lapses.

## 6. How to reproduce

```
mkdir -p ~/mapas-data/transit/raw && cd ~/mapas-data/transit/raw
for p in "metro 5c7f2951962540d69ffe8f640d94c246" "metroligero aaed26cc0ff64b0c947ac0bc3e033196" \
         "emt 868df0e58fca47e79b942902dffd7da0" "interurban 885399f83408473c8d815e40c5e702b7" \
         "urban_other 357e63c2904f43aeb5d8a267a64346d8"; do
  set -- $p; curl -sL -o crtm_$1.zip "http://crtm.maps.arcgis.com/sharing/rest/content/items/$2/data"; done
curl -sL -o renfe_cercanias.zip https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip
./gradlew --no-daemon -Dorg.gradle.workers.max=2 -Dorg.gradle.jvmargs=-Xmx1536m :core-transit:test
./gradlew --no-daemon -Dorg.gradle.workers.max=2 -Dorg.gradle.jvmargs=-Xmx1536m :core-transit:transitBench -PtransitData=$HOME/mapas-data/transit/raw
```

## 7. What is built (branches `feat/transit` and `feat/transit-follow`)

What exists, verified by JVM and Robolectric tests only (nothing was tried on a device; no `adb` was used):

| Piece | Where |
|---|---|
| Itinerary model for UI and follower | `core-transit/.../Itinerary.kt`: `Itinerary`, `ItineraryLeg.Walk`, `ItineraryLeg.Ride` (line, headsign, every stop from boarding to alighting with name, coordinates and arrival/departure as absolute epoch seconds, `shape` null for now), `LineInfo` (GTFS colours or a per-mode default, readable text colour) |
| Planning with validity | `TransitService.plan(origin, destination, departAt: Instant)` returns `Found` (up to 3 itineraries, sorted by arrival; fewer distinct options are completed with the next departures), `NoRoute`, `Expired(lastDay)` or `NotYetValid(firstDay)`; the schedule is converted with the city's time zone (GTFS "noon minus 12 h" day start, so a clock-change day is right) |
| Index validity | `TransitIndex.validity()`: intersection of the feeds' calendar ranges, derived from the services of the kept trips (feeds without `feed_info`, like EMT, would otherwise look unbounded); feeds built with the range ignored are counted as unverified |
| Data build | `TransitBuildCli` (`./gradlew :core-transit:buildTransit`, wrapper `scripts/build-transit.sh MANIFEST INPUT_DIR OUTPUT_DIR [DATE]`), manifest `scripts/transit/madrid.json` (feeds, namespaces, attribution text, bounding box). Downloads nothing. Leaves out and reports expired feeds, drops non-positive-duration trips, writes `transit-<id>.umti` and `transit-<id>.json` |
| Catalog | `gen-region-catalog.py --transit-file transit-madrid.umti --transit-base URL` (repeatable) adds `"transit": [{id, city, url, size, sha256, file, validFrom, validTo, timezone, bounds, attribution[]}]`; `RegionCatalog.transit` parses and validates it (schema stays 1; old catalogs and old apps are unaffected) |
| Download | `TransitDataManager` (`:core-transit`): `ResumableDownloader` through the NetworkPolicy (`MAP_DOWNLOAD`), size and SHA-256 checked, parsed before it replaces the installed file, refused when expired; `TransitRepository` (app) lists catalog rows and installed cities, runs downloads only when the user presses Download in Maps, "Public transport" section (`TransitMapsSection`) |
| Route panel | fourth segment `profile_transit` in `RoutePanel` (only when a `TransitController` is given; `RoutingProfile` is untouched), `TransitSection`: leave now / leave at (date and 15-minute steppers), option list, itinerary card, "theoretical times" note, validity and attribution with links; `TransitController` uses an injectable `TransitClock` |
| Map | `MapEngine.showTransitItinerary(legs, fit)` (default no-op), implemented in `MapLibreEngine` (rides solid in the line colour, walking legs dashed) |
| Attribution | itinerary footer and the About dialog (`TransitAboutBlock`) show the lines recorded in the index with tappable links; `LICENSES.md` has the CRTM and Renfe rows |

### Real build of the Madrid index (2026-10-07, build date 2026-10-07, JVM, this laptop)

`scripts/build-transit.sh scripts/transit/madrid.json ~/mapas-data/transit/raw ~/mapas-data/transit/out 2026-10-07`:
5.9 s; 12,683 stops, 692 lines, 2,358 patterns, 196,396 trips, 4,662,559 stop_times; file 3,431,742 bytes (gzip -9: 1,239,993 bytes); 2,310 non-positive-duration trips dropped.
Feed ranges read from the data: Metro Ligero 2026-01-01..2027-07-22, EMT 2026-09-29..2026-12-31, interurban 2026-09-10..2027-10-10, other urban 2026-09-10..2027-10-10, Renfe 2026-10-07..2026-11-05. **Metro de Madrid: ended 2026-05-27, left out.** The index is therefore valid 2026-10-07 to 2026-11-05 (limited by Renfe's rolling window) and has no Metro de Madrid lines.
Metro Ligero shows a start of 2026-01-01 in this derivation (earlier than the feed's published 2026-07-22 version date): the start comes from its calendar, not from `feed_info`; harmless for validity (the intersection start is Renfe's 2026-10-07).

### The live step-by-step follower (built, branch `feat/transit-follow`, tried only by JVM and Robolectric tests)

Owner request (2026-10-07): "step-by-step route in public transport so I know whether I am going as expected, for buses, metro, Cercanias". "As expected" can only mean the **schedule** (there is no real-time data): you are on the planned leg, near the stop you should be at for the scheduled time, with the planned number of stops left, and the clock against the scheduled times ("about N min behind plan").

| Piece | Where |
|---|---|
| Follower (pure) | `core-transit/.../follow/ItineraryFollower.kt`: inputs the `Itinerary`, location fixes (with accuracy) and an injected clock; output `FollowState` (phase, line, headsign, next stop, stops left, scheduled times, plan offset, connection status, `canReplan`, `basis` GNSS / ESTIMATED / NO_SIGNAL) and `FollowPrompt`s. Phases: `BEFORE_START`, `WAITING`, `ON_BOARD`, `ALIGHT_NEXT`, `TRANSFER`, `FINAL_WALK`, `ARRIVED`, `OFF_PLAN`. All thresholds are in `FollowerConfig`, each documented as a design choice |
| Trip store | `follow/TransitTripStore.kt`: itinerary + progress + zone id, atomic write, 3 h expiry, corrupt or foreign files discarded; no position of the traveller |
| Trip controller | `follow/TransitTripController.kt`: owns the one trip (coroutines, ticker every second, throttled saving, `start`/`resume`/`stop`/`replan`), `TransitReplanner` is a function the app supplies |
| Voice phrases | `core-voice/.../TransitPhrases.kt` (Spanish and English), priorities and queue keys |
| Chime | `ChimeKind.TRANSIT` in `core-cameras` (`AlertSound.kt`), the same generated PCM as the camera and incident chimes |
| App model | `app/.../transit/follow/TransitTripHost.kt` (start, stop, re-plan, resume offer, mute and glove), `TransitTripSpeaker.kt` (voice / chime / silent through `AlertDeliveryPolicy`), `TransitTripSettings.kt` (`mapas_transit_trip/prompts`) |
| Screen | `TransitTripScreen.kt`: banner with the instruction, plan-versus-clock chip, signal and connection strips, Re-plan button, stops of the current line with the position marked, ETA, Stop; glove mode and night theme from `NavigationTheme`; live regions for screen readers; `TransitTripOverlay` adds keep-screen-on, show over the lock screen and the map line |
| Service | `TransitTripService.kt`: foreground type `location`, modelled on `NavigationService`; notification text from `TransitTripNotificationTexts` (headline = banner, second line = detail + plan chip); the Android 16 status-bar chip is built by `TransitTripTexts.chip` and `TransitTripLiveUpdate` (same switch as the navigation chip) |
| Entry | *Start* button (`transit_trip_start`) on the itinerary card, shown when a follower is available and the itinerary has a vehicle leg |

How a fix is matched (design choices, none measured):

- The leg's stops joined by straight lines (no shapes in the index) form a corridor of 150 m plus the fix accuracy; the fix is projected on the nearest segment inside the look-ahead window (3 segments) giving progress `stop + fraction`. Progress never goes backwards while fixes keep arriving, and skipping two or more stops needs two agreeing fixes. A stop counts as reached within 50 m plus accuracy (at most 250 m). Fixes worse than 200 m are ignored.
- Boarding: at the boarding stop (the first fix inside its radius) the phase is `WAITING` and stays so even if a fix wanders out; two fixes moving at 2.5 m/s or more, away from the stop and inside the line's corridor, mean boarded. 2.5 m/s (about 9 km/h) is above brisk walking (1.4 to 2 m/s) and below the slowest urban bus hop found on the real Madrid index (3.2 m/s); the first choice, 4 m/s, failed on that bus.
- Underground: with no usable fix for 45 s on a tram, metro or rail leg, progress is the timetable position shifted by the last known offset, flagged `ESTIMATED` everywhere (banner strip, plan chip "On plan (estimate)", prompts "by the timetable"). The estimate never completes a ride and never goes past the stop before the alighting one; the first real fix afterwards resynchronises even backwards. Buses are not extrapolated.
- Plan offset: `now - scheduled time at the matched position`, dead band 90 s ("On plan"), "About N min behind/ahead of plan" otherwise (ahead only while riding). Connection: next departure minus (projected arrival at its stop + walking time at 1.25 m/s, detour 1.3, the planner's own numbers); under 60 s *at risk*, more than 30 s negative *missed*; waiting at the stop, *missed* 120 s after the scheduled departure.
- Off plan: 4 fixes in a row outside the corridor on board, or a walk that moves 250 m further from its target than the closest it got. It clears by itself when the position fits again. **Re-plan is only a button.**

What it does not do: platform information (the index has none; "Change here" names the line and the stop), real time, shapes, a camera that follows the traveller on the map, telling two lines apart on the same street, stopping a car navigation that is running at the same time. Hooks left for later: the chip text builder is `TransitTripTexts.chip` (already plugged into the Live Update notification).

On a phone: `docs/phase2/transit-follow-checklist.md`.

### Not built / open

Arrive-by, shapes, real time, accessibility and fares; pickup/drop-off restrictions and route-specific transfers; the speed plausibility filter for interurban trips (needs the publisher's answer); the EMT frequency semantics (section 3, item 2); the licence question about "always up to date" (`docs/decisions.md`); a static transit layer on the map (M6); the weekly workflow step that downloads the zips, runs the build and passes the files to `gen-region-catalog.py` (not touched: `.github/workflows/` is the owner's); a measurement on a device.

## 8. More cities through the NAP (2026-10-08)

The catalog's `transit` array holds one index per city or metro area; the app downloads each only on request and picks the index whose box holds both ends of a trip (tighter box first). A trip whose ends belong to two indexes is reported, not planned. Configs: `scripts/transit/{barcelona,valencia,sevilla,bilbao}.json` (each feed has a `nap` file id; the data workflow fetches them with `scripts/nap-fetch.py`, header `ApiKey` from the secret `NAP_API_KEY`). Details, omissions (Zaragoza expired, EMT Valencia licence unconfirmed) and what is unverified: `docs/decisions.md`, entry 2026-10-08 "Public transport for more Spanish cities".

## 9. Cercanías real time (opt-in, 2026-10-08)
Pure part in `:core-transit` (`core.transit.rt`): `GtfsRtJson` (parser for Renfe's JSON feeds), `RealTimeRepository` (30 s rate limit, back-off, 3 min staleness, silent failures), `HttpRealTimeFetcher` (through `NetworkPolicy`, purpose `TRANSIT_REALTIME`), `RideMatcher` / `RenfeRideRealTime` (match a ride to a trip update by the feed's trip id; guards against another day's run). The index keeps Renfe's stop and trip ids in an optional trailing section (`FeedOptions.keepIds`); `ItineraryFollower` takes a `RideRealTime` and uses the real delay, departure and cancellation. App part: `CercaniasRealTime` (switch, policy host, refresh timing), `RealTimeTexts`, the card, list, follower strips and notification. Details, numbers and what is unverified: `docs/decisions.md`, entry "Cercanías real time".
