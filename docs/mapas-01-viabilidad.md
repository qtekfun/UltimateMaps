# 01 · Estudio de viabilidad

Fecha: 2026-10-06. Los datos externos se comprobaron ese día; los marcados con [verificar] hay que confirmarlos durante el spike.

## Veredicto

**Es factible**, con una condición: la dificultad no está en la app sino en los datos y en el motor de navegación. Con todo en el dispositivo y sin tráfico, desaparecen los problemas legales con Google y Waze y el discurso de privacidad queda limpio. Lo que queda es:

1. Conseguir un buen motor de mapa, búsqueda y routing que funcione offline en móvil.
2. Tener datos de todo el mundo, descargables por regiones, y actualizados.
3. Una UI muy cuidada y fluida encima.

## Qué es claramente viable

- **Mapa vectorial offline con estilo propio.** Dos vías: motor de CoMaps (C++) o MapLibre Native con teselas PMTiles/MBTiles.
- **Routing offline en el móvil.** Hay dos motores serios: el de CoMaps (sobre sus archivos de mapa) y Valhalla (teselas jerárquicas con perfiles de coche, moto, bici y peatón).
- **Abrir enlaces de mapas.** `geo:` es trivial; los enlaces de Google Maps, Apple Maps y Waze se pueden parsear (ver RF-11). Los enlaces cortos requieren seguir una redirección en red, por lo que será un ajuste opcional.
- **Listas, GPX/KML y sync por WebDAV.** Es trabajo conocido y de bajo riesgo.

## Lo difícil

| Área | Por qué es difícil | Mitigación |
| --- | --- | --- |
| Datos mundiales | Generar y alojar mapa, routing y búsqueda del planeta es un proyecto aparte | Reutilizar los datos de CoMaps en el spike; decidir después |
| Búsqueda offline | Las fuentes abiertas no traen un índice listo para móvil salvo el de CoMaps | Usar el de CoMaps o construir uno propio (SQLite FTS5) |
| Navegación giro a giro | Voz, recálculo, carriles, límites de velocidad y fiabilidad en segundo plano | Reutilizar la lógica de CoMaps o Valhalla; pruebas con rutas simuladas |
| Rutas con curvas (moto) | Ningún motor candidato lo trae de serie | Perfil propio o puntuación de sinuosidad sobre alternativas (evaluar en el spike) |
| 60/120 fps con estilo cuidado | Los estilos cargados bajan los fps | Medir en el spike y diseñar el estilo pensando en rendimiento |
| Móviles sin GMS | Faltan servicios que se dan por hechos (TTS, ubicación por red, ahorro de batería agresivo en ROM chinas) | Ver `mapas-03-arquitectura.md`, sección «Diseño sin GMS» |

## Fuentes de datos abiertas

| Opción | Qué cubre | Licencia / condiciones | Notas |
| --- | --- | --- | --- |
| **CoMaps (.mwm)** | Mapa, búsqueda y routing, mundo entero | Código Apache-2.0 [verificar]; datos de OSM (ODbL) | Descarga por regiones desde varios nodos de su CDN. Publican herramientas para montar un servidor propio copiando sus archivos. Los mapas nuevos pueden ser incompatibles con versiones antiguas de la app, así que dependemos de su ritmo de cambios |
| **Protomaps (PMTiles)** | Solo mapa visual | Teselas: ODbL (producto derivado de OSM); estilos: BSD-3; la especificación PMTiles es de dominio público | Build diario del planeta (138,4 GB el 28/09/2026). Solo conservan los builds de la última semana, así que haría falta un espejo propio. La CLI extrae regiones |
| **OpenFreeMap** | Solo teselas | MIT (el proyecto) | No ofrece búsqueda ni routing. Válido como fuente online opcional y como referencia de estilos |
| **Valhalla** | Routing (y mapa-matching) | MIT | Hay apps móviles que ya lo usan con teselas por región. No encontré tilepacks planetarios gratuitos ya generados (Interline los vende por suscripción), así que habría que generarlos nosotros |
| **Mapterhorn** | Relieve (Terrarium en PMTiles) | Ver su documentación | Opcional, para sombreado y perfiles de elevación |

## Legal y privacidad

- **OSM es libre (ODbL).** Se puede usar, incluso comercialmente, con atribución visible «© OpenStreetMap contributors» y compartiendo las bases de datos derivadas. Es compatible con una app GPLv3.
- **Servidores de teselas de OSM:** su política de uso los reserva para consumo ligero; no sirven para una app con descargas masivas ni uso offline. No se usarán.
- **Código de CoMaps:** Apache-2.0 es compatible con incorporarse a un proyecto GPLv3 [verificar al montar el repo y registrar en `LICENSES.md`].
- **Google, Waze y Apple:** fuera de alcance como proveedores (sin tráfico). Solo se interpretan sus enlaces (deep links).
- **F-Droid:** exige dependencias libres en el sabor base: sin Play Services, sin Firebase, sin SDK propietarios.

## Estimaciones (a ojo, a revisar tras el spike)

- Fase 1 (visor, descargas, búsqueda, enlaces, listas): 2-3 meses a tiempo parcial.
- Con navegación decente, moto, sync y pulido: 6-12 meses. Depende sobre todo de las horas semanales y de si se parte de CoMaps o de cero.

## Riesgos principales

1. **Estilo y fps con el motor de CoMaps** (si no llega al look Apple Maps o a 120 Hz, se pasa a C).
2. **Acoplamiento al formato de CoMaps** (cambios incompatibles entre versiones de mapas).
3. **Coste de los datos mundiales** si hubiera que generarlos y alojarlos nosotros.
4. **Fiabilidad en segundo plano** en ROM chinas y dispositivos sin GMS.
5. **Importar Google Takeout:** las listas guardadas (CSV) suelen traer solo título y enlace, sin coordenadas [verificar con una exportación real]. Los favoritos con estrella sí traen coordenadas en GeoJSON.

## Fuentes consultadas (2026-10-06)

- CoMaps, servidor propio de mapas: https://www.comaps.app/support/how-can-i-host-a-custom-map-server-for-downloads/
- CoMaps, URL de descarga configurable: https://codeberg.org/comaps/comaps/issues/41
- CoMaps, versiones: https://codeberg.org/comaps/comaps/releases
- Protomaps, descargas: https://docs.protomaps.com/basemaps/downloads
- Protomaps, primeros pasos: https://docs.protomaps.com/guide/getting-started
- Protomaps, estilos y licencias: https://github.com/protomaps/styles
- OpenFreeMap: https://github.com/hyperknot/openfreemap
- Valhalla en móvil: https://github.com/valhalla/valhalla/discussions/4746
- Valhalla tilepacks (Interline): https://www.interline.io/valhalla/tilepacks/
