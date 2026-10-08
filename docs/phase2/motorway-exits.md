# Motorway exits: exit number and what is signposted

Report: on a motorway the navigation said to take an exit but not the exit number or where it leads ("Take exit 23 toward Alcalá de Henares", "Exit 12A", "keep left to stay on A-2").

## 1. Audit: what the data allows

### In the CoMaps core (read only, `third_party/comaps/libs/routing`)

- `RouteSegment::RoadNameInfo` (`route.hpp`) has `m_name`, `m_ref`, `m_junction_ref` (OSM `junction:ref`, "398B"), `m_destination_ref` (OSM `destination:ref`, "CA 85"), `m_destination` (OSM `destination`, "Cupertino"), `m_isLink` and `m_highwayClass`.
- `DirectionsEngine::LoadPathAttributes` (`directions_engine.cpp`) fills them from the road feature: `FMD_JUNCTION_REF`, `FMD_DESTINATION_REF`, `FMD_DESTINATION` metadata, `GetRef()`, the name, and the link flag from the `highway=*_link` type. So these tags are part of the mwm road feature metadata (ids 37 to 39 in `feature_meta.hpp`); the generator keeps them (`osm2meta.cpp`; `destination` is normalised to "a; b; c") and `tag_admixer.hpp` copies `junction:ref` from the `highway=motorway_junction` node onto the ramp way.
- `Route::GetClosestStreetNameAfterIdx` (`route.cpp`) already assembles the exit information for a maneuver: it walks the ramp (all link segments), takes the first segment with exit tags, and fills the gaps from the first non-link road after it (`destination_ref` falls back to that road's `ref`, `destination` and `name` likewise). It is what CoMaps' own TTS (`turns_tts_text.cpp`) and its navigation panel use.
- Turn kinds: `CarDirection::ExitHighwayToLeft/Right` exist and are already mapped to our `TurnType.EXIT_LEFT/EXIT_RIGHT` (wire codes 12 and 13). Forks where you stay on the motorway come as `SlightLeft/Right` or `GoStraight` with the name of the road you enter.

### In our wrapper before this change

- `FillGuidance` (`native-comaps/src/main/cpp/um_core.cpp`) called `GetClosestStreetNameAfterIdx` and kept only one string: `m_name`, else `m_ref`, else `m_destination`. The exit number, the destination ref and the destination were computed and dropped.
- The wire (`GuidanceWire`) carried per maneuver: geometry index, turn type, roundabout exit, street name index, lanes; then speed limits and an optional trailing tunnel section. `Maneuver` had `type`, `streetName`, `roundaboutExit`, `lanes`.
- Banner (`NavScreen`, `NavTexts.instruction`): "<turn> onto <street>", with the exit icon from `NavIcons`. Voice (`InstructionText`): "take the exit on the right toward <street>". The notification: "<turn> onto <street>". None had an exit number.

### What the data does not give

- OSM coverage varies: many Spanish motorway ramps have `destination` / `destination:ref`, fewer have `junction:ref` on the node; without the tag nothing is shown (never invented). Whether the maps we ship carry the tags can only be seen on a device (see section 4).
- `destination:lanes`, `destination:street` and `exit_to` are not in the mwm and are not used.
- "Keep left to stay on A-2" is not a separate maneuver in the core: it is a slight left / straight onto a road with a ref. It already reads "bear slightly left onto A-2" (the street name falls back to the ref); this change does not add more.

## 2. Design

No CoMaps patch: the core already exposes everything (patches 0001 and 0004 are untouched, no 0005).

- `Maneuver` gets three optional trailing fields: `exitRef`, `towardRef`, `towardName` (null = the map has no such tag).
- Wire (`um_core.hpp`, `GuidanceWire`): after the tunnel section (which is then always written, possibly with 0 tunnels) an optional exit section `nExits, (maneuverOrdinal, exitRefIdx, towardRefIdx, towardNameIdx)*`, the indices pointing into the same names table as the street names (-1 = none). Arrays without it decode as before. Only maneuvers that are not roundabouts and whose `RoadNameInfo` has exit info (`HasExitInfo`: link or exit tags) with at least one non-empty field are listed.
- `RoutePlanCodec` version 4 appends the same data (maneuver index plus three texts) after the altitudes; versions 1 to 3 still read. The index is checked against the maneuver count.
- `ExitSign` (`:core-routing`) is the single place that decides what is shown: roundabouts, the start and the arrival never carry a sign; `;` and `,` split lists, at most two roads and two places are used; `label` is "A-2 · Alcalá de Henares", `spokenLabel` reads "A 2, Alcalá de Henares".
- Banner: "Exit 23" badge next to the distance (`nav_exit_badge`), instruction "Take the exit on the right toward A-2 · Alcalá de Henares", "Then" line and notification "Exit 23 · ...". A real exit type is always shown as an exit; another turn only when the map gave it an exit number. With no exit data the text is exactly the old one.
- Voice: "In 800 meters, take exit 23 on the right toward A 2, Alcalá de Henares" / "En 800 metros, toma la salida 23 a la derecha hacia A 2, Alcalá de Henares". Refs are spoken with spaces ("12A" is "12 A", "A-2" is "A 2"). Without an exit number: the old sentence, with the toward road or place replacing the street when known. "Only important prompts" keeps exit prompts, far ones included, also for a ramp that carries an exit number.

## 3. Tests (JVM)

- `GuidanceWireTest`: exit section decoded onto the right maneuver by ordinal, after limits and tunnels, files without it, blank texts, every kind of bad section rejected, a malformed section only drops the guidance.
- `ExitSignTest`: labels, spoken forms, no data, caps, and the codec (version 4 round trip, version 3 file still read, bad index).
- `InstructionTextExitTest`: the sentences in Spanish and English with and without ref and toward, ramps typed as slight turns, nothing leaking into roundabouts, merges or the arrival, the important-prompts rule.
- `NavScreenTest` and `NavServiceTest`: badge, instruction, "Then" line and notification, English and Spanish, and the unchanged text with no data.

## 4. What must be checked on the Pixel 8 (not run)

The native part cannot be tested on the JVM. With the debug build and the Spain maps, run the bench (with permission, as always):

`am start -n com.qtekfun.ultimatemaps/.bench.CoreBenchActivity --ez exits true`, then read the `UMBENCH` lines tagged `exits`. It routes Madrid centre to Alcalá de Henares (A-2), to Toledo (A-42) and to Guadalajara (A-2) and logs, for every exit maneuver (and every maneuver with exit data), `exitRef`, `towardRef` and `towardName`.

Expected: `withExitData` greater than 0 and the exit maneuvers carry at least a toward road; compare the values with the real signs. If everything is null the maps lack the tags (then the fix is in the data build, not in the app). Then drive or simulate the same route and check the banner badge, the instruction line, the "Then" line and the voice.
