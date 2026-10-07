# F7. Verification of the optional data sources

Study prior to writing code (branch `spike/f7-sources`). Date of all the checks: **2026-10-07** (unless another is stated). Everything marked "verified" was checked with a real request from this machine or with the official listing cited; the rest is marked "not verified" or "third party". Confidence: **high** (checked live), **medium** (official source but not checked live, or direct inference from data), **low** (third-party source or inference), **not verified**.

The downloaded data was treated as untrusted: files in new scratchpad directories, analysed with `python3 -I`. The repo only holds two small extracts in `docs/phase7/muestras/` (`combustible-extracto.json`, anonymised; `renfe-gtfsrt-extracto.json`, with rounded positions).

Requests made to each server (to record that they were minimal): Ministry, about 18 (one national download plus tests of filters and small listings); Renfe, 6 GTFS-RT files + 1 static GTFS (14 MB) + 4 catalogue queries; Metro Bilbao, 3 RT files + 1 static (1.6 MB); the rest, one probe request per URL.

---

## 1. Fuel prices (Spain, Ministry for the Ecological Transition)

| Question | Answer | Source / command | Confidence |
| --- | --- | --- | --- |
| Real, current URL of the service | `https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/` responds 200. The domain is still `minetur` although the ministry is now MITECO. IIS server, no authentication, `Access-Control-Allow-Origin: *`. | `curl -H 'Accept: application/json' <url>` → `HTTP 200`, 12,225,635 bytes, 1.9 s | high |
| Is it the service the official Geoportal links to? | The Geoportal (`geoportalgasolineras.es`) is the official website; its footer says "Fuente: Datos del Ministerio para la Transición Ecológica" (Source: Data from the Ministry for the Ecological Transition). The datos.gob.es listing for "Gasolineras App" states that it uses "the Geoportal's REST service". **No official documentation of the REST service was found** (neither PDF nor page); the contract was deduced from the responses. | Web search; `https://geoportalgasolineras.es/geoportal-instalaciones/Inicio` | medium |
| Format | JSON (`Accept: application/json`) or XML (`Accept: application/xml`, verified on the by-province-and-product endpoint). With the default accept header it returns JSON. UTF-8. | `curl -H 'Accept: application/xml' …/FiltroProvinciaProducto/28/17` → `Content-Type: application/xml` | high |
| Root structure | `{"Fecha":"07/10/2026 12:02:03","ListaEESSPrecio":[…],"Nota":"…","ResultadoConsulta":"OK"}` | analysis with `python3 -I` | high |
| What `Fecha` means | **Madrid local time of the response itself**, not of the data: two requests 32 s apart gave 12:02:03 and 12:02:35. **There is no per-station date.** The app can only show "downloaded at…", not "price as of…". | `Fecha` in 3 different responses | high |
| Update frequency | The `Nota` field says: "La actualización de precios se realiza cada media hora, con los precios en vigor en ese momento" (Prices are updated every half hour, with the prices in force at that moment). The web Geoportal says "every five minutes". Two different figures; use 30 min as a reasonable minimum. | `Nota` of the JSON; Geoportal text | high (Nota) / medium (5 min) |
| Stations in the national file | **11,499** (all with `Tipo Venta` = `P`, sale to the public). 52 provinces, 3,246 municipalities. Coordinates present in all 11,499. | analysis | high |
| Size of the national file | **12.2 MB** uncompressed. **The server does not compress** (with `Accept-Encoding: gzip` there is no `Content-Encoding` and the size is identical). Compressed locally with gzip it would be 0.72 MB (no use for the download). No `ETag` or `Last-Modified`: no conditional request. | `curl -D` with and without gzip; `gzip.compress` | high |
| Fields of each station | `IDEESS`, `Rótulo`, `Dirección`, `C.P.`, `Localidad`, `Municipio`, `Provincia`, `IDMunicipio`, `IDProvincia`, `IDCCAA`, `Latitud`, `Longitud (WGS84)`, `Horario`, `Margen` (D/I/N: side of the road), `Remisión` (`dm`/`OM`), `Tipo Venta`, `% BioEtanol`, `% Éster metílico` and 23 `Precio …` fields (below). Everything is **strings**; `""` = does not sell. | analysis (keys and count) | high |
| Number format | Decimal comma in prices (`"1,849"`, € per litre or kg) and in coordinates (`"39,211417"`, `"-1,539167"`). All 11,499 coordinates and all non-empty prices match `\d+,\d+`. Field names contain spaces, accents and dots. | analysis (0 prices with an odd format) | high |
| Opening hours | Free, unstructured text: `L-D: 24H` (5,212 stations), `L-D: 06:00-22:00`, `L: 06:00-00:00` … It has to be shown as is; interpreting it requires an own parser. | analysis | high |
| Identifier unit | `IDEESS` (numeric string, e.g. `"4375"`) identifies the station; its stability over time was not checked. | sample | medium |

