// Ported from Pele src/diagrams/kanban/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.kanban

import pele.diagrams.common.OutlineDb
import pele.diagrams.common.ScanTokens
import pele.diagrams.common.parseOutline
import pele.diagrams.common.tokenize as tokenizeOutline

fun tokenize(src: String): ScanTokens = tokenizeOutline(src, true)

fun parseKanban(src: String, db: OutlineDb) {
  parseOutline(src, db, true)
}
