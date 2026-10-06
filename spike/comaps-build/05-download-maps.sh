#!/usr/bin/env bash
# Descarga los .mwm de España (todas las regiones, ids de data/countries.txt) y Fiji (Oceanía) desde el CDN de CoMaps
# a /tmp/claude-1000/maps/261004 (fuera del repo).
set -u
OUT=/tmp/claude-1000/maps/261004
mkdir -p "$OUT"
BASE=https://mapgen-fi-1.comaps.app/maps/2026.06.28/261004
python3 -I - <<'EOF' > /tmp/claude-1000/maps/list.txt
import json,os
d=json.load(open(os.path.expanduser('~/repos/comaps-spike/data/countries.txt')))
def walk(n,path=()):
    yield n,path
    for c in n.get('g',[]): yield from walk(c,path+(n['id'],))
for n,p in walk(d):
    if ('Spain' in p or n['id']=='Fiji') and not n.get('g'):
        print(n['id']+'\t'+str(n['s']))
EOF
while IFS=$'\t' read -r id sz; do
  f="$OUT/$id.mwm"
  if [ -f "$f" ] && [ "$(stat -c %s "$f")" = "$sz" ]; then echo "ok $id"; continue; fi
  url="$BASE/$(python3 -I -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$id").mwm"
  curl -fL --retry 3 -sS -o "$f" "$url" && echo "downloaded $id $(stat -c %s "$f") expected $sz" || echo "FAIL $id"
done < /tmp/claude-1000/maps/list.txt
