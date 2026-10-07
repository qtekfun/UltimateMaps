# F7. Verificación de las fuentes de datos opcionales

Estudio previo a escribir código (rama `spike/f7-sources`). Fecha de todas las comprobaciones: **2026-10-07** (salvo que se indique otra). Todo lo marcado «verificado» se comprobó con una petición real desde esta máquina o con la ficha oficial citada; lo demás está marcado «no verificado» o «de terceros». Confianza: **alta** (comprobado en directo), **media** (fuente oficial pero sin comprobar en directo, o inferencia directa de datos), **baja** (fuente de terceros o inferencia), **no verificado**.

Los datos descargados se trataron como no confiables: ficheros en directorios nuevos del scratchpad, analizados con `python3 -I`. En el repo solo hay dos extractos pequeños en `docs/phase7/muestras/` (`combustible-extracto.json`, anonimizado; `renfe-gtfsrt-extracto.json`, con posiciones redondeadas).

Peticiones hechas a cada servidor (para constancia de que fueron mínimas): Ministerio, unas 18 (una descarga nacional más pruebas de filtros y listados pequeños); Renfe, 6 ficheros GTFS-RT + 1 GTFS estático (14 MB) + 4 consultas al catálogo; Metro Bilbao, 3 ficheros RT + 1 estático (1,6 MB); resto, una petición de sondeo por URL.

---

## 1. Precios de combustible (España, Ministerio para la Transición Ecológica)

| Pregunta | Respuesta | Fuente / comando | Confianza |
| --- | --- | --- | --- |
| URL real y vigente del servicio | `https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/` responde 200. El dominio sigue siendo `minetur` aunque el ministerio ya es MITECO. Servidor IIS, sin autenticación, `Access-Control-Allow-Origin: *`. | `curl -H 'Accept: application/json' <url>` → `HTTP 200`, 12 225 635 bytes, 1,9 s | alta |
| ¿Es el servicio que enlaza el Geoportal oficial? | El Geoportal (`geoportalgasolineras.es`) es la web oficial; su pie dice «Fuente: Datos del Ministerio para la Transición Ecológica». La ficha de datos.gob.es de «Gasolineras App» afirma que usa «el servicio REST del Geoportal». **No se ha encontrado documentación oficial del REST** (ni PDF ni página); el contrato se ha deducido de las respuestas. | Búsqueda web; `https://geoportalgasolineras.es/geoportal-instalaciones/Inicio` | media |
| Formato | JSON (`Accept: application/json`) o XML (`Accept: application/xml`, verificado en el endpoint por provincia y producto). Con la cabecera de aceptación por defecto devuelve JSON. UTF-8. | `curl -H 'Accept: application/xml' …/FiltroProvinciaProducto/28/17` → `Content-Type: application/xml` | alta |
| Estructura raíz | `{"Fecha":"07/10/2026 12:02:03","ListaEESSPrecio":[…],"Nota":"…","ResultadoConsulta":"OK"}` | análisis con `python3 -I` | alta |
| Qué significa `Fecha` | Hora **local de Madrid de la propia respuesta**, no de los datos: dos peticiones con 32 s de diferencia dieron 12:02:03 y 12:02:35. **No hay fecha por estación.** La app solo puede mostrar «descargado a las…», no «precio de las…». | `Fecha` en 3 respuestas distintas | alta |
| Frecuencia de actualización | El campo `Nota` dice: «La actualización de precios se realiza cada media hora, con los precios en vigor en ese momento». El Geoportal web dice «cada cinco minutos». Dos cifras distintas; usar 30 min como mínimo razonable. | `Nota` del JSON; texto del Geoportal | alta (Nota) / media (5 min) |
| Estaciones en el fichero nacional | **11 499** (todas con `Tipo Venta` = `P`, venta al público). 52 provincias, 3 246 municipios. Coordenadas presentes en las 11 499. | análisis | alta |
| Tamaño del fichero nacional | **12,2 MB** sin comprimir. **El servidor no comprime** (con `Accept-Encoding: gzip` no hay `Content-Encoding` y el tamaño es idéntico). Comprimido localmente con gzip serían 0,72 MB (no sirve de nada para la descarga). Sin `ETag` ni `Last-Modified`: no hay petición condicional. | `curl -D` con y sin gzip; `gzip.compress` | alta |
| Campos de cada estación | `IDEESS`, `Rótulo`, `Dirección`, `C.P.`, `Localidad`, `Municipio`, `Provincia`, `IDMunicipio`, `IDProvincia`, `IDCCAA`, `Latitud`, `Longitud (WGS84)`, `Horario`, `Margen` (D/I/N: lado de la vía), `Remisión` (`dm`/`OM`), `Tipo Venta`, `% BioEtanol`, `% Éster metílico` y 23 campos `Precio …` (abajo). Todo son **cadenas**; `""` = no vende. | análisis (claves y recuento) | alta |
| Formato de números | Coma decimal en precios (`"1,849"`, € por litro o kg) y en coordenadas (`"39,211417"`, `"-1,539167"`). Las 11 499 coordenadas y todos los precios no vacíos cumplen `\d+,\d+`. Los nombres de campo llevan espacios, tildes y puntos. | análisis (0 precios con formato raro) | alta |
| Horario | Texto libre, no estructurado: `L-D: 24H` (5 212 estaciones), `L-D: 06:00-22:00`, `L: 06:00-00:00` … Hay que mostrarlo tal cual; interpretarlo exige parser propio. | análisis | alta |
| Unidad del identificador | `IDEESS` (cadena numérica, p. ej. `"4375"`) identifica la estación; no se comprobó su estabilidad en el tiempo. | muestra | media |

