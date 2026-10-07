# Category browsing and alternative routes: core spike

Read-only investigation of the CoMaps core (`third_party/comaps`, tag `v2026.10.05-19`) and of our wrapper
(`native-comaps/src/main/cpp/um_core.cpp`, `:core-search`, `:core-routing`). Nothing here was run on a device.

## (a) Search by category near a point

**What exists.**
- `search::SearchParams::m_categorialRequest` ("pure category results only, without names/addresses matching"). CoMaps'
  own app sets it when the user taps a category tile (`libs/map/search_api.cpp`, `params.m_isCategory`).
- `search::DisplayedCategories` is the list of tiles CoMaps shows (`category_eat`, `category_fuel`, `category_pharmacy`,
  `category_atm`, `category_hospital`, `category_parking`, `category_toilet`, ...). The category names live in
  `data/categories.txt` per language (English `Pharmacy`, `Cafe`, `Supermarket`, `Restaurant`, `ATM`, `Hospital`,
  `Parking`, `Hotel`, `Toilet`, `Fuel`; Spanish `Farmacia`, `Café`, `Supermercado`, `Restaurante`, `Cajero automático`,
  `Hospital`, `Aparcamiento`, `Hotel`, `Aseo`, `Gasolinera`). A category is searched by typing one of those names.
- `Result::GetFeatureCenter()` gives the position; the result carries no distance in meters (only an internal ranking
  distance). Our wrapper already sets `m_position` and a 20 km `m_viewport` when a point is given, so ranking is
  already biased to the point.

**What our wrapper does today.** `Core::Search` leaves `m_categorialRequest = false`, so the text "pharmacy" also matches
places whose name contains it. It works as a free-text search but is not the pure category mode.

**What needs C++ changes.** One flag: `Core::Search(..., bool categorial)` sets `params.m_categorialRequest`. It then
has to pass through JNI (`nativeSearch` gets a `jboolean`), `NativeBridge`, `CoMapsCore`, the isolated-core wire
(`CoreProtocol` gets a new opcode that reuses the search arguments) and `SearchEngine`. All additive: a new
`SearchEngine.searchCategory` with a default that calls `search`, so fakes and old callers do not change.

**Distance.** Computed in Kotlin from the point used for the search (`LatLon.distanceTo`) and sorted ascending. No core
change. The core returns at most `limit` results ranked by its own relevance inside the 20 km viewport, so "sorted by
distance" means sorted within what the core returned, not a guaranteed nearest-N over the whole map.

**Effort.** About one day including tests (done below). Risk: whether the English names match when the engine locale is
Spanish (the core indexes every language of a category, but this was not run); the app therefore sends the name in the
UI language (a resource string), which the Spanish list above covers.

**Not verified.** Result quality and latency of category-only searches on a real region (no device, and the submodule
data is not run on the JVM). Result counts per category near a point are unknown.

## (b) Alternative routes

**What exists.** Nothing. `routing::IndexRouter::CalculateRoute` returns exactly one `Route`; there is no k-shortest-path,
route variants or "alternative" API anywhere in `libs/routing` (searched for alternative / variant; the only hits are about
alternative turn candidates in maneuver generation). `RoutingSession` keeps one route. The upstream app has no alternatives.

**What it would take in C++.** A penalty-based or via-point alternative search inside the A* of `IndexRouter`: weeks of
work in a very hot, fragile piece of code, plus long-route latency (the core already needs seconds for long routes, and
each alternative would cost at least one more full search). Not attempted.

**Cheap alternative (implemented).** Re-route with one more "avoid" option than the user chose and show the result as an
honest, labelled alternative:
- car: "avoids motorways", "avoids tolls";
- foot and bike: "avoids unpaved", "avoids ferries".
Cost: one extra full route calculation per alternative (up to 2), run one after another in the background under the same
core lock, after the main route is already on screen. Avoid options are hard exclusions in the core, so an alternative can
fail (no route); it is then simply not offered. When the alternative has exactly the same geometry as the main route (the
excluded class was not used) it is dropped. Options the user already turned on are not offered again. The deltas shown are
the real differences in duration and distance against the main route.

**Limits, stated plainly.** These are not "the next best route"; they are the best route under a different restriction.
There is no alternative when no restriction changes the route. Selecting one makes "Start" and "Simulate" use the same
options (the guided calculation reads `RoutePreviewController.currentRequest`).
