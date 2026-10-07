# Place information in the user's language

Owner report (2026-10-07, Spanish phone, rc.8): the map information comes out in English; can the app show and search in the
local language, or do the maps exist in several languages? Observed: a search result reads
`Pharmacy · Madrid, Calle del Marqués de Urquijo, 27, Community of Madrid, Spain` (category and region/country in English, street
and name in Spanish), and the Maps list shows `World`, `WorldCoasts`, `Abkhazia`.

## What the data stores (verified on the files)

- **The .mwm files are multilingual.** Every feature keeps all the `name:xx` tags the generator accepts (`data/languages.txt`:
  default, en, es, ca, eu, gl, ast, oc, an, fr, de ...). `generator/search_index_builder.cpp` indexes every one of them
  (`f.ForEachName(m_inserter)`), each token tagged with its language. A byte search of the mirror files (261004) finds both
  forms, e.g. `Spain_Basque Country.mwm` holds `Donostia` and `San Sebastián`, `Spain_Catalonia_Provincia de Lleida.mwm` holds
  `Lleida` and `Lérida`, `Spain_Galicia_North.mwm` holds `A Coruña` and `La Coruña`. (Raw `grep`, so this proves the strings
  are stored, not how they are attached to features.)
- **Category names are not in the maps.** They are a CoMaps resource: `iphone/Maps/LocalizedStrings/<lang>.lproj/LocalizableTypes.strings`
  (1456 types, dozens of languages) for the displayed name and `data/categories.txt` (synonyms in many languages) for matching.
- **Region and country names are not in the maps either.** They are `data/countries-strings/<lang>.json/localize.json`
  (48 languages, 2554 keys in `es`: `Spain_Community of Madrid` -> `Comunidad de Madrid`, `Spain` -> `España`). The catalog's
  `countries.txt` only has the English ids; `country_name_synonyms` are alias spellings for matching, not translations.
- The street and the place name come from the feature itself, so they were already in the language of the tag that won.

## Why each part came out in English

| Part | Cause | Fix |
|---|---|---|
| Category (`Pharmacy`) | `um_platform.cpp` replaced `platform::GetLocalizedTypeName` with CoMaps' English-only `g_type2localizedType` (the desktop generator reads only `en.lproj`). The core was initialised with `es` correctly; the locale never reached this table. The old doc note "the UI translates" was never implemented. | `scripts/gen-localized-types.py` builds one table per language (en, es) from the same `.lproj` files; the wrapper looks up the user's language first, then English. |
| Region and country (`Community of Madrid, Spain`) | CoMaps builds it with `RegionInfoGetter` -> `TranslatedRegionName`, which picks `countries-strings/<lang>.json` from the user's language list. Our `GetAndroidSystemLanguages()` stub returned `{"en"}` for every phone. The same stub also decided the language of **result names**. | The stub now returns the chosen language (`um::PlaceLanguage()`). |
| Names (`Lleida` / `Lérida`) | Same stub; plus the default "local only" handling of CoMaps. | See the language modes below. |
| Maps list (`World`, `Abkhazia`) | `gen-region-catalog.py` wrote `name` from the English CoMaps id and kept `World`/`WorldCoasts` as regions without assets. | Catalog `names` map, base files dropped; the app also hides them for older catalogs. |

## The language modes

The setting "Language of place information" has four values. It is applied by the core on every search; no restart.

| Mode | Names | Address region/country | Category |
|---|---|---|---|
| Automatic (default) | the app language (es on a Spanish phone, otherwise en), then local, international, English | same language | same language |
| Spanish | Spanish first | Spanish | Spanish |
| English | English first | English | English |
| Local names | the name as written locally (the OSM `name` tag: `Donostia`, `Lleida`), then the regional languages, then the user's | the region's own language when CoMaps has a file for it (`ca`, `eu`; Galician has none and falls back to the user's) | the app language (type names have no "local" form) |

Mechanism: the existing `locale` parameter of init/search carries the choice (`es` or `es;local`), so the binder and JNI
formats do not change and an old client keeps working (a plain `es` means the user's language first). In C++
`ApplyPlaceLocale` sets the language the stubs return and two CoMaps settings (`MapLanguageCode` = `default` for local names, and
`MapLanguageLimitAlternativesToLocal` = `false`, the "system order" handling that keeps the fallbacks). On a change it also tells the
engine (`SetLocale`) and clears its caches (locality names are cached per language).

## Search in other languages (what the code does)

- The index holds every language's tokens (above), and the matcher scores a token higher when its language is in the "current" or
  "input" tier. Typing `Donostia` or `San Sebastián` therefore finds the same city whichever the map shows; no converter is needed.
- Category queries match the synonyms of **all** languages in `categories.txt` (the tier only ranks them), so `farmacia`,
  `pharmacy` and `apotheke` find the same places.
- `m_inputLocale` was the app language already; it is now the language part of the choice (never the `;local` suffix).
- Not done: a multi-language query (the core takes one input locale) and transliteration. Not needed for the cases above because
  all forms are indexed. Device check needed: the exact ranking of `Lérida` vs `Lleida` results.

## Map labels (tiles)

`scripts/gen-map-style.mjs` already writes `coalesce(name:es, name)` labels into the style at generation time. A runtime switch
would need either a style rewrite at load (`MultiRegionStyle` builds the style per region set: possible, but the label
expressions live in many layers and the change must be visible without a map reload) or per-language tiles. **Not done in this
change**: the labels keep the generated language (Spanish with local fallback). The setting applies to search results, the place
card and the Maps list. If the owner wants labels to follow it, the cheap path is regenerating the style with a `lang` per
setting value and switching the style JSON, measured on the device first.

## Cost summary

| Item | Cost |
|---|---|
| Category tables (en, es) | ~+150 kB native data (generated, ignored by git); `comaps-prepare.sh` generates them; without the file the core falls back to CoMaps' English table |
| More table languages | `--langs en,es,ca,...` (about 75 kB per language); only the app languages are shipped |
| Region names (es) | +about 40 kB of catalog JSON (1097 regions get a `names.es`) |
| Runtime | one settings write + cache clear when the choice changes; nothing per search otherwise |

## Licences

All the text comes from CoMaps (already a dependency, Apache-2.0): `LocalizableTypes.strings` and `countries-strings`. Nothing new
is invented or copied from elsewhere.

## Verified and not verified

- By tests: the choice-to-locale mapping, the store, the settings row, the search engine wrapper reading the choice at every call,
  the schema coverage of the new preference key, the catalog generator (`names`, no `World`), the catalog parser and the Maps list
  hiding base entries and showing Spanish names, the type-table generator. A native build (`:app:assembleFossDebug`) with the
  generated tables linked in.
- Not verified (needs the device or a run of the core): that `Pharmacy` becomes `Farmacia` end to end; the exact name chosen for
  `Lleida`/`Lérida` and `Donostia`/`San Sebastián` in each mode; the address region in Catalan or Basque in Local mode; that the
  cache clear on a language change is enough (a stale locality name would show up as the old language in the address).
- Device checklist: (1) Spanish phone, Automatic: search "farmacia" near Madrid: category `Farmacia`, address ending in
  `Comunidad de Madrid, España`. (2) Settings, English: same search: `Pharmacy`, `Community of Madrid, Spain`. (3) Local names near
  Lleida: `Lleida` and `Cataluña`/`Catalunya`. (4) Search `Donostia` and `San Sebastián`, `A Coruña` and `La Coruña`. (5) Maps list: no
  `World`/`WorldCoasts`; Spanish names only after the catalog is regenerated and published.
