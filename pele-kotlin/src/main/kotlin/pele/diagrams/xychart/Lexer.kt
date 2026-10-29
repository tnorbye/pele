// Ported from Pele src/diagrams/xychart/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.xychart

import pele.diagrams.common.wordAt
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.util.isDigit
import pele.util.isLetter
import pele.util.isSpace
import pele.util.isWord

class Tokens(
  val types: MutableList<Int>,
  val texts: MutableList<String>,
  val starts: MutableList<Int>,
)

private object S {
  const val INITIAL = 0
  const val axis_data = 1
  const val axis_band_data = 2
  const val data = 3
  const val data_inner = 4
  const val string = 5
  const val acc_title = 6
  const val acc_descr = 7
  const val acc_descr_multiline = 8
}

// The token each state gives for the empty rest of the input.
private val AT_END = intArrayOf(T.EOF, T.EOF, T.EOF, T.EOF, T.EOF, T.STR, T.acc_title_value, T.acc_descr_value, T.acc_descr_multiline_value)

// Mermaid's grammar means to open a markdown string at `"` followed by a backtick, but its quoting turns
// the rule into one that matches this text and leaves the current state. Markdown strings are never read.
private val BROKEN_RULE =
  "`)" +
    " ".repeat(36) +
    "{ this.pushstate(md_string); }\n<md_string>(?:(?!`\").)+" +
    " ".repeat(18) +
    "{ return md_str; }\n<md_string>(?:`"

