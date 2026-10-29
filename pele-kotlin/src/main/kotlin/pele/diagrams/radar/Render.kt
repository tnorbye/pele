// Ported from Pele src/diagrams/radar/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.radar

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.fitWidth
import pele.diagrams.common.titleRoom
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.PI
import kotlin.math.ceil
import java.lang.StrictMath.cos
import java.lang.StrictMath.sin

private const val SWATCH = 12
private const val ROW = 26.0
private const val GAP = 24.0

class Point(val x: Double, val y: Double)

private fun option(config: Config, key: String, fallback: Double, min: Double, max: Double): Double {
  val value = (config["radar"] as? Map<*, *>)?.get(key)
  return if (value is Double && value >= min) minOf(value, max) else fallback
}

fun relativeRadius(value: Double, min: Double, max: Double, radius: Double): Double {
  val clipped = minOf(maxOf(value, min), max)
  val scaled = radius * (clipped - min)
  // Keep the original rounding unless multiplication overflows.
  return if (scaled.isFinite()) scaled / (max - min) else radius * ((clipped - min) / (max - min))
}

// A closed Catmull-Rom spline through the points, as cubic Bézier segments.
fun closedRoundCurve(points: List<Point>, tension: Double): String {
  val n = points.size
  val d = StringBuilder("M${num(points[0].x)},${num(points[0].y)}")
  for (i in 0 until n) {
    val p0 = points[(i - 1 + n) % n]
    val p1 = points[i]
    val p2 = points[(i + 1) % n]
    val p3 = points[(i + 2) % n]
    d.append(
      " C${num(p1.x + (p2.x - p0.x) * tension)},${num(p1.y + (p2.y - p0.y) * tension)}" +
        " ${num(p2.x - (p3.x - p1.x) * tension)},${num(p2.y - (p3.y - p1.y) * tension)} ${num(p2.x)},${num(p2.y)}",
    )
  }
  return d.toString() + " Z"
}

private fun polygon(points: List<Point>): String {
  var out = ""
  for (p in points) out += (if (out.isNotEmpty()) " " else "") + num(p.x) + "," + num(p.y)
  return out
}

fun renderRadar(model: RadarModel, config: Config, options: RenderOptions): Rendered {
  val natural = option(config, "width", 320.0, 1.0, 4000.0)
  return fitWidth(options, natural, 160.0) { width, tight ->
    draw(model, config, options, width, (option(config, "height", 320.0, 1.0, 4000.0) * width) / natural, tight)
  }
}

