# Test on the Pixel 8 (2026-10-06, with the user's explicit permission)

APK: `app-foss-debug.apk` from `master` (viewer commit, before the native core integration, which is **not** in this APK). No sprites or glyphs.

| Test | Result | Evidence |
| --- | --- | --- |
| Install and launch without maps | Launches without failures; shows background, OSM attribution, location button and the "Aún no hay mapas" panel (no maps yet). Cold `am start -W`: TotalTime 577 ms (1st time, after install) and 478 / 482 ms afterwards | `01-sin-mapas.png` |
| Madrid PMTiles (38.8 MB) copied to `filesDir/maps` via `run-as` | Madrid renders offline, dark theme. No labels or icons: `Failed to load sprite` (sprites and glyphs missing, expected) | `02-madrid-offline.png` |
| `geo:40.4530,-3.6883?z=17` | Centers the map and drops a pin; "Lugar" (place) panel | `03-geo.png` |
| `https://www.google.com/maps/@40.4153,-3.6844,17z` | Centers on El Retiro with a pin | `04-google-maps.png` |
| `https://maps.app.goo.gl/abc123` | Notice "necesita una petición de red… está desactivado, no se ha enviado nada" (needs a network request… it is disabled, nothing was sent). Zero network by design | `05-enlace-corto.png` |

Observations: after the short link, the pin from the previous link stays on the map (the notice does not clear it): minor bug to fix. fps, memory and battery were not measured. The native core (`init`, search, route) was not tested: it requires building with the submodule and `.mwm` data, and it is not wired to the UI. Waze and Apple Maps were not tested.
