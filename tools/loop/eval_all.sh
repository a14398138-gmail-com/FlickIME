#!/usr/bin/env bash
# All metrics of the loop in one go (docs/GOAL_opencode.md). Prints the summary lines, writes details to out/.
# usage: tools/loop/eval_all.sh [dev|test] [tag]     (FAST=1: skip ajimee/casual/ksr, only log vis)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
[ -f build/loop.env ] && . build/loop.env
SPLIT="${1:-dev}"; TAG="${2:-run}"
mkdir -p out
if [ -f eval/events.tsv ]; then
  tools/predeval/conv.sh log eval "$SPLIT" "out/$TAG.log_$SPLIT.txt" 2>/dev/null | grep CONVEVAL
fi
[ "${FAST:-0}" = 1 ] && exit 0
tools/predeval/conv.sh ajimee build/ajimee.tsv all "out/$TAG.ajimee.txt" 2>/dev/null | grep CONVEVAL
tools/predeval/conv.sh casual tools/eval/casual.tsv all "out/$TAG.casual.txt" 2>/dev/null | grep CONVEVAL
if [ -f eval/events.tsv ]; then
  tools/predeval/run.sh eval "$SPLIT" "out/$TAG.pred_$SPLIT.txt" 2>/dev/null | grep PREDEVAL
fi
