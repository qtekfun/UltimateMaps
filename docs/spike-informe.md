# Spike report (phase 0)

Date: 2026-10-06. Author: Claude Code (autonomous technical lead). Detailed sources: `docs/spike/comaps-build.md`, `comaps-code.md`, `comaps-style.md`, `maplibre.md`, `verificaciones.md`. Code and traces in `spike/`.

## Summary

- **There is no firm A/B/C decision.** Critical data is missing (without GMS, decoupling at runtime, device classes) and two thresholds of the CoMaps engine **are not met** (search and long route). By the spike's own rule and by `CLAUDE.md` (changing the already decided option requires asking), I do not proceed to Phase 1.
- **Provisional recommendation: C (hybrid)**, conditional on the checks in the "What is missing" section. Justification below.
- Hardware: **a single device**, Pixel 8 (Android 17, 120 Hz, with GMS). Everything measured is "high-end with GMS".

## Figures against thresholds

Everything measured on the Pixel 8 at 120 Hz, with command and trace (see the per-workstream reports). "Not measured" = no data, not a failure.

| Metric | Threshold | CoMaps as is | MapLibre + PMTiles | Verdict |
| --- | --- | --- | --- | --- |
| fps / frame time in gestures | p95 ≤ 8.3 ms (120 Hz) | Interval between SurfaceFlinger buffers: p95 8.85-8.88 ms, 119-120 fps, 0-0.16% of intervals > 12.5 ms (`traces/07..`) | `gfxinfo` p95 6.8-7.3 ms (120 Hz), 0 frames > 16.6 ms | Both fluid. **Metrics not comparable** (CoMaps: SF proxy, MapLibre: UI thread with TextureView, may underestimate). The literal CoMaps p95 is 0.5 ms above |
| Cold start | ≤ 1 s | 141 ms (Splash, median of 10); ≈ 650 ms to `MwmActivity` (n=5) | 228 ms (median of 10); first full render ≈ 0.94 s | Both pass |
| Search per keystroke (first batch) | ≤ 100 ms | median 631 ms, p95 1734 ms (n=31); 1st cold 6705 ms (no complete trace) | not applicable (no index) | **Fails.** Measured with the shared device and the whole of Spain; **not repeated at idle** |
| Madrid–Barcelona route, car | ≤ 2 s | 16.5-17.4 s (agent) and **17.8 / 18.0 / 18.0 s at idle** (`traces/20-rerun-route-quiet.txt`), 620.6 km | not applicable | **Fails (≈ 9x), reproducible without load** |
| Urban routes (informative) | — | car 6.7 km 0.43 s; foot 5.1 km 1.3 s; bike 5.4 km 1.15 s; Guadarrama 13.8 km 0.67 s | — | Acceptable |
| Style (10-point checklist) | ≥ 8 achievable | 5 yes, 4 partial, 1 no (relief); 3 of the partial ones require Drape C++. Code reading only, no screenshots | Protomaps light style: soft palette, road hierarchy, rounded POIs, halo (screenshots in `spike/maplibre/traces/`). 3D, relief and day/night not tested | A: ≥ 8 only counting partials, **not demonstrated**. MapLibre: full control, partially evidenced |
| Decoupling the UI | Compose screen without the CoMaps activity | **Viable by code reading**: `:app` and `:sdk` already separated in Gradle; cost 15-21 person-days. **Not executed** | — | No runtime proof |
| Without GMS | Everything works or a clear path | CoMaps' fdroid flavor depends on `org.microg.gms:play-services-location` (`app/build.gradle.kts:375`), contrary to `CLAUDE.md`; consuming only `:sdk` avoids it (`LocationManager`). **Not tested** on a device without GMS | — | **Not measured** |
| Twisty routes | Realistic path | Yes: virtual `EdgeEstimator::CalcSegmentWeight`, only increasing costs; 6-10 days of C++ | — | Passes (by code) |
| Motorcycle | — | There is no motorcycle `VehicleType`: new estimator and router, 5-8 days | — | Own work |
| Avoid motorways/tolls | — | Exists as a hard exclusion. "Avoid motorways" gave 691 km / 11 h 25 min after ~3-4 min of computation (screenshot not saved); "avoid tolls" gave the same route as without avoiding | — | Partial |
| Lanes and speed limit | — | Only confirmed in code; simulated navigation failed (it used the real location) | — | **Not measured** |
| Memory / APK | — | PSS 503 MB; arm64 APK 46.3 MB | PMTiles mainland Spain + Balearics 3.4 GB (vs 1.9 GB of .mwm) | Informative |

