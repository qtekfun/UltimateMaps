# Positioning inside road tunnels: analysis and plan

Status: analysis and plan only, no code. Written 2026-10-08. Nothing here was tested on a device.

Question: can UltimateMaps keep a good position inside road tunnels the way Waze and Google Maps do (Bluetooth beacons), within our
constraints (offline, private, no telemetry, foss flavor without Play Services, GPLv3)?

Short answer: **do stage 1 (better dead reckoning on the route, tunnel-aware) now; do not build beacon matching (stage 2) unless the
owner accepts a research spike, because the part that makes beacons useful (the beacon id to coordinates mapping) is not public.**

## 1. Findings

### 1.1 How the beacon systems work

- Waze Beacons are battery-powered BLE transmitters glued to tunnel walls every ~30-40 m (Madrid press: 20-40 m), 4-6 year battery,
  one-way broadcast (the phone never transmits to them). Waze describes them as "powered by Eddystone". The driver only needs Bluetooth
  on. Waze says beacons "do not collect any data from vehicles".
  Sources: [Waze program overview](https://www.waze.com/discuss/t/waze-beacons-program-overview-all-you-need-to-know/411647),
  [TechCrunch 2016](https://techcrunch.com/2016/09/21/waze-launches-bluetooth-beacons-to-avoid-tunnel-blackouts),
  [Traffic Technology Today (Boston, 850+ beacons)](https://traffictechnologytoday.com/news/traffic-management/waze-beacons-eradicate-gps-blindness-in-bostons-tunnel-network.html).
- **What is public** ([Waze partner help, "How Waze Beacons work"](https://support.google.com/waze/partners/answer/9416071?hl=en)):
  the beacons send two Eddystone frame types, UID and TLM (telemetry: battery, uptime, temperature). The Waze app ignores UID packets that
  do not carry Waze's UID namespace `0xEDE5A7B1986E2BE4CA5A`, and TLM packets whose MAC does not match such a UID packet. It records TX
  power, RSSI and a timestamp per packet. The Eddystone frame layout itself is public ([Eddystone, Wikipedia](https://en.wikipedia.org/wiki/Eddystone_(Google)):
  10-byte namespace + 6-byte instance).
- **What is not public (could not find it):** how the 6-byte instance maps to a coordinate / tunnel chainage, whether the instance
  encodes anything structured, whether the app uploads the recorded packets, and how the app fuses RSSI with the route. I found no
  reverse-engineering write-up (one search; absence of evidence only). The mapping must live in Waze/Google back-end data supplied
  by whoever installs the beacons through the Waze Beacons Program.
- Waze states the technology is "open and free to use by any other navigation app, including Google Maps", but publishes **no spec,
  API or dataset** on that page. "Open" here reads as "no licence fee to consume", not "documented format". Madrid's press material
  calls the devices "open-source" per secondary reports; I could not open the primary note (HTTP 403) and
  found no repository, so treat that as unverified.
- Google Maps for Android added the same thing around Oct 2023, broadly Jan 2024: setting "Bluetooth tunnel beacons" (off by default,
  asks for the nearby-devices Bluetooth scan permission), Android only, works only where beacons exist
  ([9to5Google](https://9to5google.com/2024/01/15/google-maps-tunnel-navigation-beacons/),
  [Android Central](https://androidcentral.com/apps-software/google-maps-for-android-bluetooth-beacons-for-tunnel-navigation),
  [TechRadar](https://www.techradar.com/computing/websites-apps/google-maps-for-android-now-works-inside-tunnels-heres-how-to-enable-it)).
  I found no official Google page in the search results; this is from press. Google reusing Waze's beacons is consistent with the
  Waze statement above.
- I could **not** verify the claim that Google uses Wi-Fi/Bluetooth/sensor fusion for road tunnels. The sources I found describe only BLE
  beacons for tunnels. (Google's Fused Location Provider does use IMU and Wi-Fi for generic positioning, but that is closed and, for
  us, behind Play Services.) Wi-Fi inside road tunnels is not a thing in practice: no APs along the carriageway, and a 30 m/s car passes an
  AP in seconds; I found no deployment.

### 1.2 Where the beacons are (Spain / Europe)

- **Madrid M-30 (Calle 30):** announced April 2025, with Waze and Google. Reported as 1,600 beacons in the M-30 tunnels (~48 km of
  network, ~EUR 141k) plus ~1,100 more in other city tunnels (2,700 total). Spacing 20-40 m.
  Sources: [Xataka](https://www.xataka.com/movilidad/tuneles-m-30-absoluta-pesadilla-para-gps-tu-movil-madrid-quiere-arreglarlo-usando-bluetooth),
  [Hipertextual](https://hipertextual.com/tecnologia/la-m-30-de-madrid-ya-no-sera-un-caos-absoluto-tendras-senal-gps-gracias-a-las-nuevas-balizas-activa-este-ajuste/),
  [DPL News](https://dplnews.com/?p=289137), [Madrid city press note](https://diario.madrid.es/blog/notas-de-prensa/madrid-pone-en-marcha-el-sistema-de-balizas-que-permite-la-navegacion-asistida-por-los-tuneles-de-la-m-30-en-colaboracion-con-waze-y-google/)
  (could not fetch; numbers differ slightly between outlets). Which individual tunnels are covered: not verified.
- Elsewhere named by Waze/press: Paris (Sanef pilot), Brussels, Oslo, Boston, NYC, Chicago, Rio, Sydney, Mexico City, Pittsburgh. Current
  coverage: unknown; there is no public list or map of beacon positions that I found.
- Installation is by road authorities / operators applying to the Waze Beacons Program. Not something an app can do.

### 1.3 Android constraints for scanning

- BLE scanning on Android 12+ needs `BLUETOOTH_SCAN` (runtime). With `usesPermissionFlags="neverForLocation"` you can drop location
  permission, **but the platform then filters some beacons out of results** (Android docs), which would likely hide Eddystone beacons. Using
  scan results to derive position means we must NOT assert `neverForLocation`, and we already hold fine location.
  Source: [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- Background/foreground: our navigation runs in a foreground service with location type, so scanning from it is allowed; low-latency
  scan mode is needed at 30 m/s (a beacon every 20-40 m is one every ~1 s); battery cost is moderate. A hardware scan filter by service
  data `0xFEAA` (Eddystone) keeps the screen-off case alive. Not measured.
- Wi-Fi: `startScan()` is throttled to 4 per 2 minutes in the foreground and once per 30 minutes in the background (Android 9+); results
  need fine location + location services. That makes Wi-Fi useless for a car moving at tens of m/s. Source:
  [Android Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan). Wi-Fi RTT (802.11mc) needs supporting APs; none in tunnels.
- Everything above is a documentation read, not a device test. Phone and Android-version behaviour (OEM scan throttling, Doze) is unverified.

### 1.4 Open alternatives

- **OSM tunnel geometry:** `tunnel=yes` (or `building_passage`, `culvert`) on ways, usually with `layer<0`; ways are split at the portals
  ([Key:tunnel](https://wiki.openstreetmap.org/wiki/Key:tunnel)). Gives us entry/exit positions and length. Coverage for the long
  urban tunnels we care about is generally good, but data quality is not verified per tunnel. Note: our routing core is CoMaps (OMIM
  data); whether `RoutePlan` / `RouteGuidance` exposes a tunnel flag per segment is **not** known from the code (there is none today;
  needs a check in the native bridge, see 3.1).
- **Dead reckoning with the route as constraint:** in a tunnel there is normally one way to go, so progress along the route polyline is
  essentially one-dimensional: all that is unknown is distance travelled. That is exactly the case where dead reckoning is good.
- **IMU:** accelerometer + gyroscope + magnetometer (magnetometer is poor inside steel/concrete tunnels). The standard sensors need no
  permission. Integrating acceleration for distance drifts quickly, but it is useful to detect braking/stopping (jams in tunnels are
  common) and to correct a constant-speed assumption over tens of seconds.
- **Wheel/vehicle speed (OBD, Android Auto):** exact, but needs hardware or the car API; out of scope.
- **Pressure / light / cell-tower sensing:** weak or privacy-unfriendly; not recommended.
- **Open beacon datasets:** none found. Not aware of any OSM tagging scheme for tunnel beacons.

## 2. What the code does today

- `core-map/.../LocationSource.kt`: `LocationFix(point, accuracy, bearing, speed, timeMillis)`; `LocationSource.start/stop/lastKnown`; a
  `SimulatedLocationSource` for tests. Real one: `app/.../location/AndroidLocationSource.kt` (LocationManager, no GMS).
- `NavigationSession` pulls fixes from the source into an inbox and, with no message for `tickMillis` (1 s), calls
  `RouteTracker.onTick(clock())`.
- `RouteTracker.onTick` (see `docs/phase2/following.md`, "Signal loss"): after `signalLossMillis` = 5 s with no usable fix, status becomes
  `NO_SIGNAL`, `estimated = true`, and progress = `anchor + lastSpeed * gap`, with the gap **capped at `estimateMaxMillis` = 30 s** and
  never past the end of the route. A fix with accuracy > 100 m is ignored, so a degraded fix does not hide the loss. When a real fix
  returns it wins; if far from the anchor the tracker searches the whole route and resyncs.
- Weak points for tunnels:
  1. **30 s cap:** at 25 m/s that freezes the dot after ~750 m; long urban tunnels (Madrid M-30 has multi-km sections; exact lengths not
     verified) outlast it, and the guidance (lane, next maneuver announcements) stalls inside the tunnel.
  2. **Constant last speed:** a jam or a stop in the tunnel (or speeding up) makes the estimate drift ahead of or behind the car; announcements
     for an exit inside or just after a tunnel are then early/late.
  3. **No tunnel knowledge:** a 5 s loss under a bridge or in a city canyon looks the same as a tunnel; nothing says "the exit is 600 m ahead",
     so we neither snap to the portal nor bound the error.
  4. **Last speed may be stale:** the GNSS speed at the last fix may have been already degraded when entering.
  5. Tracker is deliberately allocation-free and synchronous; any extension must keep that.

## 3. Recommended design

### 3.1 Stage 1: tunnel-aware dead reckoning (recommended, no new permissions)

Goal: a dot and announcements that stay credible for a few km without GNSS, and a clean hand-over at the exit.

1. **Tunnel spans on the route.** Add `tunnels: List<TunnelSpan>` (start and end in route metres) to the route geometry
   (`RouteGeometry`/`RouteGuidance`). Source, in order of preference: (a) a per-segment tunnel flag from the CoMaps routing result if the
   bridge can expose it (to be checked in the native code; OMIM carries tunnel info for rendering and routing, not confirmed for our JNI
   surface); (b) otherwise detect spans from OSM `tunnel=*` ways intersected with the route (needs the map data to be readable offline;
   cost unknown). Absence of spans must leave today's behaviour untouched.
2. **Estimator state machine** (new `TunnelEstimator`, used by `RouteTracker`, no allocation in the hot path):
   - If the last good fix was within ~100 m before a tunnel span start, or signal is lost while inside a span, mode = `IN_TUNNEL`
     (expected loss) instead of generic `NO_SIGNAL` (unexpected). Silence-based detection stays as fallback.
   - In `IN_TUNNEL` the 30 s cap becomes "until the span end + margin"; progress is clamped to `[spanStart, spanEnd]`: we never claim to
     be out of the tunnel before a fix says so, and never beyond the exit while still without fix (bounded error).
   - Speed model: last speed, blended with IMU longitudinal speed change (stage 1b), decaying confidence; stopped detection from IMU
     (no motion) freezes progress.
   - Publish an uncertainty (`estimateErrorMeters`, grows with time and with disagreement) so the UI can show a hollow/dimmed dot and
     the voice layer can avoid very precise "now" calls inside long gaps.
3. **IMU (stage 1b, separate PR):** a `MotionSensor` interface (`speedDeltaSince(t)`, `isStationary()`) behind a thin Android
   implementation using `TYPE_LINEAR_ACCELERATION`/`TYPE_GYROSCOPE` (and not relying on the magnetometer in tunnels). Only used while
   `IN_TUNNEL`/`NO_SIGNAL`, sampled at ~25-50 Hz, so no battery cost on open road. Gravity-removal and phone orientation (handheld vs
   mounted) are the hard parts; start with stop/go detection only, and add speed integration only if the on-device test shows it helps.
4. **Exit hand-over:** when a fix returns, keep today's rule (real fix wins, resync). Additionally: if the fix lands within the
   span's end margin, snap progress to the portal when the fix is poor; log nothing about positions.
5. **Announcements:** maneuvers located inside a tunnel span are announced from the estimate but with their `NOW` level suppressed if
   `estimateErrorMeters` is larger than the `NOW` band (they cannot be given reliably); the first maneuver after the exit uses the real
   fix.

Interfaces to add (names indicative):

```kotlin
// core-nav (pure JVM, testable)
data class TunnelSpan(val startMeters: Double, val endMeters: Double)
interface PositionEstimator {            // lives behind the tracker, fed by fixes, ticks and motion samples
    fun onFix(fix: LocationFix, progressMeters: Double)
    fun onMotion(sample: MotionSample)   // optional; default no-op
    fun estimate(nowMillis: Long): Estimate?   // progressMeters, errorMeters, mode
}
interface MotionSensor { fun start(listener: (MotionSample) -> Unit); fun stop() }   // Android impl in app/, fake in tests
```

Keeping `LocationSource` unchanged is deliberate: the estimator needs route progress, which only the tracker knows, so wrapping
`LocationSource` (emitting synthetic `LocationFix` while in a tunnel) would feed estimates back into off-route/accuracy logic as if they
were measurements. Where a synthetic fix is wanted (map dot while the session is not running), a `LocationSource` decorator can
re-emit the estimator's output marked `estimated = true` with large `accuracyMeters`. This matches the existing `SwitchableLocationSource`
pattern; decide when implementing.

### 3.2 Stage 2: optional BLE tunnel beacons (NOT recommended now)

Why not as a default plan:

- We can detect Waze beacons (public namespace + standard Eddystone UID) but **cannot place them**: instance id to coordinate is held by
  Waze/Google. Without that table, a detected beacon only means "I am inside a covered tunnel", plus RSSI that raises/lowers with proximity.
- What we could do without the table (self-calibrating, offline, private):
  - **Presence signal:** "a Waze-namespace beacon is heard" = confirmed in a covered tunnel. Useful to confirm `IN_TUNNEL`, relevant only where
    the span is not in map data.
  - **Local learning:** while GNSS is still good near a portal, record `instance id -> route progress` on-device; reuse on later trips. Bad
    in tunnels (no GNSS to learn from), only works at portals; at best it gives tunnel entry/exit confirmation. Beacon ids are not
    secret, but a table of "id -> place" is a movement record; if built it stays on-device and is opt-in.
  - **Crowd table/OSM tag scheme:** would need a community effort (e.g. someone surveys ids in the Madrid tunnels in order and publishes
    them under ODbL). Nothing exists; legally and technically uncertain.
- Asking Waze for the data: the Waze Beacons Program is for road authorities; its terms for third-party navigation apps are unknown to me
  (the "open and free" statement has no spec). This is a **decision for the owner** (contact Waze/Google or Madrid Calle 30, a
  non-technical request; not a code task).

If the owner still wants a spike: build a debug-only scanner (`BleBeaconProbe`), filter Eddystone service data `0xFEAA` plus the Waze
namespace, record (instance, RSSI, time, route progress) in a local file under `~/mapas-data`-style storage on a drive through a covered
tunnel, and look at whether instances increase monotonically along the tunnel and how stable RSSI peaks are. That decides in one drive
whether an inferred ordering is possible. It needs a device (see section 6).

Android implementation notes if it ever goes ahead: setting off by default, a clear explanation, runtime `BLUETOOTH_SCAN` request
(without `neverForLocation`, since results are used for position), scan only while navigating in a tunnel span, `SCAN_MODE_LOW_LATENCY`,
filter `ScanFilter.setServiceData(0xFEAA)` + namespace match in code, no use of Play Services Nearby, and no network use at all.
Wi-Fi scanning: **not recommended** (throttled, no useful APs, sensitive in privacy terms).

## 4. Tests (all JVM, no device)

Extend the existing `RouteSimulator(gaps = ...)` and `SimulatedLocationSource` (`NavigationSessionTest.tunnelShowsNoSignalAndEstimatesThenRecovers`
is the template):

- Tunnel longer than 30 s: with spans, progress keeps advancing until the exit and is clamped to the span; without spans, behaviour is
  unchanged (regression).
- Stop in the tunnel (fake `MotionSensor` reporting stationary): progress freezes; resumes on motion; error grows with time.
- Speed change in the tunnel (slow to 5 m/s): estimate error bounded vs. the simulator's ground truth, assert error < N m at the exit.
- Exit with a poor fix vs. a good fix; fix inside the margin snaps/resyncs; fix far away does the whole-route search as today.
- Short loss outside a tunnel (under a bridge): remains `NO_SIGNAL`, capped as today.
- A fork inside the span (should not exist; make sure the estimator never leaves the route).
- Announcements inside the span: `FAR`/`NEAR` emitted from the estimate, `NOW` suppressed when error is large; none emitted twice after resync.
- Determinism: use the injected clock (no real time; no recomposition from other threads), same rule as the existing tests.
- Stage 2 (if any): a `FakeBeaconScanner` feeding scripted (instance, RSSI) sequences; parser tests for Eddystone UID frames (byte arrays with the
  documented layout, namespace filter).

## 5. Privacy and policy notes

- IMU data stays in memory, is never logged or stored, and only runs while navigating without a signal.
- Estimated positions follow the existing rule: not written to logs by default, never leave the device.
- BLE scanning (stage 2) would add `BLUETOOTH_SCAN` and show up in the privacy policy and `docs/`; the scan data (beacon ids) is location-
  revealing by nature, so: opt-in, off by default, no persistence unless the owner approves a local learned table, never uploaded.
  Zero network use from this feature; nothing goes through `NetworkPolicy` because nothing needs the network.
- No Play Services (foss flavor): the stage 1 design uses `SensorManager` and our own tracker only. Not using `FusedLocationProviderClient`.
- Licensing: no new dependency in stage 1 (nothing to add to `LICENSES.md`). If OSM tunnel data are read, ODbL attribution already applies.

## 6. Effort, risks, what needs a device or the owner

| Item | Effort (rough) | Needs |
|---|---|---|
| Tunnel spans from route/OSM data (check CoMaps bridge first) | 2-4 days, mostly native/bridge investigation | Maybe submodule rebuild (serialize native builds) |
| `TunnelEstimator` + `PositionEstimator` + tracker integration + unit tests | 3-4 days | none |
| Uncertainty in `NavState` + UI dimmed dot + voice rule | 1-2 days | UI review by the owner |
| IMU `MotionSensor` (stop/go only) | 2-3 days | Device drive to tune |
| IMU speed integration | 3-5 days, uncertain gain | Device drives in a tunnel |
| BLE spike (debug probe) | 1-2 days | Device + a drive through a Madrid M-30 tunnel; owner permission to use the Pixel 8 each time |
| Real stage 2 (beacon positions) | unknown, blocked by data | Owner decision; data from Waze / Calle 30 |

Risks:

- Tunnel flag not available from the CoMaps bridge, and OSM matching proving costly: mitigate with the silence-based fallback and a
  generic "long gap" mode with a larger cap and growing uncertainty.
- IMU on handheld phones: orientation changes make integration unreliable; mitigate by using it only for stop/go and, optionally,
  only when the phone is stable.
- Dead reckoning error over several km (speed changes, lane/stop-and-go): bounded by the span clamp, and shown to the user via the error.
- Wrong tunnel data (missing `tunnel=yes`): behaves as today.
- Stage 2 risks: undocumented ids (blocker), possible terms-of-use constraints from Waze, OEM scan throttling, battery.

Needs the owner:

1. Approve stage 1 as the next navigation task (it is within autonomy; no non-negotiable decision changes).
2. Decide whether to contact Waze / Madrid Calle 30 about beacon id maps (outside the repo, third-party contact: ask-first item) or drop stage 2.
3. Permission to use the Pixel 8 for a tunnel drive (the Madrid M-30 is the obvious candidate) to tune stage 1b and run the stage 2 probe.

## 7. What I could not verify

- Whether Google uses Wi-Fi or other-sensor fusion in road tunnels: only BLE beacons are documented in what I found.
- The Waze UID instance layout and the id-to-position mapping; whether the Waze/Google apps upload beacon sightings.
- Madrid's "open source" claim for the beacons and the exact per-tunnel coverage; the city's press note returned HTTP 403 and Android Central's article body was
  truncated, so several details are from secondary summaries (Xataka, Hipertextual, 9to5Google, TechRadar).
- Whether the CoMaps JNI layer exposes tunnel information for route segments (not in `RoutePlan` today).
- Any battery, throttling or behaviour claims on real devices; the Android pages are documentation only.

## 8. Open alternatives (second research pass)

Written 2026-10-08. Question from the owner: is there anything more open or free for tunnel positioning than what sections 1-7 found?
Method: web search plus fetches of the primary pages that were reachable. Nothing here was tested on a device. "Not verified" means I
could not open or find a primary source; absence of results is weak evidence, not proof.

**Verdict:** no open dataset or open specification for tunnel beacon positions exists that I could find. The only genuinely open and
usable pieces are the generic ones: OSM tunnel geometry, GNSS-fade detection from the standard Android API, and route-constrained dead
reckoning. Stage 1 of section 3 stands; stage 2 (beacons) stays blocked by missing id-to-position data.

### 8.1 Madrid Calle 30 / city tunnel beacons: data and "open source"

- **Beacon id / coordinate dataset: none found.** Searches for a datos.madrid.es dataset, a GitHub repository, or an OSM tagging scheme found nothing.
  The only primary description of the mapping is the installer's page: each beacon "has been associated to its position in the tunnel", with no
  statement that the table is published ([Imesapi](https://www.imesapi.es/suministro-e-instalacion-de-balizas-para-la-navegacion-en-los-tuneles-de-la-m-30)).
  Not verified: whether Madrid Calle 30 holds the table and would share it on request (a question for the owner, see section 6).
- **"Open source" claim:** reported via press that the city calls the devices open source and privacy-respecting
  ([OKDiario](https://okdiario.com/madrid/adios-perderse-tuneles-madrid-novedad-que-llega-lo-cambia-todo-15596918), city note at
  [diario.madrid.es](https://diario.madrid.es/blog/notas-de-prensa/madrid-pone-en-marcha-el-sistema-de-balizas-que-permite-la-navegacion-asistida-por-los-tuneles-de-la-m-30-en-colaboracion-con-waze-y-google/);
  I could not open the note itself). No hardware design, firmware or id list was found, so it is not usable. Marked unverified.
- **Waze primary statement:** the technology is "open and free to use by any other navigation app, including Google Maps", but the page gives no
  spec, API, dataset, licence or beacon locations; it points to a FAQ and a contact address (waze-beacons-support@google.com) for technical
  requirements ([Waze program overview](https://www.waze.com/discuss/t/waze-beacons-program-overview-all-you-need-to-know/411647)).
  Fields broadcast: Eddystone UID and TLM; the app also records RSSI, TX power and timestamp
  ([How Waze Beacons work](https://support.google.com/waze/partners/answer/9416071?hl=en)). The Waze beacon FAQ
  (`support.google.com/waze/partners/answer/9416294`) was named by the overview but I did not fetch it: **not verified, worth reading before contacting Waze.**
- **Reverse engineering of the instance id:** none found (Wikipedia and Scapy only document generic Eddystone framing:
  [Eddystone](https://en.wikipedia.org/wiki/Eddystone_(Google)), [Scapy eddystone](https://scapy.readthedocs.io/en/latest/api/scapy.contrib.eddystone.html)).
- **OpenStreetMap:** no tagging scheme or discussion for road-tunnel beacons found. Nearest precedents are `indoormark=beacon`
  ([proposal, indoor beacons](https://wiki.openstreetmap.org/wiki/Proposal:Indoormark=beacon)) and an acoustic beacon proposal
  ([Proposal:Acoustic beacon](https://wiki.openstreetmap.org/wiki/Proposal:Acoustic_beacon)); neither covers this. Only a template for a future community effort.

### 8.2 Other tunnels (Spain / Europe)

- Waze lists New York, Chicago, Paris, Rio, Brussels, Oslo, Sydney, Boston, Mexico City "and more"
  ([Waze partners, about the program](https://support.google.com/waze/partners/answer/7298984?hl=en)). Which tunnels, and whether ids or coordinates
  are public: **no source found.**
- Switzerland, Germany, Netherlands: no beacon rollout found in search results (one German article says the real spread is unknown). Not verified either way.
- Programme eligibility is road authorities and private road operators; joining is free (same Waze page). So every deployment has the same closed id table.

### 8.3 Open technical alternatives (offline, private)

| Technique | What I found | Usable for us? |
|---|---|---|
| **OSM `tunnel=yes`** | Ways split at portals, `layer<0` ([Key:tunnel](https://wiki.openstreetmap.org/wiki/Key:tunnel)); gives span and length. Our data is CoMaps/OMIM, tunnel flag in the JNI layer still unchecked (section 3.1). | Yes, as spans for stage 1. |
| **Smartphone INS + non-holonomic constraint (NHC)** | Li et al., ISPRS Archives XLVI-3/W1-2022: 3.1 %/D horizontal error in a tunnel with INS/NHC (I read "/D" as percent of distance, so about 31 m per km; the abstract does not spell this out). With BLE added, about 1 m in an underground car park. Source: [paper page](https://isprs-archives.copernicus.org/articles/XLVI-3-W1-2022/81/2022/) (DOI 10.5194/isprs-archives-XLVI-3-W1-2022-81-2022; I read only the abstract via search, full text not opened). | Yes as an order of magnitude; their phone was fixed and calibrated, ours may be handheld. |
| **Route as 1-D constraint (map matching)** | A UGV preprint reports RMSE dropping from 186.8 m to 1.7 m in long tunnels with HD-map matching ([arXiv 2606.19687](https://arxiv.org/pdf/2606.19687)); HD map and UGV sensors, not a phone. | Supports our design, numbers not transferable. |
| **Pure MEMS IMU drift** | Vendor claim: thousands of metres over 20 min for a MEMS INS ([Inertial Labs](https://inertiallabs.com/tunnel-guide-feature-for-gps-aided-ins-improves-performance-during-gnss-outage/)); a forum post sees a u-blox UDR module stable for 60-120 s then degrading ([SparkFun forum](https://community.sparkfun.com/t/neo-m8u-udr-performance-issues-significant-drift-and-velocity-lag-during-gnss-outages/68450)). Vendor/anecdote, not independent. | Confirms: do not integrate raw acceleration; use constraints, last speed and stop/go. |
| **GNSS fade at the entrance** | No Android app paper found. Patents describe entry detection from signal strength / satellite-count drops plus map data, and exit from a sharp rise ([US 10306559](https://patents.google.com/patent/US10306559); [US 11402514](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11402514)). `GnssMeasurement` (API 24+) exposes C/N0 per satellite ([GPS World](https://www.gpsworld.com/google-updates-progress-on-android-gnss-measurements/)). Patents: do not copy their claims; the idea (several satellites drop together, confirmed by map) is generic, but get a licence review before shipping. Thresholds untested. | Yes, with OSM span as confirmation. Needs only fine location. |
| **Barometer** | Relative altitude works, absolute does not; drifts over tens of minutes with weather; 0.65 m (indoor) and 1.39 m (outdoor) RMSE only after calibration against a terrain model and weather stations ([PolyU](https://research.polyu.edu.hk/en/publications/barometer-assisted-smartphone-localization-for-vehicle-navigation/)). No work found on tunnel entrance detection by pressure. A tunnel in a car is not sealed, so pressure effects are unverified. | Weak; at most an optional grade hint. Not recommended. |
| **Magnetometer** | No tunnel-specific accuracy numbers found. Section 1.4 already says it is poor in steel and concrete. | No. |
| **Wheel speed via OBD-II Bluetooth** | I did not find an accuracy study in this pass; exact vehicle speed is what OBD provides but it needs a dongle and extra Bluetooth permission. Not verified. | Optional later, off the critical path. |
| **Cellular / TLE** | Not researched beyond section 1.4 (needs network or a cell database, conflicts with offline and privacy rules). | No. |

### 8.4 What this changes in the plan

1. Nothing changes the stage 1 recommendation. The 3.1 %/D figure gives a planning number: a 2 km tunnel could drift about 60 m even with a
   constraint model; our route clamp and portal snap should keep us better than that (untested).
2. Add to stage 1: GNSS fade detection (several satellites losing C/N0 together) as a second trigger for `IN_TUNNEL`, confirmed by an OSM span;
   cheap, no new permission. Needs a patent check before implementation.
3. Stage 2 stays blocked by data, not by technology. The cheapest next step is non-technical: read the Waze beacon FAQ and ask Waze or Madrid
   Calle 30 whether beacon id positions can be shared with an open-source offline app (owner decision, third-party contact).
4. An OSM tagging scheme would be a community project; nothing exists.

### 8.5 Not verified in this pass

- Madrid open data portal contents (the catalogue was not browsed directly, only searched); GitHub search was by web search only.
- The primary Madrid press note, the Waze beacon FAQ, and the full text of the ISPRS paper.
- Any OBD, magnetometer, cellular or pressure-in-tunnel accuracy figure.
- Per-country beacon coverage in Europe.
