#!/bin/bash
# Arranca un servidor Forge 1.20.1 de PRODUCCIÓN en runs/smoke-test con el jar del mod
# (build/libs, reobfuscado a SRG y con sqlite-jdbc en JarJar), sin GUI y con la consola leyendo
# de una FIFO (./console) para poder mandarle comandos. Lo usan common.sh y los tests.
# Se prueba el jar de producción y no el entorno de desarrollo porque solo ahí se comprueban el
# refmap de los mixins, el manifiesto (MixinConfigs) y el JarJar.
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
GAME="$REPO/runs/smoke-test"
# Checkout cuyo mod se arranca (DIRECTAUTH_SRC permite probar otra versión contra el mismo mundo)
SRC="${DIRECTAUTH_SRC:-$REPO}"
FORGE="1.20.1-47.4.0"
ARGS="libraries/net/minecraftforge/forge/$FORGE/unix_args.txt"
# Java 17 (el de Minecraft 1.20.1): JAVA17 o el que descarga el toolchain de Gradle
JAVA="${JAVA17:-$(ls -d "$HOME"/.gradle/jdks/*-17-*/ 2>/dev/null | grep -v '\.lock' | head -1)bin/java}"

[ -x "$JAVA" ] || { echo "Java 17 not found: set JAVA17=/path/to/java"; exit 1; }
JARS=("$SRC"/build/libs/directauth-*.jar)
[ ${#JARS[@]} -eq 1 ] && [ -f "${JARS[0]}" ] || { echo "Expected one jar in $SRC/build/libs: run 'bash gradlew build' in $SRC"; exit 1; }
mkdir -p "$GAME"
if ! grep -q '^eula=true' "$GAME/eula.txt" 2>/dev/null; then
  echo "Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and accept it with:"
  echo "  echo eula=true > '$GAME/eula.txt'"
  exit 1
fi

# Instalar el servidor Forge la primera vez (librerías y servidor vanilla parcheado)
if [ ! -f "$GAME/$ARGS" ]; then
  installer="$GAME/forge-$FORGE-installer.jar"
  curl -fsSL -o "$installer" "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar" || exit 1
  (cd "$GAME" && "$JAVA" -jar "$installer" --installServer > forge-install.log 2>&1) || { echo "Forge install failed, see $GAME/forge-install.log"; exit 1; }
  rm -f "$installer"
fi

# "./server.sh install" solo instala (common.sh lo llama antes de arrancar en segundo plano)
[ "$1" = install ] && exit 0

cp "$HERE/server.properties" "$GAME/server.properties"
mkdir -p "$GAME/mods"
rm -f "$GAME"/mods/directauth*.jar
cp "${JARS[0]}" "$GAME/mods/"

rm -f "$HERE/console"
mkfifo "$HERE/console"
# Mantener la FIFO abierta para escritura para que el servidor no reciba EOF en la consola
exec 3<>"$HERE/console"
cd "$GAME" || exit 1
# Propiedad marcadora para encontrar el proceso (pgrep) sin confundirlo con otro servidor
exec "$JAVA" -Ddirectauth.smoketest=true -Xmx2G "@$ARGS" nogui < "$HERE/console"
