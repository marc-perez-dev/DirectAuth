#!/bin/bash
# Arranca el servidor con el mundo actual (para lanzar tests sueltos: node tests.js <filtro>)
source "$(dirname "$0")/common.sh"
server_up && { echo "already running"; exit 0; }
start_server
