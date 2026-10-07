# 04 · Spike plan (phase 0)

**Duration:** 1-2 weeks. **Goal:** decide with data whether the project goes with A (derive from CoMaps), C (hybrid) or B (assemble). The spike does not touch the final architecture.

## Principle

The cheapest first: build CoMaps without modifying the engine and measure. If it passes the thresholds, we continue with A; if not, it is replaced.

## Steps

1. **Environment.** Android SDK and NDK, CMake, Python and Git. Clone `codeberg.org/comaps/comaps` and pin the latest stable version.
2. **Build CoMaps as is** for Android and download the map of Spain (and a small region from another continent to test the worldwide catalog).
3. **Measure** (see the thresholds table) on the devices in the matrix.
4. **Test the style:** how close its style system gets to the Apple Maps look and what is left out (checklist below).
5. **Test the required features:** lanes, speed limits, avoiding motorways and tolls, motorcycle/bike/on-foot profiles, GPX import and export, favorites, `geo:` intent.
6. **Test the core without its interface:** a minimal Compose screen that calls search and routing without the CoMaps activity. Estimate the cost of decoupling.
7. **Test without GMS:** cold-start location, voice, a 30-minute background service with the screen off, on a device without GMS and on a de-Googled one.
8. **Optional comparison (if time allows):** MapLibre Native drawing PMTiles of Spain on the same device, to compare fps and appearance. Valhalla is out of the spike.
9. **Report** with the results and the recommendation A, B or C.

## Device matrix

| Class | What to check |
| --- | --- |
| High-end with GMS (120 Hz if possible) | fps, startup, search, routes |
| Mid-range or low-end with GMS | fps, memory, long routes |
| Chinese ROM without GMS | Location, voice, background service, battery saving |
| De-Googled (GrapheneOS, LineageOS without GApps or microG) | Location, voice, installation without Play |

Assign a real device to each class before starting.

## Test routes

- Madrid–Barcelona (long, with motorways and tolls).
- Urban: central Madrid, 5 km with turns and lanes.
- Mountain for motorcycle: Sierra de Guadarrama (bends).
- On foot and by bike: a 3 km urban route.

## Thresholds and success criterion

| Metric | How to measure | Threshold to continue with A |
| --- | --- | --- |
| Map fps | `dumpsys gfxinfo` / Perfetto during pan, zoom and rotation | p95 ≤ 16.6 ms on mid-range; ≤ 8.3 ms at 120 Hz if the panel supports it |
| Cold start | `am start -W` (median of 10) | ≤ 1 s on mid-to-high-end |
| Search | Time to first results after each keystroke | ≤ 100 ms |
| Madrid–Barcelona route | Computation time on the device | ≤ 2 s |
| Style | 10-point checklist | ≥ 8 achievable |
| Decoupling the UI | Minimal screen that calls search and routing | Works without the CoMaps activity |
| Without GMS | Location, voice and background | Everything works or there is a clear path to a fix |
| Twisty routes | See whether sinuosity can be scored or passing through certain roads can be forced | There is a realistic path |

### "Apple Maps" style checklist

1. Soft background and water palette. 2. Roads with a thin border and clear hierarchy. 3. Controllable label typography. 4. Rounded, replaceable POI icons. 5. Label halo. 6. Discreet 3D buildings. 7. Optional relief shading. 8. Day/night transition. 9. Label density adjustable by zoom. 10. 3D navigation view with a tilted camera.

## Decision rule

- **A:** all thresholds pass.
- **C:** the engine (search, routing, without GMS, decoupling) passes, but style or fps do not.
- **B:** the core cannot be decoupled, or its licenses or formats block us.

## Deliverables

1. `docs/spike-informe.md` with result tables, screenshots and traces.
2. Recommendation A, B or C with the justification.
3. Revised roadmap estimate.
4. List of new risks.
5. Update of `docs/decisions.md`.

## Autonomy during the spike

Claude Code works without asking for approval to build, measure, install on connected devices and modify the spike code. It only stops for the "When to ask" conditions in `CLAUDE.md`.
