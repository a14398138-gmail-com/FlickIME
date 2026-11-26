#!/usr/bin/env bash
# Builds libzenzjni for this PC (Linux: .so, macOS: .dylib) from app/src/main/cpp, so the JVM
# evaluators can rerank with zenz like the phone does. Needs cmake, a C++17 compiler and a JDK (for jni.h).
# usage: tools/loop/build_zenz_host.sh   → prints the library path (use it as ZENZ_LIB)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
B="$ROOT/build/zenzhost"
mkdir -p "$B"
cmake -S "$ROOT/app/src/main/cpp" -B "$B" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON >"$B/configure.log" 2>&1 || { tail -30 "$B/configure.log" >&2; exit 1; }
cmake --build "$B" --target zenzjni -j"$( (nproc || sysctl -n hw.ncpu) 2>/dev/null || echo 4)" >"$B/build.log" 2>&1 || { tail -30 "$B/build.log" >&2; exit 1; }
ls "$B"/libzenzjni.* | head -1
