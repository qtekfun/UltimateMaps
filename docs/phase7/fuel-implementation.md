# Gas stations: data and Settings (branch `feat/fuel-data-settings`)

Covers RF-15 (data) and RF-17 (Settings screen). The map layer and the gas-station card (agent F) only consume the `:core-fuel` interfaces; **no signature of `FuelRepository`, `FuelSettings`, `FuelSettingsStore`, `FuelType` or `FuelStation` was changed** (only types and functions were added).

## Warning: literal text of the licence NOT found (low risk, covered with attribution)

> **Update 2026-10-07:** after this note, the user asked for a review of the real usage and evidence was gathered (public-transparency purpose, the Ministry's official app, apps listed on datos.gob.es and in the Play Store, the framework of Law 37/2007): see `docs/decisions.md`, "Fuel licence". **It is no longer considered a blocker**; there is still no literal licence text, and the three conditions below are applied as prudent practice.

The study (`verificacion-fuentes.md`, section 1) did not find the text of the reuse conditions of the Ministry's REST service (the datos.gob.es listings no longer exist; the citation of Law 37/2007 only appears in a third-party listing). **No licence or regulation that sets it has been read.** As a precaution the usual conditions of the apps that reuse it are followed, but that does not replace verifying it:

1. Cite the source: "Datos: Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Hidrocarburos), reutilizados conforme a la Ley 37/2007. Información no oficial; comprueba el precio en el surtidor." (Data: Ministry for the Ecological Transition and the Demographic Challenge (Hydrocarbons Geoportal), reused under Law 37/2007. Unofficial information; check the price at the pump.) (in Settings, es/en, and for the card with `FuelAttribution.text(lastUpdateMillis)`).
2. Show the date of the last download (next to the text, in Settings; the card must pass it to `FuelAttribution.text`).
3. Do not alter the meaning of the data (prices are shown as they are; "price published by the Ministry" is used, never "official").

**Before announcing or publishing the feature the licence must be clarified in writing with the Ministry** (or its reuse policy located). Meanwhile the feature ships off by default.

## What was verified with real requests (2026-10-07)

Two minimal requests to `sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/`:

- `Listados/ProductosPetroliferos/` (2.7 KB, 200, JSON, UTF-8 without BOM): 30 products with `IDProducto`, `NombreProducto`, `NombreProductoAbreviatura`. The catalogue ids (`FuelTypes`) come from there: 1 G95 E5, 23 G95 E10, 24 E25, 25 E85, 20 E5 Premium, 3 G98 E5, 21 G98 E10, 4 "Gasóleo A habitual", 5 Premium, 6 B, 16 Bioetanol, 8 Biodiésel, 17 GLP, 18 GNC, 19 GNL, 22 Hidrógeno, 26 AdBlue, 27 Diésel renovable, 28 Gasolina renovable, 29 Metanol, 30 Amoniaco, 31 and 32 Biogás. Left out is what a driver cannot refuel with: gasóleo C (heating oil, 7), fuel oils (9, 10), marine gas oil (11), aviation (12-14).
- `EstacionesTerrestres/FiltroProducto/22` (hydrogen, 1 KB, 2 stations): confirms the shape of the per-product response: `Fecha`, `ListaEESSPrecio[]` with a single `PrecioProducto` field (string, decimal comma) plus `Rótulo`, `Dirección`, `Municipio`, `Provincia`, `Latitud`, `Longitud (WGS84)`, `Horario`, `IDEESS`…, `Nota`, `ResultadoConsulta: "OK"`.

**Not verified:** the real file size for the gasoline 95 E5 and gasóleo A products (estimated 3-10 MB, the 20 MB cap covers it); behaviour under many consecutive requests; the stability of `IDEESS`; that the service keeps this shape. The parser also accepts the national shape (23 `Precio …` fields, table `FuelTypes.nationalField`) in case it changes.

## Request privacy

Each request is `<URL>/EstacionesTerrestres/FiltroProducto/{IDProducto}`: it names a fuel, never a province, municipality or coordinates (a test checks this). The server sees the IP and which fuels are downloaded. HTTPS always (`FuelClient` rejects `http` except in tests with `allowInsecure`).

## Design (`:core-fuel`, pure JVM)

| Component | What it does |
| --- | --- |
| `FuelTypes` | Catalogue with real ids and names in Spanish (product names are not translated). |
| `FuelFeedParser` | Own streaming JSON reader (no new dependencies): one station object in memory at a time. Tolerates BOM, decimal comma, empty prices, numbers instead of strings, new or missing fields and a different order; rejects truncated input, `ResultadoConsulta` other than OK and files with no usable station. |
| `FuelClient` | One request per fuel. Every hop (including redirects) goes through `NetworkPolicy.authorize`; 20 MB cap (header and stream), connection 15 s, read 20 s, total 120 s. Typed errors (`FuelFailure`). |
| `FuelCache` | One compact binary file per fuel (string table, timestamp, CRC32), written to `.tmp`, `fsync` and atomic move. A damaged file counts as "no data". |
| `FuelSnapshot` / `FuelDataRepository` | Joins by `IDEESS` the prices of several fuels into `FuelStation.prices`; 0.1° grid per fuel; `stationsIn(bounds, fuel, limit)` returns the `limit` cheapest with a heap. Immutable snapshot in a `@Volatile` field: no locks, cheap from the UI thread. `lastUpdateMillis` = the OLDEST date among the fuels served. |
| `FuelDataManager` | Settings + policy + downloads + cache. A fuel that fails does not hold back the others and keeps its last good data; retries on moving to the foreground wait 5 min after a failure. TTL = chosen frequency, minimum 30 min (the service updates every 30). |
| `FuelAttribution` | `text(lastUpdateMillis, english=false)` for the card. |

`:core-net`: `DefaultNetworkPolicy.removeEndpoint(host)` was added (there is no purpose of its own in `ConnectionPurpose`: the host is listed as `OTHER`, shown as "Otra (precios de combustible)" (Other (fuel prices))).

### When it connects (and when it does not)

- On app start: **never**. `FuelDataManager.start()` only reads the local cache and, if the feature is on, registers the host in the allow-list.
- Download: on **enabling** (after confirming), on pressing **Update now**, on **ticking a new fuel** (only that one), and with the feature on when **the app moves to the foreground** if the data exceeds the TTL (`MapasApp` counts started activities; not in offline mode and not repeated after a recent failure).
- On enabling, the host enters the allow-list and "Possible connections"; on switching off it leaves both and the data stops being served (the cache stays on disk and is re-read on re-enabling). Offline mode: zero connections (the test checks it by counting requests to the local server).

## Settings (`app/.../settings`, `app/.../fuel`)

`SettingsActivity` + `SettingsScreen` (Compose with the own design system). First Settings screen of the app; the offline-mode switch in "Maps" is the same setting (it uses `RegionsController.setOfflineMode`).

- **Privacy:** offline mode, region catalogue (address and button to "Maps") and list of possible connections with their status (allowed, disabled, blocked by offline mode).
- **Gas stations:** switch (off) with a confirmation dialog (what is requested, from which server, what it sees, that the location is not sent); fuels (multi-select); map fuel (one of the downloaded ones; if unticked, it moves to another); frequency (30 min, 1, 3, 6, 24 h); source URL (https only, reset); date of the last update, attribution text and "Update now" with progress ("Descargando 2 de 3 (…)" (Downloading 2 of 3 (…))) and per-fuel errors.
- Entry point: a discreet gear at the top left of the map, under the attribution (`SettingsGear`, `MapScreenState.onOpenSettings`); there is no fourth button in the Search/Lists/Maps row.
- `PrefsFuelSettingsStore` (SharedPreferences `mapas_fuel`), with `FuelSettings.normalized()` (unknown fuels out, map fuel within the downloaded ones, minimum 30 min, non-https URL → the default one).

**Wiring for agent F:** `(application as MapasApp).fuel.repository` (`FuelRepository`) and `.fuelSettings` (`FuelSettingsStore`); `FuelTypes.byId(settings.mapFuel)` gives the map's `FuelType`. With the feature off the repository returns empty.

**Backup:** the app has `allowBackup=false` and there is no preferences export yet, so the gas-station preferences are **not** part of any backup. Noted: when the settings export exists (RF-17), add the `mapas_fuel` file.

## Verified and not verified

Verified: full `./gradlew test` green (381 tests; 12 of data with a local HTTP server: 500, truncated, connection cut, slow, too large, redirect to an unauthorised host, offline mode, https required, host removed; 5 of the screen with Robolectric: confirmation on enabling, multi-select, URL validation, list of connections, es/en attribution).
Not verified: none of this has been tested on a device (no Pixel 8); the index build time and memory consumption with the real gasoline 95 E5 file (≈ 11,000 stations) have not been measured; the grid was tested with synthetic data; there is no real download from the app (only the two manual requests above).
