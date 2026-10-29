// Ported from Pele src/diagrams/wardley/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.wardley

import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.NEWLINE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.STRING
import pele.diagrams.common.Scanner
import pele.diagrams.common.TITLE
import pele.diagrams.common.TokenType
import pele.diagrams.common.WHITESPACE
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescr
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.stringValue
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsNumber
import pele.js.jsParseInt
import pele.js.jsSubstring
import pele.js.jsTrim

private object T {
  const val lbracket = 0
  const val comma = 1
  const val rbracket = 2
  const val at = 3
  const val slash = 4
  const val lparen = 5
  const val rparen = 6
  const val minus = 7
  const val lbrace = 8
  const val rbrace = 9
  const val number = 10
  const val arrow = 11
  const val linkPort = 12
  const val linkArrow = 13
  const val linkLabel = 14
  const val strategy = 15
  const val wardley = 16
  const val size = 17
  const val evolution = 18
  const val anchor = 19
  const val component = 20
  const val label = 21
  const val inertia = 22
  const val evolve = 23
  const val pipeline = 24
  const val note = 25
  const val annotations = 26
  const val annotation = 27
  const val accelerator = 28
  const val deaccelerator = 29
  const val name = 30
  const val ws = 31
  const val accDescr = 32
  const val accTitle = 33
  const val title = 34
  const val int = 35
  const val string = 36
  const val id = 37
  const val newline = 38
}

private fun digits(src: String, from: Int): Int {
  var i = from
  var c = src.charCodeAt(i)
  while (c >= 48 && c <= 57) {
    i++
    c = src.charCodeAt(i)
  }
  return i
}

// Answers as WARDLEY_NUMBER's pattern does. The pattern reads a whole run of digits before it
// finds there is no fraction, and would do so again from every digit of a long run.
private fun numberScanner(): Scanner {
  var from = 0
  var end = 0
  return { src, p, _ ->
    if (p < from || p >= end) {
      from = p
      end = digits(src, p)
    }
    if (end == p || src.charCodeAt(end) != 46) {
      -1
    } else {
      val stop = digits(src, end + 1)
      if (stop > end + 1) stop else -1
    }
  }
}

private val ARROW = TokenType(name = "ARROW", pattern = JsRegExp("""->""", "y"), first = "-")
private val LINK_ARROW =
  TokenType(
    name = "LINK_ARROW",
    pattern = JsRegExp("""-->|-\.->|>|\+'[^']*'<>|\+'[^']*'<|\+'[^']*'>""", "y"),
    first = "->+",
  )

private fun word(name: String, text: String): TokenType =
  TokenType(name = name, pattern = JsRegExp(text, "y"), first = text[0].toString())