### Exact names of the price fields and stations that have them (11,499 in total)

| JSON field | Stations with a price | JSON field | Stations with a price |
| --- | ---: | --- | ---: |
| `Precio Gasolina 95 E5` | 10,923 | `Precio Gasoleo A` | 11,286 |
| `Precio Gasolina 95 E5 Premium` | 1,119 | `Precio Gasoleo Premium` | 5,915 |
| `Precio Gasolina 95 E10` | 28 | `Precio Gasoleo B` | 2,276 |
| `Precio Gasolina 95 E25` | 1 | `Precio Diésel Renovable` | 1,618 |
| `Precio Gasolina 95 E85` | 2 | `Precio Biodiesel` | 29 |
| `Precio Gasolina 98 E5` | 5,504 | `Precio Adblue` | 2,956 |
| `Precio Gasolina 98 E10` | 17 | `Precio Hidrogeno` | **2** |
| `Precio Gasolina Renovable` | 31 | `Precio Gases licuados del petróleo` (**GLP**) | **999** |
| `Precio Bioetanol` | 1 | `Precio Gas Natural Comprimido` (**GNC**) | **134** |
| `Precio Metanol` | 0 | `Precio Gas Natural Licuado` (**GNL**) | **94** |
| `Precio Amoniaco` | 0 | `Precio Biogas Natural Comprimido` / `…Licuado` | 42 / 50 |

Observations: "gasolina 95" is really `Gasolina 95 E5` (E10 hardly exists); the usual diesel is `Gasoleo A` **without an accent** but the renewable one is `Diésel Renovable` **with an accent**; hydrogen is `Hidrogeno` without an accent. The names are not stable by contract (there is no documentation), so the parser must tolerate new or missing fields and map through its own table (`IDProducto` from the listing below ↔ field). **The roadmap's claim "GLP (999 stations) is a useful source" is confirmed: 999 stations with GLP (8.7 %).** Average of 3.7 prices per station.

### Service by province, municipality and product (whole download or query?)

With a trailing slash `/…/28/` it returns 404; **without the final slash it works**. Verified routes (all 200, JSON):

| Route (after `/PreciosCarburantes/`) | Verified result | Size |
| --- | --- | ---: |
| `EstacionesTerrestres/` | All of Spain | 12.2 MB |
| `EstacionesTerrestres/FiltroProvincia/28` | Madrid (IDProvincia 28) | 946 KB |
| `EstacionesTerrestres/FiltroCCAA/13` | Community of Madrid (matches the province) | 946 KB |
| `EstacionesTerrestres/FiltroMunicipio/4604` | empty response (`ListaEESSPrecio: []`); `4604` was an ID I made up, **the format of the municipality filter has not been tested with a valid ID** | 254 B |
| `EstacionesTerrestres/FiltroProducto/17` (GLP) | **999 stations**; each carries a single `PrecioProducto` field (instead of the 23 `Precio …`) | 377 KB |
| `EstacionesTerrestres/FiltroProvinciaProducto/28/17` | GLP in Madrid | 50 KB |
| `Listados/ProductosPetroliferos/` | catalogue with `IDProducto` and `NombreProducto` | small |
| `Listados/MunicipiosPorProvincia/28` | municipalities of Madrid | 22 KB |
| `Listados/Provincias/` (with slash; without it gives 307) | provinces | small |

Verified product IDs: 1 G95E5, 23 G95E10, 24 G95E25, 25 G95E85, 20 G95E5+, 3 G98E5, 21 G98E10, 4 Gasóleo A, 5 Gasóleo Premium, 6 Gasóleo B, 7 Gasóleo C, 16 Bioetanol, 8 Biodiésel, **17 GLP**, **18 GNC**, **19 GNL**, **22 Hidrógeno**, 26 Adblue, 27 Diésel renovable, 28 Gasolina renovable, 29 Metanol, 30 Amoniaco, 31 Biogás GNC, 32 Biogás GNL.

