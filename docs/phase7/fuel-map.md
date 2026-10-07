# Gas stations on the map and route with stops (RF-15) — branch `feat/fuel-map-route`

Date: 2026-10-07. It only consumes the `:core-fuel` interfaces (`FuelRepository`, `FuelSettingsStore`); the data and Settings belong to agent E.

## What is there

**Map layer** (`app/.../map/FuelMapLayer.kt`, `FuelIcons.kt`, `MapLibreEngine`)
- `FuelMapLayer` (pure Kotlin, tested on the JVM) decides what is drawn: the engine notifies when a camera gesture ends (`MapEngine.setViewportListener`, never per frame); after 250 ms without changes it queries `stationsIn(bounds, mapFuel, limit)` on a background thread and calls `render` only if the result differs from what is already drawn.
- Minimum zoom 11; per-view limit by zoom (80/150/250/400); below zoom 14 the cheapest of each 48 dp cell is kept (clustering). Off, with no fuel chosen, or below the zoom: nothing is drawn and, if nothing is drawn, it is not even queried.
- A change of `mapFuel`, of `enabled` or of the repository's `lastUpdateMillis` recomputes. Changes to other settings (e.g. refresh minutes) do not.
- MapLibre: `GeoJsonSource` + `SymbolLayer` (pump icon drawn with Canvas once per style load + label "1,154 €"). The label yields to collisions (`text-optional`), the icon is always visible. Collision order by price (`symbol-sort-key`).
- Cheapest (10 % of the view, between 1 and 5, never all): larger icon (38 dp versus 28), double ring and a "pointing down" triangle, larger label. They do not depend on colour alone. Light/dark theme: icon, text and halo colours according to the theme when the style loads.
- Tap: `queryRenderedFeatures` in a 48 dp square; the nearest wins; if a saved marker/pin/user marker is right under the finger (14 dp) and no gas station, the tap is not a gas-station tap. While choosing the route origin, tapping a gas station picks it as the origin.

**Card** (`app/.../fuel/FuelStationCard.kt`, `FuelCardController.kt`): brand, address, municipality, opening hours, prices of all downloaded fuels (the chosen one first and in bold, "(en el mapa)" (on the map)), and under them always the attribution "Fuente: Ministerio para la Transición Ecológica y el Reto Demográfico · actualizado hace…" (Source: Ministry for the Ecological Transition and the Demographic Challenge · updated … ago) and "Precio publicado por el Ministerio; no oficial. Compruébalo en el surtidor." (Price published by the Ministry; unofficial. Check it at the pump.). The attribution lives in **a single function** (`fuelAttributionText`) and in `strings_fuel_map.xml`: replace with `FuelAttribution.text(lastUpdateMillis)` from `:core-fuel` when E integrates it. Buttons (≥ 48 dp): **Go** ("Ir"; new route, no stops), **Add stop** (only with an active route; if rejected, the card says why), **Save** (reuses `PlacesService` through `PlacesController.toggleSaved(info)`). The card is also shown over the route panel.

**Route with stops** (`RoutePreviewController`, `RoutePanel`): list [departure, stops…, destination]; `addStop` (before the destination), `removeStop`, `moveStop(i, ±1)`; recomputes each time; maximum 5 (`MAX_STOPS`). `StopResult`: `ADDED`, `DUPLICATE`, `SAME_AS_DESTINATION` (within ≤ 30 m), `LIMIT`, `NO_ROUTE`. The distance and time shown are the totals for the whole route. The core's `INTERMEDIATE_NOT_FOUND` is now `RouteError.STOP_NOT_FOUND` with its own message (before it was "route not found"; that case in `RoutePreviewControllerTest` was updated). UMROUTE: `route profile=car stops=2 ms=… result=ok` (`stops=` only if there are stops; just a number).

## Does `CoMapsCore.route` pass `via` to the native core?

Yes. `CoMapsCore.route` builds `[from] + via + [to]` in a `DoubleArray`; `um_jni.cpp` passes it as is to `Core::Route`, which requires ≥ 2 points and creates `routing::Checkpoints(all the points)` for `IndexRouter::CalculateRoute`, which routes leg by leg between checkpoints. **There is no need to chain legs.** Not verified on a device (no phone): the time of a route with long stops may grow with each leg (a Madrid–Barcelona leg was already risk R12).

## Interfaces added (outside my strict ownership, minimal)

- `:core-map` `MapEngine`: `showFuel(List<FuelPin>)`, `setFuelTapListener`, `setViewportListener`, plus `FuelPin` and `GeoBounds` (all with an empty default implementation).
- `app/build.gradle.kts`: one line, `implementation(project(":core-fuel"))` (E probably adds the same one: trivial conflict).
- `PanelHost`: parameters `fuelRepository` (default `NoFuelData`), `fuelSettings` (default `StaticFuelSettings`, off) and `fuelName` (id -> name). **E must pass their real implementations and the fuel name** (`FuelType.displayName`). `FuelType(id, displayName)` is built here with `displayName = id` because the settings only hold the id; the repository must look up by `id`.
- `PlacesController.isSaved/toggleSaved(info)`; `SheetPanel(fuel = FuelCardHost?)`.
- No `:core-fuel` signature was changed.

## Tests (JVM and Robolectric; 174 in `:app` + `:core-map` + `:core-fuel`, 0 failures)

`FuelMapLayerTest` (11: label and order, cheapest, off/no data without a query, minimum zoom, limit by zoom, fuel change, switching off clears, no redraw if unchanged, debounce, new data, clustering), `FuelStationCardTest` (10: tap → card with all prices and attribution, order, ≥ 48 dp, Go, Add stop, duplicate, same as destination, Save, origin by tap, unknown card), `RouteStopsTest` (8: no route, `via`, duplicate, destination, limit, remove/reorder, new destination, unreachable stop, log) and `RoutePanelTest` (list, totals, up/down/remove).

## Not measured / not verified

- Real appearance (icons, sizes, label collisions, contrast in light/dark), smoothness when zooming and panning, and the cost of `setGeoJson` with 400 stations: **not measured** (no Pixel 8 or adb). The MapLibre layer (`SymbolLayer`, `switchCase` expression, `queryRenderedFeatures`, `addImage` with `bitmap.density`) is only **compiled**, not run; the icon's pixelRatio depends on `bitmap.density` and could come out at double or half the size.
- The 48 dp touch targets on the map are checked by construction (48 dp square), not with a finger.
- The "Noto Sans Medium" font of `textFont` exists in the assets; that the `€` (U+20AC, range 8192-8447) is drawn has not been seen.
- The "unofficial" text and the attribution follow the coordinator's instructions; review the legal wording.
