// Ported from Pele src/diagrams/info/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.info

import pele.js.JsRegExp
import pele.js.jsReplace
import pele.svg.Accessible

class InfoModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
) : Accessible

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun buildInfo(ast: InfoAst, title: String?): InfoModel =
  InfoModel(
    type = "info",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle = if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr = if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
  )
