// Genera los estilos light/dark de @protomaps/basemaps (BSD-3) como plantillas para la app.
// Marcadores que sustituye la app en ejecución: @MAPDIR@ (directorio interno con sprites y glyphs)
// y @PMTILES@ (ruta del archivo .pmtiles). Sin lang: nombres locales del dato.
// Uso: node gen-map-style.mjs <dir_salida>   (con @protomaps/basemaps instalado en node_modules)
import {layers, namedFlavor} from '@protomaps/basemaps';
import fs from 'fs';
const out = process.argv[2];
fs.mkdirSync(out, {recursive: true});
for (const flavor of ['light', 'dark']) {
  const style = {
    version: 8,
    glyphs: 'file://@MAPDIR@/fonts/{fontstack}/{range}.pbf',
    sprite: `file://@MAPDIR@/sprites/${flavor}`,
    sources: {protomaps: {type: 'vector', url: 'pmtiles://file://@PMTILES@', attribution: '© OpenStreetMap contributors'}},
    layers: layers('protomaps', namedFlavor(flavor)),
  };
  fs.writeFileSync(`${out}/style-${flavor}.json`, JSON.stringify(style));
  console.log(flavor, style.layers.length, 'capas');
}
