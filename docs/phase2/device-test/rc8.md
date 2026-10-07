# rc.8 on the Pixel 8 (2026-10-07, owner's permission)

Installed `0.1.0-rc.8` over rc.6 (same package and key: the update kept settings and maps). Location permission was not granted; the map and search work from the map centre. Screenshots in this folder; the file-picker screens were not kept because they showed personal files.

## Seen working
- **Category search** (`rc8-category-search.png`): the Pharmacy chip lists nearby pharmacies nearest first with distance, and draws green pins. Distances are from the map centre because there is no location permission.
- **Place card** (`rc8-place-card.png`): shows the Plus Code and, for the pharmacy tested, the phone number read from the map data (tap to dial). That pharmacy has no website or opening hours in the data, so those rows were not seen. Scale bar visible ("100 m").
- **Recent searches** are listed under the search box with a Delete button.
- **Settings backup and restore** (`rc8-settings-restore.png`):
  - Export wrote `ultimatemaps-settings-20261007.json` (2184 bytes) through the system picker. Its content is settings only: no positions, no passwords, no search history; consent-sensitive switches exported as off here.
  - Import of the same file says it matches the current settings and changes nothing.
  - After switching one setting, import says exactly which group changes ("Track recording: 1"); Restore puts it back and reports "Restored 30, skipped 0".
  - The exported file was deleted from the phone afterwards.

## Seen, not a bug of this build
- The Maps screen lists the whole CoMaps hierarchy; everything outside Spain says "Not available yet", and `World` and `WorldCoasts` appear as items although they are base files. Cosmetic; worth hiding.
- `installed_regions` in the export was empty: the Spain data on this phone was pushed by hand earlier, not installed through the Maps screen, so the app does not list it as installed. Not checked with a region installed from the catalog.

## Still wrong (known)
- The "Park here" chip wraps onto two lines and the chip row is uneven.

## Not tested in this session
Navigation and 3D (seen in the rc.6 test), camera and incident alerts (the switches are off and the data file was not downloaded), the incident banner, public transport (the index is not in the data release yet), voice, GPS (no permission), bike options, add stop, track recording, Live Update chip and alert chimes (not in rc.8).

## Second pass (same day): speed cameras
- Settings > Radares y tráfico (the old long list): everything is off by default; turning on "Radares fijos" shows the consent dialog (DGT CC BY and OSM ODbL sources, mobile zones are DGT road stretches only, no police controls or user reports, nothing about the position is sent, informative only). After accepting, the camera file was downloaded from the data release (no manual step) and the map draws fixed cameras as red circles with an R (`rc8-camera-pins.png`, zoom about 13 near Moncloa).
- Not seen: the alert chip, the incident banner or any sound, because no route through a camera was driven. A test route started from a tapped map point that fell inside a restricted compound came out as a 1.7 km spiral inside it, which says nothing about camera alerts; a better test is a route along an urban motorway with a camera on it.
- Observed: the consent text is long; the Settings page is hard to scan (the redesign is in progress).
