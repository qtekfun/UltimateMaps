# Bike-share stations (data source #7)

Status: built 2026-10-08, branch `feat/bikeshare`, verified by JVM, Robolectric and Python tests only. Not seen on a phone.

This implements data source #7 of `docs/phase2/data-sources.md` (section 3.3): a small static file of docking stations, a map layer, a card, and an optional, separate, opt-in live availability.

## 1. Which systems, and why (licences checked at the source on 2026-10-08)

Rule: a system is included only if the station data licence was read at the owner's own page and allows redistribution with attribution. Secondary listings (MobilityData, Mobility Database, forum posts) were used only to find leads.

| System | Licence evidence | Public GBFS without a key | Included |
| --- | --- | --- | --- |
| Bicing (Barcelona) | Open Data BCN dataset "Informació estacions Bicing": **CC BY 4.0** (page read: https://opendata-ajuntament.barcelona.cat/data/en/dataset/informacio-estacions-bicing) | yes: `barcelona.publicbikesystem.net/customer/gbfs/v3.0/` (read 2026-10-08, no token, 543 stations) | **yes** |
| BiciMAD (Madrid) | datos.madrid.es dataset "Estaciones de Bicimad" (owner Empresa Municipal de Transportes, S.A.): **CC BY 4.0** (page read: https://datos.madrid.es/dataset/208327-0-transporte-bicicletas-bicimad) | yes: `madrid.publicbikesystem.net/customer/gbfs/v3.0/` (678 stations) | **yes** |
| Bilbao Bizi | Only the Mobility Database listing says CC-BY-4.0 (a secondary source). The city catalog (bilbao.eus/opendata) has a general CC BY 4.0 portal licence, but no Bilbaobizi station dataset was found there and its terms page defers to licence pages that were not opened. | feed is public, with no licence field | no |
| Sevici (Sevilla), Valenbisi (Valencia) | JCDecaux Cyclocity feeds (`api.cyclocity.fr`). No licence page of the operator or the city could be read (Valencia's open-data URLs returned 404; a 2019 mailing-list message saying CC BY is not a source). | not tested | no |
| Bizi (Zaragoza) | The city dataset "Ocupación de estaciones Bizi" (zaragoza.es catalog 6260) only says "Condiciones de uso"; the legal notice was not read. | public feed on the same platform as Bicing | no |
| Dbizi (Donostia), Valladolid, A Coruña, Rivas | Same platform as Bicing (`*.publicbikesystem.net`, public); no licence statement in the feed (`system_information` has no `license_*` fields) and no owner page checked. | yes | no |
| nextbike systems (AMBici, bibo, BiciPalma, bizkaibizi, Lovesharing, moxsi, León, BiciLOG, Roquebike, TorrentBici, TUeBICI...) | Operator feeds under nextbike's terms, not an open-data licence. | yes | no |
| MugiBIKE (Vitoria), Ganxeta (Reus), Donkey Republic | Operator feeds; nothing checked. | - | no |
| Bird, Dott, Cooltra, Getaround | Fleets that move constantly, commercial operators (the plan says skip). | - | no |

Adding a system later: confirm the licence at its owner's page, add a row to `SYSTEMS` in `scripts/build-bikeshare.py` (id, name, attribution text, `station_information` URL) and, if live data is wanted, its `station_status` URL to `BikeShareLiveFeeds` in `:core-bikeshare`; then add the name to the Settings/PRIVACY texts.

### Caveats the owner should know (not resolved)

- **Bicing:** the Open Data BCN resource itself is behind a token; the app and the build script use the public GBFS feed of the operator platform, which carries the same station list. The GBFS distribution has no licence field of its own. The licence we rely on is that of the dataset page (CC BY 4.0).
- **BiciMAD:** the Madrid dataset "Bicimad. GBFS" is published under the EMT's general open-data terms (https://mobilitylabs.emtmadrid.es/sip/terms-of-use, not read), while the station list dataset is CC BY 4.0. We rely on the latter.
- **Live availability** calls the same operator-platform hosts; no terms for that use were found. It is opt-in, one request per 30 s per system, and sends nothing but a plain GET.

## 2. Data file

`scripts/build-bikeshare.py` reads one GBFS `station_information` JSON per system (the workflow downloads them; the script downloads nothing) and writes `bikeshare-es.bin` (about 53 KB for 1 221 stations). Layout (big endian):

```
u32 magic "UMBK", u16 version=1, i64 generated (epoch s), u16 n_systems
system: utf id, utf name, utf attribution, u32 n_stations,
        n_stations x (utf station_id, utf name, i32 lat_e6, i32 lon_e6, u16 capacity)
u32 crc32 of everything before it
```

A system with fewer than 20 usable stations (or a missing/invalid file) is left out with a warning; when nothing is left the script exits 1 and writes no file, so an empty file is never published. The reader (`BikeShareFile`) rejects anything damaged as a whole.

Catalog: optional `bikeshare` block (`--bikeshare-file`, `--bikeshare-base` in `gen-region-catalog.py`), same shape as `chargers` and `zbe`.

## 3. App behaviour

- Settings > Navigation > *Bike-share stations*: switch (off by default; downloads the file from the catalog's data server through `NetworkPolicy`, as the chargers do), then a second switch *Show live bike availability* (off by default), data date and count, *Update now*, attribution.
- Map: a small bicycle badge, nothing below zoom 11, at most one station per grid cell below zoom 14, at most 250/600 pins.
- Card: name, system, docks, live line only when available, the system's credit, the data date, *Route to the station* and (while a route is active or navigating) *Add stop*.
- Live availability (`BikeAvailabilityRepository`): only while a card is open and both switches are on; `ConnectionPurpose.BIKE_AVAILABILITY`; the host is added to the policy when first used and removed when the live switch goes off (so it is listed under Settings > Privacy only then); fixed https URLs compiled into the app (the data file cannot choose a host); whole-system `station_status` GET with no query, a generic `User-Agent`, 3 MB cap; at most one request per system per 30 s (failures included); counts older than 10 min are not shown; every failure is silent. The time shown is the feed's `last_reported` (or `last_updated`), in the device time zone.
- Backup: `mapas_bikeshare` keys `enabled` and `live_availability`, both `NEEDS_CONSENT` on restore.

## 4. Not verified

On a phone (badge look in both themes, taps, card layout, frame time); the weekly workflow step (not run); the real behaviour of the two feeds over time (rate limits, outages, `ttl`); whether the operator platform allows this live use.
