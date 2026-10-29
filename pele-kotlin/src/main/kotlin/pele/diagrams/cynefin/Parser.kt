// Ported from Pele src/diagrams/cynefin/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.cynefin

import pele.diagrams.common.ACC_DESCR
import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.NEWLINE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.STRING
import pele.diagrams.common.TITLE
import pele.diagrams.common.TokenType
import pele.diagrams.common.WHITESPACE
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.keyword
import pele.diagrams.common.stringValue
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp

private object T {
  const val keywordColon = 0
  const val keyword = 1
  const val arrow = 2
  const val colon = 3
  const val domain = 4
  const val accDescr = 5
  const val accTitle = 6
  const val title = 7
  const val string = 8
  const val newline = 9
}

val CYNEFIN_TOKENS: List<TokenType> = listOf(
  TokenType(name = "cynefin-beta:", pattern = "cynefin-beta:"),
  keyword("cynefin-beta"),
  TokenType(name = "-->", pattern = "-->"),
  TokenType(name = ":", pattern = ":"),
  TokenType(name = "DOMAIN_NAME", pattern = JsRegExp("""complex|complicated|clear|chaotic|confusion""", "y")),
  ACC_DESCR,
  ACC_TITLE,
  TITLE,
  STRING,
  NEWLINE,
  WHITESPACE,
  YAML,
  DIRECTIVE,
  SINGLE_LINE_COMMENT,
)

// DomainName is "complex" | "complicated" | "clear" | "chaotic" | "confusion".

class DomainItemAst(val label: String)

class DomainBlockAst(
  override val domain: String,
  override val items: MutableList<DomainItemAst>,
) : DomainBlock

class TransitionAst(
  override val from: String,
  override val to: String,
  override var label: String? = null,
) : Transition

class CynefinAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val domains: MutableList<DomainBlockAst> = ArrayList(),
  val transitions: MutableList<TransitionAst> = ArrayList(),
)

fun parseCynefin(src: String): CynefinAst {
  val r = Reader(tokenize(src, CYNEFIN_TOKENS, "cynefin"), CYNEFIN_TOKENS, "cynefin")
  val ast = CynefinAst()

  val skipLines = {
    while (r.kinds.getOrNull(r.i) == T.newline) r.i++
  }
  val endOfLine = {
    if (r.kind != -1) {
      if (r.kind != T.newline) r.fail("a line break or the end of input")
      skipLines()
    }
  }

  skipLines()
  if (r.kind != T.keyword && r.kind != T.keywordColon) r.fail("'cynefin-beta'")
  r.i++
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
      T.domain -> {
        val name = r.take()
        if (r.accept(T.arrow)) {
          val transition = TransitionAst(name, r.expect(T.domain))
          if (r.accept(T.colon)) transition.label = stringValue(r.expect(T.string))
          endOfLine()
          ast.transitions.add(transition)
        } else {
          val block = DomainBlockAst(name, ArrayList())
          skipLines()
          while (r.kinds.getOrNull(r.i) == T.string) {
            block.items.add(DomainItemAst(stringValue(r.take())))
            skipLines()
          }
          ast.domains.add(block)
        }
      }
      else -> r.fail("a domain, a transition, a title, or a line break")
    }
  }
  return ast
}