val WARDLEY_TOKENS: List<TokenType> =
  listOf(
    TokenType(name = "[", pattern = "["),
    TokenType(name = ",", pattern = ","),
    TokenType(name = "]", pattern = "]"),
    TokenType(name = "@", pattern = "@"),
    TokenType(name = "/", pattern = "/"),
    TokenType(name = "(", pattern = "("),
    TokenType(name = ")", pattern = ")"),
    TokenType(name = "-", pattern = "-", longer = listOf(ARROW, LINK_ARROW, YAML)),
    TokenType(name = "{", pattern = "{"),
    TokenType(name = "}", pattern = "}"),
    TokenType(
      name = "WARDLEY_NUMBER",
      pattern = JsRegExp("""[0-9]+\.[0-9]+""", "y"),
      first = "0123456789",
      scanner = ::numberScanner,
    ),
    ARROW,
    TokenType(name = "LINK_PORT", pattern = JsRegExp("""\+<>|\+>|\+<""", "y"), first = "+"),
    LINK_ARROW,
    TokenType(name = "LINK_LABEL", pattern = JsRegExp(""";[^\n\r]+""", "y"), first = ";"),
    TokenType(
      name = "STRATEGY",
      pattern = JsRegExp("""build|buy|outsource|market""", "y"),
      first = "bom",
    ),
    word("KW_WARDLEY", "wardley-beta"),
    word("KW_SIZE", "size"),
    word("KW_EVOLUTION", "evolution"),
    word("KW_ANCHOR", "anchor"),
    word("KW_COMPONENT", "component"),
    word("KW_LABEL", "label"),
    word("KW_INERTIA", "inertia"),
    word("KW_EVOLVE", "evolve"),
    word("KW_PIPELINE", "pipeline"),
    word("KW_NOTE", "note"),
    word("KW_ANNOTATIONS", "annotations"),
    word("KW_ANNOTATION", "annotation"),
    word("KW_ACCELERATOR", "accelerator"),
    word("KW_DEACCELERATOR", "deaccelerator"),
    TokenType(
      name = "NAME_WITH_SPACES",
      pattern =
        JsRegExp(
          """(?!title\s|accTitle|accDescr)[A-Za-z](?:[A-Za-z0-9_()&]|-(?!>))*(?:[ \t]+[A-Za-z(](?:[A-Za-z0-9_()&]|-(?!>))*)*""",
          "y",
        ),
      first = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    ),
    TokenType(name = "WS", pattern = JsRegExp("""[ \t]+""", "y"), hidden = true, first = "\t "),
    accDescr(),
    ACC_TITLE,
    TITLE,
    TokenType(
      name = "INT",
      pattern = JsRegExp("""0|[1-9][0-9]*(?!\.)""", "y"),
      first = "0123456789",
    ),
    STRING,
    TokenType(
      name = "ID",
      pattern = JsRegExp("""[\w]([-\w]*\w)?""", "y"),
      first = "_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    ),
    NEWLINE,
    WHITESPACE,
    YAML,
    DIRECTIVE,
    SINGLE_LINE_COMMENT,
  )

class LabelAst(
  val negX: Boolean,
  val offsetX: Double,
  val negY: Boolean,
  val offsetY: Double,
)

class StageAst(
  val name: String,
  var boundary: Double? = null,
  var secondName: String? = null,
)

class DecoratorAst(val strategy: String)

class ComponentAst(
  val name: String,
  var visibility: Double,
  var evolution: Double,
  var label: LabelAst? = null,
  var decorator: DecoratorAst? = null,
  var inertia: Boolean,
)

class PipelineComponentAst(
  val name: String,
  var evolution: Double,
  var label: LabelAst? = null,
)

class LinkAst(
  val from: String,
  var fromPort: String? = null,
  var arrow: String? = null,
  var to: String,
  var toPort: String? = null,
  var linkLabel: String? = null,
)

class SizeAst(val width: Double, val height: Double)

class EvolutionAst(val stages: MutableList<StageAst>)

class AnchorAst(val name: String, val visibility: Double, val evolution: Double)

class EvolveAst(val component: String, val target: Double)

class PipelineAst(val parent: String, val components: MutableList<PipelineComponentAst>)

class NoteAst(val text: String, val visibility: Double, val evolution: Double)

class AnnotationsAst(val x: Double, val y: Double)

class AnnotationAst(val number: Double, val x: Double, val y: Double, val text: String)

// PORT: upstream mixes an unexported `Placed` interface into these two.
class AcceleratorAst(val name: String, val x: Double, val y: Double)

class DeacceleratorAst(val name: String, val x: Double, val y: Double)

class WardleyAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  var size: SizeAst? = null,
  var evolution: EvolutionAst? = null,
  val anchors: MutableList<AnchorAst> = ArrayList(),
  val components: MutableList<ComponentAst> = ArrayList(),
  val links: MutableList<LinkAst> = ArrayList(),
  val evolves: MutableList<EvolveAst> = ArrayList(),
  val pipelines: MutableList<PipelineAst> = ArrayList(),
  val notes: MutableList<NoteAst> = ArrayList(),
  val annotations: MutableList<AnnotationsAst> = ArrayList(),
  val annotation: MutableList<AnnotationAst> = ArrayList(),
  val accelerators: MutableList<AcceleratorAst> = ArrayList(),
  val deaccelerators: MutableList<DeacceleratorAst> = ArrayList(),
)

