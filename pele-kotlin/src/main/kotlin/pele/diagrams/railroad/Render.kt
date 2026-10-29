// Ported from Pele src/diagrams/railroad/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.tighten
import pele.diagrams.common.titleRoom
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsStr
import pele.js.truthy
import pele.svg.FONT_MONO
import pele.svg.ROUNDED
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.svgDocument
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.ceil

private object Kind {
  const val Terminal = 0
  const val NonTerminal = 1
  const val Special = 2
  const val Sequence = 3
  const val Choice = 4
  const val Bypass = 5
  const val Loop = 6
  const val Folded = 7
}

// A laid-out element. The track enters at the left edge and leaves at the right edge on one
// baseline, with `up` above it and `down` below it.
private class Box(
  val kind: Int,
  val width: Double,
  val up: Double,
  val down: Double,
  val kids: List<Box>,
  // A leaf's text, or a loop's count.
  val text: String,
  // The name a non-terminal refers to.
  val id: String,
  // How far a bypass rises above the baseline, or a loop drops below it.
  val reach: Double,
)

private class Metrics(
  val measurer: TextMeasurer,
  val size: Double,
  val padding: Double,
  val height: Double,
  // Space between elements in a sequence, and between stacked branches.
  val gap: Double,
  val sep: Double,
  val arc: Double,
)

private const val LEAD = 12.0
private const val HEAD_GAP = 6.0
private const val RULE_GAP = 18.0
private const val COUNT_SIZE = 12.0
private const val ARROW = 7.0

private fun option(config: Config, key: String, fallback: Double, max: Double): Double {
  val value = (config["railroad"] as? Map<*, *>)?.get(key)
  return if (value is Double && value >= 0) minOf(value, max) else fallback
}

private val RE_CONTROL = JsRegExp("""[\n\r\t]""", "g")
private val RE_AMP = JsRegExp("&", "g")
private val RE_SPACES = JsRegExp("""^ +$""")

// Text is literal here: a terminal such as "<br>" or "*" is itself, not markup. Mermaid's `#35;`
// codes still stand for characters, and escapes that would be invisible are written out.
private fun shown(text: String): String {
  val s = if (text.contains("¶ß")) decodeEntities(text.jsReplace(RE_AMP, "&amp;")) else text
  return s.jsReplace(RE_CONTROL) { m -> if (m.value == "\n") "\\n" else if (m.value == "\r") "\\r" else "\\t" }
}

private fun <T> reverse(list: MutableList<T>, from: Int) {
  var i = from
  var j = list.size - 1
  while (i < j) {
    val held = list[i]
    list[i] = list[j]
    list[j] = held
    i++
    j--
  }
}

private fun even(value: Double): Double = ceil(value / 2) * 2

private fun leaf(kind: Int, text: String, id: String, style: Int, m: Metrics): Box {
  val half = m.height / 2
  val width = maxOf(even(m.measurer.width(text, m.size, style) + 2 * m.padding), m.height)
  return Box(kind, width, half, half, NONE, text, id, 0.0)
}

private fun count(min: Double, max: Double): String {
  if (max == Double.POSITIVE_INFINITY && min <= 1) return ""
  val text = { value: Double -> if (value.isFinite()) jsStr(value) else "∞" }
  if (min == max) return text(min) + "×"
  if (max == Double.POSITIVE_INFINITY) return "≥" + text(min) + "×"
  return if (min == 0.0) "≤" + text(max) + "×" else text(min) + "–" + text(max) + "×"
}

// Where the next branch of a choice sits below the baseline, given the one above it.
private fun below(offset: Double, above: Box, branch: Box, m: Metrics): Double =
  maxOf(offset + above.down + m.sep + branch.up, 2 * m.arc)

private fun bypass(kid: Box, m: Metrics): Box {
  val reach = maxOf(kid.up + m.sep, 2 * m.arc)
  return Box(Kind.Bypass, kid.width + 4 * m.arc, reach, kid.down, listOf(kid), "", "", reach)
}

