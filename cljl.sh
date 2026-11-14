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
JSA="$CLJL_HOME/build/cljl.jsa"

if [ ! -f "$JAR" ]; then
  echo "[ClojureLite] Building cljl.jar..." >&2
  mkdir -p "$CLJL_HOME/build/classes"
  find "$CLJL_HOME/src" -name '*.java' > "$CLJL_HOME/build/sources.txt"
  javac --release 21 -d "$CLJL_HOME/build/classes" @"$CLJL_HOME/build/sources.txt"
  jar --create --file "$JAR" --main-class cljl.Main -C "$CLJL_HOME/build/classes" .
  java -XX:ArchiveClassesAtExit="$JSA" -jar "$JAR" -e "(+ 1 2)" >/dev/null 2>&1 || true
fi

if [ -f "$JSA" ]; then
  exec java ${CLJL_OPTS:-} -XX:SharedArchiveFile="$JSA" -jar "$JAR" "$@"
else
  exec java ${CLJL_OPTS:-} -jar "$JAR" "$@"
fi
