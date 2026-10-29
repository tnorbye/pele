// Ported from Pele src/diagrams/kanban/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.kanban

import pele.Config
import pele.IconResolver
import pele.LinkInfo
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.iconSvg
import pele.diagrams.common.titleRoom
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsTrim
import pele.js.toCodePointStrings
import pele.svg.CRITICAL
import pele.svg.ROUNDED
import pele.svg.classNames
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.svgDocument
import pele.svg.textStyle
import pele.svg.titleLabel
import pele.text.Label
import pele.text.Span
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.defaultMeasurer
import pele.text.iconMarkup
import pele.text.labelLine
import pele.text.layoutLabel
import pele.text.lineMiddle
import pele.text.withHeights
import pele.util.linkUrl
import pele.util.relAttr
import pele.util.safeUrl
import kotlin.math.ceil
import kotlin.math.floor

private const val COLUMN_GAP = 12.0
private const val COLUMN_PAD = 8.0
private const val CARD_PAD = 10.0
private const val CARD_GAP = 8.0
private const val META_GAP = 4.0
private const val ICON_GAP = 6.0

// Mermaid names five priorities and gives Medium no color. Matching here ignores case. The high
// ones are marked in the critical color and the low ones in the muted one, the nearer to Medium
// the fainter. They are not series colors, as a theme may put those in any order.
private val PRIORITY = mapOf(
  "very high" to "stroke=\"$CRITICAL\"",
  "high" to "stroke=\"$CRITICAL\" opacity=\"0.5\"",
  "low" to "stroke=\"var(--_m)\" opacity=\"0.5\"",
  "very low" to "stroke=\"var(--_m)\"",
)

// Lays out text for a fixed width. A word too long for a line is broken between characters,
// because a card cannot grow sideways to hold it.
private fun fitLabel(
  raw: String?,
  markdown: Boolean,
  measurer: TextMeasurer,
  size: Double,
  max: Double,
  base: Int = 0,
): Label {
  val label = layoutLabel(raw, markdown, measurer, size, max, base)
  if (label.width <= max) return label
  val lines = ArrayList<MutableList<Span>>()
  val widths = ArrayList<Double>()
  var widest = 0.0
  var line = ArrayList<Span>() as MutableList<Span>
  var width = 0.0
  val commit = {
    lines.add(line)
    widths.add(width)
    widest = maxOf(widest, width)
    line = ArrayList()
    width = 0.0
  }
  for (i in label.lines.indices) {
    if (label.widths[i] <= max) {
      line = label.lines[i]
      width = label.widths[i]
      commit()
      continue
    }
    for (span in label.lines[i]) {
      if (span.icon != null || span.math != null) {
        if (width > 0 && width + span.width > max) commit()
        line.add(span)
        width += span.width
        continue
      }
      var text = ""
      var from = width
      for (ch in span.text.toCodePointStrings()) {
        val w = measurer.width(ch, size, span.style)
        if (width > 0 && width + w > max) {
          if (text != "") line.add(Span(text = text, style = span.style, width = width - from))
          commit()
          text = ""
          from = 0.0
        }
        text += ch
        width += w
      }
      if (text != "") line.add(Span(text = text, style = span.style, width = width - from))
    }
    commit()
  }
  val fitted = label.copy(lines = lines, widths = widths, width = widest, height = lines.size * label.lineHeight)
  return if (label.heights != null) withHeights(fitted) else fitted
}

// Writes a label with every line starting at x, or ending there, and the first line's box starting at y.
private fun textBlock(label: Label, x: Double, y: Double, attrs: String, icons: IconResolver?, end: Boolean = false): String {
  var out = ""
  for (i in label.lines.indices) {
    val line = label.lines[i]
    val cy = y + lineMiddle(label, i)
    if (line.size == 1 && line[0].icon == null && line[0].math == null && (line[0].style and (Style.Sub or Style.Sup or Style.Underline)) == 0) {
      out += "<text${textStyle(attrs, line[0].style)} x=\"${num(x)}\" y=\"${num(cy + label.size * 0.35)}\"${if (end) " text-anchor=\"end\"" else ""}>${esc(
        line[0].text,
      )}</text>"
    } else {
      val width = label.widths[i]
      out += labelSvg(labelLine(label, i), x + (if (end) -width else width) / 2, cy, attrs, icons)
    }
  }
  return out
}

private class Card(
  val item: KanbanNode,
  val label: Label,
  val ticket: Label,
  val assigned: Label,
  // Height of the rows under the text that hold the ticket and the assignee.
  val meta: Double,
  val height: Double,
)

private fun has(name: String?): Boolean = !name.isNullOrEmpty() && iconMarkup(name) != ""

