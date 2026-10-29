// Ported from Pele src/diagrams/venn/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.venn

import pele.js.charCodeAt
import pele.js.jsSlice
import pele.js.jsTrim
import pele.util.isDigit
import pele.util.isHex
import pele.util.isSpace
import pele.util.isWord

object T {
  const val END = 0
  const val ERROR = 1
  const val EOF = 2
  const val NEWLINE = 3
  const val VENN = 4
  const val TITLE = 5
  const val SET = 6
  const val UNION = 7
  const val TEXT = 8
  const val INDENT_TEXT = 9
  const val STYLE = 10
  const val BRACKET_LABEL = 11
  const val NUMERIC = 12
  const val HEXCOLOR = 13
  const val RGBCOLOR = 14
  const val RGBACOLOR = 15
  const val IDENTIFIER = 16
  const val STRING = 17
  const val COMMA = 18
  const val COLON = 19
}

val TOKEN_NAMES = listOf(
  "\$end",
  "INVALID",
  "EOF",
  "NEWLINE",
  "VENN",
  "TITLE",
  "SET",
  "UNION",
  "TEXT",
  "INDENT_TEXT",
  "STYLE",
  "BRACKET_LABEL",
  "NUMERIC",
  "HEXCOLOR",
  "RGBCOLOR",
  "RGBACOLOR",
  "IDENTIFIER",
  "STRING",
  "COMMA",
  "COLON",
)

// The part of the model the lexer talks to: an indented `text` line is a different token after a
// set or union than anywhere else, and the parser is what knows which came before.
interface IndentHost {
  fun getIndentMode(): Boolean

  fun setIndentMode(enabled: Boolean)
}

private val KEYWORDS = listOf(
  "venn-beta" to T.VENN,
  "set" to T.SET,
  "union" to T.UNION,
  "text" to T.TEXT,
  "style" to T.STYLE,
)

