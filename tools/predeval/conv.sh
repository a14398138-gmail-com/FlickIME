#!/usr/bin/env bash
# "Whole reading typed: is the right conversion visible in the bar without scrolling?" (ConvEval.kt)
# usage: tools/predeval/conv.sh log EVAL_DIR [dev|test] [detail-out]
#        tools/predeval/conv.sh ajimee|casual TSV [all] [detail-out]
# zenz like on the phone: export ZENZ_LIB=$PWD/build/zenzhost/libzenzjni.so (tools/loop/build_zenz_host.sh)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SET="${1:?set}"; P="${2:?path}"; SPLIT="${3:-dev}"; OUT="${4:-}"
JAR="$("$ROOT/tools/predeval/build.sh")"
java -Xmx3g -cp "$JAR" dev.flickime.engine.ConvEval "$ROOT/app/src/main/assets" "$SET" "$P" "$SPLIT" ${OUT:+"$OUT"}
