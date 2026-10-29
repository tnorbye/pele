// Ported from Pele src/diagrams/usecase/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.js.jsTrim
import pele.util.isBlank
import pele.util.isDigit
import pele.util.isHex
import pele.util.isLetter
import pele.written

class Tokens(
  val types: IntArray,
  val starts: IntArray,
  val ends: IntArray,
)

private const val WORD = 1
private const val PUNCTUATION = 2

private val CLASS =
  IntArray(128).also { table ->
    for (c in 48..57) table[c] = WORD
    for (c in 65..90) {
      table[c] = WORD
      table[c + 32] = WORD
    }
    table[95] = WORD
    for (ch in "!#$&*+/=?^|~") table[ch.code] = PUNCTUATION
  }

private val KEYWORD_LENGTH =
  intArrayOf(0, 12, 5, 14, 3, 9, 2, 2, 2, 2, 2, 4, 3, 4, 8, 5, 5, 7, 6, 4, 5)

private fun isWord(c: Int): Boolean = c in 0..127 && (CLASS[c] and WORD) != 0

// Mermaid's INCLUDE and EXTEND are the only keywords matched without regard to case.
private fun startsWithNoCase(src: String, at: Int, word: String): Boolean {
  for (k in word.indices) if ((src.charCodeAt(at + k) or 32) != word[k].code) return false
  return true
}

// The keyword that is a prefix of the text at `at`, tried in the order of Mermaid's token list.
private fun keywordAt(src: String, at: Int, c: Int): Int {
  return when (c) {
    97 -> if (src.startsWith("actor", at)) T.ACTOR else 0
    115 ->
      if (src.startsWith("systemBoundary", at)) T.SYSTEM_BOUNDARY
      else if (src.startsWith("style", at)) T.STYLE else 0
    101 ->
      if (src.startsWith("end", at)) T.END
      else if (startsWithNoCase(src, at, "extend")) T.EXTEND else 0
    69 -> if (startsWithNoCase(src, at, "extend")) T.EXTEND else 0
    100 -> if (src.startsWith("direction", at)) T.DIRECTION else 0
    84 -> if (src.startsWith("TD", at)) T.TD else if (src.startsWith("TB", at)) T.TB else 0
    66 -> if (src.startsWith("BT", at)) T.BT else 0
    76 -> if (src.startsWith("LR", at)) T.LR else 0
    82 -> if (src.startsWith("RL", at)) T.RL else 0
    110 -> if (src.startsWith("note", at)) T.NOTE else 0
    102 -> if (src.startsWith("for", at)) T.FOR else if (src.startsWith("false", at)) T.FALSE else 0
    106 -> if (src.startsWith("json", at)) T.JSON else 0
    99 ->
      if (src.startsWith("classDef", at)) T.CLASS_DEF
      else if (src.startsWith("class", at)) T.CLASS else 0
    105,
    73 -> if (startsWithNoCase(src, at, "include")) T.INCLUDE else 0
    116 -> if (src.startsWith("true", at)) T.TRUE else 0
    117 -> if (src.startsWith("usecase-beta", at)) T.USECASE else 0
    else -> 0
  }
}

fun lineColumn(srcIn: String, offsetIn: Int): Pair<Int, Int> {
  val (src, offset) = written(srcIn, offsetIn)
  var line = 1
  var lineStart = 0
  var i = 0
  while (i < offset) {
    val c = src.charCodeAt(i)
    if (c != 10 && c != 13) {
      i++
      continue
    }
    if (c == 13 && i + 1 < offset && src.charCodeAt(i + 1) == 10) i++
    line++
    lineStart = i + 1
    i++
  }
  return Pair(line, offset - lineStart + 1)
}

fun locationText(src: String, start: Int, end: Int): String {
  val (line, column) = lineColumn(src, start)
  return "line $line, column $column [${written(src, start).second},${written(src, end).second})"
}

// The end of a quoted string that opens at `at`, or -1 when the line ends first.
private fun stringEnd(src: String, at: Int, quote: Int): Int {
  for (k in at + 1 until src.length) {
    val c = src.charCodeAt(k)
    if (c == quote) return k + 1
    if (c == 10 || c == 13) return -1
  }
  return -1
}

private fun startsToken(src: String, at: Int): Boolean {
  val c = src.charCodeAt(at)
  if (c != 34 && c != 39) return true
  return (c == 34 && src.charCodeAt(at + 1) == 96) || stringEnd(src, at, c) != -1
}

// Mermaid reports the first character no token starts with, and how many characters its lexer
// skipped before one did.
private fun lexError(src: String, offset: Int, length: Int): PeleError {
  val (line, column) = lineColumn(src, offset)
  val from = written(src, offset).second
  val to = written(src, offset + length).second
  return PeleError(
    "Error lexing usecase diagram: unexpected character: ->${src.getOrNull(offset)?.toString() ?: ""}<- at offset: $from, skipped ${to - from} characters. at line $line, column $column [$from,$to)",
    PeleErrorCode.SYNTAX,
    PeleErrorInfo(type = "usecase", line = line, column = column),
  )
}

