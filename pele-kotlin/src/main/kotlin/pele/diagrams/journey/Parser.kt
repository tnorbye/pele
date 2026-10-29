// Ported from Pele src/diagrams/journey/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.journey

import pele.js.jsSubstring
import pele.js.jsTrim
import pele.syntaxError

private const val STATEMENT =
  "'EOF', 'SPACE', 'NEWLINE', 'title', 'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'section', 'taskName'"

fun parseJourney(src: String, db: JourneyDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts

  val fail = { i: Int, expected: String ->
    val got = if (types[i] == T.END) "1" else TOKEN_NAMES[types[i]]
    throw syntaxError("journey", src, starts[i], "Expecting $expected, got '$got'")
  }

  if (types[0] != T.journey) fail(0, "'journey'")

  var i = 1
  while (true) {
    val text = texts[i]
    when (types[i]) {
      T.NEWLINE -> {}
      T.title -> db.setDiagramTitle(text.jsSubstring(6))
      T.acc_title -> db.setAccTitle(texts[++i].jsTrim())
      T.acc_descr -> db.setAccDescription(texts[++i].jsTrim())
      T.acc_descr_multiline_value -> db.setAccDescription(text.jsTrim())
      T.section -> db.addSection(text.jsSubstring(8))
      T.taskName -> {
        if (types[++i] != T.taskData) fail(i, "'taskData'")
        db.addTask(text, texts[i])
      }
      T.EOF -> return
      else -> fail(i, STATEMENT)
    }
    i++
  }
}
