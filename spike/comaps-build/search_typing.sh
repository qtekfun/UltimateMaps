#!/usr/bin/env bash
# Teclea consultas en la UI de busqueda de CoMaps caracter a caracter (input text) y guarda logcat con las lineas
# "Search ended in N ms" / "Emitting a new batch ... ms since the search has started" del nucleo.
# Uso: search_typing.sh <prefijo salida> <consulta1> [consulta2 ...]   (espacios como %s)
P=$1; shift
exec 9>/tmp/claude-1000/device.lock
flock 9
adb shell am force-stop app.comaps.fdroid
sleep 1
adb shell "am start -W -a android.intent.action.VIEW -d 'geo:40.4168,-3.7038?z=15' app.comaps.fdroid" | grep TotalTime
sleep 5
adb shell input tap 1006 1999   # cierra hoja de lugar
sleep 1
adb logcat -c
adb shell input tap 444 2254    # boton de busqueda
sleep 2
for q in "$@"; do
  echo "### query $q" >> "$P.keys.txt"
  n=${#q}
  for ((i=0;i<n;i++)); do
    c=${q:$i:1}
    echo "key '$c' at $(adb shell date +%H:%M:%S.%N | cut -c1-12)" >> "$P.keys.txt"
    if [ "$c" = "_" ]; then adb shell input keyevent KEYCODE_SPACE; else adb shell input text "$c"; fi
    sleep 1.5
  done
  sleep 1
  # borrar el texto: 30 backspaces
  adb shell 'for i in $(seq 1 30); do input keyevent KEYCODE_DEL; done'
  sleep 2
done
adb logcat -d -v threadtime > "$P.logcat.txt"
adb exec-out screencap -p > "$P.png"
