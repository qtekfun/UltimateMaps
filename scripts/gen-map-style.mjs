// Genera los estilos light/dark de @protomaps/basemaps (BSD-3) como plantillas para la app.
// Marcadores que sustituye la app en ejecución: @MAPDIR@ (directorio interno con sprites y glyphs)
// y @PMTILES@ (ruta del archivo .pmtiles). Idioma de las etiquetas: segundo argumento (por defecto `es`; si
// falta name:es se usa el nombre local). OJO: sin `lang` basemaps NO genera capas de etiquetas (symbol).
// Uso: node gen-map-style.mjs <dir_salida> [lang]   (con @protomaps/basemaps instalado en node_modules)
import {layers, namedFlavor} from '@protomaps/basemaps';
import fs from 'fs';
const out = process.argv[2];
const lang = process.argv[3] ?? 'es';
fs.mkdirSync(out, {recursive: true});
for (const flavor of ['light', 'dark']) {
  const style = {
    version: 8,
    glyphs: 'file://@MAPDIR@/fonts/{fontstack}/{range}.pbf',
    sprite: `file://@MAPDIR@/sprites/${flavor}`,
    sources: {protomaps: {type: 'vector', url: 'pmtiles://file://@PMTILES@', attribution: '© OpenStreetMap contributors'}},
    layers: layers('protomaps', namedFlavor(flavor), {lang}),
  };
  fs.writeFileSync(`${out}/style-${flavor}.json`, JSON.stringify(style));
  console.log(flavor, style.layers.length, 'capas');
}
