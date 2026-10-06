# Proyecto Mapas (nombre provisional) — Paquete de viabilidad y especificación

Fecha: 2026-10-06 · Estado: viabilidad y entrevista cerradas, spike pendiente

## En una frase

App Android de mapas y navegación que funciona 100 % en el dispositivo (mapa, búsqueda y rutas), sin tráfico ni telemetría, con una interfaz muy cuidada estilo Apple Maps y muy fluida. Es GPLv3, se distribuye por F-Droid/GitHub, funciona sin Google Play Services y aprovecha GMS cuando existe.

## Contenido del paquete

| Archivo | Para qué sirve |
| --- | --- |
| `mapas-README.md` | Este índice, decisiones cerradas y preguntas abiertas |
| `mapas-01-viabilidad.md` | Estudio de viabilidad: qué es factible, riesgos, fuentes de datos y licencias |
| `mapas-02-requisitos.md` | Requisitos funcionales y no funcionales con criterios medibles |
| `mapas-03-arquitectura.md` | Arquitectura, opciones A/B/C, diseño sin GMS y diseño de rendimiento |
| `mapas-04-spike.md` | Plan del spike (fase 0): qué se mide, con qué umbrales y cómo se decide |
| `mapas-05-roadmap.md` | Fases, estimaciones orientativas y registro de riesgos |
| `mapas-ci.yml` | Copiar al repo como `.github/workflows/ci.yml` (la puerta del merge automático) |

## Decisiones cerradas

- **Plataforma:** solo Android (Kotlin + Compose para la UI, C++ vía NDK para el núcleo). Android Auto, más adelante.
- **Todo en el dispositivo:** mapa, búsqueda y routing sin servidor. Sin tráfico ni proveedores de Google, Waze o Apple.
- **Fluidez:** mapa a 60/120 fps y arranque instantáneo.
- **Uso:** coche, moto y a pie/bici.
- **Imprescindibles para dejar Google Maps:** indicaciones de carril, límites de velocidad, y guardar sitios y listas.
- **Moto:** evitar autopistas y peajes, pantalla siempre visible y usable con guantes, grabar y exportar el recorrido, rutas con curvas.
- **Estética:** estilo Apple Maps.
- **Licencia y distribución:** GPLv3, F-Droid y GitHub.
- **Datos:** OpenStreetMap, mundo entero con regiones descargables y configurables. El origen concreto de los datos es indiferente.
- **Sitios y listas:** locales, con importación/exportación GPX y KML, sync opcional Nextcloud/WebDAV e importación desde Google Takeout.
- **Enlaces:** abrir enlaces de Google Maps, Apple Maps, Waze y `geo:`.
- **GMS:** nunca obligatorio; si el dispositivo lo tiene, se aprovecha sin depender de él.
- **Spike:** empezar por lo menos costoso (CoMaps sin modificar) y reemplazar si no convence.
- **Git:** El agente hace push de ramas, abre las PR y las mergea (squash) cuando los checks de CI pasan. La protección de rama de GitHub es la garantía real de que solo se mergea en verde.

## Preguntas abiertas

1. Nombre de la app e identificador de paquete (por ejemplo `com.qtekfun.<nombre>`).
2. `minSdk`: se propone 26 (Android 8.0) hasta tener datos de los dispositivos de prueba.
3. Diseño de la sincronización con Nextcloud: archivos GPX/JSON en WebDAV (propuesto) o la API de Nextcloud Maps.
4. Cómo implementar las rutas con curvas, que no vienen de serie en ningún motor candidato (se evalúa en el spike).
5. Alternativa de voz cuando el dispositivo no tiene motor TTS (frecuente sin GMS).
6. Alojamiento de los datos de mapas tras el spike (CDN de terceros o espejo propio).
7. Cuándo abordar Android Auto y con qué política de dependencias para F-Droid.

## Cómo usar el paquete

1. Crea el repositorio y copia estos archivos a `docs/` (los `mapas-0x-*.md`).
