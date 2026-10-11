# Battery: where the power goes and what was done (2026-10-11)

The owner reported that the app drains a lot of battery. This is what was measured on real phones, what the causes are and what
changed. Numbers are "% of one CPU core" of the app process (per-thread `utime+stime` from `/proc/<pid>/task/*/stat` over 45 to 60 s,
script in the PR) unless stated; the screen, which every app shares, is not counted. Battery counters of the phones are too coarse
for minute-long runs (charge counter moves in steps of about 1 percent), so the CPU time is the proxy and the Android battery
statistics of the owner's real use give the absolute picture.

## 1. The owner's real use (realme RMX5210, read-only `dumpsys batterystats`, 78 min on battery)

| | |
|---|---|
| Phone discharge in the window | 478 mAh |
| UltimateMaps (foss, rc.23) | **127 mAh, the top app (27 %)**; 58.9 of those are the screen attributed to the foreground app |
| Foreground time | 22 min 50 s (5 activities), foreground service 36 s |
| CPU | **39.4 mAh; 18 min 25 s user + 2 min 26 s kernel in 22 min 50 s foreground = about 91 % of one core, all the time the app was on screen** |
| GNSS | 26.8 mAh for 23 min 26 s (one continuous registration, about 69 mA): that is the GPS chip, not avoidable while locating |
| Wi-Fi scans | 45 scans, 1.5 mAh (the system fused provider's, not the app's) |
| Wake locks | none of its own (GMS location ones: a few seconds); no alarms; the screen-on window lock is the "keep screen on" flag during navigation |
| Sensors | rotation vector held for the whole 22 min 24 s (15 events a second) |
| Location | two simultaneous HIGH_ACCURACY 1 s requests per session (the map's and the navigation's); one GPS, so no extra radio cost |

So there is no leak of wake locks, alarms, services or network: the cost is **CPU in the foreground, about one core**, plus the GPS.

## 2. Where the CPU goes (OPPO CPH2841, Android 16, 1440x3168; a `bench` build of master, Madrid region, location on)

| Scenario | CPU of the app | Of which map render thread |
|---|---|---|
| Map open, no location | 0.3 % | 0 |
| Map open, location and compass on (idle) | **5.0 %** | 4.3 % (10 redraws a second) |
| Simulated navigation, 3D with buildings, 60 redraws a second (defaults) | **78 to 84 %** | 61 to 68 % |
| same, with a frame cap of 45 / 30 redraws a second | 64 % / 53 % | 52 % / 40 % |
| same, label (symbol) layers hidden | 41 % | 27 % |
| same, only the road-name layers hidden | 67 % | 53 % |
| same, buildings off | 78 % (no change) | |
| App in the background, navigation running (simulated) | 4.4 % | |
| Screen off, navigation running | 2.0 % | |

(Realme, same app: idle 4.7 %; navigation 3D 74 %, 2D 57 %. The frame-cap, label and building rows come from instrumented builds
of master with a switch for each, one 45 s run each, so read them to within about 4 points.)

Findings:

1. **Navigation costs about 0.8 of a core at 60 redraws a second, and it is almost all the map library's render thread**: about 11 ms
   of CPU per frame. It is linear in the frame rate (about 0.7 point of a core per frame a second, 32 points fixed), so the
   navigation frame-rate setting is by far the biggest lever: 60 to 30 saves about 25 points (a third).
2. **The labels (symbol layers) are the most expensive part of a frame.** Hiding all of them takes 33 points off; no single layer
   explains it (POIs, one-way arrows, house numbers, road names, places each give 0 to 8 points alone), so a partial label cut is not a cheap win.
   The 3D buildings are not the problem (no change when off). A tilted camera shows more tiles and labels: 2D was 17 points cheaper on the realme.
3. **The idle map redrew the whole map ten times a second** whenever the position dot was shown: the 1 Hz GPS fix and the compass
   (rotation vector at 15 Hz, reported at 1.5 degrees) each hand a new marker to the map library, which parses it and places every label again.
   A phone lying on a table produced 10 of them a second from noise. Fixed (below).
4. Every hand-over of the marker's GeoJSON source costs a re-placement of all labels; at the navigation redraw rate that was 60 a
   second plus the compass events, which also bypassed the frame-rate throttle (and the cruise rate) because
   `NavHost.onDeviceHeadingChanged` pushed a frame for each sensor event even while driving.
5. Nothing runs in the background after leaving navigation (checked: CPU 4 %, the rotation-vector sensor is unregistered, no wake
   locks of the app besides the location listener's own, no alarms).
6. The display: while navigating the window asks for 60 Hz (`preferredRefreshRate`); `dumpsys display` shows a 60 Hz render rate
   on both phones (their panels go to 120/144 Hz otherwise), so the refresh hint works.

## 3. What changed

| Change | Effect (OPPO) |
|---|---|
| `IdleMarkerFilter`: the idle map hands the marker to the map library only when it moved 3 m or turned 4 degrees (or every 5 s) | idle map **5.0 % -> 1.4 %** of a core, redraws 10 -> 2 a second |
| The compass sensor is registered only while a navigation or the map's own dot shows it, at 10 Hz instead of about 15, and the idle arrow moves in 4 degree steps | sensor off when nothing shows it; fewer events |
| While navigating, a compass reading draws only when the vehicle is nearly standing, through the frame-rate throttle | stops the compass bypassing the 60/45/30 and cruise rates |
| New `bench` build type (release-like, own id, debug key, profileable) | lets any session measure next to a signed release without touching its data |

## 4. What did not pay off (measured, then removed)

Two more changes were built and measured, and taken out again because they did not show in the navigation numbers:

* handing the marker to the map at most every 30 ms (it is 60 a second at the default rate): interleaved runs of master and the
  branch with it gave 83.4 and 80.8 % against 78.5, 82.6 and 80.4 %, which is inside the run-to-run noise of about 4 points;
* switching the label fade transition off while navigating: single runs showed 5 to 7 points less, but together with the cap
  the interleaved runs above did not.

The navigation cost is the per-frame render itself; the only things that move it are the frame rate, the tilt and the labels (section 2).

## 5. Not done, and why

* **Navigation numbers did not change with this PR** (the marker work above is for the idle map). The default navigation frame rate
  (60 -> 30) is the largest single saving (about a third of the navigation CPU) but trades
  smoothness, which the owner asked for in rc.11; it is a separate PR (`fix/nav-frame-rate-default`) so it can be taken or dropped on
  its own. It was not judged by eye on a moving car.
* Fewer or cheaper labels in the navigation view (the second biggest lever) needs a design decision on which labels to keep and was
  not attempted; the numbers above say it is all-or-nothing, not one layer.
* A native puck (MapLibre's `LocationIndicatorLayer` is package-private in 13.6.1) or a screen-space overlay would remove the
  per-frame GeoJSON hand-overs entirely; not done, the gain is about 5 to 10 points and it changes how the marker looks.
* Not measured: a real drive (the GPS was simulated, so the navigation foreground service was not run), the Pixel 8, `play` flavor.
* Battery counters (`dumpsys batterystats`) over a full real trip would give the absolute mAh; the owner's own numbers in section 1
  are the reference to compare against after the next release.

## 6. How to measure again

```
./gradlew :app:assembleFossBench      # installs next to the signed app as com.qtekfun.ultimatemaps.bench
# per-thread CPU over N s (device shell):  see scripts/battery/cpu.sh
```
