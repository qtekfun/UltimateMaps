# Changelog

Los cambios relevantes se listan aquí. El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones siguen [SemVer](https://semver.org/lang/es/).

## [Unreleased]

### Añadido

- Voz de navegación (texto es/en, km/mi, cola con prioridades, enfoque de audio con atenuación, guía para instalar un motor TTS libre sin GMS) y sección «Navegación» en Ajustes (voz, volumen, unidades, idioma, evitar por defecto). Sin probar en dispositivo.

## [0.1.0-rc.1] - pendiente

Primera versión candidata: un MVP en desarrollo (ver `docs/mvp-plan.md`), no una app terminada. Solo España.

### Añadido

- Mapa vectorial fuera de línea (MapLibre Native + PMTiles) con tema claro y oscuro, etiquetas e iconos, y atribución de OpenStreetMap siempre visible.
- Descarga de regiones desde «Mapas»: lista jerárquica, descargas reanudables verificadas con SHA-256, pausar, reanudar, borrar y actualizar, servicio en primer plano y modo sin red. El catálogo por defecto está en `UltimateMaps-data`.
- Búsqueda de lugares y direcciones fuera de línea con el núcleo de CoMaps, con ficha (guardar, ruta, compartir).
- Vista previa de ruta en coche, a pie o en bici, con opciones de evitar autopistas, peajes, ferris y caminos sin asfaltar (sin guía giro a giro).
- Sitios guardados y listas, con importación y exportación GPX y KML y copia de seguridad.
- Apertura de enlaces de Google Maps, Apple Maps, Waze y `geo:`; los enlaces cortos no hacen ninguna petición de red.
- Ubicación sin Google Play Services.
- **Pantalla de Ajustes** (engranaje en el mapa): privacidad (modo sin red, catálogo, lista de conexiones posibles) y gasolineras.
- **Gasolineras y precios** (opcional, apagado por defecto): descarga solo los combustibles que marques (GLP, gasolinas, gasóleos, GNC…), muestra el precio del combustible elegido sobre cada gasolinera, ficha al tocarla con **Ir** y **Añadir parada** (hasta 5), y atribución al Ministerio. La ubicación nunca sale del dispositivo.
- Ruta con paradas intermedias.
- Buscador en la lista de mapas y panel inferior más fácil de arrastrar.
- El motor de búsqueda y rutas corre en un proceso aparte: si falla, la app sigue.
- Cadenas en español e inglés.

### Limitaciones conocidas

- **Velocidad:** la búsqueda tarda de 0,5 a 4 s por consulta en un Pixel 8 con 7 regiones (el objetivo era 0,1 s). La ruta larga Madrid–Barcelona no se ha podido calcular con las 7 regiones probadas.
- Sin navegación giro a giro, voz, carriles ni límites de velocidad; sin perfil de moto.
- Solo se dibuja bien la región cuyo PMTiles está instalado; con muchas regiones el rendimiento del mapa no está medido.
- Probado solo en un dispositivo (gama alta, con GMS). Sin probar: ROM chinas, dispositivos sin Google, gama media.
- El APK de la release no está minificado.