private fun measure(root: RailroadNode, m: Metrics, room: Double = Double.POSITIVE_INFINITY): Box {
  val budgets = java.util.IdentityHashMap<RailroadNode, Double>()
  val pending = ArrayList<Pair<RailroadNode, Double>>()
  pending.add(root to room)
  var i = 0
  while (room != Double.POSITIVE_INFINITY && i < pending.size) {
    val (node, width) = pending[i]
    budgets[node] = width
    // A sequence that folds puts its rows inside rails, so its parts are sized for those rows.
    val around = if (node.type == "choice" || node.type == "optional") {
      4 * m.arc
    } else if (node is RailroadNode.Repetition) {
      (if (node.min == 0.0) 6 else 2) * m.arc
    } else if (node.type == "sequence") {
      6 * m.arc
    } else {
      0.0
    }
    for (child in parts(node)) pending.add(child to maxOf(40.0, width - around))
    i++
  }
  return fold<RailroadNode, Box>(root, { n -> parts(n) }) { node, kids ->
    val room = budgets[node] ?: Double.POSITIVE_INFINITY
    when (node) {
      is RailroadNode.Terminal -> {
        val text = shown(node.value)
        leaf(Kind.Terminal, if (RE_SPACES.test(text)) "␣".repeat(text.length) else text, "", Style.Mono, m)
      }
      is RailroadNode.Nonterminal -> leaf(Kind.NonTerminal, shown(node.name), node.name, 0, m)
      is RailroadNode.Special -> leaf(Kind.Special, "? " + shown(node.text) + " ?", "", Style.Italic, m)
      is RailroadNode.Sequence -> {
        var width = maxOf(0, kids.size - 1) * m.gap
        var up = 0.0
        var down = 0.0
        for (kid in kids) {
          width += kid.width
          up = maxOf(up, kid.up)
          down = maxOf(down, kid.down)
        }
        val sequence = Box(Kind.Sequence, width, up, down, kids, "", "", 0.0)
        if (width <= room || kids.size < 2) return@fold sequence
        val rows = ArrayList<Box>()
        var row = ArrayList<Box>()
        var rowW = 0.0
        val flush = {
          var up = 0.0
          var down = 0.0
          for (kid in row) {
            up = maxOf(up, kid.up)
            down = maxOf(down, kid.down)
          }
          rows.add(Box(Kind.Sequence, rowW, up, down, row, "", "", 0.0))
          row = ArrayList()
          rowW = 0.0
        }
        for (kid in kids) {
          if (row.size > 0 && rowW + m.gap + kid.width > maxOf(40.0, room - 6 * m.arc)) flush()
          rowW += (if (row.size > 0) m.gap else 0.0) + kid.width
          row.add(kid)
        }
        flush()
        if (rows.size < 2) return@fold sequence
        var foldedWidth = 0.0
        var offset = 0.0
        for (k in rows.indices) {
          foldedWidth = maxOf(foldedWidth, rows[k].width)
          if (k != 0) offset += rows[k - 1].down + maxOf(m.sep, 2 * m.arc) + rows[k].up
        }
        Box(Kind.Folded, foldedWidth + 6 * m.arc, rows[0].up, offset + rows[rows.size - 1].down, rows, "", "", 0.0)
      }
      is RailroadNode.Choice -> {
        var inner = 0.0
        var offset = 0.0
        for (k in kids.indices) {
          inner = maxOf(inner, kids[k].width)
          if (k > 0) offset = below(offset, kids[k - 1], kids[k], m)
        }
        val last = kids.getOrNull(kids.size - 1)
        Box(
          Kind.Choice,
          inner + 4 * m.arc,
          if (kids.size > 0) kids[0].up else 0.0,
          if (last != null) offset + last.down else 0.0,
          kids,
          "",
          "",
          0.0,
        )
      }
      is RailroadNode.Optional -> bypass(kids[0], m)
      is RailroadNode.Repetition -> {
        val min = node.min
        val max = node.max
        val kid = kids[0]
        if (min == 1.0 && max == 1.0) return@fold kid
        val text = count(min, max)
        val reach = maxOf(kid.down + m.sep, 2 * m.arc)
        val label = if (text == "") 0.0 else even(m.measurer.width(text, COUNT_SIZE, 0))
        val loop = Box(
          Kind.Loop,
          maxOf(kid.width, label) + 2 * m.arc,
          kid.up,
          reach + (if (text == "") ARROW / 2 + 1 else COUNT_SIZE + 6),
          listOf(kid),
          text,
          "",
          reach,
        )
        if (min == 0.0) bypass(loop, m) else loop
      }
    }
  }
}

