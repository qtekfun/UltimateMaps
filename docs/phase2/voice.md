# Navigation voice and navigation settings (agent N2, branch `feat/nav-voice`)

Covers the voice part of RF-05 and the "Voice" design in `docs/mapas-03-arquitectura.md` (without GMS). Everything is tested **on the JVM
and with Robolectric**; no device was used (see "What is not measured").

## Components

| Component | Where | What it does |
|---|---|---|
| Text of the instructions | `:core-voice` (JVM) `InstructionText`, `DistanceRounding` | `Announcement` + units + language → sentence (es/en). |
| Settings | `:core-voice` `NavSettings`; `app/.../settings/PrefsNavSettingsStore` | Voice, important only, volume, units, language, "avoid by default". |
| Queue, focus and recovery | `:core-voice` `SpeechDirector` (implements `VoiceGuide`) | Priorities, ducking, engine restart, deadlines. Single thread via `Scheduler`. |
| Android engine | `app/.../voice/AndroidSpeechEngine`, `AndroidAudioFocus`, `HandlerScheduler` | `TextToSpeech` without GMS, `AudioFocusRequest`, main thread. |
| "No voice" guide | `app/.../voice/VoiceInstall`, `VoiceProblemNotice`, `VoiceProblemBanner` | Which free engine to install and how; one-line visual notice. |
| Wiring | `:core-voice` `VoiceNavigationController`; `app/.../voice/VoiceModule` | Subscribes to navigation and speaks. |
| Settings (UI) | `app/.../settings/NavigationSettingsSection.kt` | "Navigation" section in `SettingsScreen`. |

## Text of the instructions

Sentences (identical in `InstructionTextTest`, which covers the 18 `TurnType` with and without a street, es/en). The spoken Spanish strings are quoted literally, with an English gloss:

- `FAR`/`NEAR`: "En 300 metros, gira a la izquierda en Calle de Alcalá" (In 300 metres, turn left onto Calle de Alcalá). `NOW`: "Ahora, gira a la derecha" (Now, turn right).
- Roundabout: "En la rotonda, toma la segunda salida hacia Avenida…" (At the roundabout, take the second exit towards Avenida…) (without "Ahora": the roundabout is the cue); ordinal exits up to the tenth, then "la salida número 11" (exit number 11); with no known exit, "entra en la rotonda" (enter the roundabout).
- Arrival: "Has llegado a tu destino" (You have arrived at your destination) (with side: "…, a la izquierda"); announcing from afar: "En 300 metros, llegarás a tu destino" (In 300 metres, you will arrive at your destination) / "tu destino estará a la derecha" (your destination will be on the right).
- Standalone messages: "Recalculando" (Recalculating), "Has salido de la ruta" (You have left the route), "Parada alcanzada" (Stop reached).
- Missing street (`null`, empty or the literal `"null"` that the core produced in the first real test, see `docs/decisions.md`): omitted. "Nullarbor Road" is spoken.
- Slight turns ("gira ligeramente"), sharp turns ("gira cerrado"), U-turn with side, motorway exit ("toma la salida de la derecha hacia…"), merge.
- Lanes: "Mantente en el carril de la izquierda / en los dos carriles de la derecha / en el carril central / en el segundo carril por la izquierda" (Stay in the left lane / in the two right lanes / in the middle lane / in the second lane from the left), **only in `NEAR` announcements** (from afar it is too early and at `NOW` there is no time left) and only if it says something (several lanes, some recommended, contiguous).
- A `FAR`/`NEAR` announcement with the maneuver less than 20 m away says "Ahora" (Now).

**Distance rounding** (`DistanceRounding`): metric: from 950 m in km (half km up to 10 km, whole numbers after: "1 kilómetro", "1,5 kilómetros"); below that, hundreds from 150 m (900 … 200), then 100, 50 and tens below 35 m. Imperial: miles from ~950 ft (tenths of a mile below 1 mi → "0,5 millas", halves after), feet below with the same ladder (800, 500, 300, 100, 50 ft). `UnitsPref.AUTO` uses the device region (US, GB, LR, MM and US territories → imperial). Decimal comma in Spanish and point in English.

**Important announcements only** (`isImportant`): turns, sharp turns, U-turns, exits, roundabouts and arrival; left out are "keep straight", slight bends, merges and leaving a roundabout, and every `FAR` except exits and roundabouts.

