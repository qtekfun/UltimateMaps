# Robustez de la navegación (agente G, rama `feat/nav-robustness`)

El usuario pidió explícitamente «robustez en la navegación». Hechos que motivan el trabajo: el núcleo nativo **sí** abortó el proceso entero (SIGABRT por un `CHECK` de CoMaps, Pixel 8; véase `docs/decisions.md`, entrada «el núcleo arrancaba con el estilo equivocado»); en navegación eso es perder la guía en plena carretera. Madrid–Barcelona con 7 regiones da `ROUTE_NOT_FOUND` y con 25 tardó ≈ 18 s en el spike.

**Qué está probado y qué no.** Todo lo que sigue compila y está probado en la JVM (`./gradlew test`: 435 tests, 0 fallos en la última ejecución). **No se ha ejecutado nada en un dispositivo y el núcleo nativo real no se ha ejecutado** con el aislamiento: la capa de aislamiento se probó contra un `NativeBridge` falso y un transporte falso. `BinderCoreTransport`, `CoreService`, `NavigationService` y el manifiesto compilan y tienen tests de manifiesto/texto, pero su comportamiento en el móvil está por verificar.

## 1. Seguimiento (`:core-nav`)

| Tema | Qué hace ahora |
|---|---|
| Aviso «ahora» saltado | `RouteTracker.derive` detecta el **cruce por posición a lo largo de la ruta** (índice de maniobra que pasa de «por delante» a «ya pasada»), no solo estar dentro de la banda. La última maniobra cruzada sin su «ahora» lo recibe (`meters = 0`); las anteriores se dan por superadas en silencio; tras una resincronización (reenganche en otro punto) no se avisa de un giro que ya quedó atrás. Nunca se repite un aviso (`mAnnounced` por maniobra y nivel). |
| Paradas intermedias | `RouteGuidance.stops: List<Int>` = índices de geometría, en orden de ruta. **Retrocompatible**: valor por defecto vacío, `data class` con el parámetro al final; `GuidanceWire` (agente A) no cambia. Llegar a una parada emite `NavEvent.StopReached(stopIndex, point)` (flujo `NavigationSession.events`) y **sigue**; si el usuario se reengancha más allá sin pasar (recálculo, pérdida larga) emite `StopSkipped`. `NavState` gana `stopsRemaining` y `nextStopMeters`. `ARRIVED` solo llega al destino. Paradas a menos de 1 m del origen se ignoran. Cómo se rellenan: el motor no las informa, así que `RoutePlan.withStops(via)` (en `core-nav`) proyecta los `via` de la `RouteRequest` sobre la geometría; **el agente F debe llamarla** con las paradas del panel al arrancar la navegación (`controller.start(plan.withStops(via), trip = NavTrip(profile, options))`). En un recálculo, el controlador pide la ruta con solo las paradas que quedan por delante. |
| Fijos corruptos | Coordenadas no finitas o fuera de rango: descartados. Duplicados y fuera de orden (`t <= último`): descartados. Reloj que retrocede para siempre (> 5 s): tras 3 fijos crecientes en el pasado se **rebasa** el tiempo (sin esto se descartarían todos los fijos para siempre). Precisión > 100 m: ignorados (ya existía). |
| Picos a lo largo de la ruta (multipath) | Un salto hacia delante que la velocidad no explica (`> 1,6·v·Δt + 3·precisión + 60 m`) se ignora salvo que se repita 3 veces; antes arrastraba el progreso para siempre (el progreso nunca retrocede) y acababa en un falso «fuera de ruta». |
| Pérdida larga | Tras `NO_SIGNAL` o > 30 s sin fijos, si la ventana de búsqueda (tope 5 km) no contiene al coche, se busca en **toda la ruta** (a 30 m/s, 4 min = 7,2 km). El primer fijo también puede estar en cualquier punto (reanudar). |
| Rutas raras | `RoutePlan.sanitized()` quita puntos no finitos y reasigna índices de maniobras, límites y paradas; un solo punto se duplica (ruta de longitud 0 que llega al instante); índices fuera de rango se acotan. Puntos repetidos y 40 000 puntos funcionan (test). `LatLon` ya impide NaN en su constructor. |
| Recálculo incoherente | `NavigationSession.isSane`: se descarta una ruta nueva que no empieza a ≤ 500 m del usuario, no acaba a ≤ 500 m del destino anterior, o tiene < 2 puntos. Se sigue con la ruta vieja y se reintenta (reintentos/cooldown de `RerouteConfig`). |
| Cambio de ruta a mitad | `NavigationSession.replaceRoute(plan)` (cola conflada); `routeRevision` sube; el nuevo tracker tiene sus propios avisos. |
| Bucle protegido | Cada mensaje del bucle de la sesión va en `try/catch`: una excepción incrementa `internalErrors` y la guía sigue. |

