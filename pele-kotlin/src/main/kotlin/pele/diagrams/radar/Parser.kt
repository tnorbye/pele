// Ported from Pele src/diagrams/radar/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.radar

import pele.diagrams.common.ACC_DESCR
import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.NEWLINE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.STRING
import pele.diagrams.common.Scanner
import pele.diagrams.common.TokenType
import pele.diagrams.common.Tokens
import pele.diagrams.common.WHITESPACE
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.keyword
import pele.diagrams.common.stringValue
import pele.diagrams.common.titleBefore
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsNumber

private object T {
  const val radarColon = 0
  const val radar = 1
  const val showLegend = 2
  const val graticule = 3
  const val curve = 4
  const val ticks = 5
  const val axis = 6
  const val max = 7
  const val min = 8
  const val colon = 9
  const val comma = 10
  const val open = 11
  const val close = 12
  const val openBrace = 13
  const val closeBrace = 14
  const val graticuleValue = 15
  const val boolean = 16
  const val accDescr = 17
  const val accTitle = 18
  const val title = 19
  const val number = 20
  const val string = 21
  const val id = 22
  const val newline = 23
}

// PORT: upstream's module-level lexer state, kept per thread.
private class LexState {
  var runFrom = 0
  var runEnd = 0
  var lastBrace = -2
}

private val lexState: ThreadLocal<LexState> = ThreadLocal.withInitial { LexState() }

private fun digits(src: String, at: Int): Int {
  var end = at
  var c = src.charCodeAt(end)
  while (c >= 48 && c <= 57) {
    end++
    c = src.charCodeAt(end)
  }
  return end
}

// The NUMBER pattern without its backtracking. As a regular expression it reads to the end of a
// run of digits from every position in the run, which is quadratic for a long run of zeros.
private fun matchNumber(src: String, at: Int, previous: Int): Int {
  val state = lexState.get()
  if (at < state.runFrom || at >= state.runEnd) {
    state.runFrom = at
    state.runEnd = digits(src, at)
  }
  val whole = state.runEnd
  if (whole == at) return -1
  val dot = src.charCodeAt(whole) == 46
  if (dot) {
    val fraction = digits(src, whole + 1)
    val length = fraction - whole - 1
    if (length > 0 && src.charCodeAt(fraction) != 46) return fraction
    if (length > 1) return fraction - 1
  }
  if (src.charCodeAt(at) == 48) return at + 1
  if (!dot) return whole
  return if (whole - 1 > at) whole - 1 else -1
}

private val RE_ACC_DESCR = ACC_DESCR.pattern as JsRegExp
private val RE_BLOCK = JsRegExp("""[\t ]*accDescr\s*{""", "y")

// In the other grammars an `accDescr {` that never closes ends the lexing. Here `accDescr` is
// also a name and `{` a token, so each unclosed one would read to the end of the text again.
private fun matchAccDescr(src: String, at: Int, previous: Int): Int {
  val state = lexState.get()
  RE_BLOCK.lastIndex = at
  if (RE_BLOCK.test(src)) {
    if (state.lastBrace == -2) state.lastBrace = src.lastIndexOf('}')
    if (RE_BLOCK.lastIndex > state.lastBrace) return -1
  }
  RE_ACC_DESCR.lastIndex = at
  return if (RE_ACC_DESCR.test(src)) RE_ACC_DESCR.lastIndex else -1
}

private val ID = TokenType(name = "ID", pattern = JsRegExp("""[\w]([-\w]*\w)?""", "y"))
private val RE_NAME_REST = JsRegExp("""[-\w]*\w""", "y")

// Gives way to a longer name, except right after its option, as in `graticule polygonaxis`.
private fun optionValue(pattern: JsRegExp, option: Int): Scanner = { src, at, previous ->
  pattern.lastIndex = at
  if (!pattern.test(src)) {
    -1
  } else {
    val end = pattern.lastIndex
    if (previous == option) {
      end
    } else {
      RE_NAME_REST.lastIndex = end
      if (RE_NAME_REST.test(src)) -1 else end
    }
  }
}

private fun word(text: String): TokenType =
  TokenType(name = text, pattern = text, longer = listOf(ID))

private fun mark(text: String): TokenType = TokenType(name = text, pattern = text)

