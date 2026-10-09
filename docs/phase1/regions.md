# Region manager (`:core-regions`) - RF-02, RF-12

Option C: each region is **two downloads** (PMTiles for rendering, CoMaps `.mwm` for search and routing). A region is only activated when both are complete and verified.

## Catalog schema (schema 1)

```json
{
  "schema": 1,
  "catalogVersion": "2026-10-06",
  "base": {"version": "261005",
    "world": {"url": "https://cdn/261005/World.mwm", "size": 12, "sha256": "<64 hex>", "file": "World.mwm"},
    "worldCoasts": {"url": "https://cdn/261005/WorldCoasts.mwm", "size": 34, "sha256": "<64 hex>", "file": "WorldCoasts.mwm"}},
  "regions": [
    {"id": "europe", "name": "Europe", "parent": null, "version": "261005"},
    {"id": "spain-madrid", "comapsId": "Spain_Community of Madrid", "name": "Madrid", "parent": "spain", "version": "261005",
     "assets": {
       "render": {"url": "pm/madrid.pmtiles", "size": 123, "sha256": "<64 hex>", "file": "madrid.pmtiles"},
       "search": {"url": "https://cdn/261005/Spain_Madrid.mwm", "size": 45, "sha256": "<64 hex>", "file": "Spain_Madrid.mwm"}}}
  ]
}
```

- Hierarchy through `parent` (no cycles); only leaves carry `assets` (`render` and `search`, both required to be downloadable). `version` is the data date (`YYMMDD`).
- `url` is absolute or relative to the catalog URL. `file` is the on-disk name (only `[A-Za-z0-9_.-]`, no paths). `sha256` in hex.
- `comapsId` (optional per region): the CoMaps id, with spaces (`Spain_La Rioja`). The core only finds the map as `<comapsId>.mwm`; without it the region cannot be linked to the core (old catalogs still load; refreshing the catalog fills it in for what is already installed).
- `base` (optional, backward compatible): `World.mwm` and `WorldCoasts.mwm`, which are not a region but which the core requires next to each region. `version` is the data date; `world` and `worldCoasts` are assets like the others (`url`, `size`, `sha256`, `file`). The generator adds them with `--base-dir <dir with the two files>` (and `--base-url`, defaulting to `--mwm-base`); if either is missing, the catalog is produced without `base`.
- Code: `RegionCatalog.parse/toJson`, `Region`, `RegionAsset`, `AssetKind`, `BaseMaps`.

## Download, activation, update and deletion

- `ResumableDownloader`: `Range: bytes=N-` over a `.part`; 206 resumes, 200 restarts from scratch; it trims/discards if there are more bytes than expected; it verifies size and SHA-256. Wrong hash: the partial file is deleted and `HashMismatchException` is thrown. Cancellation or network cut: the partial file is kept.
- `RegionManager.install`: each verified asset is renamed with `ATOMIC_MOVE` to `<id>/<version>/`; then `<id>/installed.json` is replaced atomically. Until that last step the previous version stays usable (a failed update, even with the first file already good, changes nothing). `delete` removes the manifest first; `cleanup` sweeps orphans.
- Base (World): `RegionManager.install(region, base = catalog.base)` downloads it, verifies it and moves it to `<root>/.base/<version>/` (`base.json` marker at the end) **once per version**, before the region; the progress and the space needed include it. `cleanup` keeps only the newest base (it also serves regions of an earlier version); `removeBases()` deletes them when no region is left in any storage.
- `updatesAvailable(catalog)`: installed regions whose version differs from the catalog's.
- `StorageSelector`/`StorageLocation`: internal or card; it checks free space and never silently falls back to another volume (it returns `null` so the UI asks). The Android layer must provide the list of `StorageLocation` (`getExternalFilesDirs`).
- Network: every connection and every redirect hop goes through `NetworkPolicy.authorize(host, MAP_DOWNLOAD)`; the log only stores host, reason and result (never URL or position). Offline mode, host outside the allowlist or disabled: zero connections (tested). Plain http rejected unless `allowInsecure` (tests only). The mirror host must be on the allowlist (`AllowedEndpoint(host, MAP_DOWNLOAD)`).

## Link with the CoMaps core (`CoreMapsLinker`)

The core reads `filesDir/maps-core/<version>/<comapsId>.mwm` plus `World.mwm` and `WorldCoasts.mwm` in that same directory. `CoreMapsLinker` (in `:core-regions`, pure JVM) rebuilds that directory idempotently from what is installed in all storages, without copying bytes:

