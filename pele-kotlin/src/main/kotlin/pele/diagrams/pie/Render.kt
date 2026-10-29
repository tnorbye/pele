// Ported from Pele src/diagrams/pie/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.pie

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.fitWidth
import pele.diagrams.common.titleRoom
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsString
import pele.js.toFixed
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
import kotlin.math.abs
import kotlin.math.ceil
import java.lang.StrictMath.cos
import java.lang.StrictMath.sin

private const val RADIUS = 120.0
private const val SWATCH = 12
private const val ROW = 26.0
private const val GAP = 32.0
private const val OUTSIDE = 28.0

private fun option(config: Config, key: String): Any? = (config["pie"] as? Map<*, *>)?.get(key)

private fun point(angle: Double, radius: Double): String = "${num(sin(angle) * radius)},${num(-cos(angle) * radius)}"

private fun slice(start: Double, end: Double, outer: Double, inner: Double): String {
  if (end - start >= PI * 2 - 1e-9) {
    val ring = { r: Double, sweep: Int ->
      "M0,${num(-r)}A${num(r)},${num(r)} 0 1 $sweep 0,${num(r)}A${num(r)},${num(r)} 0 1 $sweep 0,${num(-r)}Z"
    }
    return ring(outer, 1) + (if (inner > 0) ring(inner, 0) else "")
  }
  val large = if (end - start > PI) 1 else 0
  if (inner <= 0) {
    return "M0,0L${point(start, outer)}A${num(outer)},${num(outer)} 0 $large 1 ${point(end, outer)}Z"
  }
  return "M${point(start, outer)}A${num(outer)},${num(outer)} 0 $large 1 ${point(end, outer)}" +
    "L${point(end, inner)}A${num(inner)},${num(inner)} 0 $large 0 ${point(start, inner)}Z"
}

fun renderPie(model: PieModel, config: Config, options: RenderOptions): Rendered =
  fitWidth(options, RADIUS * 2, 140.0) { diameter, tight -> draw(model, config, options, diameter / 2, tight) }

private class Placed(val x: Double, val y: Double, val width: Double)

