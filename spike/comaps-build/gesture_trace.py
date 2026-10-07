#!/usr/bin/env python3
"""Perfetto + gesto: graba frametimeline/gfx durante un gesto (de gesture_bench.run_gesture) bajo el lock del dispositivo.
Uso: gesture_trace.py <pan|zoom|rotate|idle> <segundos> <salida.pftrace>   (trazas binarias van a /tmp, NO al repo)
"""
import fcntl, os, subprocess, sys, time, threading, importlib.util

here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, "gesture_bench.py")).read().replace("\nmain()\n", "\n")
ns = {"__name__": "gb"}
exec(compile(src, "gesture_bench", "exec"), ns)

kind, secs, out = sys.argv[1], float(sys.argv[2]), sys.argv[3]
cfg = open(os.path.join(here, "perfetto.cfg")).read().replace("duration_ms: 12000", f"duration_ms: {int((secs + 3) * 1000)}")
lockf = open("/tmp/claude-1000/device.lock", "w")
fcntl.flock(lockf, fcntl.LOCK_EX)
try:
    # reproducible position and zoom: central Madrid, z=15 (geo: intent)
    subprocess.run(["adb", "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "geo:40.4168,-3.7038?z=" + (sys.argv[4] if len(sys.argv) > 4 else "15"), "app.comaps.fdroid"], capture_output=True)
    time.sleep(4)
    subprocess.run(["adb", "shell", "input", "tap", "1006", "1999"])  # closes the place sheet if present
    time.sleep(2)
    p = subprocess.Popen(["adb", "shell", "perfetto", "-c", "-", "--txt", "-o", "/data/misc/perfetto-traces/gb.pftrace"],
                         stdin=subprocess.PIPE, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    p.stdin.write(cfg)
    p.stdin.close()
    time.sleep(1.5)
    if kind == "idle":
        time.sleep(secs)
    else:
        subprocess.run(["adb", "shell", "app_process", "-cp", "/data/local/tmp/inj_claude.dex", "/system/bin", "Inj", kind, str(secs)])
    o = p.communicate()[0]
    print(o)
    subprocess.run(["adb", "pull", "/data/misc/perfetto-traces/gb.pftrace", out], check=True)
    subprocess.run(["adb", "shell", "rm", "/data/misc/perfetto-traces/gb.pftrace"])
finally:
    fcntl.flock(lockf, fcntl.LOCK_UN)
