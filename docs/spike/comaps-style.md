# Spike (c): study of the CoMaps style system

Source: `~/repos/comaps-spike` (tag `v2026.10.05-19`, read-only) and the `tools/kothic` submodule at the pinned commit `3dd47d5941891ed2e0e554058edf214e9050e678`. The submodule was empty in the tree when I read it (the other agent was initializing it), so I fetched that exact commit, with `--depth 1`, into a temporary directory outside the repo; the `libkomwm.py` code cited below is from that commit.

Evidence: **[L]** read in code (`file:line`), **[I]** inferred, **[N]** not verified. Nothing has been built or rendered: **this report cannot say whether the result "looks like Apple Maps"**; only which controls exist. The visual judgment needs real screenshots (the spike's measurement flow).

## 1. How the style system works

Production chain [L]:

```
data/styles/<theme>/{light,dark}/style.mapcss  (+ colors.mapcss, symbols/*.svg)
data/styles/<theme>/include/*.mapcss, priorities_*.prio.txt
        |  tools/unix/generate_drules.sh -> tools/kothic/src/libkomwm.py
        v
data/drules_proto_<theme>_<light|dark>.bin (+ .txt, visibility.txt, classificator.txt, colors.txt, patterns.txt)
        |  tools/unix/generate_symbols.sh (skin_generator)
        v
data/resources-*/symbols.png + symbols.sdf
        |  runtime loading: libs/indexer/map_style_reader.cpp:156
        v
drape_frontend (stylist.cpp, apply_feature_functors.cpp, rule_drawer.cpp) + shaders/GL/*.glsl
```

- **Themes** (`data/styles/`): `default`, `outdoors`, `vehicle` (navigation), `driving`, `walking`, `cycling`, `public-transport` and their `_outdoor` variants (11 directories). Each one has `light/` and `dark/` (`docs/STYLES.md`, section "Styles directories and files"). `generate_drules.sh` compiles 22 combinations (theme x light/dark). [L: `tools/unix/generate_drules.sh:48-72`]
- **Language**: a subset of MapCSS 0.2 with CoMaps extensions (`docs/STYLES.md`, "Technical details"). Selectors by type and zoom: `line|z10-13[highway=motorway]` (`data/styles/default/include/Roads.mapcss:87`). Colors as variables (`@water: #89CDDC;` in `data/styles/default/light/colors.mapcss:42`, `@background0: #F5E8D6` at `:29`).
- **Compiled, not interpreted**: the style is not evaluated on the device; it is compiled to a protobuf (`libs/indexer/drules_struct.proto`) with a `feature type x zoom -> rules` table. Changing the style in the app means switching the `.bin` (`map_style_reader.cpp:156`) and reloading (`DrapeEngine::UpdateMapStyle`, `drape_engine.cpp:462-469`). [L]
- **What can be expressed** is exactly what fits in the proto [L: `drules_struct.proto`]:
  - `LineRuleProto` (`:31`): width, color (ARGB), dash pattern, priority, symbol along the line, `join` and `cap`.
  - `AreaRuleProto` (`:52`): a fill color, a border (`LineDefProto`) and priority. No gradients or textures except `pattern-image`/`hatching`.
  - `SymbolRuleProto` (`:59`): icon name, priority, `min_distance`.
  - `CaptionDefProto` (`:67`): `height` (integer), `color`, `stroke_color`, `offset_x/y`, `text`, `is_optional`. **There is no font family, weight, letter spacing, or halo width.**
  - `DrawElementProto` (`:111`): one rule per `scale` (zoom).
- **Priorities**: files `priorities_{1_BG-by-size,2_BG-top,3_FG,4_overlays}.prio.txt`, regenerated and reordered by the script. Overlays (icons, labels, shields) **do not overlap**: the one with the highest priority wins (`priorities_4_overlays.prio.txt:5-8`), and an icon's optional labels only appear if there is room.
- **Icons**: SVGs in `data/styles/<theme>/{light,dark}/symbols/` (1088 files in `default/light/symbols`), `icon-image` rule in `Icons.mapcss`, and `generate_symbols.sh` composes them into the atlas (SDF) (`docs/STYLES.md`, "How to add a new icon"). This is the official replacement procedure. [L]
- **Changes that require regenerating mwm**: if which features exist or their zoom range changes beyond the index limits (`docs/STYLES.md`, "Testing your changes"). A tweak to colors/widths/icons does not touch them. [L]
- **Tooling**: there is a desktop "Designer" version for iterating (`docs/STYLES.md`), and compiled `.bin` files can be copied to `Android/data/<app>/files/styles/` on the phone without rebuilding the app. [L]

## 2. The 10-point checklist table

"Reachable" here = can be achieved with the style system and/or small changes, not with a new engine. **Effort** in person-days (my estimate, [I]).

| # | Point | Reachable | Evidence | What is missing / limit | Effort |
| --- | --- | --- | --- | --- | --- |
| 1 | Soft background and water palette | **Yes** | Colors as hex variables per theme and mode: `default/light/colors.mapcss:29, 42` (`@background0`, `@water`); the compiler encodes them to ARGB (`libkomwm.py:104-112`). | Nothing engine-side. Has to be repeated in `dark/` and in the themes that are used. | 2-3 |
| 2 | Roads with thin casing and clear hierarchy | **Yes** | Width, color, opacity by zoom and class in `Roads.mapcss:87-100`; casing with `casing-width/-color/-dashes` (`Roads.mapcss:124, 129, 229`), which the compiler emits as an extra line under the main one (`libkomwm.py:805-831`). Order by `priorities_3_FG.prio.txt`. | No shadows or gradients on roads (the line proto does not have them). Intersection fill is fixed by the renderer. [I] | 4-6 |
| 3 | Controllable label typography | **Partial** | Per rule only integer size, color, offset and halo are controlled (`CaptionDefProto`, `drules_struct.proto:67-75`; `libkomwm.py:923-935`). The font list is **global and fixed in C++**: `libs/platform/platform.cpp:197-209` (Noto, DejaVu, Droid, Roboto Medium...) plus system fonts (`:211`). Glyphs as SDF at 22 px base (`libs/drape/font_constants.hpp:6`). | The global Latin font can be changed (replace/add a TTF in `data/fonts` and the list): low cost. There are **no** per-class weights (bold for cities, light for streets) or letter spacing without touching `glyph_manager`/`text_layout` (C++): 8-12 days. I have not checked the resolution priority between fonts [N]. | Global: 2; per class: 8-12 |
| 4 | Rounded, replaceable POI icons | **Yes** | Official replacement pipeline: SVG in `symbols/` + `icon-image` in `Icons.mapcss` + `generate_symbols.sh` (`docs/STYLES.md`). 1088 SVGs in `default/light/symbols`. | It is design work more than technical: a coherent own set (and its `dark/` version). License: the current icons come from collections with different licenses (`NOTICE`, `data/copyright.html`); using our own set avoids carrying them. Mechanics: 2 days; full set: 10-15. | 12-17 |
| 5 | Label halo | **Yes** (with a limit) | `text-halo-color`, `text-halo-opacity` and `text-halo-radius` are read in `Roads_label.mapcss:130-134` and `Icons_Label_Colors.mapcss:11`; they are serialized as `stroke_color` with alpha (`libkomwm.py:104-112, 928-929`). | **The radius only acts as a switch**: the compiler checks `!= 0` and stores only the color (`libkomwm.py:928`); the proto has no width. The halo is drawn as an outline pass of the SDF glyph (`render_group.cpp:107-114`, `text_layout.cpp:101`), with a fixed SDF margin (`kSdfBorder = 4`, `drape/font_constants.hpp:5`) [I: that this margin limits the width]. Controlling the width = C++. Halo color and opacity, yes. | 1-2 |
| 6 | Discreet 3D buildings | **Partial** | Real extrusion: height from `height` or `building:levels` x 3 m (default 3 m) (`rule_drawer.cpp:61-96`); enabled if `Is3dBuildingsEnabled` (`rule_drawer.cpp:281`); switch in `DrapeEngine::Allow3dMode(allowPerspectiveInNavigation, allow3dBuildings)` (`drape_engine.cpp:700-703`); shading with a fixed light (`shaders/GL/area3d.vsh.glsl:19`). | From the style only the fill color/opacity is controlled (`Basemap.mapcss:714-723` defines `fill-color`/`fill-opacity` per zoom). Light direction, ambient, shadows, roofs: constants in shader/C++ (modifiable, it is our code). "Discreet" = tune color+opacity+shader: 3-5 days. No cast shadows or roofs. With `building:levels` absent they come out almost flat (3 m). | 3-5 |
| 7 | Optional relief shading | **No** | Search for `hillshade|hill_shade|relief|hillshading` across the whole tree (excluding `3party/`): **no results**. What does exist are isolines (contour lines) generated with SRTM (`docs/ISOLINES.md`, `tools/topography_generator`) and route altitudes. | Hillshade needs a new raster/mesh layer + shader in Drape, plus distributed DEM data (Sonny/SRTM are already in the license list). High cost: 15-25 days and maintaining a Drape fork; or solve it with another engine (option C). Isolines as a substitute: 0 days (already exists). | 15-25 |
| 8 | Day/night transition | **Partial** | `light`/`dark` pairs per theme; hot switch with `Framework::SetMapStyle(mapStyle, forceRerendering)` (`framework.cpp:1843-1851`) -> `DrapeEngine::UpdateMapStyle` (`drape_engine.cpp:462`) -> rule reload. | It is an **abrupt** switch of rule set; I did not find interpolation between styles [I; not searched in depth]. A visual fade can be done in the UI layer (crossfade of a map snapshot in Compose): 2-3 days. The automatic mode by time/sensor belongs to the app, not the style. Keeping `dark/` in parity with `light/` doubles the palette work. | 2-3 (+ parity) |
| 9 | Label density adjustable by zoom | **Partial** | Visibility by zoom in each rule (`|zN-M`, e.g. `Roads.mapcss:87`); priorities and overlap displacement (`priorities_4_overlays.prio.txt:5-8`); `min_distance` in symbols and shields (`apply_feature_functors.cpp:597-600, 1260`); global font factor (`visual_params.hpp:63-64`, `framework.hpp:806`). | It is **static per style**, tunable offline by type and zoom; there is **no runtime control** of "density". A density slider would be a multiplier on `min_distance`/collision scale (C++, 3-5 days [I]). Tuning the priority files: 4-6 days. | 4-6 (+3-5 slider) |
| 10 | 3D navigation view with tilted camera | **Yes** (tunable in C++) | Automatic perspective when following a route: `EnablePerspective` (`routing_manager.cpp:1357`), angle by scale up to π/4 and 55° with FOV 60° (`libs/geometry/screenbase.cpp:8-10, 92-110`); `Allow3dMode(allowPerspectiveInNavigation, ...)` (`drape_engine.cpp:700`). Own `vehicle` theme for navigation. | The angles and the FOV are **constants in C++**, not in the style. Changing the tilt/position of the car on screen = touching `screenbase.cpp` and the following logic (2-3 days). The camera's "Apple look" depends on them and is not validated [N]. | 2-3 |

### Count

- **Yes: 4** (1, 2, 4, 5) plus 10, which is a yes but with constants in C++: **5**.
- **Partial: 4** (3, 6, 8, 9).
- **No: 1** (7).

Against the spike threshold ("≥ 8 reachable"):

- Counting only the **Yes**: 5/10, **not reached**.
- Counting **Yes + Partial** as reachable: 9/10, reached, **but** three of the four partials (3, 6, 9) depend on modifying Drape C++ or accepting a less refined result. My honest reading: the style system covers palette, roads, icons and halos well (the "surface" controls); the controls that define the "Apple" character (type weights, relief, buildings with shadow, animated transition) are **out of the style's reach** and depend on a Drape fork or another engine.
- If the criterion is "reachable without touching C++", the count is **5 or 6 out of 10** (1, 2, 4, 5, and 9 and 8 partially).

The spike's A/C decision depends on where that bar is set and on the visual comparison, which I have not done. I recommend that the final report not count "partial" as "yes" without a screenshot to back it up.

## 3. Total effort (if going with A, tuning style and Drape)

| Block | Days |
| --- | --- |
| Palette (light/dark, `default` + `vehicle`) | 2-3 |
| Roads | 4-6 |
| Own icons | 12-17 |
| Halo, density (static), day/night fade | 7-11 |
| Discreet 3D buildings (shader + colors) | 3-5 |
| Navigation camera | 2-3 |
| Global typography (single font) | 2 |
| **Subtotal without deep C++** | **~32-47** |
| Typography by weight (C++) | 8-12 |
| Hillshade (C++/data) | 15-25 |
| Runtime density slider (C++) | 3-5 |
| **With the three extras** | **~58-89** |

## 4. Style-specific risks

- **Maintaining a Drape fork**: any change to shaders/constants (points 3, 6, 7, 10) ties us to rebasing against CoMaps on every version (the map series changes: `MAP_SERIES` in `private.h:22`). [I]
- **Python style compiler (kothic)** with a `protobuf` dependency in Python and a script that modifies `data/` in place; the `data/` tree is regenerated and large (1088 icons, 22 variants). [L/I]
- **Icon and font licenses**: see `comaps-code.md`, section 5 (Code2000 is shareware and must be removed; icons from external collections have to be audited if not all are replaced).
- **"vehicle" theme and the per-mode themes** (foot, bike, car, transit) multiply the style work by 11 if coherence is wanted; it is advisable to decide how many themes are kept (proposal: `default` and `vehicle`, light and dark = 4 compiled). [I]
