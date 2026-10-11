# Speed-camera data file and how the weekly data release builds it

Status: 2026-10-07 (enforced direction from OSM relations added 2026-10-11, section 6). The converter and the reader are tested on the JVM and with real downloaded data (counts below). The
weekly workflow of `qtekfun/ultimate-maps-data` now has an optional step for it (added afterwards, outside this repository; it skips itself while `scripts/build-cameras.py` is not on the default branch of this repository): this document is the
instruction for whoever changes it. Anything marked "not verified" was not confirmed.

## 1. What the file is

`speedcams-es.bin`: one small binary file next to the maps in the weekly release, listed in `catalog.json` in an optional
`cameras` block (same shape as the `base` assets: `url`, `size`, `sha256`, `file`). The app downloads it only when the user
turns a camera switch on (never at start-up), verifies the SHA-256, and works without it when the block or the file is absent.

It holds, from public sources only:

| Record | Source | Notes |
|---|---|---|
| Fixed camera (point) | DGT "Radares fijos" (CC BY) and OpenStreetMap `highway=speed_camera` (ODbL), merged | position, road, enforced limit if OSM has `maxspeed`, a rough road direction |
| Average-speed section (segment) | DGT (CC BY) | start and end point, road |
| Mobile-radar **zone** | DGT report "Puntos y tramos de control de velocidad" | a road and a kilometre range, exactly as published; a drawable line only when the kilometre points can be placed (see 4) |

Not in it, ever: crowd-sourced reports, police controls, exact positions of mobile devices.

## 2. Binary layout (version 1, big endian)

```
u32 magic 0x554D4341 ("UMCA")   u16 version (1)   i64 generated (epoch seconds)   u8 source flags (1 = DGT, 2 = OSM, 4 = some fixed record has a direction derived from an OSM enforcement relation)
u32 n_fixed   u32 n_sections   u32 n_zones
fixed   x n_fixed:    i32 lat_e6, i32 lon_e6, u8 src, u8 maxspeed (0 = unknown), i16 axis (degrees 0..359, -1 = unknown), u8 sense, utf road
section x n_sections: i32 lat_e6, i32 lon_e6, i32 lat_e6, i32 lon_e6, u8 src, u8 maxspeed, utf road
zone    x n_zones:    utf road, utf province, i32 km_from_m, i32 km_to_m, u16 n_points, n_points x (i32 lat_e6, i32 lon_e6)
u32 crc32 of everything before it
```

