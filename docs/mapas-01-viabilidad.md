# 01 · Feasibility study

Date: 2026-10-06. External data was checked on that day; items marked [verify] must be confirmed during the spike.

## Verdict

**It is feasible**, with one condition: the difficulty is not in the app but in the data and the navigation engine. With everything on the device and no traffic data, the legal problems with Google and Waze disappear and the privacy story stays clean. What remains is:

1. Getting a good map, search and routing engine that works offline on a phone.
2. Having worldwide data, downloadable by region, and kept up to date.
3. A carefully designed, fluid UI on top.

## What is clearly viable

- **Offline vector map with its own style.** Two routes: the CoMaps engine (C++) or MapLibre Native with PMTiles/MBTiles tiles.
- **Offline routing on the phone.** There are two serious engines: the CoMaps one (on top of its map files) and Valhalla (hierarchical tiles with car, motorcycle, bike and pedestrian profiles).
- **Opening map links.** `geo:` is trivial; Google Maps, Apple Maps and Waze links can be parsed (see RF-11). Short links require following a network redirect, so that will be an optional setting.
- **Lists, GPX/KML and WebDAV sync.** Well-known, low-risk work.

## What is hard

| Area | Why it is hard | Mitigation |
| --- | --- | --- |
| Worldwide data | Generating and hosting the planet's map, routing and search data is a separate project | Reuse the CoMaps data in the spike; decide afterwards |
| Offline search | Open sources do not ship a mobile-ready index except the CoMaps one | Use the CoMaps one or build our own (SQLite FTS5) |
| Turn-by-turn navigation | Voice, rerouting, lanes, speed limits and background reliability | Reuse the CoMaps or Valhalla logic; tests with simulated routes |
| Twisty routes (motorcycle) | No candidate engine ships it out of the box | Own profile or a sinuosity score over alternatives (evaluate in the spike) |
| 60/120 fps with a polished style | Heavily loaded styles lower the fps | Measure in the spike and design the style with performance in mind |
| Phones without GMS | Services that are taken for granted are missing (TTS, network location, aggressive battery saving on Chinese ROMs) | See `mapas-03-arquitectura.md`, section "Design without GMS" |

## Open data sources

| Option | What it covers | License / terms | Notes |
| --- | --- | --- | --- |
| **CoMaps (.mwm)** | Map, search and routing, whole world | Code Apache-2.0 [verify]; OSM data (ODbL) | Download by region from several nodes of their CDN. They publish tools to set up your own server by copying their files. New maps may be incompatible with old versions of the app, so we depend on their pace of change |
| **Protomaps (PMTiles)** | Visual map only | Tiles: ODbL (derived product of OSM); styles: BSD-3; the PMTiles specification is public domain | Daily planet build (138.4 GB on 28/09/2026). They only keep the builds of the last week, so we would need our own mirror. The CLI extracts regions |
| **OpenFreeMap** | Tiles only | MIT (the project) | Offers no search or routing. Valid as an optional online source and as a style reference |
| **Valhalla** | Routing (and map matching) | MIT | There are mobile apps already using it with per-region tiles. I did not find free pre-generated planet tilepacks (Interline sells them by subscription), so we would have to generate them ourselves |
| **Mapterhorn** | Relief (Terrarium in PMTiles) | See its documentation | Optional, for hillshading and elevation profiles |

## Legal and privacy

- **OSM is free (ODbL).** It can be used, including commercially, with the visible attribution "© OpenStreetMap contributors" and sharing derived databases. It is compatible with a GPLv3 app.
- **OSM tile servers:** their usage policy reserves them for light consumption; they are not suitable for an app with mass downloads or offline use. They will not be used.
- **CoMaps code:** Apache-2.0 is compatible with being incorporated into a GPLv3 project [verify when setting up the repo and record in `LICENSES.md`].
- **Google, Waze and Apple:** out of scope as providers (no traffic data). Only their links (deep links) are interpreted.
- **F-Droid:** requires free dependencies in the base flavor: no Play Services, no Firebase, no proprietary SDKs.

## Estimates (rough, to be revised after the spike)

- Phase 1 (viewer, downloads, search, links, lists): 2-3 months part-time.
- With decent navigation, motorcycle, sync and polish: 6-12 months. It depends mostly on the weekly hours and on whether we start from CoMaps or from scratch.

## Main risks

1. **Style and fps with the CoMaps engine** (if it does not reach the Apple Maps look or 120 Hz, we switch to C).
2. **Coupling to the CoMaps format** (incompatible changes between map versions).
3. **Cost of the worldwide data** if we had to generate and host it ourselves.
4. **Background reliability** on Chinese ROMs and devices without GMS.
5. **Importing Google Takeout:** saved lists (CSV) usually contain only a title and a link, with no coordinates [verify with a real export]. Starred favorites do include coordinates in GeoJSON.

## Sources consulted (2026-10-06)

- CoMaps, own map server: https://www.comaps.app/support/how-can-i-host-a-custom-map-server-for-downloads/
- CoMaps, configurable download URL: https://codeberg.org/comaps/comaps/issues/41
- CoMaps, releases: https://codeberg.org/comaps/comaps/releases
- Protomaps, downloads: https://docs.protomaps.com/basemaps/downloads
- Protomaps, getting started: https://docs.protomaps.com/guide/getting-started
- Protomaps, styles and licenses: https://github.com/protomaps/styles
- OpenFreeMap: https://github.com/hyperknot/openfreemap
- Valhalla on mobile: https://github.com/valhalla/valhalla/discussions/4746
- Valhalla tilepacks (Interline): https://www.interline.io/valhalla/tilepacks/
