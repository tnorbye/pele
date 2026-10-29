// Ported from Pele src/diagrams/flowchart/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

import pele.Config
import pele.IconResolver
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.runsAcross
import pele.diagrams.common.tighten
import pele.diagrams.common.titleRoom
import pele.diagrams.common.turnToFit
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsStr
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.CompoundResult
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
import pele.svg.ResolvedStyle
import pele.svg.ShapeView
import pele.svg.casingsSvg
import pele.svg.classNames
import pele.svg.clusterTitleX
import pele.svg.drawShape
import pele.svg.edgeCasing
import pele.svg.edgeLabelSvg
import pele.svg.endSide
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
import pele.svg.setsFill
import pele.svg.shapeFilled
import pele.svg.shapeHasLabel
import pele.svg.shapeSize
import pele.svg.shapeSpan
import pele.svg.sideReach
import pele.svg.struckTitle
import pele.svg.svgDocument
import pele.svg.titleCrossed
import pele.svg.titleLabel
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import pele.util.imageUrl
import pele.util.internalLink
import pele.util.linked
import pele.util.relAttr
import pele.util.safeUrl
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val NODE_SEP = 40.0
private const val EDGE_SEP = 16.0
private const val RANK_SEP = 48.0
private const val GROUP_PAD = 20.0
private const val LOOP = 26.0
private const val SHAPE_ATTRS = " fill=\"var(--_s)\" stroke=\"var(--_b)\""
private const val LINE_ATTRS = " stroke=\"var(--_b)\""

private fun numberOption(section: Map<String, Any?>, key: String, fallback: Double): Double {
  val value = section[key]
  return if (value is Double && value > 0) min(value, 2000.0) else fallback
}

// A diagram type that is a flowchart with a different layout, which may draw its top-level groups itself.
interface FlowVariant {
  val type: String

  fun prepare(graph: FlowGraph)

  fun layout(nodes: List<CNode>, edges: List<CEdge>, dir: Dir, opt: LayeredOptions, tight: Boolean): CompoundResult

  val drawGroup: ((c: CNode, label: Label, style: ResolvedStyle, classes: String, id: String, dir: Dir, icons: IconResolver?) -> String)?
    get() = null

  // The part of a drawGroup group that takes its fill, as [left, top, right, bottom].
  val filledBox: ((c: CNode, dir: Dir) -> DoubleArray)?
    get() = null
}

private fun assetSize(value: Double?, fallback: Double): Double = if (value != null && value > 0 && value <= 4000) value else fallback

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

fun renderFlowchart(db: FlowDb, config: Config, options: RenderOptions, variant: FlowVariant? = null): Rendered {
  val fitted = { down: Boolean -> tighten(options) { tight -> draw(db, config, options, variant, down, tight) } }
  return if (runsAcross(db.direction)) turnToFit(options, fitted) else fitted(false)
}

private val RE_DEFAULT_CLASS = JsRegExp("""^default\s?""")
private val RE_INTERNAL = JsRegExp(""" internal-link(?: |$)""")
private val RE_EDGE_CLASSES = JsRegExp("""edge-thickness-normal|edge-pattern-solid|flowchart-link""", "g")
private val RE_STEP = JsRegExp("""^step""")