### Nombres exactos de los campos de precio y estaciones que lo tienen (11 499 en total)

| Campo JSON | Estaciones con precio | Campo JSON | Estaciones con precio |
| --- | ---: | --- | ---: |
| `Precio Gasolina 95 E5` | 10 923 | `Precio Gasoleo A` | 11 286 |
| `Precio Gasolina 95 E5 Premium` | 1 119 | `Precio Gasoleo Premium` | 5 915 |
| `Precio Gasolina 95 E10` | 28 | `Precio Gasoleo B` | 2 276 |
| `Precio Gasolina 95 E25` | 1 | `Precio Diésel Renovable` | 1 618 |
| `Precio Gasolina 95 E85` | 2 | `Precio Biodiesel` | 29 |
| `Precio Gasolina 98 E5` | 5 504 | `Precio Adblue` | 2 956 |
| `Precio Gasolina 98 E10` | 17 | `Precio Hidrogeno` | **2** |
| `Precio Gasolina Renovable` | 31 | `Precio Gases licuados del petróleo` (**GLP**) | **999** |
| `Precio Bioetanol` | 1 | `Precio Gas Natural Comprimido` (**GNC**) | **134** |
| `Precio Metanol` | 0 | `Precio Gas Natural Licuado` (**GNL**) | **94** |
| `Precio Amoniaco` | 0 | `Precio Biogas Natural Comprimido` / `…Licuado` | 42 / 50 |

Observaciones: «gasolina 95» es en realidad `Gasolina 95 E5` (E10 casi no existe); el diésel habitual es `Gasoleo A` **sin tilde** pero el renovable es `Diésel Renovable` **con tilde**; el hidrógeno es `Hidrogeno` sin tilde. Los nombres no son estables por contrato (no hay documentación), así que el parser debe tolerar campos nuevos o ausentes y mapear por una tabla propia (`IDProducto` del listado de abajo ↔ campo). **La afirmación del roadmap «GLP (999 estaciones) es una fuente útil» se confirma: 999 estaciones con GLP (8,7 %).** Media de 3,7 precios por estación.

### Servicio por provincia, municipio y producto (¿descarga entera o consulta?)

Con trailing slash `/…/28/` devuelve 404; **sin barra final funciona**. Rutas verificadas (todas 200, JSON):

| Ruta (tras `/PreciosCarburantes/`) | Resultado verificado | Tamaño |
| --- | --- | ---: |
| `EstacionesTerrestres/` | España entera | 12,2 MB |
| `EstacionesTerrestres/FiltroProvincia/28` | Madrid (IDProvincia 28) | 946 KB |
| `EstacionesTerrestres/FiltroCCAA/13` | Comunidad de Madrid (coincide con la provincia) | 946 KB |
| `EstacionesTerrestres/FiltroMunicipio/4604` | respuesta vacía (`ListaEESSPrecio: []`); `4604` era un ID inventado por mí, **el formato del filtro de municipio no se ha probado con un ID válido** | 254 B |
| `EstacionesTerrestres/FiltroProducto/17` (GLP) | **999 estaciones**; cada una trae un único campo `PrecioProducto` (en vez de los 23 `Precio …`) | 377 KB |
| `EstacionesTerrestres/FiltroProvinciaProducto/28/17` | GLP en Madrid | 50 KB |
| `Listados/ProductosPetroliferos/` | catálogo con `IDProducto` y `NombreProducto` | pequeño |
| `Listados/MunicipiosPorProvincia/28` | municipios de Madrid | 22 KB |
| `Listados/Provincias/` (con barra; sin ella da 307) | provincias | pequeño |

