#!/usr/bin/env python3 -I
"""p50/p95/p99 of frame time (FrameCompleted - IntendedVsync, last 120 frames of each dump; plus the gfxinfo summary (whole interval)) from gfxinfo framestats traces.
Usage: stats.py trace1 [trace2 ...]  (groups them and also shows each one)"""
import sys, statistics

def frames(path):
    out, on = [], False
    for ln in open(path):
        ln = ln.strip()
        if ln.startswith('---PROFILEDATA---'):
            if out: break
            on = not on; continue
        if on and ln and ln[0].isdigit():
            c = ln.split(',')
            if len(c) > 19 and c[0] == '0':
                a, b = int(c[2]), int(c[19])
                if a > 0 and b > a:
                    out.append((b - a) / 1e6)
    return out

def pct(v, p):
    s = sorted(v); k = (len(s) - 1) * p / 100
    f = int(k); c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)

def row(name, v):
    if not v: return f"{name}: no frames"
    return (f"{name}: n={len(v)} p50={pct(v,50):.1f} p95={pct(v,95):.1f} p99={pct(v,99):.1f} "
            f"max={max(v):.1f} ms; >16.6ms={sum(x>16.6 for x in v)} >8.3ms={sum(x>8.33 for x in v)}")

allv = []
for p in sys.argv[1:]:
    v = frames(p); allv += v; print(row(p.split('/')[-1], v))
    summ = {}
    for ln in open(p):
        for k in ('Total frames rendered','Janky frames:','50th percentile','90th percentile','95th percentile','99th percentile'):
            if ln.startswith(k) and k not in summ: summ[k] = ln.split(':')[1].strip()
    print('   resumen gfxinfo:', ' | '.join(f'{k.rstrip(":")}={v}' for k, v in summ.items()))
print(row('TOTAL', allv))
