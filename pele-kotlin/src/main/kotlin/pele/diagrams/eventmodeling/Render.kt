// Ported from Pele src/diagrams/eventmodeling/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.eventmodeling

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.breakLabel
import pele.diagrams.common.leftLabelSvg
import pele.diagrams.common.literalLabel
import pele.diagrams.common.turnToFit
import pele.js.cmp
import pele.js.jsRound
import pele.js.jsSplit
import pele.js.jsStr
import pele.js.toCodePointStrings
import pele.js.truthy
import pele.svg.FONT_MONO
import pele.svg.ROUNDED
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.abs
import kotlin.math.ceil

// Series slot for each kind of entity. A UI has none and is drawn plain.
private val SERIES = mapOf(
  "command" to 0,
  "event" to 1,
  "readmodel" to 2,
  "processor" to 6,
)

private const val BOX_PAD_X = 12.0
private const val BOX_PAD_Y = 10.0
private const val BOX_MIN_WIDTH = 96.0
private const val BOX_GAP = 16.0
private const val STAGGER = 80.0
private const val LANE_PAD = 12.0
private const val LANE_GAP = 8.0
private const val DATA_COLUMNS = 48
private const val NOTE_COLUMNS = 80
private const val CARD_GAP = 8.0

private class Placed(
  val box: EmBox,
  val name: Label,
  val data: List<String>,
  var x: Double,
  var y: Double,
  val w: Double,
  val h: Double,
)

private class Band(
  val lane: EmLane,
  val label: Label,
  var right: Double,
  var y: Double,
  var h: Double,
)

// Splits text into lines of at most `columns` characters, keeping its own line breaks.
private fun hardWrap(text: String, columns: Int): List<String> {
  val out = ArrayList<String>()
  for (line in decodeEntities(text).jsSplit("\n")) {
    val chars = if (line.length <= columns) null else line.toCodePointStrings()
    if (chars == null || chars.size <= columns) {
      out.add(line)
      continue
    }
    var i = 0
    while (i < chars.size) {
      out.add(chars.subList(i, minOf(chars.size, i + columns)).joinToString(""))
      i += columns
    }
  }
  return out
}

private fun lines(rows: List<String>, x: Double, y: Double, step: Double, attrs: String): String {
  var body = ""
  for (i in rows.indices) {
    if (rows[i] != "") body += "<tspan x=\"${num(x)}\" y=\"${num(y + i * step)}\">${esc(rows[i])}</tspan>"
  }
  return if (body == "") "" else "<text$attrs xml:space=\"preserve\">$body</text>"
}

fun renderEventModel(model: EventModelingModel, _config: Config, options: RenderOptions): Rendered =
  turnToFit(options) { down -> draw(model, _config, options, down) }