Pruebas (`RobustnessTest`, `NavigationControllerTest`, `NavStateStoreTest`, …): ruido gaussiano + picos de 500 m y de 80 m + precisión 400 m + fijos sin velocidad/rumbo (6 semillas); duplicados y pares intercambiados; reloj que salta 2,5 años atrás y que retrocede 3 fijos; pérdida de 4 min a 30 m/s; rutas degeneradas; 40 000 puntos; reanudar en medio; recálculos absurdos; propiedades: ningún aviso repetido, el progreso no retrocede salvo tras una pérdida/fuera de ruta/cambio de ruta, ninguna excepción.

## 2. Servicio de navegación (`:app`, `nav/`)

- `NavigationController` (en `:core-nav`, JVM puro, para poder probarlo): `state: StateFlow<NavState?>` (null = sin navegación), `route`, `problem`, `announcements`, `events`, `start(plan, startAlongMeters, trip)`, `stop()`, `resume()`, `replaceRoute()`, `hasResumable()`. La app lo tiene en `MapasApp.navigation`.
- `NavigationService`: servicio en primer plano `foregroundServiceType="location"`, permiso `FOREGROUND_SERVICE_LOCATION` (verificado con la documentación de Android: Android 14 exige el permiso y el tipo, y el permiso de ubicación en tiempo de ejecución al llamar a `startForeground`). Notificación (silenciosa, `CATEGORY_NAVIGATION`) con la próxima maniobra y distancia, distancia y tiempo restantes, y acciones Abrir y Parar. Textos es/en en `strings_nav.xml`. Sin ubicaciones en la notificación ni en logs.
- **Estado persistente** (`NavStateStore`): un fichero en `noBackupFilesDir`, escritura atómica (temporal + `fsync` + `ATOMIC_MOVE`), formato versionado con `RoutePlanCodec` (cuentas acotadas: un fichero corrupto solo da `IOException`; probado con 400 mutaciones), **caduca a las 3 h**, se borra al parar, al llegar y al detectar corrupción. Se guarda al empezar, al cambiar la ruta y como mucho cada 10 s (30 s con ahorro de batería). Guarda también perfil y opciones de evitar, para que los recálculos tras reanudar usen los mismos.
- **Reanudar** si el sistema mata el proceso: `START_STICKY` + `intent == null` → `controller.resume()`; si no hay estado válido el servicio se para. Si Android impide arrancar en primer plano (Android 12+ desde segundo plano, o Android 14 sin permiso) el servicio se rinde sin ruido: el estado queda en disco y la UI debe ofrecer `NavigationService.resume(context)` al volver (la pantalla de navegación está fuera de alcance).
- **Permiso retirado / GPS apagado**: el controlador lo vigila cada 2 s (`NavProblem`), la notificación lo dice, y al desaparecer la causa se reinicia la escucha (`restartLocation`). Sin fijos, el seguidor pasa solo a `NO_SIGNAL` con estima.
- **Ahorro de batería**: la lectura de posiciones sigue a 1 Hz (es la razón del servicio); se reducen escrituras a disco y se limita la notificación a 1 cada 5 s (`NavNotificationThrottle`). No se pide exención de optimización de batería.
- El proceso `:core` también crea una `Application`: `MapasApp.onCreate` sale pronto si no es el proceso principal (si no, repetiría `CoreLinks.sync` y la lectura de ajustes).

## 3. Núcleo aislado en `:core` (`:native-comaps`, `isolation/`)

```
proceso principal                               proceso :core (android:process=":core")
 SearchEngine / DetailedRoutingEngine            CoreService (Binder)
   └ IsolatedCore ──CoreTransport──Binder────►     └ CoreHost ──► CoMapsCore ──JNI──► libumcomaps.so
        (plazo, reinicio, 1 reintento)               (serializa, init 1 vez, trocea respuestas)
```