fun renderRailroad(model: RailroadModel, config: Config, options: RenderOptions): Rendered {
  return tighten(options) { tight -> draw(model, config, options, tight) }
}

private fun draw(model: RailroadModel, config: Config, options: RenderOptions, tight: Boolean): Rendered {
  val base = options.fontSize ?: 16.0
  val fontSize = option(config, "fontSize", 0.0, 200.0)
  val size = if (truthy(fontSize)) fontSize else jsRound(base * 0.875)
  val headSize = if (truthy(fontSize)) fontSize else base
  val padding = option(config, "padding", -1.0, 200.0)
  val lineHeight = jsRound(size * 1.5)
  val m = Metrics(
    measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono),
    size = size,
    padding = if (padding < 0) 12.0 else padding,
    height = even(lineHeight + (if (padding < 0) 9.0 else 2 * padding)),
    gap = minOf(option(config, "horizontalSeparation", 16.0, 500.0), if (tight) 12.0 else Double.POSITIVE_INFINITY),
    sep = option(config, "verticalSeparation", 10.0, 500.0),
    arc = minOf(option(config, "arcRadius", 10.0, 200.0), if (tight) 6.0 else Double.POSITIVE_INFINITY),
  )
  val radius = if ((config["railroad"] as? Map<*, *>)?.get("showMarkers") == false) 0.0 else option(config, "markerRadius", 4.0, 50.0)
  val strokeWidth = option(config, "strokeWidth", 0.0, 20.0)
  val stroke = if (strokeWidth > 0) " stroke-width=\"${num(strokeWidth)}\"" else ""
  val pad = options.padding ?: 8.0
  val arc = m.arc

  val A = num(arc)
  val turn = { sweep: Int, dx: String, dy: String -> "a$A,$A 0 0 $sweep $dx$A,$dy$A" }
  val eastToSouth = turn(1, "", "")
  val southToEast = turn(0, "", "")
  val eastToNorth = turn(0, "", "-")
  val northToEast = turn(1, "", "-")
  val loopDown = turn(0, "-", "")
  val loopUp = turn(0, "-", "-")

  val title = layoutLabel(model.title, false, m.measurer, base, if (tight) titleRoom(options) else 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 16 else 0.0
  val headHeight = jsRound(headSize * 1.5)
  val textShift = size * 0.35
  val boxTop = m.height / 2

  var track = ""
  var arrows = ""
  var nodes = ""

  val line = { x1: Double, y: Double, x2: Double ->
    if (x2 > x1) track += "M${num(x1)},${num(y)}H${num(x2)}"
  }

  val place = { root: Box, left: Double, baseline: Double ->
    val boxes = ArrayList<Box>()
    boxes.add(root)
    val at = ArrayList<Double>()
    at.add(left)
    at.add(baseline)
    while (boxes.size > 0) {
      val box = boxes.removeAt(boxes.size - 1)
      val y = at.removeAt(at.size - 1)
      val x = at.removeAt(at.size - 1)
      val kids = box.kids
      val right = x + box.width
      when (box.kind) {
        Kind.Sequence -> {
          var kx = right
          for (i in kids.size - 1 downTo 0) {
            kx -= kids[i].width
            boxes.add(kids[i])
            at.add(kx)
            at.add(y)
            if (i > 0) line(kx - m.gap, y, kx)
            kx -= m.gap
          }
        }
        Kind.Folded -> {
          val start = x + 2 * arc
          val from = boxes.size
          var offset = 0.0
          line(x, y, start)
          for (i in kids.indices) {
            val kid = kids[i]
            val ky = y + offset
            boxes.add(kid)
            at.add(ky)
            at.add(start)
            if (i + 1 < kids.size) {
              val next = kids[i + 1]
              val nextOffset = offset + kid.down + maxOf(m.sep, 2 * arc) + next.up
              val mid = y + offset + kid.down + maxOf(m.sep, 2 * arc) / 2
              track += "M${num(start + kid.width)},${num(ky)}H${num(right - 2 * arc)}V${num(mid)}H${num(x + arc)}V${num(y + nextOffset)}H${num(start)}"
              arrows += "M${num(start - ARROW)},${num(y + nextOffset - ARROW / 2)}l${jsStr(ARROW)},${jsStr(ARROW / 2)}l${jsStr(-ARROW)},${jsStr(ARROW / 2)}z"
              offset = nextOffset
            } else {
              track += "M${num(start + kid.width)},${num(ky)}H${num(right - arc)}V${num(y)}H${num(right)}"
            }
          }
          reverse(boxes, from)
          reverse(at, 2 * from)
        }
        Kind.Choice -> {
          val inner = box.width - 4 * arc
          val railIn = x + arc
          val railOut = right - arc
          val from = boxes.size
          var offset = 0.0
          for (i in kids.indices) {
            val kid = kids[i]
            if (i > 0) offset = below(offset, kids[i - 1], kid, m)
            val ky = y + offset
            val kx = x + 2 * arc + (inner - kid.width) / 2
            val last = i == kids.size - 1
            if (i == 0) {
              line(x, y, kx)
              line(kx + kid.width, y, right)
            } else {
              track +=
                (if (last) "M${num(x)},${num(y)}${eastToSouth}V${num(ky - arc)}" else "M${num(railIn)},${num(ky - arc)}") +
                southToEast +
                (if (kx > railIn + arc) "H${num(kx)}" else "") +
                "M${num(kx + kid.width)},${num(ky)}" +
                (if (kx > railIn + arc) "H${num(railOut - arc)}" else "") +
                eastToNorth +
                (if (last) "V${num(y + arc)}$northToEast" else "")
            }
            boxes.add(kid)
            at.add(ky)
            at.add(kx)
          }
          // Reversed, so that the branches come off the stack top to bottom.
          reverse(boxes, from)
          reverse(at, 2 * from)
        }
        Kind.Bypass -> {
          val kid = kids[0]
          val kx = x + 2 * arc
          line(x, y, kx)
          line(kx + kid.width, y, right)
          track +=
            "M${num(x)},${num(y)}${eastToNorth}V${num(y - box.reach + arc)}${northToEast}H${num(right - 2 * arc)}" +
            "${eastToSouth}V${num(y - arc)}$southToEast"
          boxes.add(kid)
          at.add(kx)
          at.add(y)
        }
        Kind.Loop -> {
          val kid = kids[0]
          val kx = x + (box.width - kid.width) / 2
          val loopY = y + box.reach
          val middle = x + box.width / 2
          line(x, y, kx)
          line(kx + kid.width, y, right)
          track +=
            "M${num(x + arc)},${num(y)}${loopDown}V${num(loopY - arc)}${southToEast}H${num(right - arc)}" +
            "${eastToNorth}V${num(y + arc)}$loopUp"
          arrows += "M${num(middle - ARROW / 2)},${num(loopY)}l${jsStr(ARROW)},${jsStr(-ARROW / 2)}v${jsStr(ARROW)}z"
          if (box.text != "") {
            nodes += "<text class=\"pele-edge-label pele-count\" x=\"${num(middle)}\" y=\"${num(loopY + COUNT_SIZE + 3)}\" font-size=\"${jsStr(COUNT_SIZE)}\" fill=\"var(--_m)\">${esc(box.text)}</text>"
          }
          boxes.add(kid)
          at.add(kx)
          at.add(y)
        }
        else -> {
          val text = box.text
          val space = if (text.startsWith(" ") || text.endsWith(" ") || text.contains("  ")) " xml:space=\"preserve\"" else ""
          val shape = "<rect x=\"${num(x)}\" y=\"${num(y - boxTop)}\" width=\"${num(box.width)}\" height=\"${num(m.height)}\""
          val label = "<text x=\"${num(x + box.width / 2)}\" y=\"${num(y + textShift)}\"$space"
          if (box.kind == Kind.Terminal) {
            nodes += "<g class=\"pele-node pele-terminal\">$shape rx=\"${num(boxTop)}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"$stroke/>$label font-family=\"$FONT_MONO\">${esc(text)}</text></g>"
          } else if (box.kind == Kind.NonTerminal) {
            nodes += "<g class=\"pele-node pele-nonterminal\" data-id=\"${escText(box.id)}\">$shape fill=\"var(--_bg)\" stroke=\"var(--_b)\"$stroke$ROUNDED/>$label>${esc(text)}</text></g>"
          } else {
            nodes +=
              "<g class=\"pele-node pele-special\">$shape fill=\"var(--_bg)\" stroke=\"var(--_b)\" stroke-dasharray=\"4 3\"$stroke$ROUNDED/>" +
              "$label font-style=\"italic\"><tspan fill=\"var(--_m)\">? </tspan>${esc(text.jsSlice(2, -2))}<tspan fill=\"var(--_m)\"> ?</tspan></text></g>"
          }
        }
      }
    }
  }

  var rules = ""
  var width = title.width
  var top = pad + titleHeight
  var bottom = pad + title.height
  for (rule in model.rules) {
    val box = measure(rule.definition, m, if (tight) maxOf(80.0, (options.maxWidth ?: 320.0) - 2 * pad - 4 * radius - 2 * LEAD - 1) else Double.POSITIVE_INFINITY)
    val name = shown(rule.name)
    val baseline = top + headHeight + HEAD_GAP + box.up
    val start = pad + 2 * radius
    val left = start + LEAD
    val end = left + box.width + LEAD
    track = ""
    arrows = ""
    nodes = ""
    line(start, baseline, left)
    line(left + box.width, baseline, end)
    place(box, left, baseline)
    val markers =
      if (radius > 0) {
        "<circle class=\"pele-marker pele-start\" cx=\"${num(pad + radius)}\" cy=\"${num(baseline)}\" r=\"${num(radius)}\" fill=\"var(--_l)\"/>" +
          "<circle class=\"pele-marker pele-end\" cx=\"${num(end + radius)}\" cy=\"${num(baseline)}\" r=\"${num(radius)}\" fill=\"var(--_l)\"/>"
      } else {
        ""
      }
    rules +=
      "<g class=\"pele-rule\" data-id=\"${escText(rule.name)}\">" +
      "<text class=\"pele-rule-name\" x=\"${num(pad)}\" y=\"${num(top + headHeight / 2 + headSize * 0.35)}\" font-size=\"${num(headSize)}\" font-weight=\"var(--_hw)\" text-anchor=\"start\">${esc(name)}</text>" +
      "<path class=\"pele-edge pele-track\" d=\"$track\" fill=\"none\" stroke=\"var(--_l)\"$stroke/>" +
      (if (arrows != "") "<path class=\"pele-marker\" d=\"$arrows\" fill=\"var(--_l)\"/>" else "") +
      markers +
      nodes +
      "</g>"
    width = maxOf(width, end + 2 * radius - pad, m.measurer.width(name, headSize, Style.Bold))
    bottom = baseline + box.down
    top = bottom + m.sep + RULE_GAP
  }

  val totalWidth = ceil(width + 2 * pad)
  val totalHeight = ceil(bottom + pad)
  val content =
    labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (rules != "") "<g class=\"pele-rules\" transform=\"translate(0.5,0.5)\" font-size=\"${num(size)}\" text-anchor=\"middle\">$rules</g>" else "")

  return Rendered(
    svg = svgDocument("railroad", totalWidth, totalHeight, base, options, model, content),
    width = totalWidth,
    height = totalHeight,
    links = ArrayList(),
  )
}
