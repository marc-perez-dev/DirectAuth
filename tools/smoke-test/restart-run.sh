#!/bin/bash
# Pruebas que reinician el servidor: parar con un /login en vuelo y arrancar con ficheros rotos.
# Espera el servidor ya arrancado (run-all.sh se encarga).
source "$(dirname "$0")/common.sh"

fail=0
node restart.js before-A || exit 1   # para el servidor ella misma
wait_down || exit 1
start_server || exit 1
node restart.js after-A || fail=1
stop_server || exit 1
node restart.js before-B || exit 1
start_server || exit 1
node restart.js after-B || fail=1
exit $fail