fun parseWardley(src: String): WardleyAst {
  val r = Reader(tokenize(src, WARDLEY_TOKENS, "wardley"), WARDLEY_TOKENS, "wardley")
  val ast = WardleyAst()

  fun at(kind: Int): Boolean = r.kinds.getOrNull(r.i) == kind
  fun isName(): Boolean = at(T.string) || at(T.id) || at(T.name)
  fun name(): String {
    if (at(T.string)) return stringValue(r.take())
    if (at(T.id) || at(T.name)) return r.take()
    return r.fail("a name")
  }
  fun number(): Double = jsNumber(r.expect(T.number))
  fun int(): Double = jsParseInt(r.expect(T.int))
  fun coordinate(): Double = if (at(T.number)) jsNumber(r.take()) else jsParseInt(r.expect(T.int))
  fun endOfLine() {
    if (at(-1)) return
    if (!at(T.newline)) r.fail("a line break or the end of input")
    while (at(T.newline)) r.i++
  }
  fun pair(read: () -> Double): Pair<Double, Double> {
    r.expect(T.lbracket)
    val first = read()
    r.expect(T.comma)
    val second = read()
    r.expect(T.rbracket)
    return Pair(first, second)
  }
  fun label(): LabelAst {
    r.expect(T.label)
    r.expect(T.lbracket)
    val negX = r.accept(T.minus)
    val offsetX = int()
    r.expect(T.comma)
    val negY = r.accept(T.minus)
    val offsetY = int()
    r.expect(T.rbracket)
    return LabelAst(negX, offsetX, negY, offsetY)
  }
  fun stage(): StageAst {
    val out = StageAst(name())
    if (r.accept(T.at)) out.boundary = number()
    if (r.accept(T.slash)) out.secondName = name()
    return out
  }

  while (at(T.newline)) r.i++
  r.expect(T.wardley)
  while (!at(-1)) {
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
      T.size -> {
        r.i++
        val (width, height) = pair(::int)
        endOfLine()
        ast.size = SizeAst(width, height)
      }
      T.evolution -> {
        r.i++
        val stages = mutableListOf(stage())
        r.expect(T.arrow)
        stages.add(stage())
        while (r.accept(T.arrow)) stages.add(stage())
        endOfLine()
        ast.evolution = EvolutionAst(stages)
      }
      T.anchor -> {
        r.i++
        val anchor = name()
        val (visibility, evolution) = pair(::number)
        endOfLine()
        ast.anchors.add(AnchorAst(anchor, visibility, evolution))
      }
      T.component -> {
        r.i++
        val component = ComponentAst(name(), 0.0, 0.0, inertia = false)
        val (visibility, evolution) = pair(::number)
        component.visibility = visibility
        component.evolution = evolution
        if (at(T.label)) component.label = label()
        if (at(T.lparen) && r.kinds.getOrNull(r.i + 1) == T.strategy) {
          r.i++
          component.decorator = DecoratorAst(r.take())
          r.expect(T.rparen)
        }
        if (r.accept(T.inertia)) {
          component.inertia = true
        } else if (r.accept(T.lparen)) {
          r.expect(T.inertia)
          r.expect(T.rparen)
          component.inertia = true
        }
        endOfLine()
        ast.components.add(component)
      }
      T.evolve -> {
        r.i++
        val component = name()
        val target = number()
        endOfLine()
        ast.evolves.add(EvolveAst(component, target))
      }
      T.pipeline -> {
        r.i++
        val parent = name()
        r.expect(T.lbrace)
        r.expect(T.newline)
        while (at(T.newline)) r.i++
        val components = ArrayList<PipelineComponentAst>()
        do {
          r.expect(T.component)
          val component = PipelineComponentAst(name(), 0.0)
          r.expect(T.lbracket)
          component.evolution = number()
          r.expect(T.rbracket)
          if (at(T.label)) component.label = label()
          endOfLine()
          components.add(component)
        } while (at(T.component))
        r.expect(T.rbrace)
        endOfLine()
        ast.pipelines.add(PipelineAst(parent, components))
      }
      T.note -> {
        r.i++
        val text = stringValue(r.expect(T.string))
        val (visibility, evolution) = pair(::number)
        endOfLine()
        ast.notes.add(NoteAst(text, visibility, evolution))
      }
      T.annotations -> {
        r.i++
        val (x, y) = pair(::coordinate)
        endOfLine()
        ast.annotations.add(AnnotationsAst(x, y))
      }
      T.annotation -> {
        r.i++
        val n = int()
        r.expect(T.comma)
        val (x, y) = pair(::coordinate)
        val text = stringValue(r.expect(T.string))
        endOfLine()
        ast.annotation.add(AnnotationAst(n, x, y, text))
      }
      T.accelerator,
      T.deaccelerator -> {
        val slow = r.kind == T.deaccelerator
        r.i++
        val force = name()
        val (x, y) = pair(::number)
        endOfLine()
        if (slow) ast.deaccelerators.add(DeacceleratorAst(force, x, y))
        else ast.accelerators.add(AcceleratorAst(force, x, y))
      }
      else -> {
        if (!isName()) r.fail("a statement")
        val link = LinkAst(name(), to = "")
        if (at(T.linkPort)) link.fromPort = r.take()
        if (at(T.linkArrow) || at(T.arrow)) link.arrow = r.take()
        link.to = name()
        if (at(T.linkPort)) link.toPort = r.take()
        if (at(T.linkLabel)) link.linkLabel = r.take().jsSubstring(1).jsTrim()
        endOfLine()
        ast.links.add(link)
      }
    }
  }
  return ast
}
