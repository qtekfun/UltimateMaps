# Verificaciones de los puntos [verificar]

Fecha: 2026-10-06. Flujo (f). Clon de CoMaps inspeccionado: `~/repos/comaps-spike`, tag `v2026.10.05-19`, solo lectura. Los submódulos de `3party/` y `tools/` NO están inicializados en el clon (shallow), así que sus licencias se toman de `data/copyright.html` y de la página del proyecto upstream, no del código.

Niveles de confianza: alta (texto de la licencia o doc oficial leído), media (fuente secundaria o inferencia directa), baja (comentario de foro o deducción), no verificado.

Puntos `[verificar]` localizados con `grep -n verificar docs/*.md`: viabilidad L3 (genérico), L35 y L45 (licencia CoMaps), L60 (Takeout); arquitectura L53 (FUSED_PROVIDER). El resto de coincidencias de «verificación» (RF-02) no son marcas.

---

## 1. Licencia del código de CoMaps y compatibilidad con GPLv3

### 1.1 ¿Es Apache-2.0? ¿Es compatible con GPLv3?
- **Respuesta:** sí a ambas. `LICENSE` es el texto de Apache 2.0; `NOTICE` dice «Licensed under the Apache License, Version 2.0» (copyright My.com B.V., Organic Maps Contributors, CoMaps Contributors) y añade que `3party` y `tools` contienen librerías de terceros con otras licencias. F-Droid también lo declara `License: Apache-2.0`. Apache-2.0 puede incorporarse a un proyecto GPLv3; GPLv3 no puede incorporarse a uno Apache. Apache-2.0 NO es compatible con GPLv2 (solo): hay que fijar «GPLv3 o posterior», nunca «GPLv2-only».
- **Fuentes:** `~/repos/comaps-spike/LICENSE:1-3`, `NOTICE:1-20`; https://www.apache.org/licenses/GPL-compatibility.html; https://www.gnu.org/licenses/license-list.html#apache2 («compatible with version 3 of the GNU GPL»); metadatos F-Droid https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/app.comaps.fdroid.yml.
- **Confianza:** alta. **Fecha:** 2026-10-06.
- **Matiz práctico:** el código CoMaps conserva sus avisos Apache (hay que mantener `NOTICE` y cabeceras); el binario resultante queda efectivamente bajo GPLv3.

### 1.2 Licencias de `3party/` y subárboles dudosos
Inventario de lo que hay en el clon (en árbol) y de los submódulos (`.gitmodules`). «copyright.html» = `data/copyright.html`, que es la lista oficial de atribuciones de CoMaps.

