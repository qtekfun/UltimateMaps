# Licencias de dependencias

Proyecto bajo GPLv3. Toda dependencia debe ser compatible y constar aquí antes de añadirse.

| Dependencia | Versión | Licencia | Compatible con GPLv3 | Uso |
|---|---|---|---|---|
| kotlinx-serialization-json | 1.11.0 | Apache-2.0 | Sí | `:core-geo`, lectura de Takeout GeoJSON |
| kXML2 (`net.sf.kxml:kxml2`, incluye `org.xmlpull`) | 2.3.0 | BSD-style / dominio público (xmlpull) | Sí | Solo `compileOnly` y tests de `:core-geo`: en Android `org.xmlpull.v1` ya lo aporta la plataforma, no se empaqueta |
| androidx.sqlite `sqlite` (interfaces `SQLiteDriver`, etc.) | 2.7.0 | Apache-2.0 | Sí | `:core-data` (API); en Android se aportará `sqlite-framework` (Apache-2.0, usa el SQLite de la plataforma) |
| androidx.sqlite `sqlite-bundled` (incluye SQLite, dominio público) | 2.7.0 | Apache-2.0 + SQLite dominio público | Sí | Solo tests JVM de `:core-data` (no se distribuye) |
| Kotlin stdlib | 2.4.20 | Apache-2.0 | Sí | Todos los módulos |
| JUnit Jupiter / Platform | 6.1.3 | EPL-2.0 | Sí (solo tests, no se distribuye) | Tests |
| MapLibre Native Android (`org.maplibre.gl:android-sdk`) | 13.6.1 | BSD-2-Clause | Sí | `:app`, render del mapa y lectura PMTiles local |
| AndroidX Compose (BOM) y Activity Compose | 2026.09.00 / 1.13.0 | Apache-2.0 | Sí | `:app`, UI (solo `ui` y `foundation`; sin Material) |
| `@protomaps/basemaps` | 5.7.2 | BSD-3-Clause | Sí | Solo en desarrollo: `scripts/gen-map-style.mjs` genera los estilos light/dark incluidos en `app/src/main/assets/map/` |
| Sprites y fuentes de `basemaps-assets` (Protomaps; Noto Sans, SIL OFL 1.1) | v4 | BSD-3 (sprites) / OFL-1.1 (fuentes) | Sí | Aún NO empaquetados: `scripts/fetch-map-assets.sh` los trae al preparar el repo (pendiente de ejecutar) |
| Robolectric | 4.17 | MIT | Sí (solo tests, no se distribuye) | Tests de UI en el PC |
| JUnit 4 | 4.13.2 | EPL-2.0 | Sí (solo tests, no se distribuye) | Tests de `:app` (requerido por Robolectric y Compose test) |
| Datos de OpenStreetMap | n/a | ODbL 1.0 | Sí (datos, no código; atribución visible, RF-13) | Teselas PMTiles locales |

Sin `play-services-*`, Firebase ni SDK propietarios.
