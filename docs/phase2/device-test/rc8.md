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