| Componente | Licencia | ¿GPLv3-compatible? | Evidencia | Confianza |
|---|---|---|---|---|
| CoMaps / Organic Maps / MAPS.ME | Apache-2.0 | Sí | `LICENSE`, `NOTICE` | alta |
| agg (Anti-Grain Geometry 2.4) | Permisiva propia (BSD-like, «sell and distribute … as is») | Sí (permisiva) | cabeceras `3party/agg/agg_basics.h:1-9` | alta |
| agg `agg_conv_gpc.h` | Cabecera que incluye `gpc.h` (GPC de Alan Murta, uso comercial con permiso). **`gpc.h/gpc.c` NO están en el árbol y ninguna fuente incluye la cabecera** | Solo si se usara; hoy es código muerto | `3party/agg/agg_conv_gpc.h:14-26`; búsqueda de `gpc`/`agg_conv_gpc` sin otros resultados | media (confirmar con build) |
| **bsdiff-courgette/bsdiff** | **«BSD Protection License»** (2002) | **NO** (diseñada contra «GPL-taint»; cláusula 4c exige licenciar todo trabajo que la contenga bajo la propia BPL; Fedora: «Free, but GPL-incompatible») | `3party/bsdiff-courgette/bsdiff/LICENCE` (preámbulo y cláusulas 3-4); `README.chromium`; https://fedoraproject.org/wiki/Licensing/BSD_Protection_License | alta (texto leído) |
| bsdiff-courgette/divsufsort | MIT | Sí | `.../divsufsort/LICENSE` | alta |
| libtess2 | SGI Free Software License B 2.0 | Sí a efectos prácticos (FSF: «free software license»; GPL-compat no confirmado en la entrada leída) | `3party/libtess2/LICENSE.txt`; FSF license-list #SGIFreeB | media |
| monocypher | BSD-2-Clause o CC0 (a elegir) | Sí | `3party/monocypher/LICENCE.md` | alta |
| open-location-code | Apache-2.0 | Sí | `3party/open-location-code/LICENSE` | alta |
| succinct | Apache-2.0 | Sí | `3party/succinct/LICENSE` | alta |
| ankerl (unordered_dense) | MIT | Sí | cabeceras SPDX en `3party/ankerl` | alta |
| skarupke | Boost 1.0 | Sí | cabeceras | alta |
| stb_image | MIT o dominio público | Sí | `3party/stb_image` (aviso «license information» al final) | media |
| kdtree++ | Artistic License | Sí si es Artistic 2.0 (FSF: compatible con GPL); la versión exacta no figura en el árbol | cabecera «libkdtree++ is (c) 2004-2007 Martin F. Krafft»; copyright.html | media-baja |
| minizip | Zlib-like (Gilles Vollant) | Sí | cabecera `3party/minizip` | media |
| opening_hours | MIT (Mail.Ru) | Sí | cabeceras | alta |
| GL (cabeceras Khronos) | MIT-like Khronos Materials | Sí | cabeceras `3party/GL` | media |
| vulkan_wrapper | Apache-2.0 (AOSP) | Sí | cabeceras | alta |
| robust (predicados de Shewchuk) | Sin cabecera de licencia visible en el árbol | **No verificado** (el código original de Shewchuk es de dominio público con petición de atribución, de memoria) | búsqueda sin resultados | no verificado |
| Boost | BSL-1.0 | Sí | copyright.html (submódulo vacío) | media |
| expat, glm, jansson, pugixml, glaze, imgui, fast_obj, just_gtfs, harfbuzz | MIT (harfbuzz «Old MIT») | Sí | copyright.html; https://raw.githubusercontent.com/harfbuzz/harfbuzz/main/COPYING | media-alta |
| protobuf, googletest, gflags | BSD-3 | Sí | copyright.html (protobuf); googletest/gflags de memoria | media |
| ICU | ICU/Unicode | Sí | copyright.html | media |
| FreeType | FTL (o GPLv2+, doble licencia) | Sí (usable bajo GPL) | copyright.html «FTL» | media |
| GLFW | Zlib | Sí | copyright.html | media |
| utfcpp, Vulkan-Headers | Boost / Apache-2.0 | Sí | copyright.html | media |
| tools/osmctools (osmconvert, osmfilter, osmupdate) | **AGPL-3.0** | Herramienta de generación, no se enlaza en la app; no aplica si no se redistribuye | https://github.com/organicmaps/osmctools | alta |
| tools/kothic | No verificado (submódulo vacío, página sin licencia visible) | — | `.gitmodules`; https://codeberg.org/comaps/kothic | no verificado |
| tools/python/stylesheet/webcolors | BSD-3 | Sí | `.../webcolors/LICENSE.txt` | alta |
| Android: androidx, Material, Guava, AndroidChart | Apache-2.0 | Sí | `android/app/build.gradle.kts:360-398` y copyright.html | media |
| Android: `org.microg.gms:play-services-location` 0.3.14.250932 | Apache-2.0 (cliente FOSS de microG) | Sí | `android/app/build.gradle.kts:375`, `android/gradle/libs.versions.toml:45` | media |

