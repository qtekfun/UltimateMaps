# Play flavor: optional online geocoder fallback (Photon)

Owner decision of 2026-10-12 (see `docs/decisions.md`). `play` flavor only; `foss` has `NoOnlineGeocoder` and shows no setting.

## Behaviour

- **Setting:** Settings, Maps and network, *Online search*: switch *Search online when the map has no result (Photon, OpenStreetMap data)*,
  **off by default**, and *Server address* (blank = `https://photon.komoot.io`; only `https://`, no credentials). Stored in the
  `online_search` preferences file (`enabled`, `server_url`), exported in the settings backup (`online_search/enabled` needs consent on
  restore like every switch that starts a third-party connection; in `foss` a restored "on" is never kept because nothing could accept it).
- **When it runs:** after the offline search has *settled* with nothing, or with only streets for a typed house number
  (`HouseNumberHint`). Never while a category is browsed, never for a typed position, never for text shorter than 3 characters,
  never in offline mode, never with the switch off.
  - Submitted search (keyboard search key): at once after the offline answer.
  - As-you-type search: after an extra 900 ms (on top of the 250 ms offline debounce), so a half-typed text is not sent; a keystroke
    cancels it.
- **What is sent:** `q` (the typed text, at most 200 characters), `lat`/`lon` = the **map centre rounded to 0.01 degree** (about 1 km;
  `OnlineSearch.roundBias`; never the live GPS position), `lang` (only `en`, `de`, `fr` or `it`, the languages the public instance
  accepts; never for Spanish or "local names"), `limit` (8), and a `User-Agent` of `UltimateMaps/<version> (+https://github.com/qtekfun/UltimateMaps)`
  as the public instance's fair-use policy asks. No cookies, no identifiers, no history.
- **Rate limit:** `PhotonGeocoder` keeps at least 1 s between requests (a later one waits, on the monotonic clock).
- **Results:** listed after the offline results (before them when the offline ones are only streets of a missing number), each with the
  label *Online* in its subtitle and a credit line *© OpenStreetMap contributors, via Photon* under the list. They are pinned on the
  map (same layer as category pins) and are picked, shown on the place card and routed like any other result. Duplicates of an offline
  hit (same name, within about 100 m) are dropped. Nothing is cached; the typed text lands in the search history only if the history
  switch is on, as for any search.
- **States:** `SearchState.onlineSearching` replaces "No results" with *Searching online…* while it runs; `onlineFailed` adds *The online
  search failed. Only results from the downloaded maps are shown.* under the offline answer. Offline mode and a denied connection are
  silent.

## Network rules

Every request goes through `NetworkPolicy` under `ConnectionPurpose.ONLINE_SEARCH` (`BoundedHttp`: https only, every hop authorised,
body capped at 512 KB, timeouts). The geocoder's host is added to the connection list (RF-12) only while the switch is on
(`MapasApp.syncOnlineSearchEndpoint`) and removed when it is turned off or the address changes. Offline mode is checked before the
rate-limit wait, so it neither waits nor logs an attempt.

## Code map

- `:core-search`: `OnlineGeocoder`, `OnlineOutcome`, `NoOnlineGeocoder`, `OnlineSearchSettingsStore`, `OnlineSearch` (defaults, URL
  validation, bias rounding, language), `SearchResult.online`.
- `app/src/play`: `PhotonGeocoder` (request, rate limit, GeoJSON parse with `org.json`), `PlatformServices.onlineGeocoder`.
  `app/src/foss`: `PlatformServices.onlineGeocoder` returns `NoOnlineGeocoder`.
- `app`: `SearchCoordinator` (fallback ordering), `SheetPanel` (marks, notes, credit), `OnlineSearchSettingsSection`,
  `PrefsOnlineSearchSettingsStore`, backup schema group `online_search`.

## Attribution

Photon is an Apache-2.0 service and no Photon code is in the app; its data is OpenStreetMap, ODbL. The credit is in the result list,
in Settings (Online search), in About and in `NOTICE`; `PRIVACY.md` (English and Spanish) says what is sent. `LICENSES.md` has the row.

## Tests

`OnlineSearchTest` (`:core-search`), `OnlineFallbackTest` (coordinator ordering, debounce, cancel, switch off, failure; virtual time),
`PhotonGeocoderTest` (parse, empty, errors, rate limit on virtual time, switch, offline mode, request parameters),
`PhotonHttpTest` (real `BoundedHttp` against a loopback server: headers, policy gate), `SheetPanelTest` (marks and credit),
`OnlineSearchSettingsTest`, `SettingsBackupTest` and `SettingsSchemaCoverageTest`.

Not verified on a device or against the real `photon.komoot.io` (no network call was made while developing): the real response shape
beyond the documented GeoJSON fields, how it feels, and the as-you-type delay.
