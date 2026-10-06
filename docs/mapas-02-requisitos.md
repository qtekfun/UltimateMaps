# 02 · Requisitos

Los umbrales marcados como «objetivo» son propuestas que el spike debe validar o corregir.

## Alcance de la v1

**Dentro:** mapa offline, búsqueda offline, routing y navegación offline (coche, moto, bici, a pie), moto (evitar autopistas/peajes, curvas, modo guantes, grabación), sitios y listas, import/export, sync opcional con Nextcloud/WebDAV, abrir enlaces de mapas, ajustes de privacidad.

**Fuera (v1):** tráfico en cualquier forma, Google/Waze/Apple como proveedores de datos, iOS, reseñas y fotos de sitios, transporte público, cuentas y cualquier backend propio. Android Auto, en una fase posterior.

## Requisitos funcionales

| ID | Requisito | Criterio de aceptación |
| --- | --- | --- |
| RF-01 | Mapa vectorial offline con giro, inclinación, modo día/noche y edificios 3D opcionales, estilo Apple Maps | Checklist de estilo del spike con ≥ 8 de 10 puntos |
| RF-02 | Gestor de regiones: lista jerárquica del mundo, tamaños, descarga reanudable, verificación por hash, actualización y borrado; almacenamiento interno o tarjeta | Descargar, interrumpir, reanudar y verificar una región sin intervención |
| RF-03 | Búsqueda offline por nombre, dirección y categoría de POI, con resultados mientras se escribe y tolerancia a errores | Primeros resultados en ≤ 100 ms (objetivo) |
| RF-04 | Routing offline con perfiles coche, moto, bici y a pie; evitar autopistas, peajes, ferris y vías sin asfaltar; alternativas y paradas intermedias | Ruta Madrid–Barcelona en ≤ 2 s (objetivo) |
| RF-05 | Navegación giro a giro con voz, recálculo, indicaciones de carril, límite de velocidad y aviso al superarlo, modo noche automático y simulación de ruta | Ruta simulada completa sin errores de guiado |
| RF-06 | Moto: perfil propio, evitar autopistas/peajes, rutas con curvas (nivel de sinuosidad configurable), pantalla siempre encendida opcional, modo guantes (objetivos táctiles grandes, contraste alto) | Prueba real en moto con el soporte habitual |
| RF-07 | Grabación de recorridos: servicio en primer plano, pausa/reanudar, estadísticas, exportación GPX y recuperación tras cierre inesperado | Una grabación de 2 h sobrevive a un cierre forzado |
| RF-08 | Sitios y listas: favoritos, listas con color, icono y notas, ordenación por distancia, visibles en el mapa | Crear, editar y buscar dentro de listas |
| RF-09 | Importar y exportar GPX, KML/KMZ y Google Takeout; copia de seguridad completa | Importar un Takeout real y un GPX de 10 000 puntos |
| RF-10 | Sync opcional con Nextcloud/WebDAV: listas y tracks, con gestión de conflictos y credenciales en Android Keystore | La app funciona igual sin sync; dos dispositivos convergen |
| RF-11 | Abrir enlaces: `geo:`, Google Maps (largos y cortos), Apple Maps y Waze; resolver enlaces cortos solo si el usuario lo activa; búsqueda por nombre si el enlace no trae coordenadas | Batería de tests con ≥ 30 enlaces reales |
| RF-12 | Privacidad: sin telemetría, modo «sin red», lista visible de conexiones posibles, fuente de teselas online opcional y desactivada por defecto | Con el modo «sin red», cero conexiones salientes |
| RF-13 | Atribución de OpenStreetMap visible | Siempre presente en el mapa o en «Acerca de» según ODbL |
| RF-14 | Android Auto | Fase posterior |

### Enlaces de mapas (detalle RF-11)

- Esquemas y dominios a registrar: `geo:`, `https://www.google.com/maps/*`, `https://maps.google.com/*`, `https://maps.app.goo.gl/*`, `https://goo.gl/maps/*`, `https://maps.apple.com/*`, `https://waze.com/ul*`, `https://www.waze.com/*`.
- En Android 12 y posteriores, una app no verificada para esos dominios no se abre sola: el usuario debe activarla en «Abrir por defecto → Añadir enlaces». La app debe guiarle.
- Un enlace corto exige una petición de red a Google para conocer el destino. Ajuste desactivado por defecto, con aviso de qué se envía.

## Requisitos no funcionales

| ID | Requisito | Objetivo |
| --- | --- | --- |
| RNF-01 | Fluidez del mapa | ≥ 60 fps en gama media (p95 de frame time ≤ 16,6 ms en gestos); en pantallas de 90/120 Hz, p95 ≤ 11,1/8,3 ms en gama alta |
| RNF-02 | Arranque en frío hasta mapa interactivo | ≤ 1 s en gama media-alta; ≤ 2 s en gama baja |
| RNF-03 | Funciona sin Google Play Services | Todas las funciones de la v1, en ROM chinas, LineageOS sin GApps, GrapheneOS y microG |
| RNF-04 | Compatible con F-Droid | Sin dependencias propietarias en el sabor base; sin anti-features |
| RNF-05 | Fiabilidad de navegación en segundo plano | Servicio en primer plano con tipo `location`; guía y detección de exclusión de ahorro de batería en ROM agresivas |
| RNF-06 | Privacidad | Cero telemetría; sin informes de fallos remotos (solo registro local exportable a mano); los logs no guardan ubicaciones por defecto |
| RNF-07 | Compatibilidad | `minSdk` 26 (propuesto), target SDK = última estable, ABI arm64-v8a obligatoria |
| RNF-08 | Batería | Medir en el spike y fijar umbral; GPS a 1 Hz en navegación y sin wake locks innecesarios |
| RNF-09 | Tamaño | App sin datos < 100 MB (objetivo) |
| RNF-10 | Licencias | GPLv3; compatibilidad de cada dependencia registrada en `LICENSES.md` |
| RNF-11 | Calidad | Tests unitarios (parsers de enlaces, importadores, sync), tests de navegación con rutas simuladas, CI y Baseline Profiles |
| RNF-12 | Accesibilidad e idioma | Tamaño de fuente del sistema, contraste, TalkBack básico; español e inglés con cadenas externalizadas |
