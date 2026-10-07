# Voz de la navegación y ajustes de navegación (agente N2, rama `feat/nav-voice`)

Cubre la parte de voz de RF-05 y el diseño «Voz» de `docs/mapas-03-arquitectura.md` (sin GMS). Todo está probado **en la JVM
y con Robolectric**; no se ha usado ningún dispositivo (ver «Qué no está medido»).

## Piezas

| Pieza | Dónde | Qué hace |
|---|---|---|
| Texto de las indicaciones | `:core-voice` (JVM) `InstructionText`, `DistanceRounding` | `Announcement` + unidades + idioma → frase (es/en). |
| Ajustes | `:core-voice` `NavSettings`; `app/.../settings/PrefsNavSettingsStore` | Voz, solo importantes, volumen, unidades, idioma, «evitar por defecto». |
| Cola, foco y recuperación | `:core-voice` `SpeechDirector` (implementa `VoiceGuide`) | Prioridades, ducking, reinicio del motor, plazos. Hilo único vía `Scheduler`. |
| Motor Android | `app/.../voice/AndroidSpeechEngine`, `AndroidAudioFocus`, `HandlerScheduler` | `TextToSpeech` sin GMS, `AudioFocusRequest`, hilo principal. |
| Guía «sin voz» | `app/.../voice/VoiceInstall`, `VoiceProblemNotice`, `VoiceProblemBanner` | Qué motor libre instalar y cómo; aviso visual de una línea. |
| Enganche | `:core-voice` `VoiceNavigationController`; `app/.../voice/VoiceModule` | Se suscribe a la navegación y habla. |
| Ajustes (UI) | `app/.../settings/NavigationSettingsSection.kt` | Sección «Navegación» en `SettingsScreen`. |

## Texto de las indicaciones

Frases (idéntico en `InstructionTextTest`, que cubre los 18 `TurnType` con y sin calle, es/en):

- `FAR`/`NEAR`: «En 300 metros, gira a la izquierda en Calle de Alcalá». `NOW`: «Ahora, gira a la derecha».
- Rotonda: «En la rotonda, toma la segunda salida hacia Avenida…» (sin «Ahora»: la rotonda es la señal); salidas ordinales hasta la décima, después «la salida número 11»; sin salida conocida, «entra en la rotonda».
- Llegada: «Has llegado a tu destino» (con lado: «…, a la izquierda»); avisando de lejos: «En 300 metros, llegarás a tu destino» / «tu destino estará a la derecha».
- Mensajes sueltos: «Recalculando», «Has salido de la ruta», «Parada alcanzada».
- Calle ausente (`null`, vacía o el literal `"null"` que el núcleo produjo en la primera prueba real, ver `docs/decisions.md`): se omite. «Nullarbor Road» sí se dice.
- Giros suaves («gira ligeramente»), cerrados («gira cerrado»), cambio de sentido con lado, salida de autovía («toma la salida de la derecha hacia…»), incorporación.
- Carriles: «Mantente en el carril de la izquierda / en los dos carriles de la derecha / en el carril central / en el segundo carril por la izquierda», **solo en avisos `NEAR`** (de lejos es pronto y en `NOW` ya no hay tiempo) y solo si dice algo (varios carriles, algunos recomendados, contiguos).
- Un aviso `FAR`/`NEAR` con la maniobra a menos de 20 m dice «Ahora».

**Redondeo de distancias** (`DistanceRounding`): métrico: desde 950 m en km (medios km hasta 10 km, enteros después: «1 kilómetro», «1,5 kilómetros»); por debajo, centenas desde 150 m (900 … 200), luego 100, 50 y decenas bajo 35 m. Imperial: millas desde ~950 ft (décimas de milla bajo 1 mi → «0,5 millas», medias después), pies por debajo con la misma escalera (800, 500, 300, 100, 50 ft). `UnitsPref.AUTO` usa la región del dispositivo (US, GB, LR, MM y territorios de EE. UU. → imperial). Decimal con coma en español y punto en inglés.

**Solo avisos importantes** (`isImportant`): giros, giros cerrados, cambios de sentido, salidas, rotondas y llegada; fuera «sigue recto», curvas suaves, incorporaciones y salir de rotonda, y todo `FAR` salvo salidas y rotondas.

## Cola de voz (`SpeechDirector`)

