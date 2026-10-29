// Ported from Pele src/diagrams/agentflow/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.runsAcross
import pele.diagrams.common.turnToFit
import pele.diagrams.flowchart.canonicalShape
import pele.js.JsRegExp
import pele.js.WeakIdentityMap
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsStr
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.Dir
import pele.layout.LayeredOptions
import pele.layout.cnode
import pele.layout.compoundLayout
import pele.layout.direction
import pele.layout.inheritCrossedDirections
import pele.layout.shiftLayout
import pele.svg.Crossings
import pele.svg.EdgePath
import pele.svg.Fill
import pele.svg.PAD_X
import pele.svg.PAD_Y
import pele.svg.ResolvedStyle
import pele.svg.ShapeView
import pele.svg.casingsSvg
import pele.svg.classNames
import pele.svg.clusterTitleX
import pele.svg.drawShape
import pele.svg.edgeCasing
import pele.svg.edgeLabelSvg
import pele.svg.esc
import pele.svg.escText
import pele.svg.inkFill
import pele.svg.insetRoute
import pele.svg.labelSvg
import pele.svg.loopPath
import pele.svg.markCrossings
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.overFills
import pele.svg.paints
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.routePath
import pele.svg.seriesColor
import pele.svg.setsFill
import pele.svg.shapeFilled
import pele.svg.shapeSize
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import pele.util.internalLink
import pele.util.linked
import pele.util.relAttr

// The number of series tokens, which is the palette Mermaid's slot rules are applied to.
const val PALETTE = 8

private const val NODE_SEP = 40.0
private const val EDGE_SEP = 16.0
private const val RANK_SEP = 48.0
private const val GROUP_PAD = 20.0
private const val LOOP = 26.0
private const val DOTS = 14.0
private const val COLLAPSED = "collapsed"

// The side a flow runs toward, with sides numbered clockwise from the top.
private val FLOW_END: Map<Dir, Int> = mapOf("TB" to 2, "RL" to 3, "BT" to 0, "LR" to 1)

private val graphs = WeakIdentityMap<AgentflowDb, AgentGraph>()

private val RE_DEFAULT_CLASS = JsRegExp("""^default\s?""")
private val RE_INTERNAL = JsRegExp(""" internal-link(?: |$)""")
private val RE_EDGE_CLASSES =
  JsRegExp("""edge-thickness-normal|edge-pattern-solid|flowchart-link""", "g")

// Builds the graph once per model. Building it also records the model's shape and containment
// diagnostics.
fun agentGraph(db: AgentflowDb, config: Config): AgentGraph {
  var graph = graphs[db]
  if (graph == null) {
    val curve = (config["flowchart"] as? Map<*, *>)?.get("curve")
    graph = buildAgentGraph(db, PALETTE, if (curve is String) curve else null)
    graphs[db] = graph
  }
  return graph
}

private fun numberOption(config: Config, key: String, fallback: Double): Double {
  val value = (config["agentflow"] as? Map<*, *>)?.get(key)
  return if (value is Double && value > 0) min(value, 2000.0) else fallback
}

// A palette color as an outline with a light wash of the same color inside.
// A fill the author set replaces the wash and stays opaque.
private fun tint(slot: Int, wash: String, style: ResolvedStyle): String {
  val color = seriesColor(slot)
  return " fill=\"$color\"${if (setsFill(style.shape)) "" else " fill-opacity=\"$wash\""} stroke=\"$color\""
}

private class NodeView(
  val node: GraphNode,
  val style: ResolvedStyle,
  val label: Label,
  override val shape: String,
  override val w: Double,
  override val h: Double,
  val dy: Double,
  var loops: Int,
) : ShapeView

private class Held(val edge: GraphEdge, val label: Label, val src: Int, val dst: Int)

fun renderAgentflow(db: AgentflowDb, config: Config, options: RenderOptions): Rendered {
  if (!runsAcross(db.direction)) return draw(db, config, options, false)
  return turnToFit(options) { down -> draw(db, config, options, down) }
}

