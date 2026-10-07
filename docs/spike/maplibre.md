# Spike (d): MapLibre Native + Spain PMTiles on Pixel 8

Step 8 of `docs/mapas-04-spike.md` (optional comparison). Discardable prototype in `spike/maplibre/`, branch `spike/maplibre`. Measurement date: 2026-10-06.

## Setup

- **Engine:** `org.maplibre.gl:android-sdk:13.6.1` (AAR from Maven Central, BSD-2, MapLibre not built from source). PMTiles supported since 11.7.0 with `pmtiles://file://<path>` (documentation via Context7).
- **Data:** `pmtiles extract` (go-pmtiles 1.31.2) from the Protomaps build `20261006.pmtiles` (planet, 138 GB, v4.15.2) with `--bbox=-9.5,35.9,4.5,43.9`: **mainland Spain + Balearic Islands, 3.4 GB** (1,114,345 tile entries, z0-15). **Canary Islands, Ceuta and Melilla not included** (the bbox was limited so as not to inflate the file). Download ~3 min.
- **Style:** `@protomaps/basemaps` 5.7.2 (BSD-3), `light` flavor, language `es`, 71 layers; sprites and glyphs (Noto Sans Regular/Medium/Italic, ranges 0-255, 256-511, 8192-8703) from `basemaps-assets`, copied to the device (no network at runtime). `tools/prep-assets.sh`.
- **App:** `MainActivity` with `MapView` in texture mode (`texture=true`, required for `gfxinfo` to see the frames) or SurfaceView. `am start` extras: `style`, `lat`, `lon`, `zoom`, `texture`, `demo`.
- **Device:** Pixel 8 (`shiba`), API 37 (`getprop ro.build.version.sdk`), 1080x2400 panel with 60 and 120 Hz modes. All adb use under `flock /tmp/claude-1000/device.lock`.

### Integration findings

1. **The native engine cannot read `file://` under `Android/data/<pkg>/files` (external storage):** `Mbgl: Cannot read file ...` for sprites and glyphs, even though Java does read the same file. With the data in `filesDir` (internal, copied with `run-as`) everything works, including the 3.4 GB PMTiles. For production: download maps to internal storage or investigate the native FileSource permissions.
2. `pmtiles://asset://` is not supported (the documentation itself says so).
3. Glyphs are requested for ranges that are not included (Cyrillic, Arabic, etc.) and fail with a log error without blocking the render; in production the 256 ranges or a fallback would have to be bundled.

## Figures

All with the whole of Spain (`style-es.json`, `es.pmtiles`), central Madrid (40.4168, -3.7038) z15, texture mode. Raw traces in `spike/maplibre/traces/`; scripts in `spike/maplibre/tools/` (`measure.sh` runs everything; `stats.py` and `cold.py` compute).

| Metric | 120 Hz | 60 Hz | Command / trace |
| --- | --- | --- | --- |
| Cold start `am start -W` TotalTime (median of 10) | **228 ms** (223-263) | 236 ms (216-255) | `am force-stop` + `am start -W`; `es-*-tex-coldstart.txt` |
| Until first complete map render (`onDidFinishRenderingMap(fully)`, `SPIKE` log) median | **936 ms** (888-970) | 890 ms (827-936; n=9, 1 run without log) | same file; from `onCreate` |
| Pan, 12 swipes `input swipe 800 1500 300 700 400` back and forth, 3 repetitions: frame time p50 / p95 / p99 | 4.4 / **7.3** / 10.4 ms (max 11.3) | 5.1 / **7.7** / 8.8 ms (max 19.5) | `es-*-tex-pan-N.txt` |
| Double-tap zoom (`input tap 540 1200` x2, 4 times), 3 rep. | 4.4 / **7.0** / 8.4 ms | 4.4 / **8.7** / 12.5 ms (max 21.0) | `es-*-tex-dtap-N.txt` |
| Zoom ±3 levels + rotation ±120° + tilt 40°, programmatic (`--ez demo true`, `easeCamera`, 8.4 s), 3 rep. | 3.9 / **6.8** / 7.9 ms | 5.6 / **8.2** / 9.7 ms | `es-*-tex-demo-N.txt` |
| Frames >16.6 ms / >8.33 ms (out of 360 per scenario) | 0 / 3-6 | 0-2 / 11-20 | `stats-120.txt`, `stats-60.txt` |
| Janky frames (gfxinfo) | 0 (0.00 %) in all | not reviewed per scenario | summary in each trace |

Frame time = `FrameCompleted - IntendedVsync` from `dumpsys gfxinfo org.ultimatemaps.spike.maplibre framestats` (last 120 frames per dump, 3 dumps per scenario = n 360). Frame interval verified: 8.33 ms (120 Hz) in the traces. The gfxinfo summary (whole interval, p95 in whole ms) agrees: p95 = 5-7 ms at 120 Hz.

Against thresholds: RNF-01 p95 ≤ 16.6 ms (60 Hz) met; p95 ≤ 8.3 ms at 120 Hz met (7.0-7.3 ms). RNF-02 (startup to interactive map ≤ 1 s): first complete render ~0.94 s at 120 Hz, just under the threshold; the window opens at 228 ms but the map appears later.

### Caveats (important)

- **Texture mode:** `gfxinfo` only sees the UI/HWUI thread. With `TextureView` every MapLibre frame goes through HWUI, but the GL render time of the map's render thread is not included in `FrameCompleted`. The figures reliably measure compositing cost and cadence, and **may underestimate** the map render cost. With SurfaceView `gfxinfo` does not record frames.
- **SurfaceView (MapLibre's default mode): not measured.** `dumpsys SurfaceFlinger --latency "SurfaceView[...](BLAST)#id"` only returned the refresh period (no per-frame marks) on this Android; no alternative was found in time. Perfetto was not tried.
- **Rotation and zoom by touch gestures: not measured.** `adb input` does not generate multitouch; zoom and rotation were measured with double tap (real gesture) and programmatic `easeCamera` (not touch). Pan is real touch.
- 60 Hz temporarily forced with `settings put system peak_refresh_rate 60` / `min_refresh_rate 60` and restored afterwards (`es-*-tex-env.txt`); `min_refresh_rate` did not exist and was deleted at the end.
- Cold start without flushing the OS page cache (no root): PMTiles reads may be cached. A single (high-end) device; mid-range not measured (no device).
- Memory, battery and routes not measured (out of scope for this prototype).
- Comparison with CoMaps: **there are no CoMaps figures in this branch**; this table is the MapLibre column of the comparison.

## Appearance

Screenshots (1080x2400) in `spike/maplibre/traces/`: `madrid-z12-es.png`, `madrid-z15-es.png`, `madrid-z17.5-es.png`.

Protomaps `light` style: soft gray-beige palette, roads with clear hierarchy and thin casings, buildings as footprints (no 3D extrusion in the default style), POIs with rounded per-category icons (colors: shopping blue, food orange, culture pink), labels with halo in Noto Sans and street names along the path, dotted railway/metro visible. Labels in Spanish. Vector with smooth rotation/tilt. Compared with the "Apple Maps" checklist: soft palette, halo, road hierarchy, replaceable icons (own sprite), density by zoom and tilted view are achievable; 3D buildings, relief and day/night require additional layers/style (not tested). The assessment against CoMaps is left for the final report when comparing screenshots.
