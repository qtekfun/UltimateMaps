#!/usr/bin/env bash
# Rutas de prueba (proceso en frio cada vez). Salidas: traces/12-*.logcat.txt/.png
H=$(dirname "$0")
T=$H/traces
SOL=40.4168,-3.7038
BERN=40.4531,-3.6883
PCAST=40.4663,-3.6890
for i in 1 2 3; do bash $H/route_probe.sh vehicle $SOL $PCAST $T/12-urban-car-5km-r$i 15; done
for i in 1 2 3; do bash $H/route_probe.sh pedestrian $SOL $BERN $T/12-urban-foot-r$i 15; done
for i in 1 2 3; do bash $H/route_probe.sh bicycle $SOL $BERN $T/12-urban-bike-r$i 15; done
# Guadarrama (Navacerrada -> Cercedilla): vehiculo
for i in 1 2; do bash $H/route_probe.sh vehicle 40.7864,-4.0108 40.7425,-4.0623 $T/12-guadarrama-car-r$i 20; done
