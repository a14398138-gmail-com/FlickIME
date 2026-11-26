#!/usr/bin/env bash
# Compiles the engine + evaluators into build/predeval/predeval.jar (only when a source changed) and prints its path.
# Needs only a JDK (17+); uses kotlinc on PATH, else downloads Kotlin 2.1.0 into ~/.cache/flickime-kotlinc.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KOTLINC="$(command -v kotlinc || true)"
if [ -z "$KOTLINC" ]; then
  K="$HOME/.cache/flickime-kotlinc"
  if [ ! -x "$K/kotlinc/bin/kotlinc" ]; then
    mkdir -p "$K"
    echo "downloading kotlinc 2.1.0 ..." >&2
    curl -fsSL -o "$K/k.zip" https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip
    (cd "$K" && unzip -q -o k.zip && rm k.zip)
  fi
  KOTLINC="$K/kotlinc/bin/kotlinc"
fi
BUILD="$ROOT/build/predeval"
mkdir -p "$BUILD"
JAR="$BUILD/predeval.jar"
SRC=("$ROOT"/app/src/main/java/dev/flickime/engine/*.kt "$ROOT"/tools/predeval/*.kt)
if [ ! -f "$JAR" ] || [ -n "$(find "${SRC[@]}" -newer "$JAR" 2>/dev/null)" ]; then
  rm -f "$JAR"
  "$KOTLINC" -nowarn -include-runtime -d "$JAR" "${SRC[@]}" 2>&1 | grep -v "^warning" >&2 || true
  [ -f "$JAR" ] || { echo "compile failed" >&2; exit 1; }
fi
echo "$JAR"
