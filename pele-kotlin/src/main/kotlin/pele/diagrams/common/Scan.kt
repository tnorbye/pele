// Ported from Pele src/diagrams/common/scan.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.js.charCodeAt
import pele.js.jsSlice
import pele.util.isSpace

// Lexer rules that Mermaid's journey and timeline grammars have in common, ported from the
// compiled Jison rules: comments, whitespace, and the accTitle and accDescr statements.

class ScanTokens(
  val types: MutableList<Int> = ArrayList(),
  val texts: MutableList<String> = ArrayList(),
  val starts: MutableList<Int> = ArrayList(),
)

fun lineEnd(src: String, from: Int): Int {
  val at = src.indexOf('\n', from)
  return if (at == -1) src.length else at
}

// `word` is lowercase ASCII letters; the grammars are case-insensitive.
fun keywordAt(src: String, p: Int, word: String): Boolean {
  for (i in word.indices) {
    if ((src.charCodeAt(p + i) or 32) != word[i].code) return false
  }
  return true
}

// Skips comments and whitespace. Stops at a line break that starts a token, which is a NEWLINE.
// Jison compiles `\%%` to `%`, so a single `%` starts a comment.
fun skipTrivia(src: String, from: Int): Int {
  val n = src.length
  var p = from
  while (p < n) {
    val c = src.charCodeAt(p)
    if (c == 37 && src.charCodeAt(p + 1) != 123) {
      p = lineEnd(src, p)
    } else if (c == 10) {
      return p
    } else if (isSpace(c)) {
      do p++ while (p < n && isSpace(src.charCodeAt(p)))
    } else if (c == 35) {
      p = lineEnd(src, p)
    } else {
      return p
    }
  }
  return p
}

private fun skipSpace(src: String, from: Int): Int {
  var p = from
  while (isSpace(src.charCodeAt(p))) p++
  return p
}

// Lexes an accTitle or accDescr statement at `p` and returns where it ends, or `p` if there is
// none.
// The token types acc_title, acc_title_value, acc_descr, acc_descr_value and
// acc_descr_multiline_value must be numbered consecutively from `first`.
fun accessibility(src: String, p: Int, out: ScanTokens, first: Int): Int {
  val descr = keywordAt(src, p, "accdescr")
  if (!descr && !keywordAt(src, p, "acctitle")) return p
  val n = src.length
  var q = skipSpace(src, p + 8)
  val c = src.charCodeAt(q)
  val emit = { type: Int, s: Int, e: Int ->
    out.types.add(type)
    out.texts.add(src.jsSlice(s, e))
    out.starts.add(s)
  }
  if (c == 58) {
    q = skipSpace(src, q + 1)
    val end = lineEnd(src, q)
    emit(if (descr) first + 2 else first, p, q)
    emit(if (descr) first + 3 else first + 1, q, end)
    return end
  }
  if (!descr || c != 123) return p
  q = skipSpace(src, q + 1)
  while (src.charCodeAt(q) != 125) {
    var end = src.indexOf('}', q)
    if (end == -1) end = n
    emit(first + 4, q, end)
    if (q == n) return n
    q = end
  }
  return q + 1
}
