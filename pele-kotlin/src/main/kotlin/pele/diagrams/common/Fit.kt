// Ported from Pele src/diagrams/common/fit.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.js.JsRegExp
import pele.js.jsMatchGlobal
import pele.js.jsReplace
import pele.js.jsTrim
import pele.text.Label
import pele.text.TextMeasurer
import pele.text.layoutLabel

private val RE_TAG_START = JsRegExp("""<(?!br\s*\/?>)""", "gi")
private val RE_ICON = JsRegExp(":fa-", "g")

// A line break, an encoded entity, a surrogate pair, or one character: the pieces a word can be cut
// between.
private val RE_UNIT =
  JsRegExp("""<br\s*\/?>|ﬂ°°?\w+¶ß|&#?\w+;|[\ud800-\udbff][\udc00-\udfff]|[\s\S]""", "gi")

// Lays out text the way Mermaid's journey and timeline draw it: as plain text in which only
// <br> is markup, so other tags and icon names show as written instead of being read as formatting.
fun plainLabel(
  raw: String?,
  measurer: TextMeasurer,
  size: Double,
  max: Double,
  base: Int = 0,
): Label {
  var text = raw
  if (text != null) {
    if (text.contains('<')) text = text.jsReplace(RE_TAG_START, "&lt;")
    if (text.contains(":fa-")) text = text.jsReplace(RE_ICON, "&colon;fa-")
  }
  return layoutLabel(text, false, measurer, size, max, base)
}

// layoutLabel wraps at spaces only. For a box of fixed width, this also cuts any word too long to
// fit.
fun fitLabel(
  raw: String?,
  measurer: TextMeasurer,
  size: Double,
  max: Double,
  base: Int = 0,
): Label {
  val label = plainLabel(raw, measurer, size, max, base)
  if (raw == null || label.width <= max) return label
  val out = StringBuilder()
  var width = 0.0
  for (unit in raw.jsMatchGlobal(RE_UNIT) ?: emptyList()) {
    if (unit.jsTrim() == "" || (unit.length > 2 && unit[0] == '<')) {
      width = 0.0
    } else {
      val w = measurer.width(if (unit.length > 2) "x" else unit, size, base)
      if (width > 0 && width + w > max) {
        out.append('\n')
        width = 0.0
      }
      width += w
    }
    out.append(unit)
  }
  return plainLabel(out.toString(), measurer, size, max, base)
}
