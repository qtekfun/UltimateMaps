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
| 6 | During step 5, press the Mute button. Drive towards another camera. | No sound and no voice; the chip still appears. Unmute: the chime (or voice, per the chosen mode) comes back for the next camera. |
| 7 | Settings, Navigation, turn on "Important prompts only". Drive towards a camera. | The camera alert is still spoken (that option only filters turn prompts). |
| 8 | During a navigation, a turn is due within a few seconds of a camera. | The turn instruction is spoken; the camera alert is not spoken if the turn is closer than its own "near" prompt, but the chip shows. A camera alert never cuts a turn instruction short. Note anything that sounded wrong. |
| 9 | Lock the phone (navigation running). Drive towards a camera. | The route is visible over the lock screen, the chip shows there and the voice is heard (the navigation service keeps going). |
| 10 | Put the app in the background (home button, another app) during a navigation. | Voice alert still heard. Without a navigation (free driving) there is NO alert in the background: this is by design (no background service for it). |
| 11 | Glove mode on the navigation screen. | The chip is bigger (taller, larger distance and limit). |
| 12 | TalkBack on: drive or use a simulated trip near a camera. | The chip is read once as "Speed camera ahead, in 400 m, limit 70 km/h" and updates politely, not on every metre. |
| 13 | Turn the camera switches off. | No chip, no voice, and no location listener of the alerts (battery: nothing extra). |
| 14 | Offline mode on, then turn the camera switch on with no cached data. | The data card shows the offline error; no connection is made. |

## Incident banner and hazard cards (branch `feat/incident-banner`)

Turn on "Traffic incidents" in Settings (and V16 / roadworks if wanted) and wait for the data card to show a download. Plan a
route along a road that currently has an incident of the DGT feed (check the red or orange markers on the map first).

| # | Step | Expected result |
|---|---|---|
| 15 | Start a real navigation along that road and drive towards the incident (or use a simulated trip). | At about 400 to 1000 m, a banner appears under the maneuver banner and under the camera chip (if any): triangle icon, "Slow traffic ahead" (or the kind), the distance and a circular clock with 5 counting down to 1. |
| 16 | Watch it without touching anything. | After 5 seconds it disappears by itself and does not come back for that incident on the same trip. Note whether the clock was readable at speed. |
| 17 | Tap the banner before the 5 seconds. | It disappears at once. |
| 18 | Two incidents close together on the route. | One at a time, 5 seconds each, the nearest first; the second starts when the first goes. |
| 19 | Press Mute (voice off) and repeat 15. | The banner still appears (visual). The incident chime or spoken alert, per the chosen mode, is unchanged. |
| 20 | Make the app recalculate (leave the route and rejoin). | A banner that was showing disappears; an incident already shown is not shown again, a different one still can be. |
| 21 | Glove mode on. | The banner is taller with a larger clock. With TalkBack it is read once as "Slow traffic ahead, in 400 m" and offers "Dismiss", without announcing every second. |
| 22 | Turn "Traffic incidents" off in Settings during a trip. | No banner appears. |
| 23 | While navigating, tap an incident or a camera marker on the map. | Its card opens above the navigation screen; "Close" closes it and the navigation screen is as before. |
| 24 | Does the banner cover the Mute / "Mute alerts" buttons or the maneuver banner? | It must not (it sits in the top column only). |

Notes to bring back: the build, the road used, the distance at which the chip and the voice appeared (the 30 s look-ahead and the
35 degree cone are design values, not measured), false or missing alerts, and whether the chip covers anything important.

## Alert sound modes and the chime (branch `feat/alert-sound`)

In Settings, Speed cameras and traffic, turn on a camera category and "Traffic incidents". Each has a three-way row (Sound, Voice,
Silent), Sound by default. Use real navigation or a simulated trip near a known camera and a known incident.

| # | Step | Expected result |
|---|---|---|
| 25 | Both rows on Sound. Drive towards a camera. | A short two-tone chime that rises in pitch, once per stage (far, near), at the navigation voice volume; the chip shows; nothing is spoken. |
| 26 | Drive towards an incident (V16, slow traffic or roadworks if enabled). | A short two-tone chime that falls in pitch and sounds lower than the camera one; the banner shows. Without looking, can you tell which is which? Note if the pitches are too similar, too sharp or too quiet. |
| 27 | Play music (another app) and repeat 25. | The music is lowered while the chime plays and comes back right after, with no jump or silence that stays. |
| 28 | Use the maps voice or a podcast with speech and repeat 25. | Same ducking; the chime is clear over the speech. |
| 29 | Set the camera row to Voice and repeat 25; then Silent. | Voice: the old spoken sentence, no chime. Silent: nothing audible, the chip still shows. The incident row is unchanged by this. |
| 30 | Set the incident row to Voice or Silent and repeat 26. | Voice speaks, Silent shows only the banner; the camera row is unchanged. |
| 31 | Change the volume in Settings, Navigation (25 %, 100 %). | The chime follows it. |
| 32 | Press the navigation Mute and drive towards a camera and an incident. | No chime and no voice for either; the visuals show. Unmute: both come back. |
| 33 | Press "Mute alerts" (both rows on Sound, then Voice). | The button changes to "Unmute alerts"; no chime and no voice for cameras or incidents, but turn instructions are still spoken. Press it again: the same modes as before are back (Settings still shows the rows you chose). |
| 34 | Approach a camera just before a turn (a turn instruction about to be spoken). | No chime over the turn instruction; the chip still shows. Note if the chime ever collides with a spoken instruction already in progress. |
| 35 | Receive a phone call or start a loud alarm, then drive towards a camera. | No chime while the call or alarm has the audio focus. |
| 36 | Upgrade from a build that had the old "Voice alerts" switch (set on, then off in the old build). | On: both rows show Voice. Off: both show Silent. Never set: both show Sound. |

Notes to bring back: whether the two chimes are distinguishable at motorway speed with the radio on, whether 280 ms is long enough
to notice, the volume against music, and any chime that arrived late (after the camera was passed).
