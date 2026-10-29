// Ported from Pele src/svg/theme.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import pele.js.JsRegExp
import pele.js.jsNumber
import pele.js.jsParseFloat
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsStr
import pele.js.jsTrim

// Private aliases set once on the root element, so each use is short and the public
// --pele-* tokens keep their fallbacks. The text color is set as well: an icon is drawn in
// currentColor, which would otherwise be whatever color the page gives the diagram's container.
const val ROOT_STYLE =
  "--_bg:var(--pele-bg,#fff);--_fg:var(--pele-fg,#1f1f1f);--_m:var(--pele-muted,#6e6e6e);" +
    "--_l:var(--pele-line,#1f1f1f);--_s:var(--pele-surface,#f3f3f3);--_a:var(--pele-surface-alt,#e6e6e6);" +
    "--_b:var(--pele-border,#8a8a8a);--_c:var(--pele-accent,#5b7bd5);color:var(--_fg)"

const val FONT_MONO = "var(--_fm)"
const val RADIUS = "var(--pele-radius,4px)"

// Use CSS for rx variables to support older browsers. Put the default first so
// diagram styles can override it.
const val ROUNDED = " style=\"rx:var(--_r)\""

private val RE_SETS_FILL = JsRegExp("""[";]fill:""")

// Expects a shape style from resolveStyle().
fun setsFill(shape: String): Boolean = RE_SETS_FILL.test(shape)

fun rounded(attrs: String): String {
  val at = attrs.indexOf(" style=\"")
  return if (at < 0) attrs + ROUNDED else "${attrs.jsSlice(0, at + 8)}rx:var(--_r);${attrs.jsSlice(at + 8)}"
}

// The color of what a diagram marks as critical. It is not one of the series colors, as a theme
// may put those in any order.
const val CRITICAL = "var(--pele-critical,#e45756)"

// A list of font names, quoted or not, and nothing that could end the declaration it is put in.
private const val FAMILY = """(?:"[^"'\\\n<>{}();:@]*"|'[^"'\\\n<>{}();:@]*'|[\p{L}\p{N}_-][\p{L}\p{N}_ .-]*)"""
private val RE_FAMILIES = JsRegExp("""^\s*$FAMILY(?:\s*,\s*$FAMILY)*\s*$""", "u")

private fun families(list: String?, generic: String): String = if (!list.isNullOrEmpty() && RE_FAMILIES.test(list)) list else generic

// The fonts the labels were measured in are the ones they are drawn in, unless the page names
// others. The rest of what text inherits is pinned, as none of it was measured.
fun fontStyle(size: Double, family: String?, mono: String?): String =
  ";--_fm:var(--pele-font-mono,${families(mono, "monospace")});font:${jsStr(size)}px sans-serif;" +
    "letter-spacing:normal;word-spacing:normal;text-transform:none;direction:ltr;" +
    "font-family:var(--pele-font,${families(family, "sans-serif")})"

private val SHAPE_PROPS = setOf(
  "fill",
  "fill-opacity",
  "stroke",
  "stroke-width",
  "stroke-dasharray",
  "stroke-dashoffset",
  "stroke-linecap",
  "stroke-linejoin",
  "stroke-opacity",
  "opacity",
  "rx",
  "ry",
)

private val TEXT_PROPS = setOf(
  "color",
  "font-size",
  "font-family",
  "font-weight",
  "font-style",
  "text-decoration",
  "letter-spacing",
  "word-spacing",
  "opacity",
)

private val RE_UNSAFE = JsRegExp("""url\s*\(|expression\s*\(|javascript:|[<>{};\\]|@import|image-set|\/\*""", "i")

class ResolvedStyle(
  val shape: String,
  val line: String,
  val text: String,
  val stroke: String?,
  val bold: Boolean,
  val italic: Boolean,
  val fontSize: Double?,
)

private val NONE = ResolvedStyle(
  shape = "",
  line = "",
  text = "",
  stroke = null,
  bold = false,
  italic = false,
  fontSize = null,
)

private val RE_IMPORTANT = JsRegExp("!important", "gi")
private val RE_PX = JsRegExp("""^[\d.]+(px)?$""")

// Splits Mermaid style declarations into what applies to a shape and what applies to its text.
fun resolveStyle(declarations: List<String>): ResolvedStyle {
  if (declarations.isEmpty()) return NONE
  val shape = LinkedHashMap<String, String>()
  val text = LinkedHashMap<String, String>()
  for (decl in declarations) {
    val at = decl.indexOf(':')
    if (at <= 0) continue
    val prop = decl.jsSlice(0, at).jsTrim().lowercase()
    val value = decl
      .jsSlice(at + 1)
      .jsReplace(RE_IMPORTANT, "")
      .jsTrim()
    if (value == "" || RE_UNSAFE.test(value) || hasInvalidXml(value)) continue
    if (prop in SHAPE_PROPS) shape[prop] = value
    if (prop in TEXT_PROPS) text[if (prop == "color") "fill" else prop] = value
  }
  if (shape.isEmpty() && text.isEmpty()) return NONE
  val shapeCss = StringBuilder()
  val lineCss = StringBuilder()
  for ((prop, value) in shape) {
    shapeCss.append("$prop:$value;")
    if (prop != "fill" && prop != "fill-opacity") lineCss.append("$prop:$value;")
  }
  val textCss = StringBuilder()
  for ((prop, value) in text) textCss.append("$prop:$value;")
  val weight = text["font-weight"]
  val size = text["font-size"]
  val px = if (!size.isNullOrEmpty() && RE_PX.test(size)) jsParseFloat(size) else null
  return ResolvedStyle(
    shape = if (shapeCss.isNotEmpty()) " style=\"${esc(shapeCss.toString())}\"" else "",
    line = if (lineCss.isNotEmpty()) " style=\"${esc(lineCss.toString())}\"" else "",
    text = if (textCss.isNotEmpty()) " style=\"${esc(textCss.toString())}\"" else "",
    stroke = shape["stroke"],
    bold = weight == "bold" || weight == "bolder" || (if (weight == null) Double.NaN else jsNumber(weight)) >= 600,
    italic = text["font-style"] == "italic",
    fontSize = if (px != null && px > 0 && px <= 200) px else null,
  )
}

private val RE_CLASS = JsRegExp("""[^\w-]+""", "g")

fun classNames(raw: String): String {
  val cleaned = raw.jsReplace(RE_CLASS, " ").jsTrim()
  return if (cleaned == "") "" else " $cleaned"
}

private val SERIES = listOf("#4c78a8", "#f58518", "#54a24b", "#e45756", "#72b7b2", "#eeca3b", "#b279a2", "#9d755d")

// Color for the nth data series in a chart, cycling through eight tokens.
fun seriesColor(index: Int): String {
  val k = ((index % 8) + 8) % 8
  return "var(--pele-series-${k + 1},${SERIES[k]})"
}
