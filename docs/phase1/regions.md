# Gestor de regiones (`:core-regions`) - RF-02, RF-12

Opción C: cada región son **dos descargas** (PMTiles para el render, `.mwm` de CoMaps para búsqueda y routing). Una región solo se activa cuando las dos están completas y verificadas.

## Esquema del catálogo (schema 1)

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

- Jerarquía por `parent` (sin ciclos); solo las hojas llevan `assets` (`render` y `search`, ambos obligatorios para ser descargables). `version` es la fecha de datos (`YYMMDD`).
- `url` absoluta o relativa a la URL del catálogo. `file` es el nombre en disco (solo `[A-Za-z0-9_.-]`, sin rutas). `sha256` en hex.
- `comapsId` (opcional por región): el id de CoMaps, con espacios (`Spain_La Rioja`). El núcleo solo encuentra el mapa como `<comapsId>.mwm`; sin él la región no se puede enlazar con el núcleo (los catálogos antiguos siguen cargando; al refrescar el catálogo se rellena en lo ya instalado).
- `base` (opcional, retrocompatible): `World.mwm` y `WorldCoasts.mwm`, que no son una región pero el núcleo exige junto a cada región. `version` es la fecha de datos; `world` y `worldCoasts` son assets como los demás (`url`, `size`, `sha256`, `file`). El generador los añade con `--base-dir <dir con los dos ficheros>` (y `--base-url`, por defecto `--mwm-base`); si falta alguno, el catálogo sale sin `base`.
- Código: `RegionCatalog.parse/toJson`, `Region`, `RegionAsset`, `AssetKind`, `BaseMaps`.

## Descarga, activación, actualización y borrado

- `ResumableDownloader`: `Range: bytes=N-` sobre un `.part`; 206 reanuda, 200 reinicia desde cero; recorta/descarta si hay más bytes de los esperados; verifica tamaño y SHA-256. Hash incorrecto: se borra el parcial y se lanza `HashMismatchException`. Cancelación o corte de red: el parcial se conserva.
- `RegionManager.install`: cada asset verificado se renombra con `ATOMIC_MOVE` a `<id>/<versión>/`; después se reemplaza atómicamente `<id>/installed.json`. Hasta ese último paso la versión anterior sigue usable (una actualización fallida, incluso con el primer fichero ya bueno, no cambia nada). `delete` quita primero el manifiesto; `cleanup` barre huérfanos.
- Base (World): `RegionManager.install(region, base = catalog.base)` la descarga, verifica y mueve a `<root>/.base/<versión>/` (marcador `base.json` al final) **una vez por versión**, antes de la región; el progreso y el espacio necesario la incluyen. `cleanup` conserva solo la base más nueva (sirve también a regiones de versión anterior); `removeBases()` las borra cuando no queda ninguna región en ningún almacenamiento.
- `updatesAvailable(catalog)`: regiones instaladas con versión distinta a la del catálogo.
- `StorageSelector`/`StorageLocation`: interno o tarjeta; comprueba espacio libre y nunca cae en silencio a otro volumen (devuelve `null` para que la UI pregunte). La capa Android debe aportar la lista de `StorageLocation` (`getExternalFilesDirs`).
- Red: cada conexión y cada salto de redirección pasa por `NetworkPolicy.authorize(host, MAP_DOWNLOAD)`; el registro solo guarda host, motivo y resultado (nunca URL ni posición). Modo sin red, host fuera de la lista blanca o desactivado: cero conexiones (probado). http plano rechazado salvo `allowInsecure` (solo tests). El host del espejo debe estar en la lista blanca (`AllowedEndpoint(host, MAP_DOWNLOAD)`).

## Enlace con el núcleo de CoMaps (`CoreMapsLinker`)

El núcleo lee `filesDir/maps-core/<versión>/<comapsId>.mwm` más `World.mwm` y `WorldCoasts.mwm` en ese mismo directorio. `CoreMapsLinker` (en `:core-regions`, JVM puro) reconstruye ese directorio de forma idempotente a partir de lo instalado en todos los almacenamientos, sin copiar bytes:

- **Enlaces simbólicos**, y duros solo si el sistema de ficheros rechaza los simbólicos. Motivo: el simbólico cruza volúmenes (la tarjeta SD es otro sistema de ficheros, donde un duro es imposible) y el núcleo lee con `stat`/`fopen`, que siguen enlaces (comprobado en el código fuente de CoMaps, **no en un dispositivo**). Lo que no se pueda enlazar queda en `LinkReport.failed` y la pantalla «Mapas» avisa.
- Cada versión de datos con regiones instaladas tiene su directorio; `World*.mwm` es el de la misma versión o, si no hay, el más nuevo. Se quitan los enlaces huérfanos (simbólicos rotos o sobrantes, y los duros anotados en `.links.json`) y los directorios vacíos. Un fichero real que no es nuestro (p. ej. subido a mano en desarrollo) nunca se toca.
- Se ejecuta (`CoreLinks.sync`, solo ficheros, fuera del hilo principal) al arrancar la app, al terminar una instalación o actualización y al borrar.
- **Núcleo y versiones:** `refreshMaps` (`Core::RefreshMaps`) escanea todas las carpetas de versión y registra cada mapa; `MwmSet::Register` sustituye la versión vieja de un país por la nueva, así que una **actualización** no exige reinicio. No hay desregistro: un mapa **borrado** sigue registrado (y contestando) hasta que muere el proceso, porque el estado nativo es un singleton inmortal. `CoreLinks.restartNeeded` se activa si se desenlaza un mapa con el núcleo ya cargado (`CoMapsSearchBackend` lo marca) y la pantalla «Mapas» pide reiniciar la app. Desregistrar en caliente exigiría tocar C++ (`MwmSet::Deregister`) y no se ha hecho.

