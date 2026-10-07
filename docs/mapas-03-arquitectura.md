# 03 · Arquitectura

## Principios

1. **Todo en el dispositivo.** Ninguna función de la v1 depende de un servidor propio ni de terceros, salvo descargar datos de mapas.
2. **Navegación desacoplada de la UI.** El motor de navegación es un servicio con un estado observable; la UI solo lo pinta. Así Android Auto o cualquier UI futura se añade sin reescribir nada.
3. **Motores intercambiables detrás de interfaces.** El spike puede decidir A, B o C sin tirar el resto de la app.
4. **GMS opcional.** Nada obligatorio depende de Google; si existe, se aprovecha por vías que no añaden dependencias.
5. **Privacidad por diseño.** Una única política de red, centralizada y auditable.

## Opciones de motor

| | A. Derivar de CoMaps | B. Ensamblar | C. Híbrido |
| --- | --- | --- | --- |
| Render | Motor de CoMaps | MapLibre Native + PMTiles | MapLibre Native + PMTiles |
| Búsqueda | Índice de CoMaps | Índice propio (SQLite FTS5) | Núcleo de CoMaps |
| Routing | Motor de CoMaps | Valhalla | Núcleo de CoMaps |
| Datos | .mwm de CoMaps | PMTiles + teselas Valhalla + índice propio | .mwm + PMTiles (dos descargas por región) |
| Control del aspecto | Limitado a su sistema de estilos | Total | Total |
| Cobertura mundial desde el día 1 | Sí | No: requiere pipeline y alojamiento propios | Sí |
| Riesgo principal | Estilo y fps por debajo del objetivo | Coste del pipeline mundial | Doble volumen de datos y complejidad |

**Regla de decisión** (detalle en `mapas-04-spike.md`): A si estilo y fps pasan; C si el motor pasa pero estilo o fps no; B solo si el núcleo de CoMaps no se puede desacoplar o sus licencias o formatos bloquean.

## Capas y módulos

```
┌──────────────────────────────────────────────────────────┐
│ :app  (Compose, estilo Apple Maps, bottom sheet con 3    │
│        detents, ajustes, intents de enlaces)             │
├──────────────────────────────────────────────────────────┤
│ :feature-*  búsqueda · navegación · moto · sitios · sync │
├──────────────────────────────────────────────────────────┤
│ :core-nav     servicio de navegación (estado observable) │
│ :core-map     interfaz MapEngine                         │
│ :core-search  interfaz SearchEngine                      │
│ :core-routing interfaz RoutingEngine                     │
│ :core-geo     parsers de enlaces, GPX/KML/Takeout        │
│ :core-data    base de datos local (sitios, listas, tracks)│
│ :core-sync    cliente WebDAV                             │
│ :core-net     NetworkPolicy (única salida a la red)      │
├──────────────────────────────────────────────────────────┤
│ :native  (C++ vía NDK/JNI): motor elegido tras el spike  │
└──────────────────────────────────────────────────────────┘
```

Interfaces clave (Kotlin): `MapEngine`, `SearchEngine`, `RoutingEngine`, `LocationSource`, `VoiceGuide`, `NetworkPolicy`. Los tests de navegación usan una `LocationSource` simulada.

## Diseño sin GMS (y con GMS cuando exista)

| Función | Cómo funciona sin GMS | Qué se aprovecha si hay GMS |
| --- | --- | --- |
| Ubicación | `LocationManager`: en Android 12+ (API 31) con `FUSED_PROVIDER`; en versiones anteriores, `GPS_PROVIDER` y `NETWORK_PROVIDER`. Sin la librería `play-services-location` | En un móvil con GMS, el proveedor fused del sistema lo respalda Google, así que se mejora sin dependencia. [verificar en el spike] |
| Ubicación por red (Wi-Fi/celdas) | Solo con microG/UnifiedNlp; si no, GPS puro | Mejor fix en frío |
| Voz | Motor TTS del sistema. Si no hay ninguno (frecuente sin GMS), la app lo detecta y guía para instalar uno libre (por ejemplo eSpeak NG o RHVoice); evaluar una voz propia empaquetada | Google TTS |
| Brújula | `SensorManager` (`TYPE_ROTATION_VECTOR`) | — |
| Notificaciones push | No se usan | — |
| Detección de GMS | Solo por `PackageManager`, para mostrar avisos; sin `GoogleApiAvailability` | — |
| Android Auto (fase posterior) | No aplica | Sabor `gms` si hace falta la Car App Library; decidir al llegar, por la política de dependencias de F-Droid |
| Compras/donaciones | Enlace externo; sin Play Billing | — |

Regla: **un solo sabor de compilación (`foss`) por defecto**. Se crea un sabor `gms` solo si las mediciones justifican una mejora concreta.

## Servicio de navegación y segundo plano

