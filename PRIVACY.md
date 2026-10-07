# Privacy policy / Política de privacidad

*Español más abajo.*

## English

Mapas is a map and navigation app that works on your device. It has no servers of its own, no accounts, no ads, no analytics, no crash reporting and no telemetry. Search and routes are computed on the phone.

### What data goes where

- **Your location** is used only on the device, to show where you are and as the start of a route. It is never sent anywhere and it is not written to logs.
- **Saved places, lists and tracks** are stored in a local database on the device. **Backups are disabled**, so Android's cloud backup does not copy them. Files you export (GPX, KML, backups) are saved where you choose. The same database holds your **Home, Work and parked-car** places and your **recent searches** (the text you searched, never positions). The history can be turned off in *Settings*, which also deletes it, or cleared at any time.
- **Emergency screen.** It shows your current coordinates on the screen only; they are not stored or logged. The call button opens your phone dialer with 112 filled in (you press call there), and the share button hands the coordinates as text to the app you pick in the system share sheet; the app has no phone or SMS permission.
- **Map data** is downloaded only when you ask. The app can connect to **GitHub** to fetch the region catalog and the region files (`github.com`, `release-assets.githubusercontent.com`, `objects.githubusercontent.com`); GitHub sees your IP address and which files you download. The catalog address can be changed in the *Maps* screen. All connections use HTTPS; plain HTTP is refused.
- **Petrol-station prices (optional, off by default).** If you turn it on in *Settings*, the app downloads fuel prices from the Spanish Ministry open service (`sedeaplicaciones.minetur.gob.es`), **one file per fuel you choose** (for example LPG). The server sees your IP address and which fuels you download; **your location is never sent** (the files cover the whole country and are filtered on the phone; the app never asks by province, municipality or coordinates). It connects only when you turn the feature on, press *Update now*, or open the app with data older than the update frequency you set; never at start-up otherwise. The host is added to the list of possible connections only while the feature is on, and offline mode blocks it. The last download is kept on the device so the app works without network. Data: Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Hidrocarburos), reused under Law 37/2007; it is unofficial information (the price published by the Ministry), so check the price at the pump. The source address can be changed in *Settings*.
- **Speed cameras and traffic (optional, off by default, one switch per category in *Settings*).** *Fixed cameras and mobile-radar zones:* when you turn one on (after reading a notice), the app downloads one small file, `speedcams-es.bin`, from the **same map data server you already chose** (the catalog says where it is; GitHub by default). The server sees your IP address and that you asked for that file; **your position is never sent**. The file is built from Dirección General de Tráfico open data (CC BY) and OpenStreetMap (ODbL); mobile-radar zones are only the stretches of road the DGT publishes, never exact positions, and the app has no live reports, no police-control data and no user reports. *Traffic incidents and V16 beacons:* when you turn either on (after a notice), the app downloads the DGT national traffic file from `nap.dgt.es` (about 200 KB compressed; it is one file for the whole country with no parameters, so **neither your position nor your route is ever sent**: it is filtered on the phone). It connects only when you turn it on, press *Update now*, or open the app with data older than the interval you set (5 minutes to 1 hour); never otherwise. The host is listed as a possible connection only while a traffic switch is on, and offline mode blocks everything. Both downloads are kept on the device so the app works without network. While these alerts are on and the app is on screen, the app listens to your location **on the device** to warn you ahead; positions are never stored, logged or sent. Alerts are informational, can be wrong or out of date, and do not replace your own judgement or the traffic rules.
- **Nothing is requested at start-up.** The first connection happens when you open *Maps* or start a download.
- **No-network mode** (in the *Maps* screen) blocks every connection of the app's own, even downloads. It is also in *Settings > Privacy*.
- **Map links** (Google Maps, Apple Maps, Waze, `geo:`) are read on the device. Short links (for example `maps.app.goo.gl`) would need a request to the provider, so the app does **not** resolve them: it only tells you so.
- The map is drawn from files stored on the device; the style, fonts and icons are part of the app.

