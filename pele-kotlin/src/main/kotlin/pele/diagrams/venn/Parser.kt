// Ported from Pele src/diagrams/venn/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.venn

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.jsParseFloat
import pele.js.jsSlice
import pele.syntaxError

private const val STATEMENT =
  "'EOF', 'NEWLINE', 'TITLE', 'SET', 'UNION', 'TEXT', 'INDENT_TEXT', 'STYLE'"
private const val VALUE = "'NUMERIC', 'IDENTIFIER', 'STRING', 'HEXCOLOR', 'RGBCOLOR', 'RGBACOLOR'"

// Ports Mermaid's venn grammar. A token is read only when the grammar has to look at one to
// decide, because the lexer asks the model whether an indented `text` continues a set, and the
// statements are what tell the model so.
fun parseVenn(src: String, db: VennDb) {
  val lexer = VennLexer(src, db)
  var type = -1

  fun peek(): Int {
    if (type == -1) type = lexer.next()
    return type
  }

  fun fail(expected: String): Nothing {
    if (peek() == T.ERROR) throw syntaxError("venn", src, lexer.start, "", true)
    throw syntaxError("venn", src, lexer.start, "Expecting $expected, got '${TOKEN_NAMES[type]}'")
  }

  fun take(): String {
    type = -1
    return lexer.text
  }

  fun expect(wanted: Int): String {
    if (peek() != wanted) fail("'${TOKEN_NAMES[wanted]}'")
    return take()
  }

  fun identifier(): String {
    if (peek() != T.IDENTIFIER && type != T.STRING) fail("'IDENTIFIER', 'STRING'")
    return take()
  }

  fun identifiers(): List<String> {
    val list = arrayListOf(identifier())
    while (peek() == T.COMMA) {
      take()
      list.add(identifier())
    }
    return list
  }

  fun subset(ids: List<String>, union: Boolean) {
    val label = if (peek() == T.BRACKET_LABEL) take() else null
    var size: Double? = null
    if (peek() == T.COLON) {
      take()
      size = jsParseFloat(expect(T.NUMERIC))
    }
    if (union) {
      if (ids.size < 2)
        throw PeleError(
          "union requires multiple identifiers",
          PeleErrorCode.SEMANTIC,
          PeleErrorInfo(type = "venn"),
        )
      db.validateUnionIdentifiers(ids)
    }
    db.addSubsetData(ids, label, size)
    db.setIndentMode(true)
  }

  // The id of a text, and the label in brackets that an id which is not a number may carry.
  fun text(sets: () -> List<String>) {
    val kind = peek()
    if (kind != T.IDENTIFIER && kind != T.STRING && kind != T.NUMERIC)
      fail("'NUMERIC', 'IDENTIFIER', 'STRING'")
    val id = take()
    val label = if (kind != T.NUMERIC && peek() == T.BRACKET_LABEL) take() else null
    db.addTextData(sets(), id, label)
  }

  fun style() {
    val ids = identifiers()
    val styles = ArrayList<Pair<String, String>>()
    while (true) {
      val key = expect(T.IDENTIFIER)
      expect(T.COLON)
      val value: String
      if (peek() == T.STRING) {
        value = take()
      } else {
        val parts = ArrayList<String>()
        while (peek() == T.IDENTIFIER || (type >= T.NUMERIC && type <= T.RGBACOLOR)) parts.add(
          take()
        )
        if (parts.size == 0) fail(VALUE)
        value = parts.joinToString(" ")
      }
      styles.add(key to value)
      if (peek() != T.COMMA) break
      take()
    }
    db.addStyleData(ids, styles)
  }

  while (peek() == T.NEWLINE) take()
  expect(T.VENN)
  while (true) {
    val statement = peek()
    if (statement == T.EOF) return
    if (statement < T.NEWLINE || statement > T.STYLE || statement == T.VENN) fail(STATEMENT)
    take()
    if (statement == T.TITLE) {
      db.setDiagramTitle(lexer.text.jsSlice(6))
    } else if (statement == T.SET) {
      subset(listOf(identifier()), false)
    } else if (statement == T.UNION) {
      subset(identifiers(), true)
    } else if (statement == T.TEXT) {
      val ids = identifiers()
      text { ids }
    } else if (statement == T.INDENT_TEXT) {
      text {
        val current =
          db.getCurrentSets()
            ?: throw PeleError(
              "text requires set",
              PeleErrorCode.SEMANTIC,
              PeleErrorInfo(type = "venn"),
            )
        current
      }
    } else if (statement == T.STYLE) {
      style()
    }
  }
}