- Servicio en primer plano con tipo `location`; en Android 14+ requiere el permiso `FOREGROUND_SERVICE_LOCATION`.
- El estado de la ruta y la posición se persisten para sobrevivir a la muerte del proceso.
- Pantalla siempre encendida mediante el flag de ventana de la actividad, no con wake lock.
- ROM agresivas (ColorOS, OriginOS, HyperOS, MagicOS): pantalla guiada para excluir la app del ahorro de batería y detección cuando el sistema la mata en segundo plano (referencia: dontkillmyapp.com).

## Datos y regiones

- Catálogo de regiones versionado (id, tamaño, hash SHA-256, versión de datos).
- Descargas reanudables por rangos, verificadas por hash antes de activarse; actualizaciones atómicas (se activa el nuevo archivo cuando está completo).
- Si el motor es CoMaps, se respeta su estructura de carpetas y de fechas (`/maps/YYMMDD/<región>.mwm`) y la lista de servidores se puede cambiar en ajustes.
- Archivos de datos accedidos con `mmap`; nada se carga entero en memoria.

## Rendimiento (diseñado desde el principio)

- Presupuesto por frame: 16,6 ms a 60 Hz y 8,3 ms a 120 Hz; sin asignaciones ni GC en el bucle de render.
- Arranque: inicialización diferida de motores nativos; la primera pantalla pinta el mapa con el último estado guardado; Baseline Profiles y Macrobenchmark en CI.
- Medición con `adb shell dumpsys gfxinfo <paquete> framestats`, `adb shell am start -W` y trazas de Perfetto. Los resultados del spike se guardan como línea base.

## Diseño visual (estilo Apple Maps)

- Sistema de diseño propio (no Material You por defecto): tokens de color, tipografía y radios.
- Bottom sheet con tres posiciones (detents): colapsado, medio y completo.
- Paleta suave, carreteras con borde fino, iconos de POI redondeados, etiquetas con halo, edificios 3D discretos y transiciones día/noche animadas.
- Modo guantes: botones ≥ 56 dp, contraste alto y sin gestos finos.

## Red y privacidad

- `NetworkPolicy` es la única salida a la red: lista blanca de dominios configurable, modo «sin red» y registro local de conexiones.
- Conexiones posibles: descarga de regiones (a petición del usuario), fuente de teselas online (opcional, desactivada), resolución de enlaces cortos (opcional, desactivada) y sync WebDAV (opcional).
- TLS siempre; sin tráfico en claro. Credenciales de WebDAV en Android Keystore.

## Fuentes de datos opcionales y ajustes (F7)

Las fuentes que traen datos de fuera del mapa (precios de combustible, transporte público en tiempo real) se montan como **complementos pequeños detrás de una interfaz común**, para añadir o retirar una sin tocar el núcleo ni la navegación:

- `OptionalDataSource`: `id`, nombre visible, hosts que necesita, qué envía (texto para el aviso de activación), política de refresco, `fetch()` que devuelve un resultado con **fecha de los datos**, y su configuración propia.
- **Todo pasa por `NetworkPolicy`**: al activar una fuente se añaden sus hosts a la lista blanca y a la lista visible de conexiones posibles; al apagarla se quitan. Con el modo sin red no se conecta ninguna.
- **Desactivadas por defecto.** Activar una pide confirmación con una frase clara: qué datos se piden, a qué servidor y qué ve ese servidor (por ejemplo, su IP). **La ubicación del usuario no se envía nunca**: los datos se bajan enteros o por estación elegida y se filtran en el dispositivo.
- **Aislamiento de fallos:** si una fuente falla, caduca o cambia de formato, solo esa deja de mostrarse (con la fecha del último dato bueno); nada más se ve afectado. Caché en disco con sello de tiempo.
- **Claves de API:** no se embeben en la app. Si un operador exige clave, la introduce el usuario en Ajustes (en Android Keystore), o no se ofrece.
- **Ajustes:** las preferencias viven en un almacén local único (`:core-data`), se leen como flujo observable y entran en la copia de seguridad. Cada fuente declara sus campos (interruptor, combustible, radio, frecuencia, operadores, URL) y la pantalla de Ajustes los dibuja, para que añadir una fuente no obligue a rehacer la pantalla.
- **Módulos previstos:** `:core-settings` (preferencias), `:core-optional` (interfaz y registro de fuentes) y un módulo por fuente (`:source-fuel`, `:source-transit-*`). Se actualizan `PRIVACY.md` y la lista de conexiones con cada fuente.

## Sitios, import/export y sync

- Base de datos local (Room/SQLite) como fuente de verdad.
- Importadores: GPX, KML/KMZ, Takeout (GeoJSON de favoritos; los CSV de listas solo traen título y enlace, así que las coordenadas se resuelven con el enlace o por búsqueda local).
- Sync: carpeta WebDAV con archivos GPX/JSON por lista y track; ETag para detectar cambios y «última escritura gana» por elemento, con copia de seguridad del conflicto.