### Permissions and why

| Permission | Why |
|---|---|
| Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Show your position, use it as the start of a route and, only if you turn the camera or traffic alerts on, warn you about what is ahead. Asked when needed; the app also works without it. |
| Internet (`INTERNET`) | Only to download the region catalog and region files, and fuel prices, the speed-camera file and the DGT traffic file if you turn them on. |
| Network and Wi-Fi state (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) | Added by the map library, to know whether the device is online. |
| Foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) | Keeps a region download going while the screen is off, with a visible notification. |
| Notifications (`POST_NOTIFICATIONS`) | Shows the download progress (Android 13+). |
| Storage, camera, contacts | **No permission is requested.** Import and export use the system file picker. |

### Open data

Map data is © OpenStreetMap contributors, under the Open Database License (ODbL). The attribution is always visible on the map. See [`UltimateMaps-data`](https://github.com/qtekfun/UltimateMaps-data) for the origin of each file.

### Contact

Questions or concerns: open an issue in the project repository.

---

## Español

Mapas es una app de mapas y navegación que funciona en tu dispositivo. No tiene servidores propios, cuentas, anuncios, analítica, informes de fallos ni telemetría. La búsqueda y las rutas se calculan en el teléfono.

### Qué datos van adónde

- **Tu ubicación** se usa solo en el dispositivo, para mostrar dónde estás y como salida de una ruta. No se envía a ningún sitio ni se escribe en los registros.
- **Sitios guardados, listas y recorridos** se guardan en una base de datos local. **Las copias de seguridad están desactivadas**, así que la copia en la nube de Android no los copia. Los archivos que exportas (GPX, KML, copias) se guardan donde tú elijas. La misma base de datos guarda tus sitios de **Casa, Trabajo y coche aparcado** y tus **búsquedas recientes** (solo el texto, nunca posiciones). El historial se puede desactivar en *Ajustes*, lo que también lo borra, o borrar cuando quieras.
- **Pantalla de emergencia.** Muestra tus coordenadas actuales solo en pantalla; no se guardan ni se registran. El botón de llamada abre el marcador del teléfono con el 112 escrito (tú pulsas llamar allí) y el de compartir entrega las coordenadas como texto a la aplicación que elijas en el menú de compartir del sistema; la app no tiene permisos de teléfono ni de SMS.
- **Los datos de mapas** se descargan solo cuando lo pides. La app puede conectarse a **GitHub** para bajar el catálogo de regiones y sus ficheros (`github.com`, `release-assets.githubusercontent.com`, `objects.githubusercontent.com`); GitHub ve tu dirección IP y qué ficheros descargas. La dirección del catálogo se puede cambiar en la pantalla *Mapas*. Todas las conexiones usan HTTPS; el HTTP plano se rechaza.
- **Precios de gasolineras (opcional, apagado por defecto).** Si lo activas en *Ajustes*, la app descarga los precios del servicio abierto del Ministerio (`sedeaplicaciones.minetur.gob.es`), **un fichero por cada combustible que elijas** (por ejemplo GLP). El servidor ve tu dirección IP y qué combustibles descargas; **tu ubicación no se envía nunca** (los ficheros son nacionales y se filtran en el móvil; la app no pregunta nunca por provincia, municipio ni coordenadas). Solo se conecta al activar la función, al pulsar *Actualizar ahora* o al abrir la app con datos más antiguos que la frecuencia que elijas; al arrancar no se pide nada. El servidor aparece en la lista de conexiones posibles solo mientras la función está activa, y el modo sin red lo bloquea. La última descarga se guarda en el dispositivo para que la app funcione sin red. Datos: Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Hidrocarburos), reutilizados conforme a la Ley 37/2007; es información no oficial (el precio publicado por el Ministerio), así que comprueba el precio en el surtidor. La dirección de la fuente se puede cambiar en *Ajustes*.
- **Radares y tráfico (opcional, apagado por defecto, un interruptor por categoría en *Ajustes*).** *Radares fijos y zonas de radar móvil:* al activar uno (tras leer un aviso), la app descarga un fichero pequeño, `speedcams-es.bin`, del **mismo servidor de datos de mapas que ya elegiste** (el catálogo dice dónde está; GitHub por defecto). El servidor ve tu dirección IP y que pides ese fichero; **tu posición no se envía nunca**. El fichero se construye con datos abiertos de la Dirección General de Tráfico (CC BY) y de OpenStreetMap (ODbL); las zonas de radar móvil son solo los tramos de carretera que publica la DGT, nunca posiciones exactas, y la app no tiene avisos en directo, ni datos de controles policiales ni avisos de usuarios. *Incidencias de tráfico y balizas V16:* al activar cualquiera de las dos (tras un aviso), la app descarga el fichero nacional de tráfico de la DGT desde `nap.dgt.es` (unos 200 KB comprimido; es un solo fichero para todo el país, sin parámetros, así que **ni tu posición ni tu ruta se envían nunca**: se filtra en el móvil). Solo se conecta al activarlo, al pulsar *Actualizar ahora* o al abrir la app con datos más antiguos que el intervalo que elijas (de 5 minutos a 1 hora); nunca en otro caso. El servidor aparece en la lista de conexiones posibles solo mientras haya un interruptor de tráfico activo, y el modo sin red lo bloquea todo. Las dos descargas se guardan en el dispositivo para que la app funcione sin red. Con estos avisos activos y la app en pantalla, la app escucha tu ubicación **en el dispositivo** para avisarte por delante; las posiciones no se guardan, no se registran y no se envían. Los avisos son informativos, pueden ser erróneos o estar desactualizados y no sustituyen tu criterio ni las normas de tráfico.
- **Al arrancar no se pide nada.** La primera conexión llega al abrir *Mapas* o al empezar una descarga.
- **El modo sin red** (en la pantalla *Mapas*) bloquea todas las conexiones propias de la app, incluso las descargas.
- **Los enlaces de mapas** (Google Maps, Apple Maps, Waze, `geo:`) se leen en el dispositivo. Los enlaces cortos (por ejemplo `maps.app.goo.gl`) exigirían una petición al proveedor, así que la app **no** los resuelve: solo te lo avisa.
- El mapa se dibuja con ficheros guardados en el dispositivo; el estilo, las fuentes y los iconos van dentro de la app.

