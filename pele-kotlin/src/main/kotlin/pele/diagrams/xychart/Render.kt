// Ported from Pele src/diagrams/xychart/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.xychart

import pele.Config
import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.capWidth
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val TICK = 4.0
private const val SWATCH = 12.0
private const val ROW = 26.0
private const val LEGEND_GAP = 24.0
private const val LIMIT = 1e300

private class Mark(val at: Double, val label: Label)

private class LegendEntry(val index: Int, val label: Label)

private val NO_RECORD: Map<*, *> = emptyMap<String, Any?>()

private fun record(value: Any?): Map<*, *> = if (value is Map<*, *>) value else NO_RECORD

private fun on(config: Map<*, *>, key: String, fallback: Boolean = true): Boolean {
  val value = config[key]
  return if (value is Boolean) value else fallback
}

private fun dimension(value: Any?, fallback: Double): Double =
  if (value is Double && value > 0) jsRound(minOf(maxOf(value, 100.0), 4000.0)) else fallback

private fun finite(value: Double?): Double? = if (value != null && value.isFinite()) minOf(maxOf(value, -LIMIT), LIMIT) else null

private fun widest(marks: List<Mark>): Double {
  var width = 0.0
  for (mark in marks) width = maxOf(width, mark.label.width)
  return width
}

// Writes a label with its left edge, center or right edge at x. A plain one-line label keeps
// its place when the text is wider or narrower than measured.
private fun aligned(label: Label, x: Double, y: Double, anchor: String): String {
  val line = if (label.lines.size == 1) label.lines[0] else null
  if (line != null && line.size == 1 && line[0].icon == null && line[0].style == 0) {
    return "<text x=\"${num(x)}\" y=\"${num(y + label.size * 0.35)}\" text-anchor=\"$anchor\">${esc(line[0].text)}</text>"
  }
  return labelSvg(label, x + (if (anchor == "start") label.width / 2 else if (anchor == "end") -label.width / 2 else 0.0), y, "")
}