**Hallazgos que requieren decisión (resumen en el informe):**
1. **bsdiff (BPL) es GPL-incompatible.** Se usa en CoMaps para los parches de mapas (`mwm_diff`). Si el spike elige la opción A/C y enlaza `mwm_diff`, el binario combinado contendría código BPL + GPLv3, lo cual no se puede redistribuir. Opciones: no usar actualizaciones diferenciales (descargar región completa), reescribir el diff, o confirmar con el autor/FSF. Confianza en la incompatibilidad: alta; interpretación legal final: no verificada por un abogado.
2. **Fuente Code2000** (`data/fonts/06_code2000.ttf`, referenciada en `libs/platform/platform.cpp:209`): copyright.html la lista como «Shareware»; FreeBSD ports: «NOT free software». No es redistribuible bajo GPLv3 ni aceptable para F-Droid (anti-feature `NonFreeAssets`). Hay que excluirla o reemplazarla. No he podido comprobar si el APK de F-Droid la incluye (F-Droid usa `scandelete: 3party`, no fuentes).
3. **Icono Entypo CC BY-SA 3.0** (copyright.html): BY-SA 3.0 no es compatible con GPLv3 (solo BY-SA 4.0 lo es, y en un sentido). Bloquea solo si se copian esos iconos dentro de nuestro código GPL; se evita no usándolos o separándolos como activos con su licencia.
4. **Datos con GPLv2** en la cadena de generación: `gb-postcode-data` (GPL-2.0) en copyright.html. No afecta a la app si solo se consumen los `.mwm` ya generados, pero sí si generamos nosotros los mapas del Reino Unido.
5. Otras atribuciones que hay que reproducir: Font Awesome Free (CC BY 4.0), DejaVu (licencia Bitstream/Tavmjong), Khmer OS (LGPL), Jomolhari/Padauk (OFL), Roboto/Droid Sans/Material Icons/Remix (Apache-2.0), US Zip Codes (CC BY 4.0), Code-Point Open y FHRS (OGL v3), Wikipedia (CC BY-SA 4.0), SRTM/TIGER (dominio público), Mangrove (CC BY / BY-SA 4.0).

- **Fuentes de 1.2:** `~/repos/comaps-spike/data/copyright.html` (lista completa de licencias), los archivos citados, https://www.gnu.org/licenses/license-list.html, https://creativecommons.org/share-your-work/licensing-considerations/compatible-licenses/ (BY-SA 4.0 compatible con GPLv3 en un sentido; BY-SA 3.0 no), https://fedoraproject.org/wiki/Licensing/BSD_Protection_License.
- **Fecha:** 2026-10-06.

### 1.3 Licencia de datos y estilos
- **Datos de mapa (.mwm):** derivan de OpenStreetMap, ODbL. «You are free to copy, distribute, transmit and adapt our data, as long as you credit OpenStreetMap and its contributors»; «If you alter or build upon our data, you may distribute the result only under the same license». copyright.html de CoMaps exige «Map data © OpenStreetMap contributors, ODbL». Hay datos auxiliares con otras licencias (ver 1.2, punto 5). Ninguna licencia ODbL se mezcla con el código: los datos se descargan, no se incorporan al binario, por lo que no choca con GPLv3. Fuente: https://www.openstreetmap.org/copyright. Confianza: alta para OSM; media para el resto de fuentes auxiliares (leídas en copyright.html, no en origen).
- **Estilos y símbolos (`data/styles`, `data/symbols`, `data/search-icons`):** no tienen LICENSE propio en el árbol; quedan bajo la Apache-2.0 del repo salvo los iconos de terceros listados en copyright.html (Material Design Icons Apache-2.0, Font Awesome Free CC BY 4.0, Evericon CC0, Remixicon Apache-2.0, Entypo CC BY-SA 3.0, Nova MIT). Confianza: media (inferido por ausencia de licencia local y por copyright.html).
- **Fecha:** 2026-10-06.

---

## 2. `LocationManager.FUSED_PROVIDER` (API 31)

- **Pregunta A: ¿existe en un móvil sin GMS?**
  - **Respuesta:** puede existir, pero no está garantizado. La doc oficial dice «Added in API level 31 … Standard name of the fused location provider. **If present**, this provider may combine inputs from several other location providers…», y `hasProvider()` «Returns true if the given location provider exists on this device». AOSP incluye un servicio propio `com.android.location.fused.FusedLocationService` (paquete `packages/FusedLocation`, «Fused Location Service that LocationManagerService binds to»), por lo que un Android sin GMS suele tener un fused básico (sin red Wi-Fi/celdas si no hay proveedor de red). Un comentario de un mantenedor de GrapheneOS reporta que existe el estándar del SO pero que «Some OS ship with a broken fused location provider». Deducción: hay que llamar a `hasProvider(FUSED_PROVIDER)` y degradar a GPS/NETWORK; no suponerlo.
  - **Fuentes:** https://developer.android.com/reference/android/location/LocationManager ; https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/FusedLocation/AndroidManifest.xml ; https://github.com/eylenburg/eylenburg.github.io/issues/70 (comentarios).
  - **Confianza:** alta para la doc oficial («If present») y AOSP; baja para el estado en ROM concretas. No probado en un dispositivo sin GMS (no tengo ninguno).
