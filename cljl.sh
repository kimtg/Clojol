#!/usr/bin/env bash
set -euo pipefail

SOURCE="${BASH_SOURCE[0]}"
while [ -L "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
CLJL_HOME="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"

JAR="$CLJL_HOME/build/cljl.jar"
AOT="$CLJL_HOME/build/cljl.aot"
JSA="$CLJL_HOME/build/cljl.jsa"

if [ ! -f "$JAR" ]; then
  "$CLJL_HOME/make.sh" build >&2
fi

if [ -f "$AOT" ]; then
  exec java ${CLJL_OPTS:-} -XX:AOTCache="$AOT" -jar "$JAR" "$@"
elif [ -f "$JSA" ]; then
  exec java ${CLJL_OPTS:-} -XX:SharedArchiveFile="$JSA" -jar "$JAR" "$@"
else
  exec java ${CLJL_OPTS:-} -jar "$JAR" "$@"
fi
