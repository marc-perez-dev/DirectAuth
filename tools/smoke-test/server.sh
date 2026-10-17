#!/bin/bash
# Arranca el servidor de desarrollo en runs/smoke-test, sin GUI y con la consola leyendo de una
# FIFO (./console) para poder mandarle comandos. Lo usan common.sh y los tests.
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
GAME="$REPO/runs/smoke-test"
LAUNCH="$REPO/build/moddev/runServer.sh"

[ -f "$LAUNCH" ] || { echo "Missing $LAUNCH: run 'bash gradlew classes createServerLaunchScript'"; exit 1; }
mkdir -p "$GAME"
if ! grep -q '^eula=true' "$GAME/eula.txt" 2>/dev/null; then
  echo "Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and accept it with:"
  echo "  echo eula=true > '$GAME/eula.txt'"
  exit 1
fi
cp "$HERE/server.properties" "$GAME/server.properties"

# Mismo comando que el script de ModDevGradle, pero en runs/smoke-test, sin GUI y con una
# propiedad marcadora para encontrar el proceso (pgrep) sin confundirlo con otro runServer/runClient
cmd=$(sed -e "s|cd '$REPO/run'|cd '$GAME'|" \
          -e "s|exec '\([^']*/bin/java\)'|exec '\1' '-Ddirectauth.smoketest=true'|" \
          -e "s|serverRunProgramArgs.txt')\$|serverRunProgramArgs.txt' --nogui)|" "$LAUNCH")
if [[ "$cmd" != *"cd '$GAME'"* || "$cmd" != *"directauth.smoketest"* || "$cmd" != *"--nogui"* ]]; then
  echo "Unexpected format in $LAUNCH; update server.sh"
  exit 1
fi

rm -f "$HERE/console"
mkfifo "$HERE/console"
# Mantener la FIFO abierta para escritura para que el servidor no reciba EOF en la consola
exec 3<>"$HERE/console"
eval "$cmd" < "$HERE/console"