- **Pregunta B: ¿en un móvil con GMS lo respalda Google Play Services?**
  - **Respuesta:** sí, medido en el Pixel 8 (Android 17, con GMS). `dumpsys location` (solo lectura) muestra `fused provider:` con `identity=10303/com.google.android.gms[fused_location_provider]` y `target service=10303/com.google.android.gms/com.google.android.location.fused.FusedLocationService`. Es decir, el proveedor del sistema es servido por GMS, no por el paquete AOSP. La entrada de CoMaps #97 también deja claro que no era obvio qué backend lo sirve; la medición lo aclara para Pixel. No generalizo a otras marcas.
  - **Fuente:** salida de `flock /tmp/claude-1000/device.lock adb shell dumpsys location` (guardada solo en el scratchpad por contener una ubicación real; no se copia aquí). https://codeberg.org/comaps/comaps/issues/97.
  - **Confianza:** alta (un dispositivo). Implicación de privacidad: en este Pixel, pedir `FUSED_PROVIDER` hace que Google Play Services procese las peticiones; no es «sin dependencia de Google», solo es sin librería de Google en la app.
- **Pregunta C: microG y GrapheneOS.**
  - **GrapheneOS:** redirige las peticiones de apps a Google Play geolocation a su propia implementación sobre el servicio de ubicación estándar: «By default, apps using Google Play geolocation are redirected to our own implementation on top of the standard OS geolocation service». La ubicación de red es opt-in. Eso afecta a apps que enlazan la librería `play-services-location`; una app que solo usa `LocationManager` usa el fused del SO. Fuente: https://grapheneos.org/usage#sandboxed-google-play-configuration. Confianza: alta para la redirección; no verificado qué devuelve exactamente `hasProvider("fused")` en GrapheneOS (sin dispositivo).
  - **microG:** un comentario en el issue de eylenburg dice que el API Fused Location de Google «is supported by Play Services, but not microG» como proveedor del sistema (y que además exige apoyo del SO). Es una afirmación de foro, sin fecha fiable. Confianza: baja. CoMaps incluye el cliente `org.microg.gms:play-services-location` (`android/app/build.gradle.kts:375`), que es un cliente que accede al servicio instalado.
- **Pregunta D (extra): ¿qué hace CoMaps?** En el árbol hay un `GoogleFusedLocationProvider` en `android/app/src/google/...` y el comentario de `build.gradle.kts` lo describe como «enabled via microG in all flavors». El issue #97 enlaza el PR #3319 «Always use fused location, remove setting and add warning» (solo el título leído). No verificado el comportamiento final.
- **Decisión que se mantiene:** `FUSED_PROVIDER` si `hasProvider`, y GPS+NETWORK como respaldo. Añadir al spike una prueba en un móvil sin GMS (no hay) o emulador AOSP sin GMS.
- **Fecha:** 2026-10-06.

---

## 3. Google Takeout: formato de listas y favoritos

Sin exportación real a mano. Todo lo que sigue procede de documentación y fuentes públicas secundarias; **hay que contrastarlo con un Takeout real antes de fijar el parser**.

