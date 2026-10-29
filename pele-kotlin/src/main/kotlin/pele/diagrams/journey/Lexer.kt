// Ported from Pele src/diagrams/journey/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.journey

import pele.diagrams.common.ScanTokens
import pele.diagrams.common.accessibility
import pele.diagrams.common.keywordAt
import pele.diagrams.common.skipTrivia
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.util.isSpace
import pele.util.isWord

object T {
  const val END = 0
  const val EOF = 1
  const val INVALID = 2
  const val NEWLINE = 3
  const val journey = 4
  const val title = 5
  const val acc_title = 6
  const val acc_title_value = 7
  const val acc_descr = 8
  const val acc_descr_value = 9
  const val acc_descr_multiline_value = 10
  const val section = 11
  const val taskName = 12
  const val taskData = 13
  const val colon = 14
}

val TOKEN_NAMES = listOf(
  "\$end",
  "EOF",
  "INVALID",
  "NEWLINE",
  "journey",
  "title",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "section",
  "taskName",
  "taskData",
  ":",
)

// `#`, a line break, and `;` end every kind of text; a colon also ends a task name or a section.
private fun textEnd(src: String, from: Int, colon: Boolean): Int {
  val n = src.length
  var e = from
  while (e < n) {
    val c = src.charCodeAt(e)
    if (c == 35 || c == 10 || c == 59 || (colon && c == 58)) break
    e++
  }
  return e
}

fun tokenize(src: String): ScanTokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val out = ScanTokens(types, texts, starts)
  var p = 0

  val emit = { type: Int, e: Int ->
    types.add(type)
    texts.add(src.jsSlice(p, e))
    starts.add(p)
    p = e
  }

  while (true) {
    p = skipTrivia(src, p)
    if (p >= n) break
    val c = src.charCodeAt(p)
    var e = p + 1
    if (c == 10) {
      while (src.charCodeAt(e) == 10) e++
      emit(T.NEWLINE, e)
      continue
    }
    if (keywordAt(src, p, "journey") && !isWord(src.charCodeAt(p + 7))) {
      emit(T.journey, p + 7)
      continue
    }
    if (keywordAt(src, p, "title")) {
      // Unlike Mermaid, ignore bare titles.
      e = p + 5
      while (src.charCodeAt(e) != 10 && isSpace(src.charCodeAt(e))) e++
      if (e == n || src.charCodeAt(e) == 10) {
        p = e
        continue
      }
      val sep = src.charCodeAt(p + 5)
      if (sep != 13 && isSpace(sep)) {
        e = textEnd(src, p + 6, false)
        if (e > p + 6) {
          emit(T.title, e)
          continue
        }
      }
    }
    e = accessibility(src, p, out, T.acc_title)
    if (e != p) {
      p = e
      continue
    }
    // Like Mermaid, a bare section uses the next line as its name.
    if (keywordAt(src, p, "section") && isSpace(src.charCodeAt(p + 7))) {
      e = textEnd(src, p + 8, true)
      if (e > p + 8) {
        emit(T.section, e)
        continue
      }
    }
    if (c == 58) {
      e = textEnd(src, p + 1, false)
      emit(if (e > p + 1) T.taskData else T.colon, if (e > p + 1) e else p + 1)
    } else if (c == 59) {
      emit(T.INVALID, p + 1)
    } else {
      emit(T.taskName, textEnd(src, p + 1, true))
    }
  }

  // Jison marks its lexer done when a rule runs on empty input, so an accessibility value that
  // matches nothing at the very end of the text is followed by no EOF token.
  if (starts.isEmpty() || starts[starts.size - 1] < n) emit(T.EOF, n)
  emit(T.END, n)
  return out
}
