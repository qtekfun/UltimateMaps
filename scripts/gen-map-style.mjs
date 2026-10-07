// Generates the light/dark styles from @protomaps/basemaps (BSD-3) as templates for the app.
// Placeholders the app substitutes at runtime: @MAPDIR@ (internal directory with sprites and glyphs)
// and @PMTILES@ (path of the .pmtiles file). Label language: second argument (default `es`; if
// name:es is missing, the local name is used). NOTE: without `lang`, basemaps does NOT generate label (symbol) layers.
// Usage: node gen-map-style.mjs <output_dir> [lang]   (with @protomaps/basemaps installed in node_modules)
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
