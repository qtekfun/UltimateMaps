# Next plan (after rc.5)

Date: 2026-10-07. Sources: `docs/phase2/feature-gap-analysis.md` (Google/Apple Maps comparison), `docs/phase2/speed-cameras-analysis.md`, `docs/decisions.md`. Effort figures are estimates from those analyses, not measurements.

## Rules for every wave

- At most 3 subagents at a time, one branch each (`feat/<task>`), worktree isolation, no push and no publishing by agents; I integrate, run the full suite and publish.
- No Pixel 8 until the owner gives permission. Anything that needs the device is listed under "Needs a device" and not claimed as done.
- English everywhere in the repo; every feature with a Settings switch is off by default unless it is core UX; strings in `values/` and `values-es/`.

## Wave 1 (running now)

| Branch | Content | Estimate |
| --- | --- | --- |
| `feat/cameras-incidents` | Speed cameras (fixed, average-speed), mobile radars only as official zones, DGT incidents and V16 beacons if an open feed exists; one switch per category; navigation warnings; map layers; data converter script for the weekly data release | 13 d (cameras) + incidents |
| `feat/personal-quick-wins` | Parked-here marker, Home/Work shortcuts, recent searches (with clear and off switch), list emoji/colour/notes, launcher shortcuts, emergency screen (coordinates, 112, share by SMS) | about 8 d |
| `feat/data-import-tracks` | Google Takeout import, GPX tracks drawn as lines, stop reordering in the route panel | about 6 d |

## Reported by the owner on 2026-10-07 (after using rc.6)

- **Repeated "no GPS signal, estimated position" while navigating.** Suspect (not confirmed on a device): on API 31+ the app asks the fused provider through the legacy provider-string call, which does not request high accuracy, and the follower discards fixes worse than 100 m and declares signal loss after 5 s. Plan: request high accuracy explicitly, base loss detection on arrival time instead of the fix timestamp, and measure fix gaps on a real drive.
- **Camera (radar) warnings while navigating "not working".** The camera data file was not in the data release yet (the layer and alerts had nothing to warn about); the weekly data workflow now builds it. Still to do: verify the alerts end to end with the real file, add a visual banner (voice alone is easy to miss), and confirm the alert reaches the voice queue and the notification.

## Wave 2 (as slots free up)

- Category browse in search (needs a one-day core spike), place-card extras from OSM tags (phone, website, opening hours text), Plus Codes and coordinate search.
- Share as `geo:`/OSM link and ETA as text, trip summary after navigation, compass/scale audit.
- **Bike: prefer or require cycle infrastructure** (user request 2026-10-07). Findings from `third_party/comaps/libs/routing_common/bicycle_model.cpp`: the model already gives `highway=cycleway` a weight of 21 to 23 against 10 to 14 for primary and tertiary roads, so cycleways are favoured today; it cannot tell a painted cycle lane on a normal road apart (only the separate cycleway type and a tiny `nocycleway` factor of 0.95), and the routing options are only Ferry, Dirty, Steps and Paved (no cycleway option). Proposal: a setting with Off / Prefer / Strongly prefer / Only cycle infrastructure, implemented as a patch to the bicycle model (weight factors, and for Only a hard exclusion of non-cycle roads) carried in `scripts/comaps-prepare.sh`; "Only" will often fail to find a route because cycle networks are discontinuous, so the UI must say so and offer to relax it. Open: whether the `.mwm` files keep lane tags at all (not verified), effort not estimated before a one-day core spike, and it needs a native build plus a device to judge route quality.
- Alternative routes and opening-hours display, once the core spike confirms what is exposed.

## Wave 3 (next)

Motorcycle and curvy routes, terrain contours and elevation profile, cycling/hiking overlays, track recording, WebDAV sync with encrypted backup, EV charger layer. Several need layers in the data pipeline (`ultimate-maps-data`).

## Later

Android Auto, transit, indoor maps, satellite, opt-in timeline, neural offline voice, widgets, diff updates.

## Needs a device (owner permission)

R12: long routes returning `ROUTE_NOT_FOUND` and search latency; the 3D view; voice; the navigation service on Android 14+; frame rate and battery with cameras and buildings.

## Not planned (conflicts with privacy or the non-negotiables)

Live traffic, crowd-sourced police reports, server-based location or ETA sharing, server ratings and photos, cloud assistants. The optional DGT incident feed is the one exception to the "no traffic" rule: opt-in, downloaded as a whole national file with no location sent, filtered on the device (see `docs/decisions.md`).

## Owner-only items still open

Signing key and secrets, protect `master`, approve `ci.yml`, first `v*` tag, tell CoMaps about the hosted `.mwm` files, store icon and screenshots, legal check of the camera feature.
