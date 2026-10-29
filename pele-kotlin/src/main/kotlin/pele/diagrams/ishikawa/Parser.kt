// Ported from Pele src/diagrams/ishikawa/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.ishikawa

import pele.js.jsSlice
import pele.js.jsTrim
import pele.syntaxError

private const val STOP = "'SPACELINE', 'NL', 'EOF'"

// Follows the parser Jison builds from Mermaid's grammar: blank lines and comments may come first,
// then the keyword and at most one line break, then statements. A statement is a line of text,
// with or without indentation, or a blank line; each is ended by a blank line, a line break or
// the end of the text, and any number of line breaks after that.
fun parseIshikawa(src: String, db: IshikawaDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val starts = tokens.starts
  val ends = tokens.ends
  var i = 0

  fun fail(expected: String): Nothing {
    throw syntaxError("ishikawa", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[types[i]]}'")
  }

  if (types[i] == T.SPACELINE) {
    i++
    while (types[i] == T.SPACELINE || types[i] == T.NL) i++
    if (types[i] != T.ISHIKAWA) fail("'SPACELINE', 'NL', 'ISHIKAWA'")
  } else if (types[i] != T.ISHIKAWA) {
    fail("'SPACELINE', 'ISHIKAWA'")
  }
  i++
  if (types[i] == T.NL) i++

  while (true) {
    val type = types[i]
    if (type == T.TEXT || (type == T.SPACELIST && types[i + 1] == T.TEXT)) {
      val text = if (type == T.TEXT) i else i + 1
      i = text + 1
      if (types[i] != T.SPACELINE && types[i] != T.NL && types[i] != T.EOF) fail(STOP)
      db.addNode(if (type == T.TEXT) 0 else ends[text - 1] - starts[text - 1], src.jsSlice(starts[text], ends[text]).jsTrim())
    } else if (type == T.SPACELINE || type == T.SPACELIST) {
      i++
      if (types[i] != T.SPACELINE && types[i] != T.NL && types[i] != T.EOF) {
        fail(if (type == T.SPACELIST) "$STOP, 'TEXT'" else STOP)
      }
    } else {
      fail("'SPACELINE', 'SPACELIST', 'TEXT'")
    }
    i++
    while (types[i] == T.NL || types[i] == T.EOF) i++
    if (types[i] == T.END) return
  }
}
