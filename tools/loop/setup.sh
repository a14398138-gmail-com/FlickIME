#!/usr/bin/env bash
# One-time setup for the improvement loop (docs/GOAL_opencode.md). Safe to run again.
#  - checks java / python3 / git
#  - eval/events.tsv from the author's log (eval/log.jsonl, exported from the app) if not built yet
#  - AJIMEE-Bench → build/ajimee.tsv
#  - zenz for the PC (build/zenzhost/libzenzjni.*) when cmake + a C++ compiler exist
#  - compiles the evaluators once
# Writes build/loop.env (source it: ZENZ_LIB, AJIMEE).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
ok=1
for t in java python3 git curl unzip; do command -v $t >/dev/null || { echo "MISSING: $t" >&2; ok=0; }; done
[ $ok = 1 ] || { echo "install the missing tools first (JDK 17+ for java)" >&2; exit 1; }
mkdir -p build out
if [ ! -f eval/events.tsv ]; then
  if [ -f eval/log.jsonl ]; then python3 tools/build_predict_eval.py eval/log.jsonl eval >/dev/null && echo "eval/events.tsv built"
  else echo "NOTE: eval/log.jsonl がありません（アプリの設定 → ログ書き出し のファイルを eval/log.jsonl に置く）。log セットなしで続けます" >&2; fi
fi
if [ ! -f build/ajimee.tsv ]; then
  [ -d build/AJIMEE-Bench ] || git clone -q --depth 1 https://github.com/azooKey/AJIMEE-Bench build/AJIMEE-Bench
  python3 tools/ajimee_tsv.py build/AJIMEE-Bench/JWTD_v2/v1/evaluation_items.json build/ajimee.tsv && echo "build/ajimee.tsv built"
fi
ZL=""
if ls build/zenzhost/libzenzjni.* >/dev/null 2>&1; then ZL="$(ls "$ROOT"/build/zenzhost/libzenzjni.* | head -1)"
elif command -v cmake >/dev/null; then echo "building zenz for this PC (a few minutes)..."; ZL="$(tools/loop/build_zenz_host.sh)" || ZL=""
else echo "NOTE: cmake がないので zenz なし（n-gram のみ）で測ります" >&2; fi
{ echo "export ZENZ_LIB=\"$ZL\""; echo "export AJIMEE=\"$ROOT/build/ajimee.tsv\""; } > build/loop.env
tools/predeval/build.sh >/dev/null && echo "evaluators compiled"
echo "zenz: ${ZL:-off}"
echo "ready. next: tools/loop/eval_all.sh"
