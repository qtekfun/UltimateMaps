# Verification of the [verificar] points

Date: 2026-10-06. Flow (f). CoMaps clone inspected: `~/repos/comaps-spike`, tag `v2026.10.05-19`, read-only. The `3party/` and `tools/` submodules are NOT initialized in the clone (shallow), so their licenses are taken from `data/copyright.html` and from the upstream project page, not from the code.

Confidence levels: high (license text or official doc read), medium (secondary source or direct inference), low (forum comment or deduction), not verified.

`[verificar]` (to be verified) points located with `grep -n verificar docs/*.md`: feasibility L3 (generic), L35 and L45 (CoMaps license), L60 (Takeout); architecture L53 (FUSED_PROVIDER). The other matches of "verificación" (RF-02) are not markers.

---

## 1. CoMaps code license and compatibility with GPLv3

### 1.1 Is it Apache-2.0? Is it compatible with GPLv3?
- **Answer:** yes to both. `LICENSE` is the Apache 2.0 text; `NOTICE` says "Licensed under the Apache License, Version 2.0" (copyright My.com B.V., Organic Maps Contributors, CoMaps Contributors) and adds that `3party` and `tools` contain third-party libraries with other licenses. F-Droid also declares it `License: Apache-2.0`. Apache-2.0 can be incorporated into a GPLv3 project; GPLv3 cannot be incorporated into an Apache one. Apache-2.0 is NOT compatible with GPLv2 (only): we must pin "GPLv3 or later", never "GPLv2-only".
- **Sources:** `~/repos/comaps-spike/LICENSE:1-3`, `NOTICE:1-20`; https://www.apache.org/licenses/GPL-compatibility.html; https://www.gnu.org/licenses/license-list.html#apache2 ("compatible with version 3 of the GNU GPL"); F-Droid metadata https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/app.comaps.fdroid.yml.
- **Confidence:** high. **Date:** 2026-10-06.
- **Practical nuance:** the CoMaps code keeps its Apache notices (we must keep `NOTICE` and headers); the resulting binary ends up effectively under GPLv3.

### 1.2 Licenses of `3party/` and doubtful subtrees
Inventory of what is in the clone (in-tree) and of the submodules (`.gitmodules`). "copyright.html" = `data/copyright.html`, which is CoMaps' official list of attributions.

