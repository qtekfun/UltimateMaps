# Test of the 0.1.0-rc.1 release APK on the Pixel 8 (2026-10-07, with the user's permission)

APK: `app-foss-release`, signed with the **debug** key only so it could be installed over the existing app (it is not the project key). Data on the phone: `World.mwm`, `WorldCoasts.mwm` and 7 `.mwm` regions (Madrid, Castilla-La Mancha, Aragón, Castilla y León Este, Lleida, Barcelona, Tarragona) and the Madrid PMTiles; copied beforehand with `run-as`. **Downloading from within the app was not tested.**

## Results (measured on the device, `UMSEARCH`, `UMROUTE`, `UMCORE` logs)

| Test | Result |
| --- | --- |
| Cold start (`am start -W`) | 474-617 ms |
| Map with labels and icons (sprites and glyphs bundled) | Works (streets, shops and POIs with icons were seen) |
| Native core startup | **Failed 2 times before working** (see below); afterwards `engine_ready_ms=215` with 7 regions |
| Search "calle mayor" (first, cold) | 20 real results, 1801 ms |
| Warm searches (full query typed at once) | 484, 779, 1045, 3967, 4201 and 3898 ms (n=6). Threshold: 100 ms → **not met** |
| Cross-region search ("placa catalunya barcelona" from Madrid) | Correct Barcelona results |
| Place card, Save / Route / Share buttons, route panel | Shown; the route panel asks for a location or a departure point |
| Route Madrid (Atocha) → Barcelona (Plaça Catalunya), car | `route_not_found` in 549 ms. **The 2 s threshold could not be measured**; no crash |

## Failures found and fixed (in `master`)

1. `CoMaps init: File not found drules_proto_walking_light.bin` (first attempt, debug test bench).
2. `SIGABRT` in `NativeCore.init` with the release build: the core wrote no log or `CHECK` message. CoMaps' log and `CHECK`s were linked to logcat (tag `UMCORE`) and this showed up: "Invalid type" for **all** categories, and then `CHECK((groups.empty() || !types.empty()))`.
3. **Cause:** `classificator::Load()` fills the classificator of the *loading style* (uninitialized, `WalkingLight`), but `classif()` queries the one of the *current style* (`DefaultLight` on Android): every type came out invalid. Fix: `GetStyleReader().SetCurrentStyle(kDefaultMapStyle)` and generate `drules_proto_default_light.bin` (before the `vehicle` style, which must go last).

## Unresolved

- **Search 5-40 times above the 100 ms threshold** (R12), with 7 regions and long queries.
- **`route_not_found` Madrid–Barcelona**: hypothesis (unverified) that the router needs neighboring regions that are not installed; in the spike, with all 25 regions, that route was computed in ≈ 18 s.
- Not tested: downloading regions from the app, saving places, importing GPX/KML, short route, smoothness (fps) with labels, memory, dark/light mode, other ROMs.
- One of my interventions went wrong in the first capture: after the app closed, Android returned the foreground to the other session's video app. My later taps were aimed at my app, but an `input text` may have reached theirs. Since then every step checks that my app is in front before sending taps or text.
