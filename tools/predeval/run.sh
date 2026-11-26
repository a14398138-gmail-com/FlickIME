#!/usr/bin/env bash
# Prediction benchmark (ksr) without Gradle / Android SDK. Needs only a JDK (17+).
# usage: tools/predeval/run.sh EVAL_DIR [dev|test] [detail-out-file]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EVAL_DIR="$(cd "${1:?usage: run.sh EVAL_DIR [dev|test] [detail-out]}" && pwd)"
SPLIT="${2:-dev}"
OUT="${3:-}"
JAR="$("$ROOT/tools/predeval/build.sh")"
java -Xmx2g -cp "$JAR" dev.flickime.engine.PredEval "$ROOT/app/src/main/assets" "$EVAL_DIR" "$SPLIT" ${OUT:+"$OUT"}
