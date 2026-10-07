# rc.6 on the Pixel 8 (2026-10-07, owner's permission)

Installed `0.1.0-rc.6` (versionCode 10006, release build, debug key) over rc.2 and used it for about five minutes, with the simulated route. Location permission was not granted (the route origin was picked on the map). Screenshots in this folder; the rest are local only.

## Seen working
- The map, the fuel pins and the new chips (Home, Work, Park here, SOS) render; the Settings gear is on a visible chip.
- Offline search ("Plaza Mayor") returns results; the first query shows "Preparing offline search…" first. The delay was not measured.
- The redesigned route panel (`rc6-route-panel.png`): from/to card, profile selector, 15 min and 4.1 km for the chosen pair, Start, Simulate, route options.
- Navigation screen in the 3D view with extruded buildings (`rc6-nav-3d.png`): banner, then-instruction, lane arrows, speed limit and over-limit warning, arrival, Stop. Mute toggles (the label flips to "Unmute"). The Route button frames the whole route and returns to following after a few seconds.
- Route over the lock screen: with the keyguard showing (`isKeyguardShowing=true`) the focused window was the app's MainActivity, with the trip on screen. When the trip stopped the phone went back to the lock screen, as expected.
- Station card during navigation (`rc6-fuel-card-during-nav.png`): tapping a pump opens the card above the navigation screen with Go and Save (no Add stop).

## Problems found (not fixed yet)
1. A `geo:` link with `?q=` text shows "offline search is not available yet, so this link only moved the map": the message is stale, the search exists.
2. The "Park here" chip wraps onto two lines and makes the chip row uneven.
3. The bottom sheet is translucent: map labels and, during navigation, the buttons behind it show through the card text (`rc6-fuel-card-during-nav.png`).
4. The over-limit warning appeared with the simulated 50 km/h on a 50 km/h limit; the rule (strictly above the limit?) needs checking.
5. Callao to Plaza Mayor by car came out as 4.1 km, 15 min with a long loop to the north-west. It may be right (pedestrian streets and one-way streets around the square) but it was not checked against another router.
6. The geo-link place card has no Route or Save buttons (it is a plain notice).

## Not tested
Voice audio, the camera and incident layers and their alerts, the Mute effect on real speech, battery, long routes (the bench activity is debug-only), search latency numbers, Takeout and tracks, the lists editor, launcher shortcuts, the emergency screen.