- `URGENT` (aviso «ahora», llegada, probar voz): vacía la cola, interrumpe lo que suena y habla de inmediato.
- `NORMAL` (aviso cercano, recálculo, parada): espera su turno; sustituye al pendiente con la misma `key`; descarta los `LOW` pendientes e interrumpe uno `LOW` que esté sonando (el aviso lejano caduca en cuanto toca el cercano).
- `LOW` (aviso lejano): una sola plaza; el nuevo sustituye al viejo, así no se acumulan.
- Caducidad: `URGENT` 10 s, `NORMAL` 15 s, `LOW` 8 s en cola; como mucho 4 pendientes. «Gira a la izquierda» dicho tarde es peor que el silencio.
- Los callbacks del motor llegan en hilos de binder: el director los reenvía a su hilo (el principal) y descarta los de un motor ya reemplazado o de un enunciado interrumpido (ids).
- **Enfoque de audio:** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` con `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH` (Context7, guía de audio focus de Android: la música de otras apps baja de volumen mientras hablamos y vuelve al abandonar). Se abandona 700 ms después de la última frase para que dos avisos seguidos no hagan «saltar» la música. Si otra app lo quita (llamada, alarma), se calla; si se deniega al pedirlo (llamada en curso), el aviso se descarta en vez de hablar encima.
- **Volumen:** `KEY_PARAM_VOLUME` (0–1, relativo al flujo) con la preferencia (25/50/75/100 %), y atributos de navegación para que los botones de volumen del sistema lo gobiernen. **Pendiente para N1:** `setVolumeControlStream(AudioManager.STREAM_MUSIC)` en la actividad de navegación para que los botones lo afecten con la pantalla encendida (no lo toco: es la UI de navegación).
- **Idioma:** cada `Utterance` lleva su idioma; el motor cambia (`setLanguage`) solo si hace falta. `Auto` = idioma de la app (es → español, resto → inglés).

## Sin motor TTS (frecuente sin GMS)

- `AndroidSpeechEngine` usa el motor predeterminado del sistema (nada de Google Play Services). **Hace falta `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`** en el manifiesto: desde Android 11 (visibilidad de paquetes, Context7) sin eso la app no ve ningún motor y `TextToSpeech` falla aunque haya varios. Ya está añadido y un test lo vigila.
- Estados (`VoiceStatus`): `NoEngine` (no hay ninguno instalado: `onInit` falla y ningún paquete declara el servicio), `LanguageMissing(lang)` (ningún motor tiene datos del idioma; antes de rendirse prueba los demás motores instalados), `Failed` (el motor no arranca o se cae repetidamente) y `Ready`. La navegación **sigue sin voz** en todos los casos; el aviso visual es `VoiceProblemBanner` (una línea, para la pantalla de navegación) y `VoiceProblemNotice` (la guía completa, en Ajustes).
- **Camino en F-Droid** (comprobado en las fichas de f-droid.org el 2026-10-07; no se enlaza a ninguna tienda propietaria):
  - **RHVoice** `com.github.olga_yakovleva.rhvoice.android` (GPL-3.0+; español e inglés; voces grabadas, más naturales). F-Droid lo marca «promueve complementos no libres» (algunas voces tienen licencia no libre).
  - **eSpeak NG** `com.reecedunn.espeak` (GPL-3.0+; más de 100 idiomas, voz robótica, ~9 MB, sin permisos ni descargas; en F-Droid su ficha se llama «eSpeak»).
  - **SherpaTTS** `org.woheller69.ttsengine` (GPL-3.0; voces neuronales Piper, las más naturales; 86 MB y descarga el modelo una vez desde Hugging Face, F-Droid lo marca como servicio de red no libre; Android 10+).
  - Los botones abren `https://f-droid.org/packages/<id>/` (el cliente de F-Droid atiende esos enlaces; si no está, el navegador). La app no hace ninguna conexión por su cuenta. Luego el usuario elige el motor en Ajustes de Android → «Salida de texto a voz» (botón «Abrir ajustes de texto a voz», intent `com.android.settings.TTS_SETTINGS`, con respaldo a los ajustes generales) y pulsa «Reintentar».
  - Falta de datos de idioma: botón «Instalar datos de voz» (`ACTION_INSTALL_TTS_DATA`).
  - LineageOS suele traer Pico TTS (`com.svox.pico`) de serie; el director lo usa si es el predeterminado o si es otro motor instalado con el idioma. *No verificado en un dispositivo.*
- **Robustez** (todo en `SpeechDirectorTest` con tiempo virtual): el motor que no responde a `onInit` en 10 s se reinicia; un error fatal (`ERROR_SERVICE`), un `speak` rechazado o dos enunciados seguidos sin «fin» (plazo `5 s + 100 ms/carácter`, máx. 30 s) lo recrean con espera creciente (0,5 s, 1 s, 2 s…); el enunciado interrumpido se reintenta si sigue vigente; más de 3 reinicios en un minuto → `Failed` y silencio hasta `retry`. No hay bucle de reinicios cuando no hay motor (solo el usuario puede arreglarlo; `prepare` lo vuelve a comprobar al empezar otra navegación).

## Ajustes de navegación

