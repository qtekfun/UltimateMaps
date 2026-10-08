# Long-route A/B on the Pixel 8 (2026-10-08, owner's permission)

Tool: `CoreBenchActivity --ez matrix true --ei runs 2 --es perf <mode>` (debug build of master at PR #15), seven regions installed (Madrid, Castilla-La Mancha, Castilla y León East, Aragón, Lleida, Barcelona, Tarragona) plus `World` and `WorldCoasts`. Each pair is run twice in a row; the table shows the mean of the two. Raw logs: `route-perf-ab-2026-10-08/`. One matrix per mode, on a shared phone: single measurements, not statistics.

## Results (mean ms of 2 runs; routes compared by exact length `m=` and duration `s=`)
| Pair | baseline | `quiet` | `prune` | routes identical |
|---|---|---|---|---|
| Madrid to Guadalajara (59 km) | 884 | 870 | 1,022 | yes |
| Madrid to Medinaceli (153 km) | 1,879 | 1,861 | 2,162 | yes |
| Madrid to Zaragoza (312 km) | 7,542 | 7,485 | 9,440 | yes |
| Madrid to Lleida (461 km) | 24,104 | 23,678 | **7,786** | yes |
| Madrid to Tarragona (541 km) | 10,798 | 10,150 | 10,614 | yes |
| Madrid to Barcelona (620 km) | 15,085 | 13,024 | 14,346 | yes |
| Zaragoza to Lleida | 1,866 | 1,725 | 1,944 | yes |
| Zaragoza to Barcelona | 9,609 | 9,076 | 10,228 | yes |
| Lleida to Barcelona | 6,646 | 7,088 | 7,024 | yes |
| Lleida to Tarragona | 1,654 | 1,836 | 1,756 | yes |
| Tarragona to Barcelona | 1,936 | 1,952 | 1,898 | yes |
| Barcelona to Madrid (620 km) | 31,692 | 30,130 | 31,700 | yes |

## Findings
- **Baseline** (default, today's behaviour): all 12 pairs found. Madrid to Barcelona 15 s on this run (19 to 20 s yesterday), Barcelona to Madrid 32 s (47 s yesterday). The 2 s target for 600 km is still not met; the reverse trip is still about twice as slow.
- **`quiet`** (engine log silenced during a route): routes identical, gains within the noise except Madrid to Barcelona (-14%). Safe, small.
- **`prune`**: routes identical on all pairs. One large win (Madrid to Lleida 24.1 s to 7.8 s) and otherwise no gain, slightly slower on some pairs (Madrid to Zaragoza +25%); within what single runs on a shared phone can show, except the Lleida case.
- **`cache` crashes the app.** With `quiet,prune,cache` (`safe`) and with `cache` alone the process dies with `SIGSEGV` (null dereference, `fault addr 0x7`) in the `umbench` thread on the third route (the first route of the second pair), inside `routing::IndexGraph::GetEdgeListImpl` (symbolised with the unstripped library). Reproduced twice. The first pair, whose second route reuses the graphs, completes; the failure comes when the next pair starts, which points at the kept loader/graphs being reused across routes whose maps differ. Not diagnosed further.
- `cand*` and `tmo*` (quality-for-time options), `fast` and `safe` were NOT measured (`safe` and `fast` contain `cache`).

## Consequences
- Production is unchanged (the app never calls `setPerfMode`). **Do not enable `cache`** until the crash is fixed.
- `quiet` and `prune` are candidates for a default-on change, but the evidence is thin (one matrix per mode); repeat with `--ei runs 3` before deciding. The big remaining cost is the leaps stage; the 2 s target needs a different approach or a relaxed target (owner's decision).

## Process notes
- Run through `~/mapas-data/bench/run-matrix.sh` (holds `/tmp/pixel-device.lock`, force-stops the app at the end). Lessons: an `adb` server started inside a `flock` command inherits the lock descriptor and holds the lock forever; start it outside the lock. With the Wi-Fi entry present, use the USB serial (`-s`). `grep -m1` on a `logcat` pipe does not end the pipe until the next line arrives.
- The first baseline attempt of the session was contaminated by two overlapping matrix runs; it is not used.

## Update: the `cache` crash is fixed (patch 0003)
- **Cause:** `IndexRouter::ClearState()` runs at the end of every route and calls `MwmDataSource::FreeHandles()`, which destroys the `MwmHandle` objects. The persistent graph loader of `cache` keeps `Geometry` loaders (a `FeatureSource` and an altitude loader built on the handle) and `IndexGraph`s (which point into the mwm value) between routes, so they dangled; the next route that read them crashed (`SIGSEGV`, null dereference in `IndexGraph::GetEdgeListImpl`).
- **Fix:** `native-comaps/patches/0003-persistent-loader-keeps-mwm-handles.patch`: `ClearState()` frees the handles only when there is no persistent loader. The handles stay pinned while the loader lives (it is dropped with the router on `RefreshMaps`, after 10 minutes, or when the option is switched off).
- **Re-run on the phone** (same setup, `after-fix` logs): `cache` and `safe` (`quiet,prune,cache`) finish all 12 pairs x 2 runs with no crash, and every route is identical (length `m=` and duration `s=`) to the baseline.

| Pair | baseline | `cache` | `safe` |
|---|---|---|---|
| Madrid to Guadalajara | 884 | 614 | 780 |
| Madrid to Medinaceli | 1,879 | 1,234 | 1,476 |
| Madrid to Zaragoza | 7,542 | 6,425 | 8,282 |
| Madrid to Lleida | 24,104 | 22,712 | **6,644** |
| Madrid to Tarragona | 10,798 | 9,182 | 9,878 |
| Madrid to Barcelona | 15,085 | 12,710 | 12,900 |
| Zaragoza to Lleida | 1,866 | 1,518 | 1,423 |
| Zaragoza to Barcelona | 9,609 | 8,668 | 9,000 |
| Lleida to Barcelona | 6,646 | 5,996 | 6,077 |
| Lleida to Tarragona | 1,654 | 1,114 | 1,209 |
| Tarragona to Barcelona | 1,936 | 1,440 | 1,508 |
| Barcelona to Madrid | 31,692 | 28,920 | 31,133 |

`cache` alone saves about 10 to 30% on most pairs (the second route of a pair reuses the graphs: Madrid to Guadalajara 862 ms then 365 ms). Prune adds the large Madrid to Lleida win. The 2 s target for 600 km is still far (about 12 to 13 s forward, about 29 to 31 s in reverse).
- **Still not measured:** `cand*`, `tmo*` (quality-for-time options), `fast`; behaviour during rerouting in navigation; memory growth with the loader pinned (a long drive across many regions keeps their graphs in memory for up to 10 minutes).
