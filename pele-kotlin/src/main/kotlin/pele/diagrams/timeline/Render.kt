// Ported from Pele src/diagrams/timeline/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.timeline

import kotlin.math.ceil
import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.fitLabel
import pele.diagrams.common.plainLabel
import pele.diagrams.common.turnToFit
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsTrim
import pele.svg.ARROW
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.num
import pele.svg.rounded
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer

private const val PAD_X = 12.0
private const val PAD_Y = 8.0
private const val MIN_TEXT = 152.0
private const val MAX_TEXT = 216.0
private const val EVENT_TEXT_TD = 264.0
private const val GAP = 20.0
private const val AXIS = 20.0
private const val SPINE = 14.0
private const val EVENT_GAP = 8.0
private const val SECTION_GAP = 8.0
private const val TAIL = 12.0
private const val ON_COLOR = " fill=\"var(--_bg)\""
private const val LINE = " fill=\"none\" stroke=\"var(--_b)\""

private class Group(val section: Int, val periods: MutableList<Int>)

private fun box(x: Double, y: Double, w: Double, h: Double, attrs: String): String =
  "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(w)}\" height=\"${num(h)}\"${rounded(attrs)}/>"

fun renderTimeline(model: TimelineModel, config: Config, options: RenderOptions): Rendered {
  if (model.direction == "TD") return draw(model, config, options, true)
  return turnToFit(options) { down -> draw(model, config, options, down) }
}