IDs de producto verificados: 1 G95E5, 23 G95E10, 24 G95E25, 25 G95E85, 20 G95E5+, 3 G98E5, 21 G98E10, 4 Gasóleo A, 5 Gasóleo Premium, 6 Gasóleo B, 7 Gasóleo C, 16 Bioetanol, 8 Biodiésel, **17 GLP**, **18 GNC**, **19 GNL**, **22 Hidrógeno**, 26 Adblue, 27 Diésel renovable, 28 Gasolina renovable, 29 Metanol, 30 Amoniaco, 31 Biogás GNC, 32 Biogás GNL.

**Consecuencia para la privacidad (decisión de diseño):** filtrar por provincia o municipio revela al servidor dónde está el usuario aproximadamente; eso choca con «la ubicación nunca sale del dispositivo». El filtro **por producto** (`FiltroProducto/{id}`) no revela ubicación, solo qué combustible interesa, y baja mucho: GLP 377 KB, frente a 12,2 MB. Recomendación: **consulta por producto** para combustibles minoritarios (GLP/GNC/GNL/hidrógeno: < 0,4 MB) y **descarga nacional entera** para gasolina/diésel (el filtro por producto de gasolina 95 E5 o gasóleo A baja casi toda la base, estimación ≈ 3-4 MB frente a 12,2; **no medido**). En ambos casos el filtrado por radio se hace en el dispositivo.

### Espacio en el dispositivo (calculado con el fichero real)

| Representación | Tamaño | Notas |
| --- | ---: | --- |
| Fichero nacional tal cual (JSON) | 12,2 MB | no guardarlo |
| Todas las estaciones, solo campos útiles (id, rótulo, dirección, localidad, coordenadas, horario + 23 precios) en JSON compacto | 2,58 MB (0,47 MB con gzip) | calculado |
| Binario estimado sin dirección (id, coordenadas, rótulo y horario indexados, 23 precios de 2 bytes) | ≈ 0,7 MB | **estimación** |
| Solo un combustible (p. ej. GLP, 999 estaciones) | ≈ 0,1-0,2 MB | **estimación** a partir del tamaño de `FiltroProducto/17` (377 KB con todos los campos de texto) |
| Solo las estaciones dentro de un radio de 25-50 km | decenas de KB | **estimación** (Madrid provincia entera = 946 KB) |

Conclusión de tamaño: guardar el fichero ya reducido a las estaciones y precios útiles cuesta **menos de 3 MB** sin ningún formato especial; la descarga de 12,2 MB cada 30 min o cada hora es lo caro (datos móviles). Por eso el ajuste de frecuencia debe tener por defecto «manual / diaria / solo con wifi».

### Límites de uso, cabeceras y licencia

| Pregunta | Respuesta | Fuente | Confianza |
| --- | --- | --- | --- |
| Límites de uso | **Ninguno publicado ni observado**: sin cabeceras `RateLimit`, sin `Retry-After`, sin clave, 18 peticiones sin incidencias. Cabeceras relevantes: `Cache-Control: private`, `Access-Control-Allow-Origin: *`. Que no se vea un límite no es una garantía; ser educados (≤ 1 descarga entera por hora y caché local) | `curl -D` | alta (observado) / no verificado (política) |
| Licencia / condiciones de reutilización | **No verificado.** No se ha localizado ninguna página oficial con las condiciones del servicio REST. (a) La ficha de datos.gob.es que citaban las búsquedas (`e05068001-precio-de-carburantes-en-las-gasolineras-espanolas` y `e0dat0002-geoportal-gasolineras`) **ya no existe**: página 404 y API `apidata` con `items: []`. (b) Una ficha de terceros (Apify, `lafabbricallc/spain-fuel-station-prices`) afirma, sin enlace oficial, reutilización bajo la Ley 37/2007 y atribución «Ministerio de Industria, Comercio y Turismo - Precios de carburantes en estaciones de servicio». Eso **no es una fuente fiable** y el nombre del ministerio ya no coincide. (c) No se ha leído ninguna licencia de reutilización ni norma que la fije. (d) El Geoportal dice: «La información publicada en este sitio web tiene únicamente carácter informativo y no constituye una comunicación de actos administrativos ni comunicados oficiales». | datos.gob.es (404 / API vacía), Geoportal, Apify | no verificado |
| Atribución recomendada hasta aclararlo | «Datos del Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Gasolineras)», con la fecha de descarga y el aviso de que es informativo. Antes de publicar la función conviene preguntar al Ministerio por la vía oficial (sede electrónica de MITECO) o localizar la política de reutilización; no se ha comprobado qué canal de contacto existe. | — | recomendación |