- **Preguntas y respuestas:**
  - Producto «Saved» → un CSV por lista (Favoritos, Quiero ir, listas propias…); columnas: título y URL de Google Maps (el hilo de Google Help lo describe como «csv file with titles and urls»; una fuente añade columna de notas, `Title,Note,URL`, **no comprobada**). Normalmente sin latitud/longitud. Confianza: media. Fuentes: https://support.google.com/maps/thread/7226226/your-places-saved-lists-will-not-appear-on-takeout ; https://exportmymap.com/blog/google-takeout-saved-places-json-vs-csv/ («CSV rows generally have no latitude or longitude»); https://github.com/orgs/organicmaps/discussions/928.
  - Producto «Maps (your places)» → `Saved Places.json`, un GeoJSON (`FeatureCollection`) de «starred places and place reviews». Cada `feature` lleva `geometry.coordinates` (lon, lat) y `properties` con `date`, `google_maps_url` y `location` (dirección, nombre, país). Confianza: media (fragmentos de fuentes públicas: ExportMyMap, hilo de r/shortcuts y OrganicMaps #928).
- **Matices a tener en cuenta (todos de fuentes secundarias, confianza baja-media):**
  - `location` puede venir vacío y la geometría puede ser `0,0` o faltar en algunos lugares; no hay forma de confirmarlo sin una exportación real.
  - Las listas actuales («Favoritos» con estrella, «Quiero ir») pueden estar en el CSV y no en el GeoJSON; un hilo de OrganicMaps dice que «saved places are exported in CSV format only» y otro de Reddit que el Takeout de Maps «no contiene» la lista Favoritos. **Contradice parcialmente la frase del doc** («Los favoritos con estrella sí traen coordenadas en GeoJSON»): puede que solo los lugares con estrella legados estén en GeoJSON.
  - Algunas exportaciones se ofrecen también en KML.
- **No se pudo comprobar:** cabecera exacta del CSV (¿`Title,Note,URL,Comment`?), codificación, si el CSV incluye `Tags`, formato de las URLs (`maps.app.goo.gl`, `?cid=`, `/maps/place/...!1s0x...`), qué lugares traen coordenadas, localización de los nombres de lista (en español: «Favoritos», «Quiero ir»). Requiere una exportación real del usuario (RF-09).
- **Fecha:** 2026-10-06.

---

## 4. Otros

### 4.1 Política de F-Droid sobre dependencias nativas, submódulos y anti-features
- **Respuesta:** la política exige FLOSS; prohíbe librerías propietarias (Play Services, Firebase, etc.); las dependencias binarias deben compilarse desde fuente o proceder de Debian o repos autorizados; los submódulos no se mencionan en la política (pero CoMaps los usa: `submodules: true` en fdroiddata, además `scanignore: data/*.bin` y `scandelete: 3party` en builds antiguos). El compilado nativo con NDK desde fuente (Boost, Qt, etc.) es aceptado en la práctica: CoMaps está en F-Droid. Los anti-features relevantes: **TetheredNet** («Apps that depend entirely on a service which is impossible (or not easy) to replace»), **NonFreeNet** (hoy «Non-Free Network Services»: «promote or depend entirely on a proprietary network service»), **NonFreeAssets**, **NonFreeDep**, **Tracking**, **UpstreamNonFree**. Las erratas del doc: usa «sin anti-features» (RNF-04) sin concretar; CoMaps lleva **TetheredNet** («Map download service (cdn*.comaps.app)», issue #41), no NonFreeNet.
- **Fuentes:** https://f-droid.org/docs/Inclusion_Policy/ ; https://f-droid.org/docs/Anti-Features/ ; https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/app.comaps.fdroid.yml ; https://codeberg.org/comaps/comaps/issues/41.
- **Confianza:** media-alta. El texto exacto de la política sobre submódulos y blobs nativos no se leyó entero (solo un resumen por modelo); revisar en F5.

### 4.2 CDN de mapas de CoMaps: condiciones, espejos y cambio de servidor
- **Respuesta:**
  - **Cambiar servidor: sí.** Android tiene «Custom Map Server» desde diciembre de 2025 (ajustes, pantalla de descarga del mapa mundial). La URL debe ser HTTP/HTTPS con la estructura `/maps/<YYMMDD>/<Region>.mwm`. En el clon: `private.h:13-14` define `METASERVER_URL "https://cdn-us-1.comaps.app"` y siete URLs por defecto (varias de terceros: `comaps.openstreetmap.fr`, `comaps-it1.unfoxo.it`, `cloud.ru`, `firewall-gateway.de`…).
  - **Alojar espejos: permitido en la práctica y documentado, sin condiciones de uso escritas.** CoMaps documenta cómo alojar un servidor propio con herramientas comunitarias (`comaps-map-distributor`, `comaps-server`) y dice que están «mostly designed for serving files over a local network». No he encontrado unos términos del CDN ni una política de uso (ancho de banda, redistribución pública). El issue #41 menciona espejos privados y públicos como deseables. Las licencias de los datos (ODbL) permiten redistribuir con atribución.
  - **Restricción técnica importante:** «CoMaps will still reject map file downloads that do not match the checksum from the `countries.txt` file bundled inside the app» y los archivos deben ser «the officially generated by CoMaps». Es decir, nuestra app puede apuntar a un espejo, pero los `.mwm` han de ser byte a byte los oficiales y coincidir con el `countries.txt` de la app; generar mapas propios exige otro `countries.txt`. Esto es lo que habría que replicar si elegimos la opción A/C.
  - La política de privacidad de CoMaps no habla del CDN (no menciona logs de IP). No verificado qué registran los servidores.
- **Fuentes:** `~/repos/comaps-spike/private.h:13-15`; `docs/DEPLOY_OWN_MAP_SERVER.md`; https://www.comaps.app/support/how-can-i-host-a-custom-map-server-for-downloads/ ; https://www.comaps.app/support/how-can-i-set-a-custom-map-server-for-downloads/ ; https://www.comaps.app/privacy/.
- **Confianza:** alta para el mecanismo; **no verificado** si existen condiciones de uso del CDN o límites de descarga masiva (si se piensa en espejo público hay que preguntar al proyecto).
- **Fecha:** 2026-10-06.

### 4.3 Otras comprobaciones menores
- Android Auto: CoMaps usa `androidx.car.app` (Apache-2.0) en el sabor base (`build.gradle.kts:380-381`). El doc de arquitectura lo manda al sabor `gms`; podría no hacer falta. Confianza: media (la Car App Library puede requerir GMS en el dispositivo en runtime, no verificado).

---

## Propuestas de edición de los docs (no aplicadas)

1. `docs/mapas-01-viabilidad.md` L35: quitar `[verificar]` y precisar «Código Apache-2.0 (confirmado en `LICENSE`/`NOTICE`, tag v2026.10.05-19); datos OSM ODbL».
2. `docs/mapas-01-viabilidad.md` L45: reemplazar por «Apache-2.0 es compatible con GPLv3 (FSF, Apache Foundation) y no con GPLv2-only; el proyecto fija GPLv3-o-posterior. Excepciones que bloquean si se enlazan: `bsdiff` (BSD Protection License, GPL-incompatible), fuente Code2000 (shareware), iconos Entypo (CC BY-SA 3.0). Ver `docs/spike/verificaciones.md` §1.2». Y crear `LICENSES.md` con la tabla de §1.2.
3. `docs/mapas-01-viabilidad.md` L47 y `mapas-02-requisitos.md` RNF-04: citar anti-features por su nombre actual (TetheredNet, NonFreeAssets, NonFreeDep, Tracking). Añadir riesgo: CoMaps ya lleva TetheredNet por el CDN; un espejo o servidor configurable lo mitiga, no lo elimina.
4. `docs/mapas-01-viabilidad.md` L60 y `mapas-03-arquitectura.md` L100: matizar Takeout: «CSV de `Saved`: título y URL (cabecera exacta sin comprobar); `Saved Places.json` (GeoJSON, `Maps (your places)`): coordenadas pero solo lugares con estrella y reseñas; los favoritos actuales podrían estar solo en CSV. Verificar con exportación real antes de F4».
5. `docs/mapas-03-arquitectura.md` L53: cambiar a «En Android 12+ usar `FUSED_PROVIDER` solo si `hasProvider()`; con GMS el fused del sistema lo sirve Google Play Services (medido en Pixel 8); sin GMS puede existir el fused de AOSP o no, y el comportamiento en microG/GrapheneOS es de confianza baja; respaldo GPS+NETWORK». Añadir una opción de ajustes «No usar proveedor fused» por privacidad.
6. `docs/mapas-04-spike.md`: añadir un paso «comprobar enlace/uso de `mwm_diff`/bsdiff y de la fuente Code2000 en el binario del spike» y «probar ubicación en emulador sin GMS».
7. `docs/mapas-05-roadmap.md`: nuevo riesgo «licencias heredadas de CoMaps (bsdiff BPL, Code2000, Entypo)» y «espejo de mapas: sin términos de uso publicados; preguntar a CoMaps».
