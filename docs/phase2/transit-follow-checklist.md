# Step-by-step public-transport trip: on-device checklist

For the owner, with a build from branch `feat/transit-follow` (a `test-v...` pre-release signed with the debug key, never `v*`).
Nothing here has been done on a device yet: the code was verified with JVM and Robolectric tests only, plus a playback of real
itineraries planned on the Madrid index through the follower (`docs/phase2/transit.md`, section 7). Every threshold of the
follower is a design choice, not a measurement; this ride is how they get measured. Mark each line OK / not OK and write down the
build and the line.

Before starting: the Madrid transit index must be installed (Maps screen, section Public transport) and still valid (the Renfe
window lasts 30 days; Metro de Madrid is not in the index while its feed is expired). Pick a real trip you are going to make
anyway: ideally one bus, one metro or Cercanias leg and one change. Use the phone's own Pixel 8 only with the owner's
permission each time; none of this needs `adb`.

| # | Step | Expected result |
|---|---|---|
| 1 | Route panel, Transit, choose a start and destination, leave now, open an itinerary. | The card has a big **Start** button above the legs (not on a walk-only itinerary). |
| 2 | Press Start while standing away from the first stop. | The sheet and map buttons give way to the trip screen. Banner: "Walk to <stop>", distance and minutes, then "Then line X at HH:MM". The plan chip shows nothing or "On plan". The line is drawn on the map. |
| 3 | Walk to the stop. | The distance falls; on arrival the banner changes to "Board line X towards Y", "Departs at HH:MM, in N min". |
| 4 | Wait at the stop with the voice on. | About a minute before the departure: "Board line X towards Y now" (spoken in the language chosen in Settings, Navigation). Said once. |
| 5 | Board the vehicle and ride. | Within a few hundred metres the banner becomes "Next stop: <stop>" with "Get off at <stop> in N stops". The list marks passed stops with a tick and the next stop in bold. **Note how many metres or seconds after boarding it took.** |
| 6 | At each stop compare the banner with the real next stop. | The next stop and the stops left are right, or off by at most one while the vehicle is at a stop. **Note any mismatch and the line.** |
| 7 | Compare the plan chip with the timetable. | "On plan" within about 1.5 min; otherwise "About N min behind/ahead of plan". The vehicle being late must show as behind. |
| 8 | Two stops before getting off. | "Get off at the next stop: <stop>" when it is the last one; the voice says "Get ready to get off at <stop>, the next stop", then "Get off now at <stop>" about 120 m before. |
| 9 | Get off and change. | "Change here: line X towards Y" with the walk and the departure time (or "<stop> · departs HH:MM" when the stop is the same). The connection margin is sensible. |
| 10 | Be late on purpose for a change (stay a few minutes at a shop). | "Connection to line X at risk", then "Line X may be missed" with a **Re-plan** button. Nothing re-plans by itself. |
| 11 | Press Re-plan. | "Planning a new trip...", then the banner follows a new itinerary from where you are. With no data or no route: "No new trip found from here" and the old trip stays. |
| 12 | Get on the wrong bus, or walk away from the plan for a minute. | After a few fixes: "You are off the plan" and the Re-plan button. When you come back to the line it clears by itself. |
| 13 | Metro or Cercanias leg underground. | When the GPS is lost for about 45 s: "No GPS: position estimated from the timetable", the plan chip says "(estimate)", the next stop follows the timetable. After the first fix outside it recovers. **Note any wrong estimate and the line.** |
| 14 | Lock the phone during the ride. | The trip shows over the lock screen; the notification has the next instruction and the plan ("Next stop: X" / "Get off at Y in 3 stops · About 2 min behind plan"); the voice is heard. |
| 15 | Android 16 only: status bar. | A chip "3 stops" / "Get off" / "4 min" (Settings, Navigation, the status-bar switch controls it). |
| 16 | Press Home, use another app for a few stops, then return. | The service kept going; the screen shows the right stop. |
| 17 | Force-stop the app mid-ride, then open it (within 3 h). | The service restarts the trip, or the screen offers "Resume your transit trip?". Resume continues at the right stop within one or two fixes. |
| 18 | Settings, Navigation, Transit trip prompts: Sound, then Silent. Press Mute on the trip screen. | Sound: a short rising chime instead of words (note whether the pitch is clear and distinct from the camera chime). Silent: nothing audible, the banner still changes. Mute: nothing audible in any mode. |
| 19 | Glove mode and night. | Bigger buttons (at least 56 dp), readable text at night; TalkBack reads the banner changes politely. |
| 20 | Arrive. | "You have arrived", the Stop button becomes **Done**, the notification stays as a plain notification, the location stops. Done closes the screen. |
| 21 | Battery: note the percentage drop for the ride with the screen off. | Not measured yet: write it down with the length of the ride. |

What to bring back: for each leg the vehicle type, the mismatches of steps 5 to 7 and 13, the seconds it took to board, whether a
bus was ever taken for "off plan", the distance at which "get off now" was said, and a screenshot of the banner at the worst
moment. The thresholds to tune are all in `FollowerConfig`.
