# 05 · Roadmap and risks

Durations are rough part-time estimates and are revised when the spike ends. They depend mostly on the weekly hours available and on the chosen option (A, B or C).

## Phases

| Phase | Content | Estimate | Done when… |
| --- | --- | --- | --- |
| **F0. Spike** | `mapas-04-spike.md` and minimal CI on GitHub (basis for automatic merging) | 1-2 weeks | There is a report with a recommendation A, B or C and the PR checks work. **Status 2026-10-06:** report done (`docs/spike-informe.md`, provisional recommendation C); CI and remote pending on the user |
| **F1. Base** | Viewer, region manager, search, opening links, local places and lists, GPX/KML | 2-3 months (A) / 3-4 months (C, revised after the spike) | It can be used day to day as an offline viewer and search tool |
| **F2. Navigation** | Car/motorcycle/bike/on-foot routing, turn-by-turn with voice, lanes, speed limits, rerouting, **Settings screen** (voice, units, privacy; with sections reserved for F7) | 2-4 months | A real car trip completed without touching the phone |
| **F3. Motorcycle** | Avoid motorways and tolls, twisty routes, glove mode, track recording and export | 1-2 months | A real motorcycle ride with the screen always visible |
| **F4. Sync and import** | Nextcloud/WebDAV, Google Takeout import | 1 month | Two devices converge; a real Takeout imported |
| **F5. Polish and release** | Performance, style, accessibility, Chinese ROMs, F-Droid metadata, reproducible builds | 1-2 months | Published on F-Droid and GitHub |
| **F6. Android Auto** | **Dropped (2026-10-08, owner decision):** needs Google's proprietary host app, against the no-Google rule. Analysis kept in `docs/phase2/android-auto.md` | - | - |
| **F2b. Petrol stations** | Fuel prices (LPG, petrol, diesel…) downloaded according to Settings, the price over each station on the map, a card on tap and adding it to the route as destination or stop | 2-3 weeks (estimate) | With a fuel chosen, prices show on the map and you can go to a station or stop at it |
| **F8. Appearance** | Customisable map style (a lighter dark map, presets, theme choice) and cursor (shape, colour, size; generic shapes, no Google Maps artwork). Plan and open decisions in `docs/phase2/appearance.md` | To be estimated | The owner can pick a dark map he finds readable at night and a cursor; judged on the phone |
| **F7. Optional data** | Real-time public transport: **Cercanías** (the metro is dropped). Can be turned on and configured in Settings, off by default | To be estimated | It can be turned on, configured and turned off in Settings; with everything off there is no new connection |

Rough total up to F5: 6-12 months. Each phase ends with a usable version.

## Risk register

| # | Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- | --- |
| R1 | The CoMaps engine does not reach the target style or fps | Medium | High | Spike; option C |
| R2 | CoMaps data format changes and breaks versions | Medium | Medium | Pin data and engine version; own mirror of the regions used |
| R3 | Cost of generating and hosting worldwide data (if B is chosen) | High under B | High | Avoid B unless necessary; start with Spain |
| R4 | Background reliability on Chinese ROMs | High | High | Foreground service, battery guide, tests on real devices |
| R5 | No TTS engine on devices without GMS | High | Medium | Detection, installation guide and optional own voice |
| R6 | Twisty routes not supported by the engine | High | Medium | Sinuosity score over alternatives; or forced passage through certain roads |
| R7 | Takeout import without coordinates | High | Low | Resolve by link or by local search |
| R8 | License incompatibility in dependencies | Low | High | `LICENSES.md` and review when adding each dependency |
| R9 | F-Droid requirements (dependencies, builds) | Medium | Medium | Single `foss` flavor; review their policy in F5 |
| R10 | Excessive scope for one developer | High | High | Phases with a usable version each; decide A/C to reuse |
| R18 | The F7 APIs change, ask for a key or limit usage (they are third-party) | Medium | Medium | Prior spike; one interface per source; one failing must not affect the others; optional key entered by the user |
| R19 | Scope: F7 extends what "What we are not doing" excluded (public transport) | Medium | Medium | F7 only after F5; each source is optional and can be removed without touching the core |
| R11 | Inherited CoMaps licenses: bsdiff (BSD Protection), code2000 font (shareware), Entypo icons (CC BY-SA 3.0) | High if not addressed | High | Exclude or replace before release; pin GPLv3+ |
| R12 | Long route and search of the CoMaps core out of threshold | Medium (long route: threshold relaxed 2026-10-08, now met; search still open) | Medium | Long route: relaxed to tiers (see RF-04) after the World-map fix (18 s to 12-15 s); search: 0.6 s vs 0.1 s still to improve |
| R13 | Signed `countries.txt` (Ed25519) and SHA-1 per region: an own mirror requires recompiling with our key; RF-02 asks for SHA-256 | Medium | Medium | Assess when choosing the engine |
| R14 | CoMaps' fdroid flavor depends on microG `play-services-location` | High if its `:app` is reused | Medium | Consume only `:sdk` |
| R15 | Android 17: no `adb push` to `Android/data`; native MapLibre does not read `file://` there | Medium | Low | Data in `filesDir` or download by the app |
| R16 | A single test device (high-end with GMS) | Certain | High | Get a mid-range, a Chinese ROM without GMS and a de-Googled one |
| R17 | Data volume of C (≈ 5.3 GB for Spain) | Medium | Medium | Download by region; assess style with .mwm data |

## F2b and F7: optional data, detail

Decided by the user on 2026-10-07: they enter the roadmap and are **configurable in Settings**. Design detail in `mapas-03-arquitectura.md` ("Optional data sources and settings") and requirements RF-15 to RF-17.

Verified on 2026-10-07 by the study `docs/phase7/verificacion-fuentes.md` (agent D); the fuel part also by me with a real download (999 stations with LPG, 377,379 bytes).

| Source | Verified state | Approach | Still unverified |
| --- | --- | --- | --- |
| **Fuel prices** (Ministry) | Open service, **no key**, JSON or XML. National file: 12.2 MB (uncompressed), 11,499 stations. LPG field: `Precio Gases licuados del petróleo` (999 stations); also CNG (134), LNG (94), hydrogen (2), petrol 95/98, diesel A. **The service updates every 30 minutes**, not daily. Decimal comma | Download and filter locally. **Better per product** (`EstacionesTerrestres/FiltroProducto/{id}`; LPG = 377 KB): it does not reveal the location. **Do not use province or municipality filters**: they reveal where the user is | **Literal license text** (the official catalog pages return 404): see `docs/decisions.md` 2026-10-07, "Fuel license"; low risk with attribution. Usage limits: none published. There is no per-station date |
| **Cercanías (Renfe)** | Public GTFS-RT **without a key** (`gtfsrt.renfe.com`), protobuf and JSON, **CC BY 4.0**, refreshed every 20 s. Stations by GTFS `stop_id` | The real-time feed only carries each train's next stop: a full departures board also needs the static GTFS (14 MB). Reduced alternative: approaching trains and alerts | Exact Renfe attribution text; license of the protobuf library |

Study estimates (unmeasured): fuel 6-9 days; Cercanías 5-8 (full board) or 2-3 (reduced); Settings and common interface 5-7. **Metro dropped by the user (2026-10-07).**

Rules: everything through `NetworkPolicy`, off by default, with a warning on activation of what is sent and to whom, and never sending the user's location. `PRIVACY.md` and the list of possible connections are updated with each source.

## What we are not doing (for now)

Road traffic data, reviews and photos, iOS, accounts and own servers. (Real-time public transport moves to F7, optional.)
