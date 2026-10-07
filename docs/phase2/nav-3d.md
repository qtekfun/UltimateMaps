# Navigation 3D view

Branch `feat/nav-3d`. Requested by the user after using the navigation screen on a phone: "a 3D view like Google Maps". **Nothing here has been seen on a device**: MapLibre cannot render in Robolectric and no phone was used. Read "What is and is not verified" before trusting any number.

## What it does

- **3D follow camera** (default): the map is tilted, points where the user is heading ("course up") and sits behind the user so the position marker is in the lower third of the screen and the road ahead fills the middle. Zoom adapts to speed and to the next maneuver.
- **2D/3D toggle** on the navigation screen (label "3D"/"2D", highlighted when 3D, content description that says the state and the action, at least 48 dp, 56 dp in glove mode). The choice is saved in the navigation settings (`NavSettings.view3d`, `PrefsNavSettingsStore`, key `view_3d`) and is also a switch in Settings, Navigation ("3D view", default on).
- **Route overview** button ("Route"): frames what is left of the route for 8 s, then goes back to following with an ease. Touching the map or "Recenter" ends it earlier.
- **3D buildings** (optional): `fill-extrusion` layers while navigating in 3D, from zoom 15. A separate switch in Settings, Navigation ("3D buildings", default on, only offered while the 3D view is on). **Its cost on mid-range phones is NOT measured**: if the map stutters, turn it off.
- **Heading arrow**: while navigating the position dot becomes a blue chevron with a white outline, flat on the map plane, rotated by the course (the plain dot comes back when navigation ends).
- **Route line**: width interpolated with zoom (3 dp at z10, 6 at z14, 10 at z17, 16 at z20) plus a casing 4 dp wider (white in the light theme, near-black in the dark one), drawn above the buildings.

## Design

| Piece | File |
| --- | --- |
| Decisions (pure): zoom, tilt, bearing, padding | `app/.../nav/NavCameraPlanner.kt` |
| Throttle and the plan placed at the user | `app/.../nav/NavCamera.kt` (existing, extended) |
| Map side effects, transitions, overview | `app/.../nav/NavHost.kt` |
| 2D/3D state, overview state, settings link | `app/.../nav/NavScreenController.kt` (`NavUi.view3d`, `buildings3d`, `overview`) |
| Toggle and overview buttons | `app/.../nav/NavScreen.kt` |
| Engine contract | `core-map/.../MapEngine.kt`: `CameraPadding` in `CameraState`, `setUserHeading`, `setBuildings3d`, `frameRoute` |
| MapLibre implementation | `app/.../map/MapLibreEngine.kt`, `Buildings3d.kt`, `UserArrowIcon.kt` |

`NavCamera` keeps its old role (when is it worth moving the camera) and now delegates WHERE to `NavCameraPlanner`, a pure class whose only state is the last bearing. Inputs: speed, distance and type of the next maneuver, 2D/3D, screen height, course. Output: zoom, tilt, bearing, padding.

### Parameters and why

- **Pitch 55 degrees in town, up to 60 at 25 m/s or more.** MapLibre Native 13 allows up to 60 by default (`MapLibreMap.setMaxPitchPreference` exists and the engine sets 60 explicitly; `CameraState` accepts up to 85, but beyond 60 the horizon eats the screen and tile loading grows). 55 shows about 150 m of road at z17 without turning the map into a skyline; at speed more look-ahead is worth more than a clear foreground. Near a roundabout the tilt drops up to 12 degrees so the whole circle is visible.
- **Zoom by speed** (m/s, zoom): 0 to 17.5, 5 to 17.25, 14 to 16.5, 25 to 15.75, 33 or more to 15.25, linear in between, then quarter steps (no jitter). Tilted views already show further ahead, so they can be closer than the flat view for the same speed. The flat 2D view keeps the old law: 17.5 standing, 15.0 at 30 m/s.
- **Approaching a maneuver:** the zoom grows up to the peak as the distance falls from `far` to 50 m, where `far` = 8 s of driving, limited to 150-300 m (the speed makes it start earlier). Peak: roundabouts +1.0, sharp turns, U-turns and arrival +0.75, normal turns and exits +0.5, slight turns +0.25, straight, merge and depart 0. After the maneuver the next one is far (or there is none) and the zoom eases back by itself through the normal animation. Maximum zoom 18.5.
- **Bearing:** the route bearing at the user (smoother than the GNSS course, already what `NavState.bearingDegrees` is), smoothed with half of the shortest angular difference per update, so a 180 degree glitch moves 90 degrees and comes back, a real 90 degree turn is followed in about 3 updates, and 359 to 1 is a 2 degree turn. Below 1 m/s (or without a heading) the last bearing is kept: a stopped receiver reports a random course.
- **Padding:** only `top` (no bottom: the bottom panel simply covers the map). The camera centre is the middle of the padded area, so `top = H x (2 x 0.72 - 1) = 0.44 H` puts the marker at 72 % of the screen height in 3D (lower third starts at 66.7 %, the bottom panel starts around 85 %). In 2D the marker sits at 62 %. The banner (about the top 20 %) leaves the stretch between 20 % and 72 % free for the road ahead.
- **Throttle (unchanged):** at most one move per 0.8 s and only for 6 m, 4 degrees, 0.25 zoom or 3 degrees of tilt. New: a switch 2D/3D or a change of padding goes through immediately, because it is a deliberate change.
- **Transitions:** 1.2 s ease into the view when navigation starts, when the user recenters after panning and when 2D/3D is switched; 0.9 s ease back to flat north-up (centre and zoom kept, tilt and padding to zero) when navigation stops or on arrival; 0.88 s for normal follow updates (slightly less than the interval so it flows). Panning stops following as before.
- **Buildings:** layer `mapas-3d-buildings-<source>` per vector source `protomaps-<i>` (one per installed region), `source-layer` `buildings` (the same the flat layer uses), kinds `building` and `building_part`, height from the `height` property or 9 m, base from `min_height` or 0, soft colour (`#D8D4CC` light, `#3B3E45` dark), opacity 0.92, no vertical gradient, minzoom 15. They are inserted below the first symbol layer of the style: above all roads (a building hides the road behind it), below labels, icons and the app's own layers, so the route line, saved markers, pin and position stay on top. They are added at runtime (`setBuildings3d(true)`) only while navigating in 3D and removed afterwards, and re-added when a style reload drops them (day/night, new region), so the normal style and its layer count never change (`MultiRegionStyleTest` still holds).
- **Marker arrow:** a second layer on the same user source (symbol, `icon-rotation-alignment` and `icon-pitch-alignment` set to `map`, rotation from the feature property `bearing`), visible only while a heading is set; the dot layer is hidden then. The heading comes from the camera planner (smoothed, kept when stopped). The engine has no accuracy circle (it never had), so there was nothing to keep there.

