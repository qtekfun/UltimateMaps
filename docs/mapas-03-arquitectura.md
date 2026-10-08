# 03 · Architecture

## Principles

1. **Everything on the device.** No v1 feature depends on an own server or on third parties, except downloading map data.
2. **Navigation decoupled from the UI.** The navigation engine is a service with an observable state; the UI only draws it. That way Android Auto or any future UI can be added without rewriting anything.
3. **Interchangeable engines behind interfaces.** The spike can decide A, B or C without throwing away the rest of the app.
4. **Optional GMS.** Nothing mandatory depends on Google; if it exists, it is used through routes that add no dependencies.
5. **Privacy by design.** A single network policy, centralized and auditable.

## Engine options

| | A. Derive from CoMaps | B. Assemble | C. Hybrid |
| --- | --- | --- | --- |
| Rendering | CoMaps engine | MapLibre Native + PMTiles | MapLibre Native + PMTiles |
| Search | CoMaps index | Own index (SQLite FTS5) | CoMaps core |
| Routing | CoMaps engine | Valhalla | CoMaps core |
| Data | CoMaps .mwm | PMTiles + Valhalla tiles + own index | .mwm + PMTiles (two downloads per region) |
| Control over appearance | Limited to its style system | Total | Total |
| Worldwide coverage from day 1 | Yes | No: requires own pipeline and hosting | Yes |
| Main risk | Style and fps below target | Cost of the worldwide pipeline | Double data volume and complexity |

**Decision rule** (details in `mapas-04-spike.md`): A if style and fps pass; C if the engine passes but style or fps do not; B only if the CoMaps core cannot be decoupled or its licenses or formats block us.

## Layers and modules

```
┌──────────────────────────────────────────────────────────┐
│ :app  (Compose, Apple Maps style, bottom sheet with 3    │
│        detents, settings, link intents)                  │
├──────────────────────────────────────────────────────────┤
│ :feature-*  search · navigation · motorcycle · places · sync │
├──────────────────────────────────────────────────────────┤
│ :core-nav     navigation service (observable state)      │
│ :core-map     MapEngine interface                        │
│ :core-search  SearchEngine interface                     │
│ :core-routing RoutingEngine interface                    │
│ :core-geo     link parsers, GPX/KML/Takeout              │
│ :core-data    local database (places, lists, tracks)     │
│ :core-sync    WebDAV client                              │
│ :core-net     NetworkPolicy (the only exit to the network)│
├──────────────────────────────────────────────────────────┤
│ :native  (C++ via NDK/JNI): engine chosen after the spike │
└──────────────────────────────────────────────────────────┘
```

Key interfaces (Kotlin): `MapEngine`, `SearchEngine`, `RoutingEngine`, `LocationSource`, `VoiceGuide`, `NetworkPolicy`. The navigation tests use a simulated `LocationSource`.

## Design without GMS (and with GMS when it exists)

| Function | How it works without GMS | What is used if GMS is present |
| --- | --- | --- |
| Location | `LocationManager`: on Android 12+ (API 31) with `FUSED_PROVIDER`; on earlier versions, `GPS_PROVIDER` and `NETWORK_PROVIDER`. Without the `play-services-location` library | On a phone with GMS, the system's fused provider is backed by Google, so it improves without a dependency. [verify in the spike] |
| Network location (Wi-Fi/cells) | Only with microG/UnifiedNlp; otherwise pure GPS | Better cold fix |
| Voice | System TTS engine. If there is none (common without GMS), the app detects it and guides the user to install a free one (for example eSpeak NG or RHVoice); evaluate an own bundled voice | Google TTS |
| Compass | `SensorManager` (`TYPE_ROTATION_VECTOR`) | — |
| Push notifications | Not used | — |
| GMS detection | Only via `PackageManager`, to show notices; without `GoogleApiAvailability` | — |
| Android Auto | Dropped (owner, 2026-10-08) | Needs Google's proprietary host app; see `docs/phase2/android-auto.md` |
| Purchases/donations | External link; no Play Billing | — |

