# Open data sources beyond what exists today

Status: research and plan only, 2026-10-08. No app or pipeline code changed. Effort numbers are rough **estimates** in person-days (one developer who knows the codebase and the `UltimateMaps-data` workflow; they include a converter script, tests, strings and docs, and exclude device testing). File sizes are **estimates unless a source is given**.

## 0. Method, scope and how to read the evidence

Read first, so nothing here repeats it: `docs/phase2/feature-gap-analysis.md`, `docs/phase2/next-plan.md`, `docs/phase2/transit.md` (Madrid GTFS, NAP licence page already read), `docs/phase2/cameras-data.md`, and the data repo workflow `~/mapas-data/repo-UltimateMaps-data/.github/workflows/weekly-data.yml` (steps today: Madrid transit index, Protomaps PMTiles split per region, CoMaps `.mwm`, speed cameras `speedcams-es.bin`, EV chargers `chargers-es.bin`, catalog with optional blocks `--cameras-file`, `--chargers-file`, `--transit-file`).

Already covered elsewhere (only referenced here): Madrid transit (CRTM/EMT/Cercanias), fuel prices, DGT cameras and incidents, EV chargers, speed limits and lanes from OSM, search categories (S2), opening hours display (S3) and place extras (S5), terrain/contours as a feature (M3), cycling/hiking overlays as a feature (M4), Wikivoyage guides (S8), level crossings and hazard warnings (X4), water points and shelters (X6), a transit planner for other cities as "later" (N10). This document adds the **data sources, licences and pipeline fit** for those features and for new ones.

Evidence levels used in the "Verif." column and the text:

- **R** = the primary page was fetched and read in this session (NAP licence page, Renfe open-data page, the MobilityData GBFS `systems.csv`).
- **S** = taken from a web-search result summary; the primary page was **not opened**. Treat as a lead to confirm before shipping.
- **U** = general knowledge, **not verified**.

Tooling note: the search tool returns summaries, so several licence texts could not be read in full. Wherever a licence is "S" or "U", the owner action list in section 9 asks for confirmation before any redistribution.

## 1. Executive summary

