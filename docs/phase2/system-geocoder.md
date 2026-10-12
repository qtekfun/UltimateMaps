# System geocoder (`android.location.Geocoder`) as an optional online fallback

> **Outcome (owner decision, 2026-10-12): dropped.** The prototype described below (the `OnlineGeocoder` interface, the
> default-off switch, the play-only backend and the bench activity) was **not merged** into master; it only lives in the
> closed pull request #114. Only this document and the decision entry are kept, so the work is not repeated and the terms
> question can be reopened if Google confirms in writing that the Android Geocoder is usable with a non-Google map.

Status: exploration, 2026-10-12. Owner request: evaluate the Android framework `Geocoder` as an OPTIONAL online fallback
for address and place lookup in the `play` flavor only, when the offline search has no result. The Photon fallback and
the "search on Google Maps web and share back" flow are separate work and are not duplicated here.

**Recommendation: drop as a product feature (do not ship it enabled for users); keep the prototype behind the default-off
switch only if the owner accepts the terms risk in section 3.** The reasons are the Google terms (section 3), the
fact that the result is a bare address with no category, and that Photon gives the same fallback without Google. Details in
section 6.

What is verified and what is not is marked in every section: **[V]** read from a primary source (quoted, with date),
**[T]** covered by a JVM test in this PR, **[U]** unknown / not verifiable without a device or a Google contract.

## 1. What the API is

Sources read on 2026-10-12: the AOSP `android15-release` branch of `frameworks/base`
(`location/java/android/location/Geocoder.java`,
`services/core/java/com/android/server/location/LocationManagerService.java`,
`.../location/provider/proxy/ProxyGeocodeProvider.java`). The developer.android.com reference pages are generated from
that same Javadoc; the pages themselves could not be fetched as text by the tools of this session, so the AOSP source
is the citation.

- **[V]** Purpose and the backend dependency (class Javadoc): "Use the isPresent() method to determine whether a Geocoder
  implementation exists on the current device. If no implementation is present, any attempt to geocode will result in an
  error."
- **[V]** Locale: "Geocoder implementations are only required to make a best effort to return results in the chosen
  locale. Note that geocoder implementations may return results in other locales if they have no information available
  for the chosen locale."
- **[V]** Quality warning: "Geocoding services may provide no guarantees on availability or accuracy. Results are a best
  guess, and are not guaranteed to be meaningful or correct."
- **[V]** `isPresent()`: "Returns true if there is a geocoder implementation present on the device that may return
  results. If true, there is still no guarantee that any individual geocoding attempt will succeed." Implementation:
  `LocationManagerService.isGeocodeAvailable()` is `mGeocodeProvider != null`, i.e. a geocoder provider *service resolves*
  (`ServiceWatcher.checkServiceResolves()` for the configured package). It says nothing about the network or the backend
  working.
- **[V]** Forward geocoding, async (API 33+): `getFromLocationName(String locationName, int maxResults, GeocodeListener)`
  and the bounded overload `getFromLocationName(locationName, maxResults, lowerLeftLatitude, lowerLeftLongitude,
  upperRightLatitude, upperRightLongitude, GeocodeListener)`. Javadoc: "You may specify a bounding box for the search
  results by including the latitude and longitude of the lower left point and upper right point of the box." It does not
  say whether the box is a hard filter or a bias.
- **[V]** The blocking variants are `@Deprecated` since API 33: "Use ... instead to avoid blocking a thread waiting for
  results." They return "null or empty list if no matches were found or there is no backend service available" and throw
  `IOException` "if there is a failure". The blocking wrapper waits at most 15 s (`TIMEOUT_MS = 15000`, then
  `IOException(TimeoutException)`).
- **[V]** `GeocodeListener`: "Only one of the methods will ever be invoked per geocoding attempt. There are no
  guarantees on how long it will take for a method to be invoked, nor any guarantees on the format or availability of
  error information." `onGeocode(List<Address>)` "May return an empty list"; `onError(String?)` has "an optional error
  message".