## Catálogo por defecto y red

La app trae `https://github.com/qtekfun/UltimateMaps-data/releases/latest/download/catalog.json` (editable en «Mapas»; «Usar el servidor por defecto» lo restaura; vacío = sin servidor, se recuerda). Fijarlo solo **lista** hosts en la lista blanca (`MAP_DOWNLOAD`, visibles en las conexiones posibles): no conecta nunca al arrancar; la primera conexión llega al abrir «Mapas» o pulsar descargar, y el modo sin red lo impide todo.

GitHub responde 302 en cada salto, y `ResumableDownloader`/`CatalogFetcher` autorizan **cada salto** con `NetworkPolicy.authorize(host, MAP_DOWNLOAD)` (https siempre; hasta 5 saltos). Cadena real comprobada (HEAD, sin descargar): `github.com/.../releases/latest/download/catalog.json` -> 302 `github.com/.../releases/download/<tag>/catalog.json` -> 302 `release-assets.githubusercontent.com/...`. Los hosts permitidos con la URL por defecto son exactamente `github.com`, `release-assets.githubusercontent.com` y `objects.githubusercontent.com` (este último, el destino anterior de GitHub, aparece en redirecciones reales documentadas en la comunidad de GitHub); sin comodines. Un catálogo en otro servidor no añade hosts de GitHub. Tests: `RedirectAuthorizationTest` (302 a otro host autorizado y no autorizado: el segundo salto no llega al servidor).

## Mapear los catálogos de origen

**CoMaps (`countries.txt`, JSON).** Árbol de nodos con `id`, versión de datos, hijos, tamaño del mwm y SHA-1 base64 por hoja (nombres exactos de campo: verificar contra `countries.txt` y `libs/storage/storage.cpp`; ver `docs/spike/comaps-code.md`). Mapeo: `id` -> `id` (los hijos conservan el nombre `Spain_Madrid`), versión -> `version`, padre = nodo contenedor, `search.url` = `<servidor>/maps/<v>/<id>.mwm`, `search.size` = tamaño del nodo, `file` = `<id>.mwm`. **El SHA-1 base64 de CoMaps no sirve como `sha256`**: o bien (a) un script de importación descarga/lee cada `.mwm` del espejo y calcula SHA-256 al generar nuestro catálogo (recomendado; el catálogo propio es la raíz de confianza para el hash), o bien (b) se amplía el esquema con `sha1` opcional. Las regiones de CoMaps con varios `.mwm` por región deben agruparse en una hoja (una sola `search`) o modelarse como varias hojas.

**PMTiles.** No hay catálogo oficial por región: se generan extractos (`pmtiles extract` de un build mundial de Protomaps o los nuestros) alineados con los límites de las regiones de CoMaps. Cada extracto -> `render` con `url`, `size` (tamaño del fichero), `sha256` (`sha256sum`) y `file` `<id>.pmtiles`. Misma `version` que el `.mwm` hermano (mismo corte de OSM) para que la actualización sea coherente.

El generador existe: `scripts/gen-region-catalog.py` (ver `docs/decisions.md`, 2026-10-07: campos reales de `countries.txt`: `id`, `v`, `s`, `sha1_base64`, `g`; ids propios en slug + `comapsId`). Une ambos por id, calcula SHA-256 de los ficheros y solo marca descargable una hoja con los dos.

## Qué falta para un espejo propio

1. Alojamiento con HTTPS y soporte `Range` (cualquier servidor estático) y su host añadido a la lista blanca de `NetworkPolicy`.
2. Generador de catálogo (arriba) y de los PMTiles por región.
3. **Para el núcleo de CoMaps (no implementado, solo documentado):** `countries.txt` se verifica con Ed25519 contra `COUNTRIES_TXT_SIGNATURE_HEX` de `private.h`; un servidor propio exige **nuestro par de claves** y recompilar con nuestra clave pública y nuestros `METASERVER_URL`/`DEFAULT_URLS_JSON` (`private.h`). Alternativa sin recompilar: reflejar ficheros oficiales con su firma. La clave privada no se guarda en el repo. Además CoMaps comprueba SHA-1, no SHA-256: unificar en SHA-256 es tocar C++ (2-3 días según el spike). En la opción C la app verifica SHA-256 de nuestro catálogo antes de entregar los `.mwm` al motor, así que el SHA-1 interno queda como segunda comprobación o se desactiva al recompilar.
4. Decidir si el catálogo propio también se firma (p. ej. Ed25519 de nuestra clave) para no depender solo de TLS.
5. ~~Integrar en `:app`~~ Hecho (M1): `app/.../regions` (pantalla «Mapas», `RegionsController`, `RegionDownloadService`, `RegionStorage`). Falta probarlo en el móvil.
