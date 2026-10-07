# Seguimiento de ruta (`:core-nav`)

Cubre RF-05 (giro a giro, recálculo, carriles, límite de velocidad, simulación de ruta) y la parte de lógica de RNF-05
(el servicio en primer plano solo alimenta y observa este motor). Módulo Kotlin JVM puro: `./gradlew :core-nav:test`
corre en segundos y sin Android.

## Piezas

| Pieza | Qué hace |
|---|---|
| `RouteGeometry` | Polilínea medida (distancias por haversine, proyección plana local por segmento). Búsqueda en ventana sin asignaciones. |
| `RouteTracker` | Núcleo síncrono y determinista: `onFix`, `onTick`, `snapshot`. El tiempo sale solo de los fijos y los ticks. No es thread-safe. |
| `NavigationSession` | Envoltorio de corrutinas: lee una `LocationSource`, publica `state: StateFlow<NavState>`, `announcements: SharedFlow<Announcement>` y `route: StateFlow<RoutePlan>`. Lanza y cancela los recálculos. |
| `RouteSimulator` | Fijos de alguien que recorre la ruta a velocidad constante, con ruido gaussiano reproducible (`java.util.Random(seed)`) y huecos. Sirve para los tests y para la simulación de ruta de la app. |
| `NavConfig` | Todos los umbrales (abajo). |

`NavStatus`: `ON_ROUTE`, `OFF_ROUTE`, `REROUTING`, `ARRIVED`, `NO_SIGNAL` (EN_RUTA, FUERA_DE_RUTA, RECALCULANDO, LLEGADO, SIN_SEÑAL; identificadores en inglés como el resto del código).

El tracker solo se toca desde una corrutina (un único consumidor de una cola de fijos que descarta el más antiguo si se
atasca): no hay locks. El resultado de un recálculo vuelve por otro canal conflado y lo aplica esa misma corrutina.

## Avance por la ruta

1. **Ventana.** Se busca el segmento más cercano solo entre `anchor - (40 m + 2·precisión)` y
   `anchor + 80 m + 3·precisión + max(2·v, 15 m/s)·Δt`, donde `anchor` es el avance del último fijo bueno y Δt el tiempo
   desde él (tope 5 km). Es lo que evita que una ruta con bucles o cruces salte a otro paso por el mismo punto: el otro paso
   queda fuera de la ventana. El primer fijo usa 2 km (la ruta puede empezar donde acaba y gana el menor avance en empate).
2. **Rumbo.** El score es `distancia + 25 m · diferencia_de_rumbo/180°` (solo con rumbo y velocidad ≥ 1,5 m/s). Sirve de
   desempate en tramos solapados u opuestos (U, ida y vuelta por la misma calle). No descarta nada: solo penaliza.
3. **Filtro alfa-beta del avance.** Predicción `anchor + v·Δt`; corrección `α = 1/(1 + (precisión/8)²)` (precisión 5 m → 0,72;
   20 m → 0,14). Con ruido de 20 m el avance se alisa en vez de saltar; con 5 m sigue casi sin retraso. Si el fijo trae
   velocidad se usa (media móvil 0,5); si no, se aprende de la innovación.
4. **Monotonía.** `progreso = max(anchor, filtrado)`. La única vez que puede retroceder es al resincronizar (primer fijo,
   tras SIN_SEÑAL, tras >10 s sin fijos o al reengancharse), porque la estimación pudo pasarse.
5. **Parado.** Con velocidad del fijo < 0,5 m/s el avance no se mueve: sin esto el `max()` hace derivar el avance hacia
   delante con ruido (probado: 600 fijos de ruido de 8 m parado dejan el avance en ±1 m).

## Fuera de ruta (histéresis)

Umbral `max(30 m, 2·precisión)`: 30 m cubre el ancho de calzada y el error típico de un buen GPS; 2·precisión (la
precisión de Android es un radio del 68 %) deja fuera la cola de ruido en cañón urbano.

Un fijo es «fuera» si `distancia > umbral` o si va en sentido contrario (rumbo a > 135° del tramo con ≥ 3 m/s). Se confirma
`OFF_ROUTE` cuando se cumple cualquiera de:

- ≥ 5 fijos fuera seguidos **y** ≥ 3 s desde el primero (el caso normal; 3 s evita que una ráfaga de fijos repetidos cuente como 5);
- ≥ 2 fijos y ≥ 10 s (fijos escasos: 1 cada 5 s tardaría 25 s con la regla anterior);
- ≥ 3 fijos seguidos a más de 3× el umbral (desvío claro: no hace falta esperar más).

Un fijo a ≤ 0,7·umbral resetea la cuenta (banda de histéresis: entre 0,7 y 1×umbral no cuenta ni resetea). Un fijo basura
aislado (3 km al lado) cuenta como uno y el siguiente bueno lo borra; un fijo con precisión > 100 m se ignora entero (ni
mueve el avance ni oculta una pérdida de señal). Mientras hay fijos «fuera» sin confirmar, el avance se queda quieto.

**Reenganche:** en `OFF_ROUTE`/`REROUTING`, un fijo a ≤ 0,7·umbral vuelve a `ON_ROUTE` y resincroniza (la ventana crece con
el tiempo fuera, así que cubre atajos). La sesión cancela entonces el recálculo en curso y descarta su resultado.

## Recálculo (`NavigationSession`)

`suspend (desde: LatLon, rumbo: Float?) -> RoutePlan?` inyectado. Solo uno a la vez. Hasta 3 intentos por ciclo, 2 s entre
ellos (más lo que tarde cada uno); si fallan todos se vuelve a `OFF_ROUTE` y se espera 8 s antes de otro ciclo mientras
siga fuera. Una excepción cuenta como fallo; una ruta con < 2 puntos, también. `CancellationException` se propaga (`stop()`
cancela). Con éxito se crea un `RouteTracker` nuevo (`routeRevision + 1`) y se le reenvía el último fijo.

