# Bottom panel drag (3 detents)

Branch `feat/ui-regions-search-sheet`. Files: `app/.../ui/sheet/BottomSheet.kt`, `ui/MapScreen.kt`, `ui/theme/Theme.kt`.

**Honest warning: the real feel is NOT measured.** The Pixel 8 was not used. Everything below is changes reasoned from reading the code and checked with synthetic Robolectric gestures (which verify the logic: which detent, who scrolls), not how it feels under a finger. The new values are a first estimate and must be tried on the phone.

## What there was (and why it "takes a bit of effort")

| Point | Before | Probable problem |
|---|---|---|
| Drag area | Only the handle: 5 dp pill + 2×10 dp = 25 dp tall | Far below 48 dp; the rest of the header (tabs, field) did not drag |
| Touch slop | `draggable` discards ~8 dp (`touchSlop`) before the first delta | The panel trails the finger by ~8 dp for the whole gesture |
| Applying the delta | `scope.launch { animatable.snapTo(...) }` on every delta | Every event goes through a coroutine: possible one-frame delay |
| Slow release | The nearest detent (you have to pass half the gap) | To go from MEDIUM to FULL you have to drag ~625 px (≈ 240 dp) |
| Fling | Fixed 800 px/s (≈ 300 dp/s at 2.6x; differs by density) | High, density-dependent threshold |
| Lists | No `nestedScroll` | Dragging over results/lists scrolls the list; with the list at the very top the gesture "gets stuck", the panel does not go down |
| Animation | Spring damping 0.85, stiffness 400 (`MediumLow`); on release it started without the fling velocity and sometimes two animations in a row (effect + explicit) | Slow settling (~0.6 s) and with a jerk |
| Keyboard | Focusing a field moves the panel to FULL; dragging down left the keyboard and focus open | The panel and the keyboard "fight" |
| TalkBack | Only the handle's click (cycles COLLAPSED→MEDIUM→FULL→COLLAPSED) | No explicit up/down actions |

## What changes

| Point | After |
|---|---|
| Handle | **48 dp** tall box across the full width (centred pill). The collapsed height goes from 84 to **107 dp** so the visible content is the same (+23 dp) |
| Drag area | **The whole panel** (`draggable` on the column): handle, title, tabs and any area that does not scroll |
| Slop | The `touchSlop` is given back in the first delta (the panel stays under the finger). Measured in Robolectric: 150 px of finger = 150 px of panel |
| Delta | Synchronous (`mutableFloatState` read in the layout phase, without recomposing the content or per-event coroutines) |
| Slow release | If the gesture started at a detent, **25 %** of the gap towards the next one is enough (before 50 %); if it goes past half, the nearest one |
| Fling | **200 dp/s** (before 800 px/s); a fling up or down goes to the next detent in that direction, skipping none (3 detents are kept) |
| Animation | Spring damping **0.9**, stiffness **800** (before 0.85 / 400), with the **finger velocity** as the initial velocity; a single animation (the effect's one is ignored if it is already heading to the same target); no overshoot outside [collapsed, full] |
| Lists | `nestedScroll`: swiping up **raises the panel before** the list moves; down with the list at the very top **lowers the panel**; with the list scrolled, only the list scrolls. On release between two detents, the panel settles with the velocity and the list does not fling; if the panel reached FULL, the list continues with its fling. Flings of the list itself do not move the panel (decision: do not collapse the panel when returning to the top with a long fling) |
| Keyboard | When settling at a detent other than FULL by gesture, focus is cleared and the keyboard is hidden |
| TalkBack | The handle exposes custom actions "Expand the panel" / "Shrink the panel" (only the possible ones, without wrapping around) in addition to the existing click |

## Tests

- `SheetMathTest`: fling threshold (limits), the 25 % rule, a drag that overshoots, nested scroll distribution, detent neighbours.
- `SheetGestureTest` (Robolectric): handle ≥ 48 dp; drag started outside the handle; short, fast fling between the 3 detents; slow drag below/above 25 %; `swipeUp`/`swipeDown` without a list; with `LazyColumn`: up before scrolling, down with the list at the top, full-screen list, scrolled list; semantics actions.
- Test note: the framework's `swipe()` interrupted slow drags midway (the injection, not the panel), so slow gestures use `down/moveBy/up`.

## Still to measure on the phone

The 200 dp/s and 25 % thresholds, stiffness 800, 107 dp collapsed height, and whether the keyboard hides at the right moment. If anything feels wrong, the values are in `SheetMath` and `SheetMotion.SPRING`.
