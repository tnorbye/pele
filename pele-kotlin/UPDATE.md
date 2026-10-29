# Updating the Kotlin port

`pele-kotlin` is a file-by-file port of Pele's `src/`. For the same input and options it must produce
**byte-for-byte the same SVG** (and the same `width`, `height`, `links`, `type`, or the same `PeleError`
code and message) as upstream at the commit it is based on. This document is the procedure for moving
it to a newer upstream commit.

## Current baseline

| | |
|---|---|
| Upstream commit | `bdbad5df4dea13b9a14a83a92aed62c4769e49f5` (2026-10-27, "Layout: Let bends move twice as far as nodes to straighten an edge") |
| Upstream version | `1.12.0` (`package.json`) |

The baseline is recorded in three places, which must always agree:

1. `PELE_UPSTREAM_COMMIT` and `PELE_VERSION` in `src/main/kotlin/pele/Pele.kt`.
2. The header of every ported file: `// Ported from Pele src/<path>.ts @ <commit>`.
3. The table above, and the fork note at the top of the repository's `README.md`.

`scripts/generate_goldens.mjs` refuses to run unless the bundle's version, `package.json` and
`PELE_VERSION` agree. It warns when `src/` differs from `PELE_UPSTREAM_COMMIT`.

## Prerequisites

- A JDK, 17 or later. Gradle provisions the 21 and 17 toolchains the build uses.
- Node 24 and npm, to build the upstream bundle and regenerate the goldens and oracles.

Commands below run from the repo root unless they start with `./gradlew` or `scripts/`, which run in
`pele-kotlin/`.

## Procedure

### 1. Bring in the new upstream commit

The port lives inside the Pele repo, so "upstream" is this repo's own history. Merge the commit you're
moving to (usually the latest `main`, or a release tag) into the branch that has the port, and note it:

```sh
OLD=$(grep -o 'PELE_UPSTREAM_COMMIT = "[0-9a-f]*"' pele-kotlin/src/main/kotlin/pele/Pele.kt | grep -o '[0-9a-f]\{40\}')
NEW=$(git rev-parse <target>)
```

### 2. See what changed

```sh
git diff --stat $OLD $NEW -- src tests package.json
git diff $OLD $NEW -- src             # every hunk that needs porting
git diff --name-status $OLD $NEW -- src   # added (A), deleted (D) and renamed (R) files
```

- **`src/` changes** have to be ported. Because the mapping is one file per file, with the same
  declaration order and comments, each hunk lands in the same place in the matching `.kt` file.
- **`tests/` changes** don't need porting. They change the golden inputs, which step 5 picks up.
- Skip files that aren't ported: `src/mount.ts`, `zoom.ts`, `window.ts`, `lazy.ts`, `models.ts`,
  `entries/*`, and the canvas part of `text/measurer.ts`. Also skip changes that are types or
  formatting only.

### 3. Port each hunk

For each changed file:

1. Open the matching Kotlin file. `src/X/y-z.ts` maps to `pele-kotlin/src/main/kotlin/pele/X/YZ.kt`.
   `diagrams/class/` becomes `diagrams/classdiagram/`, a diagram's `index.ts` becomes `Index.kt`, and
   the root `index.ts` becomes `Pele.kt`.
2. Apply the same change, following the conventions below. Keep upstream's names, order and comments.
   Don't refactor.
3. For an **added** file, create the `.kt` file with the header. For a **deleted** or **renamed** file,
   delete or rename the `.kt` file.
4. For a **new diagram type**:
   - Add `pele-kotlin/src/main/kotlin/pele/diagrams/<dir>/`.
   - Add its `[type, dirs, keyword]` entry to `TYPES` in `scripts/generate_goldens.mjs`.
   - Run `python3 pele-kotlin/scripts/gen_registry.py` to regenerate `Registry.kt` in upstream's order.

When every hunk is ported, record the new baseline:

```sh
cd pele-kotlin
# Every ported file is now in sync with NEW, so bump all headers at once.
grep -rl "@ $OLD" src scripts | xargs sed -i '' "s/@ $OLD/@ $NEW/"   # GNU sed: -i without ''
# Update PELE_UPSTREAM_COMMIT (and PELE_VERSION if package.json changed) in src/main/kotlin/pele/Pele.kt,
# the "Current baseline" table in this file, and the fork note in the top-level README.md.
```

### 4. Build the upstream bundle