- **Contrato**: `CoreHandle` (`init`, `refreshMaps`, `searchEngine`, `routingEngine`) lo implementan `CoMapsCore` (en proceso) e `IsolatedCore`. `CoMapsSearchBackend.prepareCore` devuelve `CoreHandle`, así que `CoMapsRouteBackend` (agente F) compila sin cambios. Válvula de seguridad: la preferencia `core.isolated = false` vuelve al núcleo en proceso (mientras el aislamiento no se haya visto en un móvil).
- **`init` perezoso y una sola vez**: `IsolatedCore.init` solo recuerda los argumentos; el proceso arranca en la primera llamada real; el `INIT` remoto se envía una vez por conexión y `CoreHost` ignora un segundo; tras un reinicio el cliente reinicializa solo y repite el `refreshMaps` si se había pedido. Cambiar el directorio de mapas reinicia el núcleo.
- **Muerte** (`DeathRecipient`, `DeadObjectException`, `onServiceDisconnected`): se reconecta, se reinicializa y se **reintenta UNA vez** dentro del plazo; una segunda muerte → `CoreException(CRASHED)`; tres muertes en 60 s abren un **circuito** de 30 s (`UNAVAILABLE`, falla sin arrancar el proceso) para que una petición envenenada no provoque un bucle de caídas.
- **Errores estructurados**: búsquedas lanzan `CoreException(kind)` (el `SearchCoordinator` ya muestra su estado de error); rutas devuelven `RouteOutcome` con códigos `CORE_CRASHED`, `CORE_UNAVAILABLE`, `CORE_INTERNAL`, y `CANCELLED` para tiempo agotado (la UI de F ya lo muestra como «tardó demasiado»). El servidor solo manda el **tipo** de la excepción, nunca su mensaje (podría eco de una consulta).
- **Plazo y cancelación fiables**: la llamada bloqueante va a un hilo trabajador y el cliente espera con plazo (`timeout nativo + holgura`, +12 s de presupuesto de reinicio si el proceso muere). Si vence, o se interrumpe el hilo que llama (cancelación de corrutina vía `runInterruptible`), **se mata el proceso `:core`** (`OP_KILL`, oneway): es la única forma de parar un cálculo nativo, y libera al hilo trabajador. Así no se acumulan cálculos abandonados (a lo sumo una llamada en vuelo). El siguiente uso arranca un `:core` limpio (coste: recarga de mapas).
- **Resultados grandes**: Binder limita todas las transacciones en vuelo de un proceso a ≈ 1 MB. Respuestas > 192 KB las guarda el host (máx. 4) y el cliente las pide en trozos de 192 KB dentro del mismo plazo; si el núcleo muere a mitad se repite todo. Probado con 40 000 puntos (≈ 640 KB) con un transporte falso que rechaza transacciones > 250 KB.
- **Memoria**: `:core` carga la `.so` y los `.mwm`; el proceso principal ya no. `CoMapsCore()` solo se construye en `:core` (cargar `libumcomaps` solo allí). Coste: un proceso más (~ decenas de MB de base) y la serialización de rutas. Sin medir.
- **Seguridad**: servicio `exported="false"`, el `onTransact` comprueba `getCallingUid() == myUid()`, solo códigos válidos.
- **Pruebas** (`IsolatedCoreTest`, 20, repetido 3 veces sin fallos): arranque perezoso e init único; refresco recordado tras reinicio; muerte durante la llamada, durante `INIT` y a mitad de una respuesta troceada; dos muertes → error estructurado; llamada colgada → plazo y kill; cancelación; sin acumulación; respuesta enorme; circuito; init rechazado; excepciones Java y peticiones mal formadas; concurrencia (8 hilos); cambio de directorio.

## 4. Política de ruta fallida (`RouteFailurePolicy`, `RouteRunner`)

