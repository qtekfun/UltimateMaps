#!/usr/bin/env bash
# Filtra las lineas del proceso de la app (ultimo pid arrancado) de un logcat -v threadtime. Uso: applog.sh <logcat> [regex]
PID=$(grep "Start proc.*app.comaps.fdroid" "$1" | tail -1 | sed 's/.*Start proc \([0-9]*\):.*/\1/')
[ -z "$PID" ] && PID=$(grep -m1 "Process .* created for app.comaps.fdroid" "$1" | sed 's/.*Process \([0-9]*\) created.*/\1/')
echo "pid=$PID" >&2
awk -v p="$PID" '$3==p' "$1" | grep -E "${2:-.}" | cut -c1-320
