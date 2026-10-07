# Prueba del APK de release 0.1.0-rc.1 en el Pixel 8 (2026-10-07, con permiso del usuario)

APK: `app-foss-release`, firmado con la clave de **depuración** solo para poder instalarlo sobre la app existente (no es la clave del proyecto). Datos en el móvil: `World.mwm`, `WorldCoasts.mwm` y 7 regiones `.mwm` (Madrid, Castilla-La Mancha, Aragón, Castilla y León Este, Lleida, Barcelona, Tarragona) y el PMTiles de Madrid; copiados antes con `run-as`. **No se probó la descarga desde la app.**

## Resultados (medidos en el dispositivo, logs `UMSEARCH`, `UMROUTE`, `UMCORE`)

| Prueba | Resultado |
| --- | --- |
| Arranque en frío (`am start -W`) | 474-617 ms |
| Mapa con etiquetas e iconos (sprites y glyphs empaquetados) | Funciona (se vieron calles, comercios y POI con icono) |
| Arranque del núcleo nativo | **Falló 2 veces antes de funcionar** (ver abajo); después `engine_ready_ms=215` con 7 regiones |
| Búsqueda «calle mayor» (primera, en frío) | 20 resultados reales, 1801 ms |
| Búsquedas en caliente (consulta completa tecleada de una vez) | 484, 779, 1045, 3967, 4201 y 3898 ms (n=6). Umbral: 100 ms → **no cumple** |
| Búsqueda entre regiones («placa catalunya barcelona» desde Madrid) | Resultados de Barcelona correctos |
| Ficha, botones Guardar / Ruta / Compartir, panel de ruta | Se muestran; el panel pide ubicación o salida |
| Ruta Madrid (Atocha) → Barcelona (Plaça Catalunya), coche | `route_not_found` en 549 ms. **No se pudo medir el umbral de 2 s**; sin caída |

## Fallos encontrados y corregidos (en `master`)

1. `CoMaps init: File not found drules_proto_walking_light.bin` (primer intento, banco de pruebas debug).
2. `SIGABRT` en `NativeCore.init` con la release: el núcleo no escribía ningún log ni mensaje de `CHECK`. Se enlazaron el log y los `CHECK` de CoMaps a logcat (etiqueta `UMCORE`) y apareció: «Invalid type» para **todas** las categorías, y luego `CHECK((groups.empty() || !types.empty()))`.
3. **Causa:** `classificator::Load()` llena el clasificador del *estilo de carga* (sin inicializar, `WalkingLight`), pero `classif()` consulta el del *estilo actual* (`DefaultLight` en Android): todos los tipos salían inválidos. Corrección: `GetStyleReader().SetCurrentStyle(kDefaultMapStyle)` y generar `drules_proto_default_light.bin` (antes del estilo `vehicle`, que debe ir último).

## Sin resolver

- **Búsqueda 5-40 veces por encima del umbral de 100 ms** (R12), con 7 regiones y consultas largas.
- **`route_not_found` Madrid–Barcelona**: hipótesis (no comprobada) de que el router necesita regiones vecinas que no están instaladas; en el spike, con las 25 regiones, esa ruta se calculó en ≈ 18 s.
- Sin probar: descarga de regiones desde la app, guardar sitios, importar GPX/KML, ruta corta, fluidez (fps) con etiquetas, memoria, el modo oscuro/claro, otras ROM.
- Una intervención mía quedó mal en la primera captura: tras un cierre de la app, Android devolvió el primer plano a la app de vídeo de la otra sesión. Mis toques posteriores iban dirigidos a mi app, pero un `input text` pudo llegar a la suya. Desde entonces cada paso comprueba que mi app está delante antes de enviar toques o texto.
