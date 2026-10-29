// Ported from Pele src/diagrams/requirement/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.runsAcross
import pele.diagrams.common.turnToFit
import pele.js.JsRegExp
import pele.js.cmp
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsStr
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.LayeredOptions
import pele.layout.cnode
import pele.layout.compoundLayout
import pele.layout.direction
import pele.svg.EdgePath
import pele.svg.classNames
import pele.svg.edgeLabelSvg
import pele.svg.esc
import pele.svg.escText
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.ResolvedStyle
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.routePath
import pele.svg.sharedStyle
import pele.svg.spanStyle
import pele.svg.svgDocument
import pele.svg.textStyle
import pele.svg.titleLabel
import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.defaultMeasurer
import pele.text.hasMath
import pele.text.labelLine
import pele.text.layoutLabel
import pele.text.lineMiddle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val NODE_SEP = 40.0
private const val EDGE_SEP = 16.0
private const val RANK_SEP = 56.0
private const val PAD_X = 14.0
private const val PAD_Y = 10.0
private const val ROW_GAP = 2.0
private const val COLUMN_GAP = 12.0
private const val MIN_WIDTH = 120.0
private const val NAME_WRAP = 280.0
private const val VALUE_WRAP = 240.0
private const val LOOP = 26.0
private const val CONTAINS_RADIUS = 6.0
private const val ARROW_LENGTH = 9.0
private const val ARROW_HALF = 4.5
private const val END_STEP = 28.0
private const val END_MARGIN = 14.0

private val RE_DEFAULT = JsRegExp("""^default\s?""")

private class Row(
  val key: Label,
  val value: Label,
  val height: Double,
)

private class NodeView(
  val node: GraphNode,
  val style: ResolvedStyle,
  val kind: Label,
  val name: Label,
  val rows: List<Row>,
  val keyWidth: Double,
  val headHeight: Double,
  val w: Double,
  val h: Double,
)

// Adds bold or italic to a whole label after its markdown is read, since emphasis markers only
// toggle styles the label does not start with.
private fun restyle(label: Label, bits: Int, measurer: TextMeasurer): Label {
  if (bits == 0 || label.lines.isEmpty()) return label
  var width = 0.0
  val widths = ArrayList<Double>()
  val lines = label.lines.map { line ->
    var total = 0.0
    val spans = line.map { span ->
      val style = span.style or bits
      val w = if (span.icon != null || span.math != null) span.width else measurer.width(span.text, label.size, style)
      total += w
      span.copy(style = style, width = w)
    }
    widths.add(total)
    if (total > width) width = total
    spans.toMutableList()
  }
  return label.copy(lines = lines, widths = widths, width = width)
}

// Writes a measured label with its lines starting at x, the first line's box starting at top.
private fun startText(label: Label, x: Double, top: Double, attrs: String): String {
  val shared = sharedStyle(label)
  var body = ""
  var math = ""
  for (i in label.lines.indices) {
    val line = label.lines[i]
    val middle = top + lineMiddle(label, i)
    if (hasMath(line)) {
      math += labelSvg(labelLine(label, i), x + label.widths[i] / 2, middle, attrs)
      continue
    }
    val y = middle + label.size * 0.35
    var first = true
    for (span in line) {
      if (span.icon != null || span.text == "") continue
      body += "<tspan${if (first) " x=\"${num(x)}\" y=\"${num(y)}\"" else ""}${spanStyle(span.style and shared.inv())}>${esc(span.text)}</tspan>"
      first = false
    }
  }
  return (if (body == "") "" else "<text${textStyle(attrs, shared)} text-anchor=\"start\" xml:space=\"preserve\">$body</text>") + math
}

// The containment mark: a circled plus whose rim touches (x, y), where (dx, dy) points at the node.
private fun containsMarker(x: Double, y: Double, dx: Double, dy: Double): String {
  val r = CONTAINS_RADIUS
  val cx = x - dx * r
  val cy = y - dy * r
  return "<circle class=\"pele-marker\" cx=\"${num(cx)}\" cy=\"${num(cy)}\" r=\"${jsStr(r)}\"/>" +
    "<path class=\"pele-marker\" d=\"M${num(cx - dx * r)},${num(cy - dy * r)}L${num(cx + dx * r)},${num(cy + dy * r)}M${
      num(cx + dy * r)
    },${num(cy - dx * r)}L${num(cx - dy * r)},${num(cy + dx * r)}\"/>"
}

