// Ported from Pele src/diagrams/railroad/peg.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.diagrams.common.Reader
import pele.diagrams.common.TokenType
import pele.js.JsRegExp

// `railroad-peg-beta`: parsing expression grammars.

// PORT: named per file, as private classes of one package share a JVM namespace.
private object PegT {
  const val keyword = 0
  const val arrow = 1
  const val semicolon = 2
  const val slash = 3
  const val and = 4
  const val not = 5
  const val question = 6
  const val star = 7
  const val plus = 8
  const val group = 9
  const val groupEnd = 10
  const val dot = 11
  const val title = 12
  const val accTitle = 13
  const val accDescr = 14
  const val id = 15
  const val string = 16
}

private val ID = identifier("PEG_ID")

val PEG_TOKENS: List<TokenType> = listOf(
  opening("railroad-peg-beta", ID),
  literal("<-"),
  literal(";"),
  literal("/"),
  literal("&"),
  literal("!"),
  literal("?"),
  literal("*"),
  literal("+"),
  literal("("),
  literal(")"),
  literal("."),
  title("""[\w-]"""),
  ACC_TITLE,
  ACC_DESCR,
  ID,
  quoted("PEG_STRING"),
) + hiddenTokens("PEG") + listOf(
  TokenType(name = "PEG_LINE_COMMENT", pattern = JsRegExp("""#[^\n\r]*""", "y"), hidden = true, first = "#"),
)

sealed class PegPrimary : Parent {
  class Literal(val value: String) : PegPrimary()

  class Identifier(val name: String) : PegPrimary()

  class AnyChar(val dot: String) : PegPrimary()

  class Group(override val element: PegChoice) : PegPrimary()
}

class PegSuffix(
  override val primary: PegPrimary,
  var operator: String? = null,
) : Parent

class PegPrefix(
  val operator: String?,
  override val suffix: PegSuffix,
) : Parent

class PegSequence(override val elements: MutableList<PegPrefix> = ArrayList()) : Parent

class PegChoice(override val alternatives: MutableList<PegSequence> = ArrayList()) : Parent

class PegRule(
  override val name: String,
  override val definition: PegChoice,
) : Rule

typealias PegAst = Grammar<PegRule>

// One open group, or the rule itself at the bottom of the stack.
private class PegFrame(
  val choice: PegChoice,
  var sequence: PegSequence,
  // The group's node and the predicate written before it.
  val node: PegPrimary?,
  val operator: String?,
)

private fun frame(node: PegPrimary?, choice: PegChoice, operator: String?): PegFrame {
  val sequence = PegSequence()
  choice.alternatives.add(sequence)
  return PegFrame(choice, sequence, node, operator)
}

private fun definition(r: Reader): PegChoice {
  val stack = ArrayList<PegFrame>()
  var top = frame(null, PegChoice(), null)
  outer@ while (true) {
    var operator: String? = if (r.kind == PegT.and || r.kind == PegT.not) r.take() else null
    var primary: PegPrimary
    val kind = r.kind
    when (kind) {
      PegT.string -> primary = PegPrimary.Literal(unescape(r.take()))
      PegT.id -> primary = PegPrimary.Identifier(r.take())
      PegT.dot -> primary = PegPrimary.AnyChar(r.take())
      PegT.group -> {
        r.i++
        val element = PegChoice()
        stack.add(top)
        top = frame(PegPrimary.Group(element), element, operator)
        continue@outer
      }
      else -> r.fail("a literal, a name, a dot, or an opening parenthesis")
    }
    inner@ while (true) {
      val suffix = PegSuffix(primary)
      if (r.kind == PegT.question || r.kind == PegT.star || r.kind == PegT.plus) suffix.operator = r.take()
      top.sequence.elements.add(PegPrefix(operator, suffix))
      val k = r.kind
      if (k == PegT.groupEnd && stack.size > 0) {
        r.i++
        primary = top.node!!
        operator = top.operator
        top = stack.removeAt(stack.size - 1)
        continue@inner
      }
      if (k == PegT.slash) {
        r.i++
        top.sequence = PegSequence()
        top.choice.alternatives.add(top.sequence)
      } else if (k != PegT.and && k != PegT.not && k != PegT.string && k != PegT.id && k != PegT.dot && k != PegT.group) {
        if (stack.size == 0) return top.choice
        r.fail("token of type ')'")
      }
      break
    }
  }
}

fun parsePeg(src: String): PegAst {
  return parseGrammar(src, PEG_TOKENS, "RailroadPeg", PegT.title, ::unescape) { r ->
    val name = r.expect(PegT.id)
    r.expect(PegT.arrow)
    val rule = PegRule(name, definition(r))
    r.expect(PegT.semicolon)
    rule
  }
}

// What a predicate shows of the expression it looks ahead for.
private fun label(node: RailroadNode): String {
  return when (node) {
    is RailroadNode.Terminal -> "\"${node.value}\""
    is RailroadNode.Nonterminal -> node.name
    is RailroadNode.Special -> node.text
    else -> "(...)"
  }
}

private fun node(part: Parent, kids: List<RailroadNode>): RailroadNode {
  val kid = kids.getOrNull(0)
  return when (part) {
    is PegChoice -> choice(kids)
    is PegSequence -> sequence(kids)
    is PegPrefix -> if (!part.operator.isNullOrEmpty()) special(part.operator + label(kid!!)) else kid!!
    is PegSuffix -> {
      val operator = part.operator
      if (operator.isNullOrEmpty()) {
        kid!!
      } else if (operator == "?") {
        optional(kid!!)
      } else {
        repetition(kid!!, if (operator == "+") 1.0 else 0.0)
      }
    }
    is PegPrimary.Literal -> terminal(part.value)
    is PegPrimary.Identifier -> nonterminal(part.name)
    is PegPrimary.AnyChar -> special(part.dot)
    is PegPrimary.Group -> kid!!
    else -> throw IllegalStateException("unexpected part")
  }
}

val pegRules = { ast: PegAst -> toRules(ast, ::node) }
