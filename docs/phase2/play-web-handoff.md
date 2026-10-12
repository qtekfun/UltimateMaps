# Play flavor: web hand-off to Google Maps and share-back

Owner decision of 2026-10-12 (see `docs/decisions.md`). Applies to the `play` flavor only; the `foss` flavor has none of it.

## What the user sees

1. The user types something the offline search cannot find. The panel shows "No results" (state `DONE` with no results, no category open).
   In the same state the `play` flavor adds a button, **Search on Google Maps (web)**, and a note saying how to come back.
   The button is also offered under the "the map has no address number N there" note (state `numberMissing`), where the offline search
   only found streets.
2. The button opens `https://www.google.com/maps/search/?api=1&query=<url-encoded text>` in the **default browser** (browser selector
   intent, so it never resolves back to UltimateMaps even though the manifest claims `google.com/maps` links). If there is no default
   browser, a chooser that excludes this app's own activity is used.
3. In Google Maps (web or the app) the user taps Share and picks UltimateMaps. Android delivers `ACTION_SEND` `text/plain` to
   `MainActivity`; a link opened with `ACTION_VIEW` (`google.com/maps`, `maps.google.*`, `maps.app.goo.gl`, `goo.gl/maps`) lands in the
   same place.
4. The place lands on the map with a pin and the usual place card (Save, Route, Share, Home/Work), named with the shared name when the
   link has none.

## Parsing the shared text

`SharedTextParser` (`:core-geo`, pure) takes the whole shared text. Google shares `Name\nAddress\nURL` or just the URL; browsers share
`Title\nURL`; some apps share bare coordinates.

- It finds the first `http(s)://` or `geo:` token that `MapLinkParser` understands (sentence punctuation and angle brackets are stripped,
  a `)` that closes a `(Label)` is kept). Links that are not map links are skipped, so "menu https://example.com ... map https://maps..." works.
- The name is the first non-link line before the URL (or the words before it on the same line), cut to 120 characters.
- If there is no link and the whole text is one short line that is decimal or DMS coordinates, it becomes a `geo:` link.
- It never resolves a short link.

`LinkHandler.handleShared(text)` then classifies the result; `LinkHandler.classify(uri, name)` is also what `ACTION_VIEW` uses. A short
link comes back as `LinkOutcome.ShortLink(url, name)`; `LinkHandler.handle` (the old API) still maps it to `ShortLinkNotResolved`.

## Short links

A `maps.app.goo.gl` link has no coordinates; its target is only known from the redirect. Rules:

- `foss`: never resolved (no connection to a Google host is listed or allowed); the notice explains it.
- `play`: `HttpShortLinkResolver` (`app/src/play`) asks the short-link host where it redirects. The request is authorised by
  `NetworkPolicy` under `SHORT_LINK_RESOLVE` before every hop (offline mode blocks it and the notice says so), is HTTPS only, uses a
  generic user-agent, no cookies, no body and sends **only the short URL**. It never fetches the long Google page: the first `Location`
  that is not a short-link host is the answer. A `consent.google.*?continue=<target>` redirect is unwrapped to its target. At most 4 hops.
- The two short-link hosts are listed (and enabled) in the connection list only in `play`, because the whole flow starts from the user
  sharing a link into the app; they were disabled and unused before.
- The expanded link goes through `LinkHandler.classify` again (a result that is still a short link is dropped) and shows the same
  place card or runs the offline search for a text-search link.

## Notices

`Notice.ShortLinkResolving`, `ShortLinkOffline`, `ShortLinkFailed` next to the existing `ShortLink` ("needs a request, off") and
`Unrecognized`.

## Terms

No Google Maps Platform API is called (Places and Geocoding terms, sections 5.3 and 3.3, forbid using their content with a non-Google
map). The hand-off is the user leaving the app for Google's own web page and bringing back only a link.

## Tests

- `SharedTextParserTest` (`:core-geo`), `LinkHandlerTest` (shared text, names, short links, expanded link), `WebHandoffTest` (URL
  encoding, browser selector, excluded chooser), `SheetPanelTest` (the row appears only without results or with a missing number, and
  only when the flavor hook is set), `HttpShortLinkResolverTest` (`app/src/testPlay`: one request, every hop authorised, offline,
  disabled, non-short hosts refused, loops, consent unwrapping).
- Not verified on a device: the real redirect of `maps.app.goo.gl` (the test uses a fake probe), how Chrome and the Google Maps app
  hand a share to the app, and the Android 12+ "open by default" behaviour for the claimed hosts.