// Reads tokens one at a time, as Mermaid's Jison lexer for venn diagrams does: every rule
// ignores case, the first rule that matches wins, and a keyword must end at a word boundary.
class VennLexer(
  private val src: String,
  private val host: IndentHost,
) {
  // The text and position of the token `next` returned last.
  var text = ""
  var start = 0
  private var at = 0
  private var lineStart = false
  private var ended = false

  private fun word(at: Int, word: String): Boolean {
    val src = this.src
    for (k in word.indices) {
      val c = src.charCodeAt(at + k)
      val w = word[k].code
      if (c != w && (c + 32 != w || c < 65 || c > 90)) return false
    }
    return true
  }

  private fun keyword(at: Int, word: String): Boolean = this.word(at, word) && !isWord(this.src.charCodeAt(at + word.length))

  // The end of `rgb(1, 2, 3)` or `rgba(1, 2, 3, 0.5)` whose parenthesis opens at `at`, or -1.
  private fun color(at: Int, parts: Int): Int {
    val src = this.src
    var i = at + 1
    for (part in 0 until parts) {
      while (isSpace(src.charCodeAt(i))) i++
      val from = i
      while (isDigit(src.charCodeAt(i)) || src.charCodeAt(i) == 46) i++
      if (i == from) return -1
      while (isSpace(src.charCodeAt(i))) i++
      if (src.charCodeAt(i) != (if (part == parts - 1) 41 else 44)) return -1
      i++
    }
    return i
  }

  private fun afterLineFeed(at: Int): Boolean {
    var k = at - 1
    while (this.src.charCodeAt(k) == 32 || this.src.charCodeAt(k) == 9) k--
    return this.src.charCodeAt(k) == 10
  }

  private fun lineEnd(from: Int): Int {
    val end = this.src.indexOf('\n', from)
    return if (end == -1) this.src.length else end
  }

  fun next(): Int {
    val src = this.src
    val n = src.length
    while (true) {
      var i = this.at
      this.start = i
      this.text = ""
      if (i >= n) {
        if (this.ended) return T.END
        this.ended = true
        return T.EOF
      }
      val c = src.charCodeAt(i)
      if (c == 37 && src.charCodeAt(i + 1) == 37 && src.charCodeAt(i + 2) != 123) {
        this.at = this.lineEnd(i + 2)
        continue
      }
      if (this.lineStart && this.word(i, "title") && this.afterLineFeed(i) && src.jsSlice(i + 5, this.lineEnd(i + 5)).jsTrim() == "") {
        // Skip a bare title like a comment, so it doesn't end indented text.
        var j = this.lineEnd(i + 5)
        while (src.charCodeAt(j) == 10 || src.charCodeAt(j) == 13) j++
        this.at = j
        continue
      }
      if (this.lineStart) {
        if (c == 32 || c == 9) {
          var j = i + 1
          while (src.charCodeAt(j) == 32 || src.charCodeAt(j) == 9) j++
          this.at = j
          if (this.keyword(j, "text") && this.host.getIndentMode()) {
            // The word `text` itself is dropped; the indentation stands for it.
            this.at = j + 4
            this.lineStart = false
            this.text = src.jsSlice(i, j)
            return T.INDENT_TEXT
          }
          continue
        }
        if (c != 10 && c != 13) {
          this.host.setIndentMode(false)
          this.lineStart = false
        }
      }
      if (c == 10 || c == 13) {
        i++
        while (src.charCodeAt(i) == 10 || src.charCodeAt(i) == 13) i++
        this.lineStart = true
        return this.token(T.NEWLINE, i)
      }
      if (c == 37 && src.charCodeAt(i + 1) == 37) {
        this.at = this.lineEnd(i)
        continue
      }
      if (c == 32 || c == 9) {
        i++
        while (src.charCodeAt(i) == 32 || src.charCodeAt(i) == 9) i++
        this.at = i
        continue
      }

      if (((c or 32) >= 97 && (c or 32) <= 122) || c == 95) {
        val sep = src.charCodeAt(i + 5)
        if (this.word(i, "title") && isSpace(sep) && sep != 10 && sep != 13) {
          var end = i + 6
          while (end < n) {
            val ch = src.charCodeAt(end)
            if (ch == 35 || ch == 10 || ch == 59) break
            end++
          }
          if (end > i + 6) return this.token(T.TITLE, end)
        }
        for ((word, type) in KEYWORDS) {
          // A hyphen continues a name, as in `set-theory`.
          if (this.keyword(i, word) && src.charCodeAt(i + word.length) != 45) return this.token(type, i + word.length)
        }
        if (this.word(i, "rgb")) {
          val alpha = (src.charCodeAt(i + 3) or 32) == 97
          val open = i + (if (alpha) 4 else 3)
          val end = if (src.charCodeAt(open) == 40) this.color(open, if (alpha) 4 else 3) else -1
          if (end != -1) return this.token(if (alpha) T.RGBACOLOR else T.RGBCOLOR, end)
        }
        i++
        while (isWord(src.charCodeAt(i)) || src.charCodeAt(i) == 45) i++
        return this.token(T.IDENTIFIER, i)
      }
      if (c == 91) {
        if (src.charCodeAt(i + 1) == 34) {
          val quote = src.indexOf('"', i + 2)
          if (quote == -1 || src.charCodeAt(quote + 1) != 93) return T.ERROR
          this.at = quote + 2
          this.text = src.jsSlice(i + 2, quote)
          return T.BRACKET_LABEL
        }
        var end = i + 1
        while (end < n && src.charCodeAt(end) != 93 && src.charCodeAt(end) != 34) end++
        if (end == i + 1 || src.charCodeAt(end) != 93) return T.ERROR
        this.at = end + 1
        this.text = src.jsSlice(i + 1, end).jsTrim()
        return T.BRACKET_LABEL
      }
      if (isDigit(c) || c == 46 || c == 43 || c == 45) {
        if (c == 43 || c == 45) i++
        val from = i
        while (isDigit(src.charCodeAt(i))) i++
        if (i > from) {
          if (src.charCodeAt(i) == 46 && isDigit(src.charCodeAt(i + 1))) {
            i += 2
            while (isDigit(src.charCodeAt(i))) i++
          }
        } else if (src.charCodeAt(i) == 46 && isDigit(src.charCodeAt(i + 1))) {
          i += 2
          while (isDigit(src.charCodeAt(i))) i++
        } else {
          return T.ERROR
        }
        return this.token(T.NUMERIC, i)
      }
      if (c == 35) {
        var end = i + 1
        while (end < i + 9 && isHex(src.charCodeAt(end))) end++
        return if (end >= i + 4) this.token(T.HEXCOLOR, end) else T.ERROR
      }
      if (c == 34) {
        val quote = src.indexOf('"', i + 1)
        return if (quote == -1) T.ERROR else this.token(T.STRING, quote + 1)
      }
      if (c == 44) return this.token(T.COMMA, i + 1)
      if (c == 58) return this.token(T.COLON, i + 1)
      return T.ERROR
    }
  }

  private fun token(type: Int, end: Int): Int {
    this.text = this.src.jsSlice(this.at, end)
    this.at = end
    return type
  }
}
