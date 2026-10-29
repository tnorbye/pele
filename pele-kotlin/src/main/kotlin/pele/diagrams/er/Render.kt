// Ported from Pele src/diagrams/er/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.Config
import pele.IconResolver
import pele.LinkInfo
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.parseGenericTypes
import pele.diagrams.common.runsAcross
import pele.diagrams.common.turnToFit
import pele.js.JsRegExp
import pele.js.cmp
import pele.js.jsHypot
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsStr
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.LayeredOptions
import pele.layout.cnode
import pele.layout.compoundLayout
import pele.layout.direction
import pele.layout.shiftLayout
import pele.svg.Crossings
import pele.svg.Fill
import pele.svg.ON_DARK
import pele.svg.ON_LIGHT
import pele.svg.ROUNDED
import pele.svg.ResolvedStyle
import pele.svg.casing
import pele.svg.casingsSvg
import pele.svg.classNames
import pele.svg.clusterTitleX
import pele.svg.edgeInk
import pele.svg.edgeLabelSvg
import pele.svg.esc
import pele.svg.escText
import pele.svg.fillUnder
import pele.svg.hollowOn
import pele.svg.ink
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.markCrossings
import pele.svg.num
import pele.svg.paints
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.routePath
import pele.svg.setsFill
import pele.svg.sharedStyle
import pele.svg.spanStyle
import pele.svg.svgDocument
import pele.svg.textStyle
import pele.svg.titleLabel
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.hasMath
import pele.text.labelLine
import pele.text.layoutLabel
import pele.text.lineMiddle
import pele.util.internalLink
import pele.util.relAttr
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

private const val NODE_SEP = 48.0
private const val EDGE_SEP = 20.0
private const val RANK_SEP = 64.0
private const val GROUP_PAD = 20.0
private const val LOOP = 28.0
private const val LOOP_BAND = 38.0
private const val MIN_WIDTH = 96.0
private const val BOX_PAD_X = 18.0
private const val BOX_PAD_Y = 12.0
private const val HEAD_PAD_Y = 9.0
private const val CELL_PAD_X = 12.0
private const val CELL_GAP = 16.0
private const val ROW_PAD = 9.0
private const val COMMENT_WRAP = 260.0
private const val NO_WRAP = 1e6
private val NO_LOOPS: List<Loop> = emptyList()
private const val PORT_GAP = 18.0
private const val PORT_MARGIN = 14.0
private const val BOX_ATTRS = " fill=\"var(--_s)\" stroke=\"var(--_b)\""

private val RE_FONT_FAMILY = JsRegExp("""[";]font-family:""")
private val RE_LT = JsRegExp("<", "g")
private val RE_GT = JsRegExp(">", "g")
private val RE_DEFAULT = JsRegExp("""^default\s?""")
private val RE_INTERNAL_LINK = JsRegExp(""" internal-link(?: |$)""")

private fun numberOption(config: Config, key: String, fallback: Double): Double {
  val value = (config["er"] as? Map<*, *>)?.get(key)
  return if (value is Double && value > 0) min(value, 2000.0) else fallback
}

// Type, name, keys, comment.
private class Cells(val type: Label, val name: Label, val keys: Label, val comment: Label) {
  operator fun get(k: Int): Label = when (k) {
    0 -> type
    1 -> name
    2 -> keys
    else -> comment
  }
}

private class Row(
  val cells: Cells,
  val h: Double,
)

private class NodeView(
  val node: ErGraphNode,
  val style: ResolvedStyle,
  val label: Label,
  var w: Double,
  var h: Double,
  var headH: Double,
  val rows: MutableList<Row>,
  // Left edge of each column, measured from the left edge of the box.
  val cols: MutableList<Double>,
  val rowSize: Double,
  val keySize: Double,
  val mono: Int,
  // Whether self-relationships loop out of the bottom side rather than the right.
  val below: Boolean,
)

// A relationship from an entity to itself, with the stretch of the side it loops out of.
private class Loop(
  val label: Label,
  var band: Double,
  var at: Double,
)

