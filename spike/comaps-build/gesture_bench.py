#!/usr/bin/env python3
"""Inyecta gestos reproducibles (pan 1 dedo, pinch-zoom 2 dedos, giro 2 dedos) escribiendo eventos
multitouch (protocolo B) en /dev/input/event3 (goodix_ts0) a ~120 Hz, y mide los intervalos de presentacion
de la capa SurfaceView del mapa con `dumpsys SurfaceFlinger --latency`. Todo bajo el lock compartido del
dispositivo (fcntl.flock sobre /tmp/claude-1000/device.lock, equivalente a flock(1)).

Uso: gesture_bench.py <pan|zoom|rotate|idle> <segundos> <salida_prefijo>
Salidas: <prefijo>.latency.txt (volcados crudos), <prefijo>.json (estadisticas)
"""
import fcntl, json, math, re, struct, subprocess, sys, time, statistics, threading

PKG = "app.comaps.fdroid"
DEV = "/dev/input/event3"
W, H = 1080, 2400
CX, CY = 540, 1250
HZ = 120.0

EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
SYN_REPORT = 0
BTN_TOUCH = 0x14A
SLOT, TRACK, PX, PY, PRESS, TMAJOR = 0x2F, 0x39, 0x35, 0x36, 0x3A, 0x30


def ev(t, c, v):
    return struct.pack("<qqHHi", 0, 0, t, c, v)


class Touch:
    def __init__(self):
        self.p = subprocess.Popen(["adb", "exec-in", f"cat > {DEV}"], stdin=subprocess.PIPE)
        self.next_id = 100

    def send(self, evs):
        self.p.stdin.write(b"".join(evs) + ev(EV_SYN, SYN_REPORT, 0))
        self.p.stdin.flush()

    def down(self, fingers):  # fingers: list of (slot,x,y)
        evs = []
        for i, (s, x, y) in enumerate(fingers):
            evs += [ev(EV_ABS, SLOT, s), ev(EV_ABS, TRACK, self.next_id), ev(EV_ABS, PX, x), ev(EV_ABS, PY, y),
                    ev(EV_ABS, PRESS, 60), ev(EV_ABS, TMAJOR, 10)]
            self.next_id += 1
        evs.append(ev(EV_KEY, BTN_TOUCH, 1))
        self.send(evs)

    def move(self, fingers):
        evs = []
        for s, x, y in fingers:
            evs += [ev(EV_ABS, SLOT, s), ev(EV_ABS, PX, int(x)), ev(EV_ABS, PY, int(y))]
        self.send(evs)

    def up(self, slots):
        evs = []
        for s in slots:
            evs += [ev(EV_ABS, SLOT, s), ev(EV_ABS, TRACK, -1)]
        evs.append(ev(EV_KEY, BTN_TOUCH, 0))
        self.send(evs)

    def close(self):
        self.p.stdin.close()
        self.p.wait()


def ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * t)


def run_gesture(kind, secs):
    tp = Touch()
    n = int(secs * HZ)
    dt = 1.0 / HZ
    if kind == "pan":
        # 1 finger: vertical back-and-forth sweep (1 s cycles), amplitude 500 px
        tp.down([(0, CX, CY)])
        t0 = time.perf_counter()
        for i in range(n):
            ph = (i * dt) % 1.0
            y = CY + 500 * math.sin(2 * math.pi * ph)
            x = CX + 200 * math.sin(2 * math.pi * ph * 0.5)
            tp.move([(0, x, y)])
            time.sleep(max(0, t0 + (i + 1) * dt - time.perf_counter()))
        tp.up([0])
    elif kind == "zoom":
        # 2 horizontal fingers: separation 120 <-> 600 px, back and forth every 2 s
        tp.down([(0, CX - 60, CY), (1, CX + 60, CY)])
        t0 = time.perf_counter()
        for i in range(n):
            ph = (i * dt) % 2.0 / 2.0
            d = 120 + 480 * (0.5 - 0.5 * math.cos(2 * math.pi * ph))
            tp.move([(0, CX - d / 2, CY), (1, CX + d / 2, CY)])
            time.sleep(max(0, t0 + (i + 1) * dt - time.perf_counter()))
        tp.up([0, 1])
    elif kind == "rotate":
        # 2 fingers at radius 250 px rotating 360 degrees per 3 s
        r = 250
        tp.down([(0, CX - r, CY), (1, CX + r, CY)])
        t0 = time.perf_counter()
        for i in range(n):
            a = 2 * math.pi * ((i * dt) % 3.0) / 3.0
            tp.move([(0, CX - r * math.cos(a), CY - r * math.sin(a)), (1, CX + r * math.cos(a), CY + r * math.sin(a))])
            time.sleep(max(0, t0 + (i + 1) * dt - time.perf_counter()))
        tp.up([0, 1])
    else:
        time.sleep(secs)
    tp.close()


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout


