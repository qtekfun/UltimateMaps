# Maps for a trip to China (and the recipe for any other country)

Status (2026-10-27): the data of the 28 Chinese regions of CoMaps is built and published; the app needed two small changes (release 1.0.1). **Nothing here was tried on a phone**: the owner did not allow the test device to be used for it, so how the labels look, how the search answers in Chinese and how the voice behaves are checked by reading the code, the tile data and the unit tests only. The checklist at the end lists what to try before leaving.

## What exists and what to install

The CoMaps region tree has 28 regions for mainland China (provinces and municipalities) and two for Taiwan (not built here). Cities that are not a region live inside one:

| City | Region to install |
|---|---|
| Beijing, Tianjin | Hebei |
| Shanghai | Shanghai |
| Guangzhou, Shenzhen, Hong Kong, Macau, Hainan (Haikou, Sanya) | Guangdong |
| Chengdu | Sichuan |
| Chongqing | Chongqing |
| Xi'an | Shaanxi |
| Hangzhou | Zhejiang |
| Nanjing, Suzhou | Jiangsu |
| Wuhan | Hubei |
| Kunming, Lijiang | Yunnan |
| Guilin | Guangxi |
| Xiamen | Fujian |
| Qingdao | Shandong |
| Lhasa | Tibet Autonomous Region |
| Urumqi | Xinjiang |
| Harbin | Heilongjiang |

The *Maps* screen search knows these city names (release 1.0.1), so typing "Pekín" or "Hong Kong" finds the right province. Sizes (data version 261004, Protomaps build 20261025):

| Region | Search and routing (.mwm) | Map (.pmtiles) | Total |
|---|---|---|---|
| Guangdong | 310 MB | 366 MB | 676 MB |
| Sichuan | 217 MB | 344 MB | 561 MB |
| Hebei | 159 MB | 334 MB | 493 MB |
| Xinjiang | 157 MB | 278 MB | 435 MB |
| Yunnan | 180 MB | 254 MB | 434 MB |
| Shaanxi | 148 MB | 281 MB | 428 MB |
| Inner Mongolia | 96 MB | 294 MB | 390 MB |
| Tibet Autonomous Region | 137 MB | 244 MB | 382 MB |
| Zhejiang | 120 MB | 231 MB | 351 MB |
| Gansu | 122 MB | 222 MB | 344 MB |
| Shandong | 99 MB | 227 MB | 326 MB |
| Jiangsu | 104 MB | 195 MB | 300 MB |
| Heilongjiang | 66 MB | 223 MB | 290 MB |
| Guangxi | 107 MB | 174 MB | 280 MB |
| Hubei | 89 MB | 170 MB | 258 MB |
| Henan | 75 MB | 177 MB | 252 MB |
| Hunan | 86 MB | 164 MB | 250 MB |
| Jiangxi | 74 MB | 167 MB | 242 MB |
| Anhui | 59 MB | 158 MB | 217 MB |
| Qinghai | 82 MB | 129 MB | 211 MB |
| Fujian | 66 MB | 128 MB | 194 MB |
| Shanxi | 61 MB | 130 MB | 191 MB |
| Jilin | 47 MB | 136 MB | 184 MB |
| Liaoning | 47 MB | 124 MB | 171 MB |
| Guizhou | 55 MB | 112 MB | 167 MB |
| Chongqing | 51 MB | 94 MB | 145 MB |
| Shanghai | 35 MB | 45 MB | 80 MB |
| Ningxia Hui | 16 MB | 34 MB | 50 MB |
| **All 28** | **2.87 GB** | **5.43 GB** | **8.30 GB** |

Install only the provinces of the trip: the search engine looks through every installed region, so a long list slows the searches.

## How the data is built

The same recipe as Spain, run by hand and published as its own release of the data repository (tag `china-data-<mwm version>-<protomaps date>`), because the weekly workflow builds only Spain and cannot hold the extra 8 GB on a hosted runner:

1. `scripts/fetch-comaps-mwm.py --group "People's Republic of China" --out mwm/` downloads the 28 `.mwm` files from the CoMaps map server and checks size and SHA-1 against `countries.txt`.
2. `scripts/split-pmtiles.py --pmtiles pmtiles --source https://build.protomaps.com/YYYYMMDD.pmtiles --out out --prefix China_` cuts one PMTiles per region with the CoMaps polygon (about an hour for China; two runs with `--only` in parallel halve it).
3. The `.mwm` files are linked as `<slug>.mwm` next to the `.pmtiles` (the slug is the catalog id, see `gen-region-catalog.py`).
4. `scripts/merge-region-pack.py --base-catalog <catalog of the latest weekly release> --pack-dir out --url-base <release URL> -o catalog.json` adds the download entries to the existing catalog (its hierarchy already lists every CoMaps region). The catalog was parsed with the app's own parser (53 downloadable regions, 28 of them Chinese).
5. A release with the files, `catalog.json` and `SHA256SUMS`.