private fun draw(model: EventModelingModel, _config: Config, options: RenderOptions, down: Boolean): Rendered {
  if (down) return verticalEventModel(model, options)
  val size = options.fontSize ?: 16.0
  val nameSize = jsRound(size * 0.875)
  val small = jsRound(size * 0.8125)
  val mono = jsRound(size * 0.75)
  val monoStep = jsRound(mono * 1.4)
  val smallStep = jsRound(small * 1.5)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val widest = { rows: List<String>, fontSize: Double, style: Int ->
    var width = 0.0
    for (row in rows) width = maxOf(width, measurer.width(row, fontSize, style))
    width
  }

  val bands = LinkedHashMap<EmLane, Band>()
  var labelColumn = 0.0
  for (lane in model.lanes) {
    val label = layoutLabel(lane.label, false, measurer, small, 4000.0, Style.Bold)
    labelColumn = maxOf(labelColumn, label.width)
    bands[lane] = Band(lane, label, Double.NEGATIVE_INFINITY, 0.0, 0.0)
  }
  labelColumn += 2 * LANE_PAD

  // Time runs left to right. A frame in a new lane starts under the end of the one before it,
  // so the arrow between them can drop straight down; a frame in the same lane follows on.
  val placed = ArrayList<Placed>()
  val byBox = LinkedHashMap<EmBox, Placed>()
  var previous: Placed? = null
  for (box in model.boxes) {
    val band = bands[box.lane]!!
    val name = layoutLabel(box.name, false, measurer, nameSize, 4000.0, Style.Bold)
    val data = if (!box.data.isNullOrEmpty()) hardWrap(box.data, DATA_COLUMNS) else emptyList()
    val w = maxOf(BOX_MIN_WIDTH, maxOf(name.width, widest(data, mono, Style.Mono)) + 2 * BOX_PAD_X)
    val h = 2 * BOX_PAD_Y + name.height + (if (data.size > 0) 6 + data.size * monoStep else 0.0)
    var x = 0.0
    if (previous != null) {
      val stagger = previous.x + maxOf(previous.w / 2, previous.w - STAGGER)
      x = if (previous.box.lane === box.lane) band.right + BOX_GAP else maxOf(stagger, band.right + BOX_GAP)
    }
    val item = Placed(box, name, data, x, 0.0, w, h)
    band.right = x + w
    band.h = maxOf(band.h, h)
    placed.add(item)
    byBox[box] = item
    previous = item
  }

  var contentRight = 0.0
  for (item in placed) contentRight = maxOf(contentRight, item.x + item.w)
  val laneWidth = labelColumn + contentRight + LANE_PAD

  val title = layoutLabel(model.title, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val left = pad
  val top = pad + titleHeight

  var y = top
  var lanes = ""
  for (band in bands.values) {
    band.y = y
    band.h += 2 * LANE_PAD
    lanes +=
      "<g class=\"pele-cluster pele-em-lane\" data-id=\"${escText(band.lane.label)}\">" +
      "<rect x=\"${num(left)}\" y=\"${num(y)}\" width=\"${num(laneWidth)}\" height=\"${num(band.h)}\" fill=\"var(--_s)\"$ROUNDED/>" +
      labelSvg(band.label, left + LANE_PAD + band.label.width / 2, y + band.h / 2, " class=\"pele-cluster-label\" font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\" fill=\"var(--_m)\"") +
      "</g>"
    y += band.h + LANE_GAP
  }
  var bottom = if (bands.size > 0) y - LANE_GAP else top

  var nodes = ""
  for (item in placed) {
    val band = bands[item.box.lane]!!
    item.x += left + labelColumn
    item.y = band.y + (band.h - item.h) / 2
    val series = SERIES[item.box.kind]
    val frame = "x=\"${num(item.x)}\" y=\"${num(item.y)}\" width=\"${num(item.w)}\" height=\"${num(item.h)}\""
    val shape =
      if (series == null) {
        "<rect $frame fill=\"var(--_bg)\" stroke=\"var(--_b)\"$ROUNDED/>"
      } else {
        "<rect $frame fill=\"var(--_bg)\"$ROUNDED/><rect $frame fill=\"${seriesColor(series)}\" fill-opacity=\"0.22\" stroke=\"${seriesColor(series)}\"$ROUNDED/>"
      }
    val nameY = item.y + BOX_PAD_Y + item.name.height / 2
    val text =
      labelSvg(item.name, item.x + item.w / 2, nameY, " class=\"pele-label\" font-size=\"${jsStr(nameSize)}\" font-weight=\"var(--_hw)\"") +
        lines(
          item.data,
          item.x + BOX_PAD_X,
          item.y + BOX_PAD_Y + item.name.height + 6 + monoStep / 2 + mono * 0.35,
          monoStep,
          " class=\"pele-em-data\" font-family=\"$FONT_MONO\" font-size=\"${jsStr(mono)}\"",
        )
    nodes += "<g class=\"pele-node pele-em-${item.box.kind}${if (item.box.reset) " pele-em-reset" else ""}\" data-id=\"${escText(item.box.id)}\">$shape$text</g>"
  }

  var edges = ""
  val trim = markerTrim("arrow_point")
  for (relation in model.relations) {
    val s = byBox[relation.source]
    val t = byBox[relation.target]
    if (s == null || t == null || s === t) continue
    val d: String
    val head: String
    if (s.box.lane === t.box.lane) {
      // Along the lane, from the side of one box to the side of the other.
      val forward = t.x >= s.x
      val sx = if (forward) s.x + s.w else s.x
      val tx = if (forward) t.x else t.x + t.w
      val sy = s.y + s.h / 2
      val ty = t.y + t.h / 2
      val dir = if (tx >= sx) 1.0 else -1.0
      d = "M${num(sx)},${num(sy)}L${num(tx - dir * trim)},${num(ty)}"
      head = marker("arrow_point", tx, ty, dir, 0.0, "var(--_l)")
    } else {
      // Between lanes, straight down or up where the boxes overlap, else in an S.
      val down = t.y > s.y
      val from = maxOf(s.x, t.x)
      val to = minOf(s.x + s.w, t.x + t.w)
      val inset = { w: Double -> minOf(24.0, w / 4) }
      val sx = if (to - from >= 16) (from + to) / 2 else if (t.x > s.x) s.x + s.w - inset(s.w) else s.x + inset(s.w)
      val tx = if (to - from >= 16) sx else if (t.x > s.x) t.x + inset(t.w) else t.x + t.w - inset(t.w)
      val sy = if (down) s.y + s.h else s.y
      val ty = if (down) t.y else t.y + t.h
      val dir = if (down) 1.0 else -1.0
      val end = ty - dir * trim
      val mid = (sy + end) / 2
      d = if (sx == tx) "M${num(sx)},${num(sy)}V${num(end)}" else "M${num(sx)},${num(sy)}C${num(sx)},${num(mid)} ${num(tx)},${num(mid)} ${num(tx)},${num(end)}"
      head = marker("arrow_point", tx, ty, 0.0, dir, "var(--_l)")
    }
    edges += "<g class=\"pele-edge\" data-id=\"${escText(relation.source.id)}-${escText(relation.target.id)}\"><path d=\"$d\" fill=\"none\" stroke=\"var(--_l)\"/>$head</g>"
  }

  // Notes and given/when/then specifications, listed under the lanes with the frame they belong to.
  val names = LinkedHashMap<String, String>()
  for (box in model.boxes) names[box.id] = box.name
  var cards = ""
  var cardsRight = 0.0
  fun card(kind: String, heading: String, frame: String, rows: List<String>) {
    val head = layoutLabel("$heading · $frame${if (names.containsKey(frame)) " " + names[frame]!! else ""}", false, measurer, small, 4000.0, Style.Bold)
    val w = maxOf(head.width, widest(rows, small, 0)) + 2 * BOX_PAD_X
    val h = 2 * BOX_PAD_Y + head.height + rows.size * smallStep
    val cy = bottom + (if (cards == "" && bands.size == 0) 0.0 else CARD_GAP)
    cards +=
      "<g class=\"pele-em-$kind\" data-id=\"${escText(frame)}\">" +
      "<rect x=\"${num(left)}\" y=\"${num(cy)}\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"var(--_bg)\" stroke=\"var(--_b)\"$ROUNDED/>" +
      labelSvg(head, left + BOX_PAD_X + head.width / 2, cy + BOX_PAD_Y + head.height / 2, " font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\"") +
      lines(rows, left + BOX_PAD_X, cy + BOX_PAD_Y + head.height + smallStep / 2 + small * 0.35, smallStep, " font-size=\"${jsStr(small)}\"") +
      "</g>"
    bottom = cy + h
    cardsRight = maxOf(cardsRight, w)
  }
  for (note in model.notes) card("note", "Note", note.frame, hardWrap(note.text, NOTE_COLUMNS))
  for (spec in model.specifications) {
    val rows = mutableListOf("Given  ${spec.given.joinToString(", ")}")
    if (spec.`when`.size > 0) rows.add("When  ${spec.`when`.joinToString(", ")}")
    rows.add("Then  ${spec.then.joinToString(", ")}")
    card("specification", "Given / When / Then", spec.frame, rows.flatMap { row -> hardWrap(row, NOTE_COLUMNS) })
  }

  val width = maxOf(maxOf(if (bands.size > 0) laneWidth else 0.0, cardsRight), title.width)
  val totalWidth = ceil(width + 2 * pad)
  val totalHeight = ceil(bottom + pad)
  val content =
    labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") + lanes + edges + nodes + cards

  return Rendered(
    svgDocument("eventmodeling", totalWidth, totalHeight, size, options, model, content),
    totalWidth,
    totalHeight,
    ArrayList(),
  )
}

private const val RAIL_GAP = 8.0
private const val CARD_MIN = 120.0
private const val ARROW_ROOM = 20.0

private class Slot(val y: Double, val h: Double, val w: Double, val index: Int, val lane: Double)

private fun verticalEventModel(model: EventModelingModel, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val mono = jsRound(size * 0.75)
  val pad = options.padding ?: 8.0
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val room = maxOf(160.0, (options.maxWidth ?: 320.0) - 2 * pad)
  // A relation that skips a card runs down a rail beside the cards. Relations into one card
  // share a rail; others whose runs overlap take the next rail out, so that no two follow the
  // same line.
  val order = LinkedHashMap<EmBox, Int>()
  model.boxes.forEachIndexed { index, box -> order[box] = index }
  val runs = LinkedHashMap<EmBox, IntArray>()
  // A relation between neighbouring cards passes the lower card's lane heading.
  val passed = HashSet<EmBox>()
  for (relation in model.relations) {
    val s = order[relation.source]
    val t = order[relation.target]
    if (s == null || t == null || s == t) continue
    if (abs(s - t) < 2) {
      passed.add(model.boxes[maxOf(s, t)])
      continue
    }
    val run = runs[relation.target]
    if (run != null) {
      run[0] = minOf(run[0], s)
      run[1] = maxOf(run[1], s)
    } else {
      runs[relation.target] = intArrayOf(minOf(s, t), maxOf(s, t))
    }
  }
  val railOf = LinkedHashMap<EmBox, Int>()
  val railEnds = ArrayList<Int>()
  for ((target, span) in runs.entries.map { e -> Pair(e.key, e.value) }.sortedWith { a, b -> cmp((a.second[0] - b.second[0]).toDouble()) }) {
    val from = span[0]
    val to = span[1]
    var rail = railEnds.indexOfFirst { end -> end < from }
    if (rail < 0) rail = railEnds.size
    if (rail < railEnds.size) railEnds[rail] = to else railEnds.add(to)
    railOf[target] = rail
  }
  val gutter = 24 + RAIL_GAP * maxOf(0, railEnds.size - 1)
  // Rails are never shared to save room: past what the width allows, the drawing is wider.
  val cardWidth = maxOf(CARD_MIN, room - gutter)
  val textRoom = cardWidth - 2 * BOX_PAD_X
  val title = layoutLabel(model.title, false, measurer, size, room, Style.Bold)
  var y = pad + (if (truthy(title.height)) title.height + 12 else 0.0)
  var bottom = pad + title.height
  var width = maxOf(title.width, cardWidth + gutter)
  var nodes = ""
  var edges = ""
  var cards = ""
  val placed = LinkedHashMap<EmBox, Slot>()
  fun text(raw: String, fontSize: Double, style: Int = 0): Label = literalLabel(decodeEntities(raw), measurer, fontSize, textRoom, style)
  model.boxes.forEachIndexed { index, box ->
    val laneRoom = if (passed.contains(box)) textRoom - ARROW_ROOM else textRoom
    val lane = breakLabel(layoutLabel(box.lane.label, false, measurer, small, laneRoom, Style.Bold), measurer, laneRoom)
    val name = layoutLabel(box.name, false, measurer, small, textRoom - 36, Style.Bold)
    val data = if (!box.data.isNullOrEmpty()) text(box.data, mono, Style.Mono) else null
    val w = maxOf(maxOf(cardWidth, lane.width + 2 * BOX_PAD_X), maxOf(name.width + 2 * BOX_PAD_X + 36, (data?.width ?: 0.0) + 2 * BOX_PAD_X))
    val headH = lane.height + 12
    val h = 2 * BOX_PAD_Y + name.height + (if (data != null) data.height + 6 else 0.0)
    val series = SERIES[box.kind]
    val cy = y + headH
    val frame = "x=\"${num(pad)}\" y=\"${num(cy)}\" width=\"${num(w)}\" height=\"${num(h)}\""
    nodes += "<g class=\"pele-cluster pele-em-lane\" data-id=\"${escText(box.lane.label)}\">" +
      leftLabelSvg(lane, pad + BOX_PAD_X, y, " class=\"pele-cluster-label\" font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\" fill=\"var(--_m)\"") +
      "<g class=\"pele-node pele-em-${box.kind}${if (box.reset) " pele-em-reset" else ""}\" data-id=\"${escText(box.id)}\"><rect $frame fill=\"var(--_bg)\" stroke=\"var(--_b)\"$ROUNDED/>" +
      (if (series == null) "" else "<rect $frame fill=\"${seriesColor(series)}\" fill-opacity=\"0.22\" stroke=\"${seriesColor(series)}\"$ROUNDED/>") +
      labelSvg(name, pad + BOX_PAD_X + name.width / 2, cy + BOX_PAD_Y + name.height / 2, " class=\"pele-label\" font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\"") +
      "<text class=\"pele-em-frame\" x=\"${num(pad + w - BOX_PAD_X)}\" y=\"${num(cy + BOX_PAD_Y + small)}\" text-anchor=\"end\" font-size=\"${jsStr(mono)}\" fill=\"var(--_m)\">${esc(box.id)}</text>" +
      (if (data != null) lines(data.lines.map { line -> line.joinToString("") { span -> span.text } }, pad + BOX_PAD_X, cy + BOX_PAD_Y + name.height + 6 + data.lineHeight / 2 + mono * 0.35, data.lineHeight, " class=\"pele-em-data\" font-family=\"$FONT_MONO\" font-size=\"${jsStr(mono)}\"") else "") + "</g></g>"
    placed[box] = Slot(cy, h, w, index, lane.width)
    width = maxOf(width, w + gutter)
    bottom = cy + h
    y = bottom + 28
  }
  for (relation in model.relations) {
    val s = placed[relation.source]
    val t = placed[relation.target]
    if (s == null || t == null || s === t) continue
    val down = t.y > s.y
    val dir = if (down) 1.0 else -1.0
    val sy = if (down) s.y + s.h else s.y
    val ty = if (down) t.y else t.y + t.h
    val d: String
    val head: String
    if (abs(s.index - t.index) == 1) {
      // The lower card's lane heading sits in the gap, and wraps to leave the arrow room beside it.
      val cx = pad + maxOf(minOf(s.w, t.w) / 2, BOX_PAD_X + (if (down) t else s).lane + 16)
      d = "M${num(cx)},${num(sy)}V${num(ty - dir * markerTrim("arrow_point"))}"
      head = marker("arrow_point", cx, ty, 0.0, dir, "var(--_l)")
    } else {
      val rail = pad + width - gutter + 12 + RAIL_GAP * (railOf[relation.target] ?: 0)
      // A card can send one relation and receive another: they leave above and arrive below its middle.
      val from = s.y + s.h / 2 - 5
      val to = t.y + t.h / 2 + 5
      d = "M${num(pad + s.w)},${num(from)}H${num(rail)}V${num(to)}H${num(pad + t.w + markerTrim("arrow_point"))}"
      head = marker("arrow_point", pad + t.w, to, -1.0, 0.0, "var(--_l)")
    }
    edges += "<g class=\"pele-edge\" data-id=\"${escText(relation.source.id)}-${escText(relation.target.id)}\"><path d=\"$d\" fill=\"none\" stroke=\"var(--_l)\"/>$head</g>"
  }
  val names = LinkedHashMap<String, String>()
  for (box in model.boxes) names[box.id] = box.name
  fun card(kind: String, heading: String, frame: String, raw: String) {
    val head = layoutLabel("$heading · $frame${if (names.containsKey(frame)) " " + names[frame]!! else ""}", false, measurer, small, textRoom, Style.Bold)
    val body = text(raw, small)
    val w = maxOf(cardWidth, head.width + 2 * BOX_PAD_X, body.width + 2 * BOX_PAD_X)
    val h = 2 * BOX_PAD_Y + head.height + body.height
    cards += "<g class=\"pele-em-$kind\" data-id=\"${escText(frame)}\"><rect x=\"${num(pad)}\" y=\"${num(y)}\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"var(--_bg)\" stroke=\"var(--_b)\"$ROUNDED/>" +
      labelSvg(head, pad + BOX_PAD_X + head.width / 2, y + BOX_PAD_Y + head.height / 2, " font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\"") +
      lines(body.lines.map { line -> line.joinToString("") { span -> span.text } }, pad + BOX_PAD_X, y + BOX_PAD_Y + head.height + body.lineHeight / 2 + small * 0.35, body.lineHeight, " font-size=\"${jsStr(small)}\"") + "</g>"
    bottom = y + h
    y = bottom + CARD_GAP
    width = maxOf(width, w)
  }
  for (note in model.notes) card("note", "Note", note.frame, note.text)
  for (spec in model.specifications) {
    val rows = mutableListOf("Given  ${spec.given.joinToString(", ")}")
    if (spec.`when`.size > 0) rows.add("When  ${spec.`when`.joinToString(", ")}")
    rows.add("Then  ${spec.then.joinToString(", ")}")
    card("specification", "Given / When / Then", spec.frame, rows.joinToString("\n"))
  }
  val totalWidth = ceil(width + 2 * pad)
  val height = ceil(bottom + pad)
  val content = labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") + edges + nodes + cards
  return Rendered(svgDocument("eventmodeling", totalWidth, height, size, options, model, content), totalWidth, height, ArrayList())
}