def find_layer():
    out = adb("shell", "dumpsys", "SurfaceFlinger", "--list")
    cands = []
    for l in out.splitlines():
        m = re.search(r"(SurfaceView\[[^\]]*" + re.escape(PKG) + r"[^\]]*\]\(BLAST\)#\d+)", l)
        if m and m.group(1) not in cands:
            cands.append(m.group(1))
    return cands, out


def parse_latency(txt):
    rows = []
    for l in txt.splitlines()[1:]:
        p = l.split()
        if len(p) == 3 and p[0].isdigit() and int(p[1]) < 2**62 and int(p[1]) > 0:
            rows.append((int(p[0]), int(p[1]), int(p[2])))
    return rows


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(math.ceil(q * len(v))) - 1)]


def main():
    kind, secs, prefix = sys.argv[1], float(sys.argv[2]), sys.argv[3]
    lockf = open("/tmp/claude-1000/device.lock", "w")
    fcntl.flock(lockf, fcntl.LOCK_EX)
    try:
        cands, lst = find_layer()
        open(prefix + ".layers.txt", "w").write(lst)
        if not cands:
            print("SurfaceView layer not found", file=sys.stderr)
            sys.exit(2)
        layer = cands[0]
        adb("shell", "dumpsys", "SurfaceFlinger", "--latency-clear", layer)
        th = threading.Thread(target=run_gesture, args=(kind, secs))
        raw = open(prefix + ".latency.txt", "w")
        frames = {}
        th.start()
        while th.is_alive():
            t = adb("shell", "dumpsys", "SurfaceFlinger", "--latency", f'"{layer}"')
            raw.write(f"## poll {time.time()}\n{t}\n")
            for r in parse_latency(t):
                frames[r[1]] = r  # key: actualPresentTime (ns)
            time.sleep(0.3)
        th.join()
        t = adb("shell", "dumpsys", "SurfaceFlinger", "--latency", f'"{layer}"')
        raw.write(f"## final\n{t}\n")
        for r in parse_latency(t):
            frames[r[1]] = r
        raw.close()
    finally:
        fcntl.flock(lockf, fcntl.LOCK_UN)
    pres = sorted(frames)
    iv = [(b - a) / 1e6 for a, b in zip(pres, pres[1:])]
    res = {"gesture": kind, "secs": secs, "layer": layer, "frames": len(pres), "span_s": (pres[-1] - pres[0]) / 1e9 if pres else 0}
    if iv:
        res.update({
            "interval_ms_p50": pct(iv, 0.5), "interval_ms_p90": pct(iv, 0.9), "interval_ms_p95": pct(iv, 0.95),
            "interval_ms_p99": pct(iv, 0.99), "interval_ms_max": max(iv), "interval_ms_mean": statistics.mean(iv),
            "fps_mean": 1000.0 / statistics.mean(iv),
            "pct_intervals_gt_8.3ms_x1.5": 100.0 * sum(1 for x in iv if x > 12.5) / len(iv),
            "pct_intervals_gt_16.6ms": 100.0 * sum(1 for x in iv if x > 16.7) / len(iv),
        })
    json.dump(res, open(prefix + ".json", "w"), indent=1)
    print(json.dumps(res, indent=1))


main()
