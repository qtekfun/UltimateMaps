# 05 · Roadmap y riesgos

Las duraciones son estimaciones orientativas a tiempo parcial y se revisan al terminar el spike. Dependen sobre todo de las horas semanales disponibles y de la opción elegida (A, B o C).

## Fases

| Fase | Contenido | Estimación | Hecho cuando… |
| --- | --- | --- | --- |
| **F0. Spike** | `mapas-04-spike.md` y CI mínimo en GitHub (base del merge automático) | 1-2 semanas | Hay informe con recomendación A, B o C y los checks de PR funcionan. **Estado 2026-10-06:** informe hecho (`docs/spike-informe.md`, recomendación provisional C); CI y remoto pendientes del usuario |
| **F1. Base** | Visor, gestor de regiones, búsqueda, abrir enlaces, sitios y listas locales, GPX/KML | 2-3 meses (A) / 3-4 meses (C, revisado tras el spike) | Se puede usar como visor y buscador offline en el día a día |
| **F2. Navegación** | Routing coche/moto/bici/a pie, giro a giro con voz, carriles, límites de velocidad, recálculo, **pantalla de Ajustes** (voz, unidades, privacidad; con secciones reservadas para F7) | 2-4 meses | Un viaje real de coche completado sin tocar el móvil |
| **F3. Moto** | Evitar autopistas y peajes, rutas con curvas, modo guantes, grabación y exportación de tracks | 1-2 meses | Una salida real en moto con la pantalla siempre visible |
| **F4. Sync e importación** | Nextcloud/WebDAV, importación de Google Takeout | 1 mes | Dos dispositivos convergen; Takeout real importado |
| **F5. Pulido y publicación** | Rendimiento, estilo, accesibilidad, ROM chinas, metadatos de F-Droid, builds reproducibles | 1-2 meses | Publicado en F-Droid y GitHub |
| **F6. Android Auto** | Investigación y, si procede, implementación | A estimar | Decidir tras F5 |
| **F7. Datos opcionales** | Precios de combustible por tipo (GLP, gasolina, diésel…) y transporte público en tiempo real (Cercanías, Metro…), **cada fuente activable y configurable en Ajustes**, desactivada por defecto | Estimación del estudio: ~24-35 días con tablero completo de Cercanías (sin medir) | Cada fuente se puede activar, configurar y apagar en Ajustes; con todas apagadas no hay ninguna conexión nueva; los datos se ven en el mapa o en la ficha |

Total orientativo hasta F5: 6-12 meses. Cada fase termina con una versión utilizable.

## Registro de riesgos

| # | Riesgo | Probabilidad | Impacto | Mitigación |
| --- | --- | --- | --- | --- |
| R1 | El motor de CoMaps no alcanza el estilo o los fps objetivo | Media | Alto | Spike; opción C |
| R2 | Formato de datos de CoMaps cambia y rompe versiones | Media | Medio | Fijar versión de datos y motor; espejo propio de las regiones usadas |
| R3 | Coste de generar y alojar datos mundiales (si se elige B) | Alta en B | Alto | Evitar B salvo necesidad; empezar por España |
| R4 | Fiabilidad en segundo plano en ROM chinas | Alta | Alto | Servicio en primer plano, guía de batería, pruebas en dispositivos reales |
| R5 | Sin motor TTS en dispositivos sin GMS | Alta | Medio | Detección, guía de instalación y voz propia opcional |
| R6 | Rutas con curvas no soportadas por el motor | Alta | Medio | Puntuación de sinuosidad sobre alternativas; o paso obligado por vías |
| R7 | Importación de Takeout sin coordenadas | Alta | Bajo | Resolver por enlace o por búsqueda local |
| R8 | Incompatibilidad de licencias en dependencias | Baja | Alto | `LICENSES.md` y revisión al añadir cada dependencia |
| R9 | Requisitos de F-Droid (dependencias, builds) | Media | Medio | Sabor `foss` único; revisar su política en F5 |
| R10 | Alcance excesivo para un desarrollador | Alta | Alto | Fases con versión utilizable cada una; decidir A/C para reutilizar |
| R18 | Las APIs de F7 cambian, piden clave o limitan el uso (son de terceros) | Media | Medio | Spike previo; una interfaz por fuente; que el fallo de una no afecte al resto; clave opcional puesta por el usuario |
| R19 | Alcance: F7 amplía lo que «Qué no hacemos» excluía (transporte público) | Media | Medio | F7 solo después de F5; cada fuente es opcional y se puede retirar sin tocar el núcleo |
| R11 | Licencias heredadas de CoMaps: bsdiff (BSD Protection), fuente code2000 (shareware), iconos Entypo (CC BY-SA 3.0) | Alta si no se atiende | Alto | Excluir o reemplazar antes de publicar; fijar GPLv3+ |
| R12 | Ruta larga (≈ 18 s vs 2 s) y búsqueda (≈ 0,6 s vs 0,1 s) del núcleo de CoMaps fuera de umbral | Alta | Alto | Repetir con subconjunto de regiones; relajar umbral de rutas largas; o B con Valhalla |
| R13 | `countries.txt` firmado (Ed25519) y SHA-1 por región: espejo propio exige recompilar con nuestra clave; RF-02 pide SHA-256 | Media | Medio | Valorar al elegir motor |
| R14 | Sabor fdroid de CoMaps depende de microG `play-services-location` | Alta si se reutiliza su `:app` | Medio | Consumir solo `:sdk` |
| R15 | Android 17: sin `adb push` a `Android/data`; MapLibre nativo no lee `file://` allí | Media | Bajo | Datos en `filesDir` o descarga por la app |
| R16 | Un solo dispositivo de prueba (gama alta con GMS) | Cierta | Alto | Conseguir gama media, ROM china sin GMS y de-Googled |
| R17 | Volumen de datos de C (≈ 5,3 GB para España) | Media | Medio | Descarga por regiones; valorar estilo con datos .mwm |

