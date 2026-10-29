// Ported from Pele src/diagrams/xychart/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.xychart

import pele.js.jsNumber
import pele.js.jsTrim
import pele.syntaxError

private fun flags(vararg tokens: Int): ByteArray {
  val out = ByteArray(T.EOF + 1)
  for (t in tokens) out[t] = 1
  return out
}

// PORT: an index outside the array reads as undefined in JS, which is neither 0 nor 1.
private fun flag(array: ByteArray, t: Int): Int = if (t >= 0 && t < array.size) array[t].toInt() else -1

private val WORD = flags(T.AMP, T.NUM, T.ALPHA, T.PLUS, T.EQUALS, T.MULT, T.DOT, T.BRKT, T.MINUS, T.UNDERSCORE)
private val EOL = flags(T.NEWLINE, T.SEMI, T.EOF)

fun parseXyChart(src: String, db: XyChartDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  // PORT: reads past the end as undefined.
  fun typeAt(k: Int): Int = if (k >= 0 && k < types.size) types[k] else -1

  fun fail(expected: String): Nothing {
    val t = typeAt(i)
    if (t == T.ERROR) throw syntaxError("xychart", src, starts[i], "", true)
    throw syntaxError("xychart", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }

  fun expect(type: Int): String {
    if (typeAt(i) != type) fail("'${TOKEN_NAMES[type]}'")
    return texts[i++]
  }

  fun isText(t: Int): Boolean = t == T.STR || flag(WORD, t) == 1

  fun text(): XyText {
    if (!isText(typeAt(i))) fail("'STR', 'ALPHA', 'NUM'")
    var s = texts[i++]
    if (typeAt(i - 1) != T.STR) while (flag(WORD, typeAt(i)) == 1) s += texts[i++]
    return XyText(s, "text")
  }

  fun range(): Pair<Double, Double> {
    val min = jsNumber(expect(T.NUMBER_WITH_DECIMAL))
    expect(T.ARROW_DELIMITER)
    return Pair(min, jsNumber(expect(T.NUMBER_WITH_DECIMAL)))
  }

  fun points(): List<DataPoint> {
    val out = ArrayList<DataPoint>()
    expect(T.SQUARE_BRACES_START)
    while (true) {
      val value = jsNumber(expect(T.NUMBER_WITH_DECIMAL))
      out.add(DataPoint(value, if (typeAt(i) == T.STR) texts[i++] else ""))
      if (typeAt(i) != T.COMMA) break
      i++
    }
    expect(T.SQUARE_BRACES_END)
    return out
  }

  while (true) {
    if (flag(EOL, typeAt(i)) == 1) i++
    else if (typeAt(i) != T.XYCHART) break
    else if (typeAt(++i) == T.CHART_ORIENTATION) db.setOrientation(texts[i++])
  }

  while (typeAt(i) != T.END) {
    val t = typeAt(i++)
    when (t) {
      T.title -> db.setDiagramTitle(text().text.jsTrim())
      T.X_AXIS, T.Y_AXIS -> {
        val title = if (isText(typeAt(i))) text() else null
        if (t == T.X_AXIS && typeAt(i) == T.SQUARE_BRACES_START) {
          val categories = ArrayList<XyText>()
          do {
            i++
            categories.add(text())
          } while (typeAt(i) == T.COMMA)
          expect(T.SQUARE_BRACES_END)
          db.setXAxisBand(categories)
        } else if (typeAt(i) == T.NUMBER_WITH_DECIMAL || title == null) {
          val (min, max) = range()
          if (t == T.X_AXIS) db.setXAxisRangeData(min, max)
          else db.setYAxisRangeData(min, max)
        }
        if (t == T.X_AXIS) db.setXAxisTitle(title ?: XyText("", "text"))
        else db.setYAxisTitle(title ?: XyText("", "text"))
      }
      T.LINE, T.BAR -> {
        val title: XyText = if (typeAt(i) == T.SQUARE_BRACES_START) XyText("", "text") else text()
        val data = points()
        if (t == T.LINE) db.setLineData(title, data)
        else db.setBarData(title, data)
      }
      T.acc_title -> db.setAccTitle(expect(T.acc_title_value).jsTrim())
      T.acc_descr -> db.setAccDescription(expect(T.acc_descr_value).jsTrim())
      T.acc_descr_multiline_value -> db.setAccDescription(texts[i - 1].jsTrim())
      else -> {
        i--
        fail("'title', 'X_AXIS', 'Y_AXIS', 'LINE', 'BAR', 'acc_title', 'acc_descr', 'acc_descr_multiline_value'")
      }
    }
    while (flag(EOL, typeAt(i)) == 1) i++
  }
}