// An open arrowhead with its tip at (x, y), pointing along (dx, dy).
private fun arrowMarker(x: Double, y: Double, dx: Double, dy: Double): String {
  val bx = x - dx * ARROW_LENGTH
  val by = y - dy * ARROW_LENGTH
  return "<path class=\"pele-marker\" d=\"M${num(bx - dy * ARROW_HALF)},${num(by + dx * ARROW_HALF)}L${num(x)},${num(y)}L${
    num(bx + dy * ARROW_HALF)
  },${num(by - dx * ARROW_HALF)}\" stroke-linejoin=\"round\"/>"
}

private class End(
  val route: MutableList<Double>,
  val at: Int,
  val toward: Double,
)

// The layout attaches every edge to the middle of a node's side. Ends that share a side are
// spread along it, in the order of where they head, so their markers do not sit on each other.
private fun spreadEnds(cnodes: List<CNode>, cedges: List<CEdge>, views: List<NodeView>) {
  val sides = LinkedHashMap<Int, MutableList<End>>()
  for (edge in cedges) {
    val route = edge.route
    if (edge.src == edge.dst || route.size < 6) continue
    for (at in listOf(0, route.size - 3)) {
      val node = if (at == 0) edge.src else edge.dst
      val c = cnodes[node]
      val dx = route[at] - c.x
      val dy = route[at + 1] - c.y
      val next = if (at == 0) 3 else at - 3
      val side: Int
      if (abs(abs(dy) - c.h / 2) < 0.5) side = if (dy < 0) 0 else 2
      else if (abs(abs(dx) - c.w / 2) < 0.5) side = if (dx < 0) 3 else 1
      else continue
      val key = node * 4 + side
      val end = End(route, at, route[if (side % 2 == 0) next else next + 1])
      val list = sides[key]
      if (list != null) list.add(end) else sides[key] = arrayListOf(end)
    }
  }
  for ((key, list) in sides) {
    if (list.size < 2) continue
    val node = key shr 2
    val vertical = key % 2 == 1
    val span = (if (vertical) views[node].h else views[node].w) - 2 * END_MARGIN
    val step = max(0.0, min(END_STEP, span / (list.size - 1)))
    list.sortWith { a, b -> cmp(a.toward - b.toward) }
    for (j in list.indices) {
      val offset = (j - (list.size - 1).toDouble() / 2) * step
      if (vertical) list[j].route[list[j].at + 1] = cnodes[node].y + offset
      else list[j].route[list[j].at] = cnodes[node].x + offset
    }
  }
}

fun renderRequirement(model: RequirementModel, _config: Config, options: RenderOptions): Rendered {
  if (!runsAcross(model.direction)) return draw(model, _config, options, false)
  return turnToFit(options) { down -> draw(model, _config, options, down) }
}

