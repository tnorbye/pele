// Ported from Pele src/diagrams/mindmap/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.mindmap

import pele.diagrams.common.OutlineDb
import pele.diagrams.common.ScanTokens
import pele.diagrams.common.parseOutline
import pele.diagrams.common.tokenize as tokenizeOutline

fun tokenize(src: String): ScanTokens = tokenizeOutline(src, false)

fun parseMindmap(src: String, db: OutlineDb) {
  parseOutline(src, db, false)
}
