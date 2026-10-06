# Gestor de regiones (`:core-regions`) - RF-02, RF-12

Opción C: cada región son **dos descargas** (PMTiles para el render, `.mwm` de CoMaps para búsqueda y routing). Una región solo se activa cuando las dos están completas y verificadas.

## Esquema del catálogo (schema 1)

```json
{
  "schema": 1,
  "catalogVersion": "2026-10-06",
  "regions": [
    {"id": "europe", "name": "Europe", "parent": null, "version": "261005"},
    {"id": "spain-madrid", "name": "Madrid", "parent": "spain", "version": "261005",
     "assets": {
       "render": {"url": "pm/madrid.pmtiles", "size": 123, "sha256": "<64 hex>", "file": "madrid.pmtiles"},
       "search": {"url": "https://cdn/261005/Spain_Madrid.mwm", "size": 45, "sha256": "<64 hex>", "file": "Spain_Madrid.mwm"}}}
  ]
}
```

- Jerarquía por `parent` (sin ciclos); solo las hojas llevan `assets` (`render` y `search`, ambos obligatorios para ser descargables). `version` es la fecha de datos (`YYMMDD`).
- `url` absoluta o relativa a la URL del catálogo. `file` es el nombre en disco (solo `[A-Za-z0-9_.-]`, sin rutas). `sha256` en hex.
- Código: `RegionCatalog.parse/toJson`, `Region`, `RegionAsset`, `AssetKind`.

## Descarga, activación, actualización y borrado

- `ResumableDownloader`: `Range: bytes=N-` sobre un `.part`; 206 reanuda, 200 reinicia desde cero; recorta/descarta si hay más bytes de los esperados; verifica tamaño y SHA-256. Hash incorrecto: se borra el parcial y se lanza `HashMismatchException`. Cancelación o corte de red: el parcial se conserva.
- `RegionManager.install`: cada asset verificado se renombra con `ATOMIC_MOVE` a `<id>/<versión>/`; después se reemplaza atómicamente `<id>/installed.json`. Hasta ese último paso la versión anterior sigue usable (una actualización fallida, incluso con el primer fichero ya bueno, no cambia nada). `delete` quita primero el manifiesto; `cleanup` barre huérfanos.
- `updatesAvailable(catalog)`: regiones instaladas con versión distinta a la del catálogo.
- `StorageSelector`/`StorageLocation`: interno o tarjeta; comprueba espacio libre y nunca cae en silencio a otro volumen (devuelve `null` para que la UI pregunte). La capa Android debe aportar la lista de `StorageLocation` (`getExternalFilesDirs`).
- Red: cada conexión y cada salto de redirección pasa por `NetworkPolicy.authorize(host, MAP_DOWNLOAD)`; el registro solo guarda host, motivo y resultado (nunca URL ni posición). Modo sin red, host fuera de la lista blanca o desactivado: cero conexiones (probado). http plano rechazado salvo `allowInsecure` (solo tests). El host del espejo debe estar en la lista blanca (`AllowedEndpoint(host, MAP_DOWNLOAD)`).

## Mapear los catálogos de origen

**CoMaps (`countries.txt`, JSON).** Árbol de nodos con `id`, versión de datos, hijos, tamaño del mwm y SHA-1 base64 por hoja (nombres exactos de campo: verificar contra `countries.txt` y `libs/storage/storage.cpp`; ver `docs/spike/comaps-code.md`). Mapeo: `id` -> `id` (los hijos conservan el nombre `Spain_Madrid`), versión -> `version`, padre = nodo contenedor, `search.url` = `<servidor>/maps/<v>/<id>.mwm`, `search.size` = tamaño del nodo, `file` = `<id>.mwm`. **El SHA-1 base64 de CoMaps no sirve como `sha256`**: o bien (a) un script de importación descarga/lee cada `.mwm` del espejo y calcula SHA-256 al generar nuestro catálogo (recomendado; el catálogo propio es la raíz de confianza para el hash), o bien (b) se amplía el esquema con `sha1` opcional. Las regiones de CoMaps con varios `.mwm` por región deben agruparse en una hoja (una sola `search`) o modelarse como varias hojas.

**PMTiles.** No hay catálogo oficial por región: se generan extractos (`pmtiles extract` de un build mundial de Protomaps o los nuestros) alineados con los límites de las regiones de CoMaps. Cada extracto -> `render` con `url`, `size` (tamaño del fichero), `sha256` (`sha256sum`) y `file` `<id>.pmtiles`. Misma `version` que el `.mwm` hermano (mismo corte de OSM) para que la actualización sea coherente.

El generador del catálogo (script fuera de la app) debe unir ambos por `id` y emitir este JSON; no existe aún (pendiente).

## Qué falta para un espejo propio

1. Alojamiento con HTTPS y soporte `Range` (cualquier servidor estático) y su host añadido a la lista blanca de `NetworkPolicy`.
2. Generador de catálogo (arriba) y de los PMTiles por región.
3. **Para el núcleo de CoMaps (no implementado, solo documentado):** `countries.txt` se verifica con Ed25519 contra `COUNTRIES_TXT_SIGNATURE_HEX` de `private.h`; un servidor propio exige **nuestro par de claves** y recompilar con nuestra clave pública y nuestros `METASERVER_URL`/`DEFAULT_URLS_JSON` (`private.h`). Alternativa sin recompilar: reflejar ficheros oficiales con su firma. La clave privada no se guarda en el repo. Además CoMaps comprueba SHA-1, no SHA-256: unificar en SHA-256 es tocar C++ (2-3 días según el spike). En la opción C la app verifica SHA-256 de nuestro catálogo antes de entregar los `.mwm` al motor, así que el SHA-1 interno queda como segunda comprobación o se desactiva al recompilar.
4. Decidir si el catálogo propio también se firma (p. ej. Ed25519 de nuestra clave) para no depender solo de TLS.
5. Integrar en `:app`: servicio en primer plano para descargas largas, UI jerárquica y proveedor de `StorageLocation`.
