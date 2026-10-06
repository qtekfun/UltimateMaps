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

Sin `play-services-*`, Firebase ni SDK propietarios.
