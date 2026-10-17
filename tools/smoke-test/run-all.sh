#!/bin/bash
# Pasada completa sobre un mundo limpio: compila, tests en caliente, reinicios y locale turco.
# Sale con código distinto de 0 si falla algún test.
source "$(dirname "$0")/common.sh"

[ -d node_modules ] || npm ci || exit 1
(cd "$REPO" && bash gradlew build -q -Dorg.gradle.jvmargs=-Xmx2G) || exit 1

stop_server || fail=1
rm -f server.out server-prev.out server-all.out restart-state.json
rm -rf "$GAME/world"

fail=0
start_server || exit 1
echo "=== Live tests"
node tests.js || fail=1
echo "=== Restart tests"
./restart-run.sh || fail=1
if [ -n "$DOWNGRADE_REF" ]; then
  # Volver a una versión anterior (p. ej. DOWNGRADE_REF=main o la última release) y regresar
  echo "=== Downgrade to $DOWNGRADE_REF and back"
  OLD="$(mktemp -d)/directauth-old"
  trap 'git -C "$REPO" worktree remove --force "$OLD" 2>/dev/null' EXIT
  git -C "$REPO" worktree add --detach -q "$OLD" "$DOWNGRADE_REF" || exit 1
  (cd "$OLD" && bash gradlew build -q -Dorg.gradle.jvmargs=-Xmx2G) || exit 1
  stop_server || fail=1
  DIRECTAUTH_SRC="$OLD" start_server || exit 1
  node restart.js downgrade || fail=1
  stop_server || fail=1
  start_server || exit 1
  node restart.js re-upgrade || fail=1
fi

echo "=== Turkish locale"
stop_server || fail=1
JAVA_TOOL_OPTIONS="-Duser.language=tr -Duser.country=TR" start_server || exit 1
node restart.js tr || fail=1
stop_server
cat server.out >> server-all.out

# Los tests provocan algunos errores a propósito (migración sobre una carpeta de solo lectura,
# configs y ficheros corruptos); el resto merece un vistazo
echo "=== DirectAuth warnings/errors in the server log (some are expected, see README)"
grep -a -E 'DirectAuth|directauth' server-all.out | grep -a -E '/ERROR|/WARN|Exception' \
  | grep -a -v -E 'refmap|Invalid session attempt|runs/smoke-test/libraries' | cut -c1-200 | sed -E 's/^\[[0-9:]+\] //' | sort | uniq -c | sort -rn
echo "=== Exceptions (any source)"
grep -a -E 'Exception|Caused by' server-all.out | grep -a -v DEBUG | cut -c1-200 | sed -E 's/^\[[0-9:]+\] //' | sort | uniq -c | sort -rn | head -20

[ $fail -eq 0 ] && echo "ALL PASSED" || echo "SOME TESTS FAILED"
exit $fail