## F7. Datos opcionales: detalle

Decidido por el usuario el 2026-10-07: entran en el roadmap, **configurables en Ajustes**. Detalle de diseño en `mapas-03-arquitectura.md` («Fuentes de datos opcionales y ajustes») y requisitos RF-15 a RF-17.

Verificado el 2026-10-07 por el estudio `docs/phase7/verificacion-fuentes.md` (agente D); la parte de combustible, también por mí con una descarga real (999 estaciones con GLP, 377.379 bytes).

| Fuente | Estado verificado | Enfoque | Sigue sin verificar |
| --- | --- | --- | --- |
| **Precios de combustible** (Ministerio) | Servicio abierto, **sin clave**, JSON o XML. Fichero nacional: 12,2 MB (sin compresión), 11.499 estaciones. Campo de GLP: `Precio Gases licuados del petróleo` (999 estaciones); también GNC (134), GNL (94), hidrógeno (2), gasolina 95/98, gasóleo A. **El servicio se actualiza cada 30 minutos**, no a diario. Coma decimal | Descargar y filtrar en local. **Mejor por producto** (`EstacionesTerrestres/FiltroProducto/{id}`; GLP = 377 KB): no revela la ubicación. **No usar filtros por provincia o municipio**: revelan dónde está el usuario | **Licencia de reutilización** (las fichas oficiales ya no existen): aclararlo con el Ministerio antes de publicar. Límites de uso: no hay ninguno publicado. No hay fecha por estación |
| **Cercanías (Renfe)** | GTFS-RT público **sin clave** (`gtfsrt.renfe.com`), protobuf y JSON, **CC BY 4.0**, refresco cada 20 s. Estaciones por `stop_id` del GTFS | El tiempo real solo trae la siguiente parada de cada tren: un tablero de salidas completo exige además el GTFS estático (14 MB). Alternativa reducida: trenes que se acercan y avisos | Texto exacto de atribución de Renfe; licencia de la librería protobuf |
| **Metro de Bilbao** | GTFS-RT completo **sin clave**, estático de 1,7 MB, CC BY 4.0 | Primer metro a implementar: el único con tiempo real público, oficial y comprobado | — |
| **Metro de Madrid** | **No hay tiempo real abierto.** Solo GTFS estático del CRTM (licencia con «Powered by CRTM») | Solo horarios sin conexión | — |
| **TMB (Barcelona), Valencia, Sevilla, EMT** | Piden clave o solo hay estático con clave | Solo con **clave puesta por el usuario** en Ajustes | — |

Orden y estimaciones del estudio (son estimaciones, sin medir): combustible 6-9 días; módulo GTFS/GTFS-RT genérico con Metro Bilbao 6-8; Cercanías 5-8 (tablero completo) o 2-3 (reducido); ajustes e interfaz común 5-7; TMB y similares 3-4 cada uno con clave del usuario. Total con tablero completo: unos 24-35 días.

Reglas: todo por `NetworkPolicy`, desactivado por defecto, con un aviso al activar de qué se envía y a quién, y sin enviar nunca la ubicación del usuario. `PRIVACY.md` y la lista de conexiones posibles se actualizan con cada fuente.

## Qué no hacemos (de momento)

Tráfico de carretera, reseñas y fotos, iOS, cuentas y servidores propios. (El transporte público en tiempo real pasa a F7, opcional.)
