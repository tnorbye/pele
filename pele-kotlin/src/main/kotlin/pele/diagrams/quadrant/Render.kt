// Ported from Pele src/diagrams/quadrant/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.quadrant

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.capWidth
import pele.js.jsParseInt
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.ROUNDED
import pele.svg.classNames
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.resolveStyle
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel

private const val GAP = 4.0
private const val CAREFUL = 300

private class Dot(val cx: Double, val cy: Double, val reach: Double, val text: Label)

private fun setting(config: Config, key: String): Any? = (config["quadrantChart"] as? Map<*, *>)?.get(key)

private fun length(config: Config, key: String, fallback: Double, max: Double): Double {
  val value = setting(config, key)
  return if (value is Double && value >= 0) minOf(value, max) else fallback
}

private fun hex(color: String): String = if (color.startsWith("#")) color else "#$color"

fun renderQuadrant(model: QuadrantModel, config: Config, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val axisSize = jsRound(size * 0.875)
  val pointSize = jsRound(size * 0.75)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val natural = jsRound(maxOf(100.0, length(config, "chartWidth", 500.0, 4000.0)))
  val width = jsRound(capWidth(options, natural, 280.0))
  val height = jsRound((maxOf(100.0, length(config, "chartHeight", 500.0, 4000.0)) * width) / natural)
  val outer = length(config, "quadrantPadding", options.padding ?: 8.0, 200.0)
  val xPad = length(config, "xAxisLabelPadding", 5.0, 200.0)
  val yPad = length(config, "yAxisLabelPadding", 5.0, 200.0)
  val hasPoints = model.points.size > 0

  val label = { text: QuadrantText?, fontSize: Double, max: Double ->
    layoutLabel(text?.text, text?.type == "markdown", measurer, fontSize, maxOf(max, 40.0))
  }

  val title = layoutLabel(model.title, false, measurer, size, maxOf(width - 2 * outer, 40.0), Style.Bold)
  val titlePad = length(config, "titlePadding", 10.0, 200.0)
  val titleSpace = if (title.height > 0) title.height + titlePad else 0.0

  val hasX = !model.xAxisLeft?.text.isNullOrEmpty() || !model.xAxisRight?.text.isNullOrEmpty()
  val hasY = !model.yAxisBottom?.text.isNullOrEmpty() || !model.yAxisTop?.text.isNullOrEmpty()
  // Mermaid moves the x axis labels below the chart as soon as there are points.
  val xBelow = hasPoints || setting(config, "xAxisPosition") == "bottom"
  val yRight = setting(config, "yAxisPosition") == "right"

  val side = width - 2 * outer
  val xLeft = label(model.xAxisLeft, axisSize, if (model.xAxisRight != null) side / 2 - 16 else side)
  val xRight = label(model.xAxisRight, axisSize, side / 2 - 16)
  val xHeight = maxOf(xLeft.height, xRight.height)
  val xSpace = if (hasX) xHeight + xPad else 0.0
  val tall = height - 2 * outer - titleSpace - xSpace
  val yBottom = label(model.yAxisBottom, axisSize, if (model.yAxisTop != null) tall / 2 - 16 else tall)
  val yTop = label(model.yAxisTop, axisSize, tall / 2 - 16)
  val yHeight = maxOf(yBottom.height, yTop.height)
  val ySpace = if (hasY) yHeight + yPad else 0.0

  val left = outer + (if (yRight) 0.0 else ySpace)
  val top = outer + titleSpace + (if (xBelow) 0.0 else xSpace)
  val w = maxOf(0.0, side - ySpace)
  val h = maxOf(0.0, tall)
  val tileW = maxOf(0.0, (w - GAP) / 2)
  val tileH = maxOf(0.0, (h - GAP) / 2)

  // Boxes a point label keeps clear of: the gaps between quadrants, quadrant labels, points,
  // and the labels placed before it.
  val taken = arrayListOf(left + tileW, top, left + w - tileW, top + h, left, top + tileH, left + w, top + h - tileH)
  val isClear = { from: Int, x0: Double, y0: Double, x1: Double, y1: Double ->
    var clear = true
    var k = from
    while (k < taken.size) {
      // Empty quadrant labels must not block point labels.
      if (taken[k] >= taken[k + 2] || taken[k + 1] >= taken[k + 3]) {
        k += 4
        continue
      }
      if (x0 < taken[k + 2] && x1 > taken[k] && y0 < taken[k + 3] && y1 > taken[k + 1]) {
        clear = false
        break
      }
      k += 4
    }
    clear
  }

  val textTop = length(config, "quadrantTextTopPadding", 5.0, 200.0)
  var quadrants = ""
  for (k in 0 until 4) {
    val x = if (k == 0 || k == 3) left + w - tileW else left
    val y = if (k < 2) top else top + h - tileH
    val text = label(model.quadrants[k], size, tileW - 16)
    val cx = x + tileW / 2
    val cy = if (hasPoints) y + textTop + text.height / 2 else y + tileH / 2
    taken.add(cx - text.width / 2)
    taken.add(cy - text.height / 2)
    taken.add(cx + text.width / 2)
    taken.add(cy + text.height / 2)
    quadrants +=
      "<g class=\"pele-quadrant\" data-id=\"${k + 1}\">" +
      "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(tileW)}\" height=\"${num(tileH)}\"$ROUNDED/>" +
      labelSvg(text, cx, cy, " class=\"pele-label\" fill=\"var(--_fg)\" stroke=\"none\"") +
      "</g>"
  }

  var axes = ""
  if (hasX) {
    val y = if (xBelow) top + h + xPad + xHeight / 2 else top - xPad - xHeight / 2
    axes +=
      labelSvg(xLeft, if (xRight.height > 0) left + w / 4 else left + xLeft.width / 2, y, "") +
      labelSvg(xRight, left + (w * 3) / 4, y, "")
  }
  if (hasY) {
    val x = if (yRight) left + w + yPad + yHeight / 2 else left - yPad - yHeight / 2
    val turned = { text: Label, y: Double ->
      if (text.height > 0) "<g transform=\"translate(${num(x)},${num(y)}) rotate(-90)\">${labelSvg(text, 0.0, 0.0, "")}</g>" else ""
    }
    axes +=
      turned(yBottom, if (yTop.height > 0) top + (h * 3) / 4 else top + h - yBottom.width / 2) + turned(yTop, top + h / 4)
  }

  val radius = length(config, "pointRadius", 5.0, 200.0)
  val textPad = length(config, "pointTextPadding", 5.0, 200.0)
  val fill = seriesColor(0)
  val dots = ArrayList<Dot>()
  val circles = ArrayList<String>()
  for (point in model.points) {
    if (!(point.x >= 0 && point.x <= 1 && point.y >= 0 && point.y <= 1)) continue
    val shared = model.classes[point.className]
    val r = minOf(point.radius ?: shared?.radius ?: radius, 2000.0)
    val color = point.color ?: shared?.color
    val strokeColor = point.strokeColor ?: shared?.strokeColor
    val strokeWidth = point.strokeWidth ?: shared?.strokeWidth
    val styles = ArrayList<String>()
    if (!color.isNullOrEmpty()) styles.add("fill:" + hex(color))
    if (!strokeWidth.isNullOrEmpty()) {
      if (!strokeColor.isNullOrEmpty()) styles.add("stroke:" + hex(strokeColor))
      styles.add("stroke-width:$strokeWidth")
    }
    val cx = left + point.x * w
    val cy = top + (1 - point.y) * h
    dots.add(
      Dot(
        cx,
        cy,
        r + (if (!strokeWidth.isNullOrEmpty()) minOf(jsParseInt(strokeWidth), 400.0) / 2 else 0.0),
        label(point.text, pointSize, 200.0),
      ),
    )
    circles.add(
      "<circle class=\"pele-point${classNames(point.className)}\" data-id=\"${escText(point.text.text)}\" cx=\"${num(cx)}\" cy=\"${num(cy)}\" r=\"${num(r)}\"" +
        (if (!strokeWidth.isNullOrEmpty() && strokeColor.isNullOrEmpty()) " stroke=\"$fill\"" else "") +
        resolveStyle(styles).shape + "/>",
    )
  }

  // A label goes below its point, or above, right or left of it, or below its quadrant's title,
  // when that is the first place that is inside the chart and free. A second round lets it
  // cross the gaps between quadrants.
  // Large charts skip the search for a free place.
  val careful = dots.size <= CAREFUL
  if (careful) {
    for (dot in dots) {
      taken.add(dot.cx - dot.reach)
      taken.add(dot.cy - dot.reach)
      taken.add(dot.cx + dot.reach)
      taken.add(dot.cy + dot.reach)
    }
  }
  var labels = ""
  for (dot in dots) {
    val cx = dot.cx
    val cy = dot.cy
    val reach = dot.reach
    val text = dot.text
    val halfW = text.width / 2
    val halfH = text.size * 0.6 + (text.height - text.lineHeight) / 2
    val away = reach + textPad
    val inset = minOf(away, maxOf(0.0, (tileW - text.width) / 2))
    val heading = 8 + 4 * (if (cx < left + w / 2) (if (cy < top + h / 2) 1 else 2) else (if (cy < top + h / 2) 0 else 3))
    val below = maxOf(cy + away, taken[heading + 3] + textPad) + halfH
    val spots = doubleArrayOf(cx, cy + away + halfH, cx, cy - away - halfH, cx + away + halfW, cy, cx - away - halfW, cy, cx, below)
    var tx = cx
    var ty = spots[1]
    var found = false
    var k = 0
    while (k < 20) {
      val spot = k % 10
      // A label moved below a title keeps clear of the plot's edge.
      val margin = if (spot == 8) inset else 0.0
      val x =
        if ((spot < 4 || spot == 8) && text.width < w) minOf(maxOf(spots[spot], left + halfW + margin), left + w - halfW - margin) else spots[spot]
      val y = spots[spot + 1]
      if (x - halfW < left || x + halfW > left + w || y - halfH < top || y + halfH > top + h) {
        k += 2
        continue
      }
      val free = !careful || isClear(if (k < 10) 0 else 8, x - halfW, y - halfH, x + halfW, y + halfH)
      if (!found || free) {
        tx = x
        ty = y
        found = true
      }
      if (free) break
      k += 2
    }
    if (careful) {
      taken.add(tx - halfW)
      taken.add(ty - halfH)
      taken.add(tx + halfW)
      taken.add(ty + halfH)
    }
    labels += labelSvg(text, tx, ty, " class=\"pele-label\"")
  }

  // Mermaid puts each new point under the ones before it.
  val content =
    labelSvg(title, width / 2, titlePad / 2 + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g class=\"pele-quadrants\" fill=\"var(--_s)\" stroke=\"var(--_b)\">$quadrants</g>" +
      (if (axes.isNotEmpty()) "<g class=\"pele-axis-labels\" font-size=\"${jsStr(axisSize)}\" fill=\"var(--_m)\">$axes</g>" else "") +
      (if (dots.size > 0) {
        "<g class=\"pele-points\" fill=\"$fill\">${circles.reversed().joinToString("")}</g>" +
          "<g class=\"pele-point-labels\" font-size=\"${jsStr(pointSize)}\">$labels</g>"
      } else {
        ""
      })

  return Rendered(
    svgDocument("quadrantChart", width, height, size, options, model, content),
    width,
    height,
    ArrayList(),
  )
}
