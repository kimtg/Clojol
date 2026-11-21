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
AOT="$BUILD_DIR/cljl.aot"
JSA="$BUILD_DIR/cljl.jsa"

do_clean() {
  if [ -d "$BUILD_DIR" ]; then
    echo "[Clojol] Cleaning build directory..."
    rm -rf "$BUILD_DIR"
  fi
}

do_build() {
  echo "[Clojol] Compiling Java sources..."
  mkdir -p "$CLASSES_DIR"
  find "$CLJL_HOME/src" -name '*.java' > "$BUILD_DIR/sources.txt"
  javac --release 21 -d "$CLASSES_DIR" @"$BUILD_DIR/sources.txt"

  echo "[Clojol] Packaging $JAR..."
  jar --create --file "$JAR" --main-class cljl.Main -C "$CLASSES_DIR" .

  rm -f "$AOT" "$JSA"
  echo "[Clojol] Generating AOT cache $AOT..."
  java -XX:AOTCacheOutput="$AOT" -jar "$JAR" -Srepro -Sdeps "{}" -e "(+ 1 2)" >/dev/null 2>&1 || true
  if [ -f "$AOT" ]; then
    echo "[Clojol] AOT cache generated."
  else
    echo "[Clojol] Generating AppCDS archive $JSA..."
    java -XX:ArchiveClassesAtExit="$JSA" -jar "$JAR" -Srepro -Sdeps "{}" -e "(+ 1 2)" >/dev/null 2>&1 || true
  fi
  echo "[Clojol] Build complete."
}

do_build_if_needed() {
  if [ ! -f "$JAR" ] || [ -n "$(find "$CLJL_HOME/src" -name '*.java' -newer "$JAR" 2>/dev/null | head -n 1)" ]; then
    do_build
  fi
}

run_cljl() {
  if [ -f "$AOT" ]; then
    java ${CLJL_OPTS:-} -XX:AOTCache="$AOT" -jar "$JAR" "$@"
  elif [ -f "$JSA" ]; then
    java ${CLJL_OPTS:-} -XX:SharedArchiveFile="$JSA" -jar "$JAR" "$@"
  else
    java ${CLJL_OPTS:-} -jar "$JAR" "$@"
  fi
}

run_cljl_strict() {
  if [ -f "$AOT" ]; then
    java ${CLJL_OPTS:-} -Dcljl.jit.strict=true -XX:AOTCache="$AOT" -jar "$JAR" "$@"
  elif [ -f "$JSA" ]; then
    java ${CLJL_OPTS:-} -Dcljl.jit.strict=true -XX:SharedArchiveFile="$JSA" -jar "$JAR" "$@"
  else
    java ${CLJL_OPTS:-} -Dcljl.jit.strict=true -jar "$JAR" "$@"
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
    do_build_if_needed
    echo "[Clojol] Running tests (strict JIT)..."
    run_cljl_strict "$CLJL_HOME/test/core_test.clj"
    run_cljl_strict "$CLJL_HOME/test/cheatsheet_test.clj"
    run_cljl_strict "$CLJL_HOME/test/cli_test.clj"
    ;;
  bench)
    do_build_if_needed
    echo "[Clojol] Running benchmarks..."
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
