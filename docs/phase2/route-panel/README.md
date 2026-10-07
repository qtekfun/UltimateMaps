# Route panel redesign: renders

Renders of `RoutePanel` produced on the JVM with Robolectric native graphics and `captureToImage`
(`app/src/test/kotlin/com/qtekfun/mapas/route/RoutePanelRenderTest.kt`, skipped unless `RENDER_ROUTE_PANEL` names the
output directory; `RENDER_PREFIX` is the file-name prefix). Phone width 411 dp at xxhdpi, light and dark theme.

**Not measured:** the real look and feel on a device (fonts, density, touch comfort, glove mode, animation). Nothing
here comes from a phone; these are renders of the Compose tree on the JVM.

Regenerate: `RENDER_ROUTE_PANEL=$PWD/docs/phase2/route-panel RENDER_PREFIX=after ./gradlew :app:testFossDebugUnitTest --tests '*RoutePanelRenderTest*'`

## After (`after-*-light.png` / `after-*-dark.png`)

| File | State |
| --- | --- |
| `after-ready-car` | Route ready, car. One selector (segmented control), one primary action (Start), quiet Simulate, options collapsed ("None"). |
| `after-ready-car-options-open` | Same, with "Route options" expanded: four filter chips (outlined, tinted with a check when on). |
| `after-ready-car-avoiding` | Collapsed, with tolls and ferries on: the header reads "Avoiding: tolls, ferries". |
| `after-ready-two-stops` | Two intermediate stops as rows of the from/to card, with move up / down and remove. |
| `after-walking-options-open` | Walking, two stops: car-only chips (motorways, tolls) disabled; the summary only counts what applies to walking. |
| `after-computing` | Computing: the status text takes the result's place and Start is off. |
| `after-error` | "No route found..." error: reason visible, Start off. |
| `after-needs-origin` | No location: reason shown, "Use my location" appears on the From card. |
| `after-picking-origin` | Picking the start: hint, search slot (empty here) and Cancel. |

## Before (`before-*`, from commit 0590fb9)

`before-ready-car`, `before-ready-two-stops`, `before-walking-options-open`, `before-error`, `before-picking-origin`:
Start, Simulate, Change start, Use my location, the three modes and the four avoid toggles were all the same grey pill
button at the same level.