- **Symbolic links**, and hard links only if the file system rejects symbolic ones. Reason: a symbolic link crosses volumes (the SD card is another file system, where a hard link is impossible) and the core reads with `stat`/`fopen`, which follow links (checked in the CoMaps source code, **not on a device**). Whatever cannot be linked ends up in `LinkReport.failed` and the "Mapas" screen warns about it.
- Each data version with installed regions has its directory; `World*.mwm` is the one of the same version or, if there is none, the newest. Orphan links are removed (broken or leftover symbolic links, and the hard links recorded in `.links.json`) and so are empty directories. A real file that is not ours (e.g. uploaded by hand in development) is never touched.
- It runs (`CoreLinks.sync`, files only, off the main thread) at app startup, when an install or update finishes, and on deletion.
- **Core and versions:** `refreshMaps` (`Core::RefreshMaps`) scans all version folders and registers each map; `MwmSet::Register` replaces a country's old version with the new one, so an **update** does not require a restart. There is no deregistration: a **deleted** map stays registered (and answering) until the process dies, because the native state is an immortal singleton. `CoreLinks.restartNeeded` is set if a map is unlinked with the core already loaded (`CoMapsSearchBackend` flags it) and the "Mapas" screen asks to restart the app. Deregistering at runtime would require touching C++ (`MwmSet::Deregister`) and has not been done.

## Default catalog and network

The app ships `https://github.com/qtekfun/ultimate-maps-data/releases/latest/download/catalog.json` (editable in "Mapas"; "Usar el servidor por defecto" (use the default server) restores it; empty = no server, remembered). Setting it only **lists** hosts on the allowlist (`MAP_DOWNLOAD`, visible in the possible connections): it never connects at startup; the first connection happens when opening "Mapas" or tapping download, and offline mode blocks everything.

GitHub answers 302 on each hop, and `ResumableDownloader`/`CatalogFetcher` authorize **each hop** with `NetworkPolicy.authorize(host, MAP_DOWNLOAD)` (always https; up to 5 hops). Real chain checked (HEAD, without downloading): `github.com/.../releases/latest/download/catalog.json` -> 302 `github.com/.../releases/download/<tag>/catalog.json` -> 302 `release-assets.githubusercontent.com/...`. The hosts allowed with the default URL are exactly `github.com`, `release-assets.githubusercontent.com` and `objects.githubusercontent.com` (the latter, GitHub's previous destination, appears in real redirects documented in the GitHub community); no wildcards. A catalog on another server does not add GitHub hosts. Tests: `RedirectAuthorizationTest` (302 to another host, authorized and not authorized: the second hop never reaches the server).

## Mapping the source catalogs

**CoMaps (`countries.txt`, JSON).** Tree of nodes with `id`, data version, children, mwm size and base64 SHA-1 per leaf (exact field names: verify against `countries.txt` and `libs/storage/storage.cpp`; see `docs/spike/comaps-code.md`). Mapping: `id` -> `id` (children keep the name `Spain_Madrid`), version -> `version`, parent = container node, `search.url` = `<server>/maps/<v>/<id>.mwm`, `search.size` = node size, `file` = `<id>.mwm`. **CoMaps' base64 SHA-1 is not usable as `sha256`**: either (a) an import script downloads/reads each `.mwm` from the mirror and computes SHA-256 when generating our catalog (recommended; our own catalog is the root of trust for the hash), or (b) the schema is extended with an optional `sha1`. CoMaps regions with several `.mwm` files per region must be grouped into one leaf (a single `search`) or modeled as several leaves.

**PMTiles.** There is no official per-region catalog: extracts are generated (`pmtiles extract` from a Protomaps world build or our own) aligned with the CoMaps region boundaries. Each extract -> `render` with `url`, `size` (file size), `sha256` (`sha256sum`) and `file` `<id>.pmtiles`. Same `version` as the sibling `.mwm` (same OSM cut) so the update is coherent.

The generator exists: `scripts/gen-region-catalog.py` (see `docs/decisions.md`, 2026-10-07: real `countries.txt` fields: `id`, `v`, `s`, `sha1_base64`, `g`; own ids as slug + `comapsId`). It joins both by id, computes the SHA-256 of the files and only marks a leaf as downloadable if it has both.

## What is missing for our own mirror

1. Hosting with HTTPS and `Range` support (any static server) and its host added to the `NetworkPolicy` allowlist.
2. Catalog generator (above) and the per-region PMTiles.
3. **For the CoMaps core (not implemented, only documented):** `countries.txt` is verified with Ed25519 against `COUNTRIES_TXT_SIGNATURE_HEX` from `private.h`; our own server requires **our key pair** and rebuilding with our public key and our `METASERVER_URL`/`DEFAULT_URLS_JSON` (`private.h`). Alternative without rebuilding: mirror official files with their signature. The private key is not kept in the repo. In addition CoMaps checks SHA-1, not SHA-256: unifying on SHA-256 means touching C++ (2-3 days according to the spike). In option C the app verifies the SHA-256 of our catalog before handing the `.mwm` files to the engine, so the internal SHA-1 remains as a second check or is disabled when rebuilding.
4. Decide whether our own catalog is also signed (e.g. Ed25519 with our key) so as not to depend on TLS alone.
5. ~~Integrate into `:app`~~ Done (M1): `app/.../regions` ("Mapas" screen, `RegionsController`, `RegionDownloadService`, `RegionStorage`). Still to be tested on the phone.
