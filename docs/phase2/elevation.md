# Elevation: route profile (stage 1) and terrain tiles (stage 2 plan)

Status: stage 1 implemented on branch `feat/elevation-profile`, 2026-10-08. **Not run on a phone**: everything below was verified by JVM tests, lint and an `assembleFossDebug` build only. Stage 2 is a plan.

Source plan: `docs/phase2/data-sources.md` (rows B13 Copernicus GLO-30, B14 IGN MDT, source #3 "Elevation").

## 1. Audit

### (a) Does the CoMaps core already know heights? Yes, and it is cheap to expose.

- **The map files carry them.** The section table of `~/mapas-data/mirror/mwm/261004/Spain_Community of Madrid.mwm` (parsed by reading the 8-byte offset at the start of the file and the tag table there; nothing was copied) lists an `altitudes` section of **2,418,384 bytes** (2.4 % of the 100 MB file). Its header says version 0, minimum altitude **434 m**, which is Madrid's real lowest ground, so it holds real terrain data and not zeros. The generator takes it from SRTM (`SRTM_PATH` in `map_generator.ini`); the data repo's maps already contain it, so **no data-pipeline change and no new licence** are needed for stage 1. The same file also has an empty `isolines_info` section (7 bytes, step 0): contour lines inside the mwm are not baked by our pipeline (stage 2 below).
- **The router can load them.** `IndexRouter(vehicleType, loadAltitudes, ...)` passes the per-feature altitudes (`AltitudeLoaderCached`) to `RoadGeometry::Load`; every `RouteSegment` junction is a `PointWithAltitude`; `Route::GetAltitudes()` returns one value per route point (start junction plus one per segment), i.e. the same count as `route.GetPoly()`. A feature without data yields `kInvalidAltitude` (int16 minimum). CoMaps itself draws its elevation chart from exactly this (`libs/map/elevation_info`).
- **What our wrapper did.** `um_core.cpp` already passed `loadAltitudes = (vehicle != Car)`, so bike and foot loaded heights but the wrapper threw them away. The car loaded none (junction altitude defaults to 0).
- **Cost of turning it on.** No patch to the CoMaps submodule is needed (`Route::GetAltitudes` is public). The change is in our own files: `um_core.cpp` (`FillAltitudes`, `loadAltitudes = true` for every vehicle), `um_core.hpp` (`RouteOut::altitudes`), `um_jni.cpp` (wire). For the car it adds one cached read of the altitude section per loaded road feature during the search. **That latency was not measured** (no phone, and the CoMaps spike bench needs a device); if long car routes get slower, revert the one line to `vt != Car` and the car route simply shows no profile. Bike and foot cost nothing new.
- **Wire (backward compatible, in the style of the tunnel spans).** The plain route array `[code, distance, duration, lat0, lon0, ...]` gets an optional trailer `alt0..altN-1, N, -7777777` only when the route has heights (a latitude or longitude can never equal the marker, so a reader finds it from the end). Unknown heights are `-32768`. A route without heights is byte-identical to the old format. Because the trailer rides on the plain array, it reaches both the preview (`withGuidance = false`) and the guided route, and it does not touch `GuidanceWire` (version unchanged). Kotlin: `RouteWire` and `decodeRoute` in `CoMapsCore.kt`; a count that does not match the geometry drops the heights (no profile rather than a misaligned one) and a trailer that lies about its size is rejected.
- **Saved state and isolated core.** `RoutePlan.altitudes` (NaN = unknown, empty = none) is carried by `RoutePlanCodec` version 3 (whole metres in a short); versions 1 and 2 still read, with no heights.

### Known limits of the data

- SRTM-derived, whole metres, a few metres of noise and worse in steep or forested terrain; bridges and tunnels follow the terrain underneath (a bridge over a valley shows the valley). Hence the smoothing and threshold below, and ascent figures that are estimates (same as every offline app).
- Segments whose road feature has no height record come back invalid; the profile interpolates short gaps and is hidden when fewer than half the points have a height.
- If a region's maps were built **without** altitudes the core returns the header minimum for every point: all values equal, and the profile is hidden. Not distinguishable from a perfectly flat route, which never needs a chart.

### (b) Fallback with a DEM (not needed now; kept as the design if the mwm heights prove too coarse)

Rejected for stage 1 because (a) works. Design if it is ever needed:

- **Source and licence.** Copernicus DEM GLO-30 (1 arcsecond, about 30 m; free licence, attribution text required, see `data-sources.md` B13 and section on notices) from the AWS open-data bucket as Cloud Optimized GeoTIFF without an account. IGN MDT05 (5 m, CC BY 4.0, "(c) Instituto Geografico Nacional de Espana, CC BY 4.0") is better for Spain but the 5 m grid is hundreds of GB (estimate), so it is only useful to bake derived products (contours), not to ship.
- **Tile format.** A per-region file of 3 arcsecond (about 90 m) int16 samples in 1 degree tiles, delta-coded rows plus zstd. Estimate: mainland Spain and the Balearics need about 60 land tiles of 1200 x 1200 = 86 M samples = 170 MB raw, **about 35-60 MB compressed for all of Spain**, so a few MB per region (estimates, not measured). 1 arcsecond would be 9 times that. Published on the slower-moving `terrain-YYYYMM` release (data-sources.md section 7), referenced from the weekly catalog.
- **App sampling.** Memory-map the tile, bilinear interpolation at each route vertex (densified to about 30 m), same `ElevationProfile` afterwards. About 150 lines of pure Kotlin.
- **Why not now.** It duplicates data the maps already carry, adds a download, a licence notice, a catalog field and a CI job, and gives 90 m resolution, which is no better than SRTM in the mwm. It would only win if the mwm heights proved too noisy on real routes; to be judged with a phone test.

## 2. What stage 1 does

- `core-routing` `ElevationProfile.of(geometry, altitudes)`: pure JVM. Cumulative distance (haversine); unknown heights interpolated by distance (hidden if under 50 % valid, under 2 valid, shorter than 50 m, or every height equal); moving average over +-40 m measured in distance (the first and last point keep their real height); ascent and descent by **hysteresis** (a move counts only after the height leaves the last turning point by 3 m, so +-2 m of jitter on a flat road adds nothing, shown by a test whose naive sum is over 500 m); at most 200 samples (even by distance) with a grade per sample clamped to +-40 %; `altitudeAt(distance)`, min, max, steepest climb and descent.
- Route sheet (car, foot, bike, same code): under the time and distance, `↑ 120 m ↓ 95 m` (same text in Spanish; a 48 dp tap target); a tap opens a Canvas chart (filled line, no chart library) with the lowest-highest range, steepest grades and total length under it. Nothing at all is drawn when the route has no heights. Alternatives carry their own heights and the profile follows the selected route. Distances are metric like the rest of this panel (imperial feet are not done).
- Strings en/es (`route_elevation_*`), screen-reader descriptions on the summary and the chart.
- **Not done: the current-position marker during navigation.** It needs the navigation screen to hold the profile and the follower's distance along the route; not cheap enough for this stage. `ElevationProfile.altitudeAt` is ready for it.
- **Attribution:** no new entry. The heights are inside the mwm files the app already ships and attributes. The exact provenance and licence of the SRTM files the generator used are **not recorded in this repository**: the data repo should state them in its README (NASA SRTM is generally treated as public domain with credit requested, see data-sources.md B15, unchecked here). If a Copernicus tile is ever shipped, add its exact licence text to `LICENSES.md` and the About screen.

## 3. Stage 2 plan: contour lines and hillshade (not started)

Goal: M3 of the feature gap analysis, vector contour lines and a shaded relief under the map, offline, in the PMTiles pipeline of `UltimateMaps-data`.

### Data and products

| Product | Source | Tool | Output | Estimated size for Spain |
| --- | --- | --- | --- | --- |
| Contours every 10 m (20 m or 50 m at low zoom), with `ele` and a major flag every 100 m | Copernicus GLO-30 for the bounding box of each region (IGN MDT25 or MDT05 optional later for finer lines) | `gdal_contour -a ele -i 10` (GDAL, available as `gdal-bin` on `ubuntu-latest` runners) on a mosaic from `gdalbuildvrt` | GeoJSON-seq / FlatGeobuf, then vector tiles | 1.5-3 GB as a `contours.pmtiles` zoom 9-14 (estimate from similar open contour sets; to be measured on one region first) |
| Hillshade | the same DEM | Either (A) bake `gdaldem hillshade` into raster PMTiles (WebP), or (B) ship terrarium/terrain-RGB DEM tiles and let MapLibre's `hillshade` layer shade on the device | A: raster PMTiles; B: `terrain.pmtiles` | A: 0.6-1.2 GB (zoom 6-12); B: similar, but looks right at any pitch and zoom and lets the same tiles feed a client-side profile later (estimates) |

Recommendation: **B**, because the device does the shading (no light direction baked in, works with the dark style), the same DEM tiles can answer "height here" for tapped points, and MapLibre Native already supports `raster-dem`. Contours as vector tiles in a separate PMTiles so they can be switched off and updated monthly.

### Tooling and runner availability (to verify before building)

- `gdal_contour`, `gdaldem`, `gdalbuildvrt`: `sudo apt-get install gdal-bin` on `ubuntu-latest` (about 1 minute). Available.
- `tippecanoe`: not preinstalled; builds from source in about 3-5 minutes (or a cached binary). Handles `-z14 -Z9 --drop-densest-as-needed` for contour lines.
- `planetiler`: a single Java jar (already the kind of tool the pipeline may use for the base map); can tile GeoJSON; **not verified** whether any version has a ready contour-from-DEM profile, so tippecanoe is the safe default.
- `rio-rgbify` / `gdal_translate` + a small Python script for terrarium encoding; `pmtiles convert` (go-pmtiles) to pack MBTiles into PMTiles.
- Disk: GLO-30 for Spain is about 2 GB of COGs; a region build needs under 6 GB temporary. The weekly workflow frees disk already; elevation runs in its **own monthly job** (data-sources.md section 2, step 2).

### Cost estimate

- Data repo: DEM fetch and mosaic script, contour step, tiling step, per-region clip, catalog field `terrain`, separate `terrain-YYYYMM` release with `KEEP_RELEASES=2`: **4-6 days** (estimate).
- App: new sources and layers in the style (contours with labels at high zoom, hillshade under labels), a terrain toggle in Settings or the layers sheet, download entry per region, attribution text for Copernicus, 4-6 days (estimate), plus phone tests for performance (60/120 fps with the layers on is the risk).
- Run time: the contour and tiling step for all of Spain is estimated at 30-60 minutes on a runner, fine inside the 340-minute limit as a separate job.
- Licence work: copy the exact Copernicus notice from the licence PDF (data-sources.md says it was summarised, not read) before shipping anything derived.

## 4. What the lead must do next

1. Review the PR; run a real route with bike, foot and car on the phone (with permission) and look at the ascent against a known climb; check car routing latency on a long route (`CoreBenchActivity`) because `loadAltitudes` is now on for the car.
2. Decide whether the 3 m threshold and the 40 m smoothing window feel right on real data (constants in `ElevationProfile`).
3. Decide on stage 2 (terrain job in the data repo).
