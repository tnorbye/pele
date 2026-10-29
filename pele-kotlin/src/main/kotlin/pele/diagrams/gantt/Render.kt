// Ported from Pele src/diagrams/gantt/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

import pele.Config
import pele.LinkInfo
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.capWidth
import pele.js.JsRegExp
import pele.js.jsRound
import pele.js.jsReplace
import pele.js.jsSplit
import pele.js.jsStr
import pele.svg.CRITICAL
import pele.svg.ROUNDED
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.resolveStyle
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import pele.util.linked
import kotlin.math.ceil

private const val WIDTH = 800.0
private const val MIN_PLOT = 240.0
private const val LABEL_GAP = 8.0
private val RE_BREAK = JsRegExp("""<br\s*\/?>""", "gi")

private fun setting(config: Config, key: String, min: Double, max: Double): Double? {
  val value = (config["gantt"] as? Map<*, *>)?.get(key)
  return if (value is Double && value.isFinite()) minOf(max, maxOf(min, value)) else null
}

private fun text(config: Config, key: String): String? {
  val value = (config["gantt"] as? Map<*, *>)?.get(key)
  return value as? String
}

private fun flag(config: Config, key: String): Boolean = (config["gantt"] as? Map<*, *>)?.get(key) == true

// A task label sits on one row, so only its first line is kept.
private fun oneLine(raw: String, measurer: TextMeasurer, size: Double): Label {
  val label = layoutLabel(raw.jsReplace(RE_BREAK, " "), false, measurer, size, 4000.0)
  if (label.lines.size <= 1) return label
  return label.copy(lines = listOf(label.lines[0]), widths = listOf(label.widths[0]), width = label.widths[0], height = label.lineHeight)
}

// Whether scheduling gave the task a start and an end. One that waits on a task that never resolves has neither.
private fun scheduled(task: GanttTask): Boolean {
  val start = task.startTime?.getTime() ?: Double.NaN
  val end = task.endTime?.getTime() ?: Double.NaN
  return start.isFinite() && end.isFinite()
}

// Mermaid's compact mode: within a section, a task goes on the first row that is free when it starts.
private fun packRows(tasks: List<GanttTask>, offset: Int, rowOf: MutableMap<GanttTask, Int>): Int {
  val sorted = tasks.filter { scheduled(it) }.sortedWith { a, b ->
    val d = a.startTime!!.getTime() - b.startTime!!.getTime()
    if (d != 0.0) (if (d < 0) -1 else 1) else a.order.compareTo(b.order)
  }
  val free = ArrayList<Double>()
  for (task in sorted) {
    val start = task.startTime!!.getTime()
    var row = 0
    while (row < free.size && start < free[row]) row++
    if (row == free.size) free.add(task.endTime!!.getTime()) else free[row] = task.endTime!!.getTime()
    rowOf[task] = offset + row
  }
  var rows = free.size
  for (task in tasks) if (!rowOf.containsKey(task)) rowOf[task] = offset + rows++
  return maxOf(rows, 1)
}

private class Bar(
  val task: GanttTask,
  val label: Label,
  val row: Int,
  val x0: Double,
  val x1: Double,
  val labelX: Double,
  val inside: Boolean,
)

