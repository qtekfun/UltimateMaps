# Camera alerts: on-device checklist

For the owner, on the phone, with a build from branch `fix/camera-alerts` (or the release that contains it). Nothing here has
been done on a device yet: the code was verified with JVM and Robolectric tests only (see `cameras-implementation.md`,
section 9). Mark each line OK / not OK and note the build.

Before starting: the data release must contain the camera file (the catalog has a `cameras` block). Check it by opening Settings,
"Speed cameras and traffic", turning the switch on and reading the data card (step 2). A few cameras on the route you drive
are needed: pick a road in Spain with a fixed DGT or OSM camera (the map shows them as round red markers once the switch is on).

| # | Step | Expected result |
|---|---|---|
| 1 | Settings, "Speed cameras and traffic", turn on "Fixed speed cameras". | A notice appears once. After "I understand, turn on" the switch is on. |
| 2 | Stay on the screen for a few seconds, then read the data card below the switches. | A line like "N fixed cameras, ... updated yyyy-mm-dd", the attribution (DGT, OpenStreetMap) and "Update finished". If it says "never" or shows an error, note the error text: it is the answer to "why no alerts" (no connection, offline mode, catalog without the file). Press "Update now" once and read it again. |
| 3 | Back on the map, zoom to a town with cameras. | Red round markers for cameras. Tap one: a card with the road and the limit when known. |
| 4 | Without navigation: walk or drive towards a camera with the app open on the map. | About 30 s before it (400 to 1000 m), a chip appears under the map buttons: camera icon, "Speed camera ahead", the distance counting down and, if known, a round limit sign. A spoken "In N meters, possible fixed speed camera" is heard at the same time. The chip disappears after passing. |
| 5 | Start a navigation (real, not simulated) through a road with a camera. | Same chip, now under the maneuver banner. Spoken alert once per camera. |
| 6 | During step 5, press the Mute button. Drive towards another camera. | No voice; the chip still appears. Unmute: the voice comes back for the next camera. |
| 7 | Settings, Navigation, turn on "Important prompts only". Drive towards a camera. | The camera alert is still spoken (that option only filters turn prompts). |
| 8 | During a navigation, a turn is due within a few seconds of a camera. | The turn instruction is spoken; the camera alert is not spoken if the turn is closer than its own "near" prompt, but the chip shows. A camera alert never cuts a turn instruction short. Note anything that sounded wrong. |
| 9 | Lock the phone (navigation running). Drive towards a camera. | The route is visible over the lock screen, the chip shows there and the voice is heard (the navigation service keeps going). |
| 10 | Put the app in the background (home button, another app) during a navigation. | Voice alert still heard. Without a navigation (free driving) there is NO alert in the background: this is by design (no background service for it). |
| 11 | Glove mode on the navigation screen. | The chip is bigger (taller, larger distance and limit). |
| 12 | TalkBack on: drive or use a simulated trip near a camera. | The chip is read once as "Speed camera ahead, in 400 m, limit 70 km/h" and updates politely, not on every metre. |
| 13 | Turn the camera switches off. | No chip, no voice, and no location listener of the alerts (battery: nothing extra). |
| 14 | Offline mode on, then turn the camera switch on with no cached data. | The data card shows the offline error; no connection is made. |

Notes to bring back: the build, the road used, the distance at which the chip and the voice appeared (the 30 s look-ahead and the
35 degree cone are design values, not measured), false or missing alerts, and whether the chip covers anything important.
