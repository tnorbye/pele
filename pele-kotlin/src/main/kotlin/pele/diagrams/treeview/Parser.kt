// Ported from Pele src/diagrams/treeview/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treeview

import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.Reader
import pele.diagrams.common.TokenType
import pele.diagrams.common.accDescr
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.keyword
import pele.diagrams.common.titleBefore
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsSubstring
import pele.js.jsTrim
import pele.util.isBlank

private object T {
  const val keyword = 0
  const val accDescr = 1
  const val accTitle = 2
  const val title = 3
  const val classAnnotation = 4
  const val iconAnnotation = 5
  const val descAnnotation = 6
  const val indentation = 7
  const val quotedName = 8
  const val ws = 9
  const val comment = 10
  const val newline = 11
  const val bareName = 12
}

private fun annotationAt(src: String, at: Int): Boolean {
  if (src.startsWith("##", at) || src.startsWith("icon(", at)) return true
  if (!src.startsWith(":::", at)) return false
  var i = at + 3
  while (isBlank(src.charCodeAt(i))) i++
  val c = src.charCodeAt(i)
  return (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c == 95
}

// The pattern looks ahead from every blank for an annotation, which rescans long runs of blanks.
// This looks once per run.
private fun matchBareName(src: String, at: Int, previous: Int): Int {
  val first = src.charCodeAt(at)
  if (isBlank(first) || first == 10 || first == 13 || first == 34 || first == 39) return -1
  if (src.startsWith(":::", at) || src.startsWith("icon(", at) || src.startsWith("##", at)) return -1
  val n = src.length
  var i = at + 1
  while (i < n) {
    val c = src.charCodeAt(i)
    if (c == 10 || c == 13) break
    if (isBlank(c)) {
      var j = i + 1
      while (isBlank(src.charCodeAt(j))) j++
      if (annotationAt(src, j)) break
      i = j
    } else {
      i++
    }
  }
  return i
}

private val BARE_NAME = TokenType(
  name = "BARE_NAME",
  pattern = JsRegExp("""(?!:::|icon\(|##)[^ \t\n\r"'](?:(?![ \t]+:::[ \t]*[A-Za-z_]|[ \t]+icon\(|[ \t]+##)[^\n\r])*""", "y"),
  match = ::matchBareName,
)

val TREEVIEW_TOKENS: List<TokenType> = listOf(
  keyword("treeView-beta").copy(longer = listOf(BARE_NAME)),
  accDescr(),
  ACC_TITLE,
  titleBefore("""(?!%%)[^\n\r]"""),
  TokenType(name = "CLASS_ANNOTATION", pattern = JsRegExp("""[ \t]+:::[ \t]*[A-Za-z_][\w-]*""", "y")),
  TokenType(name = "ICON_ANNOTATION", pattern = JsRegExp("""[ \t]+icon\([\w-]*(?::[\w-]+)?\)""", "y")),
  TokenType(name = "DESC_ANNOTATION", pattern = JsRegExp("""[ \t]+##[^\n\r]*""", "y")),
  TokenType(name = "INDENTATION", pattern = JsRegExp("""[ \t]{1,}""", "y")),
  TokenType(name = "QUOTED_NAME", pattern = JsRegExp(""""[^"]*"|'[^']*'""", "y")),
  TokenType(name = "WS", pattern = JsRegExp("""[ \t]+""", "y"), hidden = true),
  TokenType(name = "ML_COMMENT", pattern = JsRegExp("""\%\%[^\n]*""", "y"), hidden = true),
  TokenType(name = "NL", pattern = JsRegExp("""\r?\n""", "y"), hidden = true),
  BARE_NAME,
)

class TreeNodeAst(
  var indent: Double? = null,
  var name: String,
  var classAnnotation: String? = null,
  var iconAnnotation: String? = null,
  var descAnnotation: String? = null,
)

class TreeViewAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val nodes: MutableList<TreeNodeAst> = ArrayList(),
)

private fun trimBlanksEnd(text: String): String {
  var end = text.length
  while (end > 0 && isBlank(text.charCodeAt(end - 1))) end--
  return if (end == text.length) text else text.substring(0, end)
}

// Mermaid's TreeViewValueConverter, by terminal name.
fun convertValue(rule: String, input: String): Any? {
  when (rule) {
    "INDENTATION" -> return input.length.toDouble()
    "QUOTED_NAME" -> return input.jsSubstring(1, input.length - 1)
    "BARE_NAME" -> return trimBlanksEnd(input)
    "CLASS_ANNOTATION" -> return input.jsTrim().jsSubstring(3).jsTrim()
    "ICON_ANNOTATION" -> {
      val trimmed = input.jsTrim()
      return trimmed.jsSubstring(5, trimmed.length - 1)
    }
    "DESC_ANNOTATION" -> return input.jsTrim().jsSubstring(2).jsTrim()
    else -> return null
  }
}

fun parseTreeView(src: String): TreeViewAst {
  val r = Reader(tokenize(src, TREEVIEW_TOKENS, "treeView"), TREEVIEW_TOKENS, "treeView")
  val ast = TreeViewAst()
  r.expect(T.keyword)
  while (true) {
    if (r.kind == T.accDescr) ast.accDescr = accDescrValue(r.take())
    else if (r.kind == T.accTitle) ast.accTitle = accTitleValue(r.take())
    else if (r.kind == T.title) ast.title = titleValue(r.take())
    else break
  }
  while (r.kind != -1) {
    val node = TreeNodeAst(name = "")
    if (r.kind == T.indentation) node.indent = r.take().length.toDouble()
    if (r.kind == T.quotedName) {
      val text = r.take()
      node.name = text.jsSubstring(1, text.length - 1)
    } else if (r.kind == T.bareName) {
      node.name = trimBlanksEnd(r.take())
    } else {
      r.fail("a name")
    }
    while (true) {
      if (r.kind == T.classAnnotation) node.classAnnotation = convertValue("CLASS_ANNOTATION", r.take()) as String
      else if (r.kind == T.iconAnnotation) node.iconAnnotation = convertValue("ICON_ANNOTATION", r.take()) as String
      else if (r.kind == T.descAnnotation) node.descAnnotation = convertValue("DESC_ANNOTATION", r.take()) as String
      else break
    }
    ast.nodes.add(node)
  }
  return ast
}