**Qué bloquea:** nada técnico. Queda pendiente aclarar la licencia por escrito antes de publicar la versión con F7 (riesgo legal bajo, porque los datos son de publicación obligatoria y de uso público, pero hoy no está verificado).

---

## 2. Cercanías (Renfe)

| Pregunta | Respuesta | Fuente / comando | Confianza |
| --- | --- | --- | --- |
| ¿Hay tiempo real público? | **Sí**, tres feeds GTFS-Realtime de Cercanías: trip updates, vehicle positions y alerts. | `https://data.renfe.com/api/3/action/package_search?q=gtfs` | alta |
| URLs | `https://gtfsrt.renfe.com/trip_updates.pb` · `…/vehicle_positions.pb` · `…/alerts.pb`; las tres también en `.json` (mismo nombre). Tiempo real de larga distancia aparte: `trip_updates_LD.pb/.json` (no probado: fuera de F7). | CKAN de data.renfe.com + `curl` (200 en las 6) | alta |
| Formato | **Protobuf (GTFS-RT 2.0)** y **JSON** equivalente (`gtfsRealtimeVersion: "2.0"`; los números salen como cadenas). El JSON evita depender de una librería protobuf pero pesa ≈ 5× más (trip updates: 19,9 KB `.pb` frente a 111,8 KB `.json`). | `curl`, cabecera del JSON | alta |
| ¿Clave o registro? | **No.** Descarga anónima por HTTPS, sin cookie ni cabecera. | `curl` sin credenciales → 200 | alta |
| Límites de uso | No publicados ni observados. `Cache-Control: public, max-age=30`; `ETag` y `Last-Modified` presentes (se pueden usar peticiones condicionales). | cabeceras | alta (cabeceras) / no verificado (política) |
| Frecuencia de refresco | La ficha dice «Esta información se actualiza **cada 20 segundos**» (trip updates y alerts) y la caché del servidor es de 30 s. `Last-Modified` de trip updates y vehículos coincidía con el instante de la petición; el de alertas era de 2,5 h antes (las alertas cambian solo cuando hay avisos). | CKAN + cabeceras | alta |
| Licencia | **Creative Commons Attribution 4.0** (CC BY 4.0) en las fichas de los tres feeds, del estático y del listado de estaciones. Exige atribución a Renfe; el texto exacto de atribución **no está especificado** en la ficha. | `license_title`/`license_url` del CKAN | alta |
| Qué contiene cada feed | **Trip updates**: 285 viajes (en ese momento), campos `tripId`, `stopTimeUpdate[].{stopId, arrival.time, arrival.delay}`, `delay` por viaje. **Pero solo 235 de los 285 traen 1 sola parada** (la siguiente), 43 no traen ninguna y solo 6 traen 2 o más. No es un tablero completo de salidas por estación. **Vehicle positions**: 256 trenes con `latitude/longitude`, `currentStatus`, `stopId`, `timestamp` y `label` del tipo `C5-23717-PLATF.(5)` (la **vía/andén** va en el `label`, lo dice la ficha). **Alerts**: 69 avisos con texto en español (`descriptionText`), `activePeriod` y `informedEntity.routeId`. | análisis de los JSON | alta |
| Cómo se identifica una estación | Por el `stop_id` del GTFS (cadena de 5 dígitos con cero a la izquierda, p. ej. `"04040"` = Zaragoza Delicias). Los 220 `stopId` de trip updates y los 193 de posiciones **existen todos** en `stops.txt` del GTFS estático (1 141 paradas, con nombre y coordenadas). Renfe publica además `estaciones.csv` (listado completo de estaciones de Renfe, CC BY 4.0). | cruce de los JSON con `stops.txt` | alta |
| GTFS estático | `https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip`: **14,1 MB** comprimido; `stop_times.txt` ocupa **243 MB** descomprimido, `trips.txt` 17 MB (115 051 viajes), `shapes.txt` 4 MB; 828 rutas. Actualizado a diario (`Last-Modified` 02:08 UTC del mismo día). Los campos vienen **rellenados con espacios** (hay que recortarlos): sin recortar, solo 217 de los 285 `tripId` de tiempo real coincidían con el estático; recortando, **coinciden los 285**. | `curl -I`, `zipfile`, cruce | alta |
| Núcleos cubiertos | En los 285 viajes del feed hay rutas de **15 prefijos de núcleo** (`10`, `20`, `30`, `31`, `32`, `40`, `41`, `45`, `46`, `47`, `51`, `60`, `61`, `62`, `70`; el `route_id` empieza por ese código y el nombre corto es C1…, R1…). Solo confirmé por nombre de línea (`C1 Príncipe Pío-Aeropuerto T4`, `R1…`) que `10` es Madrid y `51` Rodalies de Barcelona; **la correspondencia de los demás prefijos con ciudades no está verificada** (probablemente Asturias, Sevilla, Cádiz, Málaga, Valencia, Murcia/Alicante, Cantabria, Zaragoza…). Etiquetas de tren en el feed: líneas C1-C10, R1-R17, T1, RL4, etc. Los núcleos de FEVE/vía estrecha y Rodalies de Cataluña aparecen mezclados en un único feed. | análisis de rutas y etiquetas | media |
| Tamaño de cada petición | 20-110 KB; un refresco de las 3 alertas+viajes+posiciones en `.pb` ≈ 85 KB. | `curl` | alta |

