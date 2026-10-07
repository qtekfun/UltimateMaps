# Gasolineras: datos y Ajustes (rama `feat/fuel-data-settings`)

Cubre RF-15 (datos) y RF-17 (pantalla de Ajustes). La capa del mapa y la ficha de gasolinera (agente F) solo consumen las interfaces de `:core-fuel`; **no se ha cambiado ninguna firma de `FuelRepository`, `FuelSettings`, `FuelSettingsStore`, `FuelType` ni `FuelStation`** (solo se añadieron tipos y funciones).

## Aviso: licencia de reutilización NO verificada (bloqueo antes de anunciar la función)

El estudio (`verificacion-fuentes.md`, sección 1) no encontró el texto de las condiciones de reutilización del servicio REST del Ministerio (las fichas de datos.gob.es ya no existen; la cita de la Ley 37/2007 solo aparece en una ficha de terceros). **No se ha leído ninguna licencia ni norma que la fije.** Por prudencia se siguen las condiciones habituales en las apps que lo reutilizan, pero eso no sustituye a verificarlo:

1. Citar la fuente: «Datos: Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Hidrocarburos), reutilizados conforme a la Ley 37/2007. Información no oficial; comprueba el precio en el surtidor.» (en Ajustes, es/en, y para la ficha con `FuelAttribution.text(lastUpdateMillis)`).
2. Mostrar la fecha de la última descarga (junto al texto, en Ajustes; la ficha debe pasarla a `FuelAttribution.text`).
3. No alterar el sentido de los datos (los precios se muestran tal cual; se usa «precio publicado por el Ministerio», nunca «oficial»).

**Antes de anunciar o publicar la función hay que aclarar la licencia por escrito con el Ministerio** (o localizar su política de reutilización). Mientras tanto la función viene apagada por defecto.

## Qué se verificó con peticiones reales (2026-10-07)

Dos peticiones, mínimas, a `sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/`:

- `Listados/ProductosPetroliferos/` (2,7 KB, 200, JSON, UTF-8 sin BOM): 30 productos con `IDProducto`, `NombreProducto`, `NombreProductoAbreviatura`. De ahí salen los ids del catálogo (`FuelTypes`): 1 G95 E5, 23 G95 E10, 24 E25, 25 E85, 20 E5 Premium, 3 G98 E5, 21 G98 E10, 4 «Gasóleo A habitual», 5 Premium, 6 B, 16 Bioetanol, 8 Biodiésel, 17 GLP, 18 GNC, 19 GNL, 22 Hidrógeno, 26 AdBlue, 27 Diésel renovable, 28 Gasolina renovable, 29 Metanol, 30 Amoniaco, 31 y 32 Biogás. Se dejan fuera lo que un conductor no puede repostar: gasóleo C (calefacción, 7), fuelóleos (9, 10), gasóleo marino (11), aviación (12-14).
- `EstacionesTerrestres/FiltroProducto/22` (hidrógeno, 1 KB, 2 estaciones): confirma la forma de la respuesta por producto: `Fecha`, `ListaEESSPrecio[]` con un único campo `PrecioProducto` (cadena, coma decimal) más `Rótulo`, `Dirección`, `Municipio`, `Provincia`, `Latitud`, `Longitud (WGS84)`, `Horario`, `IDEESS`…, `Nota`, `ResultadoConsulta: "OK"`.

**No verificado:** el tamaño real del fichero por producto de gasolina 95 E5 y gasóleo A (estimado 3-10 MB, el tope de 20 MB lo cubre); el comportamiento ante muchas peticiones seguidas; la estabilidad de `IDEESS`; que el servicio siga con esta forma. El parser también acepta la forma nacional (23 campos `Precio …`, tabla `FuelTypes.nationalField`) por si cambia.

## Privacidad de la petición

Cada petición es `<URL>/EstacionesTerrestres/FiltroProducto/{IDProducto}`: nombra un combustible, nunca provincia, municipio ni coordenadas (hay un test que lo comprueba). El servidor ve la IP y qué combustibles se descargan. HTTPS siempre (`FuelClient` rechaza `http` salvo en tests con `allowInsecure`).

## Diseño (`:core-fuel`, JVM puro)

