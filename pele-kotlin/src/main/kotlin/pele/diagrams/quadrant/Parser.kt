// Ported from Pele src/diagrams/quadrant/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.quadrant

import pele.js.jsTrim
import pele.syntaxError

private fun flags(vararg tokens: Int): ByteArray {
  val out = ByteArray(T.EOF + 1)
  for (t in tokens) out[t] = 1
  return out
}

// PORT: an index outside the array reads as undefined in JS, which is neither 0 nor 1.
private fun flag(array: ByteArray, t: Int): Int = if (t >= 0 && t < array.size) array[t].toInt() else -1

private val WORD = flags(
  T.PUNCTUATION,
  T.AMP,
  T.NUM,
  T.ALPHA,
  T.COMMA,
  T.PLUS,
  T.EQUALS,
  T.MULT,
  T.DOT,
  T.BRKT,
  T.UNDERSCORE,
  T.UNICODE_TEXT,
)
private val ID = flags(T.ALPHA, T.NUM, T.MINUS, T.COMMA, T.COLON, T.AMP, T.BRKT, T.MULT, T.UNICODE_TEXT)
private val STYLE = flags(T.ALPHA, T.NUM, T.COLON, T.SPACE, T.BRKT, T.MINUS)
private val EOL = flags(T.NEWLINE, T.SEMI, T.EOF)

private const val TEXT_TOKENS = "'STR', 'MD_STR', 'ALPHA', 'NUM', 'UNICODE_TEXT', 'PUNCTUATION'"

fun parseQuadrant(src: String, db: QuadrantDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  // PORT: reads past the end as undefined.
  fun typeAt(k: Int): Int = if (k >= 0 && k < types.size) types[k] else -1

  fun fail(expected: String): Nothing {
    val t = typeAt(i)
    if (t == T.ERROR) throw syntaxError("quadrantChart", src, starts[i], "", true)
    throw syntaxError("quadrantChart", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }

  fun expect(type: Int): String {
    if (typeAt(i) != type) fail("'${TOKEN_NAMES[type]}'")
    return texts[i++]
  }

  fun isText(t: Int): Boolean = t == T.STR || t == T.MD_STR || flag(WORD, t) == 1

  fun text(): QuadrantText {
    val first = typeAt(i)
    if (!isText(first)) fail(TEXT_TOKENS)
    var s = texts[i++]
    while (flag(WORD, typeAt(i)) == 1 || typeAt(i) == T.SPACE || typeAt(i) == T.MINUS) s += texts[i++]
    return QuadrantText(s, if (first == T.MD_STR) "markdown" else "text")
  }

  fun styles(): List<String> {
    val out = ArrayList<String>()
    while (true) {
      if (flag(STYLE, typeAt(i)) == 0) fail("'ALPHA', 'NUM', 'COLON', 'SPACE', 'BRKT', 'MINUS'")
      var s = texts[i++]
      while (flag(STYLE, typeAt(i)) == 1) s += texts[i++]
      out.add(s.jsTrim())
      if (typeAt(i) != T.COMMA) return out
      i++
    }
  }

  while (flag(EOL, typeAt(i)) == 1 || typeAt(i) == T.SPACE) i++
  expect(T.QUADRANT)

  while (typeAt(i) != T.END) {
    while (typeAt(i) == T.SPACE) i++
    val t = typeAt(i)
    when (t) {
      T.CLASSDEF -> {
        i++
        expect(T.SPACE)
        if (flag(ID, typeAt(i)) == 0) fail("'ALPHA', 'NUM', 'UNICODE_TEXT'")
        var id = texts[i++]
        while (flag(ID, typeAt(i)) == 1) id += texts[i++]
        expect(T.SPACE)
        db.addClass(id, styles())
      }
      T.X_AXIS, T.Y_AXIS -> {
        i++
        val from = text()
        var to: QuadrantText? = null
        if (typeAt(i) == T.DELIMITER) {
          i++
          if (isText(typeAt(i))) to = text()
          else from.text += " ⟶ "
        }
        if (t == T.X_AXIS) {
          db.setXAxisLeftText(from)
          if (to != null) db.setXAxisRightText(to)
        } else {
          db.setYAxisBottomText(from)
          if (to != null) db.setYAxisTopText(to)
        }
      }
      T.QUADRANT_1, T.QUADRANT_2, T.QUADRANT_3, T.QUADRANT_4 -> {
        i++
        when (t - T.QUADRANT_1) {
          0 -> db.setQuadrant1Text(text())
          1 -> db.setQuadrant2Text(text())
          2 -> db.setQuadrant3Text(text())
          else -> db.setQuadrant4Text(text())
        }
      }
      T.title -> {
        i++
        db.setDiagramTitle(expect(T.title_value).jsTrim())
      }
      T.acc_title -> {
        i++
        db.setAccTitle(expect(T.acc_title_value).jsTrim())
      }
      T.acc_descr -> {
        i++
        db.setAccDescription(expect(T.acc_descr_value).jsTrim())
      }
      T.acc_descr_multiline_value -> db.setAccDescription(texts[i++].jsTrim())
      else -> {
        if (isText(t)) {
          val label = text()
          val className = if (typeAt(i) == T.class_name) texts[i++] else ""
          expect(T.point_start)
          val x = expect(T.point_x)
          val y = expect(T.point_y)
          db.addPoint(label, className, x, y, if (flag(STYLE, typeAt(i)) == 1) styles() else emptyList())
        }
      }
    }
    if (flag(EOL, typeAt(i)) == 0) fail("'NEWLINE', 'SEMI', 'EOF'")
    i++
  }
}