**Viabilidad para «próximos trenes de una estación»:** hacen falta el tiempo real (retrasos) **y** el GTFS estático (horas programadas por parada), porque el feed en vivo casi solo trae la siguiente parada de cada tren. El estático pesa 14 MB comprimido y 243 MB de `stop_times` descomprimido: **no se puede indexar entero en el móvil sin trabajo** (hay que leerlo en streaming, quedarse solo con las paradas del núcleo elegido y guardarlo en un índice compacto, con la descarga manual o semanal). Alternativa más barata (menos funcional): mostrar solo vehículos que vienen hacia la estación (`stopId` de posiciones/siguiente parada) con su retraso y las alertas del núcleo; sin horario programado. Ambas opciones evitan enviar la ubicación (se descarga el feed entero, igual para todos).

**Qué bloquea:** nada de clave. Decisión de producto pendiente: tablero completo (más trabajo, estático 14 MB) o «trenes que se acercan + avisos» (mucho más simple).

---

## 3. Metro y otros operadores

Distinción: **estático** = horarios/paradas (GTFS); **tiempo real** = predicciones de llegada (GTFS-RT u otra API).

| Operador | Estático | Tiempo real | ¿Clave / registro? | Condiciones | Verificado con | Confianza |
| --- | --- | --- | --- | --- | --- | --- |
| **Metro Bilbao** (CTB, Consorcio de Transportes de Bizkaia) | GTFS abierto `https://ctb-gtfs.s3.eu-south-2.amazonaws.com/metrobilbao.zip`: 1,67 MB, actualizado 01:00 UTC del mismo día; incluye `stop_times.txt` (5,5 MB descomprimido) | **Sí, GTFS-RT sin clave**: `https://ctb-gtfs-rt.s3.eu-south-2.amazonaws.com/metro-bilbao-trip-updates.pb`, `…-vehicle-positions.pb`, `…-service-alerts.pb`. En la prueba: trip updates 15 KB, 73 viajes y 660 `stopTimeUpdate` (**secuencia completa de paradas por tren**, con hora de llegada; 41 paradas distintas), vehículos 1 KB, alertas vacías (0 entidades, 15 bytes) | **No** (HTTP 200 anónimo, alojado en S3). Los `stop_id` del RT (`"10.0"`) coinciden con los del estático (`1.0`, `34.0`…) | **CC BY 4.0**; condiciones generales (art. 8 Ley 37/2007): no alterar el contenido, no distorsionar el sentido, **citar la fuente y la fecha de la última actualización** | `curl` + decodificación de protobuf a mano; `https://data.ctb.eus/en/pages/legal-notice` | alta |
| **Metro de Madrid** (CRTM) | GTFS abierto «GTFS Red de Metro» (ficha en `datos.crtm.es`; ítem ArcGIS `5c7f2951962540d69ffe8f640d94c246`, última modificación 2025-05-30; el catálogo Mobility Database apunta a `…/items/357e63c2904f43aeb5d8a267a64346d8/data` y a `…/885399f83408473c8d815e40c5e702b7/data`); **descarga anónima, sin clave** (auth=0 en el catálogo; no descargada por mí) | **No hay GTFS-RT público de Metro de Madrid** (no consta en el catálogo de Mobility Database ni en los datos abiertos del CRTM). La app oficial usa un servicio de teleindicadores no documentado: `https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/<código>` — de terceros se cita como «sin clave»; **probado ahora: responde, no pide credenciales, pero devuelve error 400 con una traza de Node (`RangeError: Maximum call stack size exceeded`)** para los códigos de estación que probé (`par_4_156`, `par_4_1`). Sin documentación ni condiciones publicadas | Estático: **no**. Tiempo real: ningún registro oficial (no hay portal de desarrolladores de Metro de Madrid que haya encontrado) | **Licencia CRTM de datos estáticos** (`https://www.crtm.es/licencia-de-uso`, leída entera): uso comercial y no comercial permitido; hay que **citar «Powered by CRTM» con enlace a crtm.es**, indicar si son datos en bruto o explotados, mantener los metadatos de fecha, no dar a entender patrocinio, garantizar que la información mostrada esté actualizada; **CRTM monitoriza accesos y puede bloquear al reutilizador por uso abusivo**; compartir los datos copiados **bajo el mismo tipo de licencia**. No cubre el servicio de teleindicadores (no es dato estático de la web del CRTM) | página de licencia; ArcGIS; `curl` al endpoint | licencia y estático: alta; tiempo real oficial: **no existe público**; endpoint no oficial: **no verificado** (no funcionó) |
| **EMT Madrid** (bus; no es metro, para contexto del CRTM) | GTFS abierto `https://servicios.emtmadrid.es:8443/gtfs/transitemt.zip` (auth=0 según el catálogo; no descargado) | `https://openapi.emtmadrid.es/v1/bus/servicealerts/proto`: **200 anónimo** (47 KB, alertas de servicio, no incluye las llegadas). Las llegadas por parada de la API de MobilityLabs **exigen registro y `X-ClientId`/`passKey`** (documentación EMT: «Mandatory register your application…») | Sí para llegadas (registro + clave) | MobilityLabs; `datos.emtmadrid.es` | `curl` + `apidocs.emtmadrid.es` | alta (alertas sin clave; llegadas con clave) |
| **TMB (Metro y bus de Barcelona)** | GTFS vía `https://api.tmb.cat/v1/static/datasets/gtfs.zip?app_id=…&app_key=…`: **requiere `app_id` y `app_key`** (registro en `developer.tmb.cat`) | `https://api.tmb.cat/v1/ibus/…` y `/v1/transit/…`: **401 «Authentication failed. Authentication parameters missing»** sin clave | **Sí, ambas** | Condiciones del portal de desarrolladores no leídas: **no verificado** | `curl` (401) + catálogo Mobility Database | alta (necesita clave) |
| **Metro de Valencia (FGV)** | GTFS publicado en el **NAP** (`nap.transportes.gob.es`, fichero 1168): el catálogo indica que exige **clave en cabecera HTTP** (`auth=2`) | **No hay GTFS-RT de FGV en el catálogo** | Sí (NAP) | Licencia NAP: `nap.transportes.gob.es/licencia-datos`, **no leída** | Catálogo Mobility Database (`feeds_v2.csv`); sin comprobar en directo | media |
| **Metro de Sevilla** | GTFS en el NAP (fichero 1583), con clave en cabecera | No hay GTFS-RT en el catálogo | Sí (NAP) | NAP, no leída | catálogo, sin comprobar en directo | media |
| **Euskotren, Metro de Madrid Ligero** (otros) | Euskotren en NAP (clave); Metro Ligero en CRTM abierto | no hay RT público en el catálogo | Euskotren: sí; Metro Ligero: no | respectivas | catálogo | baja |

