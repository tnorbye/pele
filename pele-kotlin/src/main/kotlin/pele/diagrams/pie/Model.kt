// Ported from Pele src/diagrams/pie/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.pie

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsStr
import pele.svg.Accessible

class PieModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  val showData: Boolean,
  val sections: LinkedHashMap<String, Double>,
) : Accessible

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun buildPie(ast: PieAst, title: String?): PieModel {
  val sections = LinkedHashMap<String, Double>()
  for (section in ast.sections) {
    val label = section.label
    val value = section.value
    if (value < 0) {
      throw PeleError(
        "\"$label\" has invalid value: ${jsStr(value)}. Negative values are not allowed in pie charts. All slice values must be >= 0.",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "pie"),
      )
    }
    if (!sections.containsKey(label)) sections[label] = value
  }
  return PieModel(
    type = "pie",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle =
      if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr =
      if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    showData = ast.showData,
    sections = sections,
  )
}