fun renderGantt(model: GanttDb, config: Config, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val now = clock(options.now)

  val labelSize = setting(config, "fontSize", 4.0, 72.0) ?: jsRound(size * 0.8125)
  val sectionSize = setting(config, "sectionFontSize", 4.0, 72.0) ?: labelSize
  val tickSize = jsRound(size * 0.75)
  val barHeight = setting(config, "barHeight", 4.0, 400.0) ?: jsRound(labelSize * 1.85)
  val barGap = setting(config, "barGap", 0.0, 400.0) ?: jsRound(barHeight / 4)
  val rowHeight = barHeight + barGap
  val sectionStyles = jsRound(setting(config, "numberSectionStyles", 1.0, 1000.0) ?: 4.0)
  val compact = model.displayMode == "compact"
  val topAxis = model.topAxis || flag(config, "topAxis")

  val tasks = model.getTasks(now)
  val rowTasks = tasks.filter { !it.vert }
  val verts = tasks.filter { it.vert && scheduled(it) }

  val categories = LinkedHashMap<String, Int>()
  for (task in rowTasks) if (!categories.containsKey(task.section)) categories[task.section] = categories.size

  val rowOf = LinkedHashMap<GanttTask, Int>()
  val rowSection = ArrayList<String>()
  if (compact) {
    val groups = LinkedHashMap<String, MutableList<GanttTask>>()
    for (task in rowTasks) {
      val group = groups[task.section]
      if (group != null) group.add(task) else groups[task.section] = mutableListOf(task)
    }
    for ((section, group) in groups) {
      val rows = packRows(group, rowSection.size, rowOf)
      for (i in 0 until rows) rowSection.add(section)
    }
  } else {
    for (task in rowTasks) {
      rowOf[task] = rowSection.size
      rowSection.add(task.section)
    }
  }
  val rowCount = rowSection.size

  var start = Double.POSITIVE_INFINITY
  var stop = Double.NEGATIVE_INFINITY
  for (task in tasks) {
    if (!scheduled(task)) continue
    start = minOf(start, task.startTime!!.getTime())
    stop = maxOf(stop, task.endTime!!.getTime())
  }
  val hasPlot = start <= stop

  val title = layoutLabel(model.title, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 16 else 0.0

  if (!hasPlot) {
    val width = ceil(maxOf(title.width, 1.0) + 2 * pad)
    val height = ceil(maxOf(title.height, 1.0) + 2 * pad)
    val content = labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"")
    return Rendered(svgDocument("gantt", width, height, size, options, model, content), width, height, ArrayList())
  }

  val sectionLabels = LinkedHashMap<String, Label>()
  var sectionWidth = 0.0
  for (section in categories.keys) {
    val label = layoutLabel(section, false, measurer, sectionSize, 180.0)
    sectionLabels[section] = label
    sectionWidth = maxOf(sectionWidth, label.width)
  }

  val axisFormat =
    if (model.axisFormat.isNotEmpty()) model.axisFormat else if (model.dateFormat == "D") "%d" else (text(config, "axisFormat") ?: "%Y-%m-%d")
  val tickLabel = { t: Double -> timeFormat(axisFormat, JsDate(t)) }
  val sampleWidth = maxOf(measurer.width(tickLabel(start), tickSize, 0), measurer.width(tickLabel(stop), tickSize, 0))

  val left = pad + (setting(config, "leftPadding", 0.0, 2000.0) ?: maxOf(if (sectionWidth > 0) sectionWidth + 24 else 0.0, sampleWidth / 2))
  val rightPadding = setting(config, "rightPadding", 0.0, 2000.0) ?: maxOf(16.0, sampleWidth / 2)
  val target = capWidth(options, setting(config, "useWidth", 100.0, 20000.0) ?: WIDTH, 280.0)
  val plotWidth = maxOf(MIN_PLOT, target - left - rightPadding - pad)
  val right = left + plotWidth
  val scale = if (stop > start) plotWidth / (stop - start) else 0.0
  val x = { t: Double -> if (scale == 0.0) left + plotWidth / 2 else left + (t - start) * scale }

  val tickHeight = jsRound(tickSize * 1.75)
  val topPadding = setting(config, "topPadding", 0.0, 2000.0)
  val gridStart = setting(config, "gridLineStartPadding", 0.0, 2000.0)
  val headHeight = pad + titleHeight + (if (topAxis) tickHeight else 0.0)
  val plotTop = maxOf(headHeight, topPadding ?: 0.0)
  // Mermaid starts its grid lines above the first row by the difference between these two settings.
  // Here that only uses room a larger `topPadding` leaves free.
  val gridLead =
    if (topPadding != null && gridStart != null) maxOf(0.0, minOf(topPadding - gridStart, plotTop - headHeight)) else 0.0
  val plotHeight = maxOf(rowCount, 1) * rowHeight
  val plotBottom = plotTop + plotHeight

  // Bars and where their labels go: inside when they fit, otherwise beside the bar on the side with room.
  val bars = ArrayList<Bar>()
  var extent = right + rightPadding
  for (task in rowTasks) {
    val label = oneLine(task.task, measurer, labelSize)
    val row = rowOf[task]!!
    if (!scheduled(task)) {
      val labelX = left + LABEL_GAP + label.width / 2
      extent = maxOf(extent, labelX + label.width / 2)
      bars.add(Bar(task, label, row, Double.NaN, Double.NaN, labelX, false))
      continue
    }
    var x0 = x(task.startTime!!.getTime())
    var x1 = maxOf(x((task.renderEndTime ?: task.endTime!!).getTime()), x0 + (if (task.milestone) 0 else 2))
    if (task.milestone) {
      val mid = x0 + (x(task.endTime!!.getTime()) - x0) / 2
      x0 = mid - barHeight / 2
      x1 = mid + barHeight / 2
    }
    val inside = !task.milestone && label.width + 2 * LABEL_GAP <= x1 - x0
    var labelX = (x0 + x1) / 2
    if (!inside) {
      val fitsRight = x1 + LABEL_GAP + label.width <= right + rightPadding
      val fitsLeft = x0 - LABEL_GAP - label.width >= left
      labelX = if (fitsRight || !fitsLeft) x1 + LABEL_GAP + label.width / 2 else x0 - LABEL_GAP - label.width / 2
      extent = maxOf(extent, labelX + label.width / 2)
    }
    bars.add(Bar(task, label, row, x0, x1, labelX, inside))
  }

  val interval = if (!model.tickInterval.isNullOrEmpty()) model.tickInterval!! else (text(config, "tickInterval")?.takeIf { it.isNotEmpty() } ?: "")
  val weekday = model.weekday ?: text(config, "weekday") ?: "sunday"
  val count = maxOf(2.0, minOf(10.0, Math.floor(plotWidth / (sampleWidth + 24))))
  val ticks = intervalTicks(interval, weekday, start, stop, ceil(plotWidth / 3)) ?: autoTicks(start, stop, count)

  val grid = StringBuilder()
  val axisBottom = StringBuilder()
  val axisTop = StringBuilder()
  var lastRight = Double.NEGATIVE_INFINITY
  var lastLabel = ""
  val bottomY = plotBottom + tickHeight / 2 + 2
  val topY = plotTop - gridLead - tickHeight / 2 - 2
  for (t in ticks) {
    val tx = x(t)
    grid.append("M${num(tx)},${num(plotTop - gridLead)}V${num(plotBottom)}")
    val label = tickLabel(t)
    val width = measurer.width(label, tickSize, 0)
    // A label is skipped when it would run into the one before it or only repeat it; its grid line stays.
    if (tx - width / 2 < lastRight + 8 || label == lastLabel) continue
    lastRight = tx + width / 2
    lastLabel = label
    extent = maxOf(extent, lastRight)
    val body = "text-anchor=\"middle\">${esc(label)}</text>"
    axisBottom.append("<text x=\"${num(tx)}\" y=\"${num(bottomY + tickSize * 0.35)}\" $body")
    if (topAxis) axisTop.append("<text x=\"${num(tx)}\" y=\"${num(topY + tickSize * 0.35)}\" $body")
  }

  // Vertical markers take no row. Their labels go under the axis, on as many lines as it takes to keep them apart.
  val vertLines = StringBuilder()
  val vertLabels = StringBuilder()
  val vertEnds = ArrayList<Double>()
  val vertTop = plotBottom + tickHeight + 4
  val vertLine = jsRound(labelSize * 1.5)
  for (task in verts.sortedWith { a, b ->
    val d = a.startTime!!.getTime() - b.startTime!!.getTime()
    if (d < 0) -1 else if (d > 0) 1 else 0
  }) {
    val vx = x(task.startTime!!.getTime())
    val label = oneLine(task.task, measurer, labelSize)
    val cx = maxOf(pad + label.width / 2, vx)
    var line = 0
    while (line < vertEnds.size && cx - label.width / 2 < vertEnds[line] + 8) line++
    if (line == vertEnds.size) vertEnds.add(cx + label.width / 2) else vertEnds[line] = cx + label.width / 2
    extent = maxOf(extent, vertEnds[line])
    vertLines.append("<path class=\"pele-vert\" data-id=\"${escText(task.id)}\" d=\"M${num(vx)},${num(plotTop - gridLead)}V${num(plotBottom)}\"/>")
    vertLabels.append(labelSvg(label, cx, vertTop + line * vertLine + vertLine / 2, " class=\"pele-vert-label\" data-id=\"${escText(task.id)}\""))
  }

  val width = ceil(maxOf(extent, pad + title.width) + pad)
  val height = ceil(plotBottom + tickHeight + (if (vertEnds.size > 0) 4 + vertEnds.size * vertLine else 0.0) + pad)

  // Section bands, one per run of rows that share a section, shaded on alternate sections.
  val bands = StringBuilder()
  val sectionOut = StringBuilder()
  var row = 0
  while (row < rowCount) {
    val section = rowSection[row]
    var end = row + 1
    while (end < rowCount && rowSection[end] == section) end++
    val index = categories[section]!! % sectionStyles
    val y = plotTop + row * rowHeight
    val h = (end - row) * rowHeight
    if (categories.size > 1 || section != "") {
      bands.append(
        "<rect class=\"pele-section pele-section-${jsStr(index)}\" x=\"0\" y=\"${num(y)}\" width=\"${jsStr(width)}\" height=\"${num(h)}\" fill=\"${
          if (index % 2 == 0.0) "var(--_s)" else "none"
        }\"/>",
      )
    }
    val label = sectionLabels[section]!!
    sectionOut.append(labelSvg(label, pad + 8 + label.width / 2, y + h / 2, " class=\"pele-section-label\" data-id=\"${escText(section)}\"", options.icons))
    row = end
  }

  // Excluded days, as Mermaid marks them: a day at a time from the first task's start, and not at all
  // when the chart spans more than five years.
  val excluded = StringBuilder()
  if ((model.excludes.size > 0 || model.includes.size > 0) && add(JsDate(start), 6.0, "y").getTime() > stop) {
    var from: JsDate? = null
    var to: JsDate? = null
    fun flush() {
      val f = from
      val t = to
      if (f == null || t == null) return
      val dayStart = JsDate(f)
      dayStart.setHours(0.0, 0.0, 0.0, 0.0)
      val dayEnd = JsDate(t)
      dayEnd.setHours(23.0, 59.0, 59.0, 999.0)
      val ex0 = maxOf(left, x(dayStart.getTime()))
      val ex1 = minOf(right, x(dayEnd.getTime()))
      if (ex1 > ex0) {
        excluded.append("<rect x=\"${num(ex0)}\" y=\"${num(plotTop)}\" width=\"${num(ex1 - ex0)}\" height=\"${num(plotHeight)}\"/>")
      }
      from = null
      to = null
    }
    var day = JsDate(start)
    while (day.getTime() <= stop) {
      if (model.isInvalidDate(day)) {
        if (from == null) from = day
        to = day
      } else {
        flush()
      }
      day = addDays(day, 1.0)
    }
    flush()
  }

  val links = ArrayList<LinkInfo>()
  val tasksOut = StringBuilder()
  for (bar in bars) {
    val task = bar.task
    val cy = plotTop + bar.row * rowHeight + rowHeight / 2
    val state = if (task.active) "active" else if (task.done) "done" else ""
    var classes = "pele-task"
    if (state != "") classes += " pele-$state"
    if (task.crit) classes += " pele-crit"
    if (task.milestone) classes += " pele-milestone"
    if (task.classes.contains("clickable")) classes += " pele-clickable"

    var shape = ""
    var textFill = ""
    if (bar.x0.isNaN()) {
      classes += " pele-unscheduled"
      textFill = " fill=\"var(--_m)\""
    } else {
      // A critical task takes the critical color in place of the accent. Done, it keeps the fill
      // of finished work and only its border is in that color.
      val color = if (task.crit) CRITICAL else "var(--_c)"
      val paint =
        if (state == "done") "fill=\"var(--_a)\"" else if (state == "active") "fill=\"$color\" fill-opacity=\"0.25\"" else "fill=\"$color\""
      val stroke = "stroke=\"${if (state == "done" && !task.crit) "var(--_b)" else color}\""
      if (task.milestone) {
        val cx = (bar.x0 + bar.x1) / 2
        val r = barHeight / 2
        shape = "<path d=\"M${num(cx)},${num(cy - r)}L${num(cx + r)},${num(cy)}L${num(cx)},${num(cy + r)}L${num(cx - r)},${num(cy)}Z\" $paint $stroke stroke-linejoin=\"round\"/>"
      } else {
        shape = "<rect x=\"${num(bar.x0)}\" y=\"${num(cy - barHeight / 2)}\" width=\"${num(bar.x1 - bar.x0)}\" height=\"${num(barHeight)}\" $paint $stroke$ROUNDED/>"
      }
      if (bar.inside) {
        textFill = if (state == "") " fill=\"var(--_bg)\"" else ""
      } else if (state == "done") {
        textFill = " fill=\"var(--_m)\""
      }
    }

    var body = shape + labelSvg(bar.label, bar.labelX, cy, " class=\"pele-label\"$textFill")
    val url = model.links[task.id]
    if (url != null) body = linked(body, url, task.id, links, options)
    tasksOut.append("<g class=\"$classes\" data-id=\"${escText(task.id)}\">$body</g>")
  }

  var today = ""
  if (model.todayMarker != "off" && now >= start && now <= stop && stop > start) {
    val tx = x(now)
    val top = plotTop - gridLead
    val style = if (model.todayMarker.isNotEmpty()) resolveStyle(model.todayMarker.jsSplit(",")).line else ""
    today =
      "<g class=\"pele-today\"><path d=\"M${num(tx)},${num(top)}V${num(plotBottom)}\" fill=\"none\" stroke=\"var(--_c)\" stroke-width=\"2\"$style/>" +
      (if (style.isNotEmpty()) "" else "<path d=\"M${num(tx - 4)},${num(top - 5)}H${num(tx + 4)}L${num(tx)},${num(top + 1)}Z\" fill=\"var(--_c)\"/>") +
      "</g>"
  }

  val axisLine = "M${num(left)},${num(plotBottom)}H${num(right)}" + (if (topAxis) "M${num(left)},${num(plotTop - gridLead)}H${num(right)}" else "")
  val content =
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (bands.isNotEmpty()) "<g class=\"pele-sections\">$bands</g>" else "") +
      (if (excluded.isNotEmpty()) "<g class=\"pele-excluded\" fill=\"var(--_a)\">$excluded</g>" else "") +
      "<g class=\"pele-grid\" fill=\"none\"><path d=\"$grid\" stroke=\"var(--_b)\" stroke-dasharray=\"1 3\"/><path d=\"$axisLine\" stroke=\"var(--_b)\"/></g>" +
      (if (vertLines.isNotEmpty()) "<g class=\"pele-verts\" fill=\"none\" stroke=\"var(--_l)\" stroke-width=\"1.5\" stroke-dasharray=\"4 3\">$vertLines</g>" else "") +
      today +
      "<g class=\"pele-tasks\" font-size=\"${num(labelSize)}\">$tasksOut</g>" +
      "<g class=\"pele-section-labels\" font-size=\"${num(sectionSize)}\">$sectionOut</g>" +
      "<g class=\"pele-axis\" font-size=\"${jsStr(tickSize)}\" fill=\"var(--_m)\">$axisBottom$axisTop</g>" +
      (if (vertLabels.isNotEmpty()) "<g class=\"pele-vert-labels\" font-size=\"${num(labelSize)}\">$vertLabels</g>" else "")

  return Rendered(svgDocument("gantt", width, height, size, options, model, content), width, height, links)
}
