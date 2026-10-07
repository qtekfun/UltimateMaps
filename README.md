# Mapas

An Android maps and navigation app that is **offline and private**: map, search and routes are computed on the device, with no real-time traffic, no accounts and no telemetry. It works without Google Play Services. Free software (GPLv3).

> **Status: release candidate (0.1.0-rc.1).** It is an MVP under development, not a finished app. See [`CHANGELOG.md`](CHANGELOG.md) for what exists and what is missing, and [`docs/mvp-plan.md`](docs/mvp-plan.md) for the plan.

## What it does today

- Offline vector map (MapLibre Native + PMTiles), light and dark theme, OpenStreetMap attribution.
- Region downloads from the "Mapas" screen ("Maps", resumable, verified with SHA-256); offline mode.
- Offline search for places and addresses (core from [CoMaps](https://codeberg.org/comaps/comaps)).
- Route preview by car, on foot or by bike (no turn-by-turn guidance yet).
- Saved places and lists, with GPX and KML import and export.
- Opens Google Maps, Apple Maps, Waze and `geo:` links.

## What it does not do (yet)

Turn-by-turn navigation with voice, lanes and speed limits, motorcycle profile and curvy routes, track recording, Nextcloud sync, Google Takeout import, Android Auto. See `docs/mapas-05-roadmap.md`.

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
