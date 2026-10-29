// Ported from Pele src/diagrams/info/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.info

import pele.diagrams.common.ACC_DESCR
import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.NEWLINE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.TITLE
import pele.diagrams.common.TokenType
import pele.diagrams.common.WHITESPACE
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.keyword
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize

private object T {
  const val showInfo = 0
  const val info = 1
  const val accDescr = 2
  const val accTitle = 3
  const val title = 4
  const val newline = 5
}

val INFO_TOKENS: List<TokenType> =
  listOf(
    keyword("showInfo"),
    keyword("info"),
    ACC_DESCR,
    ACC_TITLE,
    TITLE,
    NEWLINE,
    WHITESPACE,
    YAML,
    DIRECTIVE,
    SINGLE_LINE_COMMENT,
  )

class InfoAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
)

fun parseInfo(src: String): InfoAst {
  val r = Reader(tokenize(src, INFO_TOKENS, "info"), INFO_TOKENS, "info")
  val ast = InfoAst()

  while (r.kind == T.newline) r.i++
  r.expect(T.info)
  while (r.kind == T.newline) r.i++
  if (r.accept(T.showInfo)) while (r.kind == T.newline) r.i++
  while (r.kind != -1) {
    val kind = r.kind
    if (kind == T.accDescr) ast.accDescr = accDescrValue(r.take())
    else if (kind == T.accTitle) ast.accTitle = accTitleValue(r.take())
    else if (kind == T.title) ast.title = titleValue(r.take())
    else r.fail("a title or the end of input")
    if (r.kind == -1) break
    if (r.kind != T.newline) r.fail("a line break or the end of input")
    while (r.kind == T.newline) r.i++
  }
  return ast
}