private fun draw(
  model: TimelineModel,
  config: Config,
  options: RenderOptions,
  vertical: Boolean,
): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val multicolor = (config["timeline"] as? Map<*, *>)?.get("disableMulticolor") != true
  val periods = model.periods
  val hasSections = model.sections.size > 0

  // Mermaid draws nothing for periods that come before the first section; here they lead, in a
  // neutral color.
  val groups: MutableList<Group> =
    model.sections.mapIndexed { section, _ -> Group(section, ArrayList()) }.toMutableList()
  val leading = Group(-1, ArrayList())
  periods.forEachIndexed { i, period ->
    (groups.getOrNull(period.sectionIndex) ?: leading).periods.add(i)
  }
  if (leading.periods.size > 0) groups.add(0, leading)

  // Columns widen, up to a point, for a word that would not fit; past that the word is cut.
  // Running down, the two columns share the width the host has.
  val room =
    if (vertical && options.maxWidth != null && options.maxWidth > 0) options.maxWidth - 2 * pad
    else Double.POSITIVE_INFINITY
  val taken = AXIS + SPINE * 2 + 4 * PAD_X
  val periodText = minOf(MIN_TEXT, maxOf(80.0, jsRound((room - taken) * 0.38)))
  val eventText = minOf(EVENT_TEXT_TD, maxOf(110.0, room - taken - periodText))
  var textW = periodText
  val eventTextW = { if (vertical) eventText else textW }
  var periodLabels = periods.map { p -> plainLabel(p.text, measurer, size, textW) }
  var eventLabels = periods.map { p ->
    p.events.map { e -> plainLabel(e, measurer, small, eventTextW()) }
  }
  var widest = 0.0
  for (label in periodLabels) widest = maxOf(widest, label.width)
  if (!vertical)
    for (labels in eventLabels) for (label in labels) widest = maxOf(widest, label.width)
  var overflow = widest > textW
  if (vertical)
    for (labels in eventLabels) for (label in labels) overflow = overflow || label.width > eventText
  if (overflow) {
    textW =
      minOf(
        maxOf(ceil(widest), periodText),
        if (room == Double.POSITIVE_INFINITY) MAX_TEXT else periodText,
      )
    periodLabels = periods.map { p -> fitLabel(p.text, measurer, size, textW) }
    eventLabels = periods.map { p ->
      p.events.map { e -> fitLabel(e, measurer, small, eventTextW()) }
    }
  }

  val lineHeight = jsRound(size * 1.5)
  val colW = textW + 2 * PAD_X
  val eventW = eventTextW() + 2 * PAD_X
  val chipHeight = { label: Label -> maxOf(label.height, lineHeight) + 2 * PAD_Y }
  val eventHeight = { label: Label -> maxOf(label.height, jsRound(small * 1.5)) + 2 * PAD_Y }
  val colorOf = { group: Group, period: Int ->
    if (group.section >= 0) seriesColor(group.section)
    else if (hasSections) "var(--_m)" else seriesColor(if (multicolor) period else 0)
  }

  var hasEvents = false
  for (labels in eventLabels) if (labels.size > 0) hasEvents = true
  var slots = 0
  for (group in groups) slots += maxOf(group.periods.size, 1)

  val pitch = colW + GAP
  val drawn = periods.size > 0
  val bodyW =
    if (vertical) {
      colW + (if (hasEvents) AXIS + SPINE * 2 + eventW else if (drawn) AXIS + TAIL else 0.0)
    } else if (slots > 0) {
      slots * pitch - GAP + (if (drawn) TAIL + ARROW else 0.0)
    } else {
      0.0
    }
  val title = fitLabel(model.title, measurer, size, maxOf(bodyW, 320.0), Style.Bold)
  val titleH = if (title.height > 0) title.height + 16 else 0.0
  val width = maxOf(bodyW, title.width)
  val left = pad + (width - bodyW) / 2
  val top = pad + titleH

  val sectionLabel = { group: Group, w: Double ->
    fitLabel(model.sections[group.section], measurer, size, w - 2 * PAD_X, Style.Bold)
  }
  val header = { group: Group, label: Label, x: Double, y: Double, w: Double, h: Double ->
    box(x, y, w, h, " fill=\"${seriesColor(group.section)}\"") +
      labelSvg(label, x + w / 2, y + h / 2, " font-weight=\"var(--_hw)\"" + ON_COLOR)
  }
  val period = { i: Int, color: String, x: Double, y: Double, h: Double ->
    box(x, y, colW, h, " fill=\"$color\"") +
      labelSvg(periodLabels[i], x + colW / 2, y + h / 2, ON_COLOR)
  }
  val event = { label: Label, color: String, x: Double, y: Double, h: Double ->
    "<g class=\"pele-node pele-event\">" +
      box(x, y, eventW, h, " fill=\"$color\" fill-opacity=\"0.16\"") +
      labelSvg(label, x + eventW / 2, y + h / 2, " font-size=\"${jsStr(small)}\"") +
      "</g>"
  }
  val dot = { x: Double, y: Double, color: String ->
    "<circle class=\"pele-dot\" cx=\"${num(x)}\" cy=\"${num(y)}\" r=\"4\" fill=\"$color\" stroke=\"var(--_bg)\" stroke-width=\"2\"/>"
  }
  val open = { group: Group ->
    if (group.section >= 0)
      "<g class=\"pele-cluster pele-section\" data-id=\"${escText(model.sections[group.section].jsTrim())}\">"
    else ""
  }
  val openPeriod = { i: Int ->
    "<g class=\"pele-node pele-period\" data-id=\"${escText(periods[i].text.jsTrim())}\">"
  }

  var body = ""
  var height: Double

  if (vertical) {
    val axisX = left + colW + AXIS
    val spineX = axisX + SPINE
    val eventsX = spineX + SPINE
    var y = top
    for (group in groups) {
      body += open(group)
      if (group.section >= 0) {
        val label = sectionLabel(group, bodyW)
        val h = chipHeight(label)
        body += header(group, label, left, y, bodyW, h)
        y += h + SECTION_GAP + 4
      }
      for (i in group.periods) {
        val color = colorOf(group, i)
        val h = chipHeight(periodLabels[i])
        // A period and its first event share one center line, so the line between them runs
        // straight.
        val first = if (eventLabels[i].size > 0) eventHeight(eventLabels[i][0]) else h
        val cy = y + maxOf(h, first) / 2
        var d = "M${num(left + colW)},${num(cy)}H${num(axisX)}"
        var cards = ""
        var ey = cy - first / 2
        var last = cy
        for (label in eventLabels[i]) {
          val eh = eventHeight(label)
          last = ey + eh / 2
          d += "M${num(spineX)},${num(last)}H${num(eventsX)}"
          cards += event(label, color, eventsX, ey, eh)
          ey += eh + EVENT_GAP
        }
        if (cards.isNotEmpty()) d += "M${num(axisX)},${num(cy)}H${num(spineX)}V${num(last)}"
        body +=
          openPeriod(i) +
            period(i, color, left, cy - h / 2, h) +
            "<path class=\"pele-edge\" d=\"$d\"$LINE/>" +
            dot(axisX, cy, color) +
            cards +
            "</g>"
        y = maxOf(cy + h / 2, ey - EVENT_GAP) + GAP
      }
      if (group.periods.size == 0) y += GAP - SECTION_GAP - 4
      if (group.section >= 0) body += "</g>"
    }
    if (groups.size > 0) y -= GAP
    if (drawn) {
      val tip = y + TAIL + ARROW
      body =
        "<path class=\"pele-axis\" d=\"M${num(axisX)},${num(top)}V${num(tip - ARROW + 1)}\" fill=\"none\" stroke=\"var(--_l)\"/>" +
          marker("arrow_point", axisX, tip, 0.0, 1.0, "var(--_l)") +
          body
      y = tip
    }
    height = y + pad
  } else {
    var headerH = 0.0
    val sectionLabels = groups.map { group ->
      if (group.section < 0) {
        null
      } else {
        val label = sectionLabel(group, maxOf(group.periods.size, 1) * pitch - GAP)
        headerH = maxOf(headerH, chipHeight(label))
        label
      }
    }
    var chipH = 0.0
    for (label in periodLabels) chipH = maxOf(chipH, chipHeight(label))
    val periodsY = top + (if (hasSections) headerH + SECTION_GAP else 0.0)
    val axisY = periodsY + chipH + (if (chipH > 0) AXIS else 0.0)
    val eventsY = axisY + AXIS
    var bottom = if (drawn) axisY + TAIL else top + headerH
    var slot = 0
    groups.forEachIndexed { g, group ->
      val span = maxOf(group.periods.size, 1)
      val gx = left + slot * pitch
      body += open(group)
      val label = sectionLabels[g]
      if (label != null) body += header(group, label, gx, top, span * pitch - GAP, headerH)
      group.periods.forEachIndexed { k, i ->
        val color = colorOf(group, i)
        val x = gx + k * pitch
        val cx = x + colW / 2
        var d =
          "M${num(cx)},${num(periodsY + chipH)}V${num(if (eventLabels[i].size > 0) eventsY else axisY)}"
        var cards = ""
        var ey = eventsY
        for (eventLabel in eventLabels[i]) {
          val eh = eventHeight(eventLabel)
          if (ey > eventsY) d += "M${num(cx)},${num(ey - EVENT_GAP)}V${num(ey)}"
          cards += event(eventLabel, color, x, ey, eh)
          ey += eh + EVENT_GAP
        }
        bottom = maxOf(bottom, ey - EVENT_GAP)
        body +=
          openPeriod(i) +
            period(i, color, x, periodsY, chipH) +
            "<path class=\"pele-edge\" d=\"$d\"$LINE/>" +
            dot(cx, axisY, color) +
            cards +
            "</g>"
      }
      if (group.section >= 0) body += "</g>"
      slot += span
    }
    if (drawn) {
      val tip = left + bodyW
      body =
        "<path class=\"pele-axis\" d=\"M${num(left)},${num(axisY)}H${num(tip - ARROW + 1)}\" fill=\"none\" stroke=\"var(--_l)\"/>" +
          marker("arrow_point", tip, axisY, 1.0, 0.0, "var(--_l)") +
          body
    }
    height = bottom + pad
  }

  val totalWidth = ceil(width + 2 * pad)
  val totalHeight = ceil(maxOf(height, pad * 2))
  val content =
    labelSvg(
      title,
      totalWidth / 2,
      pad + title.height / 2,
      " class=\"pele-title\" font-weight=\"var(--_tw)\"",
    ) + body
  return Rendered(
    svgDocument("timeline", totalWidth, totalHeight, size, options, model, content),
    totalWidth,
    totalHeight,
    ArrayList(),
  )
}