## Licenses (from `verificaciones.md`)

- The CoMaps code is Apache-2.0, compatible with GPLv3 (we must pin "GPLv3 or later", never GPLv2-only).
- Three items **block publishing as is**: `3party/bsdiff-courgette/bsdiff` (BSD Protection License, GPL-incompatible), the font `06_code2000.ttf` (shareware, not free, NonFreeAssets) and the Entypo icons (CC BY-SA 3.0). They can be excluded or replaced without touching the routing/search engine, but they cost work. `gb-postcode-data` (GPLv2) only matters if we generate GB maps; `kdtree++` (Artistic) has no license file. Submodule licenses were taken from `copyright.html`, not read in each submodule.
- Data: OSM under ODbL with attribution. No terms of use were found for the CoMaps CDN: we must ask the project before setting up a public mirror.

## Recommendation: C (provisional)

**Why not A:** not all thresholds pass (route 9x, search 6x), the style depends on touching Drape C++ to reach 8/10, and the fdroid flavor drags in a microG dependency.

**Why not B (yet):** B's rule (core cannot be decoupled, or blocking licenses/formats) is not triggered: decoupling is viable by code and the problematic licenses are excludable. B would cost the worldwide pipeline (R3), with no Valhalla figures at all.

**Why C:** MapLibre draws Spain fluidly (p95 ≈ 7 ms, startup 0.23 s) with full control over the style, and the decoupling of CoMaps (search + routing + worldwide data) is assessed as viable.

**Open weakness of C (important):** C reuses the CoMaps routing and search core, precisely what **does not meet** the thresholds. The spike's rule for C requires the engine to pass; here it does not. Before committing we need to know whether the 18 s and 0.6 s are structural or are due to measuring with all of Spain loaded cold (for example with only the necessary regions), or whether the 2 s threshold will have to be relaxed for 600 km routes (a product decision, not mine). If it turned out to be structural and the threshold non-negotiable, the alternative is B with Valhalla, unmeasured.

## What is missing (blocks a firm decision)

1. Search at idle and with a subset of regions (untested hypothesis).
2. A device without GMS and another de-Googled one: cold-start location, voice, 30-minute background service.
3. A mid-range device for the mid-range thresholds.
4. Decoupling executed: minimal Compose screen on top of `:sdk`.
5. CoMaps screenshots to compare the look with MapLibre (there are none).
6. Lanes and speed limits with a simulated route.
7. Measurement of SurfaceView in MapLibre and of real multitouch.

## Revised roadmap estimate

Person-days from the code study (estimates, not measurements): decoupling 15-21 d; with motorcycle (5-8 d), twisty routes (6-10 d), own map server and `NetworkPolicy`: 40-58 d. For C, add the integration of two engines and two downloads per region (≈ 5.3 GB for Spain with PMTiles 3.4 GB + mwm 1.9 GB). Phase 1: 2-3 months → **3-4 months** part-time. The remaining phases are not modified without the firm decision (see `docs/mapas-05-roadmap.md`).

## New risks (added to the roadmap, R11-R17)

R11 inherited licenses (bsdiff, code2000, Entypo); R12 CoMaps route and search latency 6-9x above threshold; R13 `countries.txt` signed with Ed25519 and SHA-1 per region: an own mirror requires recompiling with our key and does not meet RF-02 (SHA-256) without changes; R14 CoMaps' fdroid flavor with a microG dependency; R15 Android 17 prevents `adb push` to `Android/data` and the MapLibre engine does not read `file://` there (data goes in `filesDir`); R16 a single test device; R17 data volume of C (≈ 5.3 GB for Spain).

## Git state

Everything is on local `master` (squash of the `spike/*` and `feat/core-geo-skeleton` branches; 83 unit tests green, repeated in the consolidation). **Nothing pushed**: `origin` is empty and there is no CI or branch protection (see `docs/decisions.md`).