Notas: (1) **El Mobility Database no es una fuente primaria**; se usó para descubrir URLs y se verificó en directo todo lo que se cita como «comprobado» (Renfe, Bilbao, EMT, TMB). (2) El NAP (punto de acceso nacional de datos de transporte del Ministerio de Transportes) pide registro y clave de API; para F7 sería «clave que pone el usuario» y no «embebida». (3) **No hay ninguna fuente de metro con tiempo real público, oficial, sin clave y probada excepto Metro Bilbao.**

---

## 4. Conclusión

### ¿Es viable cada fuente con las reglas del proyecto?

Reglas: sin clave embebida, sin enviar la ubicación del usuario, descarga entera o por estación.

| Fuente | ¿Sin clave? | ¿Sin enviar ubicación? | ¿Descarga entera / por estación? | Veredicto |
| --- | --- | --- | --- | --- |
| Combustible (Ministerio) | Sí | Sí si se usa `EstacionesTerrestres/` (entero) o `FiltroProducto/{id}`; **no** `FiltroProvincia`/`FiltroMunicipio`, que filtran por zona | Entero (12,2 MB) o por producto (GLP 377 KB) | **Viable.** Pendiente: licencia por escrito |
| Cercanías Renfe | Sí | Sí: feed único igual para todos | Feed entero (20-110 KB cada uno, refresco cada 30 s) + GTFS estático (14 MB) | **Viable.** Tablero completo exige indexar el estático; versión reducida (trenes que se acercan + avisos) es simple |
| Metro Bilbao | Sí | Sí | Feed entero (15 KB) + estático 1,7 MB | **Viable, la más limpia.** Solo cubre Bilbao |
| Metro de Madrid | Estático sí; tiempo real: no hay oficial | Sí | — | **Tiempo real no viable** hoy (el endpoint no oficial falla y no tiene condiciones). Solo horarios (GTFS estático) si se quiere |
| TMB (Barcelona) | **No: clave y registro** | — | — | **Solo con clave puesta por el usuario**; no se ofrece por defecto |
| Metro Valencia / Sevilla | **No: clave del NAP** | — | — | Solo estático y con clave del usuario; sin tiempo real |

