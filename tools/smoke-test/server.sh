#!/bin/bash
# Arranca un servidor Fabric 1.21.1 de PRODUCCIÓN (Fabric Loader + Fabric API) en
# runs/smoke-test-fabric con el jar del mod (build/libs, remapeado a intermediary y con sqlite-jdbc
# dentro) y el mod de pruebas (build/testmod), sin GUI y con la consola leyendo de una FIFO
# (./console) para poder mandarle comandos. Lo usan common.sh y los tests.
# Se prueba el jar de producción y no el entorno de desarrollo porque solo ahí se comprueban el
# remapeo de los mixins a intermediary y el jar-in-jar.
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Directorio propio: runs/smoke-test es el de las ramas de NeoForge y Forge
GAME="$REPO/runs/smoke-test-fabric"
# Checkout cuyo mod se arranca (DIRECTAUTH_SRC permite probar otra versión contra el mismo mundo)
SRC="${DIRECTAUTH_SRC:-$REPO}"
# Versiones de Minecraft, Fabric Loader y Fabric API: las de gradle.properties de este checkout
prop () { sed -n "s/^$1=//p" "$REPO/gradle.properties"; }
MC=$(prop minecraft_version)
LOADER=$(prop loader_version)
FABRIC_API=$(prop fabric_api_version)
INSTALLER=$(prop fabric_server_launcher_version)
LAUNCHER="fabric-server-mc.$MC-loader.$LOADER-launcher.$INSTALLER.jar"
FABRIC_API_JAR="fabric-api-$FABRIC_API.jar"
# Java 21 (el de Minecraft 1.21.1): JAVA21 o el java del PATH
JAVA="${JAVA21:-$(command -v java)}"

[ -x "$JAVA" ] || { echo "Java 21 not found: set JAVA21=/path/to/java"; exit 1; }
# Versión mayor de Java (java.specification.version: "21", o "1.8" en las antiguas)
JAVA_MAJOR=$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
JAVA_MAJOR=${JAVA_MAJOR#1.}
[ "${JAVA_MAJOR:-0}" -ge 21 ] 2>/dev/null || { echo "Minecraft 1.21.1 needs Java 21 or newer, but $JAVA is Java ${JAVA_MAJOR:-unknown}: set JAVA21=/path/to/java"; exit 1; }
# El jar de la versión de ese checkout (build/libs puede tener los de otras versiones y loaders)
VERSION=$(sed -n 's/^mod_version=//p' "$SRC/gradle.properties")
JARS=()
for jar in "$SRC"/build/libs/directauth-fabric-*-"$VERSION".jar; do
  [ -f "$jar" ] && JARS+=("$jar")
done
[ ${#JARS[@]} -eq 1 ] || { echo "Expected one DirectAuth Fabric $VERSION jar in $SRC/build/libs: run 'bash gradlew build testmodJar' in $SRC"; exit 1; }
# Mod de pruebas (src/testmod, tarea testmodJar); las versiones anteriores no lo tienen
TESTMOD=("$SRC"/build/testmod/directauth_test-*.jar)
mkdir -p "$GAME"
if ! grep -q '^eula=true' "$GAME/eula.txt" 2>/dev/null; then
  echo "Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and accept it with:"
  echo "  echo eula=true > '$GAME/eula.txt'"
  exit 1
fi

# Instalar la primera vez: el lanzador de servidor de Fabric (descarga el servidor vanilla y las
# librerías al arrancar por primera vez) y Fabric API
if [ ! -f "$GAME/$LAUNCHER" ]; then
  curl -fsSL -o "$GAME/$LAUNCHER.part" "https://meta.fabricmc.net/v2/versions/loader/$MC/$LOADER/$INSTALLER/server/jar" \
    && mv "$GAME/$LAUNCHER.part" "$GAME/$LAUNCHER" || { echo "Could not download the Fabric server launcher"; exit 1; }
fi
mkdir -p "$GAME/mods"
if [ ! -f "$GAME/mods/$FABRIC_API_JAR" ]; then
  rm -f "$GAME"/mods/fabric-api-*.jar
  curl -fsSL -o "$GAME/mods/$FABRIC_API_JAR.part" "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API/+/%2B}/fabric-api-${FABRIC_API/+/%2B}.jar" \
    && mv "$GAME/mods/$FABRIC_API_JAR.part" "$GAME/mods/$FABRIC_API_JAR" || { echo "Could not download Fabric API $FABRIC_API"; exit 1; }
fi

# "./server.sh install" solo instala (common.sh lo llama antes de arrancar en segundo plano)
[ "$1" = install ] && exit 0

cp "$HERE/server.properties" "$GAME/server.properties"
rm -f "$GAME"/mods/directauth*.jar
cp "${JARS[0]}" "$GAME/mods/"
[ -f "${TESTMOD[0]}" ] && cp "${TESTMOD[0]}" "$GAME/mods/"

rm -f "$HERE/console"
mkfifo "$HERE/console"
# Mantener la FIFO abierta para escritura para que el servidor no reciba EOF en la consola
exec 3<>"$HERE/console"
cd "$GAME" || exit 1
# Propiedad marcadora para encontrar el proceso (pgrep) sin confundirlo con otro servidor, y el
# servidor de sesión falso de lib.js (premium()) en lugar del de Mojang (authlib necesita las dos
# propiedades; la de servicios sigue apuntando a Mojang)
exec "$JAVA" -Ddirectauth.smoketest.fabric=true -Xmx2G \
  -Dminecraft.api.session.host=http://127.0.0.1:25580 -Dminecraft.api.services.host=https://api.minecraftservices.com \
  -jar "$LAUNCHER" nogui < "$HERE/console"
