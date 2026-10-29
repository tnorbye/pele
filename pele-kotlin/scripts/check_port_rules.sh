#!/usr/bin/env bash
# PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
#
# Grep-based lint for the porting rules in UPDATE.md. Prints offending lines and exits 1 if any.
#   pele-kotlin/scripts/check_port_rules.sh [diagram-dir ...]
# A line ending in `// PORT-OK: <reason>` is exempt from the pattern rules.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
kt="$here/src/main/kotlin/pele"
status=0

files=()
if [ $# -gt 0 ]; then
  for d in "$@"; do while IFS= read -r f; do files+=("$f"); done < <(find "$kt/diagrams/$d" -name '*.kt'); done
else
  while IFS= read -r f; do files+=("$f"); done < <(find "$kt" -name '*.kt' -not -path "$kt/js/*")
fi

rule() {
  local pattern="$1" why="$2"
  local hits
  hits=$(grep -nE "$pattern" "${files[@]}" /dev/null | grep -v 'PORT-OK' | grep -vE '^[^:]+:[0-9]+:\s*//')
  if [ -n "$hits" ]; then
    echo "== $why"
    echo "$hits" | sed "s|$here/||"
    status=1
  fi
}

rule '\.trim(Start|End)?\(' 'Use jsTrim/jsTrimStart/jsTrimEnd'
rule '\bRegex\(|\.toRegex\(|Pattern\.compile' 'Use JsRegExp/jsRegex'
rule 'toDoubleOrNull|toIntOrNull|toLongOrNull|toBigDecimal' 'Use jsNumber/jsParseFloat/jsParseInt'
rule 'kotlin\.math\.round|roundToInt|roundToLong|Math\.round|[^.a-zA-Z]round\(' 'Use jsRound'
rule 'kotlin\.math\.(sin|cos|tan|asin|acos|atan|atan2|exp|expm1|ln|ln1p|log|log10|log2|pow|cbrt|sinh|cosh|tanh|hypot)\b|(^|[^h])\.pow\(|[^t]Math\.(sin|cos|tan|asin|acos|atan|atan2|exp|log|log10|pow|cbrt|hypot)\(' 'Use StrictMath (fdlibm, as V8) for transcendental functions'
rule 'String\.format|\.format\(' 'Use toFixed/num/jsStr'
rule 'catch \(\w+: Throwable\)' 'Catch Exception, not Throwable (UPDATE.md)'
rule 'toUpperCase\(\)|toLowerCase\(\)' 'Use uppercase()/lowercase()'
rule '\.replace\(' 'Kotlin replace() replaces every match; use jsReplace/jsReplaceAll'
rule "\\.split\\([^']" 'Use jsSplit/jsSplitStrings'
rule '^(private |internal )?var ' 'Top-level mutable state (UPDATE.md)'
rule '^(private |internal )?val \w+ *(: *[A-Za-z<>, ?]+)? *= *(mutable|HashMap|ArrayList|LinkedHashMap|HashSet|LinkedHashSet|hashMapOf|linkedMapOf|mutableListOf|mutableMapOf|mutableSetOf)' 'Top-level mutable collection (UPDATE.md)'

so=$(grep -lE 'StackOverflowError' "${files[@]}" /dev/null | grep -v '/Core.kt$')
if [ -n "$so" ]; then echo "== StackOverflowError outside Core.kt"; echo "$so" | sed "s|$here/||"; status=1; fi

# Headers.
for f in "${files[@]}"; do
  first=$(head -1 "$f")
  case "$first" in
    "// Ported from Pele src/"*" @ "*) ;;
    "// PORT: JVM-only helper"*) ;;
    *) echo "== Missing header: ${f#$here/}"; status=1 ;;
  esac
done

# 1:1 mapping between upstream src/**/*.ts and the Kotlin files.
kt_name() {
  local rel="$1" dir base out=""
  dir=$(dirname "$rel")
  base=$(basename "$rel" .ts)
  if [ "$rel" = "index.ts" ]; then echo "Pele.kt"; return; fi
  IFS='-' read -ra parts <<< "$base"
  for p in "${parts[@]}"; do out+="$(printf '%s' "${p:0:1}" | tr '[:lower:]' '[:upper:]')${p:1}"; done
  dir="${dir/diagrams\/class/diagrams/classdiagram}"
  if [ "$dir" = "." ]; then echo "$out.kt"; else echo "$dir/$out.kt"; fi
}

if [ $# -eq 0 ]; then
  while IFS= read -r ts; do
    rel="${ts#$root/src/}"
    case "$rel" in
      mount.ts|zoom.ts|window.ts|lazy.ts|models.ts|entries/*) continue ;;
    esac
    target="$kt/$(kt_name "$rel")"
    if [ ! -f "$target" ]; then echo "== Not ported yet: src/$rel -> ${target#$here/}"; status=1; fi
    if [ -f "$target" ] && ! head -1 "$target" | grep -q "src/$rel @"; then
      echo "== Header names the wrong source: ${target#$here/}"; status=1
    fi
  done < <(find "$root/src" -name '*.ts' | sort)
fi

exit $status