| Pieza | Qué hace |
| --- | --- |
| `FuelTypes` | Catálogo con ids reales y nombres en español (los nombres de producto no se traducen). |
| `FuelFeedParser` | Lector JSON en streaming propio (sin dependencias nuevas): un objeto de estación en memoria cada vez. Tolera BOM, coma decimal, precios vacíos, números en vez de cadenas, campos nuevos o ausentes y otro orden; rechaza truncados, `ResultadoConsulta` distinto de OK y ficheros sin ninguna estación utilizable. |
| `FuelClient` | Una petición por combustible. Cada salto (incluidas redirecciones) pasa por `NetworkPolicy.authorize`; tope de 20 MB (cabecera y flujo), conexión 15 s, lectura 20 s, total 120 s. Errores tipados (`FuelFailure`). |
| `FuelCache` | Un fichero binario compacto por combustible (tabla de cadenas, sello de tiempo, CRC32), escritura en `.tmp`, `fsync` y movimiento atómico. Un fichero dañado cuenta como «sin datos». |
| `FuelSnapshot` / `FuelDataRepository` | Une por `IDEESS` los precios de varios combustibles en `FuelStation.prices`; rejilla de 0,1° por combustible; `stationsIn(bounds, fuel, limit)` devuelve las `limit` más baratas con un montículo. Instantánea inmutable en un campo `@Volatile`: sin cerrojos, barata desde el hilo de UI. `lastUpdateMillis` = la fecha MÁS ANTIGUA entre los combustibles servidos. |
| `FuelDataManager` | Ajustes + política + descargas + caché. Un combustible que falla no frena a los demás y conserva su último dato bueno; los reintentos al pasar a primer plano esperan 5 min tras un fallo. TTL = frecuencia elegida, mínimo 30 min (el servicio se actualiza cada 30). |
| `FuelAttribution` | `text(lastUpdateMillis, english=false)` para la ficha. |

`:core-net`: se añadió `DefaultNetworkPolicy.removeEndpoint(host)` (no hay propósito propio en `ConnectionPurpose`: el host se lista como `OTHER`, mostrado como «Otra (precios de combustible)»).

### Cuándo se conecta (y cuándo no)

- Al arrancar la app: **nunca**. `FuelDataManager.start()` solo lee la caché local y, si la función está activa, registra el host en la lista blanca.
- Descarga: al **activar** (tras confirmar), al pulsar **Actualizar ahora**, al **marcar un combustible nuevo** (solo ese), y con la función activa al **pasar la app a primer plano** si los datos superan el TTL (`MapasApp` cuenta actividades iniciadas; sin modo sin red y sin repetir tras un fallo reciente).
- Al activar el host entra en la lista blanca y en «Conexiones posibles»; al apagar sale de ambas y los datos dejan de servirse (la caché queda en disco y se relee al reactivar). Modo sin red: cero conexiones (el test lo comprueba contando peticiones al servidor local).

## Ajustes (`app/.../settings`, `app/.../fuel`)

`SettingsActivity` + `SettingsScreen` (Compose con el sistema de diseño propio). Primera pantalla de Ajustes de la app; el interruptor de modo sin red de «Mapas» es el mismo ajuste (usa `RegionsController.setOfflineMode`).

- **Privacidad:** modo sin red, catálogo de regiones (dirección y botón a «Mapas») y lista de conexiones posibles con su estado (permitida, desactivada, bloqueada por modo sin red).
- **Gasolineras:** interruptor (apagado) con diálogo de confirmación (qué se pide, a qué servidor, qué ve, que la ubicación no se envía); combustibles (multiselección); combustible del mapa (uno de los descargados; si se desmarca, pasa a otro); frecuencia (30 min, 1, 3, 6, 24 h); URL de la fuente (solo https, restablecer); fecha de la última actualización, texto de atribución y «Actualizar ahora» con progreso («Descargando 2 de 3 (…)») y errores por combustible.
- Punto de entrada: engranaje discreto arriba a la izquierda del mapa, bajo la atribución (`SettingsGear`, `MapScreenState.onOpenSettings`); no hay cuarto botón en la fila Buscar/Listas/Mapas.
- `PrefsFuelSettingsStore` (SharedPreferences `mapas_fuel`), con `FuelSettings.normalized()` (combustibles desconocidos fuera, combustible del mapa dentro de los descargados, mínimo 30 min, URL no https → la de por defecto).

**Cableado para el agente F:** `(application as MapasApp).fuel.repository` (`FuelRepository`) y `.fuelSettings` (`FuelSettingsStore`); `FuelTypes.byId(settings.mapFuel)` da el `FuelType` del mapa. Con la función apagada el repositorio devuelve vacío.

**Copia de seguridad:** la app tiene `allowBackup=false` y aún no existe exportación de preferencias, así que las preferencias de gasolineras **no** entran en ninguna copia. Queda anotado: cuando exista la exportación de ajustes (RF-17), añadir el fichero `mapas_fuel`.

## Verificado y no verificado

Verificado: `./gradlew test` completo en verde (381 tests; 12 de datos con servidor HTTP local: 500, truncado, conexión cortada, lento, demasiado grande, redirección a host no autorizado, modo sin red, https obligatorio, host retirado; 5 de la pantalla con Robolectric: confirmación al activar, multiselección, validación de URL, lista de conexiones, atribución es/en).
No verificado: nada de esto se ha probado en un dispositivo (sin Pixel 8); el tiempo de construcción del índice y el consumo de memoria con el fichero real de gasolina 95 E5 (≈ 11 000 estaciones) no se han medido; la rejilla se probó con datos sintéticos; no hay descarga real de la app (solo las dos peticiones manuales de arriba).
