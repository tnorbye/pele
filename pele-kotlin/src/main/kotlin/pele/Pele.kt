// Ported from Pele src/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele

// PORT: index.ts registers every diagram type; Core.kt does so when it is first used. The public
// API (render, parse, detectType, supports, register, registered, PeleError, forgetTextWidths)
// lives in Core.kt and text/Measurer.kt, in package `pele` and `pele.text`.

const val PELE_VERSION = "1.12.0"
const val PELE_UPSTREAM_COMMIT = "bdbad5df4dea13b9a14a83a92aed62c4769e49f5"