fun renderXyChart(model: XyChartModel, config: Config, options: RenderOptions): Rendered {
  if (model.plots.size == 0) {
    throw PeleError("No Plot to render, please provide a plot with some data", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "xychart"))
  }
  val chart = record(config["xyChart"])
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val tiny = jsRound(size * 0.75)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val natural = dimension(chart["width"], 700.0)
  val width = capWidth(options, natural, 240.0)
  // A narrower chart is not as much shorter, so that the plot keeps room for its marks.
  val height = jsRound(dimension(chart["height"], 500.0) * maxOf(0.6, width / natural))
  val horizontal = (model.orientation ?: chart["chartOrientation"]) == "horizontal"
  // The value of a bar is always written past its end, which is what showDataLabelOutsideBar asks
  // for in Mermaid. Inside, whether it fits changes from bar to bar and with the width of the chart.
  val showValues = on(chart, "showDataLabel", false)
  val x = model.xAxis
  val plots = model.plots
  val text = { raw: String?, fontSize: Double, max: Double -> layoutLabel(raw, false, measurer, fontSize, max) }
  val lineHeight = text("0", small, 4000.0).height
  val tinyHeight = text("0", tiny, 4000.0).height

  val title = layoutLabel(if (on(chart, "showTitle")) model.title else null, false, measurer, size, width - 2 * pad, Style.Bold)
  val titleSpace = if (title.height > 0) title.height + 12 else 0.0

  val legend = ArrayList<LegendEntry>()
  var legendWidth = 0.0
  if (on(chart, "showLegend")) {
    plots.forEachIndexed { index, plot ->
      if (plot.title == "") return@forEachIndexed
      val label = text(plot.title, small, width * 0.3)
      legend.add(LegendEntry(index, label))
      legendWidth = maxOf(legendWidth, label.width + SWATCH + 8)
    }
  }
  val legendSpace = if (legend.size > 0) legendWidth + LEGEND_GAP else 0.0

  // The value range: as written, or taken from the data, where bars stand on zero and the
  // range grows to whole steps.
  val bars = plots.filter { plot -> plot.type == "bar" }.size
  var lo = finite(model.yAxis.min) ?: Double.POSITIVE_INFINITY
  var hi = finite(model.yAxis.max) ?: Double.NEGATIVE_INFINITY
  val exact = model.hasSetYAxis && lo.isFinite() && hi.isFinite() && lo != hi
  if (!exact) {
    lo = Double.POSITIVE_INFINITY
    hi = Double.NEGATIVE_INFINITY
    for (plot in plots) {
      for ((_, raw) in plot.data) {
        val value = finite(raw) ?: continue
        if (value < lo) lo = value
        if (value > hi) hi = value
      }
    }
    if (lo > hi) {
      hi = 0.0
      lo = hi
    }
    if (bars > 0) {
      lo = minOf(lo, 0.0)
      hi = maxOf(hi, 0.0)
    }
    if (lo == hi) {
      if (lo == 0.0) {
        hi = 1.0
      } else {
        lo -= abs(lo) / 2
        hi += abs(hi) / 2
      }
    }
  }

  val valueMarks = { length: Double, spacing: Double ->
    val step = tickStep(abs(hi - lo), maxOf(2.0, minOf(10.0, floor(length / spacing))))
    if (!exact) {
      lo = tickValue(step, floor(tickIndex(step, lo) + 1e-9))
      hi = tickValue(step, ceil(tickIndex(step, hi) - 1e-9))
    }
    ticksBetween(minOf(lo, hi), maxOf(lo, hi), step).map { value ->
      Mark((value - lo) / (hi - lo), text(jsStr(value), small, 4000.0))
    }
  }

  // Places along the category axis are fractions of its length. Categories and bars sit in the
  // middle of equal slots; the points of a line on a numeric axis run from end to end.
  var count = 1.0
  if (x is BandAxis) {
    count = maxOf(x.categories.size.toDouble(), 1.0)
  } else {
    for (plot in plots) count = maxOf(count, plot.data.size.toDouble())
  }
  val slot = 1 / count
  val inset = if (x is BandAxis || bars > 0) slot / 2 else 0.0
  val x0 = finite(if (x is LinearAxis) x.min else null)
  val x1 = finite(if (x is LinearAxis) x.max else null)
  val ranged = x0 != null && x1 != null && x0 != x1
  val place = { i: Int, n: Int ->
    if (x is BandAxis) (i + 0.5) * slot else inset + (if (n > 1) i.toDouble() / (n - 1) else if (ranged) 0.0 else 0.5) * (1 - 2 * inset)
  }

  val categoryConf = record(chart["xAxis"])
  val valueConf = record(chart["yAxis"])
  val leftConf = if (horizontal) categoryConf else valueConf
  val bottomConf = if (horizontal) valueConf else categoryConf
  val leftTitle = text(if (on(leftConf, "showTitle")) (if (horizontal) x.title else model.yAxis.title) else null, small, height * 0.8)
  val bottomTitle = text(if (on(bottomConf, "showTitle")) (if (horizontal) model.yAxis.title else x.title) else null, small, width * 0.8)
  val leftTick = if (on(leftConf, "showTick")) TICK else 0.0
  val bottomTick = if (on(bottomConf, "showTick")) TICK else 0.0
  val leftLabels = on(leftConf, "showLabel")
  val bottomLabels = on(bottomConf, "showLabel")

  val wrapAt = if (horizontal) width * 0.3 else maxOf(48.0, (width - 2 * pad - 64 - legendSpace) * slot - 8)
  val categories: List<Label> =
    if (x is BandAxis && on(categoryConf, "showLabel")) x.categories.map { category -> text(category, small, wrapAt) } else emptyList()
  var categoryWidth = 0.0
  var categoryHeight = 0.0
  for (label in categories) {
    categoryWidth = maxOf(categoryWidth, label.width)
    categoryHeight = maxOf(categoryHeight, label.height)
  }

  // The labelled places of the category axis. Labels that would run into each other are thinned out.
  val categoryMarks = { length: Double ->
    val marks = ArrayList<Mark>()
    if (x is BandAxis) {
      val need = if (categories.size == 0) 8.0 else if (horizontal) categoryHeight else categoryWidth + 12
      val every = maxOf(1.0, ceil(need / (length * slot)))
      var i = 0.0
      while (i < x.categories.size) {
        marks.add(Mark((i + 0.5) * slot, categories.getOrNull(i.toInt()) ?: text(null, small, 0.0)))
        i += every
      }
    } else if (ranged) {
      val step = tickStep(abs(x1 - x0), maxOf(2.0, minOf(10.0, floor(length / (if (horizontal) 32 else 80)))))
      for (value in ticksBetween(minOf(x0, x1), maxOf(x0, x1), step)) {
        marks.add(Mark(inset + ((value - x0) / (x1 - x0)) * (1 - 2 * inset), text(jsStr(value), small, 4000.0)))
      }
    } else if (x0 != null) {
      marks.add(Mark(0.5, text(jsStr(x0), small, 4000.0)))
    }
    marks
  }

  // Room past the end of the value axis for what is written beside a point or a bar.
  var pointLabelWidth = 0.0
  var valueWidth = 0.0
  for (plot in plots) {
    for (label in plot.pointLabels ?: emptyList()) pointLabelWidth = maxOf(pointLabelWidth, measurer.width(label, tiny, 0))
    if (showValues && horizontal && plot.type == "bar") {
      for ((_, value) in plot.data) {
        val shown = finite(value)
        valueWidth = maxOf(valueWidth, measurer.width(if (shown != null) jsStr(shown) else "", tiny, 0))
      }
    }
  }

  val bottomLabelHeight = if (!bottomLabels) 0.0 else if (horizontal || x !is BandAxis) lineHeight else categoryHeight
  val bottomSpace =
    bottomTick + (if (bottomLabelHeight > 0) bottomLabelHeight + 4 else 0.0) + (if (bottomTitle.height > 0) bottomTitle.height + 4 else 0.0)
  val headroom = if (horizontal) 0.0 else if (pointLabelWidth > 0 || showValues) tinyHeight + 4 else if (leftLabels) lineHeight / 2 else 0.0
  val top = pad + titleSpace + headroom
  val plotHeight = maxOf(1.0, height - pad - bottomSpace - top)
  val bottom = top + plotHeight

  val leftMarks = if (horizontal) categoryMarks(plotHeight) else valueMarks(plotHeight, 56.0)
  val leftLabelWidth = if (leftLabels) widest(leftMarks) else 0.0
  val left =
    pad + (if (leftTitle.height > 0) leftTitle.height + 6 else 0.0) + (if (leftLabelWidth > 0) leftLabelWidth + 6 else 0.0) + leftTick
  val beside = maxOf(if (pointLabelWidth > 0) pointLabelWidth + 12 else 0.0, if (valueWidth > 0) valueWidth + 8 else 0.0)
  val reach = if (horizontal) minOf(width * 0.25, beside) else 0.0
  var plotWidth = maxOf(1.0, width - pad - left - legendSpace - reach)
  val bottomMarks = if (horizontal) valueMarks(plotWidth, 80.0) else categoryMarks(plotWidth)
  if (bottomLabels && legendSpace + reach == 0.0) {
    var over = 0.0
    for (mark in bottomMarks) over = maxOf(over, mark.at * plotWidth + mark.label.width / 2 - plotWidth)
    plotWidth = maxOf(1.0, plotWidth - over)
  }
  val right = left + plotWidth

  val px = { c: Double, v: Double -> if (horizontal) left + v * plotWidth else left + c * plotWidth }
  val py = { c: Double, v: Double -> if (horizontal) top + c * plotHeight else bottom - v * plotHeight }
  val scale = { value: Double -> (value - lo) / (hi - lo) }
  val clamp = { v: Double -> minOf(maxOf(v, 0.0), 1.0) }

  // A line across the plot at a place on the value axis.
  val rule = { v: Double ->
    if (horizontal) "M${num(px(0.0, v))},${num(top)}V${num(bottom)}" else "M${num(left)},${num(py(0.0, v))}H${num(right)}"
  }

  var grid = ""
  for (mark in if (horizontal) bottomMarks else leftMarks) grid += rule(mark.at)

  var axis = ""
  var labels = ""
  if (on(leftConf, "showAxisLine")) axis += "M${num(left)},${num(top)}V${num(bottom)}"
  if (on(bottomConf, "showAxisLine")) axis += "M${num(left)},${num(bottom)}H${num(right)}"
  for (mark in leftMarks) {
    val y = if (horizontal) top + mark.at * plotHeight else bottom - mark.at * plotHeight
    if (leftTick != 0.0) axis += "M${num(left)},${num(y)}h${jsStr(-leftTick)}"
    if (leftLabels) labels += aligned(mark.label, left - leftTick - 6, y, "end")
  }
  for (mark in bottomMarks) {
    val at = left + mark.at * plotWidth
    if (bottomTick != 0.0) axis += "M${num(at)},${num(bottom)}v${jsStr(bottomTick)}"
    if (bottomLabels) labels += aligned(mark.label, at, bottom + bottomTick + 4 + mark.label.height / 2, "middle")
  }

  val span = (if (horizontal) plotHeight else plotWidth) * slot
  val group = span * 0.7
  val thickness = group / maxOf(bars, 1)
  val gap = if (bars > 1) minOf(2.0, thickness * 0.2) else 0.0
  val base = clamp(scale(0.0))
  // Bars that go both ways get a line to stand on.
  if (bars > 0 && base > 0 && base < 1) axis += rule(base)
  // Lines are drawn over the bars wherever they were declared, as a bar would hide part of one
  // under it. A series keeps the color of its place in the source.
  var series = ""
  var lines = ""
  var outside = ""
  var pointLabels = ""
  var bar = 0
  plots.forEachIndexed { index, plot ->
    val color = seriesColor(index)
    val id = if (plot.title.isNotEmpty()) " data-id=\"${escText(plot.title)}\"" else ""
    // A series declared before the categories can hold more values than there are categories.
    val n = if (x is BandAxis) minOf(plot.data.size.toDouble(), count).toInt() else plot.data.size
    if (plot.type == "bar") {
      val offset = (bar++ - (bars - 1) / 2.0) * thickness - (thickness - gap) / 2
      var rects = ""
      for (i in 0 until n) {
        val value = finite(plot.data[i].second) ?: continue
        val c = place(i, n)
        val v = clamp(scale(value))
        val from = minOf(base, v)
        val to = maxOf(base, v)
        val bx = if (horizontal) px(c, from) else px(c, 0.0) + offset
        val by = if (horizontal) py(c, 0.0) + offset else py(c, to)
        val bw = if (horizontal) (to - from) * plotWidth else thickness - gap
        val bh = if (horizontal) thickness - gap else (to - from) * plotHeight
        rects += "<rect x=\"${num(bx)}\" y=\"${num(by)}\" width=\"${num(bw)}\" height=\"${num(bh)}\"/>"
        if (!showValues) continue
        val label = jsStr(value)
        val w = measurer.width(label, tiny, 0)
        // Towards the end of the bar: 1 when it runs up or right from the baseline, -1 the other way.
        val out = if (v >= base) 1 else -1
        var tx = bx + bw / 2
        var ty = by + bh / 2
        var anchor = "middle"
        if (horizontal) {
          if (bh < tiny) continue
          tx = (if (out > 0) bx + bw else bx) + out * 6
          anchor = if (out > 0) "start" else "end"
        } else {
          if (w + 2 > span / bars) continue
          ty = (if (out > 0) by else by + bh) - out * (tinyHeight / 2 + 2)
        }
        outside += "<text x=\"${num(tx)}\" y=\"${num(ty + tiny * 0.35)}\" text-anchor=\"$anchor\">$label</text>"
      }
      series += "<g class=\"pele-series pele-bars\"$id fill=\"$color\">$rects</g>"
      return@forEachIndexed
    }

    // A line is cut where it leaves the value range, so it stays inside the plot.
    var d = ""
    var dots = ""
    var pen = false
    var pc = 0.0
    var pv = Double.NaN
    for (i in 0 until n) {
      val value = finite(plot.data[i].second)
      if (value == null) {
        pv = Double.NaN
        continue
      }
      val c = place(i, n)
      val v = scale(value)
      val visible = v >= 0 && v <= 1
      if (pv.isNaN()) {
        if (visible) d += "M${num(px(c, v))},${num(py(c, v))}"
        pen = visible
      } else {
        val dv = v - pv
        var t0 = 0.0
        var t1 = 1.0
        if (dv != 0.0) {
          val a = -pv / dv
          val b = (1 - pv) / dv
          t0 = maxOf(t0, minOf(a, b))
          t1 = minOf(t1, maxOf(a, b))
        } else if (!visible) {
          t0 = 2.0
        }
        if (t0 <= t1) {
          val c0 = pc + (c - pc) * t0
          val v0 = pv + dv * t0
          val c1 = pc + (c - pc) * t1
          val v1 = pv + dv * t1
          if (!pen || t0 > 0) d += "M${num(px(c0, v0))},${num(py(c0, v0))}"
          d += "L${num(px(c1, v1))},${num(py(c1, v1))}"
        }
        pen = visible
      }
      pc = c
      pv = v
      if (!visible) continue
      val cx = px(c, v)
      val cy = py(c, v)
      if (span >= 12) dots += "<circle cx=\"${num(cx)}\" cy=\"${num(cy)}\" r=\"3\"/>"
      val label = text(plot.pointLabels?.getOrNull(i), tiny, 4000.0)
      if (label.height > 0) {
        val half = label.width / 2
        pointLabels += if (horizontal) {
          aligned(label, cx + 10, cy, "start")
        } else {
          aligned(label, maxOf(minOf(cx, right - half), left + half), cy - 6 - label.height / 2, "middle")
        }
      }
    }
    lines +=
      "<g class=\"pele-series pele-line\"$id fill=\"$color\">" +
      (if (d.isNotEmpty()) "<path d=\"$d\" fill=\"none\" stroke=\"$color\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>" else "") +
      dots +
      "</g>"
  }

  var titles = ""
  if (leftTitle.height > 0) {
    titles += "<g transform=\"translate(${num(pad + leftTitle.height / 2)},${num(top + plotHeight / 2)}) rotate(-90)\">${labelSvg(leftTitle, 0.0, 0.0, "")}</g>"
  }
  titles += labelSvg(bottomTitle, left + plotWidth / 2, height - pad - bottomTitle.height / 2, "")

  var legendOut = ""
  var legendHeight = 0.0
  for (item in legend) legendHeight += maxOf(ROW, item.label.height + 6)
  var ly = top + maxOf(0.0, (plotHeight - legendHeight) / 2)
  val lx = right + reach + LEGEND_GAP
  for (entry in legend) {
    val index = entry.index
    val label = entry.label
    val row = maxOf(ROW, label.height + 6)
    if (ly + row > height) break
    val y = ly + row / 2
    val color = seriesColor(index)
    legendOut +=
      "<g class=\"pele-legend-item\" data-id=\"${escText(plots[index].title)}\">" +
      (if (plots[index].type == "bar") {
        "<rect x=\"${num(lx)}\" y=\"${num(y - SWATCH / 2)}\" width=\"${jsStr(SWATCH)}\" height=\"${jsStr(SWATCH)}\" rx=\"2\" fill=\"$color\"/>"
      } else {
        "<path d=\"M${num(lx)},${num(y)}h${jsStr(SWATCH)}\" stroke=\"$color\" stroke-width=\"2\" stroke-linecap=\"round\"/>"
      }) +
      labelSvg(label, lx + SWATCH + 8 + label.width / 2, y, "") +
      "</g>"
    ly += row
  }

  val content =
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (grid.isNotEmpty()) "<path class=\"pele-grid\" d=\"$grid\" stroke=\"var(--_a)\"/>" else "") +
      "<g class=\"pele-plot\">$series$lines</g>" +
      (if (axis.isNotEmpty()) "<path class=\"pele-axis\" d=\"$axis\" fill=\"none\" stroke=\"var(--_b)\"/>" else "") +
      (if (labels.isNotEmpty()) "<g class=\"pele-axis-labels\" font-size=\"${jsStr(small)}\" fill=\"var(--_m)\">$labels</g>" else "") +
      (if (titles.isNotEmpty()) "<g class=\"pele-axis-titles\" font-size=\"${jsStr(small)}\">$titles</g>" else "") +
      (if (outside.isNotEmpty()) "<g class=\"pele-data-labels\" font-size=\"${jsStr(tiny)}\">$outside</g>" else "") +
      (if (pointLabels.isNotEmpty()) "<g class=\"pele-point-labels\" font-size=\"${jsStr(tiny)}\">$pointLabels</g>" else "") +
      (if (legendOut.isNotEmpty()) "<g class=\"pele-legend\" font-size=\"${jsStr(small)}\">$legendOut</g>" else "")

  return Rendered(svgDocument("xychart", width, height, size, options, model, content), width, height, ArrayList())
}