// Writes a measured label starting at x, with the baseline of its first line at y.
private fun textLeft(label: Label, x: Double, yIn: Double, attrs: String, icons: IconResolver?, mono: Int = 0): String {
  var y = yIn
  val lines = label.lines
  if (lines.isEmpty()) return ""
  val only = if (lines.size == 1 && lines[0].size == 1) lines[0][0] else null
  // The short form collapses repeated spaces in monospace text.
  if (only != null && only.icon == null && only.math == null && only.style == mono && !(mono != 0 && only.text.contains("  "))) {
    return "<text${textStyle(attrs, mono)} x=\"${num(x)}\" y=\"${num(y)}\">${esc(only.text)}</text>"
  }
  val shared = sharedStyle(label)
  val shift = label.size * 0.35
  val top = y - shift - label.lineHeight / 2
  var body = ""
  var extra = ""
  for (i in lines.indices) {
    val line = lines[i]
    val middle = top + lineMiddle(label, i)
    if (hasMath(line)) {
      extra += labelSvg(labelLine(label, i), x + label.widths[i] / 2, middle, attrs, icons)
      continue
    }
    y = middle + shift
    var hasIcon = false
    for (span in line) if (span.icon != null) hasIcon = true
    var sx = x
    var placed = false
    for (span in line) {
      if (span.icon != null) {
        val side = label.size * 1.1
        extra += "<svg class=\"pele-icon\" data-icon=\"${escText(span.icon)}\" x=\"${num(sx)}\" y=\"${
          num(y - label.size * 0.35 - side / 2)
        }\" width=\"${num(side)}\" height=\"${num(side)}\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">${
          icons?.invoke(span.icon) ?: ""
        }</svg>"
      } else if (span.text != "") {
        val pos = if (hasIcon || !placed) " x=\"${num(sx)}\" y=\"${num(y)}\"" else ""
        body += "<tspan$pos${spanStyle(span.style and shared.inv())}>${esc(span.text)}</tspan>"
        placed = true
      }
      sx += span.width
    }
  }
  return "<text${textStyle(attrs, shared)} xml:space=\"preserve\">$body</text>$extra"
}

// Mermaid reads `~` pairs as angle brackets. They are written as entities so the label parser
// does not take `<int>` for a tag.
private fun cellText(raw: String): String {
  val converted = parseGenericTypes(raw)
  return if (converted == raw) raw else converted.jsReplace(RE_LT, "&lt;").jsReplace(RE_GT, "&gt;")
}

fun renderEr(db: ErDb, config: Config, options: RenderOptions): Rendered {
  if (!runsAcross(db.direction)) return draw(db, config, options, false)
  return turnToFit(options) { down -> draw(db, config, options, down) }
}