**Consequence for privacy (design decision):** filtering by province or municipality reveals to the server roughly where the user is; that clashes with "the location never leaves the device". Filtering **by product** (`FiltroProducto/{id}`) does not reveal location, only which fuel is of interest, and it shrinks the download a lot: GLP 377 KB, against 12.2 MB. Recommendation: **query by product** for minority fuels (GLP/GNC/GNL/hydrogen: < 0.4 MB) and **whole national download** for gasoline/diesel (the product filter for gasoline 95 E5 or gasóleo A downloads almost the whole base, estimate ≈ 3-4 MB versus 12.2; **not measured**). In both cases the radius filtering is done on the device.

### Space on the device (calculated with the real file)

| Representation | Size | Notes |
| --- | ---: | --- |
| National file as is (JSON) | 12.2 MB | do not store it |
| All stations, useful fields only (id, brand, address, locality, coordinates, hours + 23 prices) in compact JSON | 2.58 MB (0.47 MB with gzip) | calculated |
| Estimated binary without address (id, coordinates, indexed brand and hours, 23 prices of 2 bytes) | ≈ 0.7 MB | **estimate** |
| Only one fuel (e.g. GLP, 999 stations) | ≈ 0.1-0.2 MB | **estimate** from the size of `FiltroProducto/17` (377 KB with all the text fields) |
| Only the stations within a 25-50 km radius | tens of KB | **estimate** (whole province of Madrid = 946 KB) |

Size conclusion: storing the file already reduced to the useful stations and prices costs **less than 3 MB** with no special format; the 12.2 MB download every 30 min or every hour is the expensive part (mobile data). That is why the frequency setting should default to "manual / daily / wifi only".

### Usage limits, headers and licence

