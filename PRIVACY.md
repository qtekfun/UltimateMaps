# Privacy policy / Política de privacidad

*Español más abajo.*

## English

Mapas is a map and navigation app that works on your device. It has no servers of its own, no accounts, no ads, no analytics, no crash reporting and no telemetry. Search and routes are computed on the phone.

### What data goes where

- **Your location** is used only on the device, to show where you are and as the start of a route. It is never sent anywhere and it is not written to logs.
- **Saved places, lists and tracks** are stored in a local database on the device. **Backups are disabled**, so Android's cloud backup does not copy them. Files you export (GPX, KML, backups) are saved where you choose.
- **Map data** is downloaded only when you ask. The app can connect to **GitHub** to fetch the region catalog and the region files (`github.com`, `release-assets.githubusercontent.com`, `objects.githubusercontent.com`); GitHub sees your IP address and which files you download. The catalog address can be changed in the *Maps* screen. All connections use HTTPS; plain HTTP is refused.
- **Nothing is requested at start-up.** The first connection happens when you open *Maps* or start a download.
- **No-network mode** (in the *Maps* screen) blocks every connection of the app's own, even downloads.
- **Map links** (Google Maps, Apple Maps, Waze, `geo:`) are read on the device. Short links (for example `maps.app.goo.gl`) would need a request to the provider, so the app does **not** resolve them: it only tells you so.
- The map is drawn from files stored on the device; the style, fonts and icons are part of the app.

### Permissions and why

| Permission | Why |
|---|---|
| Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Show your position and use it as the start of a route. Asked when needed; the app also works without it. |
| Internet (`INTERNET`) | Only to download the region catalog and region files. |
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
- **Sitios guardados, listas y recorridos** se guardan en una base de datos local. **Las copias de seguridad están desactivadas**, así que la copia en la nube de Android no los copia. Los archivos que exportas (GPX, KML, copias) se guardan donde tú elijas.
- **Los datos de mapas** se descargan solo cuando lo pides. La app puede conectarse a **GitHub** para bajar el catálogo de regiones y sus ficheros (`github.com`, `release-assets.githubusercontent.com`, `objects.githubusercontent.com`); GitHub ve tu dirección IP y qué ficheros descargas. La dirección del catálogo se puede cambiar en la pantalla *Mapas*. Todas las conexiones usan HTTPS; el HTTP plano se rechaza.
- **Al arrancar no se pide nada.** La primera conexión llega al abrir *Mapas* o al empezar una descarga.
- **El modo sin red** (en la pantalla *Mapas*) bloquea todas las conexiones propias de la app, incluso las descargas.
- **Los enlaces de mapas** (Google Maps, Apple Maps, Waze, `geo:`) se leen en el dispositivo. Los enlaces cortos (por ejemplo `maps.app.goo.gl`) exigirían una petición al proveedor, así que la app **no** los resuelve: solo te lo avisa.
- El mapa se dibuja con ficheros guardados en el dispositivo; el estilo, las fuentes y los iconos van dentro de la app.

### Permisos y por qué

| Permiso | Para qué |
|---|---|
| Ubicación (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Mostrar tu posición y usarla como salida de una ruta. Se pide cuando hace falta; la app funciona también sin ella. |
| Internet (`INTERNET`) | Solo para descargar el catálogo y los ficheros de las regiones. |
| Estado de red y Wi-Fi (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) | Los añade la biblioteca de mapas, para saber si el dispositivo tiene conexión. |
| Servicio en primer plano (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) | Mantiene una descarga de regiones con la pantalla apagada, con una notificación visible. |
| Notificaciones (`POST_NOTIFICATIONS`) | Muestra el progreso de la descarga (Android 13+). |
| Almacenamiento, cámara, contactos | **No se pide ningún permiso.** Importar y exportar usan el selector de archivos del sistema. |

### Datos abiertos

Los datos de mapas son © colaboradores de OpenStreetMap, bajo la licencia ODbL. La atribución está siempre visible en el mapa. El origen de cada fichero está en [`UltimateMaps-data`](https://github.com/qtekfun/UltimateMaps-data).

### Contacto

Dudas o problemas: abre una incidencia (*issue*) en el repositorio del proyecto.