// `tight` is set when the chart does not fit the width it has with its legend beside it.
private fun draw(model: PieModel, config: Config, options: RenderOptions, radius: Double, tight: Boolean): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val position = option(config, "textPosition")
  val textPosition = if (position is Double && position >= 0 && position <= 1) position else 0.75
  val hole = option(config, "donutHole")
  val inner = if (hole is Double && hole > 0 && hole <= 0.9) hole * radius else 0.0
  val placed = jsString(option(config, "legendPosition") ?: "right")
  val legendPosition = if (tight && (placed == "left" || placed == "right")) "bottom" else placed

  val entries = model.sections.entries.map { it.key to it.value }
  var sum = 0.0
  for ((_, value) in entries) sum += value

  val legend: List<Label> = entries.map { (label, value) ->
    layoutLabel(if (model.showData) "$label [${jsStr(value)}]" else label, false, measurer, small, 4000.0)
  }
  var legendWidth = 0.0
  for (label in legend) legendWidth = maxOf(legendWidth, label.width)
  legendWidth += if (legend.isNotEmpty()) SWATCH + 8.0 else 0.0
  val legendHeight = legend.size * ROW

  val title = layoutLabel(model.title, false, measurer, size, titleRoom(options), Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 16 else 0.0

  // A slice too thin for its percentage gets the label outside the rim, which needs a margin.
  val shares = entries.map { (_, value) ->
    val share = if (sum > 0) value / sum else 0.0
    if (share * 100 >= 1 && (share * 100).toFixed(0) != "0") share else 0.0
  }
  val narrow = shares.map { share ->
    if (share == 0.0 || share >= 0.5) {
      false
    } else {
      val chord = 2 * radius * textPosition * sin(share * PI)
      chord < measurer.width((share * 100).toFixed(0) + "%", small, 0) + 6
    }
  }
  val margin = if (narrow.contains(true)) OUTSIDE else 0.0

  val side = if (legendPosition == "left" || legendPosition == "right" || legendPosition == "center") legendPosition else ""
  val stacked = legendPosition == "top" || legendPosition == "bottom"
  val diameter = (radius + margin) * 2
  var width = diameter
  var height = diameter
  if (side == "left" || side == "right") {
    width += if (legendWidth > 0) GAP + legendWidth else 0.0
    height = maxOf(height, legendHeight)
  } else if (stacked) {
    width = maxOf(width, legendWidth)
    height += if (legendHeight > 0) 20 + legendHeight else 0.0
  }
  width = maxOf(width, title.width)

  val cx = pad + (if (side == "left") legendWidth + GAP + diameter / 2 else if (side == "right") diameter / 2 else width / 2)
  val bodyTop = pad + titleHeight
  val cy = bodyTop + (if (legendPosition == "top") legendHeight + 20 + diameter / 2 else if (stacked) diameter / 2 else height / 2)

  val slices = StringBuilder()
  val labels = StringBuilder()
  val outside = StringBuilder()
  var angle = 0.0
  val rim = radius + OUTSIDE / 2
  val placedOutside = ArrayList<Placed>()
  val clear = { x: Double, y: Double, w: Double ->
    placedOutside.all { other -> abs(x - other.x) >= (w + other.width) / 2 + 2 || abs(y - other.y) >= small + 2 }
  }
  entries.forEachIndexed { index, (label, _) ->
    val share = shares[index]
    if (share == 0.0) return@forEachIndexed
    val end = angle + share * PI * 2
    slices.append("<path class=\"pele-slice\" data-id=\"${escText(label)}\" d=\"${slice(angle, end, radius, inner)}\" fill=\"${seriesColor(index)}\"/>")
    val mid = (angle + end) / 2
    val text = (share * 100).toFixed(0) + "%"
    if (narrow[index]) {
      val w = measurer.width(text, small, 0)
      var at = mid
      var step = 0
      while (step < 360 && !clear(sin(at) * rim, -cos(at) * rim, w)) {
        at += 2 / rim
        step++
      }
      val x = sin(at) * rim
      val y = -cos(at) * rim
      placedOutside.add(Placed(x, y, w))
      outside.append("<text x=\"${num(x)}\" y=\"${num(y + small * 0.35)}\">$text</text>")
    } else {
      val r = radius * textPosition
      labels.append("<text x=\"${num(sin(mid) * r)}\" y=\"${num(-cos(mid) * r + small * 0.35)}\">$text</text>")
    }
    angle = end
  }
  if (slices.isEmpty()) {
    slices.append("<circle class=\"pele-slice\" r=\"${jsStr(radius)}\" fill=\"none\" stroke=\"var(--_b)\"/>")
  }

  val legendOut = StringBuilder()
  val lx: Double
  val ly: Double
  if (side == "right") {
    lx = cx + diameter / 2 + GAP
    ly = bodyTop + (height - legendHeight) / 2
  } else if (side == "left") {
    lx = pad
    ly = bodyTop + (height - legendHeight) / 2
  } else if (side == "center") {
    lx = cx - legendWidth / 2
    ly = cy - legendHeight / 2
  } else {
    lx = pad + (width - legendWidth) / 2
    ly = if (legendPosition == "top") bodyTop else bodyTop + diameter + 20
  }
  legend.forEachIndexed { index, label ->
    val y = ly + index * ROW + ROW / 2
    legendOut.append(
      "<g class=\"pele-legend-item\" data-id=\"${escText(entries[index].first)}\">" +
        "<rect x=\"${num(lx)}\" y=\"${num(y - SWATCH / 2.0)}\" width=\"$SWATCH\" height=\"$SWATCH\" rx=\"2\" fill=\"${seriesColor(index)}\"/>" +
        labelSvg(label, lx + SWATCH + 8 + label.width / 2, y, "") +
        "</g>",
    )
  }

  val totalWidth = ceil(width + 2 * pad)
  val totalHeight = ceil(height + titleHeight + 2 * pad)
  val smallText = jsStr(small)
  val content =
    labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g class=\"pele-slices\" transform=\"translate(${num(cx)},${num(cy)})\" stroke=\"var(--_bg)\" stroke-width=\"1\" stroke-linejoin=\"round\">$slices</g>" +
      (
        if (labels.isNotEmpty()) {
          "<g class=\"pele-slice-labels\" transform=\"translate(${num(cx)},${num(cy)})\" font-size=\"$smallText\" text-anchor=\"middle\" fill=\"var(--_bg)\">$labels</g>"
        } else {
          ""
        }
        ) +
      (
        if (outside.isNotEmpty()) {
          "<g class=\"pele-slice-labels\" transform=\"translate(${num(cx)},${num(cy)})\" font-size=\"$smallText\" text-anchor=\"middle\">$outside</g>"
        } else {
          ""
        }
        ) +
      (if (legendOut.isNotEmpty()) "<g class=\"pele-legend\" font-size=\"$smallText\">$legendOut</g>" else "")

  return Rendered(
    svg = svgDocument("pie", totalWidth, totalHeight, size, options, model, content),
    width = totalWidth,
    height = totalHeight,
    links = ArrayList(),
  )
}
