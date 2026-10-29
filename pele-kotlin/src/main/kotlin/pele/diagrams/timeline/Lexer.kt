// Ported from Pele src/diagrams/timeline/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.timeline

import pele.diagrams.common.ScanTokens
import pele.diagrams.common.accessibility
import pele.diagrams.common.keywordAt
import pele.diagrams.common.lineEnd
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
  const val timeline = 4
  const val timeline_lr = 5
  const val timeline_td = 6
  const val title = 7
  const val acc_title = 8
  const val acc_title_value = 9
  const val acc_descr = 10
  const val acc_descr_value = 11
  const val acc_descr_multiline_value = 12
  const val section = 13
  const val period = 14
  const val event = 15
}

val TOKEN_NAMES = listOf(
  "\$end",
  "EOF",
  "INVALID",
  "NEWLINE",
  "timeline",
  "timeline_lr",
  "timeline_td",
  "title",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "section",
  "period",
  "event",
)

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
    if (keywordAt(src, p, "timeline")) {
      e = p + 8
      while (src.charCodeAt(e) == 32 || src.charCodeAt(e) == 9) e++
      val lr = keywordAt(src, e, "lr")
      if (e > p + 8 && (lr || keywordAt(src, e, "td")) && !isWord(src.charCodeAt(e + 2))) {
        emit(if (lr) T.timeline_lr else T.timeline_td, e + 2)
        continue
      }
      if (!isWord(src.charCodeAt(p + 8))) {
        emit(T.timeline, p + 8)
        continue
      }
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
        emit(T.title, lineEnd(src, p + 6))
        continue
      }
    }
    e = accessibility(src, p, out, T.acc_title)
    if (e != p) {
      p = e
      continue
    }
    // Like Mermaid, a bare section uses the next line as its name.
    if (keywordAt(src, p, "section") && isSpace(src.charCodeAt(p + 7))) {
      e = p + 8
      while (e < n && src.charCodeAt(e) != 58 && src.charCodeAt(e) != 10) e++
      if (e > p + 8) {
        emit(T.section, e)
        continue
      }
    }
    if (c != 58) {
      e = p + 1
      var k = src.charCodeAt(e)
      while (e < n && k != 35 && k != 58 && k != 10) {
        k = src.charCodeAt(++e)
      }
      emit(T.period, e)
      continue
    }
    // An event runs to the end of the line or to a colon that is followed by whitespace.
    if (isSpace(src.charCodeAt(p + 1))) {
      e = p + 2
      while (e < n) {
        val k = src.charCodeAt(e)
        if (k == 10 || (k == 58 && isSpace(src.charCodeAt(e + 1)))) break
        e++
      }
      if (e > p + 2) {
        emit(T.event, e)
        continue
      }
    }
    emit(T.INVALID, p + 1)
  }

  // Jison marks its lexer done when a rule runs on empty input, so an accessibility value that
  // matches nothing at the very end of the text is followed by no EOF token.
  if (starts.isEmpty() || starts[starts.size - 1] < n) emit(T.EOF, n)
  emit(T.END, n)
  return out
}
