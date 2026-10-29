// Ported from Pele src/diagrams/railroad/common.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.Scanner
import pele.diagrams.common.TokenType
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.keyword
import pele.diagrams.common.quotedEnd
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.fromCharCode
import pele.js.jsSlice

// What the four railroad grammars share: the title and accessibility tokens (which, unlike
// the ones in Mermaid's common grammar, do not take leading blanks), the hidden tokens, the
// statements before the first rule, string values, and the walk from a syntax tree to the rule
// model.

private val RE_SPACE = JsRegExp("""\s""")

fun isSpace(c: Int): Boolean {
  return c == 32 || (c >= 9 && c <= 13) || (c > 127 && RE_SPACE.test(fromCharCode(c)))
}

// The braced form scans to the next closing brace. Once none is found, none exists further on
// either, so later braced descriptions fail without scanning again.
private fun accDescrScanner(): Scanner {
  var unclosed = Int.MAX_VALUE
  return { src, at, _ ->
    if (!src.startsWith("accDescr", at)) {
      -1
    } else {
      val n = src.length
      var i = at + 8
      while (i < n && (src.charCodeAt(i) == 32 || src.charCodeAt(i) == 9)) i++
      if (src.charCodeAt(i) == 58) {
        i++
        while (i < n) {
          val c = src.charCodeAt(i)
          if (c == 10 || c == 13 || (c == 37 && src.charCodeAt(i + 1) == 37)) break
          i++
        }
        i
      } else {
        while (i < n && isSpace(src.charCodeAt(i))) i++
        if (src.charCodeAt(i) != 123 || i >= unclosed) {
          -1
        } else {
          val close = src.indexOf('}', i + 1)
          if (close == -1) unclosed = i
          if (close + 1 != 0) close + 1 else -1
        }
      }
    }
  }
}

const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

// `rest` matches a name suffix so `title` does not split longer names.
fun title(rest: String): TokenType =
  TokenType(
    name = "TITLE",
    pattern = JsRegExp("""title(?:[\t ][^\n\r]*?(?=%%)|[\t ][^\n\r]*|(?!$rest))""", "y"),
    first = "t",
  )

val ACC_TITLE =
  TokenType(
    name = "ACC_TITLE",
    pattern = JsRegExp("""accTitle[\t ]*:(?:[^\n\r]*?(?=%%)|[^\n\r]*)""", "y"),
    first = "a",
  )
val ACC_DESCR =
  TokenType(
    name = "ACC_DESCR",
    pattern = JsRegExp("""accDescr(?:[\t ]*:([^\n\r]*?(?=%%)|[^\n\r]*)|\s*{([^}]*)})""", "y"),
    first = "a",
    scanner = ::accDescrScanner,
  )

fun literal(name: String, longer: List<TokenType>? = null): TokenType =
  TokenType(name = name, pattern = name, longer = longer)

// The diagram's keyword. A name that starts with it and goes on is a name.
fun opening(word: String, name: TokenType): TokenType = keyword(word).copy(longer = listOf(name))

fun identifier(name: String): TokenType =
  TokenType(name = name, pattern = JsRegExp("""[A-Z_a-z][\w-]*""", "y"), first = LETTERS + "_")

fun quoted(name: String): TokenType =
  TokenType(
    name = name,
    pattern = JsRegExp("""\"([^"\\]|\\.)*"|'([^'\\]|\\.)*'""", "y"),
    first = "\"'",
    match = ::quotedEnd,
  )

// Whitespace, front matter, directive, and `%%` comment, in the order every railroad grammar lists
// them.
fun hiddenTokens(prefix: String): List<TokenType> =
  listOf(
    TokenType(
      name = prefix + "_WHITESPACE",
      pattern = JsRegExp("""[\t \r\n]+""", "y"),
      hidden = true,
      first = "\t \r\n",
    ),
    YAML.copy(name = prefix + "_YAML", first = "-"),
    DIRECTIVE.copy(name = prefix + "_DIRECTIVE", first = "\t %"),
    SINGLE_LINE_COMMENT.copy(name = prefix + "_SINGLE_LINE_COMMENT", first = "\t %"),
  )

// Mermaid's railroad value converters know three escapes; any other escaped character stands for
// itself.
fun unescape(text: String): String {
  if (!text.contains('\\')) return text.jsSlice(1, -1)
  val end = text.length - 1
  val out = StringBuilder()
  var i = 1
  while (i < end) {
    var c = text[i]
    if (c == '\\' && i + 1 < end) {
      c = text[++i]
      out.append(
        if (c == 'n') "\n" else if (c == 'r') "\r" else if (c == 't') "\t" else c.toString()
      )
    } else {
      out.append(c)
    }
    i++
  }
  return out.toString()
}