`utf` = u16 length + UTF-8 bytes (what Java's `writeUTF` reads for BMP text). `sense`: 0 both directions, 1 only traffic
travelling along `axis`, 2 only against it; bit `0x80` of that byte marks a direction derived from an OSM enforcement
relation and bit `0x40` that the axis too came only from it (section 6). Layout version stays 1: an older reader maps any
value above 2 to "both", so it keeps reading the file and treats such a camera like an axis-only one. The reader (`:core-cameras`, `CameraFile`) rejects as a whole anything with a bad
checksum, size, count, magic, version, position or trailing bytes. It is tested against a file written by the Python
converter (`core-cameras/src/test/resources/cameras/sample.bin`, regenerated with
`UM_WRITE_CAMERAS_SAMPLE=<path> python3 scripts/test_build_cameras.py`).

## 3. How the weekly workflow should call the converter

`scripts/build-cameras.py` needs only Python 3 and, for the report, `pdftotext` (poppler). It does not download anything.
Sketch of the steps to add to the weekly job (do not copy blindly; paths are examples):

```sh
# DGT NAP, CC BY (all plain HTTPS, no key; the DATEX files are served gzip-compressed)
curl -sSL --compressed -o radares.xml   https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml
curl -sSL --compressed -o situations.xml https://nap.dgt.es/datex2/v3/dgt/SituationPublication/datex2_v37.xml
curl -sSL --compressed -o camaras.xml   https://nap.dgt.es/datex2/v3/dgt/DevicePublication/camaras_datex2_v37.xml
curl -sSL --compressed -o vms.xml       https://nap.dgt.es/datex2/v3/dgt/DevicePublication/vms_datex2_v37.xml
# DGT report (PDF): take the link from https://www.dgt.es/conoce-el-estado-del-trafico/vigilancia-y-control/equipos-y-tramos-de-vigilancia/
# ("Puntos y tramos de control de velocidad (radares)"). The file name changes with the date (INFORME_CINEMOMETROS_WEB_OK_<yyyymmdd>_extra.pdf).
curl -sSL -A "Mozilla/5.0" -o informe.pdf "<link from that page>" && pdftotext -layout informe.pdf informe.txt
# OSM: from the Spain extract the pipeline already has
osmium tags-filter spain.osm.pbf n/highway=speed_camera -o cams.osm.pbf
osmium export -f geojsonseq cams.osm.pbf -o cams.geojsonseq

scripts/build-cameras.py --dgt-radars radares.xml --dgt-report informe.txt \
    --dgt-anchors situations.xml camaras.xml vms.xml --osm-geojsonseq cams.geojsonseq \
    --generated "$(date -u +%Y-%m-%dT%H:%M:%SZ)" -o speedcams-es.bin --summary speedcams-summary.json

# then, in the step that writes catalog.json, add the optional block:
scripts/gen-region-catalog.py ... --cameras-file speedcams-es.bin --cameras-base "$RELEASE_BASE_URL"
# and upload speedcams-es.bin as one more asset of the release.
```

Rules for the job: treat a failed DGT download or a converter error as "no camera file this week" (leave the block out; the
app copes) rather than failing the whole release; keep the `speedcams-summary.json` in the release notes (counts); never
publish a file with fewer records than the previous one without looking (a sudden drop means a broken source).
The Overpass API is not needed: it timed out for a whole-country query, and the pipeline has the `.osm.pbf` anyway.

## 4. What was measured on 2026-10-07 (real data)

| Item | Result |
|---|---|
| DGT "Radares fijos" DATEX II v1 | 2,051,128 bytes; 690 point booths + 47 average-speed sections = 737; `publicationTime` 2025-12-18 although the dataset page says it is updated hourly and shows "last update 18 Sep 2026": **stale**, treat as not verified |
| DGT report PDF (3 Aug 2026) | 2,158 rows after `pdftotext -layout`: 707 fixed, 63 average-speed sections (126 rows, start and end), **1,325 mobile-radar rows** (road + kilometre range + province), no coordinates; excludes the Basque Country and Catalonia |
| Match of report and DATEX | 563 of 707 report fixed radars match a DATEX point (same road, same kilometre within 20 m); 144 do not (17 newer than the stale DATEX file, many on regional roads): **dropped, not guessed** |
| Anchors for placing kilometre points | 6,923 (road, km) -> coordinates from DGT incident, traffic-camera and variable-message-panel feeds plus the radar points; 787 distinct mobile-radar roads, **554 of them have no anchor at all** (mostly regional `CM-`, `CV-`... roads) |
| Interpolation error (leave-one-out on the 690 DATEX points) | placeable 364; median 104 m, 75th percentile 314 m, 90th 670 m, 95th 972 m. With a gap of 1 km or less between anchors: median 38 m, 90th percentile 342 m. Too coarse for a "camera ahead" alert, so report-only fixed radars are not placed; fine for a rough zone line |
| Result: fixed cameras | **2,608** (690 DGT booths + 2,526 OSM nodes, 608 merged within 100 m), 1,643 with a road axis, 2,236 with an enforced limit (from OSM); 47 average-speed sections |
| Mobile-radar zones | 1,325 in the file; only 25 can be drawn (both ends between anchors); the other 1,300 are stored as text (road, province, kilometres) |
| OSM `highway=speed_camera` | 2,526 nodes inside Spain from Overpass in 3-degree tiles restricted to the ES area (a whole-country query timed out; 5 ocean-only tiles never answered; "at least", not a verified total); 28 dropped as mobile or disused |
| Output size | **80,526 bytes** for everything above; the analysis estimate of under 150 KB holds |

Direction: the DATEX file has none (`tpegDirection` is always `unknown`); the report gives "Creciente/Decreciente" (kilometre
points increasing/decreasing), which the converter joins to the booths it can match and combines with a local road bearing
computed from neighbouring anchors (the farthest-apart pair within 2 km, at least 100 m apart). That bearing is **derived, not
published**: it only separates a parallel road from a crossing one. OSM `direction` is used as an axis for both directions
because the OSM wiki says only "the way the speed camera faces" (checked 2026-10-11), not which traffic it enforces (a
front-facing camera watches traffic travelling against `direction`, a rear-facing one along it). Merging an OSM node into a DGT
booth keeps the DGT axis and sense (before 2026-10-11 the OSM axis overwrote them with "both").

What is lost: a DGT booth that is not in the report (or not matched within 20 m) has no sense at all, and every OSM-only
camera has "both" with an axis. Those cannot be told from the camera of the opposite carriageway by direction; the app
uses their offset from the route instead (see `cameras-implementation.md`). OSM `type=enforcement` relations (`from`, `to`,
`device`) carry the monitored direction explicitly; since 2026-10-11 the converter can use them (section 6), needing one more
extract step in the weekly job, under the same ODbL attribution as the nodes.

## 5. Licence and attribution

- DGT: the NAP dataset pages say "Creative Commons Attribution". The exact version and the DGT legal notice
  (https://www.dgt.es/contenido/aviso-legal/) were **not verified**; the report PDF's own licence was not read.
  Attribution shown: "Data: Dirección General de Tráfico (nap.dgt.es), CC BY."
- OSM: ODbL 1.0; the derived camera database keeps attribution and share-alike. Attribution shown only when the file
  contains OSM records (source flag 2).
- Where it is shown: Settings (Speed cameras and traffic), the card of every marker, `LICENSES.md`, `PRIVACY.md`.

## 6. Enforced direction from OSM `type=enforcement` relations (optional, experimental, 2026-10-11)

**What the OSM data says.** A relation `type=enforcement` + `enforcement=maxspeed` has members with roles `device` (the camera
node), `from` and `to` (the way or node the monitored traffic comes from and goes to; the direction of travel is from `from`
towards `to`) and optionally `force`, `via`. Other `enforcement=` values (traffic lights, average speed) are ignored.
(Source: OSM wiki `Relation:enforcement`; the roles' exact semantics were **not** re-verified against current mapping practice.)

**What the converter does** (`scripts/build-cameras.py --osm-enforcement FILE...`; the input is an OSM XML file or an Overpass
JSON that contains the relations *with* their member ways and the nodes of those ways):

1. Per relation, `from` way + `to` way that share a node which is an END of both: the bearing is taken from a point up to 50 m
   along the `from` way, to the shared node, to a point up to 50 m along the `to` way. `from` node + `to` node: the bearing
   between them (at least 8 m apart). `from` = `to` = the same way: only when the way is `oneway` (`yes`/`-1`), using its node order.
   Anything else (not connected, junction inside a way, a way and a node, a member missing from the extract, a way shorter than
   8 m) is dropped and counted under `enforcement_unresolved` in the summary, by reason.
2. Per device node: several relations that agree (within 30 degrees) give one direction; two groups that are opposite give
   "both directions"; anything else is dropped (`device_directions_disagree`).
3. The device is matched to the camera node by OSM id (Overpass JSON) or by exact position (an `osmium export` geojsonseq has no
   ids by default).
4. The direction is written into the camera's `axis` and `sense`, relative to the axis it already had (the OSM `direction` or the
   DGT road bearing): along when the travel bearing is within 45 degrees of it, against within 45 degrees of the opposite; when
   it is neither, the relation is not used (`relation_axis_conflict`). A camera that had no axis gets the travel bearing as axis
   and sense along, flagged `0x40` as well. "Both" keeps sense 0 (flagged `0x80`).
5. A DGT sense that came from the report is **never** overridden: it is published data. How often the relation disagrees is
   counted (`relation_disagrees_with_dgt`).

The summary adds `enforcement_relations`, `enforcement_maxspeed_relations`, `enforcement_resolved`, `enforcement_unresolved`
(by reason), `enforcement_devices_with_direction`, `enforcement_devices_matched_to_camera_nodes`,
`enforcement_cameras_with_relation_direction`, `enforcement_cameras_relation_both`, `fixed_with_relation_direction`. **No count on
real data exists yet** (no extract was available when this was written; the tests use small synthetic extracts).

**Weekly job change (outside this repository; do not edit `.github/workflows` here).** In the step that already downloads
`spain.osm.pbf` and runs `osmium tags-filter ... n/highway=speed_camera`, add, before `rm -f spain.osm.pbf`:

```sh
# relations come with their member ways and nodes (osmium's default: referenced objects are kept)
{ osmium tags-filter spain.osm.pbf r/type=enforcement -o enforcement.osm.pbf --overwrite \
  && osmium cat enforcement.osm.pbf -f osm -o enforcement.osm --overwrite; } || rm -f enforcement.osm
[ -s enforcement.osm ] && ARGS="$ARGS --osm-enforcement enforcement.osm"
```

The ready patch against `.github/workflows/weekly-data.yml` of `qtekfun/ultimate-maps-data` is
`docs/phase2/weekly-data-enforcement.patch` (checked with `patch --dry-run` against the job as of 2026-10-11; apply with
`git apply` or `patch -p1` from the root of that repository, review it, commit it there, and read the new
`enforcement_*` counts in the next `speedcams-summary.json` before enabling the app setting). The step is optional: without the argument the file is exactly what it was. The argument is new, so
the job must run the converter of the release that has it (the job checks out the app repository's default branch).

**In the app** the data is used only when "Use the enforced direction from OpenStreetMap (experimental)" is on (default off, see
`cameras-implementation.md`). The flagged records are in the file either way, so switching it on later needs no data release.

**Attribution and network.** The relations are OSM data (ODbL 1.0): the OSM credit already shown with the cameras covers them
(source flag 2 is set for these cameras). The app does not download anything new; it is the same file, from the same place,
through `NetworkPolicy`.

**Test files.** `core-cameras/src/test/resources/cameras/sample-enforcement.bin` is written by
`UM_WRITE_CAMERAS_ENFORCEMENT_SAMPLE=<path> python3 scripts/test_build_cameras.py EnforcementTest`.
