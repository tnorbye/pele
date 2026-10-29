// Ported from Pele src/diagrams/cynefin/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.cynefin

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.js.jsHypot
import pele.js.jsOr
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.ROUNDED
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.abs
import kotlin.math.ceil

private class Quadrant(val name: String, val col: Int, val row: Int, val color: Int)

// [domain, column, row, series color]
private val QUADRANTS = listOf(
  Quadrant("complex", 0, 0, 0),
  Quadrant("complicated", 1, 0, 1),
  Quadrant("chaotic", 0, 1, 3),
  Quadrant("clear", 1, 1, 2),
)
private const val CONFUSION_COLOR = 4

// [name, decision model, kind of practice]
private val META = mapOf(
  "complex" to listOf("Complex", "Probe → Sense → Respond", "Emergent Practices"),
  "complicated" to listOf("Complicated", "Sense → Analyse → Respond", "Good Practices"),
  "clear" to listOf("Clear", "Sense → Categorise → Respond", "Best Practices"),
  "chaotic" to listOf("Chaotic", "Act → Sense → Respond", "Novel Practices"),
  "confusion" to listOf("Confusion", "", "Disorder"),
)

private const val MAX_CONFUSION_ITEMS = 3
private const val ITEM_MAX_WIDTH = 200.0
private const val ITEM_PAD_X = 10.0
private const val ITEM_PAD_Y = 4.0
private const val ITEM_GAP = 6.0
private const val HEAD_GAP = 10.0
private const val MARGIN = 20.0
private const val CLEARANCE = 10.0

private class Badge(
  val text: String,
  val label: Label,
  val w: Double,
  val h: Double,
  val overflow: Boolean,
)

private class Block(
  val name: String,
  val title: Label,
  val subtitles: List<Label>,
  val badges: List<Badge>,
  val w: Double,
  val h: Double,
  var cx: Double,
  var cy: Double,
)

private fun setting(config: Config, key: String, fallback: Double, min: Double, max: Double): Double {
  val value = (config["cynefin"] as? Map<*, *>)?.get(key)
  return if (value is Double && value >= min && value <= max) value else fallback
}

