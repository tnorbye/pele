// Ported from Pele src/diagrams/railroad/abnf.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.diagrams.common.Reader
import pele.diagrams.common.TokenType
import pele.js.JsRegExp
import pele.js.jsParseInt
import pele.js.jsSlice

// `railroad-abnf-beta`: ABNF as in RFC 5234, with each rule closed by a semicolon.

// PORT: named per file, as private classes of one package share a JVM namespace.
private object AbnfT {
  const val keyword = 0
  const val equals = 1
  const val semicolon = 2
  const val slash = 3
  const val group = 4
  const val groupEnd = 5
  const val option = 6
  const val optionEnd = 7
  const val title = 8
  const val accTitle = 9
  const val accDescr = 10
  const val name = 11
  const val string = 12
  const val number = 13
  const val repeat = 14
  const val exact = 15
}

private const val DIGITS = "0123456789"
private val NAME =
  TokenType(
    name = "ABNF_RULENAME",
    pattern = JsRegExp("""[A-Za-z][A-Za-z0-9-]*""", "y"),
    first = LETTERS,
  )
private val COMMENT =
  TokenType(
    name = "ABNF_COMMENT",
    pattern = JsRegExp(""";[^\n\r]*""", "y"),
    hidden = true,
    first = ";",
  )

val ABNF_TOKENS: List<TokenType> =
  listOf(
    opening("railroad-abnf-beta", NAME),
    literal("="),
    // A semicolon closes a rule only at the end of its line; with anything after it, it starts a
    // comment.
    literal(";", listOf(COMMENT)),
    literal("/"),
    literal("("),
    literal(")"),
    literal("["),
    literal("]"),
    title("""[A-Za-z0-9-]"""),
    ACC_TITLE,
    ACC_DESCR,
    NAME,
    TokenType(name = "ABNF_STRING", pattern = JsRegExp("\"[^\"]*\"", "y"), first = "\""),
    TokenType(
      name = "ABNF_NUMVAL",
      pattern = JsRegExp("""%[xXdDbB][0-9A-Fa-f]+(?:-[0-9A-Fa-f]+|\.[0-9A-Fa-f]+)*""", "y"),
      first = "%",
    ),
    TokenType(
      name = "ABNF_REPEAT",
      pattern = JsRegExp("""[0-9]*\*[0-9]*""", "y"),
      first = DIGITS + "*",
    ),
    TokenType(name = "ABNF_EXACT_REPEAT", pattern = JsRegExp("""[0-9]+""", "y"), first = DIGITS),
  ) + hiddenTokens("ABNF") + listOf(COMMENT)

sealed class AbnfPrimary : Parent {
  // $type is AbnfStringLiteral or AbnfNumVal.
  class Literal(val `$type`: String, val value: String) : AbnfPrimary()

  class RuleName(val name: String) : AbnfPrimary()

  // $type is AbnfGroup or AbnfOptionalGroup.
  class Group(val `$type`: String, override val element: AbnfAlternation) : AbnfPrimary()
}

class AbnfElement(
  val repeat: String?,
  override val primary: AbnfPrimary,
) : Parent

class AbnfConcatenation(override val elements: MutableList<AbnfElement> = ArrayList()) : Parent

class AbnfAlternation(override val alternatives: MutableList<AbnfConcatenation> = ArrayList()) :
  Parent

class AbnfRule(
  override val name: String,
  override val definition: AbnfAlternation,
) : Rule

typealias AbnfAst = Grammar<AbnfRule>

// One open bracket, or the rule itself at the bottom of the stack.
private class AbnfFrame(
  val alternation: AbnfAlternation,
  var concatenation: AbnfConcatenation,
  // The bracket's node, its closing token, and the repeat written before it.
  val node: AbnfPrimary?,
  val close: Int,
  val repeat: String?,
)

