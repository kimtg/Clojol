#!/usr/bin/env bash
set -euo pipefail

SOURCE="${BASH_SOURCE[0]}"
while [ -L "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
CLJL_HOME="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"

BUILD_DIR="$CLJL_HOME/build"
CLASSES_DIR="$BUILD_DIR/classes"
JAR="$BUILD_DIR/cljl.jar"
JSA="$BUILD_DIR/cljl.jsa"

do_clean() {
  if [ -d "$BUILD_DIR" ]; then
    echo "[ClojureLite] Cleaning build directory..."
    rm -rf "$BUILD_DIR"
  fi
}

do_build() {
  echo "[ClojureLite] Compiling Java sources..."
  mkdir -p "$CLASSES_DIR"
  find "$CLJL_HOME/src" -name '*.java' > "$BUILD_DIR/sources.txt"
  javac --release 21 -d "$CLASSES_DIR" @"$BUILD_DIR/sources.txt"

  echo "[ClojureLite] Packaging $JAR..."
  jar --create --file "$JAR" --main-class cljl.Main -C "$CLASSES_DIR" .

  echo "[ClojureLite] Generating AppCDS archive $JSA..."
  rm -f "$JSA"
  java -XX:ArchiveClassesAtExit="$JSA" -jar "$JAR" -e "(+ 1 2)" >/dev/null 2>&1 || true
  echo "[ClojureLite] Build complete."
}

run_cljl() {
  if [ -f "$JSA" ]; then
    java ${CLJL_OPTS:-} -XX:SharedArchiveFile="$JSA" -jar "$JAR" "$@"
  else
    java ${CLJL_OPTS:-} -jar "$JAR" "$@"
  fi
}

show_help() {
  cat <<EOF
Usage: ./make.sh [target]

Targets:
  build    Compile Java sources, package build/cljl.jar, generate AppCDS archive (default)
  rebuild  Clean and build from scratch
  clean    Remove build/ directory
  test     Build and run test/core_test.clj
  bench    Build and run test/bench.clj
  help     Show this help message
EOF
}

CMD="${1:-build}"
case "$CMD" in
  clean)
    do_clean
    ;;
  build)
    do_build
    ;;
  rebuild)
    do_clean
    do_build
    ;;
  test)
    [ -f "$JAR" ] || do_build
    echo "[ClojureLite] Running tests..."
    run_cljl "$CLJL_HOME/test/core_test.clj"
    ;;
  bench)
    [ -f "$JAR" ] || do_build
    echo "[ClojureLite] Running benchmarks..."
    run_cljl "$CLJL_HOME/test/bench.clj"
    ;;
  help|-h|--help)
    show_help
    ;;
  *)
    echo "Unknown target: $CMD" >&2
    show_help
    exit 1
    ;;
esac
