# Long-route benchmark on the Pixel 8 (2026-10-07, owner's permission)

Tool: `CoreBenchActivity --ez matrix true` (debug build only), seven regions installed (Madrid, Castilla-La Mancha, Castilla y León East, Aragón, Lleida, Barcelona, Tarragona). Live capture of the `UMBENCH` tag. One run per pair, on a shared phone: single measurements, not statistics.

## Before the fix
Every pair returned `RouterResultCode` 8 (route not found), after 13.8 s (Lleida to Tarragona), 11 to 12 s (Tarragona to Barcelona), 0.6 s (Barcelona to Madrid), 23.5 s (Zaragoza to Barcelona) and 70.7 s (Madrid to Barcelona); the first pairs of that run were lost because the engine logged 28,147 warnings ("No cross-mwm-section for CountryFile [World]") and overwrote the log buffer.

## Cause
In this CoMaps version `World` and `WorldCoasts` are leaves of `countries.txt`, and our wrapper registered them in the router's map-id table. `World` covers the whole planet, so (1) `IndexRouter::AreMwmsNear` was always true and long trips ran in the slow `Joints` mode instead of cross-mwm leaps, and (2) every neighbour query warned that `World` has no cross-mwm section.

## After the fix (`World` and `WorldCoasts` are not registered as routable maps)
| From | To | Result | Time | Length |
|---|---|---|---|---|
| Madrid | Guadalajara | found | 1.1 s | 59 km |
| Madrid | Medinaceli | found | 2.2 s | 153 km |
| Madrid | Zaragoza | found | 9.1 s | 312 km |
| Madrid | Lleida | found | 28.9 s | 461 km |
| Madrid | Tarragona | found | 13.9 s | 541 km |
| Madrid | Barcelona | found | 20.1 s | 620 km |
| Zaragoza | Lleida | found | 2.6 s | 152 km |
| Zaragoza | Barcelona | found | 14.8 s | 311 km |
| Lleida | Barcelona | found | 10.5 s | 160 km |
| Lleida | Tarragona | found | 2.4 s | 96 km |
| Tarragona | Barcelona | found | 2.8 s | 98 km |
| Barcelona | Madrid | found | 47.0 s | 620 km |

All twelve pairs are found. This is the first time Madrid to Barcelona (620 km) returns a route in the app.

## Caveats
- **This is a debug build**, including the native core (CMake Debug configuration). Times are not representative of a release build; the spike measured 17.8 s for Madrid to Barcelona with other conditions. Repeat with an optimised core before drawing conclusions.
- The target is 2 s for Madrid to Barcelona: not met in this run (20.1 s; the reverse direction took 47.0 s). No cause for the slowness or the asymmetry has been found.
- Zaragoza to Barcelona was found in 14.8 s here, after failing in 23.5 s before the fix.