// `turned` draws a diagram that runs across as one that runs down.
private fun draw(
  db: AgentflowDb,
  config: Config,
  options: RenderOptions,
  turned: Boolean,
): Rendered {
  val graph = agentGraph(db, config)
  val size = options.fontSize ?: 16.0
  val edgeSize = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val wrapWidth = numberOption(config, "wrappingWidth", 120.0)
  val minWidth = numberOption(config, "minNodeWidth", 120.0)
  val pad = options.padding ?: 8.0
  val icons = options.icons

  val index = HashMap<String, Int>()
  val views = ArrayList<NodeView>()
  val cnodes = ArrayList<CNode>()
  graph.nodes.forEachIndexed { i, node -> index[node.id] = i }

  for (node in graph.nodes) {
    val styles =
      if (node.cssStyles.isNotEmpty()) node.cssCompiledStyles + node.cssStyles
      else node.cssCompiledStyles
    val style = resolveStyle(styles)
    val textStyle = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val markdown = node.labelType == "markdown"
    val fontSize = style.fontSize ?: size
    val view: NodeView
    val c: CNode
    if (node.isGroup) {
      val label = layoutLabel(node.label, markdown, measurer, fontSize, 4000.0, textStyle)
      view = NodeView(node, style, label, "group", 0.0, 0.0, 0.0, 0)
      c = cnode(0.0, 0.0)
      c.isGroup = true
      c.dir = if (!node.dir.isNullOrEmpty()) direction(node.dir) else null
      c.padX = GROUP_PAD
      c.padTop = if (label.height > 0) label.height + 16 else GROUP_PAD
      c.padBottom = GROUP_PAD
      c.minW = label.width + 2 * GROUP_PAD - 12
    } else if (node.shape == "collapsedGroup") {
      val label = layoutLabel(node.label, markdown, measurer, fontSize, wrapWidth, textStyle)
      val w = max(label.width, minWidth) + 2 * PAD_X
      val h = label.height + 2 * PAD_Y + DOTS
      view = NodeView(node, style, label, COLLAPSED, w, h, -DOTS / 2, 0)
      c = cnode(w, h)
    } else {
      val shape = canonicalShape(node.shape)
      val label = layoutLabel(node.label, markdown, measurer, fontSize, wrapWidth, textStyle)
      // A diamond grows twice as fast as its label, so it keeps the label's own width.
      val s =
        shapeSize(
          shape,
          if (shape == "diam") label.width else max(label.width, minWidth),
          label.height,
        )
      view = NodeView(node, style, label, shape, s.w, s.h, s.dy, 0)
      c = cnode(s.w, s.h)
    }
    c.seq = views.size.toDouble()
    views.add(view)
    cnodes.add(c)
  }
  graph.nodes.forEachIndexed { i, node ->
    cnodes[i].parent = if (node.parentId != null) (index[node.parentId] ?: -1) else -1
  }
  inheritCrossedDirections(
    cnodes,
    graph.edges.map { edge -> Pair(index[edge.start] ?: -1, index[edge.end] ?: -1) },
  )

  val rootDir = if (turned) "TB" else direction(db.direction)
  // The direction of the level a node sits in.
  val flowOf = { i: Int ->
    var dir: Dir? = null
    var p = cnodes[i].parent
    var hops = 0
    while (p >= 0 && dir == null && hops < 10000) {
      dir = cnodes[p].dir
      p = cnodes[p].parent
      hops++
    }
    dir ?: rootDir
  }
  val flowsSideways = { i: Int ->
    val dir = flowOf(i)
    dir == "LR" || dir == "RL"
  }

  val depth = IntArray(cnodes.size) { -1 }
  for (i in cnodes.indices) {
    val chain = ArrayList<Int>()
    var j = i
    while (j >= 0 && depth[j] < 0 && chain.size <= cnodes.size) {
      chain.add(j)
      j = cnodes[j].parent
    }
    var d = if (j >= 0) depth[j] + 1 else 0
    for (k in chain.size - 1 downTo 0) depth[chain[k]] = d++
  }
  val contains = { group: Int, i: Int ->
    var j = i
    var d = depth[i]
    while (d > depth[group]) {
      j = cnodes[j].parent
      d--
    }
    j == group && i != group
  }

  val cedges = ArrayList<CEdge>()
  val edgeLabels = ArrayList<Label>()
  val drawn = ArrayList<GraphEdge>()
  // Edges between a node and a container around it. The layout has no place for them, so they
  // are drawn afterwards, between the node and the container's border.
  val held = ArrayList<Held>()
  for (edge in graph.edges) {
    val src = index[edge.start]
    val dst = index[edge.end]
    if (src == null || dst == null) continue
    val label = layoutLabel(edge.label, edge.labelType == "markdown", measurer, edgeSize, wrapWidth)
    if (contains(src, dst) || contains(dst, src)) {
      held.add(Held(edge, label, src, dst))
      continue
    }
    if (src == dst) {
      // A self-loop sits beside the node, across the flow, and the node's box grows to hold it.
      views[src].loops++
      if (flowsSideways(src))
        cnodes[src].h += 2 * (LOOP + (if (label.height > 0) label.height + 8 else 0.0))
      else cnodes[src].w += 2 * (LOOP + (if (label.width > 0) label.width + 12 else 0.0))
    }
    drawn.add(edge)
    edgeLabels.add(label)
    cedges.add(
      CEdge(
        src = src,
        dst = dst,
        minlen = edge.minlen ?: 1.0,
        labelW = if (label.width > 0) label.width + 12 else 0.0,
        labelH = if (label.height > 0) label.height + 4 else 0.0,
        route = ArrayList(),
        labelX = 0.0,
        labelY = 0.0,
      )
    )
  }

  val layout =
    compoundLayout(
      cnodes,
      cedges,
      rootDir,
      LayeredOptions(
        nodeSep = numberOption(config, "nodeSpacing", NODE_SEP),
        edgeSep = EDGE_SEP,
        rankSep = numberOption(config, "rankSpacing", RANK_SEP),
        portSep = 20.0,
      ),
    )

  val title = layoutLabel(db.title, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = max(layout.width, title.width)
  val ox = pad + (inner - layout.width) / 2
  val oy = pad + titleHeight
  shiftLayout(cnodes, cedges, ox, oy)
  val width = ceil(inner + 2 * pad)
  val height = ceil(layout.height + titleHeight + 2 * pad)
  val links = ArrayList<pele.LinkInfo>()

  // An edge between a node and a container around it runs straight between the node and the
  // container's border: out along the flow, or in against it. If another node is in the way it
  // takes a side instead, and failing that the far end.
  val heldPaths = ArrayList<EdgePath>()
  val heldLabels = ArrayList<Double>()
  val heldRoutes = ArrayList<List<Double>>()
  val careful = held.size.toDouble() * cnodes.size < 2e6
  for (h in held) {
    val edge = h.edge
    val src = h.src
    val dst = h.dst
    val out = contains(dst, src)
    val outer = if (out) dst else src
    val i = if (out) src else dst
    val g = cnodes[outer]
    val n = cnodes[i]
    val end = FLOW_END[g.dir ?: flowOf(outer)]!!
    val first = if (out) end else (end + 2) and 3
    val toward = { side: Int ->
      val upright = (side and 1) == 0
      val sign = if (side == 0 || side == 3) -1.0 else 1.0
      val nx = if (upright) n.x else n.x + (sign * n.w) / 2
      val ny = if (upright) n.y + (sign * n.h) / 2 else n.y
      val bx = if (upright) n.x else g.x + (sign * g.w) / 2
      val by = if (upright) g.y + (sign * g.h) / 2 else n.y
      val axis = if (upright) 0.0 else 1.0
      if (out) listOf(nx, ny, axis, bx, by, axis) else listOf(bx, by, axis, nx, ny, axis)
    }
    val blocked = { r: List<Double> ->
      var result = false
      for (k in cnodes.indices) {
        val c = cnodes[k]
        if (
          k != i &&
            !c.isGroup &&
            c.x + c.w / 2 > min(r[0], r[3]) - 1 &&
            c.x - c.w / 2 < max(r[0], r[3]) + 1 &&
            c.y + c.h / 2 > min(r[1], r[4]) - 1 &&
            c.y - c.h / 2 < max(r[1], r[4]) + 1
        ) {
          result = true
          break
        }
      }
      result
    }
    var route = toward(first)
    if (careful && blocked(route)) {
      for (side in listOf((first + 1) and 3, (first + 3) and 3, (first + 2) and 3)) {
        val other = toward(side)
        if (!blocked(other)) {
          route = other
          break
        }
      }
    }
    heldRoutes.add(route)
    heldLabels.add((route[0] + route[3]) / 2)
    heldLabels.add((route[1] + route[4]) / 2)
    val drawnRoute = ArrayList(route)
    insetRoute(drawnRoute, if (out) 0 else 3, views[i], n)
    heldPaths.add(routePath(drawnRoute, "linear", 0.0, markerTrim(edge.arrowTypeEnd)))
  }

  val crossings = Crossings()
  var anyGroup = false
  for (c in cnodes) if (c.isGroup) anyGroup = true
  if (anyGroup) {
    for (e in cedges) markCrossings(crossings, e.route)
    for (route in heldRoutes) markCrossings(crossings, route)
  }

  val clusters = StringBuilder()
  val nodesOut = StringBuilder()
  val fills = ArrayList<Fill>()
  for (i in views.indices) {
    val view = views[i]
    val c = cnodes[i]
    val node = view.node
    val x = c.x
    val y = c.y
    val classes = classNames(node.cssClasses.jsReplace(RE_DEFAULT_CLASS, ""))
    val id = escText(node.id)
    if (c.isGroup) {
      if (paints(view.style.shape))
        fills.add(
          Fill(doubleArrayOf(x - c.w / 2, y - c.h / 2, x + c.w / 2, y + c.h / 2), view.style.shape)
        )
      val titleX = clusterTitleX(c, view.label.width, crossings, GROUP_PAD)
      clusters.append(
        "<g class=\"pele-cluster pele-flow$classes\" data-id=\"$id\">" +
          "<rect x=\"${num(x - c.w / 2)}\" y=\"${num(y - c.h / 2)}\" width=\"${num(c.w)}\" height=\"${num(c.h)}\"${rounded(
            tint(node.colorIndex ?: KIND_SLOT.size, "0.06", view.style) + view.style.shape
          )}/>" +
          labelSvg(
            view.label,
            titleX,
            y - c.h / 2 + 8 + view.label.height / 2,
            " class=\"pele-cluster-label\"${inkFill(view.style, "var(--_m)")}${view.style.text}",
            icons,
          ) +
          "</g>"
      )
      continue
    }

    val w = view.w
    val h = view.h
    var kind = ""
    val shape: String
    if (view.shape == COLLAPSED) {
      val color = seriesColor(node.colorIndex ?: KIND_SLOT.size)
      val line = h / 2 - DOTS - 2
      val dots = line + (DOTS + 2) / 2
      shape =
        "<rect x=\"${num(-w / 2)}\" y=\"${num(-h / 2)}\" width=\"${num(w)}\" height=\"${num(h)}\"${rounded(
          tint(node.colorIndex ?: KIND_SLOT.size, "0.06", view.style) + view.style.shape
        )}/>" +
          "<path d=\"M${num(-w / 2 + 10)},${num(line)}H${num(w / 2 - 10)}\" fill=\"none\" stroke=\"$color\" stroke-dasharray=\"3 3\"${view.style.line}/>" +
          "<path class=\"pele-collapsed-mark\" d=\"${dot(-9.0, dots)}${dot(0.0, dots)}${dot(9.0, dots)}\" fill=\"$color\" stroke=\"none\"/>"
    } else {
      val slot = KIND_SLOT[node.kind ?: "task"] ?: 1
      kind = " pele-kind-${node.kind ?: "task"}"
      shape =
        drawShape(
          view.shape,
          w,
          h,
          tint(slot, "0.14", view.style) + view.style.shape,
          " stroke=\"${seriesColor(slot)}\"" + view.style.line,
        )
    }

    val ink = if (view.shape == COLLAPSED || shapeFilled(view.shape)) inkFill(view.style) else ""
    var text =
      labelSvg(view.label, 0.0, view.dy, " class=\"pele-label\"$ink${view.style.text}", icons)
    val internal = RE_INTERNAL.test("$classes ")
    val nodeLabel = node.label
    if (internal && !nodeLabel.isNullOrEmpty() && options.links != false) {
      // The label is a note name, but it still ends up in an href, so it gets the same check as a
      // URL.
      val name = decodeEntities(nodeLabel)
      val safe = internalLink(name, options)
      val target = esc(name)
      text =
        "<a class=\"internal-link\"${if (safe) " href=\"$target\"${relAttr(options)}" else ""} data-href=\"$target\">$text</a>"
      links.add(pele.LinkInfo(node.id, name, true))
    }
    var body = shape + text
    val tooltip = node.tooltip
    if (!tooltip.isNullOrEmpty()) body = "<title>${escText(tooltip)}</title>" + body
    val link = node.link
    if (!link.isNullOrEmpty()) {
      body =
        linked(
          body,
          link,
          node.id,
          links,
          options,
          if (!node.linkTarget.isNullOrEmpty()) " target=\"${esc(node.linkTarget!!)}\"" else "",
        )
    }
    nodesOut.append(
      "<g class=\"pele-node pele-shape-${view.shape}$kind$classes\" data-id=\"$id\" transform=\"translate(${num(
        x
      )},${num(y)})\">$body</g>"
    )
  }

  val casings = StringBuilder()
  val edgesOut = StringBuilder()
  val labelsOut = StringBuilder()
  val put = { edge: GraphEdge, label: Label, path: EdgePath, x: Double, y: Double ->
    val style =
      if (edge.style.isNotEmpty() || edge.cssCompiledStyles.isNotEmpty())
        resolveStyle(edge.cssCompiledStyles + edge.style)
      else null
    val endType = edge.arrowTypeEnd
    val over =
      overFills(path.d, doubleArrayOf(path.ex, path.ey), fills, style?.line ?: "", path.tokens)
    val stroke = style?.stroke
    val color =
      if (!over.isNullOrEmpty()) over else if (!stroke.isNullOrEmpty()) esc(stroke) else "var(--_l)"
    val kind =
      if (edge.thickness == "dotted") "reference"
      else if (endType == "arrow_cross") "failure" else "sequence"
    val classes = classNames(edge.classes.jsReplace(RE_EDGE_CLASSES, ""))
    if (over == "") casings.append(edgeCasing(path, "none", endType, style?.line ?: ""))
    edgesOut.append(
      "<g class=\"pele-edge pele-edge-$kind$classes\" data-id=\"${escText(edge.id)}\"${if (!over.isNullOrEmpty()) " stroke=\"$over\"" else ""}${style?.line ?: ""}>" +
        "<path d=\"${path.d}\"${if (edge.thickness == "dotted") " stroke-dasharray=\"3 4\"" else ""}/>" +
        marker(endType, path.ex, path.ey, path.edx, path.edy, color) +
        "</g>"
    )
    if (label.width > 0) {
      labelsOut.append(edgeLabelSvg(edge.id, label, x, y, style?.text ?: "", icons))
    }
  }

  val loopSeen = HashMap<Int, Int>()
  for (i in cedges.indices) {
    val ce = cedges[i]
    val edge = drawn[i]
    val label = edgeLabels[i]
    val endType = edge.arrowTypeEnd
    val path: EdgePath
    if (ce.src == ce.dst) {
      val view = views[ce.src]
      val k = loopSeen[ce.src] ?: 0
      loopSeen[ce.src] = k + 1
      path =
        loopPath(
          cnodes[ce.src],
          view.w,
          view.h,
          k,
          flowsSideways(ce.src),
          LOOP,
          markerTrim(endType),
          ce,
        )
    } else {
      val route = ArrayList(ce.route)
      insetRoute(route, 0, views[ce.src], cnodes[ce.src])
      insetRoute(route, route.size - 3, views[ce.dst], cnodes[ce.dst])
      path = routePath(route, edge.curve, 0.0, markerTrim(endType))
    }
    put(edge, label, path, ce.labelX, ce.labelY)
  }
  held.forEachIndexed { i, h ->
    put(h.edge, h.label, heldPaths[i], heldLabels[2 * i], heldLabels[2 * i + 1])
  }

  val svg =
    svgDocument(
      "agentflow",
      width,
      height,
      size,
      options,
      db,
      labelSvg(
        title,
        width / 2,
        pad + title.height / 2,
        " class=\"pele-title\" font-weight=\"var(--_tw)\"",
      ) +
        (if (clusters.isNotEmpty()) "<g class=\"pele-clusters\">$clusters</g>" else "") +
        casingsSvg(casings.toString()) +
        (if (edgesOut.isNotEmpty())
          "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>"
        else "") +
        (if (labelsOut.isNotEmpty())
          "<g class=\"pele-edge-labels\" font-size=\"${jsStr(edgeSize)}\">$labelsOut</g>"
        else "") +
        "<g class=\"pele-nodes\">$nodesOut</g>",
    )

  return Rendered(svg, width, height, links)
}

private fun dot(x: Double, y: Double): String =
  "M${num(x - 1.75)},${num(y)}a1.75,1.75 0 1 0 3.5,0a1.75,1.75 0 1 0 -3.5,0Z"