- **[V]** `maxResults`: "Smaller numbers (1 to 5) are recommended".
- **[V]** Network and threads: "This API may hit the network, and may block for excessive amounts of time. It's strongly
  encouraged to use the asynchronous version of this API."
- **[V]** The input is free text: `locationName` "may be a place name such as "Dalvik, Iceland", an address such as
  "1600 Amphitheatre Parkway, Mountain View, CA", an airport code such as "SFO", and so forth."
- **[V]** `Address` (not re-fetched; the class is stable since API 1): the getters used are `getFeatureName`,
  `getAddressLine(i)` / `getMaxAddressLineIndex`, `getThoroughfare`, `getSubThoroughfare`, `getLocality`, `getAdminArea`,
  `getPostalCode`, `getCountryName`, `hasLatitude()/getLatitude()`, `hasLongitude()/getLongitude()`. There is no place
  type, category, opening hours, phone-with-meaning or place id in the contract: an `Address` is an address.

### Behaviour with no backend

- **[V]** `LocationManagerService.forwardGeocode`: when `mGeocodeProvider == null` the service calls
  `callback.onError(null)`. In the listener API that is `onError(null)`; in the blocking API it becomes
  `IOException(null)` (or an empty list on some code paths, per the Javadoc "Returns null or empty list ... or there is no
  backend service available"). The app must treat null, empty and `IOException` identically as "no backend or no match".
- **[V]** If the provider service exists but dies or cannot be bound, `ProxyGeocodeProvider` reports
  `callback.onError(throwable.toString())`.
- **[V]** The system server logs `no geocoder provider found` (error level) at boot when none resolves.

## 2. Behaviour on real configurations

Nothing here was measured: no device was used (the Pixel needs the owner's permission, the other phones are not mine).
The statements split into what the source guarantees and what is only common knowledge.

| Configuration | What is known | Status |
|---|---|---|
| Stock Android with Google Play Services | The geocoder provider is a Google Play services component; the request goes to Google's servers. `isPresent()` is true. | package/implementation **[U]** (not in AOSP; stable folklore), existence of a Google backend is well known |
| LineageOS / GrapheneOS without GMS | AOSP ships no geocoder backend. `isPresent()` is normally false; `onError(null)`. Users may install a third-party backend (microG's `NominatimGeocoderBackend`, or "Nominatim Geocoder Backend") which then answers from OSM Nominatim, over the network. | **[V]** that no provider means error; **[U]** per ROM |
| microG on a de-Googled ROM | `isPresent()` may be true thanks to an installed backend (Nominatim). The answers are OSM-based, not Google. | **[U]** |
| Huawei (HMS, no GMS) | Huawei devices have their own geocoder provider stack; whether `Geocoder` answers depends on the model/EMUI/HarmonyOS version. | **[U]** |
| Airplane mode / no data | The backend cannot answer: `onError(...)` or empty list, after an unspecified delay (bounded here by our 4 s timeout). There is no offline geocoder in AOSP. | **[V]** no offline mode in the API; delay **[U]** |

### The reliable probe

`Geocoder.isPresent()` only says a service resolves. The probe implemented here (`GeocoderProbe`) asks one fixed,
non-personal query (`"Madrid, Spain"`, 1 result) and calls the backend working only if it answers with a result that has
coordinates. Rules: run it only after the owner turned the switch on (it sends that text), cache the answer for some
hours, re-probe after a `Failed` outcome, and never run it in offline mode. **[T]** the probe logic; **[U]** how often
real backends fail on the first call after boot.

### Limits, latency, quotas

- **[U]** Rate limits and quotas: the platform documents none for the system `Geocoder`. Google's Geocoding API
  (a different product) has published quotas, but nothing says they apply here. Practical consequence: the app must be
  conservative (one request per submitted search, never per keystroke; minimum 3 characters; one in flight), and treat any
  error as "unavailable for now".
- **[U]** Latency: not measured. The blocking call has a 15 s ceiling; the prototype uses 4 s for a search box.
  The debug bench logs milliseconds per query so the owner can read real numbers.
- **[U]** POI / business name vs street address vs house number: the contract is "an address"; the backend is a
  geocoder, not a places index. From experience reports (not verified here): a street with a number usually resolves to
  rooftop or interpolated points; a well-known business may resolve when Google's backend recognises it, but there is no
  guarantee and no category; a bus stop or a small shop chain branch is the weak case. The manual test plan below
  exists to measure this on the owner's phone.
- **[U]** `featureName` quirk: for street addresses backends often put only the house number in `featureName`. The mapper
  handles that (name = street + number). **[T]**
- **[U]** Language: the `Locale` passed to the constructor is a best-effort hint ([V] above). The prototype passes the
  device locale, so a Spanish phone gets Spanish names. It does not follow the app's "place language" setting yet.
- **[U]** The bounding box: documented only as a "bounding box for the search results"; whether it filters or biases is
  not said. The bench lets the owner compare the same query with and without `--es near`.

## 3. Google terms

Searched and fetched on 2026-10-12.

### What I could establish

**Google Maps Platform Service Specific Terms**, page header "Last modified June 10, 2026"
(`https://cloud.google.com/maps-platform/terms/maps-service-terms`), section "3. Android Geocoder SDK (Restricted GA)":

> 3.1 Use without a Google Map. Customer may use Google Maps Content from the Android Geocoder SDK in Customer
> Applications without a corresponding Google Map.
>
> 3.2 No use with a non-Google map. Customer must not use Google Maps Content from the Android Geocoder SDK in
> conjunction with a non-Google map.
>
> 3.3.1 Customer may temporarily cache latitude (lat) and longitude (lng) values from the Android Geocoder SDK for up to
> 30 consecutive calendar days, after which Customer must delete the cached latitude and longitude values.
>
> 3.3.2 Customer may indefinitely cache latitude (lat), longitude (lng), formatted_address, and the structured address
> values from the Android Geocoder SDK solely to support the direct, End User facing functionality of the Customer
> Application that initiated the request [...] only where the cache is not used as a replacement for making an
> additional call to the Services. Cached data must be logically isolated to the specific End User [...]

The same page has the identical "No use with a non-Google map" clause for the Geocoding API (section 6.2), Places API,
Directions API, and others. The umbrella **Google Maps Platform Terms of Service**
(`https://cloud.google.com/maps-platform/terms`, "Last modified August 26, 2026") says: "**No Use With Non-Google Maps.**
To avoid quality issues and/or brand confusion, Customer will not use the Google Maps Core Services with or near a
non-Google Map in a Customer Application. For example, Customer will not (i) display or use Places content on a non-Google
Map [...]".

### Why this matters here

UltimateMaps shows every result on a MapLibre map with OpenStreetMap data, which is a non-Google map. If the platform
`Geocoder` on a Play Services phone *is* the "Android Geocoder SDK" of those terms, then displaying its results on this
map is prohibited by 3.2, regardless of the flavor.

### What I could NOT establish

- **[U]** Whether the framework class `android.location.Geocoder` is the product the terms call "Android Geocoder SDK
  (Restricted GA)". The name matches in spirit and the cache rule mentions "formatted_address" and "structured address
  values", but the terms never name the class `android.location.Geocoder`, and the framework class is a platform API
  (Apache 2.0 AOSP code) whose backend is merely *provided by* Google on GMS phones. A web search for the exact product
  name returned no Google documentation page.
- **[U]** Whether an app that only calls the platform class, without a Maps Platform account or API key, is a "Customer"
  bound by these Service Specific Terms at all. The Google Play services terms page
  (`developers.google.com/android/guides/terms`) did not render in this session and so could not be read.
- **[U]** Any Android developer documentation that restricts displaying `Geocoder` results on a non-Google map: none
  found in the Javadoc above, which contains no such clause.

Reading it conservatively: the only Google-published clause that mentions an Android geocoder says "must not use ...
in conjunction with a non-Google map", and this app's whole purpose is a non-Google map. Without a written answer from
Google, assuming the clause applies is the safe position. This is not legal advice: to use the feature, ask Google or
accept the risk.

## 4. Privacy

What is sent where, when the switch is on and the offline search had no result:

- The **query text** exactly as typed, the **locale**, and, if the caller passes it, the **bounding box** around the
  map (about 30 km around the camera in the bench) go from the app to the system location service over Binder
  (`ForwardGeocodeRequest` also carries the calling package name and uid), and from there to the geocoder provider.
  **[V]** by the Geocoder source.
- On a phone with Play Services the provider is Google's, so **Google receives the query text, the bias box, the
  locale, the app's identity and the phone's IP address**, under Google's own privacy policy. UltimateMaps has no
  control over that. **[V]** for the parameters, **[U]** for what Google logs.
- With a different backend (microG Nominatim, ROM-provided) the destination is that backend's server.
- The app itself opens no connection: it is Binder IPC to the system. That is also why it does not appear in
  `NetworkPolicy` as an HTTP host. The prototype therefore takes an `allowed: () -> Boolean` gate so the lead can bind
  it to offline mode and, if wanted, to a `NetworkPolicy.authorize("system-geocoder", ConnectionPurpose.OTHER)`
  entry so that the "possible connections" list (RF-12) shows it. This is open; I did not add a new `ConnectionPurpose`
  value because that touches an exhaustive `when` in Settings and the other agent's work.
- Nothing is stored by this code: no result cache, no query log. The debug bench logs queries and results to logcat
  (debug builds only, started explicitly by the owner).
- Zero telemetry is not violated by the app (no analytics, no remote crash reporting), but the switch moves query text to
  a third party, so the backup schema marks it `NEEDS_CONSENT`: a restored backup never turns it on by itself.
- The switch is default OFF, only exists in the `play` flavor, and its text says in plain words that Google receives the
  query on a Play Services phone. The `foss` flavor has no switch and `PlatformServices.onlineGeocoder` returns null.

## 5. What was built

- `core-search/.../OnlineGeocoder.kt` (pure Kotlin, JVM-tested): `OnlineGeocoder` (minimal local interface: query,
  optional bias box, limit, one callback; unify with the Photon agent's interface), `GeocodeBounds.around`,
  `OnlineGeocodeOutcome` (Found / NoMatch / Failed / Skipped), `GeocodedAddress` (plain copy of the Android `Address`),
  `GeocodedAddressMapper` (Address -> `SearchResult`: name, address line, coordinates; drops results with no or invalid
  coordinates; collapses duplicates), `SystemGeocoderFallback` (gates: switch, allowed, 3-char minimum; one 4 s timeout; one
  delivery) and `GeocoderProbe`.
- `app/src/main/.../search/SystemGeocoderSettings.kt` + `SettingsSchema` entry `system_geocoder/enabled`
  (default false, `NEEDS_CONSENT`, included in the backup file and the coverage tests).
- `app/src/play/.../platform/`: `AndroidGeocoderBackend` (framework `Geocoder`; listener API on 33+, one background thread
  with the deprecated call before), `MainLooperScheduler`, the settings switch composable, and
  `PlatformServices.onlineGeocoder(context, allowed)` (the single integration point; the `foss` variant returns null and
  renders no switch). No `:gms` dependency is used: the framework class needs no Play Services library.
- `app/src/playDebug/.../SystemGeocoderBenchActivity` (debug and play only): see the manual test plan.
- Tests: `OnlineGeocoderTest` (virtual time, fake backend, no threads) and the updated backup schema tests.
- Not done on purpose: nothing in `SearchCoordinator` calls the fallback; wiring ("when the offline search has no result,
  and the owner has the switch on") belongs to the lead together with the Photon fallback.

## 6. Recommendation

**Drop it as a user-facing feature; keep the code behind the default-off switch only as an owner comparison tool.**

Reasons:

1. **Terms risk (section 3).** The only Google clause that names an Android geocoder forbids use with a non-Google map.
   I could not prove the platform class is covered, but I also could not find a clause that clears it, and the app
   *is* a non-Google map. A fallback whose legality is unclear is worse than none for a GPLv3 app published on GitHub.
2. **It sends the query to Google.** The app's value is privacy; the switch is honest and default-off, but it is the
   exact thing the app promises not to do by default.
3. **Weak output.** Addresses only: no category, no opening hours, no place id, an unreliable `featureName` for house
   numbers, unspecified bias. POI and "shop chain near me" queries are the cases that need help most and the ones a
   plain geocoder answers worst.
4. **No guarantee on non-Google phones.** On GrapheneOS/LineageOS without a backend it does nothing; the Photon
   fallback (OSM data, the same on every phone) covers them and does not depend on Google.
5. **Photon is the better primary fallback**; this would at best be a secondary one, in `play` only, and even then only
   if section 3 is cleared.

If the owner wants to continue anyway: ask Google (Maps Platform support or the Play services terms contact) for a
written answer on section 3.2, and only then wire the integration point as *secondary* after Photon.

## 7. Manual test plan for the owner (phone, Spain)

Use a `playDebug` build on a phone with Play Services, and only with the owner's permission to use the device. The bench
sends every query to the phone's geocoder service.

```
am start -n com.qtekfun.ultimatemaps.play/com.qtekfun.ultimatemaps.bench.SystemGeocoderBenchActivity \
  --es q "<queries separated by |>" --es near "40.4168,-3.7038" --ei radius 30000
adb logcat -s UMBENCH   # lines start with "geocoder"
```
(The application id of the play debug build is `com.qtekfun.ultimatemaps.play`; confirm with
`adb shell pm list packages | grep ultimatemaps`.) `--ez probe true` runs only the probe. Run each query once with
`near` and once without; compare latency and the first result with Photon later.

| # | Query | Kind | What to look at |
|---|---|---|---|
| 1 | `Calle de Alcalá 42, Madrid` | street with house number | is the point on the number, not the street centre; `n`, ms |
| 2 | `Paseo de la Castellana 100, Madrid` | street with house number | same, long avenue |
| 3 | `Restaurante Sobrino de Botín, Madrid` | restaurant by name | found at all? coordinates correct? |
| 4 | `Mercadona Getafe` | shop chain + town | which branch, how many results |
| 5 | `Mercadona` (with `near` set) | chain without a town | does the bias box change the result |
| 6 | `Cercedilla` | village | one result, sensible centre |
| 7 | `parada de autobús Plaza de Castilla Madrid` | bus stop | expected to fail or return the square |
| 8 | `Hospital Universitario La Paz, Madrid` | hospital | exact building vs area |
| 9 | `farmacia` (with `near`) | category word only | expected to return nothing useful: confirms no category search |
| 10 | `Plaza Mayor` (with and without `near`) | ambiguous name in many towns | does the bias box select the nearest one |

Also run, without a data connection: query 1 in airplane mode, and read `failed=` and `ms=` (how long until the error),
and `--ez probe true`. Report: for each query the `n`, `ms` and the first result; add the phone model and whether Play
Services is present.

## 8. Sources

- AOSP `frameworks/base`, branch `android15-release`, read 2026-10-12:
  `location/java/android/location/Geocoder.java`,
  `services/core/java/com/android/server/location/LocationManagerService.java` (`isGeocodeAvailable`, `forwardGeocode`),
  `services/core/java/com/android/server/location/provider/proxy/ProxyGeocodeProvider.java`.
- Google Maps Platform Service Specific Terms, last modified June 10, 2026:
  https://cloud.google.com/maps-platform/terms/maps-service-terms (section 3, "Android Geocoder SDK (Restricted GA)").
- Google Maps Platform Terms of Service, last modified August 26, 2026: https://cloud.google.com/maps-platform/terms
  ("No Use With Non-Google Maps").