Rule: **a single build flavor (`foss`) by default**. A `gms` flavor is created only if measurements justify a specific improvement.

## Navigation service and background

- Foreground service with type `location`; on Android 14+ it requires the `FOREGROUND_SERVICE_LOCATION` permission.
- Route state and position are persisted to survive process death.
- Always-on screen through the activity's window flag, not a wake lock.
- Aggressive ROMs (ColorOS, OriginOS, HyperOS, MagicOS): guided screen to exclude the app from battery saving and detection when the system kills it in the background (reference: dontkillmyapp.com).

## Data and regions

- Versioned region catalog (id, size, SHA-256 hash, data version).
- Resumable downloads by ranges, verified by hash before being activated; atomic updates (the new file is activated when it is complete).
- If the engine is CoMaps, its folder and date structure is respected (`/maps/YYMMDD/<region>.mwm`) and the server list can be changed in settings.
- Data files accessed with `mmap`; nothing is loaded entirely into memory.

## Performance (designed from the start)

- Per-frame budget: 16.6 ms at 60 Hz and 8.3 ms at 120 Hz; no allocations or GC in the render loop.
- Startup: deferred initialization of native engines; the first screen draws the map with the last saved state; Baseline Profiles and Macrobenchmark in CI.
- Measurement with `adb shell dumpsys gfxinfo <package> framestats`, `adb shell am start -W` and Perfetto traces. The spike results are stored as a baseline.

## Visual design (Apple Maps style)

- Own design system (not Material You by default): color, typography and radius tokens.
- Bottom sheet with three positions (detents): collapsed, medium and full.
- Soft palette, roads with a thin border, rounded POI icons, labels with a halo, discreet 3D buildings and animated day/night transitions.
- Glove mode: buttons ≥ 56 dp, high contrast and no fine gestures.

## Network and privacy

- `NetworkPolicy` is the only exit to the network: configurable domain allowlist, "no network" mode and a local log of connections.
- Possible connections: region download (at the user's request), online tile source (optional, disabled), short-link resolution (optional, disabled) and WebDAV sync (optional).
- TLS always; no cleartext traffic. WebDAV credentials in Android Keystore.

## Optional data sources and settings (F7)

Sources that bring data from outside the map (fuel prices, real-time public transport) are built as **small plug-ins behind a common interface**, so one can be added or removed without touching the core or the navigation:

- `OptionalDataSource`: `id`, display name, the hosts it needs, what it sends (the text for the activation warning), refresh policy, a `fetch()` that returns a result with the **date of the data**, and its own configuration.
- **Everything goes through `NetworkPolicy`**: turning a source on adds its hosts to the allow list and to the visible list of possible connections; turning it off removes them. With offline mode on, none connects.
- **Off by default.** Turning one on asks for confirmation with a plain sentence: what data is requested, from which server and what that server sees (for example, the IP address). **The user's location is never sent**: data is downloaded whole, or per chosen station, and filtered on the device.
- **Failure isolation:** if a source fails, expires or changes format, only that one stops being shown (with the date of the last good data); nothing else is affected. Disk cache with a timestamp.
- **API keys:** not embedded in the app. If an operator requires a key, the user enters it in Settings (stored in the Android Keystore), or the source is not offered.
- **Settings:** preferences live in a single local store, are read as an observable flow and go into the backup. Each source declares its fields (switch, fuel, radius, frequency, operators, URL) and the Settings screen draws them, so adding a source does not require redoing the screen.
- **Planned modules:** `:core-settings` (preferences), `:core-optional` (interface and registry of sources) and one module per source (`:source-fuel`, `:source-transit-*`). `PRIVACY.md` and the list of connections are updated with each source.

## Places, import/export and sync

- Local database (Room/SQLite) as the source of truth.
- Importers: GPX, KML/KMZ, Takeout (GeoJSON of favorites; the list CSVs only contain a title and a link, so coordinates are resolved from the link or by local search).
- Sync: WebDAV folder with GPX/JSON files per list and track; ETag to detect changes and "last write wins" per item, with a backup copy of the conflict.
