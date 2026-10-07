# Prueba de gasolineras en el Pixel 8 (2026-10-07, APK de depuración, con permiso del usuario)

Cadena probada con el servicio real del Ministerio, tocando la pantalla (`adb input`), sin ubicación del usuario:

| Paso | Resultado | Captura |
| --- | --- | --- |
| Engranaje → Ajustes → Privacidad: modo sin red, catálogo y «Conexiones posibles» (github y 2 hosts de descarga permitidos, 3 desactivados) | Correcto | 01 |
| Activar gasolineras: diálogo con qué se pide, a qué servidor, qué ve (IP y combustibles) y que la ubicación no se envía | Correcto | 02 |
| Marcar GLP y Gasóleo A → descarga real; elegir cuál se ve en el mapa; frecuencia; URL; atribución; «Última actualización: 12:49» y «Precios actualizados» | Correcto | 03 |
| Mapa: surtidor con «1,145 €» (GLP) | Correcto | 04 |
| Tocar la gasolinera: ficha con marca, dirección, horario, GLP «(en el mapa)» y Gasóleo A, fuente y «no oficial», Ir / Guardar | Correcto | 04 |
| Ir → elegir salida tocando el mapa → ruta a la gasolinera: 1,7 km, 7 min, 492 ms (`UMROUTE result=ok`) | Correcto | 05 |
| Mapa alejado: varias gasolineras con precio; otra ficha, ahora con «Añadir parada» | Correcto | 06 |
| Añadir parada → «Paradas (1 de 5) · 1. REPSOL» con subir/bajar/quitar; ruta recalculada 15,3 km, 39 min, 452 ms (`stops=1`) | Correcto | 07 |

## Observaciones
- Con el mapa alejado, una nota «Lugar · Abierto desde un enlace de mapa» queda encima del panel de ruta (estorba un poco; solo ocurre tras abrir un enlace `geo:`).
- Solo se vio GLP (pocas estaciones: salen 1-4 por pantalla en Madrid); no se probó gasolina 95 (más de 10.000 estaciones), así que el coste del índice y del dibujado con muchas estaciones sigue sin medir.
- Sin probar aún: quitar/reordenar paradas, «Guardar» la gasolinera, modo oscuro/claro de la capa, actualización en segundo plano al volver a la app, y el modo sin red bloqueando la descarga.

## Gasolina 95 E5 (la mayor: unas 11.000 estaciones), misma sesión
- Descarga real y caché de ~0,9 MB; en Madrid a zoom 13 salen ~10 estaciones con precio, espaciadas (por debajo de zoom 14 se deja la más barata de cada celda) (captura 08).
- Memoria del proceso principal: 281 MB PSS antes de dibujarla → 366 MB PSS con la capa dibujada (`dumpsys meminfo`).
- `dumpsys gfxinfo`: 353 fotogramas, 14 con tirones (3,97 %), acumulado desde el arranque (incluye la carga); no es una medida de fluidez de gesto.
- **Detalles a pulir:** (1) con el panel inferior translúcido se transparentan los precios de abajo y ensucian el texto del panel; (2) «Última actualización» muestra 12:49 aunque 95 E5 se bajó a las 13:01 (probablemente el más antiguo de los combustibles): puede confundir.
