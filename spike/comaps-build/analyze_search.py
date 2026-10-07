#!/usr/bin/env python3
"""Extrae del logcat del nucleo el tiempo hasta el primer lote de resultados y la duracion total de cada busqueda
(una por pulsacion de tecla). Uso: analyze_search.py <logcat> [n_descartar_frias=1]"""
import re, sys, statistics, math

emit = re.compile(r"Emitting a new batch of results: (\d+) , (\d+) ms since")
end = re.compile(r"Search ended in (\d+) ms")
cur, rows = [], []
for l in open(sys.argv[1], errors="replace"):
    m = emit.search(l)
    if m:
        cur.append((int(m.group(1)), int(m.group(2))))
        continue
    m = end.search(l)
    if m:
        tot = int(m.group(1))
        if tot == 0 and not cur:
            continue
        rows.append((cur[0] if cur else (None, None), tot))
        cur = []
skip = int(sys.argv[2]) if len(sys.argv) > 2 else 1
rows = rows[skip:]
first = [r[0][1] for r in rows if r[0][1] is not None and r[0][0] > 0]
total = [r[1] for r in rows]
def p(v, q):
    v = sorted(v); return v[min(len(v) - 1, int(math.ceil(q * len(v))) - 1)]
print(f"searches (after discarding {skip} cold): {len(rows)}; with results: {len(first)}")
for name, v in (("primer_lote_ms", first), ("fin_busqueda_ms", total)):
    if v:
        print(f"{name}: min={min(v)} p50={statistics.median(v)} p95={p(v, .95)} max={max(v)}")
