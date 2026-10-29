// Ported from Pele src/diagrams/railroad/ebnf.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.diagrams.common.Reader
import pele.diagrams.common.TokenType
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.js.jsTrim

// `railroad-ebnf-beta`: EBNF in the W3C and ISO 14977 styles.

// PORT: named per file, as private classes of one package share a JVM namespace.
private object EbnfT {
  const val keyword = 0
  const val defines = 1
  const val equals = 2
  const val semicolon = 3
  const val bar = 4
  const val comma = 5
  const val group = 6
  const val groupEnd = 7
  const val option = 8
  const val optionEnd = 9
  const val repeat = 10
  const val repeatEnd = 11
  const val question = 12
  const val star = 13
  const val plus = 14
  const val minus = 15
  const val title = 16
  const val accTitle = 17
  const val accDescr = 18
  const val id = 19
  const val string = 20
  const val special = 21
}

// A question mark, text with something other than blanks in it and no `?` or `;`, and a question mark.
// Mermaid's pattern looks ahead with two nested runs, which rescans; this reads the text once.
private fun specialSequence(src: String, at: Int, previous: Int): Int {
  if (src.charCodeAt(at) != 63) return -1
  var solid = false
  for (i in at + 1 until src.length) {
    val c = src.charCodeAt(i)
    if (c == 63) return if (solid) i + 1 else -1
    if (c == 59) return -1
    solid = solid || !isSpace(c)
  }
  return -1
}

private val ID = identifier("EBNF_ID")
private val SPECIAL = TokenType(
  name = "EBNF_SPECIAL_SEQUENCE",
  pattern = JsRegExp("""\?(?=[^?;]*[^?\s;][^?;]*\?)[^?;]*\?""", "y"),
  first = "?",
  scanner = { ::specialSequence },
)
private val HIDDEN = hiddenTokens("EBNF")
private val ISO_COMMENT = TokenType(
  name = "EBNF_ISO_COMMENT",
  pattern = JsRegExp("""\(\*[\s\S]*?\*\)""", "y"),
  hidden = true,
  first = "(",
  opener = JsRegExp("""\(\*""", "y"),
)

val EBNF_TOKENS: List<TokenType> = listOf(
  opening("railroad-ebnf-beta", ID),
  literal("::="),
  literal("="),
  literal(";"),
  literal("|"),
  literal(","),
  literal("(", listOf(ISO_COMMENT)),
  literal(")"),
  literal("["),
  literal("]"),
  literal("{"),
  literal("}"),
  literal("?", listOf(SPECIAL)),
  literal("*"),
  literal("+"),
  literal("-", listOf(HIDDEN[1])),
  title("""[\w-]"""),
  ACC_TITLE,
  ACC_DESCR,
  ID,
  quoted("EBNF_STRING"),
  SPECIAL,
) + HIDDEN + listOf(
  TokenType(
    name = "EBNF_BLOCK_COMMENT",
    pattern = JsRegExp("""\/\*[\s\S]*?\*\/""", "y"),
    hidden = true,
    first = "/",
    opener = JsRegExp("""\/\*""", "y"),
  ),
  ISO_COMMENT,
)

sealed class EbnfPrimary : Parent {
  class Terminal(val value: String) : EbnfPrimary()

  class NonTerminal(val name: String) : EbnfPrimary()

  class Special(val text: String) : EbnfPrimary()

  // $type is EbnfGroup, EbnfOptional or EbnfRepetition.
  class Bracket(val `$type`: String, override val element: EbnfChoice) : EbnfPrimary()
}

sealed class EbnfPostfix : Postfix {
  // $type is EbnfOptionalPostfix, EbnfZeroOrMorePostfix or EbnfOneOrMorePostfix.
  class Operator(val `$type`: String, val operator: String) : EbnfPostfix()

  class Exception(override val except: EbnfPrimary) : EbnfPostfix()
}

class EbnfTerm(
  override val base: EbnfPrimary,
  override val postfixes: MutableList<EbnfPostfix> = ArrayList(),
) : Parent

class EbnfSequence(override val elements: MutableList<EbnfTerm> = ArrayList()) : Parent

class EbnfChoice(override val alternatives: MutableList<EbnfSequence> = ArrayList()) : Parent

class EbnfRule(
  override val name: String,
  override val definition: EbnfChoice,
) : Rule

typealias EbnfAst = Grammar<EbnfRule>

// One open bracket, or the rule itself at the bottom of the stack.
private class EbnfFrame(
  val choice: EbnfChoice,
  var sequence: EbnfSequence,
  var term: EbnfTerm?,
  // The bracket's node, its closing token, and whether it is the right side of an exception.
  val node: EbnfPrimary?,
  val close: Int,
  val except: Boolean,
)

