#!/usr/bin/env bash
# PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
#
# Differential fuzzing: renders mutated golden inputs with upstream and the port and compares.
#   pele-kotlin/scripts/run.sh [seed] [perType] [types]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
seed="${1:-1}"; per="${2:-100}"; types="${3:-}"
out="${FUZZ_OUT:-$here/build/fuzz}"
mkdir -p "$out"
node "$here/scripts/mutate.mjs" "$seed" "$per" $types > "$out/inputs.txt"
node "$here/scripts/up.mjs" "$out/inputs.txt" > "$out/up.txt"
(cd "$here" && ./gradlew -q fuzzRender -Pinputs="$out/inputs.txt" ${FUZZ_JDK:+-Pjdk=$FUZZ_JDK}) > "$out/kt.txt"
node "$here/scripts/compare_fuzz.mjs" "$out/up.txt" "$out/kt.txt" "$out/report.txt"