| Question | Answer | Source | Confidence |
| --- | --- | --- | --- |
| Usage limits | **None published or observed**: no `RateLimit` headers, no `Retry-After`, no key, 18 requests without incident. Relevant headers: `Cache-Control: private`, `Access-Control-Allow-Origin: *`. Not seeing a limit is not a guarantee; be polite (≤ 1 whole download per hour and a local cache) | `curl -D` | high (observed) / not verified (policy) |
| Licence / reuse conditions | **Not verified.** No official page with the REST service's conditions was located. (a) The datos.gob.es listing that the searches cited (`e05068001-precio-de-carburantes-en-las-gasolineras-espanolas` and `e0dat0002-geoportal-gasolineras`) **no longer exists**: 404 page and `apidata` API with `items: []`. (b) A third-party listing (Apify, `lafabbricallc/spain-fuel-station-prices`) states, without an official link, reuse under Law 37/2007 and the attribution "Ministerio de Industria, Comercio y Turismo - Precios de carburantes en estaciones de servicio". That is **not a reliable source** and the ministry's name no longer matches. (c) No reuse licence or regulation that sets it has been read. (d) The Geoportal says: "La información publicada en este sitio web tiene únicamente carácter informativo y no constituye una comunicación de actos administrativos ni comunicados oficiales" (The information published on this website is for information purposes only and does not constitute a communication of administrative acts or official communiqués). | datos.gob.es (404 / empty API), Geoportal, Apify | not verified |
| Recommended attribution until clarified | "Datos del Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Gasolineras)" (Data from the Ministry for the Ecological Transition and the Demographic Challenge (Gas Stations Geoportal)), with the download date and the notice that it is informational. Before publishing the feature it is advisable to ask the Ministry through the official route (MITECO's electronic office) or locate the reuse policy; which contact channel exists was not checked. | — | recommendation |

**What it blocks:** nothing technical. What remains pending is clarifying the licence in writing before publishing the version with F7 (low legal risk, because the data is of mandatory publication and public use, but today it is not verified).

---

## 2. Cercanías (Renfe)

| Question | Answer | Source / command | Confidence |
| --- | --- | --- | --- |
| Is there public real time? | **Yes**, three Cercanías GTFS-Realtime feeds: trip updates, vehicle positions and alerts. | `https://data.renfe.com/api/3/action/package_search?q=gtfs` | high |
| URLs | `https://gtfsrt.renfe.com/trip_updates.pb` · `…/vehicle_positions.pb` · `…/alerts.pb`; all three also as `.json` (same name). Long-distance real time separately: `trip_updates_LD.pb/.json` (not tested: outside F7). | CKAN of data.renfe.com + `curl` (200 on all 6) | high |
| Format | **Protobuf (GTFS-RT 2.0)** and equivalent **JSON** (`gtfsRealtimeVersion: "2.0"`; numbers come out as strings). The JSON avoids depending on a protobuf library but weighs ≈ 5× more (trip updates: 19.9 KB `.pb` versus 111.8 KB `.json`). | `curl`, JSON header | high |
| Key or registration? | **No.** Anonymous download over HTTPS, no cookie or header. | `curl` without credentials → 200 | high |
| Usage limits | Not published or observed. `Cache-Control: public, max-age=30`; `ETag` and `Last-Modified` present (conditional requests can be used). | headers | high (headers) / not verified (policy) |
| Refresh frequency | The listing says "Esta información se actualiza **cada 20 segundos**" (This information is updated every 20 seconds) (trip updates and alerts) and the server cache is 30 s. `Last-Modified` of trip updates and vehicles matched the instant of the request; that of alerts was 2.5 h earlier (alerts change only when there are notices). | CKAN + headers | high |
| Licence | **Creative Commons Attribution 4.0** (CC BY 4.0) on the listings of the three feeds, of the static one and of the station list. Requires attribution to Renfe; the exact attribution text is **not specified** in the listing. | `license_title`/`license_url` of the CKAN | high |
| What each feed contains | **Trip updates**: 285 trips (at that moment), fields `tripId`, `stopTimeUpdate[].{stopId, arrival.time, arrival.delay}`, `delay` per trip. **But only 235 of the 285 carry 1 single stop** (the next one), 43 carry none and only 6 carry 2 or more. It is not a complete departures board per station. **Vehicle positions**: 256 trains with `latitude/longitude`, `currentStatus`, `stopId`, `timestamp` and `label` of the form `C5-23717-PLATF.(5)` (the **track/platform** is in the `label`, the listing says so). **Alerts**: 69 notices with text in Spanish (`descriptionText`), `activePeriod` and `informedEntity.routeId`. | analysis of the JSON files | high |
| How a station is identified | By the GTFS `stop_id` (5-digit string with a leading zero, e.g. `"04040"` = Zaragoza Delicias). The 220 `stopId` of trip updates and the 193 of positions **all exist** in `stops.txt` of the static GTFS (1,141 stops, with name and coordinates). Renfe also publishes `estaciones.csv` (complete list of Renfe stations, CC BY 4.0). | cross-check of the JSON files with `stops.txt` | high |
| Static GTFS | `https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip`: **14.1 MB** compressed; `stop_times.txt` takes **243 MB** uncompressed, `trips.txt` 17 MB (115,051 trips), `shapes.txt` 4 MB; 828 routes. Updated daily (`Last-Modified` 02:08 UTC of the same day). Fields come **padded with spaces** (they must be trimmed): untrimmed, only 217 of the 285 real-time `tripId` matched the static one; trimmed, **all 285 match**. | `curl -I`, `zipfile`, cross-check | high |
| Cores covered | In the 285 trips of the feed there are routes of **15 core prefixes** (`10`, `20`, `30`, `31`, `32`, `40`, `41`, `45`, `46`, `47`, `51`, `60`, `61`, `62`, `70`; the `route_id` starts with that code and the short name is C1…, R1…). I only confirmed by line name (`C1 Príncipe Pío-Aeropuerto T4`, `R1…`) that `10` is Madrid and `51` Rodalies de Barcelona; **the correspondence of the other prefixes to cities is not verified** (probably Asturias, Sevilla, Cádiz, Málaga, Valencia, Murcia/Alicante, Cantabria, Zaragoza…). Train labels in the feed: lines C1-C10, R1-R17, T1, RL4, etc. The FEVE/narrow-gauge cores and Rodalies de Catalunya appear mixed in a single feed. | analysis of routes and labels | medium |
| Size of each request | 20-110 KB; one refresh of the 3 alerts+trips+positions as `.pb` ≈ 85 KB. | `curl` | high |

**Feasibility for "next trains at a station":** both real time (delays) **and** the static GTFS (scheduled times per stop) are needed, because the live feed almost only carries each train's next stop. The static one weighs 14 MB compressed and 243 MB of `stop_times` uncompressed: **it cannot be indexed whole on the phone without work** (it must be read in streaming, keeping only the stops of the chosen core and storing them in a compact index, with a manual or weekly download). A cheaper (less functional) alternative: show only vehicles heading to the station (`stopId` of positions/next stop) with their delay and the core's alerts; no scheduled timetable. Both options avoid sending the location (the whole feed is downloaded, the same for everyone).

**What it blocks:** nothing key-related. Pending product decision: complete board (more work, static 14 MB) or "approaching trains + notices" (much simpler).

---

## 3. Metro and other operators

Distinction: **static** = timetables/stops (GTFS); **real time** = arrival predictions (GTFS-RT or another API).

| Operator | Static | Real time | Key / registration? | Conditions | Verified with | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| **Metro Bilbao** (CTB, Consorcio de Transportes de Bizkaia) | Open GTFS `https://ctb-gtfs.s3.eu-south-2.amazonaws.com/metrobilbao.zip`: 1.67 MB, updated 01:00 UTC of the same day; includes `stop_times.txt` (5.5 MB uncompressed) | **Yes, GTFS-RT without a key**: `https://ctb-gtfs-rt.s3.eu-south-2.amazonaws.com/metro-bilbao-trip-updates.pb`, `…-vehicle-positions.pb`, `…-service-alerts.pb`. In the test: trip updates 15 KB, 73 trips and 660 `stopTimeUpdate` (**complete sequence of stops per train**, with arrival time; 41 distinct stops), vehicles 1 KB, alerts empty (0 entities, 15 bytes) | **No** (anonymous HTTP 200, hosted on S3). The RT `stop_id` (`"10.0"`) match those of the static one (`1.0`, `34.0`…) | **CC BY 4.0**; general conditions (art. 8 Law 37/2007): do not alter the content, do not distort the meaning, **cite the source and the date of the last update** | `curl` + hand decoding of protobuf; `https://data.ctb.eus/en/pages/legal-notice` | high |
| **Metro de Madrid** (CRTM) | Open GTFS "GTFS Red de Metro" (listing on `datos.crtm.es`; ArcGIS item `5c7f2951962540d69ffe8f640d94c246`, last modified 2025-05-30; the Mobility Database catalogue points to `…/items/357e63c2904f43aeb5d8a267a64346d8/data` and to `…/885399f83408473c8d815e40c5e702b7/data`); **anonymous download, no key** (auth=0 in the catalogue; not downloaded by me) | **There is no public GTFS-RT for Metro de Madrid** (it is not in the Mobility Database catalogue or in the CRTM open data). The official app uses an undocumented "teleindicadores" service: `https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/<code>` — third parties cite it as "no key"; **tested now: it responds, asks for no credentials, but returns error 400 with a Node trace (`RangeError: Maximum call stack size exceeded`)** for the station codes I tried (`par_4_156`, `par_4_1`). No documentation or published conditions | Static: **no**. Real time: no official registration (there is no Metro de Madrid developer portal that I found) | **CRTM static-data licence** (`https://www.crtm.es/licencia-de-uso`, read in full): commercial and non-commercial use permitted; you must **cite "Powered by CRTM" with a link to crtm.es**, state whether the data is raw or processed, keep the date metadata, not imply sponsorship, guarantee that the information shown is up to date; **CRTM monitors access and may block the reuser for abusive use**; share the copied data **under the same type of licence**. It does not cover the teleindicadores service (it is not static data from the CRTM website) | licence page; ArcGIS; `curl` to the endpoint | licence and static: high; official real time: **no public one exists**; unofficial endpoint: **not verified** (it did not work) |
| **EMT Madrid** (bus; not metro, for CRTM context) | Open GTFS `https://servicios.emtmadrid.es:8443/gtfs/transitemt.zip` (auth=0 according to the catalogue; not downloaded) | `https://openapi.emtmadrid.es/v1/bus/servicealerts/proto`: **200 anonymous** (47 KB, service alerts, does not include arrivals). Per-stop arrivals from the MobilityLabs API **require registration and `X-ClientId`/`passKey`** (EMT documentation: "Mandatory register your application…") | Yes for arrivals (registration + key) | MobilityLabs; `datos.emtmadrid.es` | `curl` + `apidocs.emtmadrid.es` | high (alerts without a key; arrivals with a key) |
| **TMB (Barcelona Metro and bus)** | GTFS via `https://api.tmb.cat/v1/static/datasets/gtfs.zip?app_id=…&app_key=…`: **requires `app_id` and `app_key`** (registration at `developer.tmb.cat`) | `https://api.tmb.cat/v1/ibus/…` and `/v1/transit/…`: **401 "Authentication failed. Authentication parameters missing"** without a key | **Yes, both** | Developer portal conditions not read: **not verified** | `curl` (401) + Mobility Database catalogue | high (needs a key) |
| **Metro de Valencia (FGV)** | GTFS published on the **NAP** (`nap.transportes.gob.es`, file 1168): the catalogue indicates it requires a **key in an HTTP header** (`auth=2`) | **There is no FGV GTFS-RT in the catalogue** | Yes (NAP) | NAP licence: `nap.transportes.gob.es/licencia-datos`, **not read** | Mobility Database catalogue (`feeds_v2.csv`); not checked live | medium |
| **Metro de Sevilla** | GTFS on the NAP (file 1583), with a key in a header | No GTFS-RT in the catalogue | Yes (NAP) | NAP, not read | catalogue, not checked live | medium |
| **Euskotren, Metro de Madrid Ligero** (others) | Euskotren on the NAP (key); Metro Ligero on open CRTM | no public RT in the catalogue | Euskotren: yes; Metro Ligero: no | respective | catalogue | low |

Notes: (1) **The Mobility Database is not a primary source**; it was used to discover URLs and everything cited as "checked" was verified live (Renfe, Bilbao, EMT, TMB). (2) The NAP (national access point for transport data of the Ministry of Transport) requires registration and an API key; for F7 it would be a "key supplied by the user" and not "embedded". (3) **There is no metro source with public, official, keyless, tested real time except Metro Bilbao.**

---

## 4. Conclusion

### Is each source viable under the project's rules?

Rules: no embedded key, no sending the user's location, whole download or per station.

| Source | No key? | No sending location? | Whole download / per station? | Verdict |
| --- | --- | --- | --- | --- |
| Fuel (Ministry) | Yes | Yes if `EstacionesTerrestres/` (whole) or `FiltroProducto/{id}` is used; **not** `FiltroProvincia`/`FiltroMunicipio`, which filter by area | Whole (12.2 MB) or per product (GLP 377 KB) | **Viable.** Pending: licence in writing |
| Cercanías Renfe | Yes | Yes: a single feed, the same for everyone | Whole feed (20-110 KB each, refresh every 30 s) + static GTFS (14 MB) | **Viable.** A complete board requires indexing the static one; the reduced version (approaching trains + notices) is simple |
| Metro Bilbao | Yes | Yes | Whole feed (15 KB) + static 1.7 MB | **Viable, the cleanest.** Only covers Bilbao |
| Metro de Madrid | Static yes; real time: no official one | Yes | — | **Real time not viable** today (the unofficial endpoint fails and has no conditions). Only timetables (static GTFS) if wanted |
| TMB (Barcelona) | **No: key and registration** | — | — | **Only with a key supplied by the user**; not offered by default |
| Metro Valencia / Sevilla | **No: NAP key** | — | — | Static only and with the user's key; no real time |

### Recommended implementation order

1. **Fuel** (`:source-fuel`): maximum value, format and service verified, no key risk. Start with the fuel chosen per product and local radius filtering.
2. **Generic GTFS + GTFS-RT module** (`:source-transit-gtfs`) with **Metro Bilbao** as the first operator (small static, complete real time, clear licence): it serves to validate the engine and the interface.
3. **Cercanías Renfe** on the same module (own configuration: URLs, JSON or protobuf, trimming of spaces in the static one, per-core index).
4. The rest (TMB, Metro de Valencia/Sevilla) **only if the user supplies their key**; Metro de Madrid in real time: discard or revisit if an official API appears.

### Minimal data schema for `OptionalDataSource`

What is strictly necessary according to what was verified, on top of what the architecture already says:

```
SourceDescriptor
  id                 // "fuel-es", "transit-renfe", "transit-metro-bilbao"
  displayName
  hosts[]            // for NetworkPolicy: sedeaplicaciones.minetur.gob.es, gtfsrt.renfe.com, ssl.renfe.com, ctb-gtfs-rt.s3.eu-south-2.amazonaws.com, ctb-gtfs.s3.eu-south-2.amazonaws.com
  whatIsSent         // notice text: "the whole file is requested; the server sees your IP; your location is not sent"
  attribution        // licence text ("Renfe, CC BY 4.0", "CTB, CC BY 4.0", "Ministerio ...")
  licenseUrl
  requiresUserKey    // false by default; if true, the key is supplied by the user (Keystore)
  refresh: { minIntervalSec, defaultIntervalSec, wifiOnlyDefault }
  settingsFields[]   // drawn by the Settings screen: toggle, fuelProducts[], radiusKm, operators[], baseUrl

FetchResult<T>
  data               // common model: Station(id, name, lat, lon, kind), Offer(stationId, product, price, unit)
                     // or Departure(stationId, line, headsign, scheduledEpoch, estimatedEpoch, platform?)
  fetchedAtEpoch     // when it was downloaded (it is the only thing known about the Ministry)
  sourceTimestampEpoch? // GTFS-RT header.timestamp; null for fuel
  validUntilEpoch    // for the cache with expiry
  error?             // isolated failure; the source shows the last good data

Capabilities per source:
  fetchAll(): FetchResult<List<Station+Offer>>   // fuel
  fetchForStation(stationId): FetchResult<List<Departure>>  // transport (over the whole feed)
```

Rules that follow from what was verified: (a) trim spaces in Renfe's GTFS fields; (b) convert the decimal comma in the fuel parser; (c) own product ↔ JSON field table (the names are irregular); (d) in transport, do not retry before 30 s (`max-age=30`); (e) show attribution and data date in the UI (CC BY 4.0 and the CRTM licence require it); (f) protobuf dependency: Renfe offers JSON (no library), Bilbao only protobuf; choose `protobuf-javalite` or write a minimal reader of the GTFS-RT schema; **the dependency's licence has not been checked** (review per R8 in `LICENSES.md`).

### Effort estimate (these are estimates, not measurements)

| Block | Days |
| --- | ---: |
| `:core-settings` + `:core-optional` (interface, registry, cache, NetworkPolicy, Settings screen drawn from fields) | 5-7 |
| `:source-fuel`: download, parser, radius filtering, map layer and card, fuel/radius/frequency settings | 6-9 |
| `:source-transit-gtfs` + Metro Bilbao (GTFS-RT parser, static index, station UI) | 6-8 |
| Renfe Cercanías on top of the module (with complete board and per-core index from the static one) | 5-8 |
| Renfe Cercanías in the reduced version (approaching trains + notices) | 2-3 (instead of the previous line) |
| Operator with the user's key (TMB, for example) | 3-4 each |
| `PRIVACY.md`, connection list, notice texts, tests | 2-3 |

Indicative total for fuel + Bilbao + Renfe with a complete board and the Settings infrastructure: **≈ 24-35 days** of work; fuel only: ≈ 11-16 days with the minimal infrastructure.

### What was refuted or corrected with respect to the roadmap

- "Download the national file and filter locally": **correct, but the server does not compress** (12.2 MB real per download) and `FiltroProducto/{id}` exists (GLP 377 KB), which improves the case of minority fuels without sending location.
- "A daily snapshot, not real time" (roadmap, about fuel): the source says it updates **every half hour**; it is still not guaranteed real time and there is no per-station date.
- "Renfe open data (timetables and real time in GTFS format)": **correct** and **keyless** (CC BY 4.0), but Renfe's real time is not enough for a per-station departures board without the 14 MB static GTFS.
- "Metro (Madrid and others): start with the one that has open keyless data": **the one that does is Metro Bilbao**, not Metro de Madrid (which publishes no open real time).
- Fuel licence: **not verified** (the datos.gob.es listing no longer exists).

### Pending / not verified

1. Exact licence and attribution of the fuel REST service (ask the Ministry).
2. That the municipality filter works (tested only with an invalid ID); stability of `IDEESS`; real size of `FiltroProducto/1` and `/4`.
3. Correspondence of Renfe's core prefixes to cities; exact Renfe attribution text.
4. Usage limits not published by any of the sources.
5. Metro de Madrid in real time: no official source; the app's endpoint (undocumented) returned an error.
6. Conditions of TMB, NAP, FGV and Metro de Sevilla (not read); CRTM GTFS not downloaded.
7. Licence of the chosen protobuf library.
