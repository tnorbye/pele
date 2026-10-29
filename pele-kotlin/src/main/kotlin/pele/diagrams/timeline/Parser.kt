// Ported from Pele src/diagrams/timeline/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.timeline

import pele.js.jsSubstring
import pele.js.jsTrim
import pele.syntaxError

private const val STATEMENT =
  "'EOF', 'SPACE', 'NEWLINE', 'title', 'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'section', 'period', 'event'"

fun parseTimeline(src: String, db: TimelineDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts

  val fail = { i: Int, expected: String ->
    val got = if (types[i] == T.END) "1" else TOKEN_NAMES[types[i]]
    throw syntaxError("timeline", src, starts[i], "Expecting $expected, got '$got'")
  }

  val head = types[0]
  if (head != T.timeline && head != T.timeline_lr && head != T.timeline_td) {
    fail(0, "'timeline', 'timeline_lr', 'timeline_td'")
  }
  if (head != T.timeline) db.setDirection(if (head == T.timeline_lr) "LR" else "TD")

  var i = 1
  while (true) {
    val text = texts[i]
    when (types[i]) {
      T.NEWLINE -> {}
      T.title -> db.getCommonDb().setDiagramTitle(text.jsSubstring(6))
      T.acc_title -> db.getCommonDb().setAccTitle(texts[++i].jsTrim())
      T.acc_descr -> db.getCommonDb().setAccDescription(texts[++i].jsTrim())
      T.acc_descr_multiline_value -> db.getCommonDb().setAccDescription(text.jsTrim())
      T.section -> db.addSection(text.jsSubstring(8))
      T.period -> db.addTask(text, 0.0, "")
      T.event -> db.addEvent(text.jsSubstring(2))
      T.EOF -> return
      else -> fail(i, STATEMENT)
    }
    i++
  }
}