// `turned` draws a flowchart that runs across as one that runs down. `tight` sets nodes closer together.
private fun draw(db: FlowDb, config: Config, options: RenderOptions, variant: FlowVariant?, turned: Boolean, tight: Boolean): Rendered {
  val groupPad = if (tight) 12.0 else GROUP_PAD
  val own = if (variant != null) config[variant.type] else null
  @Suppress("UNCHECKED_CAST")
  val flow: Map<String, Any?> = (if (own is Map<*, *>) own else (config["flowchart"] as? Map<*, *>) ?: emptyMap<String, Any?>()) as Map<String, Any?>
  val flowCurve = flow["curve"]
  val graph = buildFlowGraph(db, if (flowCurve is String) flowCurve else null)
  variant?.prepare(graph)
  val size = options.fontSize ?: 16.0
  val edgeSize = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val wrapWidth = min(numberOption(flow, "wrappingWidth", 200.0), if (variant != null && tight) 100.0 else Double.POSITIVE_INFINITY)
  val pad = options.padding ?: 8.0
  val icons = options.icons

  val index = HashMap<String, Int>()
  val views = ArrayList<NodeView>()
  val cnodes = ArrayList<CNode>()
  graph.nodes.forEachIndexed { i, node -> index[node.id] = i }

  for (node in graph.nodes) {
    val style = resolveStyle(
      if (node.cssStyles.isNotEmpty()) node.cssCompiledStyles + node.cssStyles else node.cssCompiledStyles,
    )
    val textStyle = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val markdown = node.labelType == "markdown"
    val fontSize = style.fontSize ?: size
    val view: NodeView
    val c: CNode
    if (node.isGroup) {
      val label = layoutLabel(node.label, markdown, measurer, fontSize, if (variant != null && tight) max(80.0, titleRoom(options) / 2) else 4000.0, textStyle)
      view = NodeView(node, style, label, "group", 0.0, 0.0, 0.0, 0)
      c = cnode(0.0, 0.0)
      c.isGroup = true
      c.dir = if (!node.dir.isNullOrEmpty()) direction(node.dir) else null
      c.padX = groupPad
      c.padTop = if (label.height > 0) label.height + 16 else GROUP_PAD
      c.padBottom = groupPad
      c.minW = label.width + 2 * groupPad - 12
    } else {
      val shape = canonicalShape(node.shape)
      val text = if (!node.img.isNullOrEmpty() || !node.icon.isNullOrEmpty() || shapeHasLabel(shape)) node.label else null
      val label = layoutLabel(text, markdown, measurer, fontSize, wrapWidth, textStyle)
      val w: Double
      val h: Double
      var dy: Double
      if (!node.img.isNullOrEmpty() || !node.icon.isNullOrEmpty()) {
        val box = if (!node.img.isNullOrEmpty()) 80.0 else 48.0
        val aw = assetSize(node.assetWidth, box)
        val ah = assetSize(node.assetHeight, box)
        w = max(aw, label.width + 8)
        h = ah + (if (label.height > 0) label.height + 6 else 0.0)
        dy = if (node.pos == "t") -ah / 2 else ah / 2
        if (label.height == 0.0) dy = 0.0
      } else {
        val s = shapeSize(shape, label.width, label.height)
        w = s.w
        h = s.h
        dy = s.dy
      }
      view = NodeView(node, style, label, shape, w, h, dy, 0)
      c = cnode(w, h)
    }
    c.seq = views.size.toDouble()
    views.add(view)
    cnodes.add(c)
  }
  graph.nodes.forEachIndexed { i, node ->
    cnodes[i].parent = if (node.parentId != null) (index[node.parentId] ?: -1) else -1
  }
  inheritCrossedDirections(cnodes, graph.edges.map { edge -> Pair(index[edge.start] ?: -1, index[edge.end] ?: -1) })

  val rootDir = if (turned) "TB" else direction(db.direction)
  val flowsSideways = { i: Int ->
    var dir: Dir? = null
    var p = cnodes[i].parent
    var hops = 0
    while (p >= 0 && dir == null && hops < 10000) {
      dir = cnodes[p].dir
      p = cnodes[p].parent
      hops++
    }
    if (dir == null) dir = rootDir
    dir == "LR" || dir == "RL"
  }
  // The edges on one side of a node spread along it; a picture or an icon keeps them at its middle.
  views.forEachIndexed { i, view ->
    if (view.node.isGroup) cnodes[i].span = Double.POSITIVE_INFINITY
    else if (view.node.img.isNullOrEmpty() && view.node.icon.isNullOrEmpty()) cnodes[i].span = shapeSpan(view.shape, view.w, view.h, flowsSideways(i))
  }

  val cedges = ArrayList<CEdge>()
  val edgeLabels = ArrayList<Label>()
  val drawn = ArrayList<GraphEdge>()
  for (edge in graph.edges) {
    val src = index[edge.start]
    val dst = index[edge.end]
    if (src == null || dst == null) continue
    val label = layoutLabel(edge.label, edge.labelType == "markdown", measurer, edgeSize, wrapWidth)
    if (src == dst) {
      // A self-loop sits beside the node, across the flow, and the node's box grows to hold it.
      views[src].loops++
      if (flowsSideways(src)) cnodes[src].h += 2 * (LOOP + (if (label.height > 0) label.height + 8 else 0.0))
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
      ),
    )
  }

  val layoutOptions = LayeredOptions(
    nodeSep = numberOption(flow, "nodeSpacing", NODE_SEP) * (if (tight) 0.5 else 1.0),
    edgeSep = EDGE_SEP,
    rankSep = numberOption(flow, "rankSpacing", RANK_SEP) * (if (tight) 0.75 else 1.0),
    portSep = 20.0,
    tracks = flowCurve is String && RE_STEP.test(flowCurve),
    straight = flowCurve == "linear",
    headRoom = markerTrim("arrow_point"),
  )
  val layout = variant?.layout(cnodes, cedges, rootDir, layoutOptions, tight) ?: compoundLayout(cnodes, cedges, rootDir, layoutOptions)

  val title = titleLabel(db.title, measurer, size, if (tight) titleRoom(options) else 4000.0)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = max(layout.width, title.width)
  val ox = pad + (inner - layout.width) / 2
  val oy = pad + titleHeight
  shiftLayout(cnodes, cedges, ox, oy)
  val width = ceil(inner + 2 * pad)
  val height = ceil(layout.height + titleHeight + 2 * pad)
  val links = ArrayList<pele.LinkInfo>()

  val crossings = Crossings()
  var anyGroup = false
  for (c in cnodes) if (c.isGroup) anyGroup = true
  if (anyGroup) {
    for (e in cedges) markCrossings(crossings, e.route)
  }

  val clusters = StringBuilder()
  val struckTitles = StringBuilder()
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
    val drawGroup = variant?.drawGroup
    if (c.isGroup && drawGroup != null && c.parent < 0) {
      val filledBox = variant.filledBox
      if (paints(view.style.shape) && filledBox != null) fills.add(Fill(filledBox(c, rootDir), view.style.shape))
      clusters.append(drawGroup(c, view.label, view.style, classes, id, rootDir, icons))
      continue
    }
    if (c.isGroup) {
      if (paints(view.style.shape)) fills.add(Fill(doubleArrayOf(x - c.w / 2, y - c.h / 2, x + c.w / 2, y + c.h / 2), view.style.shape))
      val titleX = clusterTitleX(c, view.label.width, crossings, groupPad)
      val titleY = y - c.h / 2 + 8 + view.label.height / 2
      val struck = titleCrossed(c, view.label.width, titleX, crossings)
      // A title drawn over the edges sits on a halo in the theme's colors.
      val titleAttrs = " class=\"pele-cluster-label\"${if (struck) " fill=\"var(--_m)\"" else inkFill(view.style, "var(--_m)")}${view.style.text}"
      if (struck) struckTitles.append(struckTitle(view.label, titleX, titleY, titleAttrs, id, cnodes, i, icons))
      clusters.append(
        "<g class=\"pele-cluster$classes\" data-id=\"$id\">" +
          "<rect x=\"${num(x - c.w / 2)}\" y=\"${num(y - c.h / 2)}\" width=\"${num(c.w)}\" height=\"${num(c.h)}\" fill=\"var(--_a)\"${if (setsFill(view.style.shape)) "" else " fill-opacity=\"0.5\""} stroke=\"var(--_b)\"${rounded(view.style.shape)}/>" +
          (if (struck) "" else labelSvg(view.label, titleX, titleY, titleAttrs, icons)) +
          "</g>",
      )
      continue
    }

    val w = view.w
    val h = view.h
    val innerSvg: String
    var ink = ""
    val img = node.img
    val icon = node.icon
    if (!img.isNullOrEmpty()) {
      val aw = assetSize(node.assetWidth, 80.0)
      val ah = assetSize(node.assetHeight, 80.0)
      val top = if (view.label.height > 0) (if (node.pos == "t") h / 2 - ah else -h / 2) else -ah / 2
      val src = imageUrl(safeUrl(img), options)
      innerSvg =
        if (src == null) {
          ""
        } else {
          "<image href=\"${esc(src)}\" x=\"${num(-aw / 2)}\" y=\"${num(top)}\" width=\"${num(aw)}\" height=\"${num(ah)}\" preserveAspectRatio=\"${
            if (node.constraint == "on") "xMidYMid meet" else "none"
          }\"/>"
        }
    } else if (!icon.isNullOrEmpty()) {
      val box = assetSize(node.assetHeight, 48.0)
      val top = if (view.label.height > 0) (if (node.pos == "t") h / 2 - box else -h / 2) else -box / 2
      val frame =
        if (node.shape == "icon") {
          ""
        } else if (node.shape == "iconCircle") {
          "<circle cy=\"${num(top + box / 2)}\" r=\"${num(box / 2)}\"$SHAPE_ATTRS${view.style.shape}/>"
        } else {
          "<rect x=\"${num(-box / 2)}\" y=\"${num(top)}\" width=\"${num(box)}\" height=\"${num(box)}\" rx=\"${
            if (node.shape == "iconRounded") 8 else 0
          }\"$SHAPE_ATTRS${view.style.shape}/>"
        }
      val glyph = box * 0.6
      innerSvg =
        frame +
        "<svg class=\"pele-icon\" data-icon=\"${escText(icon)}\" x=\"${num(-glyph / 2)}\" y=\"${num(
          top + (box - glyph) / 2,
        )}\" width=\"${num(glyph)}\" height=\"${num(glyph)}\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">${
          icons?.invoke(icon) ?: ""
        }</svg>"
    } else {
      innerSvg = drawShape(view.shape, w, h, SHAPE_ATTRS + view.style.shape, LINE_ATTRS + view.style.line)
      if (shapeFilled(view.shape)) ink = inkFill(view.style)
    }

    var text = labelSvg(view.label, 0.0, view.dy, " class=\"pele-label\"$ink${view.style.text}", icons)
    val internal = RE_INTERNAL.test("$classes ")
    val nodeLabel = node.label
    if (internal && !nodeLabel.isNullOrEmpty() && options.links != false) {
      // The label is a note name, but it still ends up in an href, so it gets the same check as a URL.
      val name = decodeEntities(nodeLabel)
      val safe = internalLink(name, options)
      val target = esc(name)
      text = "<a class=\"internal-link\"${if (safe) " href=\"$target\"${relAttr(options)}" else ""} data-href=\"$target\">$text</a>"
      links.add(pele.LinkInfo(node.id, name, true))
    }
    var body = innerSvg + text
    if (!node.tooltip.isNullOrEmpty()) body = "<title>${escText(node.tooltip!!)}</title>" + body
    val link = node.link
    if (!link.isNullOrEmpty()) {
      body = linked(body, link, node.id, links, options, if (!node.linkTarget.isNullOrEmpty()) " target=\"${esc(node.linkTarget!!)}\"" else "")
    }
    nodesOut.append(
      "<g class=\"pele-node pele-shape-${view.shape}$classes\" data-id=\"$id\" transform=\"translate(${num(
        x,
      )},${num(y)})\">$body</g>",
    )
  }

  val reach = sideReach()
  for (i in cedges.indices) {
    val ce = cedges[i]
    if (ce.src == ce.dst || drawn[i].thickness == "invisible" || ce.route.size < 6) continue
    reach.add(ce.src, endSide(ce.route, 0, cnodes[ce.src]), drawn[i].arrowTypeStart)
    reach.add(ce.dst, endSide(ce.route, ce.route.size - 3, cnodes[ce.dst]), drawn[i].arrowTypeEnd)
  }

  val casings = StringBuilder()
  val edgesOut = StringBuilder()
  val labelsOut = StringBuilder()
  val loopSeen = HashMap<Int, Int>()
  for (i in cedges.indices) {
    val ce = cedges[i]
    val edge = drawn[i]
    val label = edgeLabels[i]
    val style = if (edge.style.isNotEmpty() || edge.cssCompiledStyles.isNotEmpty()) resolveStyle(edge.cssCompiledStyles + edge.style) else null
    if (edge.thickness != "invisible") {
      val startType = edge.arrowTypeStart
      val endType = edge.arrowTypeEnd
      val path: EdgePath
      if (ce.src == ce.dst) {
        val view = views[ce.src]
        val k = loopSeen[ce.src] ?: 0
        loopSeen[ce.src] = k + 1
        path = loopPath(cnodes[ce.src], view.w, view.h, k, flowsSideways(ce.src), LOOP, markerTrim(endType), ce)
      } else {
        val route = ArrayList(ce.route)
        val leaves = reach.of(ce.src, endSide(route, 0, cnodes[ce.src]))
        val arrives = reach.of(ce.dst, endSide(route, route.size - 3, cnodes[ce.dst]))
        insetRoute(route, 0, views[ce.src], cnodes[ce.src])
        insetRoute(route, route.size - 3, views[ce.dst], cnodes[ce.dst])
        path = routePath(route, edge.curve, markerTrim(startType), markerTrim(endType), leaves, arrives)
      }
      val over = overFills(path.d, doubleArrayOf(path.sx, path.sy, path.ex, path.ey), fills, style?.line ?: "", path.tokens)
      val stroke = style?.stroke
      val color = if (!over.isNullOrEmpty()) over else if (!stroke.isNullOrEmpty() && edge.thickness != "invisible") esc(stroke) else "var(--_l)"
      var attrs = ""
      if (edge.thickness == "thick") attrs += " stroke-width=\"2.5\""
      else if (edge.thickness == "dotted") attrs += " stroke-dasharray=\"3 4\""
      val classes = classNames(edge.classes.jsReplace(RE_EDGE_CLASSES, ""))
      if (over == "") casings.append(edgeCasing(path, startType, endType, style?.line ?: "", edge.thickness == "thick"))
      edgesOut.append(
        "<g class=\"pele-edge$classes\" data-id=\"${escText(edge.id)}\"${if (!over.isNullOrEmpty()) " stroke=\"$over\"" else ""}${style?.line ?: ""}>" +
          "<path d=\"${path.d}\"$attrs/>" +
          marker(startType, path.sx, path.sy, path.sdx, path.sdy, color) +
          marker(endType, path.ex, path.ey, path.edx, path.edy, color) +
          "</g>",
      )
    }
    if (label.width > 0) {
      val x = ce.labelX
      val y = ce.labelY
      labelsOut.append(edgeLabelSvg(edge.id, label, x, y, style?.text ?: "", icons))
    }
  }

  val svg = svgDocument(
    variant?.type ?: "flowchart",
    width,
    height,
    size,
    options,
    db,
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (clusters.isNotEmpty()) "<g class=\"pele-clusters\">$clusters</g>" else "") +
      casingsSvg(casings.toString()) +
      (if (edgesOut.isNotEmpty()) "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>" else "") +
      (if (struckTitles.isNotEmpty()) "<g class=\"pele-cluster-titles\">$struckTitles</g>" else "") +
      (if (labelsOut.isNotEmpty()) "<g class=\"pele-edge-labels\" font-size=\"${jsStr(edgeSize)}\">$labelsOut</g>" else "") +
      "<g class=\"pele-nodes\">$nodesOut</g>",
  )

  return Rendered(svg, width, height, links)
}