### Orden de implementación recomendado

1. **Combustible** (`:source-fuel`): máximo valor, formato y servicio verificados, ningún riesgo de clave. Empezar con el combustible elegido por producto y el filtrado local por radio.
2. **Módulo genérico GTFS + GTFS-RT** (`:source-transit-gtfs`) con **Metro Bilbao** como primer operador (estático pequeño, tiempo real completo, licencia clara): sirve para validar el motor y la interfaz.
3. **Cercanías Renfe** sobre el mismo módulo (configuración propia: URLs, JSON o protobuf, recorte de espacios del estático, índice por núcleo).
4. Resto (TMB, Metro de Valencia/Sevilla) **solo si el usuario aporta su clave**; Metro de Madrid en tiempo real: descartar o revisar si aparece una API oficial.

### Esquema mínimo de datos para `OptionalDataSource`

Lo estrictamente necesario según lo verificado, por encima de lo que ya dice la arquitectura:

```
SourceDescriptor
  id                 // "fuel-es", "transit-renfe", "transit-metro-bilbao"
  displayName
  hosts[]            // para NetworkPolicy: sedeaplicaciones.minetur.gob.es, gtfsrt.renfe.com, ssl.renfe.com, ctb-gtfs-rt.s3.eu-south-2.amazonaws.com, ctb-gtfs.s3.eu-south-2.amazonaws.com
  whatIsSent         // texto del aviso: "se pide el fichero completo; el servidor ve su IP; no se envía su ubicación"
  attribution        // texto de la licencia ("Renfe, CC BY 4.0", "CTB, CC BY 4.0", "Ministerio ...")
  licenseUrl
  requiresUserKey    // false por defecto; si true, la clave la pone el usuario (Keystore)
  refresh: { minIntervalSec, defaultIntervalSec, wifiOnlyDefault }
  settingsFields[]   // dibujado por la pantalla de Ajustes: toggle, fuelProducts[], radiusKm, operators[], baseUrl

FetchResult<T>
  data               // modelo común: Station(id, name, lat, lon, kind), Offer(stationId, product, price, unit)
                     // o Departure(stationId, line, headsign, scheduledEpoch, estimatedEpoch, platform?)
  fetchedAtEpoch     // cuándo se descargó (es lo único que se sabe del Ministerio)
  sourceTimestampEpoch? // GTFS-RT header.timestamp; null para combustible
  validUntilEpoch    // para la caché con caducidad
  error?             // fallo aislado; la fuente muestra el último dato bueno

Capacidades por fuente:
  fetchAll(): FetchResult<List<Station+Offer>>   // combustible
  fetchForStation(stationId): FetchResult<List<Departure>>  // transporte (sobre el feed entero)
```