private val SINGLE: IntArray = run {
  val single = IntArray(128)
  val table = listOf(
    ':' to T.COLON,
    '+' to T.PLUS,
    ',' to T.COMMA,
    '=' to T.EQUALS,
    '*' to T.MULT,
    '#' to T.BRKT,
    '_' to T.UNDERSCORE,
    '.' to T.DOT,
    '&' to T.AMP,
    '-' to T.MINUS,
    ';' to T.SEMI,
  )
  for ((char, type) in table) single[char.code] = type
  single
}

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
  val push = { s: Int ->
    stack.add(state)
    state = s
  }
  val pop = {
    if (stack.size > 0) state = stack.removeAt(stack.size - 1)
  }
  val lineEnd = { q: Int ->
    val e = src.indexOf('\n', q)
    if (e == -1) n else e
  }
  val run = { q0: Int ->
    var q = q0
    while (q < n && isSpace(src.charCodeAt(q))) q++
    q
  }
  val digits = { q0: Int ->
    var q = q0
    while (isDigit(src.charCodeAt(q))) q++
    q
  }
  val keyword = { word: String -> wordAt(src, p, word) && !isWord(src.charCodeAt(p + word.length)) }

  while (p < n) {
    val c = src.charCodeAt(p)

    if (state == S.string) {
      if (c == 34) {
        p++
        pop()
      } else {
        val e = src.indexOf('"', p)
        emit(T.STR, p, if (e == -1) n else e)
      }
      continue
    }
    if (state == S.acc_title || state == S.acc_descr) {
      emit(AT_END[state], p, lineEnd(p))
      pop()
      continue
    }
    if (state == S.acc_descr_multiline) {
      if (c == 125) {
        p++
        pop()
      } else {
        val e = src.indexOf('}', p)
        emit(T.acc_descr_multiline_value, p, if (e == -1) n else e)
      }
      continue
    }

    val next = src.charCodeAt(p + 1)
    if (c == 37 && next == 37) {
      p = lineEnd(p + 2)
      continue
    }
    if (c == 10 || c == 13) {
      // Only a line break that directly follows an axis or a series ends it; one after spaces is skipped
      // with them, and the state carries on to the next line.
      if ((state == S.axis_data || state == S.data) && (c == 10 || next == 10)) {
        emit(T.NEWLINE, p, if (c == 10) p + 1 else p + 2)
        pop()
        continue
      }
      var e = p + 1
      var d = src.charCodeAt(e)
      while (d == 10 || d == 13) {
        e++
        d = src.charCodeAt(e)
      }
      emit(T.NEWLINE, p, e)
      continue
    }

    when (c or 32) {
      116 -> {
        if (keyword("title")) {
          emit(T.title, p, p + 5)
          continue
        }
      }
      97 -> {
        if (wordAt(src, p, "acc")) {
          val descr = wordAt(src, p + 3, "descr")
          if (descr || wordAt(src, p + 3, "title")) {
            val k = run(p + 8)
            val d = src.charCodeAt(k)
            if (d == 58) {
              emit(if (descr) T.acc_descr else T.acc_title, p, run(k + 1))
              push(if (descr) S.acc_descr else S.acc_title)
              continue
            }
            if (d == 123 && descr) {
              p = run(k + 1)
              push(S.acc_descr_multiline)
              continue
            }
          }
        }
      }
      120 -> {
        if (keyword("xychart-beta")) {
          emit(T.XYCHART, p, p + 12)
          continue
        }
        if (keyword("xychart")) {
          emit(T.XYCHART, p, p + 7)
          continue
        }
        if (keyword("x-axis")) {
          emit(T.X_AXIS, p, p + 6)
          push(S.axis_data)
          continue
        }
      }
      121 -> {
        if (keyword("y-axis")) {
          emit(T.Y_AXIS, p, p + 6)
          push(S.axis_data)
          continue
        }
      }
      118 -> {
        if (wordAt(src, p, "vertical")) {
          emit(T.CHART_ORIENTATION, p, p + 8)
          continue
        }
      }
      104 -> {
        if (wordAt(src, p, "horizontal")) {
          emit(T.CHART_ORIENTATION, p, p + 10)
          continue
        }
      }
      108 -> {
        if (keyword("line")) {
          emit(T.LINE, p, p + 4)
          push(S.data)
          continue
        }
      }
      98 -> {
        if (keyword("bar")) {
          emit(T.BAR, p, p + 3)
          push(S.data)
          continue
        }
      }
    }

    if (c == 91) {
      if (state == S.axis_data) push(S.axis_band_data)
      else if (state == S.data) push(S.data_inner)
      emit(T.SQUARE_BRACES_START, p, p + 1)
      continue
    }
    if (c == 93) {
      if (state == S.data_inner || state == S.axis_band_data) pop()
      emit(T.SQUARE_BRACES_END, p, p + 1)
      continue
    }
    if (state == S.axis_data && c == 45 && next == 45 && src.charCodeAt(p + 2) == 62) {
      emit(T.ARROW_DELIMITER, p, p + 3)
      continue
    }
    if (state == S.axis_data || state == S.data_inner) {
      val q = if (c == 43 || c == 45) p + 1 else p
      val d = src.charCodeAt(q)
      var e = -1
      if (isDigit(d)) {
        e = digits(q + 1)
        if (src.charCodeAt(e) == 46 && isDigit(src.charCodeAt(e + 1))) e = digits(e + 2)
      } else if (d == 46 && isDigit(src.charCodeAt(q + 1))) {
        e = digits(q + 2)
      }
      if (e != -1) {
        emit(T.NUMBER_WITH_DECIMAL, p, e)
        continue
      }
    }
    if (c == 96 && wordAt(src, p, BROKEN_RULE)) {
      p += BROKEN_RULE.length
      pop()
      continue
    }
    if (c == 34) {
      p++
      push(S.string)
      continue
    }
    if (isLetter(c)) {
      var e = p + 1
      while (isLetter(src.charCodeAt(e))) e++
      emit(T.ALPHA, p, e)
      continue
    }
    if (isDigit(c)) {
      emit(T.NUM, p, digits(p + 1))
      continue
    }
    if (isSpace(c)) {
      p = run(p + 1)
      continue
    }
    if (c < 128 && SINGLE[c] != 0) {
      emit(SINGLE[c], p, p + 1)
      continue
    }
    break
  }

  if (p < n) {
    types.add(T.ERROR)
    texts.add("")
    starts.add(p)
    return Tokens(types, texts, starts)
  }
  // Jison tries the rules of the current state once more against the empty rest of the input.
  types.add(AT_END[state])
  texts.add("")
  starts.add(n)
  types.add(T.END)
  texts.add("")
  starts.add(n)
  return Tokens(types, texts, starts)
}