| Component | License | GPLv3-compatible? | Evidence | Confidence |
|---|---|---|---|---|
| CoMaps / Organic Maps / MAPS.ME | Apache-2.0 | Yes | `LICENSE`, `NOTICE` | high |
| agg (Anti-Grain Geometry 2.4) | Own permissive (BSD-like, "sell and distribute … as is") | Yes (permissive) | headers `3party/agg/agg_basics.h:1-9` | high |
| agg `agg_conv_gpc.h` | Header that includes `gpc.h` (Alan Murta's GPC, commercial use with permission). **`gpc.h/gpc.c` are NOT in the tree and no source includes the header** | Only if it were used; today it is dead code | `3party/agg/agg_conv_gpc.h:14-26`; search for `gpc`/`agg_conv_gpc` with no other results | medium (confirm with build) |
| **bsdiff-courgette/bsdiff** | **"BSD Protection License"** (2002) | **NO** (designed against "GPL-taint"; clause 4c requires licensing any work containing it under the BPL itself; Fedora: "Free, but GPL-incompatible") | `3party/bsdiff-courgette/bsdiff/LICENCE` (preamble and clauses 3-4); `README.chromium`; https://fedoraproject.org/wiki/Licensing/BSD_Protection_License | high (text read) |
| bsdiff-courgette/divsufsort | MIT | Yes | `.../divsufsort/LICENSE` | high |
| libtess2 | SGI Free Software License B 2.0 | Yes for practical purposes (FSF: "free software license"; GPL-compat not confirmed in the entry read) | `3party/libtess2/LICENSE.txt`; FSF license-list #SGIFreeB | medium |
| monocypher | BSD-2-Clause or CC0 (choose) | Yes | `3party/monocypher/LICENCE.md` | high |
| open-location-code | Apache-2.0 | Yes | `3party/open-location-code/LICENSE` | high |
| succinct | Apache-2.0 | Yes | `3party/succinct/LICENSE` | high |
| ankerl (unordered_dense) | MIT | Yes | SPDX headers in `3party/ankerl` | high |
| skarupke | Boost 1.0 | Yes | headers | high |
| stb_image | MIT or public domain | Yes | `3party/stb_image` ("license information" notice at the end) | medium |
| kdtree++ | Artistic License | Yes if it is Artistic 2.0 (FSF: compatible with GPL); the exact version is not in the tree | header "libkdtree++ is (c) 2004-2007 Martin F. Krafft"; copyright.html | medium-low |
| minizip | Zlib-like (Gilles Vollant) | Yes | header `3party/minizip` | medium |
| opening_hours | MIT (Mail.Ru) | Yes | headers | high |
| GL (Khronos headers) | MIT-like Khronos Materials | Yes | headers `3party/GL` | medium |
| vulkan_wrapper | Apache-2.0 (AOSP) | Yes | headers | high |
| robust (Shewchuk's predicates) | No visible license header in the tree | **Not verified** (Shewchuk's original code is public domain with a request for attribution, from memory) | search with no results | not verified |
| Boost | BSL-1.0 | Yes | copyright.html (empty submodule) | medium |
| expat, glm, jansson, pugixml, glaze, imgui, fast_obj, just_gtfs, harfbuzz | MIT (harfbuzz "Old MIT") | Yes | copyright.html; https://raw.githubusercontent.com/harfbuzz/harfbuzz/main/COPYING | medium-high |
| protobuf, googletest, gflags | BSD-3 | Yes | copyright.html (protobuf); googletest/gflags from memory | medium |
| ICU | ICU/Unicode | Yes | copyright.html | medium |
| FreeType | FTL (or GPLv2+, dual license) | Yes (usable under GPL) | copyright.html "FTL" | medium |
| GLFW | Zlib | Yes | copyright.html | medium |
| utfcpp, Vulkan-Headers | Boost / Apache-2.0 | Yes | copyright.html | medium |
| tools/osmctools (osmconvert, osmfilter, osmupdate) | **AGPL-3.0** | Generation tool, not linked into the app; does not apply if not redistributed | https://github.com/organicmaps/osmctools | high |
| tools/kothic | Not verified (empty submodule, page with no visible license) | — | `.gitmodules`; https://codeberg.org/comaps/kothic | not verified |
| tools/python/stylesheet/webcolors | BSD-3 | Yes | `.../webcolors/LICENSE.txt` | high |
| Android: androidx, Material, Guava, AndroidChart | Apache-2.0 | Yes | `android/app/build.gradle.kts:360-398` and copyright.html | medium |
| Android: `org.microg.gms:play-services-location` 0.3.14.250932 | Apache-2.0 (microG's FOSS client) | Yes | `android/app/build.gradle.kts:375`, `android/gradle/libs.versions.toml:45` | medium |

**Findings that require a decision (summarized in the report):**
1. **bsdiff (BPL) is GPL-incompatible.** It is used in CoMaps for map patches (`mwm_diff`). If the spike chooses option A/C and links `mwm_diff`, the combined binary would contain BPL + GPLv3 code, which cannot be redistributed. Options: do not use differential updates (download the full region), rewrite the diff, or confirm with the author/FSF. Confidence in the incompatibility: high; final legal interpretation: not verified by a lawyer.
2. **Code2000 font** (`data/fonts/06_code2000.ttf`, referenced in `libs/platform/platform.cpp:209`): copyright.html lists it as "Shareware"; FreeBSD ports: "NOT free software". It is not redistributable under GPLv3 nor acceptable for F-Droid (anti-feature `NonFreeAssets`). It must be excluded or replaced. I could not check whether the F-Droid APK includes it (F-Droid uses `scandelete: 3party`, not fonts).
3. **Entypo icon CC BY-SA 3.0** (copyright.html): BY-SA 3.0 is not compatible with GPLv3 (only BY-SA 4.0 is, and in one direction). It only blocks if those icons are copied into our GPL code; it is avoided by not using them or by separating them as assets with their own license.
4. **GPLv2 data** in the generation chain: `gb-postcode-data` (GPL-2.0) in copyright.html. It does not affect the app if only the already generated `.mwm` files are consumed, but it does if we generate the UK maps ourselves.
5. Other attributions that must be reproduced: Font Awesome Free (CC BY 4.0), DejaVu (Bitstream/Tavmjong license), Khmer OS (LGPL), Jomolhari/Padauk (OFL), Roboto/Droid Sans/Material Icons/Remix (Apache-2.0), US Zip Codes (CC BY 4.0), Code-Point Open and FHRS (OGL v3), Wikipedia (CC BY-SA 4.0), SRTM/TIGER (public domain), Mangrove (CC BY / BY-SA 4.0).

- **Sources for 1.2:** `~/repos/comaps-spike/data/copyright.html` (full license list), the cited files, https://www.gnu.org/licenses/license-list.html, https://creativecommons.org/share-your-work/licensing-considerations/compatible-licenses/ (BY-SA 4.0 compatible with GPLv3 in one direction; BY-SA 3.0 not), https://fedoraproject.org/wiki/Licensing/BSD_Protection_License.
- **Date:** 2026-10-06.

### 1.3 License of data and styles
- **Map data (.mwm):** derived from OpenStreetMap, ODbL. "You are free to copy, distribute, transmit and adapt our data, as long as you credit OpenStreetMap and its contributors"; "If you alter or build upon our data, you may distribute the result only under the same license". CoMaps' copyright.html requires "Map data © OpenStreetMap contributors, ODbL". There is auxiliary data with other licenses (see 1.2, point 5). No ODbL license is mixed with the code: the data is downloaded, not incorporated into the binary, so it does not clash with GPLv3. Source: https://www.openstreetmap.org/copyright. Confidence: high for OSM; medium for the other auxiliary sources (read in copyright.html, not at the origin).
- **Styles and symbols (`data/styles`, `data/symbols`, `data/search-icons`):** they have no LICENSE of their own in the tree; they fall under the repo's Apache-2.0 except for the third-party icons listed in copyright.html (Material Design Icons Apache-2.0, Font Awesome Free CC BY 4.0, Evericon CC0, Remixicon Apache-2.0, Entypo CC BY-SA 3.0, Nova MIT). Confidence: medium (inferred from the absence of a local license and from copyright.html).
- **Date:** 2026-10-06.

---

## 2. `LocationManager.FUSED_PROVIDER` (API 31)

- **Question A: does it exist on a phone without GMS?**
  - **Answer:** it may exist, but it is not guaranteed. The official doc says "Added in API level 31 … Standard name of the fused location provider. **If present**, this provider may combine inputs from several other location providers…", and `hasProvider()` "Returns true if the given location provider exists on this device". AOSP includes its own service `com.android.location.fused.FusedLocationService` (package `packages/FusedLocation`, "Fused Location Service that LocationManagerService binds to"), so an Android without GMS usually has a basic fused provider (without Wi-Fi/cell network if there is no network provider). A comment from a GrapheneOS maintainer reports that the OS standard one exists but that "Some OS ship with a broken fused location provider". Deduction: call `hasProvider(FUSED_PROVIDER)` and degrade to GPS/NETWORK; do not assume it.
  - **Sources:** https://developer.android.com/reference/android/location/LocationManager ; https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/FusedLocation/AndroidManifest.xml ; https://github.com/eylenburg/eylenburg.github.io/issues/70 (comments).
  - **Confidence:** high for the official doc ("If present") and AOSP; low for the state on specific ROMs. Not tested on a device without GMS (I have none).
- **Question B: on a phone with GMS, is it backed by Google Play Services?**
  - **Answer:** yes, measured on the Pixel 8 (Android 17, with GMS). `dumpsys location` (read-only) shows `fused provider:` with `identity=10303/com.google.android.gms[fused_location_provider]` and `target service=10303/com.google.android.gms/com.google.android.location.fused.FusedLocationService`. That is, the system provider is served by GMS, not by the AOSP package. CoMaps entry #97 also makes it clear that it was not obvious which backend serves it; the measurement clarifies it for Pixel. I do not generalize to other brands.
  - **Source:** output of `flock /tmp/claude-1000/device.lock adb shell dumpsys location` (saved only in the scratchpad because it contains a real location; not copied here). https://codeberg.org/comaps/comaps/issues/97.
  - **Confidence:** high (one device). Privacy implication: on this Pixel, requesting `FUSED_PROVIDER` makes Google Play Services process the requests; it is not "no dependency on Google", only no Google library in the app.
- **Question C: microG and GrapheneOS.**
  - **GrapheneOS:** redirects app requests to Google Play geolocation to its own implementation on top of the standard location service: "By default, apps using Google Play geolocation are redirected to our own implementation on top of the standard OS geolocation service". Network location is opt-in. That affects apps that link the `play-services-location` library; an app that only uses `LocationManager` uses the OS fused provider. Source: https://grapheneos.org/usage#sandboxed-google-play-configuration. Confidence: high for the redirection; not verified what exactly `hasProvider("fused")` returns on GrapheneOS (no device).
  - **microG:** a comment in the eylenburg issue says that Google's Fused Location API "is supported by Play Services, but not microG" as a system provider (and that it also requires OS support). It is a forum claim, with no reliable date. Confidence: low. CoMaps includes the client `org.microg.gms:play-services-location` (`android/app/build.gradle.kts:375`), which is a client that accesses the installed service.
- **Question D (extra): what does CoMaps do?** In the tree there is a `GoogleFusedLocationProvider` in `android/app/src/google/...` and the `build.gradle.kts` comment describes it as "enabled via microG in all flavors". Issue #97 links PR #3319 "Always use fused location, remove setting and add warning" (only the title read). Final behavior not verified.
- **Decision that stands:** `FUSED_PROVIDER` if `hasProvider`, and GPS+NETWORK as a fallback. Add to the spike a test on a phone without GMS (there is none) or an AOSP emulator without GMS.
- **Date:** 2026-10-06.

---

## 3. Google Takeout: format of lists and favorites

No real export at hand. Everything that follows comes from documentation and secondary public sources; **it must be checked against a real Takeout before fixing the parser**.

- **Questions and answers:**
  - Product "Saved" → one CSV per list (Favoritos, Quiero ir, own lists…; i.e. Favorites, Want to go); columns: title and Google Maps URL (the Google Help thread describes it as "csv file with titles and urls"; one source adds a notes column, `Title,Note,URL`, **not checked**). Normally without latitude/longitude. Confidence: medium. Sources: https://support.google.com/maps/thread/7226226/your-places-saved-lists-will-not-appear-on-takeout ; https://exportmymap.com/blog/google-takeout-saved-places-json-vs-csv/ ("CSV rows generally have no latitude or longitude"); https://github.com/orgs/organicmaps/discussions/928.
  - Product "Maps (your places)" → `Saved Places.json`, a GeoJSON (`FeatureCollection`) of "starred places and place reviews". Each `feature` carries `geometry.coordinates` (lon, lat) and `properties` with `date`, `google_maps_url` and `location` (address, name, country). Confidence: medium (fragments from public sources: ExportMyMap, an r/shortcuts thread and OrganicMaps #928).
- **Nuances to keep in mind (all from secondary sources, low-medium confidence):**
  - `location` may come empty and the geometry may be `0,0` or missing for some places; there is no way to confirm it without a real export.
  - The current lists ("Favoritos" with a star, "Quiero ir") may be in the CSV and not in the GeoJSON; an OrganicMaps thread says that "saved places are exported in CSV format only" and a Reddit one that the Maps Takeout "does not contain" the Favoritos list. **It partially contradicts the doc's sentence** ("Los favoritos con estrella sí traen coordenadas en GeoJSON", i.e. starred favorites do carry coordinates in GeoJSON): perhaps only legacy starred places are in GeoJSON.
  - Some exports are also offered in KML.
- **Could not be checked:** exact CSV header (`Title,Note,URL,Comment`?), encoding, whether the CSV includes `Tags`, URL format (`maps.app.goo.gl`, `?cid=`, `/maps/place/...!1s0x...`), which places carry coordinates, localization of the list names (in Spanish: "Favoritos", "Quiero ir"). Requires a real export from the user (RF-09).
- **Date:** 2026-10-06.

---

## 4. Other

### 4.1 F-Droid policy on native dependencies, submodules and anti-features
- **Answer:** the policy requires FLOSS; it forbids proprietary libraries (Play Services, Firebase, etc.); binary dependencies must be built from source or come from Debian or authorized repos; submodules are not mentioned in the policy (but CoMaps uses them: `submodules: true` in fdroiddata, plus `scanignore: data/*.bin` and `scandelete: 3party` in old builds). Native compilation with the NDK from source (Boost, Qt, etc.) is accepted in practice: CoMaps is on F-Droid. The relevant anti-features: **TetheredNet** ("Apps that depend entirely on a service which is impossible (or not easy) to replace"), **NonFreeNet** (today "Non-Free Network Services": "promote or depend entirely on a proprietary network service"), **NonFreeAssets**, **NonFreeDep**, **Tracking**, **UpstreamNonFree**. Errata in the doc: it uses "no anti-features" (RNF-04) without specifying; CoMaps carries **TetheredNet** ("Map download service (cdn*.comaps.app)", issue #41), not NonFreeNet.
- **Sources:** https://f-droid.org/docs/Inclusion_Policy/ ; https://f-droid.org/docs/Anti-Features/ ; https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/app.comaps.fdroid.yml ; https://codeberg.org/comaps/comaps/issues/41.
- **Confidence:** medium-high. The exact text of the policy on submodules and native blobs was not read in full (only a model summary); review in F5.

### 4.2 CoMaps map CDN: conditions, mirrors and server change
- **Answer:**
  - **Changing server: yes.** Android has "Custom Map Server" since December 2025 (settings, world map download screen). The URL must be HTTP/HTTPS with the structure `/maps/<YYMMDD>/<Region>.mwm`. In the clone: `private.h:13-14` defines `METASERVER_URL "https://cdn-us-1.comaps.app"` and seven default URLs (several third-party: `comaps.openstreetmap.fr`, `comaps-it1.unfoxo.it`, `cloud.ru`, `firewall-gateway.de`…).
  - **Hosting mirrors: allowed in practice and documented, with no written terms of use.** CoMaps documents how to host your own server with community tools (`comaps-map-distributor`, `comaps-server`) and says they are "mostly designed for serving files over a local network". I have not found CDN terms or a usage policy (bandwidth, public redistribution). Issue #41 mentions private and public mirrors as desirable. The data licenses (ODbL) allow redistribution with attribution.
  - **Important technical restriction:** "CoMaps will still reject map file downloads that do not match the checksum from the `countries.txt` file bundled inside the app" and the files must be "the officially generated by CoMaps". That is, our app can point to a mirror, but the `.mwm` files must be byte-for-byte the official ones and match the app's `countries.txt`; generating our own maps requires another `countries.txt`. This is what would have to be replicated if we choose option A/C.
  - CoMaps' privacy policy does not mention the CDN (it does not mention IP logs). Not verified what the servers log.
- **Sources:** `~/repos/comaps-spike/private.h:13-15`; `docs/DEPLOY_OWN_MAP_SERVER.md`; https://www.comaps.app/support/how-can-i-host-a-custom-map-server-for-downloads/ ; https://www.comaps.app/support/how-can-i-set-a-custom-map-server-for-downloads/ ; https://www.comaps.app/privacy/.
- **Confidence:** high for the mechanism; **not verified** whether CDN terms of use or bulk-download limits exist (if a public mirror is considered, the project must be asked).
- **Date:** 2026-10-06.

### 4.3 Other minor checks
- Android Auto: CoMaps uses `androidx.car.app` (Apache-2.0) in the base flavor (`build.gradle.kts:380-381`). The architecture doc sends it to the `gms` flavor; it might not be needed. Confidence: medium (the Car App Library may require GMS on the device at runtime, not verified).

---

## Proposed edits to the docs (not applied)

1. `docs/mapas-01-viabilidad.md` L35: remove `[verificar]` and specify "Apache-2.0 code (confirmed in `LICENSE`/`NOTICE`, tag v2026.10.05-19); OSM ODbL data".
2. `docs/mapas-01-viabilidad.md` L45: replace with "Apache-2.0 is compatible with GPLv3 (FSF, Apache Foundation) and not with GPLv2-only; the project pins GPLv3-or-later. Exceptions that block if linked: `bsdiff` (BSD Protection License, GPL-incompatible), Code2000 font (shareware), Entypo icons (CC BY-SA 3.0). See `docs/spike/verificaciones.md` §1.2". And create `LICENSES.md` with the §1.2 table.
3. `docs/mapas-01-viabilidad.md` L47 and `mapas-02-requisitos.md` RNF-04: cite anti-features by their current name (TetheredNet, NonFreeAssets, NonFreeDep, Tracking). Add a risk: CoMaps already carries TetheredNet because of the CDN; a mirror or configurable server mitigates it, does not eliminate it.
4. `docs/mapas-01-viabilidad.md` L60 and `mapas-03-arquitectura.md` L100: qualify Takeout: "`Saved` CSV: title and URL (exact header unchecked); `Saved Places.json` (GeoJSON, `Maps (your places)`): coordinates but only starred places and reviews; current favorites might be only in CSV. Verify with a real export before F4".
5. `docs/mapas-03-arquitectura.md` L53: change to "On Android 12+ use `FUSED_PROVIDER` only if `hasProvider()`; with GMS the system fused provider is served by Google Play Services (measured on Pixel 8); without GMS the AOSP fused provider may or may not exist, and behavior on microG/GrapheneOS is low confidence; GPS+NETWORK fallback". Add a settings option "Do not use fused provider" for privacy.
6. `docs/mapas-04-spike.md`: add a step "check linking/use of `mwm_diff`/bsdiff and of the Code2000 font in the spike binary" and "test location on an emulator without GMS".
7. `docs/mapas-05-roadmap.md`: new risk "licenses inherited from CoMaps (bsdiff BPL, Code2000, Entypo)" and "map mirror: no published terms of use; ask CoMaps".
