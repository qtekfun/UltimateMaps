# Gas-station test on the Pixel 8 (2026-10-07, debug APK, with the user's permission)

Chain tested against the Ministry's real service, tapping the screen (`adb input`), without the user's location:

| Step | Result | Screenshot |
| --- | --- | --- |
| Gear → Settings → Privacy: offline mode, catalogue and "Possible connections" (github and 2 download hosts allowed, 3 disabled) | Correct | 01 |
| Enable gas stations: dialog with what is requested, from which server, what it sees (IP and fuels) and that the location is not sent | Correct | 02 |
| Tick LPG and Gasóleo A → real download; choose which one is shown on the map; frequency; URL; attribution; "Última actualización: 12:49" (Last update: 12:49) and "Precios actualizados" (Prices updated) | Correct | 03 |
| Map: pump with "1,145 €" (LPG) | Correct | 04 |
| Tap the gas station: card with brand, address, hours, LPG "(en el mapa)" (on the map) and Gasóleo A, source and "no oficial" (unofficial), Go ("Ir") / Save ("Guardar") | Correct | 04 |
| Go → choose the origin by tapping the map → route to the gas station: 1.7 km, 7 min, 492 ms (`UMROUTE result=ok`) | Correct | 05 |
| Map zoomed out: several gas stations with a price; another card, now with "Añadir parada" (Add stop) | Correct | 06 |
| Add stop → "Paradas (1 de 5) · 1. REPSOL" (Stops (1 of 5) · 1. REPSOL) with up/down/remove; route recalculated 15.3 km, 39 min, 452 ms (`stops=1`) | Correct | 07 |

## Observations
- With the map zoomed out, a note "Lugar · Abierto desde un enlace de mapa" (Place · Opened from a map link) stays on top of the route panel (a bit of a nuisance; only happens after opening a `geo:` link).
- Only LPG was seen (few stations: 1-4 appear per screen in Madrid); gasoline 95 (more than 10,000 stations) was not tested, so the cost of the index and of drawing with many stations is still unmeasured.
- Not yet tested: removing/reordering stops, "Save" on the gas station, dark/light mode of the layer, background update on returning to the app, and offline mode blocking the download.

## Gasoline 95 E5 (the largest: about 11,000 stations), same session
- Real download and cache of ~0.9 MB; in Madrid at zoom 13 about 10 stations with a price appear, spaced out (below zoom 14 the cheapest of each cell is kept) (screenshot 08).
- Main process memory: 281 MB PSS before drawing it → 366 MB PSS with the layer drawn (`dumpsys meminfo`).
- `dumpsys gfxinfo`: 353 frames, 14 with jank (3.97 %), accumulated since launch (includes loading); it is not a measurement of gesture smoothness.
- **Details to polish:** (1) with the translucent bottom panel the prices underneath show through and dirty the panel's text; (2) "Última actualización" (Last update) shows 12:49 although 95 E5 was downloaded at 13:01 (probably the oldest of the fuels): it can be confusing.
