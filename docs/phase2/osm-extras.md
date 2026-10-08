# OSM extras block (data source #2 of `data-sources.md`)

Scope of the plan: candidates B1 (opening hours), B2 (wheelchair), B4 (drinking water, toilets), C2 (level crossings) and B22 (tolls), all ODbL, all from the Spain extract the weekly workflow already downloads. This page starts with an audit of what the app already did before this work, then records what was built and what was left out.

## 1. Audit (state of master at `00a12e4`)

| ID | Candidate | Verdict | Where / evidence |
| --- | --- | --- | --- |
| B1 | Opening hours | **Partly** | The core hands over the raw `opening_hours` text (`um_core.cpp`, `FMD_OPEN_HOURS`). `:core-search` `OpeningHours` already parsed the common subset (day lists and ranges, several spans, overnight spans, `24:00`, `off`, `24/7`, rule replacement) and the card (`PlaceExtrasSection`) showed "Hours: ..." plus "Open now / Closed now / Open now: unknown". Gaps: no time of the next change ("Closes at 20:00", "Opens tomorrow at 09:00"), and any `PH`/`SH` rule (very common: `Mo-Fr 09:00-18:00; PH off`) made the whole text unknown. |
| B2 | Wheelchair | **Present** | Native code reads the feature type (`feature::GetWheelchairType`) and the card shows "Accessible en silla de ruedas / Partly / Not". Nothing to add. The renamed key `mobility:wheelchair` is whatever the CoMaps classificator understands; the app does not parse OSM tags itself. A wheelchair search filter is not available (the core search has no such filter) and was not built. |
| B4 | Toilets | **Present** | Quick chip `PlaceCategory.TOILETS` (query `toilet`, localized) already lists the nearest ones through the core category search. |
| B4 | Drinking water | **Missing as a chip, present in the core** | CoMaps' `categories.txt` has the category `amenity-drinking_water` ("Drinking Water", also localized), so the core search can already list them. No data file is needed. |
| B4 | Shelters, emergency assembly points | Missing | Not built (low demand; see section 4). |
| C2 | Level crossings | **Missing** | The classificator draws `railway=level_crossing` (type 74) but there is no warning. Needs a points file and an alert hook. Later. |
| B22 | Tolls | **Partly** | "Avoid tolls" exists (route options, default in Settings, backup whitelisted). The core does NOT report tolls on a result: `RoutePlan` has geometry, distance, duration and guidance only, and `libs/routing` only uses `RoutingOptions::Toll` as a hard exclusion mask. The existing "alternative routes" feature (`RoutePreviewController.findAlternatives`) offers an "Avoid tolls" alternative only when it differs from the main route, which tells the user a toll road is used, but only on request. |
| (layers) | Map layers | n/a | Layers exist for fuel, cameras and chargers (each with its own data file and optional catalog block built by `weekly-data.yml`: `build-cameras.py`, `build-chargers.py`, `gen-region-catalog.py --cameras-file/--chargers-file`). |

Conclusion: three of the five candidates were already (mostly) there. The genuine gaps are the opening-hours next change and holiday tolerance, a drinking water chip, toll display and level crossings.

## 2. What was built

1. **Opening hours** (`:core-search`, pure Kotlin, clock injected):
   - `OpeningHours.Schedule.statusAt(at)` gives the state and the next change within 8 days (spans that touch are merged, so `Mo-Su 00:00-24:00`-like texts and `24/7` never "close").
   - `OpeningHours.summary(text, at)` returns a language-free `Summary` (kinds `OPEN_ALWAYS`, `CLOSES_AT`, `CLOSES_ON`, `CLOSED_ALWAYS`, `OPENS_AT`, `OPENS_TOMORROW`, `OPENS_ON`, `UNKNOWN`). A closing after midnight tonight ("until 02:00") reads as today's.
   - `PH` and `SH` items are dropped from the rules and reported (`holidaysIgnored`); the card then adds "Public holidays are not taken into account." This is deliberate: there is no holiday calendar (year, region and local fiestas all matter), and the text stays visible. A text made only of holiday rules is unknown.
   - Still unknown (never guessed): months, weeks, dates, `sunrise`/`sunset`, `+`, comments, `Mo[1]`, ...
   - The card (`PlaceExtrasSection`) shows "Open now · Closes at 20:00", "Closed · Opens tomorrow at 09:00", "Closed · Opens Saturday at 10:00", times in 24 h, local time zone taken to be the phone's (as before).
2. **Drinking water quick chip** (`PlaceCategory.DRINKING_WATER`, strings en/es). No file, no layer, no setting, so nothing to download or to put in the backup whitelist.

## 3. Not built, with the cost

- **Tolls on the route summary.** The core does not report them. Options: (a) patch `IndexRouter`/`Route` in C++ to carry per-segment `RoutingOptions` flags (a third CoMaps patch, 3-5 days, must be maintained across upgrades); (b) infer it by also routing with `avoidTolls` and comparing geometry, which is what the "alternatives" feature does but costs one extra full route per query, so it stays on request; (c) a toll-gantry points file (like chargers) and a proximity check along the route (2-3 days plus the data step, but only a rough hint). Recommendation: (c) together with level crossings, as one "road features" file.
- **Level crossings and HGV/dimension limits.** Both need a points file built with `osmium tags-filter nw/railway=level_crossing`, `w/maxheight,maxweight,hgv` and an alert along the route (the cameras `AlertWarner` can be reused). 2-3 days for crossings, 6-10 for HGV warnings (tag semantics are ambiguous). Later.
- **Water/toilet map layer.** Would need a points file and a layer; the chip already gives the list through the core, so the layer is a "nice to have" (3-4 days).
- **Shelters, emergency assembly points, wheelchair filter.** No data in the core search to filter on; later.

## 4. Pipeline

Nothing was added to `weekly-data.yml` or the catalog: no new file was needed for the pieces built. If the "road features" or water layer files are built later, follow the chargers pattern (osmium `tags-filter` + `export -f geojsonseq` + a converter script in the app repo + an optional catalog block).

## 5. Verification

JVM tests: `OpeningHoursTest` (real-world strings, wrapping day ranges, overnight, holidays, summaries, week wrap; the clock is always a fixed `LocalDateTime`), `PlaceExtrasCardTest` (English and Spanish lines), `CategoryBrowseTest` (11 distinct chips). **Not verified on a phone**: the new lines on the card, the water chip against a real map (the core category name `drinking water` was read from `categories.txt`, not run), and how many drinking water points the downloaded maps actually contain.