class Grammar<Rule>(
  val `$type`: String,
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val rules: MutableList<Rule> = ArrayList(),
)

// Parses the keyword, then any titles and accessibility statements, then rules to the end of the
// text.
// Every table starts with its keyword and has TITLE, ACC_TITLE, and ACC_DESCR in a row from `meta`.
fun <Rule> parseGrammar(
  src: String,
  types: List<TokenType>,
  `$type`: String,
  meta: Int,
  unquote: (text: String) -> String,
  rule: (r: Reader) -> Rule,
): Grammar<Rule> {
  val r = Reader(tokenize(src, types, "railroad"), types, "railroad")
  val ast = Grammar<Rule>(`$type`)
  r.expect(0)
  while (true) {
    val kind = r.kind
    if (kind == meta) {
      val title = titleValue(r.take())
      val quote = title.firstOrNull()
      ast.title =
        if ((quote == '"' || quote == '\'') && title.endsWith(quote)) unquote(title) else title
    } else if (kind == meta + 1) {
      ast.accTitle = accTitleValue(r.take())
    } else if (kind == meta + 2) {
      ast.accDescr = accDescrValue(r.take())
    } else {
      break
    }
  }
  while (r.kind != -1) ast.rules.add(rule(r))
  return ast
}

val NONE: List<Nothing> = emptyList()

// The fields that hold a node's parts, in any of the four syntax trees and in the rule model.
interface Parent {
  val alternatives: List<Parent>?
    get() = null

  val elements: List<Parent>?
    get() = null

  val element: Parent?
    get() = null

  val primary: Parent?
    get() = null

  val suffix: Parent?
    get() = null

  val base: Parent?
    get() = null

  val postfixes: List<Postfix>?
    get() = null
}

interface Postfix {
  val except: Parent?
    get() = null
}

@Suppress("UNCHECKED_CAST")
fun <T> parts(node: T): List<T> {
  val p = node as Parent
  val one = p.element ?: p.primary ?: p.suffix
  var list: List<Parent> = p.alternatives ?: p.elements ?: (if (one != null) listOf(one) else NONE)
  if (p.base != null) {
    val l = arrayListOf(p.base!!)
    for (postfix in p.postfixes!!) if (postfix.except != null) l.add(postfix.except!!)
    list = l
  }
  return list as List<T>
}

fun terminal(value: String): RailroadNode = RailroadNode.Terminal(value)

fun nonterminal(name: String): RailroadNode = RailroadNode.Nonterminal(name)

fun special(text: String): RailroadNode = RailroadNode.Special(text)

fun optional(element: RailroadNode): RailroadNode = RailroadNode.Optional(element)

fun repetition(
  element: RailroadNode,
  min: Double,
  max: Double = Double.POSITIVE_INFINITY,
): RailroadNode = RailroadNode.Repetition(element, min, max)

// A sequence or a choice of one is that one, in every notation.
fun sequence(elements: List<RailroadNode>): RailroadNode =
  if (elements.size == 1) elements[0] else RailroadNode.Sequence(elements)

fun choice(alternatives: List<RailroadNode>): RailroadNode =
  if (alternatives.size == 1) alternatives[0] else RailroadNode.Choice(alternatives)

interface Rule {
  val name: String
  val definition: Parent
}

// Turns each rule's syntax tree into the common model, given how one node is built from its parts.
fun <R : Rule> toRules(
  ast: Grammar<R>,
  build: (part: Parent, kids: List<RailroadNode>) -> RailroadNode,
): List<RailroadRule> =
  ast.rules.map { rule -> RailroadRule(rule.name, fold(rule.definition, { n -> parts(n) }, build)) }

// Rebuilds a tree bottom-up without recursion, so that nesting depth is bounded by memory, not by
// the stack.
fun <T, R> fold(
  root: T,
  children: (node: T) -> List<T>,
  build: (node: T, results: List<R>) -> R,
): R {
  val nodes = arrayListOf(root)
  val lists = arrayListOf(children(root))
  val done = arrayListOf<MutableList<R>>(ArrayList())
  while (true) {
    val top = nodes.size - 1
    val results = done[top]
    if (results.size < lists[top].size) {
      val child = lists[top][results.size]
      nodes.add(child)
      lists.add(children(child))
      done.add(ArrayList())
    } else {
      val value = build(nodes[top], results)
      if (top == 0) return value
      nodes.removeAt(nodes.size - 1)
      lists.removeAt(lists.size - 1)
      done.removeAt(done.size - 1)
      done[top - 1].add(value)
    }
  }
}