fun renderCynefin(model: CynefinModel, config: Config, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.8125)
  val itemSize = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = options.icons
  val showDescriptions = (config["cynefin"] as? Map<*, *>)?.get("showDomainDescriptions") != false
  val amplitude = setting(config, "boundaryAmplitude", 8.0, 0.0, 50.0)
  val seed = resolveSeed(setting(config, "seed", 0.0, -1e15, 1e15), options.idPrefix ?: "pele")
  val itemLine = jsRound(itemSize * 1.5)

  val badge = { text: String, overflow: Boolean ->
    val label = layoutLabel(text, false, measurer, itemSize, ITEM_MAX_WIDTH)
    Badge(text, label, label.width + 2 * ITEM_PAD_X, maxOf(label.height, itemLine) + 2 * ITEM_PAD_Y, overflow)
  }

  val block = { name: String ->
    val meta = META[name]!!
    val title = layoutLabel(meta[0], false, measurer, size, 4000.0, Style.Bold)
    val subtitles = ArrayList<Label>()
    if (showDescriptions) {
      for (text in listOf(meta[1], meta[2])) if (text != "") subtitles.add(layoutLabel(text, false, measurer, small, 4000.0, Style.Italic))
    }
    val items = model.domains[name]?.items ?: emptyList()
    val shown = if (name == "confusion" && items.size > MAX_CONFUSION_ITEMS) MAX_CONFUSION_ITEMS else items.size
    val badges = ArrayList<Badge>()
    for (i in 0 until shown) badges.add(badge(items[i].label, false))
    if (shown < items.size) badges.add(badge("+${items.size - shown} more", true))
    var w = title.width
    var h = title.height
    for (s in subtitles) {
      w = maxOf(w, s.width)
      h += s.height
    }
    if (badges.size > 0) h += HEAD_GAP - ITEM_GAP
    for (b in badges) {
      w = maxOf(w, b.w)
      h += ITEM_GAP + b.h
    }
    Block(name, title, subtitles, badges, w, h, 0.0, 0.0)
  }

  val quadrants = QUADRANTS.map { q -> block(q.name) }
  val confusion = block("confusion")
  val rx = maxOf(88.0, confusion.w * 0.71 + 8)
  val ry = maxOf(64.0, confusion.h * 0.71 + 6)

  var quadW = setting(config, "width", 640.0, 100.0, 10000.0) / 2
  var quadH = setting(config, "height", 440.0, 100.0, 10000.0) / 2
  for (b in quadrants) {
    quadW = maxOf(quadW, b.w + 2 * MARGIN)
    quadH = maxOf(quadH, b.h + 2 * MARGIN)
  }
  // A block sits in the middle of its quadrant, so its inner corner must stay clear of the ellipse.
  for (b in quadrants) {
    val dx = (quadW - b.w) / 2
    val dy = (quadH - b.h) / 2
    if (StrictMath.pow(dx / (rx + CLEARANCE), 2.0) + StrictMath.pow(dy / (ry + CLEARANCE), 2.0) >= 1) continue
    val wider = b.w + 2 * (rx + CLEARANCE)
    val taller = b.h + 2 * (ry + CLEARANCE)
    if (wider * quadH <= quadW * taller) quadW = maxOf(quadW, wider) else quadH = maxOf(quadH, taller)
  }

  val title = layoutLabel(model.title, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val width = 2 * quadW
  val height = 2 * quadH
  val x0 = pad
  val y0 = pad + titleHeight
  val cx = x0 + quadW
  val cy = y0 + quadH
  val right = x0 + width
  val bottom = y0 + height

  val fold = wave(y0, cy, cx, seed, amplitude, FOLD.copy(segments = 4, pinEnd = true))
  val west = wave(x0, cx, cy, seed + 100, amplitude, HORIZON.copy(segments = 4, pinEnd = true))
  val east = wave(cx, right, cy, seed + 200, amplitude, HORIZON.copy(segments = 4, pinStart = true))
  val drop = cliff(cx, cy, bottom, width * 0.03)
  val at = { curve: List<Double>, end: Boolean ->
    if (end) "${num(curve[curve.size - 2])},${num(curve[curve.size - 1])}" else "${num(curve[0])},${num(curve[1])}"
  }

  val regions = mapOf(
    "complex" to "M${num(x0)},${num(y0)}L${at(fold, false)}${forward(fold)}${backward(west)}Z",
    "complicated" to "M${at(fold, false)}L${num(right)},${num(y0)}L${at(east, true)}${backward(east)}${backward(fold)}Z",
    "chaotic" to "M${at(west, false)}${forward(west)}${forward(drop)}L${num(x0)},${num(bottom)}Z",
    "clear" to "M${at(east, false)}${forward(east)}L${num(right)},${num(bottom)}L${at(drop, true)}${backward(drop)}Z",
  )

  var fills = ""
  for (q in QUADRANTS) {
    fills += "<path class=\"pele-cynefin-region\" data-id=\"${q.name}\" d=\"${regions[q.name]!!}\" fill=\"${seriesColor(q.color)}\" fill-opacity=\"0.14\"/>"
  }
  val boundaries =
    "<g class=\"pele-cynefin-boundaries\" fill=\"none\" stroke=\"var(--_b)\" stroke-dasharray=\"6 3\">" +
      "<path d=\"${curvePath(fold)}\"/><path d=\"${curvePath(west)}\"/><path d=\"${curvePath(east)}\"/>" +
      "<path class=\"pele-cynefin-cliff\" d=\"${curvePath(drop)}\" stroke=\"var(--_l)\" stroke-width=\"2\" stroke-dasharray=\"none\" stroke-linecap=\"round\"/></g>"
  val oval = ellipsePath(cx, cy, rx, ry)
  val ellipse =
    "<path d=\"$oval\" fill=\"var(--_bg)\"/>" +
      "<path class=\"pele-cynefin-region\" data-id=\"confusion\" d=\"$oval\" fill=\"${seriesColor(
        CONFUSION_COLOR,
      )}\" fill-opacity=\"0.18\" stroke=\"var(--_b)\" stroke-dasharray=\"4 2\"/>"

  val drawBlock = { b: Block ->
    var y = b.cy - b.h / 2
    var out = labelSvg(b.title, b.cx, y + b.title.height / 2, " class=\"pele-label\" font-weight=\"var(--_hw)\"")
    y += b.title.height
    for (s in b.subtitles) {
      out += labelSvg(s, b.cx, y + s.height / 2, " class=\"pele-cynefin-subtitle\" font-size=\"${jsStr(small)}\" font-style=\"italic\" fill=\"var(--_m)\"")
      y += s.height
    }
    y += HEAD_GAP - ITEM_GAP
    for (item in b.badges) {
      y += ITEM_GAP
      out +=
        "<g class=\"pele-node${if (item.overflow) " pele-cynefin-overflow" else ""}\"${if (item.overflow) "" else " data-id=\"${escText(item.text)}\""}>" +
        "<rect x=\"${num(b.cx - item.w / 2)}\" y=\"${num(y)}\" width=\"${num(item.w)}\" height=\"${num(
          item.h,
        )}\" fill=\"var(--_bg)\" stroke=\"var(--_b)\"${if (item.overflow) " stroke-dasharray=\"3 2\"" else ""}$ROUNDED/>" +
        labelSvg(item.label, b.cx, y + item.h / 2, " class=\"pele-label\" font-size=\"${jsStr(itemSize)}\"${if (item.overflow) " fill=\"var(--_m)\"" else ""}", icons) +
        "</g>"
      y += item.h
    }
    "<g class=\"pele-cynefin-domain\" data-id=\"${b.name}\">$out</g>"
  }

  val blocks = LinkedHashMap<String, Block>()
  var domains = ""
  QUADRANTS.forEachIndexed { index, q ->
    val b = quadrants[index]
    b.cx = x0 + quadW * (q.col + 0.5)
    b.cy = y0 + quadH * (q.row + 0.5)
    blocks[q.name] = b
    domains += drawBlock(b)
  }
  confusion.cx = cx
  confusion.cy = cy
  blocks["confusion"] = confusion
  domains += drawBlock(confusion)

  val inside = { b: Block, x: Double, y: Double ->
    if (b === confusion) {
      StrictMath.pow((x - cx) / (rx + 4), 2.0) + StrictMath.pow((y - cy) / (ry + 4), 2.0) <= 1
    } else {
      abs(x - b.cx) <= b.w / 2 + 8 && abs(y - b.cy) <= b.h / 2 + 8
    }
  }

  var edges = ""
  val seen = LinkedHashMap<String, Int>()
  for (transition in model.transitions) {
    val from = blocks[transition.from]
    val to = blocks[transition.to]
    if (from == null || to == null || from === to) continue
    val key = if (transition.from < transition.to) "${transition.from} ${transition.to}" else "${transition.to} ${transition.from}"
    val repeat = seen[key] ?: 0
    seen[key] = repeat + 1

    val dx = to.cx - from.cx
    val dy = to.cy - from.cy
    val len = jsHypot(dx, dy).jsOr { 1.0 }
    val mx = (from.cx + to.cx) / 2
    val my = (from.cy + to.cy) / 2
    // Curves bow away from the middle of the diagram, which keeps them off the ellipse.
    val outward = if (-dy * (mx - cx) + dx * (my - cy) >= 0) 1 else -1
    val nx = (-dy / len) * outward
    val ny = (dx / len) * outward
    // A diagonal runs through the middle, so it bows far enough to pass the ellipse.
    val diagonal = from !== confusion && to !== confusion && abs(dx) > 1 && abs(dy) > 1
    val bow = (if (diagonal) 2 * (jsHypot(rx * nx, ry * ny) + 16) else len * 0.15) + repeat * 56
    val px = mx + nx * bow
    val py = my + ny * bow
    val point = { t: Double ->
      val u = 1 - t
      Pair(u * u * from.cx + 2 * u * t * px + t * t * to.cx, u * u * from.cy + 2 * u * t * py + t * t * to.cy)
    }
    val crossing = { b: Block, loIn: Double, hiIn: Double, leaving: Boolean ->
      var lo = loIn
      var hi = hiIn
      for (i in 0 until 16) {
        val mid = (lo + hi) / 2
        val p = point(mid)
        if (inside(b, p.first, p.second) == leaving) lo = mid else hi = mid
      }
      (lo + hi) / 2
    }
    val STEPS = 48
    var i = 1
    while (i <= STEPS && point(i.toDouble() / STEPS).let { inside(from, it.first, it.second) }) i++
    if (i > STEPS) continue
    val t0 = crossing(from, (i - 1).toDouble() / STEPS, i.toDouble() / STEPS, true)
    while (i <= STEPS && !point(i.toDouble() / STEPS).let { inside(to, it.first, it.second) }) i++
    val t1 = if (i > STEPS) 1.0 else crossing(to, (i - 1).toDouble() / STEPS, i.toDouble() / STEPS, false)

    val (sx, sy) = point(t0)
    var (ex, ey) = point(t1)
    val blossom = { a: Double, b: Double, c: Double -> (1 - t0) * (1 - t1) * a + ((1 - t0) * t1 + t0 * (1 - t1)) * b + t0 * t1 * c }
    val qx = blossom(from.cx, px, to.cx)
    val qy = blossom(from.cy, py, to.cy)
    val el = jsHypot(ex - qx, ey - qy).jsOr { 1.0 }
    val ux = (ex - qx) / el
    val uy = (ey - qy) / el
    val head = marker("arrow_point", ex, ey, ux, uy, "var(--_l)")
    ex -= ux * markerTrim("arrow_point")
    ey -= uy * markerTrim("arrow_point")

    var text = ""
    if (!transition.label.isNullOrEmpty()) {
      val label = layoutLabel(transition.label, false, measurer, small, ITEM_MAX_WIDTH)
      val (lx, ly) = point((t0 + t1) / 2)
      val away = (abs(nx) * label.width) / 2 + (abs(ny) * label.height) / 2 + 6
      val half = label.width / 2 + 4
      val tx = minOf(maxOf(lx + nx * away, x0 + half), maxOf(right - half, x0 + half))
      text = labelSvg(label, tx, ly + ny * away, " class=\"pele-edge-label\" font-size=\"${jsStr(small)}\"", icons)
    }
    edges +=
      "<g class=\"pele-edge\" data-id=\"${transition.from}-${transition.to}\">" +
      "<path d=\"M${num(sx)},${num(sy)}Q${num(qx)},${num(qy)} ${num(ex)},${num(ey)}\" fill=\"none\" stroke=\"var(--_l)\"/>" +
      head +
      text +
      "</g>"
  }

  val totalWidth = ceil(maxOf(width, title.width) + 2 * pad)
  val totalHeight = ceil(height + titleHeight + 2 * pad)
  val content =
    labelSvg(title, x0 + width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g class=\"pele-cynefin-regions\">$fills</g>" +
      boundaries +
      ellipse +
      domains +
      (if (edges != "") "<g class=\"pele-edges\">$edges</g>" else "")

  return Rendered(
    svgDocument("cynefin", totalWidth, totalHeight, size, options, model, content),
    totalWidth,
    totalHeight,
    ArrayList(),
  )
}
