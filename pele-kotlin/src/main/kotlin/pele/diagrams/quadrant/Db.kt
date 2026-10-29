// Ported from Pele src/diagrams/quadrant/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.quadrant

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsNumber
import pele.js.jsParseInt
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsTrim
import pele.js.jsTrimEnd
import pele.js.jsTrimStart
import pele.svg.Accessible

// `type` is 'text' or 'markdown'.
class QuadrantText(var text: String, val type: String)

open class PointStyle(
  var radius: Double? = null,
  var color: String? = null,
  var strokeColor: String? = null,
  var strokeWidth: String? = null,
)

class QuadrantPoint(
  val x: Double,
  val y: Double,
  val text: QuadrantText,
  val className: String,
  radius: Double?,
  color: String?,
  strokeColor: String?,
  strokeWidth: String?,
) : PointStyle(radius, color, strokeColor, strokeWidth)

interface QuadrantModel : Accessible {
  val type: String
  val title: String?
  override val accTitle: String?
  override val accDescr: String?

  // Quadrants 1 to 4: top right, top left, bottom left, bottom right.
  val quadrants: List<QuadrantText?>
  val xAxisLeft: QuadrantText?
  val xAxisRight: QuadrantText?
  val yAxisBottom: QuadrantText?
  val yAxisTop: QuadrantText?
  val points: List<QuadrantPoint>
  val classes: Map<String, PointStyle>
}

private val RE_NUMBER = JsRegExp("""^\d+$""")
private val RE_HEX = JsRegExp("""^#?([\dA-Fa-f]{6}|[\dA-Fa-f]{3})$""")
private val RE_PIXELS = JsRegExp("""^\d+px$""")

private fun invalid(style: String, value: String, kind: String): PeleError =
  PeleError(
    "value for $style $value is invalid, please use a valid $kind",
    PeleErrorCode.SEMANTIC,
    PeleErrorInfo(type = "quadrantChart"),
  )

// The first two parts of Mermaid's `style.trim().split(/\s*:\s*/)`, found in one pass.
private fun keyValue(style: String): Pair<String, String> {
  val s = style.jsTrim()
  val colon = s.indexOf(':')
  if (colon == -1) return Pair(s, "undefined")
  val rest = s.jsSlice(colon + 1).jsTrimStart()
  val next = rest.indexOf(':')
  return Pair(s.jsSlice(0, colon).jsTrimEnd(), if (next == -1) rest else rest.jsSlice(0, next).jsTrimEnd())
}

fun parseStyles(styles: List<String>): PointStyle {
  val out = PointStyle()
  for (style in styles) {
    val (key, value) = keyValue(style)
    if (key == "radius") {
      if (!RE_NUMBER.test(value)) throw invalid(key, value, "number")
      out.radius = jsParseInt(value)
    } else if (key == "color") {
      if (!RE_HEX.test(value)) throw invalid(key, value, "hex code")
      out.color = value
    } else if (key == "stroke-color") {
      if (!RE_HEX.test(value)) throw invalid(key, value, "hex code")
      out.strokeColor = value
    } else if (key == "stroke-width") {
      if (!RE_PIXELS.test(value)) throw invalid(key, value, "number of pixels (eg. 10px)")
      out.strokeWidth = value
    } else {
      throw PeleError("style named $key is not supported.", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "quadrantChart"))
    }
  }
  return out
}

private fun trimmed(label: QuadrantText): QuadrantText = QuadrantText(label.text.jsTrim(), label.type)

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

class QuadrantDb : QuadrantModel {
  override val type = "quadrantChart"
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  override val quadrants: MutableList<QuadrantText?> = arrayListOf(null, null, null, null)
  override var xAxisLeft: QuadrantText? = null
  override var xAxisRight: QuadrantText? = null
  override var yAxisBottom: QuadrantText? = null
  override var yAxisTop: QuadrantText? = null
  override val points: MutableList<QuadrantPoint> = ArrayList()
  override val classes: MutableMap<String, PointStyle> = LinkedHashMap()

  fun setQuadrant1Text(label: QuadrantText) {
    this.quadrants[0] = trimmed(label)
  }

  fun setQuadrant2Text(label: QuadrantText) {
    this.quadrants[1] = trimmed(label)
  }

  fun setQuadrant3Text(label: QuadrantText) {
    this.quadrants[2] = trimmed(label)
  }

  fun setQuadrant4Text(label: QuadrantText) {
    this.quadrants[3] = trimmed(label)
  }

  fun setXAxisLeftText(label: QuadrantText) {
    this.xAxisLeft = trimmed(label)
  }

  fun setXAxisRightText(label: QuadrantText) {
    this.xAxisRight = trimmed(label)
  }

  fun setYAxisBottomText(label: QuadrantText) {
    this.yAxisBottom = trimmed(label)
  }

  fun setYAxisTopText(label: QuadrantText) {
    this.yAxisTop = trimmed(label)
  }

  fun addPoint(label: QuadrantText, className: String, x: String, y: String, styles: List<String>) {
    val style = parseStyles(styles)
    this.points.add(
      QuadrantPoint(jsNumber(x), jsNumber(y), trimmed(label), className, style.radius, style.color, style.strokeColor, style.strokeWidth),
    )
  }

  fun addClass(className: String, styles: List<String>) {
    this.classes[className] = parseStyles(styles)
  }

  fun setDiagramTitle(text: String) {
    this.title = text
  }

  @JvmName("setAccTitleText")
  fun setAccTitle(text: String) {
    this.accTitle = text.jsReplace(RE_LEADING, "")
  }

  @JvmName("setAccDescriptionText")
  fun setAccDescription(text: String) {
    this.accDescr = text.jsReplace(RE_INDENT, "\n")
  }
}
