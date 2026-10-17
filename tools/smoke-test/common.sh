# Funciones comunes de los scripts del smoke test (se cargan con source)
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
GAME="$REPO/runs/smoke-test"
cd "$HERE" || exit 1

# El patrón con corchetes no se encuentra a sí mismo en la línea de comandos de pgrep
server_up () { pgrep -f '[d]irectauth.smoketest' >/dev/null; }

# Espera a que el servidor se apague. Si se cuelga, guarda un volcado de hilos, lo mata y falla
# (para que el resto de la pasada pueda seguir)
wait_down () {
  for _ in $(seq 1 60); do server_up || return 0; sleep 1; done
  local pid dump
  pid=$(pgrep -f '[d]irectauth.smoketest' | head -1)
  dump="hang-$(date +%Y%m%d-%H%M%S).txt"
  "$(dirname "$(readlink "/proc/$pid/exe")")/jstack" "$pid" > "$dump" 2>&1
  kill -9 "$pid"
  sleep 2
  echo "server did not stop in 60 s: thread dump saved to $dump, process killed"
  return 1
}

# Arranca el servidor en segundo plano y espera a RCON. Guarda el log anterior en server-prev.out
# y lo acumula en server-all.out
start_server () {
  ./server.sh install || return 1   # la primera vez descarga e instala Forge (varios minutos)
  if [ -f server.out ]; then cat server.out >> server-all.out; mv server.out server-prev.out; fi
  setsid nohup ./server.sh > server.out 2>&1 < /dev/null &
  for i in $(seq 1 180); do
    grep -q -a 'RCON running' server.out && return 0
    if [ "$i" -gt 5 ] && ! server_up; then echo "server did not start:"; tail -20 server.out; return 1; fi
    sleep 1
  done
  echo "server did not start in time"; return 1
}

stop_server () {
  if server_up; then echo stop > console; fi
  wait_down
}
