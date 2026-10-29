// Ported from Pele src/diagrams/ishikawa/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.ishikawa

import pele.js.charCodeAt
import pele.util.isSpace
import pele.util.isWord

object T {
  const val END = 0
  const val EOF = 1
  const val SPACELINE = 2
  const val NL = 3
  const val ISHIKAWA = 4
  const val SPACELIST = 5
  const val TEXT = 6
}

val TOKEN_NAMES = listOf("\$end", "EOF", "SPACELINE", "NL", "ISHIKAWA", "SPACELIST", "TEXT")

class Tokens(
  val types: MutableList<Int>,
  val starts: MutableList<Int>,
  val ends: MutableList<Int>,
)

private fun keyword(src: String, at: Int): Int {
  val word = "ishikawa-beta"
  var k = 0
  while (k < 13) {
    val c = src.charCodeAt(at + k)
    val w = word.charCodeAt(k)
    if (c != w && (c + 32 != w || c < 65 || c > 90)) break
    k++
  }
  // `ishikawa-beta`, or failing that `ishikawa`, either one ending at a word boundary.
  if (k == 13 && !isWord(src.charCodeAt(at + 13))) return 13
  return if (k >= 8 && !isWord(src.charCodeAt(at + 8))) 8 else 0
}

// Produces the tokens of Mermaid's Jison lexer for Ishikawa diagrams, where the first rule that
// matches wins: white space up to a comment or up to the last line break in it is a blank line,
// a single line break before text is a new line, other white space is indentation, and the rest
// of a line is text.
fun tokenize(src: String): Tokens {
  val types = ArrayList<Int>()
  val starts = ArrayList<Int>()
  val ends = ArrayList<Int>()
  val n = src.length
  var i = 0
  while (i < n) {
    var j = i
    var lastBreak = -1
    while (j < n && isSpace(src.charCodeAt(j))) {
      if (src.charCodeAt(j) == 10) lastBreak = j
      j++
    }
    val type: Int
    var end: Int
    if (src.charCodeAt(j) == 37 && src.charCodeAt(j + 1) == 37) {
      type = T.SPACELINE
      end = j + 2
      while (end < n) {
        val c = src.charCodeAt(end)
        if (c == 10 || c == 13 || c == 0x2028 || c == 0x2029) break
        end++
      }
    } else if (j == i) {
      val length = keyword(src, i)
      if (length > 0) {
        type = T.ISHIKAWA
        end = i + length
      } else {
        type = T.TEXT
        end = src.indexOf('\n', i)
        if (end == -1) end = n
      }
    } else if (lastBreak > i) {
      type = T.SPACELINE
      end = lastBreak + 1
    } else if (lastBreak == i) {
      type = T.NL
      end = i + 1
    } else {
      type = T.SPACELIST
      end = j
    }
    types.add(type)
    starts.add(i)
    ends.add(end)
    i = end
  }
  types.add(T.EOF)
  types.add(T.END)
  starts.add(n)
  starts.add(n)
  ends.add(n)
  ends.add(n)
  return Tokens(types, starts, ends)
}