Sección «Navegación» en `SettingsScreen` (tras Gasolineras; solo se muestra si `SettingsEnv.navigation != null`, que pone `SettingsActivity`): guía por voz, solo avisos importantes, volumen, idioma de la voz (Auto/es/en), unidades (Automáticas/km/mi), «Probar voz» (dice una frase real con las unidades, idioma y volumen elegidos, aunque la voz esté apagada, y muestra la guía si falla) y «Evitar por defecto» (autopistas, peajes, ferris, sin asfaltar). Almacén `PrefsNavSettingsStore` (`mapas_nav`), normalizado al leer y escribir; valores por defecto: voz activada, 100 %, unidades por región, idioma de la app, **nada evitado**. `NavSettings.routeOptions()` los convierte en `RouteOptions` para que la pantalla de ruta (otro agente) empiece desde ellos.

Cambios en código ajeno: en `SettingsScreen.kt` solo se subió a `internal` la visibilidad de los 5 componentes (`SectionTitle`, `Card`, `TextButton`, `SwitchRow`, `ChoiceRow`), se añadió el parámetro `navigation` (con valor por defecto) a `SettingsEnv` y una línea que dibuja la sección; en `SettingsActivity` una línea. También `app/build.gradle.kts` (una dependencia), `AndroidManifest.xml` (`<queries>`) y `settings.gradle.kts` (una línea: `include(":core-voice")`). **`core-nav` no se tocó.**

## Cómo conectarlo (N1 / coordinador)

`VoiceNavigationController` solo necesita tres flujos de `NavigationController` (`announcements`, `events`, `state`), que ya existen:

```kotlin
// NavigationService.onStartCommand, tras comprobar que hay navegación activa (start o resume):
VoiceModule.attach(app)            // idempotente
// NavigationService.shutDown() / onDestroy(), y al terminar la navegación desde la UI:
VoiceModule.detach()               // calla; si el último estado fue ARRIVED, deja acabar «Has llegado a tu destino»
```

Para evitar perder el primer aviso, `attach` debe ejecutarse antes de que el seguimiento emita (`announcements` no tiene repetición); lo natural es en `NavigationService` justo después de `controller.start/resume` (los avisos llegan al cabo de ≥ 1 tick). En la pantalla de navegación: `VoiceProblemBanner(VoiceModule.guide(ctx).status.collectAsState().value, onClick = { abrir Ajustes })`. Qué dice el controlador: cada `Announcement` (FAR → `LOW`, NEAR → `NORMAL`, NOW → `URGENT`); «Has salido de la ruta» + «Recalculando» al pasar a `OFF_ROUTE`/`REROUTING` (como mucho una vez cada 20 s: un recálculo fallido ciclaba cada ~8 s); «Parada alcanzada» (`StopReached`; `StopSkipped` calla); «Has llegado a tu destino» al pasar a `ARRIVED` si no se acaba de decir con la maniobra de llegada; si la navegación termina a medias, calla.

## Qué no está medido (sin dispositivo)

- **La calidad de la voz** (naturalidad, pronunciación de nombres de calle, velocidad) depende del motor del usuario: no medida ni evaluable en la JVM. Las frases se han revisado como texto.
- **El comportamiento real del enfoque de audio**: que la música baje y vuelva, cómo reaccionan Spotify, Bluetooth/coche o las llamadas; los 700 ms de retención y la política «descartar si se deniega» son decisiones razonadas, no probadas. Robolectric solo comprueba que se pide con los atributos y el tipo correctos, y que se abandona.
- **Motores reales**: `ShadowTextToSpeech` es un motor falso; no se ha probado con RHVoice, eSpeak NG, SherpaTTS ni Pico, ni el reinicio con un servicio TTS que muere de verdad (en JVM se simula con `onError(ERROR_SERVICE)`), ni cuánto tarda `onInit` (el plazo de 10 s es una estimación), ni que el cliente de F-Droid atienda los enlaces `f-droid.org/packages`.
- **Latencia** del primer aviso (el motor arranca con `prepare` al empezar la navegación, pero no se ha medido).
- **Botones de volumen** con la pantalla de navegación encendida (falta `setVolumeControlStream` en esa actividad, ver arriba).

## Alternativas descartadas

- Una voz propia empaquetada (eSpeak NG/Piper dentro del APK): pesa decenas de MB y duplica algo que el sistema ya ofrece; sigue siendo opción si el 100 % sin motor resulta frecuente (ver arquitectura).
- Voz pregrabada por maniobra: no cubre nombres de calle.
- Hablar siempre aunque se deniegue el foco: es hablar encima de una llamada.
- Poner la interfaz `VoiceGuide` y la cola en `:app`: no se podrían probar en la JVM con tiempo virtual (ahí están los tests de recuperación); en `:app` quedan solo las clases que tocan Android.
