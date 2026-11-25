#!/usr/bin/env bash
# Prediction benchmark without Gradle / Android SDK. Needs only a JDK (17+).
# usage: tools/predeval/run.sh EVAL_DIR [dev|test] [detail-out-file]
# Compiles app/src/main/java/dev/flickime/engine/*.kt + PredEval.kt with kotlinc
# (uses the one on PATH, else downloads Kotlin 2.1.0 from GitHub releases into ~/.cache/flickime-kotlinc).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EVAL_DIR="$(cd "${1:?usage: run.sh EVAL_DIR [dev|test] [detail-out]}" && pwd)"
SPLIT="${2:-dev}"
OUT="${3:-}"

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
SRC=("$ROOT"/app/src/main/java/dev/flickime/engine/*.kt "$ROOT/tools/predeval/PredEval.kt")
# rebuild only when a source is newer than the jar
if [ ! -f "$JAR" ] || [ -n "$(find "${SRC[@]}" -newer "$JAR" 2>/dev/null)" ]; then
  "$KOTLINC" -nowarn -include-runtime -d "$JAR" "${SRC[@]}" 2>&1 | grep -v "^warning" >&2 || true
  [ -f "$JAR" ] || { echo "compile failed" >&2; exit 1; }
fi
java -Xmx2g -cp "$JAR" dev.flickime.engine.PredEval "$ROOT/app/src/main/assets" "$EVAL_DIR" "$SPLIT" ${OUT:+"$OUT"}