private fun frame(node: EbnfPrimary?, choice: EbnfChoice, close: Int, except: Boolean): EbnfFrame {
  val sequence = EbnfSequence()
  choice.alternatives.add(sequence)
  return EbnfFrame(choice, sequence, null, node, close, except)
}

private val BRACKETS = listOf("EbnfGroup", "EbnfOptional", "EbnfRepetition")

private fun definition(r: Reader): EbnfChoice {
  val stack = ArrayList<EbnfFrame>()
  var top = frame(null, EbnfChoice(), -2, false)
  var except = false
  outer@ while (true) {
    var primary: EbnfPrimary
    val kind = r.kind
    when (kind) {
      EbnfT.string -> primary = EbnfPrimary.Terminal(unescape(r.take()))
      EbnfT.id -> primary = EbnfPrimary.NonTerminal(r.take())
      EbnfT.special -> primary = EbnfPrimary.Special(r.take().jsSlice(1, -1).jsTrim())
      EbnfT.group, EbnfT.option, EbnfT.repeat -> {
        r.i++
        val element = EbnfChoice()
        stack.add(top)
        top = frame(EbnfPrimary.Bracket(BRACKETS[(kind - EbnfT.group) shr 1], element), element, kind + 1, except)
        except = false
        continue@outer
      }
      else -> r.fail("a terminal, a name, or an opening bracket")
    }
    inner@ while (true) {
      if (except) {
        top.term!!.postfixes.add(EbnfPostfix.Exception(primary))
        except = false
      } else {
        top.term = EbnfTerm(primary)
        top.sequence.elements.add(top.term!!)
      }
      val postfixes = top.term!!.postfixes
      while (true) {
        val k = r.kind
        if (k == EbnfT.question) postfixes.add(EbnfPostfix.Operator("EbnfOptionalPostfix", r.take()))
        else if (k == EbnfT.star) postfixes.add(EbnfPostfix.Operator("EbnfZeroOrMorePostfix", r.take()))
        else if (k == EbnfT.plus) postfixes.add(EbnfPostfix.Operator("EbnfOneOrMorePostfix", r.take()))
        else break
      }
      val k = r.kind
      if (k == top.close) {
        r.i++
        primary = top.node!!
        except = top.except
        top = stack.removeAt(stack.size - 1)
        continue@inner
      }
      if (k == EbnfT.minus) {
        r.i++
        except = true
      } else if (k == EbnfT.comma) {
        r.i++
      } else if (k == EbnfT.bar) {
        r.i++
        top.sequence = EbnfSequence()
        top.choice.alternatives.add(top.sequence)
      } else if (k != EbnfT.string && k != EbnfT.id && k != EbnfT.special && k != EbnfT.group && k != EbnfT.option && k != EbnfT.repeat) {
        if (stack.size == 0) return top.choice
        r.fail("token of type '${EBNF_TOKENS[top.close].name}'")
      }
      break
    }
  }
}

fun parseEbnf(src: String): EbnfAst {
  return parseGrammar(src, EBNF_TOKENS, "RailroadEbnf", EbnfT.title, ::unescape) { r ->
    val name = r.expect(EbnfT.id)
    if (!r.accept(EbnfT.equals)) r.expect(EbnfT.defines)
    val rule = EbnfRule(name, definition(r))
    r.expect(EbnfT.semicolon)
    rule
  }
}

private fun node(part: Parent, kids: List<RailroadNode>): RailroadNode {
  return when (part) {
    is EbnfChoice -> choice(kids)
    is EbnfSequence -> sequence(kids)
    is EbnfTerm -> {
      // Each postfix operator applies to everything before it in the term.
      var result = kids[0]
      var next = 1
      for (postfix in part.postfixes) {
        if (postfix is EbnfPostfix.Operator && postfix.`$type` == "EbnfOptionalPostfix") {
          result = optional(result)
        } else if (postfix is EbnfPostfix.Exception) {
          result = RailroadNode.Sequence(listOf(result, terminal("-"), kids[next++]))
        } else {
          result = repetition(result, if ((postfix as EbnfPostfix.Operator).`$type` == "EbnfOneOrMorePostfix") 1.0 else 0.0)
        }
      }
      result
    }
    is EbnfPrimary.Terminal -> terminal(part.value)
    is EbnfPrimary.NonTerminal -> nonterminal(part.name)
    is EbnfPrimary.Special -> special(part.text)
    is EbnfPrimary.Bracket -> when (part.`$type`) {
      "EbnfGroup" -> kids[0]
      "EbnfOptional" -> optional(kids[0])
      else -> repetition(kids[0], 0.0)
    }
    else -> throw IllegalStateException("unexpected part")
  }
}

val ebnfRules = { ast: EbnfAst -> toRules(ast, ::node) }
