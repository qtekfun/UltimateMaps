# Low-emission zones (ZBE): data, file format and honesty limits

Plan row 6 of `docs/phase2/data-sources.md` (B20): draw the zones, warn when a car route enters one, prompt once before entering during a navigation. Status: built, JVM-tested, not seen on a phone, the weekly workflow step has not run.

## What the data is

There is **no national open dataset** (plan, section 4 and row B20). The only open source with polygons is OpenStreetMap (ODbL), so the feature is "zones as OpenStreetMap has them".

### Tagging (OSM wiki, `Tag:boundary=low_emission_zone`, read 2026-10-08)

- `boundary=low_emission_zone` on a closed way or a `type=boundary` / multipolygon relation (areas). The wiki marks nodes as "should not be used".
- Companion tags that exist but are inconsistent: `name`, `ref`, `start_date`/`end_date`, `access`, `access:conditional` (for example `no @ ((fuel=diesel AND emissions<euro_4) OR ...)`, the Getafe row of the wiki table), `description`, `website`, `addr:city`/`is_in:city`. The Spanish table of the wiki lists 23 zones with their relation ids (Madrid, Barcelona, Terrassa, Badalona, Sabadell, Tarragona, Reus, Getafe, ...).
- The plain key `low_emission_zone=*` is **not** used by the build: it has about 800 uses worldwide (taginfo), more than the 550 `boundary=low_emission_zone` objects, so it sits on other kinds of objects too, and a whole-municipality boundary carrying it would paint a whole city as a zone.

### Filter (decision and reason)

`osmium tags-filter spain.osm.pbf wr/boundary=low_emission_zone` then `osmium export -f geojsonseq`. Only that tag, ways and relations (no nodes). It is the narrowest filter that matches the documented tagging and cannot produce a municipality-wide false zone. `build-zbe.py` then drops: lifecycle prefixes (`proposed:`, `disused:`, ...), `access=yes`, a `start_date` in the future, an `end_date` in the past, rings with fewer than three distinct points. Rings are simplified (Douglas-Peucker, 4 m) and capped at 1500 points.

### How many there are (measured 2026-10-08)

- taginfo: 550 objects with `boundary=low_emission_zone` worldwide (8 nodes, 417 ways, 125 relations).
- One Overpass count over the Spain bounding box (35.8..43.9 N, 9.5 W..4.5 E, which also takes in a bit of Portugal, Andorra and France): **47 ways + 32 relations = 79 tagged objects**. Several are probably the same zone mapped twice or non-zone uses, so the number of usable zones is probably in the **20-50** range; the first weekly run will print the real figure (`zones` in the summary).
- For comparison: MITECO counted 58 ZBE in force at the end of 2025 (a news article quoted by the plan, not read at source); the plan's third-party count of OSM zones is 46. Many cities are missing in OSM, or have a polygon that is out of date. **The coverage is not complete and cannot be made complete from open data**; that is why every screen says so.

## File `zbe-es.bin`

Big endian, written by `scripts/build-zbe.py`, read by `:core-zbe` (`ZbeFile`). Typical size: a few tens of KB.

```
u32 magic "UMZB" (0x554D5A42), u16 version (1), i64 generated (epoch seconds), u8 source_flags (1 = OSM), u32 n_zones
zone:    utf name, utf city, utf restriction, u8 n_polygons
polygon: u8 n_rings (first = outer ring, others = holes)
ring:    u16 n_points (the closing point is not repeated), n_points x (i32 lat_e6, i32 lon_e6)
u32 crc32 of everything before it
```

`utf` is a u16 length plus UTF-8 bytes (Java `writeUTF` for BMP text). The reader rejects the whole file on a bad checksum, magic, version, count or position; the app then keeps what it had. `restriction` is free text from the OSM tags (a `description`/`note`, else the raw `*:conditional` tag): it is shown as "Tagged in OpenStreetMap: ..." and never interpreted.

The catalog block is `zbe` (`url`, `size`, `sha256`, `file`), made by `gen-region-catalog.py --zbe-file ... --zbe-base ...`. Without it the app works as before.

## What the app does, and does not say

- Off by default. Settings > Alerts > Low-emission zones. Turning it on downloads the file once through `NetworkPolicy` (same host and purpose as the camera file; no query, no position).
- Map: translucent amber fill with a dashed outline, under the route line (the dashes tell it apart without colour), own toggle.
- Car route summary: "This route enters a low-emission zone: check the access rules of the city", zone names, the OSM restriction text when there is one, and "Based on OpenStreetMap, may be incomplete or out of date; the city's official rules apply."
- Navigation (car trips only): one prompt per zone, "Low-emission zone ahead", as a banner plus chime, voice or nothing (same `AlertDeliveryPolicy` as the camera alerts: the navigation Mute and an imminent maneuver win).
- **The app never says that a vehicle is allowed or banned.** There is no label (DGT sticker), fuel or emission-class logic, because the OSM conditions are not reliable enough to interpret and a wrong "you may enter" can cost a fine. A test checks that no user-facing text contains words such as allowed or banned.

## Route test (design values, not measured)

`ZbeIndex.crossings` cuts each route segment at the polygon edges and tests the middle of each piece, so touching a corner is not a crossing. Zone borders usually follow a street, so two guards avoid warning for a road that runs along the border: a stretch must reach 15 m inside, and stretches less than 30 m apart along the route are one. A chord that clips a corner for a few metres does not count. Polygon simplification (4 m) and route-geometry noise are of the same order as those thresholds, so a route that really enters a zone by less than about 15 m is not reported (a false negative in a rare case); the destination inside a zone by less than 15 m is also not reported.

## Not verified

- On a phone: the look of the layer, the banner, the chime.
- That the weekly step produces a sensible file from the real Spain extract (the tag spellings of real objects, how `osmium export` treats closed ways tagged only `boundary=low_emission_zone`, which the script accepts either as polygons or as closed lines).
- The real number of usable zones and how many cities are missing.
- Whether zones mapped in OSM match the legal perimeters.
