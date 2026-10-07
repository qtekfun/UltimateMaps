# Settings hub

Settings is a hub: a short list of categories (icon, title, one-line summary of the current state, chevron) with a
search field on top. A row opens that category's screen inside the same `SettingsActivity` (back arrow in the title
bar, system Back returns to the hub first, the open category and the Advanced groups survive rotation). No navigation
library: the open category id is one `rememberSaveable` string in `SettingsScreen`.

The Settings button lives in the right-hand map button column: my location, compass (only when the map is rotated or
tilted), Settings. The OpenStreetMap attribution stays top-left; the scale bar sits under it.

Renders (JVM, Robolectric; no device): `docs/phase2/settings-hub/README.md`.

## Categories

| Id | Content | Env (null hides it) |
| --- | --- | --- |
| `navigation` | voice, units, 3D, status-bar distance chip, avoid defaults, bike cycleways | `SettingsEnv.navigation` |
| `alerts` | speed cameras and traffic incidents, alert modes, refresh interval (Advanced) | `SettingsEnv.cameras` |
| `fuel` | enable, fuels, map fuel, update, refresh and source URL (Advanced) | always |
| `network` | privacy note, offline mode, region catalog, possible connections | always |
| `data` | recent searches, track recording, backup and restore (destructive actions set apart) | `history`, `recording`, `backup` (any) |
| `about` | version, licence, attributions (OSM, petrol prices, transit data) | always (`about`) |

## How to add a setting

1. Put the row (a `Card` with `SwitchRow` / `ChoiceRow`, from `SettingsParts.kt`) in the content composable of the
   category it belongs to. Keep its test tag and persistence key; never rename an existing preference key (the
   settings-backup whitelist test, `SettingsSchemaCoverageTest`, depends on them). Add the key to `SettingsSchema`.
2. Use `SectionTitle` for a group heading, `AdvancedGroup("<tag>")` for things most people never change (it is closed by
   default; header tag `<tag>_header`), `DestructiveButton` for delete / clear actions.
3. If the state should show in the hub row, extend that category's `summary` in `SettingsHub.kt` (strings in
   `strings_settings_hub.xml`, English in `values/`, Spanish in `values-es/`).
4. Add words people would search for to the category's `*_keywords` string (both languages).
5. Tests: render the category with `SettingsScreen(env, onBack = {}, initialCategory = "<id>")`, or open it from the hub
   with the `openCategory("<id>")` helper (`SettingsHubTest.kt`). Inside an Advanced group, click the header first.

## How to add a category

Add one `SettingsCategory(id, title, keywords, icon, summary, content)` to `settingsCategories(env)` in
`SettingsHub.kt` (a small data class). The icon is a `DrawScope` function (see `SettingsIcons.kt`), the summary is a
composable returning one line, the content is a composable. A category whose data source is missing is simply not added.

## Not done

- "Storage location" and "mute defaults" settings do not exist in this code base yet, so they have no row.
- Long descriptions were kept as they were (they carry the privacy and honesty statements); only group headings and
  the Advanced groups were added.
- Layout and feel on a device (glove mode, TalkBack) are checked by tests and renders only.