```sh
npm ci && npm run build      # writes dist/pele.global.js (git-ignored)
```

### 5. Regenerate the goldens with full SVGs and make them pass

```sh
node pele-kotlin/scripts/generate_goldens.mjs           # full SVG, for readable diffs
cd pele-kotlin
./gradlew test                                            # all types, JDK 21
./gradlew test --tests pele.GoldenSvgTest -Dpele.type=flowchart -Dpele.svgOut=/tmp/out   # one type
```

- A failure prints the record (type, index, options, source) and the text around the first character
  that differs. `-Dpele.svgOut` writes the expected and actual SVGs to disk.
- **Upstream JS is the only source of truth.** When in doubt, run the bundle in Node to see what it
  does. `loadBundle` in `scripts/generate_goldens.mjs` shows how to load it.
- The generator also reports inputs where upstream throws something other than `PeleError`. Those
  are upstream bugs, with no defined behaviour to match.

### 6. Fuzz against upstream

The goldens only contain upstream's own test strings. Differential fuzzing mutates them and compares
the two implementations:

```sh
cd pele-kotlin
T=$(ls testData/golden | sed 's/.jsonl.gz//' | paste -sd, -)
scripts/run.sh 1 500 $T                      # seed, inputs per type, types
FUZZ_JDK=17 scripts/run.sh 2 500 $T          # render on JDK 17 too
scripts/run.sh 3 2000 <changed types>        # more on the types the update touched
```

- Each run must report `0 DIFF`. Look into a `DIFF` with `build/fuzz/report.txt` and
  `build/fuzz/inputs.txt`; the inputs are separated by `\n=====\n`, and `DIFF #n` is input `n`.
- Every DIFF is a bug. Fix it, and save the input as `testData/seeds/<type>-<topic>.mmd`, so the next
  golden generation includes it.
- A mismatch whose upstream line is `UPSTREAM-CRASH` is an upstream bug.

### 7. Refresh the oracles if their inputs changed

`DatesTest` and `UrlTest` compare the port's JS emulation with values recorded from Node. Regenerate
them only if the update touches date or URL handling, or you change Node versions:

```sh
node pele-kotlin/scripts/generate_dates.mjs
node pele-kotlin/scripts/generate_urls.mjs
```

### 8. Final checks

```sh
cd pele-kotlin
scripts/check_port_rules.sh          # porting rules, headers, and the 1:1 file mapping; must print nothing
node scripts/generate_goldens.mjs --sha256-only    # the checked-in goldens hold hashes only
./gradlew test testJdk17 testSmallStack # both JDKs, plus large flat inputs with a 1 MB stack
node --test scripts/compare_fuzz.test.mjs
```

To publish a jar, set `version` in `build.gradle.kts` to the new upstream version and push a
`kotlin-v<version>` tag; `.github/workflows/kotlin.yml` tests, builds, attests and releases it.

Commit with the new commit hash in the message, for example
`Kotlin: Update port to <short NEW> (1.13.0)`.

## Conventions the port follows

### Layout and naming

- Every file starts with:
  ```kotlin
  // Ported from Pele src/<path>.ts @ <commit>
  // Copyright (c) 2026 kepano, MIT License.
  ```
  JVM-only helpers instead start with `// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).`
- A diagram's `index.ts` becomes
  `val <name>: Diagram<Model> = object : Diagram<Model> { override val type = "..."; ... }`.
- A TS `const enum` becomes `object T { const val x = 0 ... }`. String unions stay `String`.
- Interfaces and object literals become Kotlin classes. Use a `data class` only where upstream spreads
  an object (`{...x, a}` becomes `x.copy(a = ...)`). Where upstream compares objects by reference or
  uses them as Map/Set keys, compare with `===` or use `IdentityHashMap`/`WeakIdentityMap`.
- Mark every intentional deviation with `// PORT: <reason>`. A lint exemption is
  `// PORT-OK: <reason>` at the end of the line.

### The JS layer (`pele.js`): never bypass it