## Voice queue (`SpeechDirector`)

- `URGENT` ("now" announcement, arrival, test voice): empties the queue, interrupts what is playing and speaks immediately.
- `NORMAL` (near announcement, reroute, stop): waits its turn; replaces the pending one with the same `key`; discards pending `LOW` ones and interrupts a `LOW` that is playing (the far announcement expires as soon as the near one is due).
- `LOW` (far announcement): a single slot; the new one replaces the old one, so they do not pile up.
- Expiry: `URGENT` 10 s, `NORMAL` 15 s, `LOW` 8 s in the queue; at most 4 pending. "Turn left" said late is worse than silence.
- Engine callbacks arrive on binder threads: the director forwards them to its thread (the main one) and discards those from an already replaced engine or from an interrupted utterance (ids).
- **Audio focus:** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH` (Context7, Android's audio focus guide: other apps' music drops in volume while we speak and comes back when we abandon focus). It is abandoned 700 ms after the last sentence so that two announcements in a row do not make the music "jump". If another app takes it away (call, alarm), it goes quiet; if it is denied when requested (call in progress), the announcement is dropped instead of speaking over it.
- **Volume:** `KEY_PARAM_VOLUME` (0–1, relative to the stream) with the preference (25/50/75/100 %), and navigation attributes so that the system volume buttons govern it. **Pending for N1:** `setVolumeControlStream(AudioManager.STREAM_MUSIC)` in the navigation activity so that the buttons affect it with the screen on (I do not touch it: it is the navigation UI).
- **Language:** each `Utterance` carries its language; the engine switches (`setLanguage`) only if needed. `Auto` = app language (es → Spanish, anything else → English).

## No TTS engine (common without GMS)

- `AndroidSpeechEngine` uses the system's default engine (no Google Play Services). **The manifest needs `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`**: since Android 11 (package visibility, Context7) without it the app sees no engine and `TextToSpeech` fails even if there are several. It is already added and a test guards it.
- States (`VoiceStatus`): `NoEngine` (none installed: `onInit` fails and no package declares the service), `LanguageMissing(lang)` (no engine has data for the language; before giving up it tries the other installed engines), `Failed` (the engine does not start or crashes repeatedly) and `Ready`. Navigation **carries on without voice** in every case; the visual notice is `VoiceProblemBanner` (one line, for the navigation screen) and `VoiceProblemNotice` (the full guide, in Settings).
- **F-Droid path** (checked on the f-droid.org listings on 2026-10-07; no link to any proprietary store):
  - **RHVoice** `com.github.olga_yakovleva.rhvoice.android` (GPL-3.0+; Spanish and English; recorded voices, more natural). F-Droid flags it "promotes non-free add-ons" (some voices have a non-free licence).
  - **eSpeak NG** `com.reecedunn.espeak` (GPL-3.0+; more than 100 languages, robotic voice, ~9 MB, no permissions or downloads; on F-Droid its listing is called "eSpeak").
  - **SherpaTTS** `org.woheller69.ttsengine` (GPL-3.0; Piper neural voices, the most natural; 86 MB and downloads the model once from Hugging Face, F-Droid flags it as a non-free network service; Android 10+).
  - The buttons open `https://f-droid.org/packages/<id>/` (the F-Droid client handles those links; if it is not installed, the browser). The app makes no connection on its own. Then the user picks the engine in Android Settings → "Text-to-speech output" ("Salida de texto a voz") (button "Open text-to-speech settings" ("Abrir ajustes de texto a voz"), intent `com.android.settings.TTS_SETTINGS`, falling back to the general settings) and presses "Retry" ("Reintentar").
  - Missing language data: button "Install voice data" ("Instalar datos de voz") (`ACTION_INSTALL_TTS_DATA`).
  - LineageOS usually ships Pico TTS (`com.svox.pico`) by default; the director uses it if it is the default or if it is another installed engine with the language. *Not verified on a device.*
- **Robustness** (all in `SpeechDirectorTest` with virtual time): an engine that does not respond to `onInit` within 10 s is restarted; a fatal error (`ERROR_SERVICE`), a rejected `speak` or two consecutive utterances without an "end" (deadline `5 s + 100 ms/character`, max. 30 s) recreate it with growing back-off (0.5 s, 1 s, 2 s…); the interrupted utterance is retried if it is still valid; more than 3 restarts in a minute → `Failed` and silence until `retry`. There is no restart loop when there is no engine (only the user can fix it; `prepare` checks again when another navigation starts).

