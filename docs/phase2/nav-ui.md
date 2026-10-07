# Pantalla de navegación (agente N1, rama `feat/nav-ui`)

Estado: **compila y está probado en la JVM/Robolectric; nada se ha ejecutado en un dispositivo** (Pixel 8 vetado). La apariencia real, la fluidez de la cámara y el consumo quedan **no medidos**. Solo se ha visto el dibujo de los iconos y de la pantalla renderizados con Robolectric (gráficos nativos), no en pantalla real.

## Qué hay

| Pieza | Dónde | Notas |
|---|---|---|
| Botones «Empezar» y «Simular» | `route/RoutePanel` (`navStart`), `nav/NavLauncher` | Piden la ruta **con guiado** (`routingEngine(timeout, withGuidance = true)`) bajo `RouteRunner` (plazo 40 s, reintento acotado) y el mismo cerrojo nativo que búsqueda y vista previa. Los fallos se explican con `RouteFailureMessages`. `RoutePreviewController.currentRequest()` da la petición. |
| Guiado a través del núcleo aislado | `IsolatedCoreTest` (+2 tests) | **Verificado**: `withGuidance` viaja en `RouteArgs`, `CoreHost` lo respeta y `RoutePlanCodec` devuelve maniobras, carriles, límites y paradas; un guiado de ~3000 maniobras se trocea bajo el límite de Binder. No hizo falta cambiar código. El núcleo real con Binder real sigue sin ejecutarse. |
| Paradas intermedias | `NavScreenController.begin` | Llama a `plan.withStops(via)`; la sesión emite `StopReached` y la pantalla muestra «Parada alcanzada» 6 s. |
| Modelo | `nav/NavScreenController` (`MapasApp.navScreen`) | Vive con la aplicación: la actividad puede morir sin perder nada. Estados: `EN_RUTA, FUERA_DE_RUTA, RECALCULANDO, SIN_SEÑAL, PARADA_ALCANZADA, LLEGADO` (`NavPhase`). El resumen de llegada sobrevive a que el servicio pare el controlador. |
| Pantalla | `nav/NavScreen`, `ui/NavIcons`, `ui/theme/NavTheme` | Banner (icono, distancia, calle, segunda maniobra, carriles con el recomendado resaltado), franja de estado, límite y velocidad (aviso con texto, no solo color), ETA, restante, parar, recentrar, barra de simulación, resumen, oferta de reanudar. Atribución OSM dentro del panel inferior. Iconos vectoriales propios (48x48, trazos) para los 18 `TurnType` y los 10 `LaneDirection`; los de izquierda son los de derecha en espejo. |
| Modo guantes (RF-06) | `NavUi.glove`, `SharedNavUiPrefs` (`nav_ui`/`glove`) | Botones ≥ 56 dp (≥ 48 dp normal), negro/blanco/amarillo, texto mayor, sin gestos finos. Interruptor «Guantes» en la propia pantalla; Ajustes podrá enlazar la misma clave. |
| Cámara | `nav/NavCamera`, `nav/NavHost` | Rumbo de la ruta, inclinación 45°, zoom 17,5→15 según velocidad (pasos de 0,25; +0,5 cerca de un giro). Máx. 1 movimiento cada 0,8 s y solo si cambia ≥ 6 m, 4° o 0,25 de zoom: coche parado = 0 trabajo. Un gesto del usuario (`MapEngine.setCameraGestureListener`, nuevo) deja de seguir y muestra «Recentrar». |
| Pantalla encendida | `nav/KeepScreenOn` | `FLAG_KEEP_SCREEN_ON` de la ventana (no wake lock), solo mientras se navega (no en el resumen). |
| Simulación (RF-05) | `nav/NavSimulation`, `nav/SwitchableLocationSource` | `RouteSimulator` a 10/30/50/90/130 km/h ajustables en vivo. Apaga la fuente real, no arranca servicio ni pide permiso, **no se guarda** (`NavigationController.start(persist = false)`, cambio mínimo en `core-nav`; además limpia cualquier estado guardado anterior). |
| Salir y volver | `NavScreenController`, `AndroidNavServiceControl` | El servicio mantiene el seguimiento; al recrear la actividad `NavHost` vuelve a pintar el mismo estado. Tras morir el proceso: tarjeta «Navegación interrumpida: Reanudar / Descartar» (`refreshResumable` en `onStart`). Parar: limpia ruta, estado guardado, línea del mapa y servicio (`stopService`, nunca `startForegroundService` con la acción STOP, que se cuelga si no llama a `startForeground`). |

## Gancho para la voz (N2)

`nav/NavEventSink`: `onNavigationStarted`, `onAnnouncement(Announcement)` (sin cambios de firma, cada aviso una vez), `onEvent(NavEvent)`, `onNavigationEnded(arrived)`. Registro: `(application as MapasApp).navScreen.addSink(sink)`. Se llama desde un hilo de fondo; debe volver rápido.

## Cambios fuera de la carpeta propia (mínimos)

`core-nav`: `NavigationController.start(..., persist)`. `core-map`/`MapLibreEngine`: `setCameraGestureListener`. `MapScreen`: parámetros `navigating`/`overlay` (oculta hoja y botones; por defecto todo igual). `PanelHost`, `SheetPanel`, `MainActivity`, `MapasApp`: cableado (≈ 25 líneas). `RouteRunner.timeoutMillis` pasa a público. Cadena `nav_onto_street` en español: «a» → «en».

## Pruebas (JVM/Robolectric)

Estados y fases, simulación completa hasta la llegada, parada intermedia, fuera de ruta sin ruta y recálculo con motor falso, sin señal, reanudar/descartar, parar desde fuera, recreación de la actividad (bandera de pantalla, cámara, línea), banner con los 18 giros, carriles, límite y exceso, guantes ≥ 56 dp, es/en, botón Empezar. Los tests del modelo corren en un solo hilo con reloj manual y la simulación cede (`yield`) entre fijos: sin esperas por tiempo (repetidos 3 veces sin fallos).

## No verificado

- Apariencia y fluidez reales; consumo de batería de la cámara a 1 Hz; legibilidad al sol y con guantes reales.
- `startForegroundService` y el aviso en Android 14 desde la pantalla; permiso de ubicación al pulsar «Empezar» si aún no se había concedido (se pide y la franja de problema avisa; no se espera al resultado).
- La cámara no desplaza al usuario al tercio inferior (centrada en la posición); la línea de ruta se dibuja entera, no solo lo que queda.
- Guiado real con `:core` real (carriles en autovía sin probar); recálculo con el núcleo real durante la marcha.
- Voz: fuera de alcance (N2).
