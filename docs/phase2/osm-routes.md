# OSM hiking and cycling routes (data source #4 of `data-sources.md`)

An optional map overlay of the marked hiking, cycling and mountain-bike routes of OpenStreetMap, built from the same Spain extract as the rest of the weekly data. Off by default; turning it on downloads one file. Inspired by Waymarked Trails (a viewer; none of its code or rendering is used, and the data is plain OSM, ODbL).

## 1. Design

### 1.1 Does the base map already have them?

No. The Protomaps basemap schema (layers `boundaries`, `buildings`, `earth`, `landuse`, `natural`, `places`, `pois`, `roads`, `transit`, `water`) carries ways, not route relations: a waymarked GR appears only as the plain `path`/`track` roads it runs on, with no name, ref, network or colour. The app style (`scripts/gen-map-style.mjs`) has no route layer either. Nothing is duplicated.

### 1.2 How the data is built (the cheapest way that fits the repo)

| Option | Verdict |
| --- | --- |
| Separate small PMTiles (tippecanoe or planetiler) | Rejected. tippecanoe is not in the GitHub runner image (it would be built from source, BSD-2 licence, 5+ minutes), planetiler needs a JVM and a profile. The app would also need a second vector tile source with its own installation and cache. The overlay is small enough to hold in memory, and MapLibre can draw a GeoJSON source directly. |
| GeoJSON per region | Rejected. Text, no tap metadata structure, 5-10x bigger. |
| **Compact binary of simplified polylines, one file for Spain (chosen)** | `osmium` (apt, already installed by the cameras and chargers steps) + a 450-line pure-Python converter with no dependency, the same pattern as `build-cameras.py` and `build-chargers.py`. The app queries it by viewport and hands MapLibre a GeoJSON source of at most 30 000 points. |

Pipeline in `weekly-data.yml` (optional, `continue-on-error`, skips itself while `app/scripts/build-routes.py` is missing):

1. `osmium tags-filter spain.osm.pbf r/route=hiking,foot,bicycle,mtb` keeps the relations and, by default, their member ways and nodes.
2. `osmium export -c export.json -n -u type_id --geometry-types=linestring -f geojsonseq` for the ways (`export.json` = `{"linear_tags": true, "area_tags": false}` so closed ways, which are loops, stay lines; `-n` keeps untagged member ways; ids come out as `w<id>`), and `osmium cat -t relation -f opl` for the relations (members and tags).
3. `scripts/build-routes.py` joins them: classifies each relation, chains the member ways end to end, simplifies with Douglas-Peucker (15 m default), delta-codes the coordinates as zigzag varints, deflates the body, adds a CRC32.
4. `gen-region-catalog.py --routes-file/--routes-base` adds the optional catalog block `routes` (`url`, `size`, `sha256`, `file`), one national file (not per region: routes cross regions, and one file keeps the catalog and the client simple).

What is kept (`classify`): `type=route` with `route=hiking|foot|bicycle|mtb`, not proposed/disused, and
- hiking and foot: network `lwn/rwn/nwn/iwn` (local, regional, national, international); without a network only when it has a name or ref (local);
- bicycle: network `rcn/ncn/icn` only (`lcn` city cycle networks are mostly bike lanes and would drown the map);
- mtb: any of `lcn/rcn/ncn/icn`, or no network with a name or ref (local).

Per route the file stores kind, level, length (the `distance` tag when valid, else measured on the simplified lines, flagged), name (or `from - to`), ref, operator, and the lines. Colour and `osmc:symbol` are not stored: the overlay colours by reach, and the symbol images cannot be drawn without a renderer for them (decision in `decisions.md`).

### 1.3 File layout (`routes-es.bin`, big endian, version 1)

`u32 magic "UMRT"`, `u16 version`, `i64 generated`, `u32 n_routes`, `u32 raw_length`, `deflate(body)`, `u32 crc32` of everything before it. Body per route: `u8 kind` (0 hiking, 1 cycling, 2 mtb), `u8 level` (0..3), `u8 flags` (bit 0: length from the tag), `u32 length_m`, three `u16`-prefixed UTF-8 strings (name, ref, operator), `u16 n_segments`, per segment a varint point count, then zigzag varints: the first point's lat and lon in millionths of a degree, then deltas. The reader (`:core-routes` `RouteFile`) rejects anything damaged, truncated, from another version, over 40 MB packed or 120 MB unpacked, or with out-of-range values, as a whole.

