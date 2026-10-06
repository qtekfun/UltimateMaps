#!/usr/bin/env bash
# Espera a que haya $1 ficheros .mwm completos en el dispositivo (sin retener el lock entre consultas).
HERE=$(dirname "$0")
D=/sdcard/Android/data/app.comaps.fdroid/files/261004
for i in $(seq 1 ${2:-200}); do
  n=$(bash "$HERE/adbl.sh" shell "ls $D | grep -c 'mwm\$'")
  [ "$n" -ge "$1" ] && break
  sleep 10
done
echo "mwm=$n"
bash "$HERE/adbl.sh" shell "ls -l $D | grep -v mwm; du -sh $D"
