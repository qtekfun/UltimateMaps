# UI on foldable phones

Status: implemented and covered by JVM layout tests (2026-10-12). **Nothing was seen on a foldable or on an emulator**: the
owner has no foldable and the tests assert node bounds, not pixels. Use the checklist at the end on a real device.

## What it does

The map screen (search sheet, map buttons, attribution, scale bar), the navigation screen and the transit trip screen read
one rule set, `ui/fold/WindowLayout.kt`, that turns the window size and the fold into a **posture**:

| Posture | When | Arrangement |
|---|---|---|
| `PORTRAIT` | window narrower than tall and under 600 dp on its short side, or no usable fold | Bottom sheet, full-width banner and panel. Unchanged from before. Every normal phone, the cover displays, a 300 dp split-screen window. |
| `SIDE_COLUMN` | window wider than tall (the landscape rule of PR #105), or large (both sides at least 600 dp, upright or not) | Column at the start side, map beside it. Phone on its side: at most 380 dp and 46 % of the width, as before. Large window (unfolded inner display, tablet): 46 % of the width, between 360 and 440 dp. Never below 240 dp (or the window width). |
| `BOOK` | fold is half-open, or separating, or hides part of the screen, and its crease is vertical | The column fills the pane before the crease (at most 480 dp); the map controls, attribution, scale bar, speed and buttons are in the pane after it. Nothing crosses the crease or the hidden strip. In right-to-left locales the start pane is the right one. |
| `TABLETOP` | same, crease horizontal | The top pane (map) has the banner, the strips and the speed; the bottom pane has the sheet (search) or the arrival panel and the buttons (navigation, transit). The sheet cannot leave its pane: FULL is the bottom pane, COLLAPSED and MEDIUM are inside it. Content in the bottom pane is at most 720 dp wide. |

A fold that leaves one pane smaller than 260 dp (book start), 200 dp (book end), 180 dp (tabletop top) or 200 dp (tabletop
bottom) is ignored and the plain rules apply. A crease outside the window (split-screen on one half of a dual-screen device)
is ignored too. A fold that is flat and does not separate (Pixel Fold fully open) is just a large window.

## Where the pieces are

- `ui/fold/FoldPosture.kt`: `FoldFeatureInfo` (plain value: bounds in window px, vertical, half-open, separating, occluding),
  `FoldPosture` interface, `WindowManagerFoldPosture` (AndroidX WindowManager `WindowInfoTracker`, collected only while the
  activity is started), `StaticFoldPosture` (fake) and `LocalFoldFeature` (the composition local the screens read).
- `ui/fold/WindowLayout.kt`: the pure rules (`WindowLayout.compute`) and `rememberWindowLayout`.
- `ui/MapScreen.kt`: sheet as column or bottom pane; `MapScreenState.windowLayout` publishes the layout.
- `nav/NavScreen.kt`, `transit/follow/TransitTripScreen.kt`: column, tabletop and portrait variants.
- `route/RouteFraming.kt` (`RoutePadding.computeFor`) and `nav/NavCamera.kt` / `NavHost.kt` (`coveredPx`): the route framing and
  the follow camera use the free part of the map (side column and bottom pane excluded).

## Assumed sizes (420 dpi, density 2.625; derived from the pixel sizes the makers publish, rounded)

| Device state | dp (w x h) | Posture |
|---|---|---|
| Pixel Fold inner, flat | 841 x 701 | SIDE_COLUMN, 387 dp column |
| Pixel Fold inner, half-open vertical crease at x = 420 | 841 x 701 | BOOK |
| Pixel Fold inner, half-open horizontal crease at y = 350 | 841 x 701 | TABLETOP |
| Pixel Fold cover | 411 x 797 | PORTRAIT |
| Galaxy Z Fold (5, 6) inner, flat / book / tabletop | 829 x 690 | SIDE_COLUMN / BOOK / TABLETOP |
| Same display held upright | 690 x 829 | SIDE_COLUMN, 360 dp column |
| Galaxy Z Fold cover | 344 x 882 | PORTRAIT |
| Dual-screen with a 34 dp hinge that hides display | 1114 x 720 | BOOK, overlays after the hinge |
| Tablet-ish window | 600 x 960 | SIDE_COLUMN, 360 dp column |
| Split-screen, narrow | 300 x 640, 300 x 200 | PORTRAIT / SIDE_COLUMN at 240 dp, no crash |

These are assumptions to check on the real devices; the rules do not depend on the exact numbers.

## State continuity

- `MainActivity` declares `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|uiMode|density"`
  and no `screenOrientation` or `resizeableActivity=false`, so folding, rotating and split-screen resizing do **not** recreate it.
  The navigation controller, the search state, `MapScreenState` (detent), the engine and the camera all live in the activity or
  the application and are untouched. `FoldActivityConfigTest` pins these manifest facts.
- The posture changes only the arrangement: tests change the fold while the sheet is FULL and while navigating and assert the
  detent and every control survive (`FoldMapScreenTest`, `NavScreenFoldTest`).
- The route framing frames again when the layout changes, unless the user already moved the map. The follow camera adopts the new
  free area on the next navigation fix (within a second); before that it keeps the previous padding.
- If the system recreates the activity anyway (a configuration not in the list, such as a font scale change), the existing
  behaviour applies: the navigation lives in the controller and the screen comes back (`NavRecreationTest`).
- Known gaps: dialogs (About, confirmations) are separate windows centred on the whole window, so on a book they can sit over
  the crease; the map icons rendered at style load keep the density of the display they were created on (inner and cover
  displays have similar density, so this is cosmetic at worst).

## Test matrix

| Test | What it asserts |
|---|---|
| `WindowLayoutTest` | posture for every size/fold above, RTL, cutout, fallbacks, route padding, covered padding |
| `FoldMapScreenTest` | sheet, attribution, scale bar and buttons: inside the window, disjoint, never crossing the crease, in the right pane, at every detent; cover and 300 dp windows; folding while FULL keeps the detent |
| `NavScreenFoldTest` | every nav control in book, tabletop and glove mode: disjoint, inside, not crossing, banner/speed above and panel/buttons below the crease; arrival and resume cards in their pane; fold toggling while navigating |
| `TransitTripFoldTest` | the same for the transit trip screen |
| `NavCameraCoveredTest` | the follow camera uses the free area |
| `FoldActivityConfigTest` | the activity handles fold configuration changes itself |
| existing `LandscapeLayoutTest`, `NavScreenLandscapeTest`, `TransitTripLandscapeTest` | phone landscape unchanged |

Seen visually: nothing. Asserted only: everything above.

## Manual checklist (a real foldable, ideally Pixel Fold and a Galaxy Z Fold)

1. Cover display: the app looks like on any phone (bottom sheet, full-width banner). Unfold with the app open: the map and
   the sheet position stay, the sheet becomes a column at the start side (about 390 to 440 dp), nothing is cut.
2. Unfolded, flat: search for a place, open its card, start a route. List and card fit in the column, the map beside it shows the
   route framed in the free area (not under the column), buttons at the end side.
3. Half-open like a book (vertical crease): column on one side of the crease, map on the other; nothing (text, buttons, the scale
   bar, the attribution) over the crease. Drag the sheet through its three positions.
4. Half-open like a laptop (horizontal crease): map on the top half, search sheet on the bottom half; the three detents all stay
   in the bottom half. Start navigation (a simulation is enough): the maneuver banner, strips and speed on the top half, the arrival
   time, Stop and the buttons on the bottom half. The position arrow stays visible in the top half. Try glove mode.
5. During navigation fold and unfold, going through flat, book and tabletop: navigation keeps running, voice continues, no
   restart, the route and the arrow stay.
6. Rotate the unfolded device and use split-screen (also a 300 dp wide window): no crash, controls reachable.
7. Dark mode, a language written right to left (column goes to the right pane), a display cutout at the start side.
8. Report anything that crosses the crease, any clipped text, and any frame drop while folding.
