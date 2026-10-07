# UltimateMaps

An Android maps and navigation app that is **offline and private**: map, search and routes are computed on the device, with no real-time traffic, no accounts and no telemetry. It works without Google Play Services. Free software (GPLv3).

> **Status: release candidate (0.1.0-rc.3, test pre-releases).** It is an MVP under development, not a finished app. See [`CHANGELOG.md`](CHANGELOG.md) for what exists and what is missing, and [`docs/mvp-plan.md`](docs/mvp-plan.md) for the plan.

## What it does today

- Offline vector map (MapLibre Native + PMTiles), light and dark theme, OpenStreetMap attribution.
- Region downloads from the "Maps" screen (resumable, verified with SHA-256); offline mode.
- Offline search for places and addresses (core from [CoMaps](https://codeberg.org/comaps/comaps), running in its own process so a crash cannot take the app down).
- Route preview by car, on foot or by bike, with up to 5 stops.
- **Petrol stations (optional, off by default):** choose the fuels in Settings (LPG, petrol, diesel, CNG…), see the price over each station on the map, tap one to go there or add it as a stop. Prices come from the Spanish Ministry for the Ecological Transition (unofficial).
- **Speed cameras and traffic (optional, off by default, one switch per category):** fixed cameras and average-speed sections, stretches where the DGT says mobile radars may operate (never exact points), live traffic incidents and V16 beacons, on the map and as spoken alerts ahead. Data from the DGT (CC BY) and OpenStreetMap (ODbL); traffic is downloaded only when you turn it on and never with your position. Informational and possibly out of date.
- **Navigation screen with voice** (new, not yet verified on a device): turn banner, lanes, speed limit, arrival time, night and glove modes, a route simulator, and voice guidance in English and Spanish.
- Saved places and lists, with GPX and KML import and export.
- **Backup and restore of the settings** (Settings): save your preferences and the list of installed maps to a file (or everything, with your places, in one ZIP) and restore them on a new phone. No locations or credentials in the settings file; switches that start a download are not turned on by a restore.
- Opens Google Maps, Apple Maps, Waze and `geo:` links.

## What it does not do (yet)

Motorcycle profile and curvy routes, track recording, Nextcloud sync, Google Takeout import, Android Auto. Long routes can return "route not found" with few regions installed. See `docs/mapas-05-roadmap.md`.

## Data

Map data (OpenStreetMap, ODbL) is downloaded by region from [`UltimateMaps-data`](https://github.com/qtekfun/UltimateMaps-data). Only Spain for now. The app does not connect to anything until you open "Mapas" ("Maps") or download a region.

## Building

Requirements: JDK 21, Android SDK (platform 37), NDK 28.2.13676358, CMake 3.31.6, Git, Python 3 and `jq`.

```sh
git clone https://github.com/qtekfun/UltimateMaps.git && cd UltimateMaps
git submodule update --init third_party/comaps
scripts/comaps-prepare.sh            # once: ~2 GB, needs network and PyPI
./gradlew assembleFossDebug -Dorg.gradle.workers.max=2
./gradlew test                       # 267 JVM tests; they do not need the submodule
```

The native build needs quite a lot of RAM; with little, use `-Dorg.gradle.workers.max=2` and avoid LTO.

## Documentation

Start with [`docs/mapas-README.md`](docs/mapas-README.md). Decisions and their reasons are in [`docs/decisions.md`](docs/decisions.md); spike results in [`docs/spike-informe.md`](docs/spike-informe.md); how to cut a release in [`RELEASING.md`](RELEASING.md); dependency licences in [`LICENSES.md`](LICENSES.md); privacy in [`PRIVACY.md`](PRIVACY.md).

## Licence

GPL-3.0-or-later. It uses CoMaps code (Apache-2.0, compatible) and OpenStreetMap data © OpenStreetMap contributors (ODbL).