// `turned` draws a diagram that runs across as one that runs down.
private fun draw(db: ErDb, config: Config, options: RenderOptions, turned: Boolean): Rendered {
  val graph = buildErGraph(db)
  val monospaced = options.monospace?.contains("attributes") == true
  val size = options.fontSize ?: 16.0
  val smallSize = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = options.icons

  val index = LinkedHashMap<String, Int>()
  val views = ArrayList<NodeView>()
  val cnodes = ArrayList<CNode>()
  graph.nodes.forEachIndexed { i, node -> index[node.id] = i }

  val cedges = ArrayList<CEdge>()
  val edgeLabels = ArrayList<Label>()
  val drawn = ArrayList<ErGraphEdge>()
  val loops = LinkedHashMap<Int, MutableList<Loop>>()
  val loopOf = ArrayList<Loop?>()
  for (edge in graph.edges) {
    val src = index[edge.start]
    val dst = index[edge.end]
    if (src == null || dst == null) continue
    val label = layoutLabel(edge.label, true, measurer, smallSize, 200.0)
    var loop: Loop? = null
    if (src == dst) {
      loop = Loop(label, 0.0, 0.0)
      val list = loops[src]
      if (list != null) list.add(loop) else loops[src] = arrayListOf(loop)
    }
    loopOf.add(loop)
    drawn.add(edge)
    edgeLabels.add(label)
    cedges.add(
      CEdge(
        src = src,
        dst = dst,
        minlen = 1.0,
        labelW = if (label.width > 0) label.width + 12 else 0.0,
        labelH = if (label.height > 0) label.height + 4 else 0.0,
        route = ArrayList(),
        labelX = 0.0,
        labelY = 0.0,
      ),
    )
  }

  // The direction of the level a node is laid out in: its nearest subgraph that sets one, or the diagram's.
  fun horizontal(i: Int): Boolean {
    var at = i
    // Subgraphs can name each other as members, so the walk up is bounded.
    for (steps in 0 until graph.nodes.size) {
      val parent = graph.nodes[at].parentId
      val up = if (parent == null) null else index[parent]
      if (up == null) break
      val dir = graph.nodes[up].dir
      if (!dir.isNullOrEmpty()) return dir == "LR" || dir == "RL"
      at = up
    }
    return db.direction == "LR" || db.direction == "RL"
  }

  for (i in graph.nodes.indices) {
    val node = graph.nodes[i]
    val style = resolveStyle(
      if (node.cssStyles.isNotEmpty()) node.cssCompiledStyles + node.cssStyles else node.cssCompiledStyles,
    )
    val base = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val mono = if (monospaced && !RE_FONT_FAMILY.test(style.text)) Style.Mono else 0
    val nameSize = style.fontSize ?: size
    val rowSize = style.fontSize ?: smallSize
    val keySize = style.fontSize ?: jsRound(size * 0.75)
    val label = if (node.isGroup) {
      layoutLabel(node.label, true, measurer, nameSize, 4000.0, base)
    } else {
      layoutLabel(if (node.alias.isNotEmpty()) node.alias else node.label, true, measurer, nameSize, 240.0, base)
    }
    val own = loops[i] ?: NO_LOOPS
    // Loops leave by a side the layout keeps free of other edges: the right when ranks run down,
    // the bottom when they run across.
    val below = own.size > 0 && !node.isGroup && horizontal(i)
    val view = NodeView(node, style, label, 0.0, 0.0, 0.0, ArrayList(), ArrayList(), rowSize, keySize, mono, below)
    var reach = 0.0
    var bands = 0.0
    for (loop in own) {
      loop.band = max(LOOP_BAND, if (below) loop.label.width + 12 else loop.label.height + 6)
      bands += loop.band
      reach = max(reach, if (below) loop.label.height else loop.label.width)
    }
    val minW = max(MIN_WIDTH, if (below) bands + 8 else 0.0)
    val c: CNode
    if (node.isGroup) {
      c = cnode(0.0, 0.0)
      c.isGroup = true
      c.dir = if (!node.dir.isNullOrEmpty()) direction(node.dir) else null
      c.padX = GROUP_PAD
      c.padTop = if (view.label.height > 0) view.label.height + 16 else GROUP_PAD
      c.padBottom = GROUP_PAD
      c.minW = max(view.label.width + 2 * GROUP_PAD - 12, MIN_WIDTH)
    } else {
      val nameH = max(label.height, jsRound(nameSize * 1.5))
      if (node.attributes.isEmpty()) {
        view.w = max(label.width + 2 * BOX_PAD_X, minW)
        view.h = max(nameH + 2 * BOX_PAD_Y, if (below) 0.0 else bands + 8)
      } else {
        val rowLine = jsRound(rowSize * 1.5)
        val widths = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        for (attribute in node.attributes) {
          val cells = Cells(
            layoutLabel(cellText(attribute.type), true, measurer, rowSize, NO_WRAP, base or mono),
            layoutLabel(cellText(attribute.name), true, measurer, rowSize, NO_WRAP, base or mono),
            layoutLabel(attribute.keys.joinToString(", "), false, measurer, keySize, NO_WRAP, base or mono),
            layoutLabel(cellText(attribute.comment), true, measurer, rowSize, COMMENT_WRAP, base),
          )
          var h = rowLine
          for (k in 0 until 4) {
            if (cells[k].width > widths[k]) widths[k] = cells[k].width
            if (cells[k].height > h) h = cells[k].height
          }
          view.rows.add(Row(cells, h + ROW_PAD))
          view.h += h + ROW_PAD
        }
        var columns = 0
        var inner = 0.0
        for (w in widths) {
          if (w == 0.0) continue
          inner += w + (if (columns > 0) CELL_GAP else 0.0)
          columns++
        }
        view.w = max(max(inner + 2 * CELL_PAD_X, label.width + 2 * BOX_PAD_X), minW)
        // A box wider than its table shares the spare width between the columns.
        val spare = if (columns > 0) (view.w - inner - 2 * CELL_PAD_X) / columns else 0.0
        var x = CELL_PAD_X
        for (w in widths) {
          view.cols.add(x)
          if (w > 0) x += w + spare + CELL_GAP
        }
        view.headH = nameH + 2 * HEAD_PAD_Y
        view.h += view.headH
      }
      // The layout keeps a node centered in its box, so room for loops is added on both sides.
      val room = if (own.size > 0) 2 * (MARKER_LENGTH + LOOP * 0.75 + 10 + reach) else 0.0
      c = if (below) cnode(view.w, view.h + room) else cnode(view.w + room, view.h)
    }
    c.seq = views.size.toDouble()
    views.add(view)
    cnodes.add(c)
  }
  graph.nodes.forEachIndexed { i, node ->
    cnodes[i].parent = if (node.parentId != null) (index[node.parentId] ?: -1) else -1
  }

  val layout = compoundLayout(
    cnodes,
    cedges,
    if (turned) "TB" else direction(db.direction),
    LayeredOptions(
      nodeSep = numberOption(config, "nodeSpacing", NODE_SEP),
      edgeSep = EDGE_SEP,
      rankSep = numberOption(config, "rankSpacing", RANK_SEP),
      portSep = 24.0,
    ),
  )

  spreadEnds(cedges, cnodes, views)

  // A subgraph gets its size from the layout, so its loops hang outside it and may widen the diagram.
  var layoutWidth = layout.width
  for ((i, own) in loops) {
    val c = cnodes[i]
    if (!c.isGroup) continue
    var reach = 0.0
    for (loop in own) reach = max(reach, loop.label.width)
    layoutWidth = max(layoutWidth, c.x + c.w / 2 + MARKER_LENGTH + LOOP * 0.75 + 10 + reach)
  }

  val title = titleLabel(db.title, measurer, size, 4000.0)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = max(layoutWidth, title.width)
  val ox = pad + (inner - layoutWidth) / 2
  val oy = pad + titleHeight
  shiftLayout(cnodes, cedges, ox, oy)
  val width = ceil(inner + 2 * pad)
  val height = ceil(layout.height + titleHeight + 2 * pad)
  val links = ArrayList<LinkInfo>()

  val crossings = Crossings()
  var anyGroup = false
  for (c in cnodes) if (c.isGroup) anyGroup = true
  if (anyGroup) {
    for (e in cedges) markCrossings(crossings, e.route)
  }

  var clusters = ""
  val fills = ArrayList<Fill>()
  var nodesOut = ""
  for (i in views.indices) {
    val view = views[i]
    val c = cnodes[i]
    val node = view.node
    val classes = classNames(node.cssClasses.jsReplace(RE_DEFAULT, ""))
    if (c.isGroup) {
      view.w = c.w
      view.h = c.h
      val x = c.x - c.w / 2
      val y = c.y - c.h / 2
      val titleX = clusterTitleX(c, view.label.width, crossings, GROUP_PAD)
      if (paints(view.style.shape)) fills.add(Fill(doubleArrayOf(x, y, x + c.w, y + c.h), view.style.shape))
      clusters +=
        "<g class=\"pele-cluster$classes\" data-id=\"${escText(node.id)}\">" +
        "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(c.w)}\" height=\"${num(c.h)}\" fill=\"var(--_a)\"${if (setsFill(view.style.shape)) "" else " fill-opacity=\"0.5\""} stroke=\"var(--_b)\"${rounded(view.style.shape)}/>" +
        labelSvg(view.label, titleX, y + 8 + view.label.height / 2, " class=\"pele-cluster-label\"${inkFill(view.style, "var(--_m)")}${view.style.text}", icons) +
        "</g>"
      continue
    }

    val w = view.w
    val h = view.h
    val x0 = -w / 2
    val y0 = -h / 2
    val text = view.style.text
    val color = inkFill(view.style)
    val muted = if (color.isNotEmpty()) color else " fill=\"var(--_m)\""
    val shape = "x=\"${num(x0)}\" y=\"${num(y0)}\" width=\"${num(w)}\" height=\"${num(h)}\""
    var name = labelSvg(view.label, 0.0, if (view.rows.size > 0) y0 + view.headH / 2 else 0.0, " class=\"pele-label\"$color$text", icons)
    val display = decodeEntities(if (node.alias.isNotEmpty()) node.alias else node.label)
    if (RE_INTERNAL_LINK.test("$classes ") && name != "" && options.links != false) {
      // The name ends up in an href, so it gets the same check as a URL.
      val safe = internalLink(display, options)
      val target = esc(display)
      name = "<a class=\"internal-link\"${if (safe) " href=\"$target\"${relAttr(options)}" else ""} data-href=\"$target\">$name</a>"
      links.add(LinkInfo(node.label, display, true))
    }

    val body: String
    if (view.rows.size == 0) {
      body = "<rect $shape${rounded(BOX_ATTRS + view.style.shape)}/>" + name
    } else {
      val rowLine = jsRound(view.rowSize * 1.5)
      val lineColor = if (!view.style.stroke.isNullOrEmpty()) esc(view.style.stroke) else "var(--_b)"
      var stripes = ""
      var cells = ""
      var y = y0 + view.headH
      for (r in view.rows.indices) {
        val row = view.rows[r]
        if (r % 2 == 0) {
          val span = "x=\"${num(x0)}\" width=\"${num(w)}\""
          if (r == view.rows.size - 1) {
            // The last row follows the box's rounded bottom corners: a rounded rect, squared off on top.
            stripes +=
              "<rect $span y=\"${num(y)}\" height=\"${num(row.h)}\"$ROUNDED/>" +
              "<rect $span y=\"${num(y)}\" height=\"${num(row.h / 2)}\"/>"
          } else {
            stripes += "<rect $span y=\"${num(y)}\" height=\"${num(row.h)}\"/>"
          }
        }
        val baseline = y + ROW_PAD / 2 + rowLine / 2 + view.rowSize * 0.35
        val type = row.cells.type
        val attribute = row.cells.name
        val keys = row.cells.keys
        val comment = row.cells.comment
        cells +=
          textLeft(type, x0 + view.cols[0], baseline, " class=\"pele-er-type\"$muted$text", icons, view.mono) +
          textLeft(attribute, x0 + view.cols[1], baseline, " class=\"pele-er-name\"$color$text", icons, view.mono) +
          textLeft(keys, x0 + view.cols[2], baseline, " class=\"pele-er-keys\" font-size=\"${jsStr(view.keySize)}\"$muted$text", icons, view.mono) +
          textLeft(comment, x0 + view.cols[3], baseline, " class=\"pele-er-comment\"$muted$text", icons)
        y += row.h
      }
      val dividerY = num(y0 + view.headH)
      // Keep row shading light on light fills and dark on dark fills to preserve text contrast.
      val tone = ink(view.style.shape)
      val shade = if (tone == ON_LIGHT) ON_DARK else if (tone == ON_DARK) ON_LIGHT else "var(--_bg)"
      body =
        "<rect $shape${rounded(BOX_ATTRS + view.style.shape)}/>" +
        // Over a fill the diagram chose, the shading is a tint of that fill. The opacity is on the
        // group because the two parts of the last row overlap.
        "<g class=\"pele-er-stripes\" fill=\"$shade\"${if (view.style.shape.contains("fill:")) " opacity=\"0.6\"" else ""}>$stripes</g>" +
        "<path class=\"pele-er-divider\" d=\"M${num(x0)},${dividerY}H${num(x0 + w)}\" stroke=\"$lineColor\"/>" +
        "<rect $shape fill=\"none\" stroke=\"var(--_b)\"${rounded(view.style.line)}/>" +
        name +
        "<g class=\"pele-er-attributes\" font-size=\"${jsStr(view.rowSize)}\">$cells</g>"
    }
    nodesOut += "<g class=\"pele-node pele-entity$classes\" data-id=\"${escText(node.label)}\" transform=\"translate(${
      num(c.x)
    },${num(c.y)})\">$body</g>"
  }

  // Self-relationships share the side they loop out of, squeezed together if it is too short for them.
  for ((i, own) in loops) {
    val view = views[i]
    var total = 0.0
    for (loop in own) total += loop.band
    val scale = min(1.0, max((if (view.below) view.w else view.h) - 8, 0.0) / total)
    var at = (-total * scale) / 2
    for (loop in own) {
      loop.band *= scale
      loop.at = at + loop.band / 2
      at += loop.band
    }
  }

  var edgesOut = ""
  var casings = ""
  var labelsOut = ""
  for (i in cedges.indices) {
    val ce = cedges[i]
    val edge = drawn[i]
    val label = edgeLabels[i]
    val d: String
    var start: String
    var end: String
    val loop = loopOf[i]
    if (loop != null) {
      val c = cnodes[ce.src]
      val view = views[ce.src]
      val spread = max(min(loop.band / 2 - 6, 13.0), 1.0)
      val far = MARKER_LENGTH + LOOP * 0.75 + 6
      if (view.below) {
        val x = c.x + loop.at
        val y = c.y + view.h / 2
        val turn = num(y + MARKER_LENGTH)
        val out = num(y + MARKER_LENGTH + LOOP)
        d = "M${num(x - spread)},${num(y)}V$turn" + "C${num(x - spread)},$out ${num(x + spread)},$out ${num(x + spread)},$turn" + "V${num(y)}"
        start = erMarker(edge.arrowTypeStart, x - spread, y, 0.0, -1.0)
        end = erMarker(edge.arrowTypeEnd, x + spread, y, 0.0, -1.0)
        ce.labelX = x
        ce.labelY = y + far + label.height / 2
      } else {
        val x = c.x + view.w / 2
        val y = c.y + loop.at
        val turn = num(x + MARKER_LENGTH)
        val out = num(x + MARKER_LENGTH + LOOP)
        d = "M${num(x)},${num(y - spread)}H$turn" + "C$out,${num(y - spread)} $out,${num(y + spread)} $turn,${num(y + spread)}" + "H${num(x)}"
        start = erMarker(edge.arrowTypeStart, x, y - spread, -1.0, 0.0)
        end = erMarker(edge.arrowTypeEnd, x, y + spread, -1.0, 0.0)
        ce.labelX = x + far + label.width / 2
        ce.labelY = y
      }
    } else {
      val route = straighten(ce.route)
      if (route.size < 6) continue
      inset(route, 0, views[ce.src], cnodes[ce.src])
      inset(route, route.size - 3, views[ce.dst], cnodes[ce.dst])
      // A curve stops short of each box so that the marker sits on a straight run of line.
      val dx = abs(route[route.size - 3] - route[0])
      val dy = abs(route[route.size - 2] - route[1])
      val direct = route.size == 6 && (dx < 0.01 || dy < 0.01 || jsHypot(dx, dy) <= 3 * MARKER_LENGTH)
      val stub = if (direct) 0.0 else MARKER_LENGTH
      val path = routePath(route, null, stub, stub)
      d = if (stub > 0) "M${num(path.sx)},${num(path.sy)}L${path.d.jsSlice(1)}L${num(path.ex)},${num(path.ey)}" else path.d
      start = erMarker(edge.arrowTypeStart, path.sx, path.sy, path.sdx, path.sdy)
      end = erMarker(edge.arrowTypeEnd, path.ex, path.ey, path.edx, path.edy)
    }
    val under = fillUnder(d, DoubleArray(0), fills)
    val over: String? = if (under is Fill) edgeInk(under) else under as String?
    if (over == "") casings += casing(d, start + end)
    if (under is Fill && !over.isNullOrEmpty()) {
      start = hollowOn(start, under)
      end = hollowOn(end, under)
    }
    val dashed = edge.pattern == "dashed"
    edgesOut +=
      "<g class=\"pele-edge pele-er-${if (dashed) "non-identifying" else "identifying"}\" data-id=\"${escText(edge.id)}\"${if (!over.isNullOrEmpty()) " stroke=\"$over\"" else ""}>" +
      "<path d=\"$d\"${if (dashed) " stroke-dasharray=\"4 3\"" else ""}/>" +
      start +
      end +
      "</g>"
    if (label.width > 0) {
      val x = ce.labelX
      val y = ce.labelY
      labelsOut += edgeLabelSvg(edge.id, label, x, y, "", icons)
    }
  }

  val svg = svgDocument(
    "er",
    width,
    height,
    size,
    options,
    db,
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (clusters != "") "<g class=\"pele-clusters\">$clusters</g>" else "") +
      casingsSvg(casings) +
      (if (edgesOut != "") "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>" else "") +
      (if (labelsOut != "") "<g class=\"pele-edge-labels\" font-size=\"${jsStr(smallSize)}\">$labelsOut</g>" else "") +
      "<g class=\"pele-nodes\">$nodesOut</g>",
  )

  return Rendered(svg, width, height, links)
}

