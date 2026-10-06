# Prueba en el Pixel 8 (2026-10-06, con permiso explícito del usuario)

APK: `app-foss-debug.apk` de `master` (commit del visor, anterior a la integración del núcleo nativo, que **no** va en este APK). Sin sprites ni glyphs.

| Prueba | Resultado | Evidencia |
| --- | --- | --- |
| Instalar y arrancar sin mapas | Arranca sin fallos; muestra fondo, atribución OSM, botón de ubicación y panel «Aún no hay mapas». `am start -W` en frío: TotalTime 577 ms (1.ª vez, tras instalar) y 478 / 482 ms después | `01-sin-mapas.png` |
| PMTiles de Madrid (38,8 MB) copiado a `filesDir/maps` vía `run-as` | Se pinta Madrid fuera de línea, tema oscuro. Sin etiquetas ni iconos: `Failed to load sprite` (faltan sprites y glyphs, esperado) | `02-madrid-offline.png` |
| `geo:40.4530,-3.6883?z=17` | Centra el mapa y pone pin; panel «Lugar» | `03-geo.png` |
| `https://www.google.com/maps/@40.4153,-3.6844,17z` | Centra en el Retiro con pin | `04-google-maps.png` |
| `https://maps.app.goo.gl/abc123` | Aviso «necesita una petición de red… está desactivado, no se ha enviado nada». Cero red por diseño | `05-enlace-corto.png` |

Observaciones: tras el enlace corto el pin del enlace anterior sigue en el mapa (el aviso no lo limpia): fallo menor a corregir. No se midieron fps, memoria ni batería. No se probó el núcleo nativo (`init`, búsqueda, ruta): requiere compilar con el submódulo y datos `.mwm`, y no está conectado a la UI. No se probó Waze ni Apple Maps.
