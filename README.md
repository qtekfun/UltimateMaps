# Mapas

App Android de mapas y navegación **fuera de línea y privada**: mapa, búsqueda y rutas se calculan en el dispositivo, sin tráfico en tiempo real, sin cuentas y sin telemetría. Funciona sin Google Play Services. Software libre (GPLv3).

> **Estado: versión candidata (0.1.0-rc.1).** Es un MVP en desarrollo, no una app terminada. Mira [`CHANGELOG.md`](CHANGELOG.md) para lo que hay y lo que falta, y [`docs/mvp-plan.md`](docs/mvp-plan.md) para el plan.

## Qué hace hoy

- Mapa vectorial fuera de línea (MapLibre Native + PMTiles), tema claro y oscuro, atribución de OpenStreetMap.
- Descarga de regiones desde la pantalla «Mapas» (reanudables, verificadas con SHA-256); modo sin red.
- Búsqueda de lugares y direcciones fuera de línea (núcleo de [CoMaps](https://codeberg.org/comaps/comaps)).
- Vista previa de ruta en coche, a pie o en bici (sin guía giro a giro todavía).
- Sitios guardados y listas, con importación y exportación GPX y KML.
- Abre enlaces de Google Maps, Apple Maps, Waze y `geo:`.

## Qué no hace (todavía)

Navegación giro a giro con voz, carriles y límites de velocidad, perfil de moto y rutas con curvas, grabación de recorridos, sincronización con Nextcloud, importación de Google Takeout, Android Auto. Ver `docs/mapas-05-roadmap.md`.

## Datos

Los datos de mapas (OpenStreetMap, ODbL) se bajan por regiones desde [`UltimateMaps-data`](https://github.com/qtekfun/UltimateMaps-data). Hoy solo España. La app no se conecta a nada hasta que abres «Mapas» o descargas una región.

## Compilar

Requisitos: JDK 21, Android SDK (plataforma 37), NDK 28.2.13676358, CMake 3.31.6, Git, Python 3 y `jq`.

```sh
git clone https://github.com/qtekfun/UltimateMaps.git && cd UltimateMaps
git submodule update --init third_party/comaps
scripts/comaps-prepare.sh            # una vez: ~2 GB, necesita red y PyPI
./gradlew assembleFossDebug -Dorg.gradle.workers.max=2
./gradlew test                       # 267 tests JVM; no necesitan el submódulo
```

La compilación nativa necesita bastante RAM; con poca, usa `-Dorg.gradle.workers.max=2` y evita LTO.

## Documentación

Empieza por [`docs/mapas-README.md`](docs/mapas-README.md). Decisiones y su porqué en [`docs/decisions.md`](docs/decisions.md); resultados del spike en [`docs/spike-informe.md`](docs/spike-informe.md); cómo sacar una versión en [`RELEASING.md`](RELEASING.md); licencias de dependencias en [`LICENSES.md`](LICENSES.md); privacidad en [`PRIVACY.md`](PRIVACY.md).

## Licencia

GPL-3.0-or-later. Usa el código de CoMaps (Apache-2.0, compatible) y datos de OpenStreetMap © OpenStreetMap contributors (ODbL).
