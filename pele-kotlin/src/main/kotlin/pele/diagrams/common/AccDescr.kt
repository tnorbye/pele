// Ported from Pele src/diagrams/common/accDescr.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.js.JsRegExp

private val RE_HEAD =
  JsRegExp("""[\t ]*accDescr(?:[\t ]*:(?:[^\n\r]*?(?=%%)|[^\n\r]*)|\s*({))""", "y")

// The braced form scans for its closing brace. Once none is found there is none further on,
// so later braced descriptions fail without scanning the rest of the text again.
private fun scanner(): Scanner {
  var unclosed = false
  return { src, at, _ ->
    RE_HEAD.lastIndex = at
    val m = RE_HEAD.exec(src)
    if (m == null) {
      -1
    } else {
      val end = RE_HEAD.lastIndex
      if (m[1] == null) {
        end
      } else if (unclosed) {
        -1
      } else {
        val close = src.indexOf('}', end)
        unclosed = close == -1
        if (close + 1 != 0) close + 1 else -1
      }
    }
  }
}

// The common accDescr terminal for grammars where lexing goes on after an `accDescr {` that never
// closes, because `accDescr` is also a name there. Each one would otherwise read to the end of the
// text.
fun accDescr(name: String = "ACC_DESCR"): TokenType =
  ACC_DESCR.copy(name = name, scanner = ::scanner)