fun renderKanban(model: KanbanModel, config: Config, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val line = jsRound(size * 1.5)
  val smallLine = jsRound(small * 1.5)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = options.icons
  // PORT: a non-object section has no properties.
  val conf = config["kanban"] as? Map<*, *> ?: emptyMap<String, Any?>()
  val sectionWidth = conf["sectionWidth"]
  val glyph = small * 1.25
  // Math can't break across lines, so columns widen to hold the widest expression.
  var mathWidth = 0.0
  val holds = { raw: String?, fontSize: Double, reserved: Double ->
    if (!(raw.isNullOrEmpty() || !raw.contains("$$"))) {
      for (spans in layoutLabel(raw, true, measurer, fontSize, Double.POSITIVE_INFINITY).lines) {
        for (span in spans) {
          val math = span.math
          if (math != null) mathWidth = maxOf(mathWidth, math.width + reserved)
        }
      }
    }
  }
  for (section in model.sections) {
    val count = measurer.width(section.items.size.toString(), small, 0)
    holds(section.label, size, 2 * COLUMN_PAD + 8 + count + 12 + (if (has(section.icon)) glyph + ICON_GAP else 0.0))
    for (item in section.items) {
      val reserved = 2 * (COLUMN_PAD + CARD_PAD)
      holds(item.label, small, reserved + (if (has(item.icon)) glyph + ICON_GAP else 0.0))
      holds(item.ticket, small, reserved)
      holds(item.assigned, small, reserved)
    }
  }
  val ownWidth = maxOf(
    if (sectionWidth is Double && sectionWidth > 0) minOf(maxOf(sectionWidth, 80.0), 2000.0) else 200.0,
    ceil(mathWidth),
  )
  // A board wider than the width the host has is folded into rows of columns, which then
  // share the row between them.
  val room = if (options.maxWidth != null && options.maxWidth > 0) options.maxWidth - 2 * pad else Double.POSITIVE_INFINITY
  val count = model.sections.size.toDouble()
  val fits = count * (ownWidth + COLUMN_GAP) - COLUMN_GAP <= room
  val perRow = if (fits) maxOf(count, 1.0) else maxOf(1.0, floor((room + COLUMN_GAP) / (ownWidth + COLUMN_GAP)))
  val columnWidth = if (fits) {
    ownWidth
  } else {
    maxOf(
      minOf(ownWidth, room),
      minOf(floor((room + COLUMN_GAP) / perRow) - COLUMN_GAP, ownWidth * 2),
      ceil(mathWidth),
    )
  }
  val baseUrl = conf["ticketBaseUrl"] as? String ?: ""
  val cardWidth = columnWidth - 2 * COLUMN_PAD
  val textWidth = cardWidth - 2 * CARD_PAD
  val links = ArrayList<LinkInfo>()

  val sections = model.sections
  val titles = ArrayList<Label>()
  val countWidths = ArrayList<Double>()
  val cards = ArrayList<List<Card>>()
  var headHeight = line
  val bodyHeights = ArrayList<Double>()
  for (section in sections) {
    val countWidth = measurer.width(section.items.size.toString(), small, 0)
    val reserved = countWidth + 12 + (if (has(section.icon)) glyph + ICON_GAP else 0.0)
    val title = fitLabel(section.label, true, measurer, size, cardWidth - 8 - reserved, Style.Bold)
    headHeight = maxOf(headHeight, title.height)
    countWidths.add(countWidth)
    titles.add(title)

    val list = ArrayList<Card>()
    var height = 0.0
    for (item in section.items) {
      val label = fitLabel(item.label, true, measurer, small, textWidth - (if (has(item.icon)) glyph + ICON_GAP else 0.0))
      val ticket = fitLabel(item.ticket, false, measurer, small, textWidth)
      val assigned = fitLabel(item.assigned, false, measurer, small, textWidth)
      val meta = if (ticket.width + assigned.width + 8 > textWidth) {
        ticket.height + assigned.height
      } else {
        maxOf(ticket.height, assigned.height)
      }
      val cardHeight = 2 * CARD_PAD + maxOf(label.height, smallLine) + (if (meta != 0.0 && !meta.isNaN()) META_GAP + meta else 0.0)
      list.add(Card(item, label, ticket, assigned, meta, cardHeight))
      height += cardHeight + CARD_GAP
    }
    cards.add(list)
    bodyHeights.add(height)
  }
  headHeight += 2 * COLUMN_PAD
  val rowHeights = ArrayList<Double>()
  val rowTops = ArrayList<Double>()
  var first = 0.0
  var yy = 0.0
  while (first < sections.size) {
    var tallest = CARD_GAP
    var index = first
    while (index < minOf(first + perRow, sections.size.toDouble())) {
      tallest = maxOf(tallest, bodyHeights[index.toInt()])
      index++
    }
    rowTops.add(yy)
    rowHeights.add(headHeight + tallest)
    yy += headHeight + tallest + COLUMN_GAP
    first += perRow
  }
  val boardHeight = if (rowHeights.size > 0) rowTops[rowTops.size - 1] + rowHeights[rowHeights.size - 1] else 0.0

  val title = titleLabel(model.title, measurer, size, titleRoom(options))
  val titleHeight = if (title.height > 0) title.height + 16 else 0.0
  val boardWidth = maxOf(minOf(sections.size.toDouble(), perRow) * (columnWidth + COLUMN_GAP) - COLUMN_GAP, 0.0)
  val width = maxOf(boardWidth, title.width)
  val left = pad + (width - boardWidth) / 2
  val edge = columnWidth - COLUMN_PAD - 4

  var out = ""
  for (index in sections.indices) {
    val section = sections[index]
    val row = floor(index / perRow).toInt()
    var body =
      "<rect width=\"${jsStr(columnWidth)}\" height=\"${num(rowHeights[row])}\" fill=\"var(--_s)\"$ROUNDED/>" +
        textBlock(titles[index], COLUMN_PAD + 4, COLUMN_PAD, " class=\"pele-cluster-label\" font-weight=\"var(--_hw)\"", icons) +
        "<text class=\"pele-count\" x=\"${jsStr(edge)}\" y=\"${num(
          COLUMN_PAD + line / 2 + small * 0.35,
        )}\" text-anchor=\"end\" font-size=\"${jsStr(small)}\" fill=\"var(--_m)\">${section.items.size}</text>"
    if (has(section.icon)) {
      body += iconSvg(section.icon!!, edge - countWidths[index] - ICON_GAP - glyph, COLUMN_PAD + (line - glyph) / 2, glyph, icons)
    }

    var y = headHeight
    for (card in cards[index]) {
      val item = card.item
      val priority = (item.priority ?: "").jsTrim().lowercase()
      val color = PRIORITY[priority]
      var inner = "<rect width=\"${jsStr(cardWidth)}\" height=\"${num(card.height)}\" fill=\"var(--_bg)\" stroke=\"var(--_b)\"$ROUNDED/>"
      if (color != null) {
        inner += "<path class=\"pele-priority\" d=\"M2.5,5V${num(card.height - 5)}\" fill=\"none\" $color stroke-width=\"3\" stroke-linecap=\"round\"/>"
      }
      inner += textBlock(card.label, CARD_PAD, CARD_PAD, " class=\"pele-label\"", icons)
      if (has(item.icon)) {
        inner += iconSvg(item.icon!!, cardWidth - CARD_PAD - glyph, CARD_PAD + (smallLine - glyph) / 2, glyph, icons)
      }
      val bottom = card.height - CARD_PAD
      if (!item.ticket.isNullOrEmpty()) {
        val top = bottom - card.meta
        val href = if (baseUrl != "") linkUrl(safeUrl(baseUrl.jsReplace("#TICKET#") { item.ticket!! }), options) else null
        if (href == null) {
          inner += textBlock(card.ticket, CARD_PAD, top, " class=\"pele-ticket\" fill=\"var(--_m)\"", icons)
        } else {
          inner +=
            "<a href=\"${esc(href)}\" target=\"_blank\"${relAttr(options)}>" +
            textBlock(card.ticket, CARD_PAD, top, " class=\"pele-ticket\" fill=\"var(--_c)\"", icons) +
            "</a>"
          links.add(LinkInfo(item.id, href, false))
        }
      }
      if (!item.assigned.isNullOrEmpty()) {
        inner += textBlock(
          card.assigned,
          cardWidth - CARD_PAD,
          bottom - card.assigned.height,
          " class=\"pele-assigned\" fill=\"var(--_m)\"",
          icons,
          true,
        )
      }
      body +=
        "<g class=\"pele-node pele-card${if (color == null) "" else " pele-priority-" + priority.jsReplace(" ", "-")}${classNames(
          item.cssClasses ?: "",
        )}\" data-id=\"${escText(item.id)}\" transform=\"translate(${jsStr(COLUMN_PAD)},${num(y)})\" font-size=\"${jsStr(small)}\">$inner</g>"
      y += card.height + CARD_GAP
    }

    out +=
      "<g class=\"pele-cluster pele-column${classNames(section.cssClasses ?: "")}\" data-id=\"${escText(section.id)}\" transform=\"translate(${num(
        left + (index % perRow) * (columnWidth + COLUMN_GAP),
      )},${num(pad + titleHeight + rowTops[row])})\">$body</g>"
  }

  val totalWidth = ceil(width + 2 * pad)
  val totalHeight = ceil(boardHeight + titleHeight + 2 * pad)
  val content = labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") + out

  return Rendered(
    svg = svgDocument("kanban", totalWidth, totalHeight, size, options, pele.svg.Acc(), content),
    width = totalWidth,
    height = totalHeight,
    links = links,
  )
}