val RADAR_TOKENS: List<TokenType> =
  listOf(
    mark("radar-beta:"),
    keyword("radar-beta").copy(longer = listOf(ID)),
    word("showLegend"),
    word("graticule"),
    word("curve"),
    word("ticks"),
    word("axis"),
    word("max"),
    word("min"),
    mark(":"),
    mark(","),
    mark("["),
    mark("]"),
    mark("{"),
    mark("}"),
    TokenType(
      name = "GRATICULE",
      pattern = JsRegExp("""circle|polygon""", "y"),
      match = optionValue(JsRegExp("""circle|polygon""", "y"), T.graticule),
    ),
    TokenType(
      name = "BOOLEAN",
      pattern = JsRegExp("""true|false""", "y"),
      match = optionValue(JsRegExp("""true|false""", "y"), T.showLegend),
    ),
    ACC_DESCR.copy(match = ::matchAccDescr),
    ACC_TITLE,
    titleBefore("""[-\w]*\w"""),
    TokenType(
      name = "NUMBER",
      pattern = JsRegExp("""(?:[0-9]+\.[0-9]+(?!\.))|(?:0|[1-9][0-9]*(?!\.))""", "y"),
      match = ::matchNumber,
    ),
    STRING,
    ID,
    NEWLINE,
    WHITESPACE,
    YAML,
    DIRECTIVE,
    SINGLE_LINE_COMMENT,
  )

class AxisAst(
  val name: String,
  var label: String? = null,
)

class EntryAst(
  // Langium leaves the reference unresolved; Mermaid reads only its text ($refText).
  val axis: String? = null,
  val value: Double,
)

class CurveAst(
  val name: String,
  var label: String? = null,
  val entries: MutableList<EntryAst> = ArrayList(),
)

// name: 'showLegend' | 'ticks' | 'max' | 'min' | 'graticule'; value: Boolean | Double | String
class OptionAst(
  val name: String,
  val value: Any,
)

class RadarAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val axes: MutableList<AxisAst> = ArrayList(),
  val curves: MutableList<CurveAst> = ArrayList(),
  val options: MutableList<OptionAst> = ArrayList(),
)

fun lexRadar(src: String): Tokens {
  val state = lexState.get()
  state.runFrom = 0
  state.runEnd = 0
  state.lastBrace = -2
  return tokenize(src, RADAR_TOKENS, "radar")
}

fun parseRadar(src: String): RadarAst {
  val r = Reader(lexRadar(src), RADAR_TOKENS, "radar")
  val ast = RadarAst()

  val newlines = { while (r.kind == T.newline) r.i++ }

  val endOfLine = {
    if (r.kind != -1) {
      if (r.kind != T.newline) r.fail("a line break or the end of input")
      newlines()
    }
  }

  val label =
    fun(): String? {
      if (!r.accept(T.open)) return null
      val text = stringValue(r.expect(T.string))
      r.expect(T.close)
      return text
    }

  val entry =
    fun(detailed: Boolean): EntryAst {
      if (!detailed) return EntryAst(value = jsNumber(r.expect(T.number)))
      val axis = r.expect(T.id)
      r.accept(T.colon)
      return EntryAst(axis = axis, value = jsNumber(r.expect(T.number)))
    }

  val curve =
    fun(): CurveAst {
      val name = r.expect(T.id)
      val text = label()
      val node = CurveAst(name)
      if (text != null) node.label = text
      r.expect(T.openBrace)
      newlines()
      if (r.kind != T.number && r.kind != T.id) r.fail("a number or an axis name")
      val detailed = r.kind == T.id
      node.entries.add(entry(detailed))
      while (r.accept(T.comma)) {
        newlines()
        node.entries.add(entry(detailed))
      }
      newlines()
      r.expect(T.closeBrace)
      return node
    }

  val axis =
    fun(): AxisAst {
      val node = AxisAst(r.expect(T.id))
      val text = label()
      if (text != null) node.label = text
      return node
    }

  val option =
    fun(): OptionAst {
      val kind = r.kind
      val name = r.take()
      val value: Any =
        if (kind == T.showLegend) {
          r.expect(T.boolean) == "true"
        } else if (kind == T.graticule) {
          r.expect(T.graticuleValue)
        } else {
          jsNumber(r.expect(T.number))
        }
      return OptionAst(name, value)
    }

  val isOption = {
    r.kind == T.showLegend ||
      r.kind == T.ticks ||
      r.kind == T.max ||
      r.kind == T.min ||
      r.kind == T.graticule
  }

  newlines()
  if (!r.accept(T.radarColon)) {
    r.expect(T.radar)
    r.accept(T.colon)
  }
  newlines()
  while (r.kind != -1) {
    when (r.kind) {
      T.newline -> r.i++
      T.accDescr -> {
        ast.accDescr = accDescrValue(r.take())
        endOfLine()
      }
      T.accTitle -> {
        ast.accTitle = accTitleValue(r.take())
        endOfLine()
      }
      T.title -> {
        ast.title = titleValue(r.take())
        endOfLine()
      }
      T.axis -> {
        r.i++
        do {
          ast.axes.add(axis())
        } while (r.accept(T.comma))
      }
      T.curve -> {
        r.i++
        do {
          ast.curves.add(curve())
        } while (r.accept(T.comma))
      }
      else -> {
        if (!isOption()) r.fail("an axis, a curve, an option, a title, or a line break")
        ast.options.add(option())
        while (r.accept(T.comma)) {
          if (!isOption()) r.fail("an option")
          ast.options.add(option())
        }
      }
    }
  }
  return ast
}