// Produces the tokens of Mermaid's Chevrotain lexer for use case diagrams: the first pattern in
// its list that matches wins, a keyword gives way to a longer identifier, and `json id@` and `<<`
// switch to modes that read a JSON object and a stereotype.
fun tokenize(src: String): Tokens {
  val types = ArrayList<Int>()
  val starts = ArrayList<Int>()
  val ends = ArrayList<Int>()
  val n = src.length
  var i = 0
  // Whether only blanks came before on this line, where comments and accessibility lines may start.
  var lineStart = true
  var noBrace = false

  while (i < n) {
    val c = src.charCodeAt(i)
    if (c == 32 || c == 9) {
      i++
      continue
    }
    var type: Int
    var end = i + 1
    var fresh = false

    if (c < 128 && (CLASS[c] and WORD) != 0) {
      type = 0
      if (c == 97 && lineStart && src.startsWith("acc", i)) {
        val descr = src.startsWith("Descr", i + 3)
        if (descr || src.startsWith("Title", i + 3)) {
          var j = i + 8
          while (isBlank(src.charCodeAt(j))) j++
          val next = src.charCodeAt(j)
          if (next == 58) {
            type = if (descr) T.ACC_DESCR_LINE else T.ACC_TITLE_LINE
            end = j + 1
            while (end < n && src.charCodeAt(end) != 10 && src.charCodeAt(end) != 13) end++
          } else if (next == 123 && descr && !noBrace) {
            val close = src.indexOf('}', j + 1)
            if (close == -1) noBrace = true
            else {
              type = T.ACC_DESCR_BLOCK
              end = close + 1
            }
          }
        }
      } else if (c == 106 && src.startsWith("json", i) && isBlank(src.charCodeAt(i + 4))) {
        var j = i + 5
        while (isBlank(src.charCodeAt(j))) j++
        val id = j
        while (isWord(src.charCodeAt(j))) j++
        if (j > id) {
          while (isBlank(src.charCodeAt(j))) j++
          if (src.charCodeAt(j) == 64) {
            j++
            while (isBlank(src.charCodeAt(j))) j++
            if (src.charCodeAt(j) == 123) {
              types.add(T.JSON_DECLARATION_START)
              starts.add(i)
              ends.add(j)
              i = j
              var depth = 0
              var quoted = false
              var escaped = false
              var k = j
              while (k < n) {
                val ch = src.charCodeAt(k)
                if (quoted) {
                  if (escaped) escaped = false
                  else if (ch == 92) escaped = true else if (ch == 34) quoted = false
                } else if (ch == 34) quoted = true
                else if (ch == 123) depth++ else if (ch == 125 && --depth == 0) break
                k++
              }
              types.add(if (k < n) T.JSON_OBJECT_LITERAL else T.UNCLOSED_JSON_OBJECT_LITERAL)
              starts.add(j)
              i = if (k < n) k + 1 else n
              ends.add(i)
              lineStart = false
              continue
            }
          }
        }
      }
      if (type == 0) {
        while (end < n && isWord(src.charCodeAt(end))) end++
        val keyword = keywordAt(src, i, c)
        if (keyword != 0) {
          // A keyword that is only the start of a longer word is an identifier.
          if (keyword == T.USECASE || end - i == KEYWORD_LENGTH[keyword]) {
            type = keyword
            end = i + KEYWORD_LENGTH[keyword]
          } else type = T.IDENTIFIER
        } else if (
          (c == 111 || c == 120) && src.charCodeAt(i + 1) == 45 && src.charCodeAt(i + 2) == 45
        ) {
          type = if (c == 111) T.BACKWARD_CIRCLE else T.BACKWARD_CROSS
          end = i + 3
        } else if (isDigit(c)) {
          // `1mg` is an identifier, and `1.5px` is a number because the identifier stops at the
          // dot.
          var digits = i + 1
          while (isDigit(src.charCodeAt(digits))) digits++
          if (src.charCodeAt(digits) == 46 && isDigit(src.charCodeAt(digits + 1))) {
            type = T.NUMBER
            end = digits + 2
            while (isDigit(src.charCodeAt(end))) end++
            while (isLetter(src.charCodeAt(end))) end++
          } else type = T.IDENTIFIER
        } else {
          type = T.IDENTIFIER
          while (src.charCodeAt(end) == 45 && isWord(src.charCodeAt(end + 1))) {
            type = T.CSS_IDENTIFIER
            end += 2
            while (isWord(src.charCodeAt(end))) end++
          }
        }
      }
    } else if (c == 10) {
      type = T.NEWLINE
      fresh = true
    } else if (c == 13) {
      type = T.NEWLINE
      fresh = true
      if (src.charCodeAt(end) == 10) end++
    } else if (c == 34 || c == 39) {
      if (c == 34 && src.charCodeAt(i + 1) == 96) {
        val close = src.indexOf("`\"", i + 2)
        type = if (close == -1) T.UNCLOSED_MARKDOWN_STRING else T.MARKDOWN_STRING
        end = if (close == -1) n else close + 2
      } else {
        type = T.PLAIN_STRING
        end = stringEnd(src, i, c)
        if (end == -1) {
          var resume = i + 1
          while (resume < n && !startsToken(src, resume)) resume++
          throw lexError(src, i, resume - i)
        }
      }
    } else if (c == 37) {
      if (lineStart && src.charCodeAt(end) == 37) {
        type = T.COMMENT
        while (end < n && src.charCodeAt(end) != 10 && src.charCodeAt(end) != 13) end++
      } else type = T.PERCENT
    } else if (c == 45) {
      if (src.charCodeAt(end) != 45) type = T.DASH
      else {
        while (src.charCodeAt(end) == 45) end++
        val next = src.charCodeAt(end)
        val two = end - i == 2
        if (two && next == 124 && src.charCodeAt(end + 1) == 62) {
          type = T.GENERALIZATION
          end += 2
        } else if (next == 62) {
          type = T.FORWARD_SOLID
          end++
        } else if (two && (next == 111 || next == 120)) {
          type = if (next == 111) T.FORWARD_CIRCLE else T.FORWARD_CROSS
          end++
        } else type = T.MARKERLESS_SOLID
      }
    } else if (c == 46) {
      val next = src.charCodeAt(end)
      if (next == 46 && src.charCodeAt(end + 1) == 62) {
        type = T.DEPENDENCY_ARROW
        end += 2
      } else if (isDigit(next)) {
        type = T.NUMBER
        while (isDigit(src.charCodeAt(end))) end++
        while (isLetter(src.charCodeAt(end))) end++
      } else type = T.DOT
    } else if (c == 60) {
      if (src.charCodeAt(end) == 60) {
        types.add(T.STEREOTYPE_START)
        starts.add(i)
        ends.add(i + 2)
        lineStart = false
        i += 2
        if (i >= n) break
        if (src.charCodeAt(i) == 62 && src.charCodeAt(i + 1) == 62) {
          type = T.STEREOTYPE_END
          end = i + 2
        } else {
          var close = i
          var found = false
          while (close < n) {
            val ch = src.charCodeAt(close)
            if (ch == 10 || ch == 13) break
            if (ch == 62 && src.charCodeAt(close + 1) == 62) {
              found = true
              break
            }
            close++
          }
          if (found && src.jsSlice(i, close).jsTrim() != "") {
            types.add(T.STEREOTYPE_TEXT)
            starts.add(i)
            ends.add(close)
            type = T.STEREOTYPE_END
            i = close
            end = close + 2
          } else {
            while (close < n && src.charCodeAt(close) != 10 && src.charCodeAt(close) != 13) close++
            if (close == i) {
              while (
                close < n && (src.charCodeAt(close) == 10 || src.charCodeAt(close) == 13)
              ) close++
              throw lexError(src, i, close - i)
            }
            type = T.UNCLOSED_STEREOTYPE_TEXT
            end = close
          }
        }
      } else if (src.charCodeAt(end) == 45 && src.charCodeAt(end + 1) == 45) {
        type = T.BACKWARD_SOLID
        end += 2
        while (src.charCodeAt(end) == 45) end++
      } else type = T.LABEL_PUNCTUATION
    } else if (c == 58) {
      if (src.charCodeAt(end) == 58 && src.charCodeAt(end + 1) == 58) {
        type = T.CLASS_SEPARATOR
        end += 2
      } else type = T.COLON
    } else if (c == 64) {
      if (src.charCodeAt(end) == 123) {
        type = T.METADATA_START
        end++
      } else type = T.AT
    } else if (c == 123) type = T.LBRACE
    else if (c == 125) type = T.RBRACE
    else if (c == 91) type = T.LBRACKET
    else if (c == 93) type = T.RBRACKET
    else if (c == 40) type = T.LPAREN
    else if (c == 41) type = T.RPAREN
    else if (c == 44) type = T.COMMA
    else if (c == 35) {
      while (isHex(src.charCodeAt(end))) end++
      type = if (end > i + 1) T.HASH_COLOR else T.CSS_PUNCTUATION
    } else if (c == 92) {
      if (src.charCodeAt(end) == 44) {
        type = T.CSS_ESCAPED_COMMA
        end++
      } else type = T.LABEL_PUNCTUATION
    } else if (c < 127 && c > 32) {
      type = if ((CLASS[c] and PUNCTUATION) != 0) T.CSS_PUNCTUATION else T.LABEL_PUNCTUATION
    } else {
      type = T.LABEL_SYMBOL
      while (end < n) {
        val ch = src.charCodeAt(end)
        if ((ch >= 32 && ch < 127) || ch == 9 || ch == 10 || ch == 13) break
        end++
      }
    }

    types.add(type)
    starts.add(i)
    ends.add(end)
    i = end
    lineStart = fresh
  }

  for (k in 0 until 3) {
    types.add(T.EOF)
    starts.add(n)
    ends.add(n)
  }
  return Tokens(types.toIntArray(), starts.toIntArray(), ends.toIntArray())
}
