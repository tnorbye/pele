// Ported from Pele src/diagrams/treemap/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treemap

import pele.diagrams.common.ACC_DESCR
import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.Reader
import pele.diagrams.common.TokenType
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.titleBefore
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.jsParseFloat
import pele.js.jsReplace
import pele.js.jsSlice

private object T {
  const val accDescr = 0
  const val accTitle = 1
  const val title = 2
  const val keyword = 3
  const val classDef = 4
  const val styleSeparator = 5
  const val separator = 6
  const val comma = 7
  const val indentation = 8
  const val whitespace = 9
  const val comment = 10
  const val newline = 11
  const val id = 12
  const val number = 13
  const val string = 14
}

// Mermaid's treemap grammar has its own terminals. Every run of spaces or tabs is an INDENTATION
// token, since that comes before WS, and line breaks are hidden, so rows are told apart by their
// tokens alone and the indentation of a row is whatever whitespace precedes it.
val TREEMAP_TOKENS: List<TokenType> = listOf(
  ACC_DESCR,
  ACC_TITLE,
  titleBefore("""\w"""),
  TokenType(name = "TREEMAP_KEYWORD", pattern = JsRegExp("""treemap-beta|treemap""", "y")),
  TokenType(name = "CLASS_DEF", pattern = JsRegExp("""classDef\s+([a-zA-Z_][a-zA-Z0-9_]+)(?:\s+([^;\r\n]*))?(?:;)?""", "y")),
  TokenType(name = "STYLE_SEPARATOR", pattern = JsRegExp(""":::""", "y")),
  TokenType(name = "SEPARATOR", pattern = JsRegExp(""":""", "y")),
  TokenType(name = "COMMA", pattern = JsRegExp(""",""", "y")),
  TokenType(name = "INDENTATION", pattern = JsRegExp("""[ \t]{1,}""", "y")),
  TokenType(name = "WS", pattern = JsRegExp("""[ \t]+""", "y"), hidden = true),
  TokenType(name = "ML_COMMENT", pattern = JsRegExp("""\%\%[^\n]*""", "y"), hidden = true),
  TokenType(name = "NL", pattern = JsRegExp("""\r?\n""", "y"), hidden = true),
  TokenType(name = "ID2", pattern = JsRegExp("""[a-zA-Z_][a-zA-Z0-9_]*""", "y")),
  TokenType(name = "NUMBER2", pattern = JsRegExp("""[0-9_\.\,]+""", "y")),
  TokenType(name = "STRING2", pattern = JsRegExp(""""[^"]*"|'[^']*'""", "y")),
)

private val RE_CLASS_DEF = JsRegExp("""classDef\s+([A-Z_a-z]\w+)(?:\s+([^\n\r;]*))?;?""")
private val RE_COMMAS = JsRegExp(""",""", "g")

sealed class TreemapItemAst {
  abstract val name: String
  abstract var classSelector: String?
}

class SectionAst(
  override val name: String,
  override var classSelector: String? = null,
) : TreemapItemAst()

class LeafAst(
  override val name: String,
  val value: Double,
  override var classSelector: String? = null,
) : TreemapItemAst()

sealed class TreemapRowsAst

class TreemapRowAst(
  var indent: Double? = null,
  val item: TreemapItemAst,
) : TreemapRowsAst()

class ClassDefAst(
  var indent: Double? = null,
  val className: String,
  var styleText: String? = null,
) : TreemapRowsAst()

class TreemapAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val TreemapRows: MutableList<TreemapRowsAst> = ArrayList(),
)

fun parseTreemap(src: String): TreemapAst {
  val r = Reader(tokenize(src, TREEMAP_TOKENS, "treemap"), TREEMAP_TOKENS, "treemap")
  val kinds = r.kinds
  val ast = TreemapAst()

  val classSelector = { if (r.accept(T.styleSeparator)) r.expect(T.id) else null }

  r.expect(T.keyword)
  while (r.kind != -1) {
    val kind = r.kind
    if (kind == T.accDescr) {
      ast.accDescr = accDescrValue(r.take())
      continue
    }
    if (kind == T.accTitle) {
      ast.accTitle = accTitleValue(r.take())
      continue
    }
    if (kind == T.title) {
      ast.title = titleValue(r.take())
      continue
    }
    if (kind != T.indentation && kind != T.string && kind != T.classDef) r.fail("a node, a class definition, or a title")

    val indent: Double? = if (kind == T.indentation) r.take().length.toDouble() else null
    if (r.kind == T.classDef) {
      val m = RE_CLASS_DEF.exec(r.take())!!
      val row = ClassDefAst(className = m[1]!!)
      if (!m[2].isNullOrEmpty()) row.styleText = m[2]
      if (indent != null) row.indent = indent
      ast.TreemapRows.add(row)
      continue
    }

    val name = r.expect(T.string).jsSlice(1, -1)
    // A leaf is a name followed by a colon or comma, with or without whitespace before it.
    val after = if (kinds.getOrNull(r.i) == T.indentation) r.i + 1 else r.i
    val item: TreemapItemAst
    if (kinds.getOrNull(after) == T.separator || kinds.getOrNull(after) == T.comma) {
      r.i = after + 1
      r.accept(T.indentation)
      item = LeafAst(name, jsParseFloat(r.expect(T.number).jsReplace(RE_COMMAS, "")))
    } else {
      item = SectionAst(name)
    }
    val selector = classSelector()
    if (selector != null) item.classSelector = selector
    val row = TreemapRowAst(item = item)
    if (indent != null) row.indent = indent
    ast.TreemapRows.add(row)
  }
  return ast
}