| TS | Kotlin |
|---|---|
| `/re/flags` | `JsRegExp("""re""", "flags")`, a raw string holding the JS source verbatim. `.test`, `.exec` and `.lastIndex` work as in JS (`lastIndex` is per thread). Never `Regex(...)`. |
| `s.replace(re, str \| fn)`, `replaceAll` | `s.jsReplace(re, "...")`, `s.jsReplace(re) { m -> ... }`. With a string pattern, `s.jsReplace("a", "b")` replaces the **first** match only. Never Kotlin `replace`. |
| `s.split(str \| re)` | `s.jsSplit(...)`, `s.jsSplitStrings(re)` |
| `s.match(re)`, `/g` match, `matchAll` | `jsMatch`, `jsMatchGlobal`, `jsMatchAll` |
| a number in `` `${n}` ``, `String(n)` | `jsStr(n)`. SVG coordinates use `num(n)`. Never interpolate a `Double`. |
| `Math.round`, `toFixed`, `toPrecision` | `jsRound`, `x.toFixed(d)`, `x.toPrecision(p)` |
| `Number(s)`, `parseFloat`, `parseInt` | `jsNumber`, `jsParseFloat`, `jsParseInt`, which return a Double (NaN on failure) |
| `trim`, `trimStart`, `trimEnd` | `jsTrim`, `jsTrimStart`, `jsTrimEnd` |
| `s.charCodeAt(i)` | `s.charCodeAt(i)` returns -1 past the end, where JS returns NaN. `===`, `>=` and `<=` behave the same; `<` and `>` don't. |
| `slice`, `substring`, `substr` | `jsSlice`, `jsSubstring`, `jsSubstr` |
| `Array.from(s)`, `for (const ch of s)` | `s.toCodePointStrings()` |
| `JSON.parse`, `JSON.stringify` | `jsonParse` (V8's error messages), `jsonStringify`, `jsonQuote` |
| `x \| 0`, `>>> 0`, `Math.imul` | `toInt32`, `toUint32`, `jsImul` |
| `Object.keys`, `Object.entries` | `jsKeys`, `jsEntries` (integer-like keys first) |
| truthiness, `a \|\| b` | `isTruthy(v)`, `truthy(n)`, `x.jsOr { y }`. Test falsy values explicitly. |
| sort comparator | `sortedWith { a, b -> cmp(...) }`, which counts NaN as 0 |
| `Math.sin`, `cos`, `atan2`, `exp`, `log`, `pow` | `StrictMath.*`, which is fdlibm, as in V8. `Math.hypot` → `jsHypot`. |
| `new URL(x).href`, `decodeURIComponent` | `whatwgHref`, `jsDecodeURIComponent` |
| `WeakMap<object, V>` | `WeakIdentityMap`. The value must not reference its key. |

### Numbers, collections, errors and state

- `number` becomes `Double`. Use `Int` only for indices, counts, bitmasks and explicit 32-bit math.
- `Map<number, …>` keys: JS treats `-0` and `0` as the same key. Normalize with `+ 0.0`.
- `Map` and `Set` become `LinkedHashMap` and `LinkedHashSet`, which keep insertion order.
- Throw `PeleError` with upstream's exact message. Catch `Exception`, never `Throwable`. Only
  `Core.kt` catches `StackOverflowError`.
- No mutable top-level state. A module-level `let` becomes a `ThreadLocal`, a per-call object, or a
  synchronized or atomically swapped cache. `ConcurrentGoldenSvgTest` renders every golden on 8
  threads to catch shared state.
- Repeated string concatenation copies the accumulated text on the JVM. Use `StringBuilder` in
  loops that run once per node or task in large inputs. Journey is the one measured hotspot so far;
  `./gradlew benchmarkJourney` reports its time and cumulative allocations.
- Repeated regex alternatives can exhaust Java's stack on flat text. Preserve their matching rules
  with iterative scanners, and include generated large inputs in `scripts/stress_inputs.mjs`.

## Tooling reference

| File | Purpose |
|---|---|
| `scripts/generate_goldens.mjs` | Renders all upstream test inputs and `testData/seeds/*.mmd` with the bundle into `testData/golden/<type>.jsonl.gz` |
| `scripts/run.sh`, `mutate.mjs`, `up.mjs` | Differential fuzzing (Kotlin side: `./gradlew fuzzRender`) |
| `scripts/check_port_rules.sh` | Lint for the rules above, headers, and the 1:1 file mapping |
| `scripts/gen_registry.py` | Regenerates `diagrams/Registry.kt` from upstream's registry order |
| `scripts/generate_dates.mjs`, `generate_urls.mjs` | Node oracles for `DatesTest` and `UrlTest` |
| `src/test/kotlin/pele/` | `GoldenSvgTest`, `ConcurrentGoldenSvgTest`, `JsCompatTest`, `UrlTest`, `DatesTest` |