private fun straighten(route: List<Double>): MutableList<Double> {
  val out = ArrayList<Double>(route.subList(0, min(3, route.size)))
  var k = 3
  while (k < route.size) {
    val prev = out.size - 3
    val flat = { axis: Int ->
      abs(out[prev + axis] - route[k + axis]) < 0.01 && abs(route[k + axis] - route[k + 3 + axis]) < 0.01
    }
    if (k + 3 < route.size && (flat(0) || flat(1))) {
      k += 3
      continue
    }
    out.add(route[k])
    out.add(route[k + 1])
    out.add(route[k + 2])
    k += 3
  }
  return out
}

private class End(
  val route: MutableList<Double>,
  val at: Int,
  val toward: Double,
)

// The layout brings every edge that ends on one side of a box to the middle of that side, where
// their markers would cover each other. This spaces them along the side, in the order they fan out.
private fun spreadEnds(cedges: List<CEdge>, cnodes: List<CNode>, views: List<NodeView>) {
  val sides = LinkedHashMap<Int, MutableList<End>>()
  fun add(node: Int, route: MutableList<Double>, at: Int, next: Int) {
    val c = cnodes[node]
    val dx = route[at] - c.x
    val dy = route[at + 1] - c.y
    val side: Int
    if (abs(abs(dy) - c.h / 2) < 0.5) side = if (dy < 0) 0 else 2
    else if (abs(abs(dx) - c.w / 2) < 0.5) side = if (dx < 0) 3 else 1
    else return
    val key = node * 4 + side
    val end = End(route, at, route[next + (if ((side and 1) != 0) 1 else 0)])
    val list = sides[key]
    if (list != null) list.add(end) else sides[key] = arrayListOf(end)
  }
  for (e in cedges) {
    val last = e.route.size - 3
    if (e.src == e.dst || last < 3) continue
    add(e.src, e.route, 0, 3)
    add(e.dst, e.route, last, last - 3)
  }
  for ((key, ends) in sides) {
    if (ends.size < 2) continue
    val node = key shr 2
    val vertical = (key and 1) == 1
    val length = if (cnodes[node].isGroup) (if (vertical) cnodes[node].h else cnodes[node].w) else (if (vertical) views[node].h else views[node].w)
    val step = max(min(PORT_GAP, (length - 2 * PORT_MARGIN) / (ends.size - 1)), 0.0)
    val center = if (vertical) cnodes[node].y else cnodes[node].x
    ends.sortWith { a, b -> cmp(a.toward - b.toward) }
    for (i in ends.indices) {
      ends[i].route[ends[i].at + (if (vertical) 1 else 0)] = center + (i - (ends.size - 1).toDouble() / 2) * step
    }
  }
}

// An entity with a self-relationship is laid out larger than it is drawn; this moves a route end
// from the side of that larger box onto the entity's own side.
private fun inset(route: MutableList<Double>, at: Int, view: NodeView, c: CNode) {
  if (c.isGroup) return
  val dx = route[at] - c.x
  val dy = route[at + 1] - c.y
  if (c.w != view.w && abs(abs(dx) - c.w / 2) < 0.5) route[at] = route[at] - (sign(dx) * (c.w - view.w)) / 2
  if (c.h != view.h && abs(abs(dy) - c.h / 2) < 0.5) route[at + 1] = route[at + 1] - (sign(dy) * (c.h - view.h)) / 2
}
