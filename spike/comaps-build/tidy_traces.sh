#!/usr/bin/env bash
# Leaves only text in traces/: deletes PNG screenshots and discarded traces, and filters the logcats to the core lines
# (OMcore) and the bookmark manager, to avoid keeping noise or data from other apps on the shared device.
cd "$(dirname "$0")/traces" || exit 1
rm -f ./*.png 08-pan.json 08-pan.latency.txt 08-pan.layers.txt 09-pan-test.json 09-pan-frametimeline.json 11-route-mad-bcn-car-warm1.logcat.txt
for f in ./*.logcat.txt; do
  grep -E "OMcore|BookmarkManager|MwmApplication|ActivityTaskManager: Displayed app.comaps" "$f" | grep -v "GetSymbolRegion\|GetFontNames\|string_storage" > "$f.tmp" && mv "$f.tmp" "$f"
done
du -sh .
