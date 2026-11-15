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

JVM_OPTS=()
APP_ARGS=()
parsing_clj=1
while [ $# -gt 0 ]; do
  arg="$1"
  if [ "$parsing_clj" -eq 1 ]; then
    case "$arg" in
      --)
        parsing_clj=0
        APP_ARGS+=("$arg")
        ;;
      -J)
        if [ $# -ge 2 ]; then
          JVM_OPTS+=("$2")
          shift
        else
          APP_ARGS+=("$arg")
        fi
        ;;
      -J*)
        JVM_OPTS+=("${arg#-J}")
        ;;
      -Sdeps|-Scp|-Sdeps-file|-Scommand|-Sthreads)
        APP_ARGS+=("$arg")
        if [ $# -ge 2 ]; then
          shift
          APP_ARGS+=("$1")
        fi
        ;;
      -S*|-P|-version|--version|-h|-\?|--help)
        APP_ARGS+=("$arg")
        ;;
      -A*|-M*|-X*|-T*)
        parsing_clj=0
        APP_ARGS+=("$arg")
        ;;
      *)
        parsing_clj=0
        APP_ARGS+=("$arg")
        ;;
    esac
  else
    APP_ARGS+=("$arg")
  fi
  shift
done

if [ -f "$JSA" ]; then
  exec java ${CLJL_OPTS:-} ${JVM_OPTS[@]+"${JVM_OPTS[@]}"} -XX:SharedArchiveFile="$JSA" -jar "$JAR" ${APP_ARGS[@]+"${APP_ARGS[@]}"}
else
  exec java ${CLJL_OPTS:-} ${JVM_OPTS[@]+"${JVM_OPTS[@]}"} -jar "$JAR" ${APP_ARGS[@]+"${APP_ARGS[@]}"}
fi
