#!/usr/bin/env bash
# Espera (sin retener el lock) a que aparezca en el dispositivo un fichero que termine en $1 dentro de la carpeta de mapas
HERE=$(dirname "$0")
D=/sdcard/Android/data/app.comaps.fdroid/files/261004
for i in $(seq 1 ${2:-60}); do
  o=$(bash "$HERE/adbl.sh" shell "ls -l $D" | tr '\n' ' ')
  case "$o" in *"$1.mwm "*) break;; esac
  sleep 5
done
echo "$o"