## Llegada

`ARRIVED` (final) si faltan ≤ 25 m de ruta (el radio de un portal/aparcamiento) o si faltan ≤ 60 m y lleva ≥ 8 s parado
(< 0,8 m/s): aparcar antes de la puerta también es llegar. Se mide sobre el avance proyectado, no la distancia al punto, para que
una ruta que acaba donde empieza no «llegue» en la salida. Al llegar se emite el aviso `NOW` pendiente de la última maniobra.

## Pérdida de señal

Sin fijo útil durante 5 s (`onTick`, que la sesión llama cada 1 s) → `NO_SIGNAL`, `estimated = true`, avance = último avance
+ velocidad · tiempo (tope 30 s de estimación, sin pasar nunca del último metro: no se llega en un túnel). Los avisos
siguen saliendo con la estimación. Al volver un fijo gana el real y se resincroniza. Si ya estaba fuera de ruta no se pasa a
`NO_SIGNAL`.

## Avisos de voz

Tres niveles por maniobra, `clamp(v·segundos, mín, máx)`:

| Nivel | segundos | mín | máx | A 10 m/s | A 36 m/s | A pie (1,4 m/s) |
|---|---|---|---|---|---|---|
| `FAR` | 30 | 200 m | 2000 m | 300 m | 1080 m | 200 m |
| `NEAR` | 10 | 60 m | 400 m | 100 m | 360 m | 60 m |
| `NOW` | 3 | 20 m | 80 m | 30 m | 80 m | 20 m |

Cada nivel se emite como mucho una vez por maniobra y solo avanza en urgencia. Si un fijo cruza varios umbrales a la vez
(rápido o con huecos) solo se emite el más urgente y los menos urgentes quedan descartados: no se lee «en 500 m» a 60 m.
Solo se anuncia la próxima maniobra; `DEPART` y las maniobras ya rebasadas se dan por pasadas.
Límite conocido: si entre dos fijos el coche salva toda la banda `NOW` (80 m como máximo, es decir ~2,2 s a 36 m/s) y la
maniobra queda atrás, no se emite su `NOW`; el test de 36 m/s con fijos cada 2 s (72 m) comprueba que aún no ocurre.

## Límite de velocidad

Por segmento (se rellena una vez al cargar la ruta; `kmh = null` borra el límite). Superación con tolerancia configurable
(0 por defecto) e histéresis de 2 km/h para que el aviso no parpadee con el ruido de la velocidad del GPS.

## Rendimiento

Microbenchmark (`TrackerBenchmarkTest`, `./gradlew :core-nav:test --tests '*Benchmark*' -i`). Ruta de 100 km (5001
puntos, 250 maniobras), 3995 fijos a 25 m/s con ruido de 5 m. Solo se mide el bucle por fijo (sin construir el tracker).
Salida pegada de una ejecución, en este PC (Intel Core Ultra 7 265U, JDK 21, JIT caliente tras 40 pasadas):

```
BENCH route=5001 points, 250 maneuvers, 3995 fixes/pass
BENCH onFix only:      516 ns/fix, 0,0 B/fix allocated
BENCH onFix+snapshot:  818 ns/fix, 167,9 B/fix allocated
```

Otras ejecuciones dieron 620-756 ns (solo `onFix`): el ruido entre ejecuciones es de ±30 %. Es CPU de escritorio con JVM;
**no** es una medida del Pixel 8 ni de ART. `onFix` no asigna; el `NavState` publicado (≈ 170 B, uno por fijo) es la única
asignación por fijo, y se hace en el publicador, no en la búsqueda. Construir la geometría de esta ruta (una vez) asigna ≈ 400 KB.

## Pruebas

`RouteTrackerTest` (síncrono): recta, L, rotonda, U, bucle con dos pasos por el mismo tramo (con y sin rumbo), ruido de 5 m y
de 20 m (20 semillas cada una, sin salir de ruta, error < 15 m / 45 m), fijo basura aislado, confirmación de fuera de ruta
(por fijos, por tiempo y por desvío claro), reset por fijo bueno, precisión que ensancha el umbral, reenganche, sentido contrario,
llegada (radio y parado), ruta que acaba donde empieza, avisos rápidos (36 m/s con fijos cada 2 s) y a pie, maniobras
cercanas, primer fijo a mitad de ruta, límite y exceso con histéresis, carriles, túnel, parado con ruido.
`NavigationSessionTest` (corrutinas con tiempo virtual): seguimiento completo, recálculo (falso por fijo malo; real; reintentos y
espera sin solaparse; excepción; ruta inservible; cancelación al parar; cancelación al reengancharse), túnel y silencio inicial.
`RouteSimulatorTest`: velocidad, reproducibilidad, desviación típica del ruido, huecos.

## Pendiente / límites

- La estimación de tiempo restante es lineal en distancia (`duración · restante/total`); no usa la velocidad real ni los límites.
- Sin mapa de carreteras: la ventana y el rumbo resuelven los solapes, pero una ruta que pasa dos veces por el mismo sitio
  *en la misma ventana de 100-150 m* (p. ej. un cruce en 8 muy cerrado) puede elegir el paso equivocado hasta que se aleje.
- Persistir el estado para sobrevivir a la muerte del proceso (arquitectura, «Servicio de navegación») y el servicio en primer plano
  quedan para la capa Android; `NavigationSession(plan, …)` puede reanudarse con el último fijo conocido (primer fijo: ventana de 2 km).
- Las cadenas de voz y su traducción (es/en) pertenecen a la capa `VoiceGuide`; aquí solo salen `Announcement`.
