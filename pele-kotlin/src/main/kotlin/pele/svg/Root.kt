// Ported from Pele src/svg/root.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import pele.RenderOptions
import pele.js.JsRegExp
import pele.js.jsMatchAll
import pele.js.jsStr

// The weights of bold text, each set only in a diagram that has such text.
private val WEIGHTS =
  listOf(
    "tw" to "title",
    "hw" to "heading",
    "w" to "bold",
  )

private val RE_USED = JsRegExp("""var\(--_(r|tw|hw|w)\)""", "g")

interface Accessible {
  val accTitle: String?
  val accDescr: String?
}

class Acc(override val accTitle: String? = null, override val accDescr: String? = null) : Accessible

fun svgDocument(
  type: String,
  width: Double,
  height: Double,
  fontSize: Double,
  options: RenderOptions,
  acc: Accessible,
  content: String,
): String {
  val prefix = esc(options.idPrefix ?: "pele")
  var head = ""
  var aria = ""
  val accTitle = acc.accTitle
  if (!accTitle.isNullOrEmpty()) {
    head += "<title id=\"$prefix-title\">${escText(accTitle)}</title>"
    aria += " aria-labelledby=\"$prefix-title\""
  }
  val accDescr = acc.accDescr
  if (!accDescr.isNullOrEmpty()) {
    head += "<desc id=\"$prefix-desc\">${escText(accDescr)}</desc>"
    aria += " aria-describedby=\"$prefix-desc\""
  }
  val fit = if (options.responsive == false) "" else "max-width:100%;height:auto;"
  val used = HashSet<String>()
  for (m in content.jsMatchAll(RE_USED)) {
    used.add(m[1]!!)
    if (used.size == 4) break
  }
  var weights = if ("r" in used) ";--_r:$RADIUS" else ""
  for ((alias, name) in WEIGHTS) {
    if (alias in used) weights += ";--_$alias:var(--pele-$name-weight,bold)"
  }
  val w = jsStr(width)
  val h = jsStr(height)
  return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"pele pele-$type\" viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\" style=\"$fit$ROOT_STYLE$weights${esc(fontStyle(fontSize, options.fontFamily, options.fontFamilyMono))}\" fill=\"var(--_fg)\" role=\"graphics-document document\" aria-roledescription=\"$type\"$aria>" +
    head +
    content +
    "</svg>"
}
