// Ported from Pele src/diagrams/railroad/ir.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.diagrams.common.Reader
import pele.diagrams.common.TokenType

// `railroad-beta`: Mermaid's own notation, one constructor call per node.

// PORT: named per file, as private classes of one package share a JVM namespace.
private object IrT {
  const val keyword = 0
  const val nonterminal = 1
  const val zeroOrMore = 2
  const val oneOrMore = 3
  const val sequence = 4
  const val optional = 5
  const val terminal = 6
  const val special = 7
  const val choice = 8
  const val equals = 9
  const val semicolon = 10
  const val open = 11
  const val comma = 12
  const val close = 13
  const val title = 14
  const val accTitle = 15
  const val accDescr = 16
  const val id = 17
  const val string = 18
}

private val ID = identifier("RR_ID")

private fun word(name: String): TokenType = literal(name, listOf(ID))

val IR_TOKENS: List<TokenType> =
  listOf(
    opening("railroad-beta", ID),
    word("nonterminal"),
    word("zeroOrMore"),
    word("oneOrMore"),
    word("sequence"),
    word("optional"),
    word("terminal"),
    word("special"),
    word("choice"),
    literal("="),
    literal(";"),
    literal("("),
    literal(","),
    literal(")"),
    title("""[\w-]"""),
    ACC_TITLE,
    ACC_DESCR,
    ID,
    quoted("RR_STRING"),
  ) +
    hiddenTokens("RR") +
    listOf(
      TokenType(
        name = "RR_BLOCK_COMMENT",
        pattern = pele.js.JsRegExp("""\/\*[\s\S]*?\*\/""", "y"),
        hidden = true,
        first = "/",
        opener = pele.js.JsRegExp("""\/\*""", "y"),
      )
    )

sealed class IrExpression : Parent {
  class Sequence(override val elements: MutableList<IrExpression> = ArrayList()) : IrExpression()

  class Choice(override val alternatives: MutableList<IrExpression> = ArrayList()) : IrExpression()

  // $type is RailroadOptionalExpr, RailroadOneOrMoreExpr or RailroadZeroOrMoreExpr.
  class Wrapper(val `$type`: String) : IrExpression() {
    override var element: Parent? = null
  }

  class Terminal(val value: String) : IrExpression()

  class NonTerminal(val name: String) : IrExpression()

  class Special(val text: String) : IrExpression()
}

class IrRule(
  override val name: String,
  override val definition: IrExpression,
) : Rule

typealias IrAst = Grammar<IrRule>

private fun argument(r: Reader): String {
  r.i++
  r.expect(IrT.open)
  val value = unescape(r.expect(IrT.string))
  r.expect(IrT.close)
  return value
}

private fun wrapper(`$type`: String): IrExpression = IrExpression.Wrapper(`$type`)

private fun expression(r: Reader): IrExpression {
  val open = ArrayList<IrExpression>()
  while (true) {
    var value: IrExpression =
      when (r.kind) {
        IrT.terminal -> IrExpression.Terminal(argument(r))
        IrT.nonterminal -> IrExpression.NonTerminal(argument(r))
        IrT.special -> IrExpression.Special(argument(r))
        IrT.sequence -> IrExpression.Sequence()
        IrT.choice -> IrExpression.Choice()
        IrT.optional -> wrapper("RailroadOptionalExpr")
        IrT.oneOrMore -> wrapper("RailroadOneOrMoreExpr")
        IrT.zeroOrMore -> wrapper("RailroadZeroOrMoreExpr")
        else -> r.fail("an expression")
      }
    if (
      value is IrExpression.Sequence ||
        value is IrExpression.Choice ||
        value is IrExpression.Wrapper
    ) {
      r.i++
      r.expect(IrT.open)
      open.add(value)
      continue
    }
    while (true) {
      val parent = open.getOrNull(open.size - 1) ?: return value
      if (parent is IrExpression.Wrapper) {
        parent.element = value
      } else {
        (if (parent is IrExpression.Sequence) parent.elements
          else (parent as IrExpression.Choice).alternatives)
          .add(value)
        if (r.accept(IrT.comma)) break
      }
      r.expect(IrT.close)
      open.removeAt(open.size - 1)
      value = parent
    }
  }
}

fun parseIr(src: String): IrAst {
  return parseGrammar(src, IR_TOKENS, "Railroad", IrT.title, ::unescape) { r ->
    val name = r.expect(IrT.id)
    r.expect(IrT.equals)
    val definition = expression(r)
    r.expect(IrT.semicolon)
    IrRule(name, definition)
  }
}

private fun node(e: Parent, kids: List<RailroadNode>): RailroadNode {
  return when (e) {
    is IrExpression.Terminal -> terminal(e.value)
    is IrExpression.NonTerminal -> nonterminal(e.name)
    is IrExpression.Special -> special(e.text)
    is IrExpression.Sequence -> sequence(kids)
    is IrExpression.Choice -> choice(kids)
    else -> {
      val w = e as IrExpression.Wrapper
      if (w.`$type` == "RailroadOptionalExpr") optional(kids[0])
      else repetition(kids[0], if (w.`$type` == "RailroadOneOrMoreExpr") 1.0 else 0.0)
    }
  }
}

val irRules = { ast: IrAst -> toRules(ast, ::node) }
