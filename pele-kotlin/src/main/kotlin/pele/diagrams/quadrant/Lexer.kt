// Ported from Pele src/diagrams/quadrant/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.quadrant

import pele.diagrams.common.wordAt
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.util.isDigit
import pele.util.isLetter
import pele.util.isLineEnd
import pele.util.isSpace
import pele.util.isWord

class Tokens(
  val types: MutableList<Int>,
  val texts: MutableList<String>,
  val starts: MutableList<Int>,
)

private object S {
  const val INITIAL = 0
  const val title = 1
  const val acc_title = 2
  const val acc_descr = 3
  const val acc_descr_multiline = 4
  const val string = 5
  const val md_string = 6
  const val class_name = 7
  const val point_start = 8
  const val point_x = 9
  const val point_y = 10
}

// The token each state gives for the empty rest of the input, where it gives one.
private val AT_END = intArrayOf(T.EOF, T.title_value, T.acc_title_value, T.acc_descr_value, T.acc_descr_multiline_value, T.STR)

private val SINGLE: IntArray = run {
  val single = IntArray(128)
  val table = listOf(
    ":" to T.COLON,
    "+" to T.PLUS,
    "," to T.COMMA,
    "=" to T.EQUALS,
    "*" to T.MULT,
    "#" to T.BRKT,
    "_" to T.UNDERSCORE,
    "." to T.DOT,
    "&" to T.AMP,
    "-" to T.MINUS,
    " \t\u000B\u000C" to T.SPACE,
    ";" to T.SEMI,
    "!$%'`?\\/" to T.PUNCTUATION,
  )
  for ((chars, type) in table) {
    for (k in chars.indices) single[chars[k].code] = type
  }
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
  // The last run scanned of each kind, so that a long run is not scanned again from each of its characters.
  var spaceFrom = 0
  var spaceTo = 0
  var dashFrom = 0
  var dashTo = 0
  var wsFrom = 0
  var wsTo = 0
  var gapFrom = 0
  var gapTo = 0

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
  val spaces = { q0: Int ->
    var q = q0
    while (src.charCodeAt(q) == 32) q++
    q
  }
  val run = { q0: Int ->
    var q = q0
    while (q < n && isSpace(src.charCodeAt(q))) q++
    q
  }
  // Mermaid's `(1)|(0(.\d+)?)`, where the dot is any character within a line.
  val coordinate = { q: Int ->
    val c = src.charCodeAt(q)
    if (c == 49) {
      q + 1
    } else if (c != 48) {
      -1
    } else {
      var e = q + 1
      if (e < n && !isLineEnd(src.charCodeAt(e)) && isDigit(src.charCodeAt(e + 1))) {
        e += 2
        while (isDigit(src.charCodeAt(e))) e++
      }
      e
    }
  }

  lexing@ while (p < n) {
    val c = src.charCodeAt(p)
    val next = src.charCodeAt(p + 1)

    when (state) {
      S.INITIAL -> {}

      S.title, S.acc_title, S.acc_descr -> {
        emit(AT_END[state], p, lineEnd(p))
        pop()
        continue@lexing
      }

      S.acc_descr_multiline -> {
        if (c == 125) {
          p++
          pop()
        } else {
          val e = src.indexOf('}', p)
          emit(T.acc_descr_multiline_value, p, if (e == -1) n else e)
        }
        continue@lexing
      }

      S.string -> {
        if (c == 34) {
          p++
          pop()
        } else {
          val e = src.indexOf('"', p)
          emit(T.STR, p, if (e == -1) n else e)
        }
        continue@lexing
      }

      S.md_string -> {
        if (c == 96 && next == 34) {
          p += 2
          pop()
          continue@lexing
        }
        if (c != 96 && c != 34) {
          var e = p + 1
          var d = src.charCodeAt(e)
          while (e < n && d != 96 && d != 34) {
            e++
            d = src.charCodeAt(e)
          }
          emit(T.MD_STR, p, e)
          continue@lexing
        }
        break@lexing
      }

      S.class_name -> {
        if (isWord(c)) {
          var e = p + 1
          while (isWord(src.charCodeAt(e))) e++
          emit(T.class_name, p, e)
          pop()
          continue@lexing
        }
        break@lexing
      }

      S.point_start -> {
        val e = coordinate(p)
        if (e != -1) {
          emit(T.point_x, p, e)
          push(S.point_x)
          continue@lexing
        }
        val k = run(p)
        if (src.charCodeAt(k) == 93) {
          p = spaces(k + 1)
          pop()
          continue@lexing
        }
        break@lexing
      }

      S.point_x -> {
        val k = run(p)
        if (src.charCodeAt(k) == 44) {
          p = run(k + 1)
          state = S.point_y
          continue@lexing
        }
        break@lexing
      }

      S.point_y -> {
        val e = coordinate(p)
        if (e != -1) {
          emit(T.point_y, p, e)
          pop()
          continue@lexing
        }
        break@lexing
      }
    }

    if (c == 37 && next == 37) {
      p = lineEnd(p + 2)
      continue
    }
    if (c == 10 || c == 13) {
      var e = p + 1
      var d = src.charCodeAt(e)
      while (d == 10 || d == 13) {
        e++
        d = src.charCodeAt(e)
      }
      emit(T.NEWLINE, p, e)
      continue
    }

    val lower = c or 32
    if (lower == 116 && wordAt(src, p, "title") && !isWord(src.charCodeAt(p + 5))) {
      emit(T.title, p, p + 5)
      push(S.title)
      continue
    }
    if (lower == 97 && wordAt(src, p, "acc")) {
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

    // Axis, quadrant and chart keywords and the axis arrow take the spaces around them.
    var j = p
    if (c == 32) {
      if (p < spaceFrom || p >= spaceTo) {
        spaceFrom = p
        spaceTo = spaces(p)
      }
      j = spaceTo
    }
    val d = src.charCodeAt(j)
    val dl = d or 32
    var type = 0
    var end = 0
    if (dl == 120 && wordAt(src, j, "x-axis")) {
      type = T.X_AXIS
      end = j + 6
    } else if (dl == 121 && wordAt(src, j, "y-axis")) {
      type = T.Y_AXIS
      end = j + 6
    } else if (d == 45) {
      if (j < dashFrom || j >= dashTo) {
        dashFrom = j
        dashTo = j
        while (src.charCodeAt(dashTo) == 45) dashTo++
      }
      if (dashTo - j >= 2 && src.charCodeAt(dashTo) == 62) {
        type = T.DELIMITER
        end = dashTo + 1
      }
    } else if (dl == 113 && wordAt(src, j, "quadrant")) {
      val q = src.charCodeAt(j + 9)
      if (src.charCodeAt(j + 8) == 45 && q >= 49 && q <= 52) {
        type = T.QUADRANT_1 + q - 49
        end = j + 10
      } else if (wordAt(src, j + 8, "chart")) {
        type = T.QUADRANT
        end = j + 13
      }
    }
    if (type != 0) {
      emit(type, p, spaces(end))
      continue
    }

    if (lower == 99 && wordAt(src, p, "classdef") && !isWord(src.charCodeAt(p + 8))) {
      emit(T.CLASSDEF, p, p + 8)
      continue
    }
    if (c == 34) {
      if (next == 96) {
        p += 2
        push(S.md_string)
      } else {
        p++
        push(S.string)
      }
      continue
    }
    if (c == 58 && next == 58 && src.charCodeAt(p + 2) == 58) {
      p += 3
      push(S.class_name)
      continue
    }
    // A point's `: [`, with any whitespace before, between and after, line breaks included.
    if (c == 58 || isSpace(c)) {
      var k = p
      if (c != 58) {
        if (p < wsFrom || p >= wsTo) {
          wsFrom = p
          wsTo = run(p)
        }
        k = wsTo
      }
      if (src.charCodeAt(k) == 58) {
        if (k + 1 < gapFrom || k + 1 >= gapTo) {
          gapFrom = k + 1
          gapTo = run(k + 1)
        }
        if (src.charCodeAt(gapTo) == 91) {
          emit(T.point_start, p, run(gapTo + 1))
          push(S.point_start)
          continue
        }
      }
    }

    if (isLetter(c)) {
      var e = p + 1
      while (isLetter(src.charCodeAt(e))) e++
      emit(T.ALPHA, p, e)
      continue
    }
    if (c >= 128) {
      var e = p + 1
      while (src.charCodeAt(e) >= 128) e++
      emit(T.UNICODE_TEXT, p, e)
      continue
    }
    if (isDigit(c)) {
      var e = p + 1
      while (isDigit(src.charCodeAt(e))) e++
      emit(T.NUM, p, e)
      continue
    }
    if (SINGLE[c] != 0) {
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
  if (state < AT_END.size) {
    types.add(AT_END[state])
    texts.add("")
    starts.add(n)
  }
  types.add(T.END)
  texts.add("")
  starts.add(n)
  return Tokens(types, texts, starts)
}