## Navigation settings

"Navigation" section in `SettingsScreen` (after Gas stations; only shown if `SettingsEnv.navigation != null`, which `SettingsActivity` sets): voice guidance, important announcements only, volume, voice language (Auto/es/en), units (Automatic/km/mi), "Test voice" (says a real sentence with the chosen units, language and volume, even if the voice is off, and shows the guide if it fails) and "Avoid by default" (motorways, tolls, ferries, unpaved). Store `PrefsNavSettingsStore` (`mapas_nav`), normalised on read and write; default values: voice on, 100 %, units by region, app language, **nothing avoided**. `NavSettings.routeOptions()` converts them into `RouteOptions` so that the route screen (another agent) starts from them.

Changes in code owned by others: in `SettingsScreen.kt` only the visibility of the 5 components (`SectionTitle`, `Card`, `TextButton`, `SwitchRow`, `ChoiceRow`) was raised to `internal`, the `navigation` parameter (with a default value) was added to `SettingsEnv` and one line that draws the section; in `SettingsActivity` one line. Also `app/build.gradle.kts` (one dependency), `AndroidManifest.xml` (`<queries>`) and `settings.gradle.kts` (one line: `include(":core-voice")`). **`core-nav` was not touched.**

## How to wire it up (N1 / coordinator)

`VoiceNavigationController` only needs three flows from `NavigationController` (`announcements`, `events`, `state`), which already exist:

```kotlin
// NavigationService.onStartCommand, after checking that there is an active navigation (start or resume):
VoiceModule.attach(app)            // idempotent
// NavigationService.shutDown() / onDestroy(), and when navigation ends from the UI:
VoiceModule.detach()               // goes quiet; if the last state was ARRIVED, lets "Has llegado a tu destino" finish
```

To avoid losing the first announcement, `attach` must run before following emits (`announcements` has no replay); the natural place is in `NavigationService` right after `controller.start/resume` (announcements arrive after ≥ 1 tick). On the navigation screen: `VoiceProblemBanner(VoiceModule.guide(ctx).status.collectAsState().value, onClick = { open Settings })`. What the controller says: each `Announcement` (FAR → `LOW`, NEAR → `NORMAL`, NOW → `URGENT`); "Has salido de la ruta" + "Recalculando" when switching to `OFF_ROUTE`/`REROUTING` (at most once every 20 s: a failed reroute cycled every ~8 s); "Parada alcanzada" (`StopReached`; `StopSkipped` stays quiet); "Has llegado a tu destino" when switching to `ARRIVED` if it has not just been said with the arrival maneuver; if navigation ends midway, it goes quiet.

## What is not measured (no device)

- **Voice quality** (naturalness, pronunciation of street names, speed) depends on the user's engine: not measured and cannot be evaluated on the JVM. The sentences were reviewed as text.
- **Real audio focus behaviour**: that the music drops and comes back, how Spotify, Bluetooth/car or calls react; the 700 ms hold and the "drop if denied" policy are reasoned decisions, not tested. Robolectric only checks that it is requested with the right attributes and type, and that it is abandoned.
- **Real engines**: `ShadowTextToSpeech` is a fake engine; it was not tested with RHVoice, eSpeak NG, SherpaTTS or Pico, nor the restart with a TTS service that really dies (on the JVM it is simulated with `onError(ERROR_SERVICE)`), nor how long `onInit` takes (the 10 s deadline is an estimate), nor that the F-Droid client handles `f-droid.org/packages` links.
- **Latency** of the first announcement (the engine starts with `prepare` when navigation begins, but it has not been measured).
- **Volume buttons** with the navigation screen on (`setVolumeControlStream` is missing in that activity, see above).

## Discarded alternatives

- A bundled voice of our own (eSpeak NG/Piper inside the APK): weighs tens of MB and duplicates something the system already offers; still an option if 100 % without an engine turns out to be common (see architecture).
- Pre-recorded voice per maneuver: does not cover street names.
- Always speaking even if focus is denied: it means speaking over a call.
- Putting the `VoiceGuide` interface and the queue in `:app`: they could not be tested on the JVM with virtual time (that is where the recovery tests are); only the classes that touch Android stay in `:app`.
