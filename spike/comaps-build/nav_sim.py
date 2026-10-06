#!/usr/bin/env python3
"""Simulacion de navegacion por adb: provider GPS de prueba (cmd location) alimentado a 1 Hz a lo largo de un recorrido
Sol -> Cibeles -> Colon -> Castellana -> Plaza de Castilla, con la ruta en coche calculada por la app. Hace capturas
cada ~10 s y guarda el logcat. Bajo el lock del dispositivo. Limpia el provider al terminar.
Uso: nav_sim.py <prefijo salida> [segundos=70]"""
import fcntl, math, subprocess, sys, time

P = sys.argv[1]
SECS = int(sys.argv[2]) if len(sys.argv) > 2 else 70
PTS = [(40.4168, -3.7038), (40.4195, -3.6931), (40.4250, -3.6892), (40.4399, -3.6903), (40.4458, -3.6925), (40.4663, -3.6890)]


def adb(*a, **k):
    return subprocess.run(["adb", *a], capture_output=True, text=True, **k).stdout


def path(speed_mps=14.0):
    out = []
    for (a, b) in zip(PTS, PTS[1:]):
        dy = (b[0] - a[0]) * 111320
        dx = (b[1] - a[1]) * 111320 * math.cos(math.radians(a[0]))
        d = math.hypot(dx, dy)
        n = max(1, int(d / speed_mps))
        brg = (math.degrees(math.atan2(dx, dy)) + 360) % 360
        for i in range(n):
            f = i / n
            out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, brg))
    return out


lock = open("/tmp/claude-1000/device.lock", "w")
fcntl.flock(lock, fcntl.LOCK_EX)
try:
    log = open(P + ".steps.txt", "w")
    def sh(*a):
        r = adb("shell", *a)
        log.write("$ " + " ".join(a) + "\n" + r + "\n")
        return r
    sh("cmd", "location", "providers", "add-test-provider", "gps")
    sh("cmd", "location", "providers", "set-test-provider-enabled", "gps", "true")
    def setloc(lat, lon, brg, spd):
        sh("cmd", "location", "providers", "set-test-provider-location", "gps", "--location", f"{lat},{lon}",
           "--accuracy", "5", "--bearing", f"{brg:.0f}", "--speed", f"{spd}")
    pts = path()
    setloc(*pts[0][:2], pts[0][2], 0)
    time.sleep(2)
    adb("logcat", "-c")
    # ruta Sol -> Plaza de Castilla en coche; la app puede restaurar otra, de modo que se repite
    adb("shell", "am", "force-stop", "app.comaps.fdroid")
    time.sleep(1)
    adb("shell", "am start -W -a android.intent.action.VIEW -d 'comaps://route?sll=40.4168,-3.7038&saddr=A&dll=40.4663,-3.6890&daddr=B&type=vehicle' app.comaps.fdroid")
    for _ in range(8):
        setloc(*pts[0][:2], pts[0][2], 0)
        time.sleep(1)
    open(P + "-planner.png", "wb").write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    sh("input", "tap", "890", "2128")  # Empezar
    for _ in range(4):
        setloc(*pts[0][:2], pts[0][2], 0)
        time.sleep(1)
    sh("input", "tap", "828", "1396")  # Aceptar: planificar desde la ubicacion actual
    for _ in range(6):
        setloc(*pts[0][:2], pts[0][2], 0)
        time.sleep(1)
    sh("input", "tap", "890", "2128")  # Empezar de nuevo
    for _ in range(3):
        setloc(*pts[0][:2], pts[0][2], 0)
        time.sleep(1)
    t0 = time.time()
    for i, (lat, lon, brg) in enumerate(pts):
        if i > SECS:
            break
        setloc(lat, lon, brg, 14)
        if i % 10 == 0:
            open(f"{P}-nav{i:03d}.png", "wb").write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
        time.sleep(max(0, t0 + (i + 1) - time.time()))
    open(P + ".logcat.txt", "w").write(adb("logcat", "-d", "-v", "threadtime"))
finally:
    adb("shell", "cmd", "location", "providers", "remove-test-provider", "gps")
    fcntl.flock(lock, fcntl.LOCK_UN)