### Permisos y por qué

| Permiso | Para qué |
|---|---|
| Ubicación (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Mostrar tu posición, usarla como salida de una ruta y, solo si activas los avisos de radares o tráfico, avisarte de lo que hay por delante. Se pide cuando hace falta; la app funciona también sin ella. |
| Internet (`INTERNET`) | Solo para descargar el catálogo y los ficheros de las regiones, y los precios de combustible, el fichero de radares y el de tráfico de la DGT si los activas. |
| Estado de red y Wi-Fi (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) | Los añade la biblioteca de mapas, para saber si el dispositivo tiene conexión. |
| Servicio en primer plano (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) | Mantiene una descarga de regiones con la pantalla apagada, con una notificación visible. |
| Notificaciones (`POST_NOTIFICATIONS`) | Muestra el progreso de la descarga (Android 13+). |
| Almacenamiento, cámara, contactos | **No se pide ningún permiso.** Importar y exportar usan el selector de archivos del sistema. |

### Datos abiertos

Los datos de mapas son © colaboradores de OpenStreetMap, bajo la licencia ODbL. La atribución está siempre visible en el mapa. El origen de cada fichero está en [`UltimateMaps-data`](https://github.com/qtekfun/UltimateMaps-data).

### Contacto

Dudas o problemas: abre una incidencia (*issue*) en el repositorio del proyecto.