| Resultado | Clasificación | Consejo | Reintento automático |
|---|---|---|---|
| `NEED_MORE_MAPS` | falta de mapas | descargar (con nombres de región si el router los dio) | no (determinista) |
| `START/END/INTERMEDIATE_NOT_FOUND` | punto fuera de carretera | cambiar el punto | no |
| `ROUTE_NOT_FOUND` | sin ruta con estas opciones | cambiar perfil/opciones/puntos | no |
| `CANCELLED` / plazo | tardó demasiado | reintentar (decide el usuario) | no (volvería a pasar) |
| `CORE_CRASHED` | el motor se reinició | reintentar | **1** vez tras 1,5 s |
| `CORE_UNAVAILABLE` | circuito abierto / no arranca | reintentar luego | no |
| otro / excepción | interno | reintentar | **1** vez |

`RouteRunner.run` nunca bloquea el hilo (suspende), nunca lanza (salvo cancelación), acota **toda** la ejecución con reintentos a 40 s, e interrumpe el hilo al vencer o cancelarse (con el núcleo aislado eso mata el cálculo atascado; probado con un motor falso que registra la interrupción). Mensajes es/en en `RouteFailureMessages`.

**Regiones que faltan.** El catálogo no tiene geometría, así que **no se puede deducir** qué regiones faltan solo con él. `Route::GetAbsentCountries()` sí existe en el núcleo; `RouteOutcome.absentCountries` (nuevo, con valor por defecto) y `RouteFailurePolicy.missingRegionNames` (los ids de CoMaps → regiones del catálogo por `comapsId`) ya están listos, pero el valor **aún llega vacío** porque falta exponerlo por JNI (una función `nativeLastAbsentCountries()` que lea `route.GetAbsentCountries()` en `Core::Route`; no se hizo porque recompilar el núcleo nativo en este entorno es muy costoso y no habría forma de ejecutarlo). Mientras tanto el mensaje es el genérico «faltan mapas… descárgalos».

**Madrid–Barcelona.** `route_not_found` con 7 regiones es lo esperable si faltan regiones intermedias (CoMaps necesita todos los mwm del trayecto); con el router sin dar nombres, la app solo puede decir que faltan mapas. El `RoutePreviewController` de F tiene su propio plazo de 30 s y hace `work.cancel()` sobre una llamada bloqueante que no se interrumpe: con el aislamiento esa llamada acaba sola a más tardar a `timeout + 5 s` (el cliente mata el núcleo), así que no se acumulan; para cortarla de verdad a los 30 s, F puede adoptar `RouteRunner`.

## Riesgos que quedan

1. **Nada de esto se ha ejecutado en un móvil.** Los fallos típicos de lo no probado: Binder real (`bindService` desde un hilo trabajador sin `Looper`, `onNullBinding`), tiempo de arranque de `:core` (carga de mwm) frente a los 12 s de presupuesto, `startForeground` rechazado en Android 14, `START_STICKY` bajo restricciones de segundo plano. Hay una válvula (`core.isolated=false`) pero no una UI para ella.
2. **Matar `:core` para cancelar es caro**: tras un plazo vencido, la siguiente búsqueda/ruta paga el arranque en frío del núcleo (segundos).
3. **Reencolado de recálculos mientras `:core` revive**: la sesión sigue con la última ruta y reintenta (3 intentos, 2 s, cooldown 8 s) pero no hay «precalentamiento» del núcleo tras una muerte: el primer recálculo paga el reinicio.
4. **Un `CHECK` determinista en la misma petición** (petición envenenada) tumba `:core` dos veces; el usuario ve un error y el circuito evita el bucle, pero no se identifica ni se bloquea esa petición.
5. **`GetAbsentCountries` sin exponer por JNI** (arriba): mensaje genérico de «faltan mapas».
6. **El estado reanudable guarda la ruta (posiciones) en `noBackupFilesDir`**, caduca a las 3 h y no se cifra; coherente con `allowBackup=false`. No hay ubicaciones en logs.
7. **La UI aún no usa nada de esto**: no hay pantalla de navegación; `NavigationController.start` debe llamarse con `plan.withStops(via)` y `NavigationService.start(context)` desde una actividad visible.
8. `CoreLinks.restartNeeded` (una región borrada sigue respondiendo hasta reiniciar) podría resolverse ahora matando `:core`; no se tocó.
9. Los umbrales nuevos (saltos de 60 m de holgura, 30 s de pérdida larga, 500 m de recálculo) salen de razonamiento y de las pruebas sintéticas, no de trazas reales.
