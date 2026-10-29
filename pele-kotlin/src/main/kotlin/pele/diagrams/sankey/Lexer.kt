// Ported from Pele src/diagrams/sankey/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sankey

import pele.js.charCodeAt
import pele.js.jsCodePointAt
import pele.js.jsSlice
import pele.util.isWord

object T {
  const val END = 0

  // Not a Jison token: the reference lexer would return the same empty token forever from here.
  const val STUCK = 1
  const val SANKEY = 2
  const val NEWLINE = 3
  const val COMMA = 4
  const val DQUOTE = 5
  const val NON_ESCAPED_TEXT = 6
  const val ESCAPED_TEXT = 7
  const val EOF = 8
}

val TOKEN_NAMES: List<String> =
  listOf(
    "\$end",
    "STUCK",
    "SANKEY",
    "NEWLINE",
    "COMMA",
    "DQUOTE",
    "NON_ESCAPED_TEXT",
    "ESCAPED_TEXT",
    "EOF",
  )

class Tokens(
  val types: MutableList<Int>,
  val texts: MutableList<String>,
  val starts: MutableList<Int>,
)

private object S {
  const val INITIAL = 0
  const val csv = 1
  const val escaped_text = 2
}

// RFC 4180 TEXTDATA extended to Unicode, excluding controls and invalid XML characters.
private fun isTextData(c: Int): Boolean =
  (c >= 32 && c <= 126 && c != 34 && c != 44) ||
    (c >= 0xa0 && c <= 0xd7ff) ||
    (c >= 0xe000 && c <= 0xfffd) ||
    (c >= 0x10000 && c <= 0x10ffff)

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state = S.INITIAL
  var p = 0

  val emit = { type: Int, s: Int, e: Int ->
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
  }
  val keyword = { word: String ->
    src.jsSlice(p, p + word.length).lowercase() == word && !isWord(src.charCodeAt(p + word.length))
  }

  while (p < n) {
    val c = src.charCodeAt(p)
    val start = p
    var q = p
    if (state == S.escaped_text) {
      if (c == 34 && src.charCodeAt(p + 1) != 34) {
        if (stack.size > 0) state = stack.removeAt(stack.size - 1)
        emit(T.DQUOTE, p, p + 1)
        continue
      }
      while (q < n) {
        val d = src.jsCodePointAt(q)
        if (isTextData(d) || d == 44 || d == 13 || d == 10) q += if (d > 0xffff) 2 else 1
        else if (d == 34 && src.charCodeAt(q + 1) == 34) q += 2 else break
      }
      emit(T.ESCAPED_TEXT, p, q)
    } else {
      if (state == S.INITIAL && (c or 32) == 115) {
        val length = if (keyword("sankey-beta")) 11 else if (keyword("sankey")) 6 else 0
        if (length > 0) {
          stack.add(state)
          state = S.csv
          emit(T.SANKEY, p, p + length)
          continue
        }
      }
      if (c == 10 || (c == 13 && src.charCodeAt(p + 1) == 10)) {
        emit(T.NEWLINE, p, if (c == 10) p + 1 else p + 2)
        continue
      }
      if (c == 44) {
        emit(T.COMMA, p, p + 1)
        continue
      }
      if (c == 34) {
        stack.add(state)
        state = S.escaped_text
        emit(T.DQUOTE, p, p + 1)
        continue
      }
      while (q < n) {
        val d = src.jsCodePointAt(q)
        if (!isTextData(d)) break
        q += if (d > 0xffff) 2 else 1
      }
      emit(T.NON_ESCAPED_TEXT, p, q)
    }
    // A character outside the CSV alphabet matches as empty text and is never consumed.
    if (q == start) {
      emit(T.STUCK, p, p)
      return Tokens(types, texts, starts)
    }
  }

  emit(if (state == S.escaped_text) T.ESCAPED_TEXT else T.EOF, n, n)
  emit(T.END, n, n)
  return Tokens(types, texts, starts)
}