### 1.4 Sizes

Measured on a real sample (Overpass `relation[route~hiking|foot|bicycle|mtb]` over a 6 000 km2 box around Cantabria, 29 MB of JSON, 287 relations seen, 225 kept after filtering; local `build-routes.py` run, Python 3.14, taken 2026-10-08, stored in `~/mapas-data/bench/routes`):

| Tolerance | Points | File |
| --- | --- | --- |
| 12 m | 65 970 | 237 KB |
| 15 m (default) | 56 863 | 208 KB (238 KB before deflate) |
| 25 m | 40 318 | 156 KB |

Extrapolation to Spain (506 000 km2, 84 times that box, by area): about **13 MB at 25 m, 17 MB at 15 m, 20 MB at 12 m**. Cantabria is mountainous and well mapped, so it is probably denser than the national average; a range of **8-20 MB** is the honest estimate, to be replaced by the real figure after the first workflow run (`routes-summary.json` is printed in the log). Bounded by the reader at 40 MB. Overlapping relations (a GR over several PRs) are stored twice; deduplicating shared ways would save an unknown share and cost a more complex file, so it is left.

Memory on the phone: 17 MB packed is about 60-80 MB unpacked and about 2 million points in `IntArray`s (about 16 MB) while the overlay is on; nothing is held while it is off.

Not measured: the full pipeline with `osmium` (not installed on the dev machine) and the real Spain extract; the CI time (estimate 5-10 minutes after the extract is on disk: `tags-filter` 2-3 minutes, `export` of about a million ways 2-3 minutes, Python 2-5 minutes and about 2-3 GB of RAM).

## 2. App side

- `:core-routes` (JVM): `RouteFile` reader, `RouteRepository` (viewport query: kinds chosen by the user, levels by zoom, only the parts of lines that touch the view plus a 25 % margin, thinned when zoomed out, at most 30 000 points, highest levels first when trimming), `RouteSettings` (off by default; hiking and cycling kinds), `RouteDataManager` (same download rules as the camera file: nothing at start, nothing while off, `NetworkPolicy`, SHA-256 check, atomic cache; in the background only when the cached file is over 30 days old, because the file is several MB; "Update now" always downloads).
- `TrailMapLayer` + `TrailStyle` + `MapLibreEngine.showTrails`: lines under the tracks, the route line and the pins. Colour by reach (purple local, green regional, orange national, red international), long dashes for walking and dots for cycling (not colour alone), a translucent casing for contrast on both themes, widths 1.2-4 dp. Nothing below zoom 7; far out only national and international.
- Tap: a route line within 32 dp of the finger opens `TrailCard` (name or ref, kind and reach, reference, operator, length, community-data note, attribution, data date) with "Route to the start". Pins, chargers and cameras win over a route line; a route line wins over the generic map tap (place card). Ignored while navigating; while picking a route origin it is that origin.
- Settings: group "Hiking and cycling routes" inside Navigation and voice (switch, kinds, legend, data date and count, Update now, attribution). Backup: `trails/enabled` (needs consent, like the other download switches), `trails/hiking`, `trails/cycling`; the coverage test knows the new preference file.
- Attribution: the OSM line is already on the map; the card, Settings and this document add the trail wording.

## 3. Not built, with the cost

- Route detail with elevation and a "follow this route" navigation along the trail (the routing core is not asked to follow a relation): 4-6 days.
- Waymarked-style symbols (`osmc:symbol`) and route colour from the `colour` tag: 2-3 days for generated icons; low value next to the level colour.
- A tile (PMTiles) variant for whole-country display at low zoom without the 30 000 point budget: only if the in-memory approach shows jank on a phone.
- Per-region files: not needed at this size.

## 4. Verification

Python (`scripts/test_build_routes.py`, 16 tests): classification of every network and kind, distance parsing, simplification, chaining in both directions, OPL parsing, a round trip through the real writer and a reference reader, determinism, checksum failure, the OPL + GeoJSON-seq input path. Catalog block in Python and Kotlin. JVM: `RouteFileTest`, `RouteRepositoryTest`, `RouteDataManagerTest`, `TrailMapLayerTest` (also the style tables), the settings backup and coverage tests. **Not verified on a phone**: drawing, taps, the frame time with a full-Spain file, the card layout.