// `turned` draws a diagram that runs across as one that runs down.
private fun draw(model: RequirementModel, _config: Config, options: RenderOptions, turned: Boolean): Rendered {
  val graph = buildRequirementGraph(model)
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val index = LinkedHashMap<String, Int>()
  val views = ArrayList<NodeView>()
  val cnodes = ArrayList<CNode>()
  for (node in graph.nodes) {
    val style = resolveStyle(node.cssStyles)
    val base = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val nameSize = style.fontSize ?: size
    val bodySize = style.fontSize ?: small
    val requirement = node.requirement
    val element = node.element
    val kind = restyle(layoutLabel("«${if (requirement != null) requirement.type else "Element"}»", false, measurer, bodySize, 4000.0), base, measurer)
    val name = restyle(layoutLabel(node.id, true, measurer, nameSize, NAME_WRAP), base or Style.Bold, measurer)
    val fields: List<Pair<String, String>> = if (requirement != null) {
      listOf(
        "ID" to requirement.requirementId,
        "Text" to requirement.text,
        "Risk" to requirement.risk,
        "Verification" to requirement.verifyMethod,
      )
    } else {
      listOf(
        "Type" to element!!.type,
        "Doc Ref" to element.docRef,
      )
    }
    val rows = ArrayList<Row>()
    var keyWidth = 0.0
    var valueWidth = 0.0
    var bodyHeight = 0.0
    for ((label, text) in fields) {
      if (text.isEmpty()) continue
      val key = restyle(layoutLabel(label, false, measurer, bodySize, 4000.0), base, measurer)
      val value = restyle(layoutLabel(text, true, measurer, bodySize, VALUE_WRAP), base, measurer)
      val height = max(key.height, value.height)
      rows.add(Row(key, value, height))
      keyWidth = max(keyWidth, key.width)
      valueWidth = max(valueWidth, value.width)
      bodyHeight += height + ROW_GAP
    }
    if (rows.size > 0) bodyHeight += 2 * PAD_Y - ROW_GAP
    val headHeight = 2 * PAD_Y + kind.height + name.height
    val w = max(
      max(
        MIN_WIDTH,
        max(kind.width, name.width) + 2 * PAD_X,
      ),
      if (rows.size > 0) keyWidth + COLUMN_GAP + valueWidth + 2 * PAD_X else 0.0,
    )
    // A requirement and an element may share a name; relationships then go to the requirement.
    if (!index.containsKey(node.id)) index[node.id] = views.size
    val c = cnode(w, headHeight + bodyHeight)
    c.seq = views.size.toDouble()
    views.add(NodeView(node, style, kind, name, rows, keyWidth, headHeight, w, headHeight + bodyHeight))
    cnodes.add(c)
  }

  val rootDir = if (turned) "TB" else direction(graph.direction)
  val sideways = rootDir == "LR" || rootDir == "RL"
  val cedges = ArrayList<CEdge>()
  val edgeLabels = ArrayList<Label>()
  val drawn = ArrayList<GraphEdge>()
  for (edge in graph.edges) {
    val src = if (edge.start == null) null else index[edge.start]
    val dst = if (edge.end == null) null else index[edge.end]
    if (src == null || dst == null) continue
    val label = layoutLabel("«${edge.type}»", false, measurer, small, 4000.0)
    if (src == dst) {
      // A self-loop sits beside the node, across the flow, and the node's box grows to hold it.
      if (sideways) cnodes[src].h += 2 * (LOOP + label.height + 8)
      else cnodes[src].w += 2 * (LOOP + label.width + 12)
    }
    drawn.add(edge)
    edgeLabels.add(label)
    cedges.add(
      CEdge(
        src = src,
        dst = dst,
        minlen = 1.0,
        labelW = label.width + 12,
        labelH = label.height + 4,
        route = ArrayList(),
        labelX = 0.0,
        labelY = 0.0,
      ),
    )
  }

  val layout = compoundLayout(
    cnodes,
    cedges,
    rootDir,
    LayeredOptions(
      nodeSep = NODE_SEP,
      edgeSep = EDGE_SEP,
      rankSep = RANK_SEP,
      portSep = 20.0,
    ),
  )

  spreadEnds(cnodes, cedges, views)

  val title = titleLabel(model.title, measurer, size, 4000.0)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = max(layout.width, title.width)
  val ox = pad + (inner - layout.width) / 2
  val oy = pad + titleHeight
  val width = ceil(inner + 2 * pad)
  val height = ceil(layout.height + titleHeight + 2 * pad)

  var nodesOut = ""
  for (i in views.indices) {
    val view = views[i]
    val w = view.w
    val h = view.h
    val style = view.style
    val color = inkFill(style)
    val muted = if (color.isNotEmpty()) color else " fill=\"var(--_m)\""
    val left = -w / 2
    val top = -h / 2
    var body =
      "<rect x=\"${num(left)}\" y=\"${num(top)}\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"${rounded(style.shape)}/>" +
      labelSvg(
        view.kind,
        0.0,
        top + PAD_Y + view.kind.height / 2,
        " class=\"pele-stereotype\" font-size=\"${jsStr(view.kind.size)}\"$muted${style.text}",
      ) +
      labelSvg(
        view.name,
        0.0,
        top + PAD_Y + view.kind.height + view.name.height / 2,
        " class=\"pele-label\" font-size=\"${jsStr(view.name.size)}\" font-weight=\"var(--_hw)\"$color${style.text}",
      )
    if (view.rows.size > 0) {
      val divider = top + view.headHeight
      body += "<path class=\"pele-divider\" d=\"M${num(left)},${num(divider)}H${num(-left)}\" stroke=\"var(--_b)\"${style.line}/>"
      var y = divider + PAD_Y
      var rows = ""
      for (row in view.rows) {
        rows +=
          startText(row.key, left + PAD_X, y, " class=\"pele-field\"$muted${style.text}") +
          startText(row.value, left + PAD_X + view.keyWidth + COLUMN_GAP, y, " class=\"pele-value\"$color${style.text}")
        y += row.height + ROW_GAP
      }
      body += "<g class=\"pele-fields\" font-size=\"${jsStr(view.rows[0].key.size)}\">$rows</g>"
    }
    nodesOut += "<g class=\"pele-node ${if (view.node.requirement != null) "pele-requirement" else "pele-element"}${
      classNames(view.node.cssClasses.jsReplace(RE_DEFAULT, ""))
    }\" data-id=\"${escText(view.node.id)}\" transform=\"translate(${num(cnodes[i].x + ox)},${num(cnodes[i].y + oy)})\">$body</g>"
  }

  var edgesOut = ""
  var labelsOut = ""
  val loopSeen = LinkedHashMap<Int, Int>()
  for (i in cedges.indices) {
    val ce = cedges[i]
    val edge = drawn[i]
    val label = edgeLabels[i]
    val startTrim = if (edge.contains) 2 * CONTAINS_RADIUS else 0.0
    val path: EdgePath
    var labelX = ce.labelX + ox
    var labelY = ce.labelY + oy
    if (ce.src == ce.dst) {
      val view = views[ce.src]
      val cx = cnodes[ce.src].x + ox
      val cy = cnodes[ce.src].y + oy
      val k = loopSeen[ce.src] ?: 0
      loopSeen[ce.src] = k + 1
      val half = (if (sideways) view.w else view.h) / 2
      val spread = min(half - 4, 12 + k * 6.0)
      val reach = LOOP + k * 8
      // Local frame: `out` points away from the node, `along` runs along its side.
      val at = { out: Double, along: Double ->
        if (sideways) "${num(cx + along)},${num(cy + view.h / 2 + out)}" else "${num(cx + view.w / 2 + out)},${num(cy + along)}"
      }
      // A cubic whose control points are this far out peaks at `reach`.
      val pull = reach / 0.75
      path = EdgePath(
        d = "M${at(startTrim, -spread)}C${at(pull, -spread)} ${at(pull, spread)} ${at(0.0, spread)}",
        sx = if (sideways) cx - spread else cx + view.w / 2,
        sy = if (sideways) cy + view.h / 2 else cy - spread,
        sdx = if (sideways) 0.0 else -1.0,
        sdy = if (sideways) -1.0 else 0.0,
        ex = if (sideways) cx + spread else cx + view.w / 2,
        ey = if (sideways) cy + view.h / 2 else cy + spread,
        edx = if (sideways) 0.0 else -1.0,
        edy = if (sideways) -1.0 else 0.0,
      )
      if (sideways) {
        labelX = cx
        labelY = cy + view.h / 2 + reach + 4 + ce.labelH / 2
      } else {
        labelX = cx + view.w / 2 + reach + 4 + ce.labelW / 2
        labelY = cy
      }
    } else {
      val route = ce.route.toMutableList()
      var k = 0
      while (k < route.size) {
        route[k] = route[k] + ox
        route[k + 1] = route[k + 1] + oy
        k += 3
      }
      path = routePath(route, null, startTrim, 0.0)
    }
    edgesOut +=
      "<g class=\"pele-edge pele-relationship-${if (edge.contains) "contains" else "arrow"}\" data-id=\"${escText(edge.id)}\">" +
      "<path d=\"${path.d}\"${if (edge.contains) "" else " stroke-dasharray=\"5 4\""}/>" +
      (if (edge.contains) containsMarker(path.sx, path.sy, path.sdx, path.sdy) else arrowMarker(path.ex, path.ey, path.edx, path.edy)) +
      "</g>"
    labelsOut += edgeLabelSvg(edge.id, label, labelX, labelY, "")
  }

  val svg = svgDocument(
    "requirement",
    width,
    height,
    size,
    options,
    model,
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (edgesOut != "") "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>" else "") +
      (if (labelsOut != "") "<g class=\"pele-edge-labels\" font-size=\"${jsStr(small)}\" fill=\"var(--_m)\">$labelsOut</g>" else "") +
      "<g class=\"pele-nodes\">$nodesOut</g>",
  )

  return Rendered(svg, width, height, ArrayList())
}
