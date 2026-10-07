#!/bin/bash
# Measures cold start and gfxinfo. Run it ENTIRELY under the lock:
#   flock /tmp/claude-1000/device.lock bash measure.sh <tag> <hz: 120|60> <style> <texture:true|false>
# (flock is NOT called inside). Leaves raw traces in ../traces/.
TAG=$1; HZ=$2; STYLE=${3:-style-es.json}; TEX=${4:-true}
PKG=org.ultimatemaps.spike.maplibre
ACT=$PKG/org.ultimatemaps.spike.MainActivity
T="$(dirname "$(readlink -f "$0")")/../traces"
mkdir -p "$T"
OLDMIN=$(adb shell settings get system min_refresh_rate); OLDPEAK=$(adb shell settings get system peak_refresh_rate)
echo "refresh antes: min=$OLDMIN peak=$OLDPEAK" > "$T/$TAG-env.txt"
if [ "$HZ" = 60 ]; then adb shell settings put system min_refresh_rate 60; adb shell settings put system peak_refresh_rate 60
else adb shell settings put system min_refresh_rate 120; adb shell settings put system peak_refresh_rate 120; fi
sleep 2
adb shell dumpsys display | grep -m1 "mActiveModeId\|activeModeId\|mActiveMode" >> "$T/$TAG-env.txt"
adb shell dumpsys SurfaceFlinger | grep -m3 -i "refresh-rate\|vsyncPeriod\|VSYNC period" >> "$T/$TAG-env.txt"
adb shell getprop ro.product.model >> "$T/$TAG-env.txt"
EX="--es style $STYLE --ez texture $TEX"

# 1) Cold start x10
: > "$T/$TAG-coldstart.txt"
for i in $(seq 1 10); do
  adb shell am force-stop $PKG; sleep 2
  adb logcat -c
  OUT=$(adb shell am start -W -n $ACT $EX)
  TT=$(echo "$OUT" | grep TotalTime | awk '{print $2}')
  sleep 5
  FR=$(adb logcat -d -s SPIKE | grep first_full_render | sed 's/.*ms=//')
  echo "run=$i TotalTime_ms=$TT first_full_render_ms=$FR" >> "$T/$TAG-coldstart.txt"
done

# 2) gfxinfo: pan (swipes) x3
for r in 1 2 3; do
  adb shell am force-stop $PKG; sleep 2
  adb shell am start -W -n $ACT $EX >/dev/null; sleep 6
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  # 12 swipes alternating direction (400 ms each), reproducible
  adb shell 'for i in 1 2 3 4 5 6; do input swipe 800 1500 300 700 400; input swipe 300 700 800 1500 400; done'
  sleep 1
  adb shell dumpsys gfxinfo $PKG framestats > "$T/$TAG-pan-$r.txt"
done
# 3) gfxinfo: double-tap zoom + (programmatic) zoom/rotation x3
for r in 1 2 3; do
  adb shell am force-stop $PKG; sleep 2
  adb shell am start -W -n $ACT $EX >/dev/null; sleep 6
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  adb shell 'for i in 1 2 3 4; do input tap 540 1200; input tap 540 1200; sleep 1.2; done'
  sleep 1
  adb shell dumpsys gfxinfo $PKG framestats > "$T/$TAG-dtap-$r.txt"
done
for r in 1 2 3; do
  adb shell am force-stop $PKG; sleep 2
  adb logcat -c
  adb shell am start -W -n $ACT $EX --ez demo true >/dev/null; sleep 7.5
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  sleep 9.5
  adb shell dumpsys gfxinfo $PKG framestats > "$T/$TAG-demo-$r.txt"
  adb logcat -d -s SPIKE > "$T/$TAG-demo-$r-log.txt"
done
if [ "$OLDMIN" = null ]; then adb shell settings delete system min_refresh_rate; else adb shell settings put system min_refresh_rate "$OLDMIN"; fi; adb shell settings put system peak_refresh_rate "$OLDPEAK"
echo "refresh restaurado: $(adb shell settings get system min_refresh_rate) / $(adb shell settings get system peak_refresh_rate)" >> "$T/$TAG-env.txt"
adb shell am force-stop $PKG