All 56 files were checked: every `.mwm` has the search, routing and cross-map sections, every PMTiles passes `pmtiles verify`, the largest file is 366 MB (GitHub's limit is 2 GiB).

**The weekly release replaces the default catalog every Monday and it has no China.** The files stay (the tag does not start with `data-`, so the weekly pruning never touches it), but from the next Monday the app's default catalog no longer lists them: set the data server to `https://github.com/qtekfun/ultimate-maps-data/releases/download/china-data-261004-20261025/catalog.json` in the Maps screen, or install everything before the trip. Making the weekly workflow include a list of extra countries is a change to a workflow, which is the owner's to approve; it also needs a split of the job, because a hosted runner has about 14 GB of disk.

## What works in China

- **Map labels.** The tiles carry `name:en` for most streets and places of the big cities (Shanghai 95 % of the named roads, Shenzhen 93 %, Beijing 73 %, Guangzhou 59 %, Hangzhou 56 %, Chongqing 30 % in a sample tile of the city centre) and mark the features whose name is in Chinese characters (`script = Han`). The style shows the English name first and the Chinese name under it; where there is no English name only the Chinese shows. There is no pinyin in the tiles, so those labels cannot be read without knowing the characters (useful to show a taxi driver). MapLibre draws Chinese characters with the phone's own font by default (the packaged glyphs are Latin only); **not verified on a device**.
- **Search.** The CoMaps engine indexes the Chinese names and the English ones where OpenStreetMap has them; typing Chinese characters works through the phone keyboard. Names with no English version are found only by their characters. The *Place language* setting (English or local) chooses which name the results show.
- **Routes.** The maps include the car, bicycle and pedestrian routing data and the altitudes. Walking is the realistic use: a foreigner needs a Chinese licence to drive.
- **Voice.** The voice no longer tries to read Chinese, Japanese or Korean characters (release 1.0.1): "Yan'an Road 延安路" is said "Yan'an Road", a name that is only characters is not said.
- **Position.** The GPS gives WGS-84 coordinates, which is what OpenStreetMap uses, so the dot lands where it should. Chinese map apps use a shifted system (GCJ-02) and would be 300 to 500 m off; this one is not affected. In places where OpenStreetMap contributors traced from shifted imagery the data itself can still be off by that much.
- **Network.** Everything runs offline. GitHub is slow or blocked from China, so every download must be done before leaving. Turn on *offline mode* in China so that no feature waits for a connection that will not come.

What is Spain only and does nothing in China: the public-transport planner (the metro lines are drawn on the map, but there is no journey planning), fuel prices, speed cameras and incidents, weather, low-emission zones, bike-share and charger data. Live traffic needs a TomTom key and a connection.

## Before leaving: checklist

1. Install version 1.0.1 or later.
2. On Wi-Fi, in *Maps*, download the provinces of the trip (and the Spanish ones you may need back home); check that each shows as installed.
3. In *Settings*, choose the place language you want for the search results (English is the most readable) and turn on offline mode.
4. Try in Spain, with the airplane mode on: search a Chinese place by its English name and by its characters, open its card, ask for a walking route and start it (the simulator is enough), listen to the voice.
5. Save the places you already know (hotel, stations) in a list with the Chinese name in the title, to show them to a driver.

## Doing it for the whole world

Measured from `countries.txt` and the Protomaps build of 2026-10-25: 1,153 regions, 99.4 GB of `.mwm` (United States 15.8 GB, Canada 6.6, Russia 6.4, France 5.5, Germany 4.6, China 2.9) and a planet PMTiles of 139 GB, so about 250 to 300 GB of files once cut by region (neighbouring regions overlap at the border). It is feasible with this recipe, but:

- A hosted runner (about 14 GB of disk, six hours) cannot build it, and the weekly pipeline keeps two releases, so it would need about 600 GB of public storage. One-off builds by hand like this one are fine; a permanent worldwide pipeline needs storage that is not GitHub Releases (a cost to decide) and its own job design.
- Nobody installs the world: a phone holds a few GB. What matters is that any region can be added on demand with the five steps above (about two hours for a country of China's size). The catalog already lists all 1,153 regions.
- The packs are not updated by the weekly job; a pack built by hand does not follow the data version until it is rebuilt.

Recommended: build packs when a trip is planned, and decide later whether to automate a list of extra countries in the weekly workflow.
