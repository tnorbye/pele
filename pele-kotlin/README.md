# pele-kotlin

A native Kotlin/JVM port of [Pele](../README.md), the Mermaid-to-SVG renderer, with no dependencies
beyond the Kotlin standard library. For the same input and options, it produces the same SVG, byte for byte,
as upstream Pele at the pinned commit (see `PELE_UPSTREAM_COMMIT` in `src/main/kotlin/pele/Pele.kt`).

```kotlin
import pele.RenderOptions
import pele.render

val result = pele.render("pie title Pets\n  \"Dogs\" : 3\n  \"Cats\" : 2", RenderOptions(maxWidth = 600.0))
println(result.svg)
```

Errors are reported as `pele.PeleError`, which has the same `code` and message as upstream.

## Status

All 33 diagram types are ported. `mount`, `enableZoom`, `renderAsync` and the canvas text measurer are
browser-only and not ported; text is measured with the bundled Arial metrics unless you pass your own
`TextMeasurer`. The port is checked by:

- 4,621 golden records (extracted upstream test inputs, generated stress cases, narrow-width and links-off variants, and inputs
  found by fuzzing), compared by SHA-256 on JDK 21 and 17;
- differential fuzzing against the upstream bundle (over 100,000 mutated inputs with no differences);
- oracles from Node for `JSON.parse`, `Date.parse`/`new Date` (three time zones) and `new URL`;
- concurrent rendering of every golden on 8 threads.

## Download

Jars are built, tested and published by the [Kotlin workflow](../.github/workflows/kotlin.yml) when a
`kotlin-v<version>` tag is pushed. Each release on the repository's releases page has the jar, a sources jar,
`SHA256SUMS`, and a build provenance attestation, which shows the jar came from that workflow run on the
tagged commit:

```sh
gh attestation verify pele-kotlin-1.12.0.jar --repo <owner>/<repo>
```

The jar needs only `kotlin-stdlib` (2.x) on the classpath, and Java 17 or later.

## Develop

Requires a JDK (17+; Gradle provisions 21 and 17 toolchains). Golden tests compare the port with SVG
rendered by upstream's JS bundle; they are checked in, so Node is needed only to regenerate them.

```sh
./gradlew test                     # unit, golden and concurrency tests (JDK 21)
./gradlew testJdk17                # the same on JDK 17
./gradlew testSmallStack           # API and large flat inputs with a 1 MB thread stack
./gradlew benchmarkJourney         # rendering time and cumulative allocation by task count
./gradlew test --tests pele.GoldenSvgTest -Dpele.type=flowchart -Dpele.svgOut=/tmp/out
./gradlew ktfmtFormat              # format the Kotlin sources (ktfmt, Google style); ktfmtCheck verifies
scripts/check_port_rules.sh        # porting rules lint and 1:1 file mapping

# Regenerate goldens / fuzz against upstream (Node 24, from the repo root):
npm ci && npm run build
node pele-kotlin/scripts/generate_goldens.mjs
pele-kotlin/scripts/run.sh <seed> <perType> [types]
```

See [UPDATE.md](UPDATE.md) for how to move the port to a newer upstream commit, and the conventions it follows.

The fuzz runner exits nonzero on mismatches, missing records or empty output.