private fun frame(
  node: AbnfPrimary?,
  alternation: AbnfAlternation,
  close: Int,
  repeat: String?,
): AbnfFrame {
  val concatenation = AbnfConcatenation()
  alternation.alternatives.add(concatenation)
  return AbnfFrame(alternation, concatenation, node, close, repeat)
}

private fun alternation(r: Reader): AbnfAlternation {
  val stack = ArrayList<AbnfFrame>()
  var top = frame(null, AbnfAlternation(), -2, null)
  outer@ while (true) {
    var repeat: String? = if (r.kind == AbnfT.repeat || r.kind == AbnfT.exact) r.take() else null
    var primary: AbnfPrimary
    val kind = r.kind
    when (kind) {
      AbnfT.string -> primary = AbnfPrimary.Literal("AbnfStringLiteral", r.take().jsSlice(1, -1))
      AbnfT.number -> primary = AbnfPrimary.Literal("AbnfNumVal", r.take())
      AbnfT.name -> primary = AbnfPrimary.RuleName(r.take())
      AbnfT.group,
      AbnfT.option -> {
        r.i++
        val element = AbnfAlternation()
        stack.add(top)
        top =
          frame(
            AbnfPrimary.Group(
              if (kind == AbnfT.group) "AbnfGroup" else "AbnfOptionalGroup",
              element,
            ),
            element,
            kind + 1,
            repeat,
          )
        continue@outer
      }
      else -> r.fail("a string, a number, a name, or an opening bracket")
    }
    inner@ while (true) {
      top.concatenation.elements.add(AbnfElement(repeat, primary))
      val k = r.kind
      if (k == top.close) {
        r.i++
        primary = top.node!!
        repeat = top.repeat
        top = stack.removeAt(stack.size - 1)
        continue@inner
      }
      if (k == AbnfT.slash) {
        r.i++
        top.concatenation = AbnfConcatenation()
        top.alternation.alternatives.add(top.concatenation)
      } else if (
        k != AbnfT.string &&
          k != AbnfT.number &&
          k != AbnfT.name &&
          k != AbnfT.group &&
          k != AbnfT.option &&
          k != AbnfT.repeat &&
          k != AbnfT.exact
      ) {
        if (stack.size == 0) return top.alternation
        r.fail("token of type '${ABNF_TOKENS[top.close].name}'")
      }
      break
    }
  }
}

fun parseAbnf(src: String): AbnfAst {
  return parseGrammar(
    src,
    ABNF_TOKENS,
    "RailroadAbnf",
    AbnfT.title,
    { text -> text.jsSlice(1, -1) },
    { r ->
      val name = r.expect(AbnfT.name)
      r.expect(AbnfT.equals)
      val definition = alternation(r)
      r.expect(AbnfT.semicolon)
      AbnfRule(name, definition)
    },
  )
}

private fun node(part: Parent, kids: List<RailroadNode>): RailroadNode {
  return when (part) {
    is AbnfAlternation -> choice(kids)
    is AbnfConcatenation -> sequence(kids)
    is AbnfElement -> {
      val repeat = part.repeat
      if (repeat.isNullOrEmpty()) return kids[0]
      val star = repeat.indexOf('*')
      val exact = star == -1
      val min = if (star > 0 || exact) jsParseInt(repeat, 10) else 0.0
      val max =
        if (exact) min
        else if (star < repeat.length - 1) jsParseInt(repeat.jsSlice(star + 1), 10)
        else Double.POSITIVE_INFINITY
      if (min == 0.0 && max == 1.0) optional(kids[0]) else repetition(kids[0], min, max)
    }
    is AbnfPrimary.Literal -> terminal(part.value)
    is AbnfPrimary.RuleName -> nonterminal(part.name)
    is AbnfPrimary.Group -> if (part.`$type` == "AbnfGroup") kids[0] else optional(kids[0])
    else -> throw IllegalStateException("unexpected part")
  }
}

val abnfRules = { ast: AbnfAst -> toRules(ast, ::node) }
