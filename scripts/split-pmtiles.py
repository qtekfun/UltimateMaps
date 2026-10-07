#!/usr/bin/env python3
"""Corta un PMTiles de origen en un PMTiles por región de CoMaps, usando los polígonos de
`third_party/comaps/data/borders/<id>.poly` (formato Osmosis). Salida: `<slug>.pmtiles` por región,
con el mismo slug que `gen-region-catalog.py`, para que el catálogo encaje 1:1 con los .mwm.

Uso:
  scripts/split-pmtiles.py --pmtiles BIN --source ORIGEN --out DIR [--prefix Spain_] [--only ID ...]
ORIGEN puede ser un fichero local o la URL de un build de Protomaps. Es idempotente: salta lo ya hecho.
Un PMTiles recortado incluye los teselas enteros que tocan el polígono, así que las regiones vecinas
se solapan en el borde (eso lo debe tener en cuenta el dibujado).
"""
import argparse
import importlib.util
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BORDERS = os.path.join(HERE, "..", "third_party", "comaps", "data", "borders")


def _slug():
    spec = importlib.util.spec_from_file_location("gen_region_catalog", os.path.join(HERE, "gen-region-catalog.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.slug


def read_poly(path):
    """Devuelve un MultiPolygon GeoJSON: cada anillo exterior es un polígono; los agujeros (`!nombre`) se añaden al último."""
    polys = []
    with open(path, encoding="utf-8") as f:
        lines = [l.strip() for l in f if l.strip()]
    i = 1  # la primera línea es el nombre del fichero
    while i < len(lines) and lines[i] != "END":
        hole = lines[i].startswith("!")
        i += 1
        ring = []
        while lines[i] != "END":
            lon, lat = lines[i].split()[:2]
            ring.append([float(lon), float(lat)])
            i += 1
        i += 1
        if ring and ring[0] != ring[-1]:
            ring.append(ring[0])
        if hole and polys:
            polys[-1].append(ring)
        elif not hole:
            polys.append([ring])
    return {"type": "Feature", "properties": {}, "geometry": {"type": "MultiPolygon", "coordinates": polys}}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pmtiles", required=True, help="binario go-pmtiles")
    ap.add_argument("--source", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--prefix", default="Spain_")
    ap.add_argument("--only", nargs="*", help="ids de CoMaps (sin .poly) a procesar")
    a = ap.parse_args(argv)
    slug = _slug()
    os.makedirs(a.out, exist_ok=True)
    ids = a.only or sorted(f[:-5] for f in os.listdir(BORDERS) if f.startswith(a.prefix) and f.endswith(".poly"))
    for cid in ids:
        dest = os.path.join(a.out, slug(cid) + ".pmtiles")
        if os.path.exists(dest):
            print("ya existe", dest)
            continue
        gj = os.path.join(a.out, "." + slug(cid) + ".geojson")
        with open(gj, "w") as f:
            json.dump(read_poly(os.path.join(BORDERS, cid + ".poly")), f)
        tmp = dest + ".part"
        r = subprocess.run([a.pmtiles, "extract", a.source, tmp, "--region=" + gj], capture_output=True, text=True)
        os.remove(gj)
        if r.returncode != 0:
            print("FALLO", cid, r.stderr[-300:], file=sys.stderr)
            continue
        os.replace(tmp, dest)
        print("ok", cid, os.path.getsize(dest))


if __name__ == "__main__":
    main()
