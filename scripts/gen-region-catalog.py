#!/usr/bin/env python3
"""Genera el catálogo de regiones (JSON schema 1, ver docs/phase1/regions.md) uniendo:

  * la jerarquía y los .mwm de CoMaps (`third_party/comaps/data/countries.txt`), y
  * los extractos PMTiles propios (un fichero `<id>.pmtiles` por región).

Campos reales de `countries.txt` (verificados contra el fichero de la versión 261004):
  raíz:  {"id": "Countries", "v": 261004, "map_series": "2026.06.28", "g": [...]}
  nodo:  {"id": "Spain_Community of Madrid", "s": <bytes del .mwm>, "sha1_base64": "...",
          "old": [...], "affiliations": [...], "country_name_synonyms": {...}}
  grupo: {"id": "Spain", "g": [hijos...]}        (los grupos no llevan "s" ni "sha1_base64")
`v` es la versión de datos (YYMMDD) y vale para todo el árbol. El SHA-1 de CoMaps NO se usa: el SHA-256 se
calcula de los ficheros reales (el catálogo propio es la raíz de confianza del hash).

Una hoja solo es descargable si hay AMBOS ficheros (.mwm y .pmtiles) con su SHA-256; el resto de nodos
se emiten igualmente (jerarquía completa) pero sin `assets`, y la app los muestra como «no disponible».

Los ids de CoMaps llevan espacios y no valen como id de región (`[A-Za-z0-9_.-]`): el id propio es el
original en minúsculas ASCII con los huecos como `-` (`spain_community-of-madrid`); el original va en
`comapsId` (campo extra que el parser ignora) para que el motor pida el .mwm correcto.

Ejemplos (sin red):
  scripts/gen-region-catalog.py --mwm-dir ~/mirror/mwm --pmtiles-dir ~/mirror/pmtiles \\
      --mwm-base https://maps.example.org/mwm/ --pmtiles-base https://maps.example.org/pmtiles/ -o catalog.json
  scripts/gen-region-catalog.py -o hierarchy.json          # solo jerarquía, nada descargable

`--base-dir` añade el bloque `base` con `World.mwm` y `WorldCoasts.mwm` (no son una región, pero el núcleo los exige
junto a cada región; la app los descarga una vez por versión). Sin él, el catálogo no lleva `base`.

Con red, solo bajo petición explícita y con tope de tamaño: --fetch-mwm <comapsId> descarga ESE .mwm
(por defecto ≤ 20 MB) a --mwm-dir para calcular su SHA-256.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request

SHA256_CHUNK = 1 << 20
DEFAULT_MWM_BASE = "https://mapgen-fi-1.comaps.app/maps/{series}/{v}/"  # estructura documentada, no verificada con red


def slug(comaps_id):
    s = unicodedata.normalize("NFKD", comaps_id).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9_.]+", "-", s.lower()).strip("-")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(SHA256_CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def display_name(comaps_id, parent_id):
    rest = comaps_id[len(parent_id) + 1:] if parent_id and comaps_id.startswith(parent_id + "_") else comaps_id
    return rest.replace("_", " - ")


def flatten(root):
    """Pre-order: [(node, parent_comaps_id or None)], sin el nodo raíz."""
    out = []

    def walk(node, parent):
        out.append((node, parent))
        for child in node.get("g", []):
            walk(child, node["id"])

    for child in root.get("g", []):
        walk(child, None)
    return out


def fetch_mwm(base_url, comaps_id, expected_size, dest_dir, max_bytes):
    if expected_size > max_bytes:
        raise SystemExit(f"{comaps_id}: {expected_size} bytes superan el tope de descarga ({max_bytes})")
    url = base_url + urllib.parse.quote(comaps_id) + ".mwm"
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, comaps_id + ".mwm")
    with urllib.request.urlopen(url, timeout=60) as r, open(dest, "wb") as f:  # noqa: S310 (https, a petición)
        got = 0
        while True:
            b = r.read(SHA256_CHUNK)
            if not b:
                break
            got += len(b)
            if got > max_bytes:
                raise SystemExit(f"{comaps_id}: la respuesta supera el tope de descarga")
            f.write(b)
    return dest


BASE_FILES = (("world", "World.mwm"), ("worldCoasts", "WorldCoasts.mwm"))


def build_base(version, base_dir, base_url, log=lambda m: None):
    """Bloque `base` (World.mwm y WorldCoasts.mwm, que el núcleo exige junto a cada región) o None si falta alguno."""
    if not base_dir:
        return None
    if not base_url:
        raise SystemExit("--base-dir necesita --base-url (o --mwm-base)")
    base_url = base_url if base_url.endswith("/") else base_url + "/"
    out = {"version": version}
    for key, name in BASE_FILES:
        path = os.path.join(base_dir, name)
        if not os.path.isfile(path):
            log(f"AVISO falta {path}; el catálogo no llevará `base`")
            return None
        out[key] = {"url": base_url + name, "size": os.path.getsize(path), "sha256": sha256_of(path), "file": name}
    return out


def build(countries, mwm_dir=None, pmtiles_dir=None, mwm_base=None, pmtiles_base=None, catalog_version=None,
          fetch=(), max_download_bytes=20 << 20, log=lambda m: None, mwm_url_by_slug=False,
          base_dir=None, base_url=None):
    version = str(countries["v"])
    series = countries.get("map_series", "")
    mwm_base = (mwm_base or DEFAULT_MWM_BASE.format(series=series, v=version))
    mwm_base = mwm_base if mwm_base.endswith("/") else mwm_base + "/"
    if pmtiles_base and not pmtiles_base.endswith("/"):
        pmtiles_base += "/"
    nodes = flatten(countries)
    slugs = {}
    seen = set()
    for node, _ in nodes:
        s = slug(node["id"])
        if node["id"] not in slugs and s in slugs.values():
            raise SystemExit(f"colisión de ids tras normalizar: {node['id']} -> {s}")
        slugs[node["id"]] = s
    regions = []
    for node, parent in nodes:
        cid = node["id"]
        if cid in seen:  # countries.txt repite algunos nodos bajo dos padres (p. ej. Campo de Hielo Sur)
            log(f"AVISO {cid}: repetido bajo otro padre; se conserva la primera aparición")
            continue
        seen.add(cid)
        rid = slugs[cid]
        region = {
            "id": rid,
            "comapsId": cid,
            "name": display_name(cid, parent),
            "parent": slugs[parent] if parent else None,
            "version": version,
        }
        is_leaf = "g" not in node
        if is_leaf and "s" in node:
            mwm = os.path.join(mwm_dir, cid + ".mwm") if mwm_dir else None
            if cid in fetch:
                if not mwm_dir:
                    raise SystemExit("--fetch-mwm necesita --mwm-dir")
                mwm = fetch_mwm(mwm_base, cid, node["s"], mwm_dir, max_download_bytes)
            pm = os.path.join(pmtiles_dir, rid + ".pmtiles") if pmtiles_dir else None
            if mwm and pm and os.path.isfile(mwm) and os.path.isfile(pm):
                msize = os.path.getsize(mwm)
                if msize != node["s"]:
                    log(f"AVISO {cid}: el .mwm mide {msize} y countries.txt dice {node['s']}; se omite")
                else:
                    if not pmtiles_base:
                        raise SystemExit("--pmtiles-dir necesita --pmtiles-base")
                    region["assets"] = {
                        "render": {"url": pmtiles_base + rid + ".pmtiles", "size": os.path.getsize(pm),
                                   "sha256": sha256_of(pm), "file": rid + ".pmtiles"},
                        "search": {"url": mwm_base + (rid if mwm_url_by_slug else urllib.parse.quote(cid)) + ".mwm", "size": msize,
                                   "sha256": sha256_of(mwm), "file": rid + ".mwm"},
                    }
        regions.append(region)
    cat = {
        "schema": 1,
        "catalogVersion": catalog_version or datetime.date.today().isoformat(),
    }
    base = build_base(version, base_dir, base_url or mwm_base, log)
    if base:
        cat["base"] = base
    cat["regions"] = regions
    return cat


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--countries", default=os.path.join(os.path.dirname(__file__), "..", "third_party", "comaps",
                                                         "data", "countries.txt"))
    ap.add_argument("--mwm-dir", help="directorio con <comapsId>.mwm (se calcula su SHA-256)")
    ap.add_argument("--pmtiles-dir", help="directorio con <id propio>.pmtiles")
    ap.add_argument("--mwm-base", help="URL base de los .mwm (por defecto la estructura de CoMaps)")
    ap.add_argument("--pmtiles-base", help="URL base de los .pmtiles (obligatoria con --pmtiles-dir)")
    ap.add_argument("--catalog-version", help="por defecto, la fecha de hoy (YYYY-MM-DD)")
    ap.add_argument("--fetch-mwm", action="append", default=[], metavar="COMAPS_ID",
                    help="descarga ese .mwm a --mwm-dir (red; solo si se pide)")
    ap.add_argument("--max-download-mb", type=int, default=20)
    ap.add_argument("--base-dir", help="directorio con World.mwm y WorldCoasts.mwm (bloque `base` del catálogo)")
    ap.add_argument("--base-url", help="URL base de World*.mwm (por defecto, --mwm-base)")
    ap.add_argument("--mwm-url-by-slug", action="store_true",
                    help="la URL del .mwm usa el id propio (`<slug>.mwm`), p. ej. en GitHub Releases, que renombra los espacios")
    ap.add_argument("-o", "--output", default="-")
    a = ap.parse_args(argv)
    with open(a.countries, encoding="utf-8") as f:
        countries = json.load(f)
    cat = build(countries, a.mwm_dir, a.pmtiles_dir, a.mwm_base, a.pmtiles_base, a.catalog_version,
                set(a.fetch_mwm), a.max_download_mb << 20, log=lambda m: print(m, file=sys.stderr),
                mwm_url_by_slug=a.mwm_url_by_slug, base_dir=a.base_dir, base_url=a.base_url)
    text = json.dumps(cat, indent=1, ensure_ascii=False) + "\n"
    if a.output == "-":
        sys.stdout.write(text)
    else:
        with open(a.output, "w", encoding="utf-8") as f:
            f.write(text)
    if "base" not in cat and any("assets" in r for r in cat["regions"]):
        print("AVISO: el catálogo tiene regiones descargables pero NO el bloque `base` (World.mwm y WorldCoasts.mwm): "
              "la app las descargará, pero la búsqueda dirá «no hay mapas». Pasa --base-dir.", file=sys.stderr)
    n = len(cat["regions"])
    d = sum(1 for r in cat["regions"] if "assets" in r)
    print(f"{n} regiones, {d} descargables", file=sys.stderr)


if __name__ == "__main__":
    main()