## Tests

- `NavCameraPlannerTest` (JVM): speed bands, quarter steps, 2D law, approaching turns and easing out, roundabouts, sharp turns and arrival, limits, standing still and missing heading keep the last bearing, smoothing of jumps, wrap-around at 359 to 1, marker position for several screen heights, determinism.
- `NavCameraTest`: the throttle (interval, tiny changes, forced update), 2D/3D switch goes through, padding change goes through, heading kept for a standing car.
- `NavHost3dTest` (Robolectric, fake `MapEngine`): what the engine is asked in 3D, 2D, on switching, on recenter, on stop and on a new trip, buildings on/off without repeated calls, heading arrow, overview; `remainingRoute`.
- `NavView3dControllerTest`: persistence through the settings store, Settings changes reaching a trip in progress, overview start, touch and timeout (the timeout test uses a 1 ms overview and waits for the state, not for time).
- `NavScreenTest`: the toggle (text, content description in English and Spanish, click asks for the opposite), 48 dp and 56 dp targets, the overview button.
- `Nav3dSettingsTest`: defaults, switches, hidden buildings switch without 3D, restart, damaged file.
- `Buildings3dTest`: one layer per source, unique ids that never collide with the style, anchor below the first symbol layer, the extrusion reads the same `source-layer` and kinds as the flat layer in the generated styles, adding and removing leaves the style layers alone.

## What is and is not verified

Verified (JVM and Robolectric): all the decisions above as numbers, what the engine is asked, state, persistence, strings in both languages, layer data and ids.

**Not verified, because MapLibre does not render here and no device was used:**

- That the tilted camera looks right, that the marker really lands in the lower third (the padding semantics of `CameraPosition.padding` in MapLibre Native 13 were read from the API, not seen), and that the animation with padding does not jerk.
- That the Protomaps tiles of the installed Spanish regions carry `height` and `min_height` for buildings (if not, every building is 9 m), and that `building` plus `building_part` do not z-fight where both exist.
- That the route line stays on top of extruded buildings and that the extrusions sit under the labels as intended.
- That `frameRoute` and the route-preview fit really leave no padding behind (the camera padding is set to zero explicitly after fitting).
- Frame rate, battery and heat of the tilted map, and above all of the extrusions.

## To check on a device

1. **Pitch comfort:** 55 to 60 degrees at town and road speeds; is the foreground too short, the horizon too high? Tunables: `TILT_TOWN`, `TILT_FAST` in `NavCameraPlanner`.
2. **Marker position:** in 3D and 2D, with the banner showing lanes and a second maneuver, and with the bottom panel plus the simulation bar; adjust `MARKER_Y_3D` and `MARKER_Y_2D`. Check glove mode (bigger banner).
3. **Zoom:** too close in town, too far on the motorway? Approaching a roundabout and after it. `ZOOM_3D`, `zoomBoost`, `approach`.
4. **Bearing:** a stopped car at a light, a U-turn, a roundabout, a motorway with GPS noise.
5. **Battery and smoothness:** 20 minutes of navigation in 3D without buildings, with buildings, and in 2D (use the simulation for repeatability and a real drive for GPS). Turn "3D buildings" off if frames drop; a region border (two overlapping sources) doubles the extrusion work there.
6. **Extrusion look:** colours in both themes, height of tall buildings, route above buildings, labels above buildings.
7. **Overview:** frames the right part, returns after 8 s with an ease; the toggle and the overview button are reachable and not covered by the speed cluster.
8. **Transitions:** start, stop, arrival, recenter after panning, toggle 2D/3D while moving.
