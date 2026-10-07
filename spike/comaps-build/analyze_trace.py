#!/usr/bin/env python3
"""Analyzes a .pftrace: the CoMaps GL engine does not show up in frametimeline (0 rows per layer), so the SurfaceFlinger
slice `setBuffer ... SurfaceView[app.comaps...](BLAST)... hasBuffer=true` is used as a proxy for 'frame presented'
(one slice per queued buffer). The longest continuous burst is taken (gaps > 100 ms split it).
Output: intervals between consecutive buffers (ms): p50/p90/p95/p99/max, mean fps, % of intervals > 16.7 and > 8.4 ms.
Usage: analyze_trace.py <trace.pftrace> <output.json>"""
import json, sys, math
from perfetto.trace_processor import TraceProcessor

tp = TraceProcessor(file_path=sys.argv[1])
rows = list(tp.query("select ts from slice where name like 'setBuffer %SurfaceView[app.comaps%hasBuffer=true' order by ts"))
ts = [r.ts for r in rows]


def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(math.ceil(p * len(v))) - 1)] if v else None


# bursts
bursts, cur = [], [ts[0]] if ts else []
for a, b in zip(ts, ts[1:]):
    if (b - a) / 1e6 > 100:
        bursts.append(cur)
        cur = []
    cur.append(b)
if cur:
    bursts.append(cur)
best = max(bursts, key=len) if bursts else []
iv = [(b - a) / 1e6 for a, b in zip(best, best[1:])]
res = {"buffers_total": len(ts), "bursts": len(bursts), "burst_frames": len(best),
       "burst_span_s": (best[-1] - best[0]) / 1e9 if len(best) > 1 else 0,
       "interval_ms": {k: pct(iv, p) for k, p in [("p50", .5), ("p90", .9), ("p95", .95), ("p99", .99), ("max", 1.0)]},
       "fps_mean": (len(best) - 1) / ((best[-1] - best[0]) / 1e9) if len(best) > 1 else 0,
       "pct_gt_16.7ms": 100.0 * sum(1 for x in iv if x > 16.7) / len(iv) if iv else None,
       "pct_gt_12.5ms_dropped": 100.0 * sum(1 for x in iv if x > 12.5) / len(iv) if iv else None,
       "pct_gt_8.4ms": 100.0 * sum(1 for x in iv if x > 8.4) / len(iv) if iv else None}
json.dump(res, open(sys.argv[2], "w"), indent=1)
print(json.dumps(res, indent=1))
