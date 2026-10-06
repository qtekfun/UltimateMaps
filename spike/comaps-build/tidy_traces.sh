#!/usr/bin/env bash
# Deja en traces/ solo texto: borra capturas PNG y trazas descartadas, y filtra los logcat a las lineas del nucleo
# (OMcore) y del gestor de marcadores, para no guardar ruido ni datos de otras apps del dispositivo compartido.
cd "$(dirname "$0")/traces" || exit 1
rm -f ./*.png 08-pan.json 08-pan.latency.txt 08-pan.layers.txt 09-pan-test.json 09-pan-frametimeline.json 11-route-mad-bcn-car-warm1.logcat.txt
for f in ./*.logcat.txt; do
  grep -E "OMcore|BookmarkManager|MwmApplication|ActivityTaskManager: Displayed app.comaps" "$f" | grep -v "GetSymbolRegion\|GetFontNames\|string_storage" > "$f.tmp" && mv "$f.tmp" "$f"
done
du -sh .
