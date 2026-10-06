import {layers, namedFlavor} from '@protomaps/basemaps';
import fs from 'fs';
const [,, out, glyphs, sprite, tiles] = process.argv;
const style = {version:8, glyphs, sprite,
 sources:{protomaps:{type:'vector', url:tiles, attribution:'© OpenStreetMap, Protomaps'}},
 layers: layers('protomaps', namedFlavor('light'), {lang:'es'})};
fs.writeFileSync(out, JSON.stringify(style));
console.log(out, style.layers.length, 'capas');