Reglas que salen de lo verificado: (a) recortar espacios de los campos de los GTFS de Renfe; (b) convertir coma decimal en el parser de combustible; (c) tabla propia de producto ↔ campo JSON (los nombres son irregulares); (d) en transporte, no reintentar antes de 30 s (`max-age=30`); (e) mostrar atribución y fecha de datos en la UI (lo exigen CC BY 4.0 y la licencia CRTM); (f) dependencia de protobuf: Renfe ofrece JSON (sin librería), Bilbao solo protobuf; elegir `protobuf-javalite` o escribir un lector mínimo del esquema GTFS-RT; **la licencia de la dependencia no se ha comprobado** (revisar según R8 en `LICENSES.md`).

### Estimación de trabajo (son estimaciones, no medidas)

| Bloque | Días |
| --- | ---: |
| `:core-settings` + `:core-optional` (interfaz, registro, caché, NetworkPolicy, pantalla de Ajustes dibujada por campos) | 5-7 |
| `:source-fuel`: descarga, parser, filtrado por radio, capa en mapa y ficha, ajustes de combustible/radio/frecuencia | 6-9 |
| `:source-transit-gtfs` + Metro Bilbao (parser GTFS-RT, índice estático, UI de estación) | 6-8 |
| Renfe Cercanías encima del módulo (con tablero completo e índice por núcleo desde el estático) | 5-8 |
| Renfe Cercanías en versión reducida (trenes que se acercan + avisos) | 2-3 (en lugar de la línea anterior) |
| Operador con clave del usuario (TMB, por ejemplo) | 3-4 cada uno |
| `PRIVACY.md`, lista de conexiones, textos de aviso, pruebas | 2-3 |

Total orientativo para fuel + Bilbao + Renfe con tablero completo y la infraestructura de Ajustes: **≈ 24-35 días** de trabajo; solo combustible: ≈ 11-16 días con la infraestructura mínima.

### Lo desmentido o corregido respecto al roadmap

- «Descargar el fichero nacional y filtrar en local»: **correcto, pero el servidor no comprime** (12,2 MB reales por descarga) y existe `FiltroProducto/{id}` (GLP 377 KB) que mejora el caso de combustibles minoritarios sin enviar ubicación.
- «Una foto diaria, no tiempo real» (roadmap, sobre combustible): la fuente dice que se actualiza **cada media hora**; sigue sin ser tiempo real garantizado y no hay fecha por estación.
- «Datos abiertos de Renfe (horarios y tiempo real en formato GTFS)»: **correcto** y **sin clave** (CC BY 4.0), pero el tiempo real de Renfe no basta para un tablero de salidas por estación sin el GTFS estático de 14 MB.
- «Metro (Madrid y otros): empezar por el que tenga datos abiertos sin clave»: **el que sí lo tiene es Metro Bilbao**, no Metro de Madrid (que no publica tiempo real abierto).
- Licencia del combustible: **no verificada** (la ficha de datos.gob.es ya no existe).

### Pendiente / no verificado

1. Licencia y atribución exactas del servicio REST de combustible (preguntar al Ministerio).
2. Que el filtro por municipio funcione (probado solo con un ID inválido); estabilidad de `IDEESS`; tamaño real de `FiltroProducto/1` y `/4`.
3. Correspondencia de los prefijos de núcleo de Renfe con ciudades; texto exacto de atribución de Renfe.
4. Límites de uso no publicados en ninguna de las fuentes.
5. Metro de Madrid en tiempo real: ninguna fuente oficial; el endpoint de la app (no documentado) devolvió error.
6. Condiciones de TMB, NAP, FGV y Metro de Sevilla (no leídas); GTFS del CRTM no descargado.
7. Licencia de la librería protobuf elegida.