1. **Biggest win: more cities of public transport through the Spanish National Access Point (NAP).** One licence text covers a large part of the Spanish feeds (R: https://nap.transportes.gob.es/licencia-datos: commercial reuse allowed, attribution "Powered by MITRAMS" with a link, metadata must be kept, redistribution allowed, derived works that add value may carry other licences, "as is", and a stricter source licence prevails). The Mobility Database lists NAP-sourced GTFS for Barcelona/Catalonia (ATM), Valencia (FGV Metrovalencia), Sevilla (Tussam, Metro), Zaragoza, Bilbao (Bilbobus), Salamanca, Toledo and others (S: https://mobilitydatabase.org/feeds/gtfs/mdb-2808, https://mobilitydatabase.org/feeds/gtfs/mdb-2770). Downloads need a **registered account and a header token** (S: Mobility Database feed pages; also noted in `docs/phase2/transit.md`: HTTP 401 on a probe). This reuses the transit tool already built for Madrid, but it needs a planner that scales from one city to many (index per city) and an owner action (NAP account + a CI secret).
2. **Cheapest big value: an "OSM extras" block** (opening-hours text, wheelchair, toll, level crossings, height/weight/HGV restrictions, drinking water, toilets, ZBE polygons found in OSM). All of it comes from the Spain extract the workflow already downloads for cameras and chargers, so ODbL is already handled and the effort is mostly converters.
3. **Elevation** (contours, hillshade, elevation profile for bike/hike): Copernicus DEM GLO-30 (free licence with mandatory attribution, open S3 bucket, S: https://registry.opendata.aws/copernicus-dem/) or Spain's IGN MDT (CC BY 4.0, 5 m, S: https://datos.gob.es/es/catalogo/e0dat0002-modelo-digital-del-terreno-con-paso-de-malla-de-5-metros-mdt05-de-espana1). Produce vector contours and a small elevation grid per region in the pipeline.
4. **Hiking/cycling route relations** from OSM, extracted per region (a Waymarked-Trails-like overlay without depending on that site).
5. **Renfe long and medium distance GTFS plus Cercanias/Rodalies** and GTFS-Realtime (R: https://data.renfe.com/dataset lists them; **the page states no licence**, so this one needs written confirmation).
6. **Things to avoid or defer**: WDPA protected areas (non-commercial, no redistribution, S: https://www.protectedplanet.net/c/terms-and-conditions), Wikivoyage text (CC BY-SA 3.0 cannot go into GPLv3 through CC's mechanism, S: https://wiki.creativecommons.org/wiki/ShareAlike_compatibility:_GPLv3), Open-Meteo free API (non-commercial only, S: https://open-meteo.com/en/terms), Overture Places as a POI source (large, dedupe against OSM, quality unknown).

### Ranked top 10

| Rank | Candidate | Why | Licence in one line | Effort (est.) | Priority |
| --- | --- | --- | --- | --- | --- |
| 1 | NAP GTFS for Barcelona, Valencia, Sevilla, Bilbao, Zaragoza (+ Malaga/TITSA from city portals) | Transit outside Madrid is the main gap vs Google/Apple | NAP open licence, "Powered by MITRAMS" (R) | 15-25 for 3 cities incl. multi-city index; +3-5 per extra city | next |
| 2 | OSM extras block (opening hours, wheelchair, toll, crossings, HGV limits, water, toilets) | Place-card and navigation quality at near-zero licence cost | ODbL, already in use (R in CLAUDE.md) | 6-10 total | now |
| 3 | Elevation: contours, hillshade, profile (Copernicus GLO-30, IGN MDT) | Bike/hike value; unlocks the elevation profile | Copernicus free licence + attribution (S); IGN CC BY 4.0 (S) | 8-12 | next |
| 4 | OSM hiking/cycling route relations overlay | Outdoor use; no new licence | ODbL | 6-10 | next |
| 5 | Renfe long/medium distance GTFS + national Cercanias + Rodalies | Intercity trips; needed for Spain-wide transit | **Not stated** on data.renfe.com (R); ask Renfe | 5-8 once the planner is multi-feed | next (after licence answer) |
| 6 | ZBE polygons and a "low-emission zone ahead" notice | Practical for drivers; legal risk of wrong data | OSM polygons (ODbL); no national dataset found (S) | 5-8 | next |
| 7 | Bike-share stations (GBFS static) and optional live availability | Urban cycling; uses the same feed standard everywhere | Per system; Bilbao CC BY 4.0 (S); Bicing via Open Data BCN CC BY 4.0 (S) | 4-6 static; +4-6 live | later |
| 8 | AEMET weather alerts (CAP), on demand | Safety for hikers/drivers | AEMET attribution, free API key (S) | 5-7 | later |
| 9 | Wikidata descriptions (CC0) and optional Wikipedia lead text (CC BY-SA 4.0) for place cards | Rich place cards offline | CC0 / CC BY-SA 4.0 one-way to GPLv3 (S) | 6-10 (Wikidata only) ; +8-12 (Wikipedia leads) | later |
| 10 | Spanish address gap-filling (CNIG CartoCiudad, Catastro INSPIRE) | Better house-number search where OSM lacks them | CNIG/Catastro licence text not confirmed (S) | 15-25 (needs core work) | later |

## 2. Cross-cutting rules for every candidate

These come from `CLAUDE.md` and `docs/phase2/next-plan.md`:

- Zero telemetry; network only through `NetworkPolicy`; a feature with a Settings switch is off by default unless it is core UX.
- A source is either **baked weekly** into the data release (the app downloads files the user chose, no location sent) or **fetched on demand at the user's request**. On-demand fetches must download a whole national or system-wide file and filter on the device; never send coordinates, bounding boxes or a province (this is the rule already used for fuel and DGT incidents).
- Every source must be recorded in `LICENSES.md` and in the About/attribution screen before shipping.
- Files built from OSM data are an ODbL "derivative database" and are published with "Data (c) OpenStreetMap contributors (ODbL)" (the existing release notes already say so).

## 3. Part A: public transport, bike sharing and ferries

### 3.1 How the Spanish and European catalogues work

| Item | Facts | Verif. |
| --- | --- | --- |
| NAP Spain (MITRAMS) | Spanish national access point for transport data; download URLs follow `nap.transportes.gob.es/api/Fichero/download/<id>`; authenticated by an HTTP header, registration needed. License page terms listed in section 1. | R licence page https://nap.transportes.gob.es/licencia-datos ; S for URL pattern and auth https://mobilitydatabase.org/feeds/gtfs/mdb-2808 |
| Mobility Database (MobilityData) | Metadata generated by MobilityData is CC0, the API code is Apache-2.0, **feed content keeps the licence of its owner**, users must follow each owner's terms. Feeds are fetched daily at 00:00 UTC. Useful as a **discovery index** of feed URLs and validation reports, not as a data licence. | S https://mobilitydatabase.org/terms-and-conditions , https://github.com/MobilityData/mobility-database-catalogs |
| Transitland | Aggregates feeds under many licences; consumers must confirm each source feed's licence; API users accept Interline terms; its library `transitland-lib` is GPLv3-or-commercial (do not link it into the app without a decision). | S https://www.transit.land/terms , https://www.transit.land/documentation/an-open-project |
| DGT NAP (road traffic, `nap.dgt.es`) | Separate from the transport NAP. DATEX II datasets for incidents, cameras, panels (VMS); licence field says "free of charge", the "conditions of use" field is empty. Dataset versions v3.6 and v3.7 both listed. | S https://nap.dgt.es/en/dataset/paneles-dgt-tiempo-real-datex2-v3-7 |

### 3.2 Per source

Coverage and quality figures are from the Mobility Database listings unless stated. "Real time" means GTFS-Realtime (GTFS-RT) or an equivalent feed.

| Source | Coverage | Licence and attribution | Format and size | Cadence | Real time | Caveats | Verif. |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Renfe long distance, high speed, medium distance | National Renfe services | **Not stated** on the dataset list page ("all rights reserved" footer only); ask Renfe | GTFS zip; size not measured (U) | not stated | Yes: GTFS-RT vehicle positions and trip updates in PB/JSON, "updated every 30 s" for the trip-update feed | A third-party CLI uses this GTFS as the official long-distance dataset | R https://data.renfe.com/dataset ; S https://pypi.org/project/renfe-cli |
| Renfe Cercanias (incl. Rodalies and other nuclei) | Cercanias nuclei | **Not stated** on the page; our Madrid pipeline already downloads `fomento_transit.zip` from `ssl.renfe.com` (see workflow) | GTFS (14 MB, measured in `docs/decisions.md`) | not stated | Yes: trip updates, vehicle positions, service alerts (GTFS-RT, JSON) | Renfe's GTFS **may not match the Generalitat's Rodalies schedules** | R https://data.renfe.com/dataset ; S https://pypi.org/project/renfe-cli ; repo `docs/decisions.md` |
| Rodalies de Catalunya (Generalitat) | Catalonia | No Generalitat GTFS/GTFS-RT found; the ATM feeds listed under NAP probably include Rodalies (not named) | n/a | n/a | none found | Check Generalitat open-data portal | S https://mobilitydatabase.org/feeds/gtfs/mdb-2832 |
| ATM Barcelona (all Catalan buses and trains) | Whole Catalonia; two feeds (simplified 1615 routes, full 2112 routes); service dates up to Dec 2028 | NAP licence (link to `licencia-datos`) | GTFS, size U (estimate tens of MB for the full one) | via NAP | static only found | Large; pick the simplified one first | S https://mobilitydatabase.org/feeds/gtfs/mdb-2826 , https://mobilitydatabase.org/feeds/gtfs/mdb-2832 |
| TMB (Barcelona metro and bus) | Barcelona | **Licence not confirmed** (2014 mailing-list guess of CC BY-SA is not reliable); needs an API key from developer.tmb.cat | GTFS | not verified | not verified | Possibly redundant with ATM feed; skip TMB direct | S https://mobilitydatabase.org/feeds/gtfs/mdb-2359 |
| Metrovalencia / FGV | Valencia metro and tram, 208 routes, service range into mid-2026 | NAP licence; powered by MIMTRANS | GTFS | NAP | not verified | **Feed expiry risk**: service range was reported to end mid-2026 | S https://mobilitydatabase.org/feeds/gtfs/mdb-2830 |
| EMT Valencia (bus) | Valencia city, 47 routes | Licence not confirmed for the GTFS dataset (other EMT datasets on the portal are CC BY 4.0) | GTFS on opendata.vlci.valencia.es | not stated | not verified | Check dataset page | S https://opendata.vlci.valencia.es/en/dataset/google-transit-lines-stops-bus-schedules |
| Sevilla: Tussam (bus, tram), Metro de Sevilla | Sevilla city and metro | NAP licence; no separate licence text found | GTFS | NAP | not verified | Regional consortium (CTMAS) data has an "aviso legal" not read | S https://mobilitydatabase.org/feeds/gtfs/mdb-2770 , https://mobilitydatabase.org/feeds/gtfs/mdb-2781 |
| Bilbao: Bilbobus | Bilbao, 56 routes, 0 errors, 2 warnings | NAP licence (download pattern `api/Fichero/download/...`) | GTFS | NAP | not verified | | S https://mobilitydatabase.org/feeds/gtfs/mdb-2808 (search summary lists it) |
| Basque Country: Moveuskadi (Metro Bilbao, Euskotren, Bizkaibus, Bilbobus, Dbus...) | Basque Country | Open Data Euskadi lists CC BY 4.0 at portal level; the dataset-level licence value was not shown | GTFS, GTFS-RT, SIRI, NeTEx, SHP index | not stated | GTFS-RT and SIRI listed | Bizkaibus GTFS was produced by a contractor; Metro Bilbao feed not confirmed separately | S https://www.euskadi.eus/moveuskadi-datos-de-la-red-de-transporte-publico-de-euskadi-operadores-horarios-paradas-calendario-tarifas-etc/web01-s2ing/es/ , https://datos.gob.es/es/iniciativas/open-data-euskadi |
| Zaragoza (bus and tram) | Zaragoza, 55 routes in the combined feed | NAP licence | GTFS | NAP | not verified | **Service period reported as ended 2026-06-21** (expired) | S https://mobilitydatabase.org/feeds/gtfs/mdb-2801 |
| Malaga EMT | Malaga bus | CC BY 4.0 | GTFS zip on datosabiertos.malaga.eu | portal shows last update 2023-05-05 | no | **Probably stale** | S https://datosabiertos.malaga.eu/en/dataset/lineas-y-horarios-bus-google-transit/resource/2e0463e5-7353-4a8c-b610-f8332100768d |
| Canarias: TITSA (Tenerife) | Tenerife | CC BY | GTFS zip from titsa.com | catalogue entry from 2023 | not verified | Check currency | S https://datos.gob.es/ca/catalogo/la0010696-informacion-sobre-el-sistema-de-transporte-de-titsa-en-tenerife.csv |
| Canarias: Guaguas Municipales (Las Palmas) | Las Palmas, 47 routes, service range to 2035 | not found | GTFS | not verified | not verified | | S https://mobilitydatabase.org/feeds/gtfs/tld-767 |
| Canarias: Global (Gran Canaria) | Gran Canaria | none: only a data request asking for GTFS/GTFS-R publication | none | n/a | n/a | No feed | S https://datos.gob.es/es/solicitud-de-datos/datos-gtfs-y-gtfs-r-en-tiempo-real-de-las-empresas-de-transporte-publico-de-la-isla |
| Other NAP bus feeds seen | Salamanca (109 routes), Toledo (56), Terrassa TMESA (17), A Coruna (25) | NAP licence | GTFS | NAP | n/a | Examples only; a full NAP listing needs a logged-in session | S (first search result) |
| Mallorca consortium | Mallorca | CC BY 4.0 (stated in a search summary) | GTFS | not verified | not verified | Check the Balearic portal | S https://www.tib.org/en/web/ctm/open-data |

### 3.3 Bike sharing and micromobility (GBFS)

The MobilityData GBFS `systems.csv` lists **72 Spanish rows** in the part that was read (R: https://raw.githubusercontent.com/MobilityData/gbfs/master/systems.csv; the file was only read up to character 100000 of 219325, and the Spanish rows were complete). Public bike systems with a standard auto-discovery URL include:

- Bicing (Barcelona), BiciMAD (Madrid), Sevici (Sevilla), Valenbisi (Valencia), Bizi (Zaragoza), Bilbao Bizi, Dbizi (Donostia), Valladolid, A Coruna, Rivas; nextbike-based systems (Palma, Vitoria MugiBIKE, Logrono, Leon, Santander, Las Palmas moxsi and others).
- Shared scooters and cars: Bird, Dott, Cooltra, Getaround appear in the list; they are commercial operators and fleet positions are volatile.

| Item | Detail | Verif. |
| --- | --- | --- |
| Licence | Per system. Bilbao Bizi is listed as CC-BY-4.0. Bicing station information on Open Data BCN is listed as CC BY 4.0 (older datasets); the GBFS licence for Bicing itself was not confirmed. Madrid BiciMAD GBFS is published through datos.madrid.es (Madrid has its own attribution-based licence). | S https://mobilitydatabase.org/feeds/gbfs/gbfs-bilbao ; https://opendata-ajuntament.barcelona.cat/data/en/dataset/informacio-estacions-bicing/resource/a7384eca-550c-4f61-886e-53a726541dff ; https://datos.gob.es/en/catalogo/l01280796-bicimad-gbfs-general-bikeshare-feed-specification |
| Static data | `station_information` (name, position, capacity) is small (kilobytes per city, estimate) and can be baked weekly. | U |
| Live data | `station_status` (bikes and docks available) is the only live part; fetch on demand, whole system file only. Revealing which city the user looks at is a (small) privacy cost. | U |
| Scooters and cars | Skip: operator licences are unclear and fleets move constantly. | U |

### 3.4 Ferries

No open ferry timetable feed for Spain was found in the searches done. Ferry routes exist as OSM `route=ferry` ways that CoMaps routing already supports as an "avoid ferries" option. Timetables: **not verified, no source found**. Priority: no (until a feed exists).

### 3.5 What this means for the planner

`docs/phase2/transit.md` built a schedule-based planner with a custom binary index (`.umti`) for Madrid. Moving to many cities needs: one index per city or metro area (not one national index), a "transit packs" catalog block listing packs with bounding boxes, feed expiry handling (store the feed's last service date in the pack header and show "timetable outdated" in the UI) and cross-pack trips only as a later step (intercity via Renfe).

## 4. Part B: general knowledge and POI data usable offline

| ID | Candidate | What it adds | Licence and GPLv3 + redistribution | Format and size | Offline | Privacy | Effort | Priority | Verif. and sources |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B1 | OSM opening hours (text parsed on device) | "Open now" badge and filter (S3) | ODbL; parser `westnordost/osm-opening-hours` is MIT and pure Kotlin | no new data (tag inside `.mwm`/PMTiles if exposed) | yes | none | 3-5 (parser integration only) | now | S https://central.sonatype.com/artifact/de.westnordost/osm-opening-hours |
| B2 | OSM wheelchair and accessibility tags | Accessibility filter, place card badge | ODbL | tag already in OSM extract; small converter if the core does not expose it | yes | none | 2-4 | now | S https://wiki.openstreetmap.org/wiki/Rollstuhlrouting (a Feb 2026 proposal to rename to `mobility:wheelchair` was noted, so parse both) |
| B3 | OSM `wikimedia_commons` / `wikidata` tags | Link from a place to a Commons file or Wikidata item (link only, no image download offline) | ODbL for the tag; Commons file licences are per file | tags only | links only | opening a link uses the network | 1-2 | later | S https://wiki.openstreetmap.org/wiki/Key:wikimedia_commons |
| B4 | Drinking water, toilets, shelters, emergency assembly points | Outdoor and emergency use (X6) | ODbL | small per region (estimate under 1 MB per region) | yes | none | 3-4 as a points file like chargers | now | U (OSM tags `amenity=drinking_water`, `amenity=toilets`; wiki not opened) |
| B5 | Wikidata labels, descriptions, coordinates, instance-of for notable places | Short descriptions on place cards without the network | **CC0** for structured data | full JSON dump is very large (a 2017 snapshot was 19.3 GB compressed); we would extract only Spanish items with coordinates and a sitelink: estimate tens of MB for Spain | yes | none | 6-10 | later | S https://zenodo.org/records/1211767 (size, 2017) ; S https://wikimedia.bytemark.co.uk/wikimedia.org/dumps/legal.html (CC0 structured data, CC BY-SA 4.0 text) |
| B6 | Wikipedia lead paragraphs for notable places | Encyclopaedic text on place cards | Text is **CC BY-SA 4.0**; CC lists GPLv3 as compatible one way for 4.0 only, but we ship data, not code, so we would just keep the share-alike and attribution in the file | estimate 1-2 KB per article, tens of MB for Spanish notable places | yes | none | 8-12 | later | S https://creativecommons.org/2015/10/08/cc-by-sa-4-0-now-one-way-compatible-with-gplv3/ ; S https://wikimedia.bytemark.co.uk/wikimedia.org/dumps/legal.html |
| B7 | Wikivoyage guides | Guides (S8) | **CC BY-SA 3.0**; no CC route into GPLv3; Wikivoyage had not moved to 4.0 as of a source whose date is unknown | n/a | n/a | n/a | n/a | no (until licence changes) | S https://wiki.creativecommons.org/wiki/ShareAlike_compatibility:_GPLv3 ; S https://de.wikivoyage.org/wiki/Wikivoyage:LB |
| B8 | GeoNames | Placenames for alternative names and population | CC BY 4.0 (attribution) | `allCountries` is hundreds of MB (about 350 MB average download reported by a third party) | yes | none | 4-6 | no: OSM already has names and CoMaps search; low marginal value | S https://geonames.org/export/ , https://wiki.creativecommons.org/wiki/GeoNames |
| B9 | Overture Maps (Places, Addresses, Buildings) | More POIs and addresses | Places: **CDLA Permissive 2.0**; Transportation, Buildings and Base: ODbL; upstream sources may add CC BY 4.0 | global Parquet (tens of GB); clip to Spain | yes | none | 15-25 plus dedupe against OSM | later | S https://overturemaps.org/?p=1042 , https://docs.overturemaps.org/attribution |
| B10 | Postal codes | Search by postal code | CartoCiudad portals carry Correos postal codes; licence of that layer not found | per-province zips (CNIG) | yes | none | 4-6 | later | S https://www.idee.es/resources/documentos/Cartociudad/CartoCiudad_Especificaciones.pdf |
| B11 | Spanish address datasets: CNIG CartoCiudad, Catastro INSPIRE addresses | Housenumber search where OSM is incomplete | CartoCiudad licence **not found**; Catastro has a "Licencia de Acceso y Uso" page, text not read; OpenAddresses lists Spain as CC BY 4.0 per a third-party listing | CartoCiudad: zip per province (shapefile); Catastro: GML per municipality via ATOM, updated twice a year | yes | none | 15-25 (core must index them) | later | S https://datos.gob.es/es/catalogo/e00125901-spaign-cartociudad-addresses ; S https://www.catastro.hacienda.gob.es/webinspire/index_eng.html ; S https://wiki.openstreetmap.org/wiki/OpenAddresses |
| B12 | OpenAddresses | Address aggregator | Per-source licences, some share-alike; pipeline output is not relicensed | per-source | yes | none | 8-12 | no (use the original publisher's data if needed) | S https://wiki.openstreetmap.org/wiki/OpenAddresses |
| B13 | Elevation: Copernicus DEM GLO-30 | Contours, hillshade, elevation profile (M3, N9) | Free licence, attribution to DLR/Airbus "provided under COPERNICUS by the European Union and ESA", liability sentence to pass on, and a "produced using" notice for modified data; not an OSI/CC licence | Cloud Optimized GeoTIFF in an open S3 bucket without an account; a part of the world is not released | yes after baking | none | 8-12 | next | S https://registry.opendata.aws/copernicus-dem/ , https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf |
| B14 | Elevation: IGN MDT05 / MDT25 | Higher resolution (5 m) for Spain | CC BY 4.0, attribution to the Spanish National Cartographic System (SCNE) | MDT05 download sizes **not found**; the full 5 m grid is very large (estimate hundreds of GB), so bake contours only | yes | none | +4-6 over B13 | later | S https://datos.gob.es/es/catalogo/e0dat0002-modelo-digital-del-terreno-con-paso-de-malla-de-5-metros-mdt05-de-espana1 , https://datos.gob.es/en/aplicaciones/centro-de-descargas |
| B15 | Elevation: SRTM | Fallback | NASA/USGS data is treated as public domain with credit requested; **CGIAR void-filled SRTM is non-commercial** and must not be mixed with OSM-derived data | 90 m / 30 m | yes | none | 4-6 | no (B13 is better and cleaner) | S https://wiki.openstreetmap.org/wiki/SRTM |
| B16 | Hiking/cycling route relations (Waymarked-Trails-like) | Long-distance paths, MTB, cycle networks as overlays | OSM = ODbL (Waymarked Trails is only a viewer; its code is GPLv3 and does not govern the data) | `osmium tags-filter` on `r/route=hiking,foot,bicycle,mtb` plus members; estimate a few MB per region | yes | none | 6-10 | next | S https://wiki.openstreetmap.org/wiki/Waymarked_Trails |
| B17 | Weather and alerts: AEMET OpenData | Severe weather warnings (CAP 1.2) and forecasts | Reuse allowed incl. commercial, cite AEMET as author; free API key for automated queries | CAP XML in tar.gz; RSS/Atom per area | no, on demand only | fetching the national set is private; per-municipality forecast reveals interest, so use national alerts only | 5-7 | later | S https://opendata.aemet.es/centrodedescargas/docs/FAQs220424.pdf , https://www.aemet.es/en/rss_info/avisos/esp |
| B18 | Weather: Open-Meteo | Hourly forecast anywhere | Data CC BY 4.0, but **free API is non-commercial only**, key/fee for commercial; sends coordinates | JSON API | no | **sends location**: conflicts with our rule | n/a | no | S https://open-meteo.com/en/terms |
| B19 | Air quality: MITECO / EEA | Station values or index | MITECO reuse terms not found; EEA data: licence not confirmed (a third-party page says ODC-BY-like) | EEA Parquet via scripts; MITECO downloads yearly | partly | none for a national file | 4-6 | later (weak value) | S https://www.miteco.gob.es/en/calidad-y-evaluacion-ambiental/temas/atmosfera-y-calidad-del-aire/evaluacion-y-datos-de-calidad-del-aire/datos.html |
| B20 | Low-emission zones (ZBE) | Draw ZBE, warn before entering, route option | No national open dataset found; MITECO counted 58 ZBE in force at end of 2025 (per a news article); OSM has 46 in Spain (third-party count) | polygons: small (tens of KB) | yes | none | 5-8 | next | S https://www.ecoticias.com/co2/zonas-de-bajas-emisiones-espana-retrasos-2025 , https://mapatlas.eu/es/blog/low-emission-zones-europe-openstreetmap , https://datos.gob.es/es/solicitud-de-datos/zonas-de-bajas-emisiones |
| B21 | Parking (city open data) | Public car parks by city | Valencia parking CC BY 4.0 (district summary, not a point list); Madrid uses its own attribution licence; Barcelona listing has no licence in the catalogue record | CSV/GeoJSON | partly (occupancy is live) | none for static | 3-5 per city | no for static (OSM `amenity=parking` is already in the core); live occupancy later | S https://opendata.vlci.valencia.es/en/dataset/car-parks-by-districts-neighborhoods/resource/6810cb63-d55c-4ffa-9b5d-5dcbbdce171c ; https://wiki.openstreetmap.org/wiki/ES:Importaci%C3%B3n_Ayuntamiento_de_Madrid |
| B22 | Tolls | Toll roads, price estimate | OSM `toll=yes`, `barrier=toll_booth`, `highway=toll_gantry`; prices have no open national dataset found | in the core already for "avoid tolls" | yes | none | 2-3 to show toll sections on the route | next (display only) | S https://wiki.openstreetmap.org/wiki/Key:toll |
| B23 | DGT cameras (images) and variable message panels | Show a camera image or the panel text on a route | DGT NAP: "free of charge", conditions field empty; already used as location anchors in `build-cameras.py` | panels real-time DATEX II v3.6/v3.7 updated every minute; locations hourly | images need network | viewing a camera image reveals interest in a place | 4-6 (panels), 6-8 (images on demand) | later | S https://nap.dgt.es/en/dataset/paneles-dgt-tiempo-real-datex2-v3-7 , https://nap.dgt.es/en/dataset/paneles-dgt-localizaciones-datex2-v3-6 |
| B24 | Protected areas | Park boundaries, rules | **WDPA: no commercial use, no redistribution** (not usable). Natura 2000 (EEA): reuse permitted with acknowledgement per one source; an OSM wiki note reports a CC BY 4.0 authorisation from 03/2026 (unconfirmed). MITECO Banco de Datos de la Naturaleza has "open data" downloads, licence text not read. Alternative: OSM `boundary=protected_area` (ODbL) | polygons: a few MB | yes | none | 4-6 | later (use OSM) | S https://www.protectedplanet.net/c/terms-and-conditions , https://wiki.openstreetmap.org/wiki/Natura_2000 , https://www.miteco.gob.es/es/biodiversidad/servicios/banco-datos-naturaleza/informacion-disponible/rednatura_2000_desc.html |
| B25 | Tide tables | Beach and coast use | No open licence found for Puertos del Estado or the Hydrographic Institute tables | n/a | n/a | n/a | n/a | no (until a licence is confirmed); could compute harmonic tides locally but needs constants (U) | S https://portus.puertos.es/Portus/docs/widgets.pdf (no licence text) |
| B26 | Ski and mountain information | Snow reports | no open source found in this research | n/a | n/a | n/a | n/a | no | not researched beyond a search: **not verified** |

## 5. Part C: navigation quality

| ID | Candidate | What it adds | Licence | Format and size | Offline | Privacy | Effort | Priority | Verif. and sources |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C1 | HGV and dimension limits (`maxheight`, `maxweight`, `maxweightrating:hgv`, `hgv=*`) | Truck profile or a warning for tall or heavy vehicles | ODbL | already inside the OSM extract; the CoMaps core has no truck router, so this would be warnings from a points file | yes | none | 6-10 for warnings; 25+ for a real truck profile | later | S https://wiki.openstreetmap.org/wiki/Restriction ; tag semantics are ambiguous (`maxweight` vs `maxweightrating`) |
| C2 | Level crossings | Warn before crossing | ODbL (`railway=level_crossing`) | small points file | yes | none | 2-3 | next (part of the OSM extras block) | S https://wiki.openstreetmap.org/wiki/Level_crossing |
| C3 | Low-emission zones | See B20 | | | | | | next | |
| C4 | Speed limits and road works | Limits are in the core; road works are in the DGT incident feed already planned | DGT NAP conditions field empty | | | | 0 extra | already covered by `next-plan.md` | `docs/phase2/next-plan.md` |
| C5 | Truck safe parking (SSTP) in the DGT NAP | Truck stop planning | DGT NAP says it handles safe-parking data but no specific dataset was found | n/a | n/a | none | n/a | no until a dataset is found | S https://www.dgt.es/conoce-la-dgt/que-hacemos/ambito-internacional/index.html?page=7 |
| C6 | Toll prices | Cost estimate | no open dataset found | n/a | n/a | n/a | n/a | no | S (search found only OSM tags) |

## 6. Full ranking table (all candidates)

Priority legend as in the gap analysis: **now** / **next** / **later** / **no**.

| Candidate | Priority | Effort | Licence OK for GPLv3 app + redistribution? |
| --- | --- | --- | --- |
| NAP GTFS (several cities) | next | 15-25 | Yes, with "Powered by MITRAMS" and metadata kept; share data under the same licence type, derived index may use another (R) |
| Renfe GTFS and GTFS-RT | next, blocked on licence | 5-8 | Unknown: no licence on the page (R) |
| Basque Moveuskadi feeds | later | 4-6 | Likely CC BY 4.0 (portal level, S) |
| Malaga, TITSA | later | 3-4 each | CC BY / CC BY 4.0 (S), data may be stale |
| Bike-share GBFS | later | 4-6 | Per system (S) |
| OSM extras block (B1, B2, B4, C2, B22) | now | 6-10 | Yes, ODbL |
| OSM hiking/cycling routes | next | 6-10 | Yes, ODbL |
| Elevation (Copernicus, IGN) | next | 8-12 | Yes with attribution and notices (S) |
| ZBE | next | 5-8 | OSM polygons yes; official data none found |
| AEMET alerts | later | 5-7 | Likely yes with attribution (S) |
| Wikidata CC0 | later | 6-10 | Yes |
| Wikipedia leads CC BY-SA 4.0 | later | 8-12 | Yes with share-alike attribution (S); check with owner |
| Overture Places | later | 15-25 | CDLA Permissive 2.0 (S) |
| Spanish addresses (CNIG/Catastro) | later | 15-25 | Not confirmed |
| DGT panels and camera images | later | 4-8 | "free of charge", terms empty (S) |
| Protected areas via OSM | later | 4-6 | Yes (ODbL); WDPA no |
| Wikivoyage, WDPA, Open-Meteo free API, SRTM CGIAR | no | | Not usable as stated |
| GeoNames, OpenAddresses, parking static, tide tables, ski | no (for now) | | See table rows |

## 7. Pipeline design fit

### 7.1 New steps in `weekly-data.yml`

The workflow already has optional, `continue-on-error` steps that skip when the converter script is missing from the app repo, and the catalog step adds a block only if the file exists. Follow the same pattern.

1. **OSM extras step** (reuse the Spain `spain.osm.pbf` already downloaded twice, once for cameras and once for chargers). Refactor to download Geofabrik's Spain extract **once** into a shared step output and run `osmium tags-filter` for: `drinking_water`, `toilets`, `level_crossing`, `toll_booth`/`toll_gantry`, `maxheight`/`maxweight`/`hgv` restrictions, `boundary=protected_area`, `low_emission_zone`, and route relations. Convert each to a small binary file (`extras-es.bin`, `routes-<region>.bin`). Estimated CI cost: a few minutes each after the extract is on disk.
2. **Elevation step**: pull GLO-30 tiles for the bounding box of each region from the open S3 bucket, build contours (for example with GDAL) and a compact elevation grid; publish per region. This is the heaviest new step (disk and CPU); the workflow already frees disk and has a 340-minute timeout, so build it **in a separate job** triggered monthly (terrain does not change weekly).
3. **Transit packs step** (generalise the Madrid step): a config JSON per pack under `app/scripts/transit/` (as `madrid.json` today) listing feed URLs; NAP feeds need an `Authorization`-style header taken from a repository secret (the exact header name was not verified; confirm in the NAP developer documentation). Produce `transit-<pack>.umti` and publish as separate assets so users download only their city.
4. **ZBE step**: from OSM polygons plus an optional curated `zbe-es.json` kept in the data repo and reviewed by hand each quarter.
5. **GBFS static step**: read `systems.csv` rows for ES, fetch each system's `station_information`, write `bikeshare-es.bin`.
6. **Wikidata step (later)**: query only Spanish items with coordinates through the Wikidata SPARQL service or a dump filter and write a compact file; a monthly job.

### 7.2 Catalog blocks

Existing blocks come from `gen-region-catalog.py` options `--cameras-file`, `--chargers-file`, `--transit-file`, each with a `--*-base` URL. Add optional blocks of the same shape, each with `url`, `sha256`, `size`, `generated`, `attribution` and `licence` fields (the last two are new and let the About screen be generated from the catalog; this is a proposal):

- `extras` (national points file, a few MB),
- `routes` (one file per region, listed in the region's assets, optional),
- `terrain` (one file per region, optional),
- `transit.packs[]` (id, bounding box, last service date, size),
- `bikeshare`, `zbe`, later `places` (Wikidata).

Per-region assets keep downloads small: a user in Catalonia downloads Catalonia's PMTiles, MWM, routes, terrain and the Barcelona transit pack only. The two-release retention (`KEEP_RELEASES=2`) multiplies the stored size: elevation grids and routes should be published on a **separate, slower-moving release** (for example `terrain-YYYYMM`) and referenced by URL from each weekly catalog, so weekly releases stay near their present size (4.34 GB for the first release, from `docs/decisions.md`).

### 7.3 Update cadence

| Data | Cadence | Reason |
| --- | --- | --- |
| OSM extras, routes, ZBE, protected areas | weekly | same extract as the map |
| Transit packs | weekly | feeds expire (Zaragoza and Valencia reports show end dates in 2026); the pack header carries the last service date |
| Elevation | monthly or on demand | static data |
| Bike-share static | monthly | station changes are rare |
| Wikidata/Wikipedia | monthly or quarterly | slow-moving |
| Live data (GTFS-RT, GBFS status, AEMET alerts, DGT panels) | **never baked**; on demand through `NetworkPolicy` | |

### 7.4 Privacy design for on-demand fetches

Download the whole national or system-wide file and filter on the device. For GTFS-RT, one file per operator (e.g. Renfe Cercanias trip updates) already covers all nuclei, which is acceptable. Do not call per-stop or per-municipality APIs. Add each host to the "possible connections" list in Settings with its purpose, as the fuel and DGT hosts are.

## 8. Licensing and attribution matrix

"About text" is what the app's About/attribution screen must show. GPLv3 applies to the program; these data files are separate works shipped alongside it, so their licences need to allow redistribution with the stated conditions, not to be GPL-compatible themselves (this reading is **U**; the owner may want a second opinion, see section 9).

| Source | Licence | Conditions | About text (proposal) | Verif. |
| --- | --- | --- | --- | --- |
| OpenStreetMap and everything derived (extras, routes, ZBE, protected areas) | ODbL 1.0 | Attribution; derivative databases under ODbL | "Map data (c) OpenStreetMap contributors, ODbL" (already shown) | R (CLAUDE.md) |
| NAP transit feeds | MITRAMS open data licence | "Powered by MITRAMS" with a link to https://www.transportes.gob.es/ ; keep metadata; do not alter meaning; do not keep outdated info displayed; no implied endorsement; stricter source licence prevails | "Transit data: Powered by MITRAMS (Ministerio de Transportes)" + show the feed date | R https://nap.transportes.gob.es/licencia-datos |
| CRTM (existing) | CRTM open licence | already handled in `docs/phase2/transit.md` | already in About | repo |
| Renfe | not stated | needs written confirmation | "Renfe" (tentative) | R https://data.renfe.com/dataset |
| Basque Open Data | CC BY 4.0 (portal level) | attribution | "Open Data Euskadi" | S |
| Malaga EMT, TITSA, Valencia datasets | CC BY 4.0 / CC BY | attribution to the city / operator | per source | S |
| Madrid city open data (BiciMAD, parking) | own licence: attribution with update date | cite source and last update date | "Ayuntamiento de Madrid, datos.madrid.es" | S https://wiki.openstreetmap.org/wiki/ES:Importaci%C3%B3n_Ayuntamiento_de_Madrid |
| Bilbao Bizi, Open Data BCN Bicing | CC BY 4.0 | attribution | per system | S |
| Copernicus DEM GLO-30 | Copernicus free licence | credit "DLR e.V. 2010-2014 and (c) Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA. All rights reserved" (wording must be copied from the licence PDF; it was summarised, not read), liability sentence, "produced using" notice for modified data | exact text from the licence | S https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf |
| IGN MDT / CNIG | CC BY 4.0 | credit IGN/CNIG (SCNE) | "(c) Instituto Geografico Nacional de Espana, CC BY 4.0" | S |
| AEMET | AEMET legal notice | cite AEMET as author | "Fuente: AEMET" | S https://opendata.aemet.es/centrodedescargas/docs/FAQs220424.pdf |
| Wikidata | CC0 | none | optional thanks | S |
| Wikipedia text | CC BY-SA 4.0 | attribute, link, share-alike for adaptations | per place link to the article and licence | S |
| Overture Places | CDLA Permissive 2.0 | per Overture's attribution page | "Overture Maps Foundation" | S https://docs.overturemaps.org/attribution |
| GeoNames | CC BY 4.0 | attribution | "GeoNames" | S |
| DGT NAP | "free of charge", no conditions stated | the existing camera feature already cites DGT; confirm wording | "DGT" | S |
| WDPA, Open-Meteo free API, Wikivoyage, CGIAR SRTM | restrictive or incompatible | do not use | | S |

Compatibility to confirm: baking CC BY data into an ODbL-derived file is common practice, but this was **not verified** in this research. Keep the CC BY files as separate assets with their own attribution (as the plan above does) instead of merging them into ODbL files.

## 9. Risks and what needs the owner

### Risks

- **Feed expiry**: several reported service periods have ended or end in 2026 (Zaragoza reported end date 2026-06-21, Valencia FGV range into mid-2026, Malaga page dated 2023). The existing note says the Madrid feeds last about 30 days. Mitigation: store the last service date in each pack and show it; fail the CI step when a feed has no future service day.
- **Licence on Renfe**: no licence is on the dataset page; do not publish Renfe-derived files until Renfe replies.
- **NAP "same type of licence" clause** for redistributed raw data: publish only derived indexes (the licence says derived works that add value may be offered under different licences), not the raw zips.
- **DGT dataset versions**: search summaries disagree on deprecation dates (one says some incident and camera datasets were marked obsolete on 2026-01-12 and panel datasets on 2026-09-30; another showed v3.6 as "new"). The workflow already uses `datex2_v37` URLs; add a CI check that each URL returns a non-empty file and alert (the steps are `continue-on-error`, so a silent skip is possible).
- **Planner scale**: moving from Madrid to several cities may break the "index per city" design if intercity transfers are wanted.
- **Elevation CI cost**: disk and time; run in a separate job and release.
- **Tag ambiguity**: `maxweight` and `maxweightrating` differ, wheelchair tag renaming is in flux, so converters must be tolerant.
- **Wrong ZBE data** can cause fines or false confidence; label it "informational, check local signs" and keep a "last reviewed" date.
- **Scope creep on POIs** (Overture, GeoNames): large downloads, dedupe cost, little visible gain over OSM.
- **Unverified items**: see the "S" and "U" marks; none of the "S" licences were read at the primary source.

### What needs the owner

1. Register an account at the NAP (https://nap.transportes.gob.es/) and store the API token as a repository secret in `UltimateMaps-data`. (Registration requirement is S; the process was not tested.)
2. Ask Renfe in writing about the licence of its GTFS and GTFS-RT datasets (contact route not verified; the dataset page links to a legal notice that was not read).
3. Decide whether Wikipedia text (CC BY-SA 4.0) may ship in the data files, or only Wikidata (CC0).
4. Approve any change to the `UltimateMaps-data` workflow (it is under `.github/workflows/`, which `CLAUDE.md` lists as owner-controlled for the app repo; confirm the same rule applies to the data repo).
5. Confirm the preferred first cities for transit (suggestion: Barcelona via ATM, Valencia via FGV, Sevilla via Tussam) and whether Basque feeds are wanted.
6. Request a second opinion on mixing CC BY data with ODbL-derived files and on the data-versus-GPLv3 reading in section 8.
7. Decide whether the live features (GTFS-RT, GBFS status, AEMET) should appear at all, since each adds a network host to the "possible connections" list.

## 10. Suggested sequence

1. OSM extras block (B1, B2, B4, C2, B22) and the shared Spain extract refactor. About 6-10 days, no licence risk.
2. Transit pack generalisation + NAP step + Barcelona and Valencia packs, in parallel with the NAP account request. About 15-25 days.
3. Elevation job (Copernicus first), then the profile in the app. About 8-12 days.
4. Route relations overlay and ZBE polygons. About 11-18 days.
5. Renfe feeds after the licence answer; bike share static; AEMET alerts; Wikidata.

Each step ends with its own entry in `docs/decisions.md` and `LICENSES.md`, as the earlier data features did.