// `tight` is set when the chart does not fit the width it has with its legend beside it.
private fun draw(model: RadarModel, config: Config, options: RenderOptions, width: Double, height: Double, tight: Boolean): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val axisScale = option(config, "axisScaleFactor", 1.0, 0.0, 10.0)
  val labelFactor = option(config, "axisLabelFactor", 1.05, 0.0, 10.0)
  val tension = option(config, "curveTension", 0.17, 0.0, 1.0)
  val radius = minOf(width, height) / 2

  val axes = model.axes
  val curves = model.curves
  val ticks = model.options.ticks
  val graticule = model.options.graticule
  val min = model.options.min
  val count = axes.size
  val unit: List<Point> = axes.mapIndexed { i, _ ->
    val angle = (2 * PI * i) / count - PI / 2
    Point(cos(angle), sin(angle))
  }

  var max = model.options.max
  if (max == null) {
    max = Double.NEGATIVE_INFINITY
    for (curve in curves) for (value in curve.entries) if (value > max!!) max = value
  }
  val maxValue: Double = max!!

  // The chart is drawn around the origin. Margins reserve space; labels and the legend widen it.
  var x0 = -width / 2 - option(config, "marginLeft", 16.0, 0.0, 2000.0)
  var x1 = width / 2 + option(config, "marginRight", 16.0, 0.0, 2000.0)
  var y0 = -height / 2 - option(config, "marginTop", 16.0, 0.0, 2000.0)
  var y1 = height / 2 + option(config, "marginBottom", 16.0, 0.0, 2000.0)

  val grid = StringBuilder()
  var i = 0
  while (i < ticks) {
    val r = minOf(radius, (radius * (i + 1)) / ticks)
    if (graticule == "circle") grid.append("<circle r=\"${num(r)}\"/>")
    else grid.append("<polygon points=\"${polygon(unit.map { u -> Point(u.x * r, u.y * r) })}\"/>")
    i++
  }

  val spokes = StringBuilder()
  val labels = StringBuilder()
  unit.forEachIndexed { index, u ->
    val reach = radius * axisScale
    spokes.append("M0,0L${num(u.x * reach)},${num(u.y * reach)}")
    val label = layoutLabel(axes[index].label, false, measurer, small, 4000.0)
    val at = radius * labelFactor + 6
    // Each label hangs off its axis on the side away from the center.
    val cx = u.x * at + (if (u.x > 0.01) label.width / 2 else if (u.x < -0.01) -label.width / 2 else 0.0)
    val cy = u.y * at + (if (u.y > 0.01) label.height / 2 else if (u.y < -0.01) -label.height / 2 else 0.0)
    labels.append("<g class=\"pele-axis-label\" data-id=\"${escText(axes[index].name)}\">${labelSvg(label, cx, cy, "")}</g>")
    x0 = minOf(x0, cx - label.width / 2)
    x1 = maxOf(x1, cx + label.width / 2)
    y0 = minOf(y0, cy - label.height / 2)
    y1 = maxOf(y1, cy + label.height / 2)
  }

  val shapes = StringBuilder()
  curves.forEachIndexed { index, curve ->
    // Mermaid leaves out a curve that does not have a value for every axis.
    if (curve.entries.size != count) return@forEachIndexed
    val points = curve.entries.mapIndexed { k, value ->
      val r = relativeRadius(value, min, maxValue, radius)
      val safe = if (r.isFinite()) r else 0.0
      Point(unit[k].x * safe, unit[k].y * safe)
    }
    val color = seriesColor(index)
    val attrs = " class=\"pele-curve\" data-id=\"${escText(curve.name)}\" fill=\"$color\" stroke=\"$color\""
    shapes.append(
      if (graticule == "circle") {
        "<path$attrs d=\"${closedRoundCurve(points, tension)}\"/>"
      } else {
        "<polygon$attrs points=\"${polygon(points)}\"/>"
      },
    )
  }

  val legend: List<Label> = if (model.options.showLegend) {
    curves.map { curve -> layoutLabel(curve.label, false, measurer, small, 4000.0) }
  } else {
    emptyList()
  }
  var legendWidth = 0.0
  for (label in legend) legendWidth = maxOf(legendWidth, label.width)
  val legendHeight = legend.size * ROW
  val legendX = if (tight) -(SWATCH + 8 + legendWidth) / 2 else x1 + GAP
  val legendTop = if (tight) y1 + GAP / 2 else -legendHeight / 2
  if (legend.isNotEmpty() && tight) {
    x0 = minOf(x0, legendX)
    x1 = maxOf(x1, -legendX)
    y1 = legendTop + legendHeight
  } else if (legend.isNotEmpty()) {
    x1 = legendX + SWATCH + 8 + legendWidth
    y0 = minOf(y0, -legendHeight / 2)
    y1 = maxOf(y1, legendHeight / 2)
  }
  val legendOut = StringBuilder()
  legend.forEachIndexed { index, label ->
    val y = legendTop + index * ROW + ROW / 2
    val color = seriesColor(index)
    legendOut.append(
      "<g class=\"pele-legend-item\" data-id=\"${escText(curves[index].name)}\">" +
        "<rect x=\"${num(legendX)}\" y=\"${num(y - SWATCH / 2.0)}\" width=\"$SWATCH\" height=\"$SWATCH\" rx=\"2\" fill=\"$color\" fill-opacity=\"0.4\" stroke=\"$color\"/>" +
        labelSvg(label, legendX + SWATCH + 8 + label.width / 2, y, "") +
        "</g>",
    )
  }

  val title = layoutLabel(model.title, false, measurer, size, titleRoom(options), Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 8 else 0.0
  val bodyWidth = maxOf(x1 - x0, title.width)
  val totalWidth = ceil(bodyWidth + 2 * pad)
  val totalHeight = ceil(y1 - y0 + titleHeight + 2 * pad)
  val origin = "translate(${num(pad + (bodyWidth - (x1 - x0)) / 2 - x0)},${num(pad + titleHeight - y0)})"

  val smallText = jsStr(small)
  val content =
    labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g transform=\"$origin\">" +
      (if (grid.isNotEmpty()) "<g class=\"pele-graticule\" fill=\"none\" stroke=\"var(--_a)\">$grid</g>" else "") +
      (if (spokes.isNotEmpty()) "<path class=\"pele-axes\" d=\"$spokes\" fill=\"none\" stroke=\"var(--_b)\"/>" else "") +
      (if (shapes.isNotEmpty()) "<g class=\"pele-curves\" fill-opacity=\"0.2\" stroke-width=\"1.5\" stroke-linejoin=\"round\">$shapes</g>" else "") +
      (if (labels.isNotEmpty()) "<g class=\"pele-axis-labels\" font-size=\"$smallText\">$labels</g>" else "") +
      (if (legendOut.isNotEmpty()) "<g class=\"pele-legend\" font-size=\"$smallText\">$legendOut</g>" else "") +
      "</g>"

  return Rendered(
    svg = svgDocument("radar", totalWidth, totalHeight, size, options, model, content),
    width = totalWidth,
    height = totalHeight,
    links = ArrayList(),
  )
}
