// Ported from Pele src/diagrams/usecase/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import pele.Config
import pele.IconResolver
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.runsAcross
import pele.diagrams.common.turnToFit
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.cmp
import pele.js.jsHypot
import pele.js.jsMax
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsStr
import pele.js.truthy
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.LayeredOptions
import pele.layout.cnode
import pele.layout.compoundLayout
import pele.layout.direction
import pele.layout.shiftLayout
import pele.svg.Crossings
import pele.svg.EdgePath
import pele.svg.Fill
import pele.svg.ResolvedStyle
import pele.svg.casing
import pele.svg.casingsSvg
import pele.svg.classNames
import pele.svg.edgeInk
import pele.svg.edgeLabelSvg
import pele.svg.esc
import pele.svg.escText
import pele.svg.fillUnder
import pele.svg.hollowOn
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.markCrossings
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.paints
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.routePath
import pele.svg.setsFill
import pele.svg.svgDocument
import pele.svg.titleLabel
import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.defaultMeasurer
import pele.text.labelLine
import pele.text.layoutLabel
import pele.text.lineMiddle

private const val NODE_SEP = 32.0
private const val EDGE_SEP = 16.0
private const val RANK_SEP = 72.0
private const val GROUP_PAD = 20.0
private const val LOOP = 26.0
private const val FIGURE_W = 40.0
private const val FIGURE_H = 60.0
private const val FIGURE_GAP = 6.0
private const val TAB_PAD = 6.0
private const val CELL_PAD = 10.0
private const val ROW_PAD = 8.0
private const val FOLD = 9.0
private const val EXTENSION = 12.0
private const val PORT_GAP = 14.0
private const val SHAPE_ATTRS = " fill=\"var(--_s)\" stroke=\"var(--_b)\""

private object Kind {
  const val Actor = 0
  const val Ellipse = 1
  const val Box = 2
  const val Json = 3
  const val Boundary = 4
}

private class View(
  val node: UsecaseGraphNode,
  var kind: Int,
  val style: ResolvedStyle,
  var label: Label,
  val stereotype: Label,
  // The size of what is drawn; the layout box is larger when the node carries a self-relationship.
  var w: Double,
  var h: Double,
  val rows: MutableList<Pair<Label, Label>>,
  var keyW: Double,
  var headH: Double,
  var loops: Int,
)

private fun numberOption(config: Config, key: String, fallback: Double): Double {
  val value = (config["usecase"] as? Map<*, *>)?.get(key)
  return if (value is Double && value > 0) min(value, 2000.0) else fallback
}

private val RE_MARKUP = JsRegExp("""[&<>\\]""", "g")
private val RE_DEFAULT = JsRegExp("""^default\s?""")
private val RE_RELATIONSHIP = JsRegExp("""^default relationship relationship-\S+\s?""")

// A plain label is shown as written: no tags, no markdown, no `\n` breaks. Its markup characters
// are handed to the label parser as character references, which it turns back into characters.
private fun literal(text: String): String =
  text.jsReplace(RE_MARKUP) { m -> "&#${m.value.charCodeAt(0)};" }

private fun text(
  raw: String?,
  markdown: Boolean,
  measurer: TextMeasurer,
  size: Double,
  wrap: Double,
  base: Int = 0,
): Label =
  layoutLabel(
    if (!raw.isNullOrEmpty() && !markdown) literal(raw) else raw,
    markdown,
    measurer,
    size,
    wrap,
    base,
  )

// The point of a node's outline on one of its four sides (0 top, 1 right, 2 bottom, 3 left),
// `along` away from the middle of that side. Lines meet an actor at its figure, not at its label.
private fun anchor(view: View, c: CNode, side: Int, along: Double): Pair<Double, Double> {
  val sign = if (side == 1 || side == 2) 1.0 else -1.0
  val upright = side == 1 || side == 3
  var reach = (if (upright) view.w else view.h) / 2
  var middle = 0.0
  if (view.kind == Kind.Actor) {
    if (upright) {
      reach = FIGURE_W / 2 + 3
      middle = (FIGURE_H - view.h) / 2
    } else reach += 2
  } else if (view.kind == Kind.Ellipse) {
    val span = (if (upright) view.h else view.w) / 2
    reach *= sqrt(max(1 - StrictMath.pow(along / span, 2.0), 0.0))
  }
  return if (upright) Pair(c.x + sign * reach, c.y + middle + along)
  else Pair(c.x + along, c.y + sign * reach)
}

// How much of a side edges may spread over.
private fun sideLength(view: View, side: Int): Double {
  val upright = side == 1 || side == 3
  if (view.kind == Kind.Actor) return if (upright) FIGURE_H * 0.5 else FIGURE_W * 0.6
  val length = if (upright) view.h else view.w
  return if (view.kind == Kind.Ellipse) length * 0.6 else length - 16
}

private class End(
  val route: MutableList<Double>,
  val at: Int,
  val toward: Double,
)

// The layout brings every edge that ends on one side of a node to the middle of that side. This
// spaces them along the side in the order they fan out, and puts each on the node's outline.
private fun attachEnds(cedges: List<CEdge>, cnodes: List<CNode>, views: List<View>) {
  val sides = LinkedHashMap<Int, MutableList<End>>()
  fun add(index: Int, route: MutableList<Double>, at: Int, next: Int) {
    val c = cnodes[index]
    if (c.isGroup) return
    val dx = route[at] - c.x
    val dy = route[at + 1] - c.y
    val side: Int
    if (abs(abs(dy) - c.h / 2) < 0.5) side = if (dy < 0) 0 else 2
    else if (abs(abs(dx) - c.w / 2) < 0.5) side = if (dx < 0) 3 else 1 else return
    val key = index * 4 + side
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
    val index = key shr 2
    val side = key and 3
    val view = views[index]
    val step = if (ends.size > 1) min(PORT_GAP, sideLength(view, side) / (ends.size - 1)) else 0.0
    ends.sortWith { a, b -> cmp(a.toward - b.toward) }
    for (i in ends.indices) {
      val (x, y) = anchor(view, cnodes[index], side, (i - (ends.size - 1) / 2.0) * step)
      ends[i].route[ends[i].at] = x
      ends[i].route[ends[i].at + 1] = y
    }
  }
}

// The hollow triangle of a generalization, with its tip at (x, y).
private fun extension(x: Double, y: Double, dx: Double, dy: Double, color: String): String {
  val bx = x - dx * EXTENSION
  val by = y - dy * EXTENSION
  val half = EXTENSION * 0.5
  return "<path class=\"pele-marker\" d=\"M${num(x)},${num(y)}L${num(bx - dy * half)},${num(by + dx * half)}L${
    num(bx + dy * half)
  },${num(by - dx * half)}Z\" fill=\"var(--_bg)\" stroke=\"$color\" stroke-linejoin=\"round\"/>"
}

private fun trim(type: String): Double = if (type == "extension") EXTENSION else markerTrim(type)

private fun mark(
  type: String,
  x: Double,
  y: Double,
  dx: Double,
  dy: Double,
  color: String,
  stroke: String? = null,
): String =
  if (type == "extension") extension(x, y, dx, dy, color)
  else if (stroke == null) marker(type, x, y, dx, dy, color)
  else marker(type, x, y, dx, dy, color, stroke)

private fun figure(view: View, icons: IconResolver?): String {
  val node = view.node
  val shape = view.style.shape
  val line = " fill=\"none\" stroke=\"var(--_b)\"${view.style.line}"
  var head = -21.0
  var radius = 9.0
  var out: String
  if (node.actorType == "hollow") {
    radius = 8.0
    out =
      "<circle cy=\"-21\" r=\"8\" fill=\"var(--_bg)\" stroke=\"var(--_b)\"$shape/>" +
        "<path d=\"M-20,-9H20V0H5.5L20,15.5L12,25.5L0,12L-12,25.5L-20,15.5L-5.5,0H-20Z\" fill=\"var(--_bg)\" stroke=\"var(--_b)\" stroke-linejoin=\"round\"$shape/>"
  } else if (node.actorType == "awesome") {
    out =
      "<path d=\"M0,-30A11,11 0 1 1 0,-8A11,11 0 1 1 0,-30ZM-20,25C-20,9 -12,-3 0,-3C12,-3 20,9 20,25C20,28 18,30 15,30H-15C-18,30 -20,28 -20,25Z\" fill=\"var(--_b)\"$shape/>"
  } else if (node.actorType == "icon") {
    head = -14.0
    radius = 12.0
    out =
      "<rect x=\"-22\" y=\"-26\" width=\"44\" height=\"44\"${rounded(SHAPE_ATTRS + shape)}/>" +
        "<svg class=\"pele-icon\" data-icon=\"${escText(node.icon ?: "")}\" x=\"-14\" y=\"-18\" width=\"28\" height=\"28\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">${
        icons?.invoke(node.icon ?: "") ?: ""
      }</svg>"
  } else {
    out =
      "<circle cy=\"-21\" r=\"9\"$SHAPE_ATTRS$shape/><path d=\"M0,-12V10M-14,-3H14M0,10L-12,30M0,10L12,30\"$line/>"
  }
  if (node.business == true) {
    // The slash of a business actor: a chord across the lower right of the head.
    out +=
      "<path class=\"pele-business\" d=\"M${num(0.12 * radius)},${num(head + 0.99 * radius)}L${num(0.92 * radius)},${
      num(head - 0.39 * radius)
    }\"$line/>"
  }
  return out
}

fun renderUsecase(model: UsecaseModel, config: Config, options: RenderOptions): Rendered {
  if (!runsAcross(model.direction)) return draw(model, config, options, false)
  return turnToFit(options) { down -> draw(model, config, options, down) }
}

// `turned` draws a diagram that runs across as one that runs down.
private fun draw(
  model: UsecaseModel,
  config: Config,
  options: RenderOptions,
  turned: Boolean,
): Rendered {
  val graph = buildUsecaseGraph(model)
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val tiny = jsRound(size * 0.8125)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = options.icons
  val rootDir = if (turned) "TB" else direction(model.direction)
  val sideways = rootDir == "LR" || rootDir == "RL"

  val index = HashMap<String, Int>()
  val views = ArrayList<View>()
  val cnodes = ArrayList<CNode>()
  graph.nodes.forEachIndexed { i, node -> index[node.id] = i }

  for (node in graph.nodes) {
    val style =
      resolveStyle(
        if (node.cssStyles.size > 0) node.cssCompiledStyles + node.cssStyles
        else node.cssCompiledStyles
      )
    val base = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val fontSize = style.fontSize ?: size
    val markdown = node.labelType == "markdown"
    val stereotype =
      text(
        if (!node.stereotype.isNullOrEmpty()) "«${node.stereotype}»" else node.stereotype,
        false,
        measurer,
        tiny,
        200.0,
        base,
      )
    val view =
      View(node, Kind.Box, style, stereotype, stereotype, 0.0, 0.0, ArrayList(), 0.0, 0.0, 0)
    val c: CNode
    if (node.isGroup) {
      view.kind = Kind.Boundary
      view.label = text(node.label, markdown, measurer, fontSize, 4000.0, base)
      val titleH = view.label.height
      c = cnode(0.0, 0.0)
      c.isGroup = true
      c.padX = GROUP_PAD
      c.padBottom = GROUP_PAD
      c.padTop =
        if (node.boundaryType == "package") titleH + 2 * TAB_PAD + 16
        else if (titleH > 0) titleH + 16 else GROUP_PAD
      c.minW = view.label.width + 2 * GROUP_PAD
    } else {
      if (node.jsonRows != null) {
        view.kind = Kind.Json
        view.label = text(node.label, false, measurer, fontSize, 4000.0, base or Style.Bold)
        var valueW = 0.0
        for (row in node.jsonRows) {
          val key = text(row.key, false, measurer, small, 4000.0, base)
          val value = text(row.value, false, measurer, small, 260.0, base)
          view.rows.add(Pair(key, value))
          view.keyW = max(view.keyW, key.width)
          valueW = max(valueW, value.width)
          view.h += jsMax(key.height, value.height, jsRound(small * 1.5)) + ROW_PAD
        }
        // The root of an empty object has a value and no key, and then there is no key column.
        if (view.keyW > 0) view.keyW += 2 * CELL_PAD
        view.headH = max(view.label.height, jsRound(fontSize * 1.5)) + ROW_PAD
        view.w =
          jsMax(
            view.label.width + 2 * CELL_PAD,
            if (view.rows.size > 0) view.keyW + valueW + 2 * CELL_PAD else 0.0,
            72.0,
          )
        view.h += view.headH
      } else if (!node.actorType.isNullOrEmpty()) {
        view.kind = Kind.Actor
        view.label = text(node.label, markdown, measurer, fontSize, 140.0, base)
        view.w = jsMax(FIGURE_W, view.label.width, stereotype.width)
        view.h = FIGURE_H + FIGURE_GAP + stereotype.height + view.label.height
      } else {
        view.label =
          text(
            node.label,
            markdown,
            measurer,
            if (node.shape == "note") small else fontSize,
            180.0,
            base,
          )
        val tw = max(view.label.width, stereotype.width)
        val th = view.label.height + stereotype.height
        if (node.shape == "usecaseEllipse" || node.shape == "usecaseBusiness") {
          view.kind = Kind.Ellipse
          // An oval needs more room around its text than a box, and a business oval room for its
          // slash.
          view.w = max(tw + 2 * node.padding + (if (node.business == true) 16 else 4), 88.0)
          view.h = max(th + 28, 48.0)
        } else if (node.shape == "note") {
          view.w = tw + 2 * node.padding + 6
          view.h = th + 18
        } else {
          view.w = max(tw + 2 * node.padding + 12, 80.0)
          view.h = max(th + 24, 44.0)
        }
      }
      c = cnode(view.w, view.h)
    }
    c.seq = views.size.toDouble()
    views.add(view)
    cnodes.add(c)
  }
  graph.nodes.forEachIndexed { i, node ->
    cnodes[i].parent = if (node.parentId != null) (index[node.parentId] ?: -1) else -1
  }

  val cedges = ArrayList<CEdge>()
  val edgeLabels = ArrayList<Label>()
  val drawn = ArrayList<UsecaseGraphEdge>()
  for (edge in graph.edges) {
    val src = index[edge.start]
    val dst = index[edge.end]
    if (src == null || dst == null) continue
    val semantic = edge.relationshipType == "include" || edge.relationshipType == "extend"
    val label =
      text(
        if (semantic) "«${edge.label}»" else edge.label,
        edge.labelType == "markdown",
        measurer,
        small,
        200.0,
      )
    if (src == dst) {
      // A self-relationship loops out across the flow, and the node's box grows to hold it.
      views[src].loops++
      if (sideways) cnodes[src].h += 2 * (LOOP + (if (label.height > 0) label.height + 8 else 0.0))
      else cnodes[src].w += 2 * (LOOP + (if (label.width > 0) label.width + 12 else 0.0))
    }
    drawn.add(edge)
    edgeLabels.add(label)
    cedges.add(
      CEdge(
        src = src,
        dst = dst,
        minlen = edge.minlen.toDouble(),
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

  val title = titleLabel(model.title, measurer, size, 4000.0)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = max(layout.width, title.width)
  val ox = pad + (inner - layout.width) / 2
  val oy = pad + titleHeight
  shiftLayout(cnodes, cedges, ox, oy)
  val width = ceil(inner + 2 * pad)
  val height = ceil(layout.height + titleHeight + 2 * pad)

  // Where edges cross each boundary's top border, so that a title can keep out of their way.
  val crossings = Crossings()
  for (e in cedges) markCrossings(crossings, e.route)
  attachEnds(cedges, cnodes, views)

  var clusters = ""
  val fills = ArrayList<Fill>()
  var nodesOut = ""
  for (i in views.indices) {
    val view = views[i]
    val c = cnodes[i]
    val node = view.node
    val classes = classNames(node.cssClasses.jsReplace(RE_DEFAULT, ""))
    val id = escText(node.id)
    val textAttrs = view.style.text
    val shape = view.style.shape
    val color = inkFill(view.style)
    val muted = if (color.isNotEmpty()) color else " fill=\"var(--_m)\""
    val w = view.w
    val h = view.h

    if (view.kind == Kind.Boundary) {
      val x = c.x - c.w / 2
      val y = c.y - c.h / 2
      if (paints(shape)) fills.add(Fill(doubleArrayOf(x, y, x + c.w, y + c.h), shape))
      val box =
        " fill=\"var(--_a)\"${if (setsFill(shape)) "" else " fill-opacity=\"0.5\""} stroke=\"var(--_b)\"$shape"
      val titleAttrs = " class=\"pele-cluster-label\"$muted$textAttrs"
      val body: String
      if (node.boundaryType == "package") {
        val tabW = min(view.label.width + 2 * TAB_PAD + 8, c.w)
        val tabH = view.label.height + 2 * TAB_PAD
        body =
          "<rect class=\"pele-boundary-tab\" x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(tabW)}\" height=\"${num(tabH)}\"$box/>" +
            "<rect x=\"${num(x)}\" y=\"${num(y + tabH)}\" width=\"${num(c.w)}\" height=\"${num(c.h - tabH)}\"$box/>" +
            labelSvg(view.label, x + tabW / 2, y + tabH / 2, titleAttrs, icons)
      } else {
        body =
          "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(c.w)}\" height=\"${num(c.h)}\"${rounded(box)}/>" +
            labelSvg(
              view.label,
              titleX(c, view.label.width, crossings),
              y + 8 + view.label.height / 2,
              titleAttrs,
              icons,
            )
      }
      clusters += "<g class=\"pele-cluster pele-boundary$classes\" data-id=\"$id\">$body</g>"
      continue
    }

    val body: String
    val kind: String
    if (view.kind == Kind.Actor) {
      kind = "pele-actor"
      val top = -h / 2
      val stereotypeY = top + FIGURE_H + FIGURE_GAP + view.stereotype.height / 2
      body =
        "<g class=\"pele-actor-figure\" transform=\"translate(0,${num(top + FIGURE_H / 2)})\">${figure(view, icons)}</g>" +
          labelSvg(
            view.stereotype,
            0.0,
            stereotypeY,
            " class=\"pele-stereotype\" font-size=\"${jsStr(tiny)}\" fill=\"var(--_m)\"",
            icons,
          ) +
          labelSvg(
            view.label,
            0.0,
            h / 2 - view.label.height / 2,
            " class=\"pele-label\"$textAttrs",
            icons,
          )
    } else if (view.kind == Kind.Json) {
      kind = "pele-json"
      val x0 = -w / 2
      var y = -h / 2 + view.headH
      var cells = ""
      var lines = "M${num(x0)},${num(y)}H${num(x0 + w)}"
      if (view.keyW > 0) lines += "M${num(x0 + view.keyW)},${num(y)}V${num(h / 2)}"
      for ((key, value) in view.rows) {
        val rowH = jsMax(key.height, value.height, jsRound(small * 1.5)) + ROW_PAD
        cells +=
          labelSvg(
            key,
            x0 + CELL_PAD + key.width / 2,
            y + rowH / 2,
            " class=\"pele-json-key\"$muted",
            icons,
          ) +
            leftLabel(
              value,
              x0 + view.keyW + CELL_PAD,
              y + rowH / 2,
              " class=\"pele-json-value\"$color$textAttrs",
              icons,
            )
        y += rowH
      }
      body =
        "<rect x=\"${num(x0)}\" y=\"${num(-h / 2)}\" width=\"${num(w)}\" height=\"${num(h)}\"${rounded(SHAPE_ATTRS + shape)}/>" +
          "<path class=\"pele-json-lines\" d=\"$lines\" fill=\"none\" stroke=\"var(--_b)\"${view.style.line}/>" +
          labelSvg(
            view.label,
            0.0,
            -h / 2 + view.headH / 2,
            " class=\"pele-label\" font-weight=\"var(--_hw)\"$color$textAttrs",
            icons,
          ) +
          "<g font-size=\"${jsStr(small)}\">$cells</g>"
    } else {
      val labelY = view.stereotype.height / 2
      var outline: String
      if (view.kind == Kind.Ellipse) {
        kind = "pele-usecase"
        outline = "<ellipse rx=\"${num(w / 2)}\" ry=\"${num(h / 2)}\"$SHAPE_ATTRS$shape/>"
        if (node.business == true) {
          // The slash of a business use case, cutting off the right end of the oval beside the
          // text.
          val from = w / 2 - 15
          val to = w / 2 - 5
          val arc = { at: Double -> (h / 2) * sqrt(1 - StrictMath.pow(at / (w / 2), 2.0)) }
          outline +=
            "<path class=\"pele-business\" d=\"M${num(from)},${num(arc(from))}L${num(to)},${num(-arc(to))}\" fill=\"none\" stroke=\"var(--_b)\"${view.style.line}/>"
        }
      } else if (node.shape == "note") {
        kind = "pele-note"
        val r = w / 2
        val b = h / 2
        outline =
          "<path d=\"M${num(-r)},${num(-b)}H${num(r - FOLD)}L${num(r)},${num(FOLD - b)}V${num(b)}H${num(-r)}Z\" fill=\"var(--_a)\" stroke=\"var(--_b)\" stroke-linejoin=\"round\"$shape/>" +
            "<path d=\"M${num(r - FOLD)},${num(-b)}V${num(FOLD - b)}H${num(r)}\" fill=\"none\" stroke=\"var(--_b)\" stroke-linejoin=\"round\"${view.style.line}/>"
      } else {
        kind = "pele-usecase"
        outline =
          "<rect x=\"${num(-w / 2)}\" y=\"${num(-h / 2)}\" width=\"${num(w)}\" height=\"${num(h)}\"${rounded(SHAPE_ATTRS + shape)}/>"
      }
      body =
        outline +
          labelSvg(
            view.stereotype,
            0.0,
            -view.label.height / 2,
            " class=\"pele-stereotype\" font-size=\"${jsStr(tiny)}\"$muted",
            icons,
          ) +
          labelSvg(
            view.label,
            0.0,
            labelY,
            " class=\"pele-label\"${if (node.shape == "note") " font-size=\"${jsStr(small)}\"" else ""}$color$textAttrs",
            icons,
          )
    }
    nodesOut +=
      "<g class=\"pele-node $kind$classes\" data-id=\"$id\" transform=\"translate(${num(c.x)},${num(c.y)})\">$body</g>"
  }

  var casings = ""
  var edgesOut = ""
  var labelsOut = ""
  val loopSeen = HashMap<Int, Int>()
  for (i in cedges.indices) {
    val ce = cedges[i]
    val edge = drawn[i]
    val label = edgeLabels[i]
    val style =
      if (edge.style.size > 0 || edge.cssCompiledStyles.size > 0)
        resolveStyle(edge.cssCompiledStyles + edge.style)
      else null
    val startType = edge.arrowTypeStart
    val endType = edge.arrowTypeEnd
    val path: EdgePath
    if (ce.src == ce.dst) {
      val c = cnodes[ce.src]
      val view = views[ce.src]
      val k = loopSeen[ce.src] ?: 0
      loopSeen[ce.src] = k + 1
      val side = if (sideways) 2 else 1
      val spread = min(sideLength(view, side) / 2, 8 + k * 6.0)
      val reach = LOOP + k * 8
      val (ax, ay) = anchor(view, c, side, -spread)
      val (bx, by) = anchor(view, c, side, spread)
      val hypot = jsHypot(reach, spread)
      val len = if (truthy(hypot)) hypot else 1.0
      val ux = if (sideways) spread / len else reach / len
      val uy = if (sideways) reach / len else spread / len
      val st = trim(startType)
      val et = trim(endType)
      // Both ends leave the node leaning away from each other, and the curve joins them outside it.
      val out = { x: Double, y: Double, sign: Double, far: Double ->
        if (sideways) "${num(x + sign * spread * (if (truthy(far)) 1.0 else 0.0))},${num(y + far)}"
        else "${num(x + far)},${num(y + sign * spread * (if (truthy(far)) 1.0 else 0.0))}"
      }
      val sx = if (sideways) ax - ux * st else ax + ux * st
      val sy = if (sideways) ay + uy * st else ay - uy * st
      val ex = bx + ux * et
      val ey = by + uy * et
      path =
        EdgePath(
          d =
            "M${num(sx)},${num(sy)}C${out(ax, ay, -1.0, reach)} ${out(bx, by, 1.0, reach)} ${num(ex)},${num(ey)}",
          sx = ax,
          sy = ay,
          sdx = if (sideways) ux else -ux,
          sdy = if (sideways) -uy else uy,
          ex = bx,
          ey = by,
          edx = -ux,
          edy = -uy,
        )
      if (sideways) {
        ce.labelX = c.x
        ce.labelY = max(ay, by) + reach * 0.75 + 6 + label.height / 2
      } else {
        ce.labelX = max(ax, bx) + reach * 0.75 + 8 + label.width / 2
        ce.labelY = c.y
      }
    } else {
      if (ce.route.size < 6) continue
      path = routePath(ce.route, null, trim(startType), trim(endType))
    }
    val under =
      fillUnder(path.d, doubleArrayOf(path.sx, path.sy, path.ex, path.ey), fills, path.tokens)
    val over: String? = if (under is Fill) edgeInk(under, style?.line ?: "") else under as String?
    val color =
      if (!over.isNullOrEmpty()) over
      else if (!style?.stroke.isNullOrEmpty()) esc(style!!.stroke!!) else "var(--_l)"
    if (over == "") {
      val bg = "var(--_bg)"
      casings +=
        casing(
          path.d,
          mark(startType, path.sx, path.sy, path.sdx, path.sdy, bg, bg) +
            mark(endType, path.ex, path.ey, path.edx, path.edy, bg, bg),
          style?.line ?: "",
        )
    }
    // Mermaid animates these dashes. A still picture keeps the dashes.
    val dashes =
      if (edge.internal) "2 3"
      else if (edge.pattern == "dotted") "5 4" else if (edge.animate) "9 5" else ""
    val marks =
      mark(startType, path.sx, path.sy, path.sdx, path.sdy, color) +
        mark(endType, path.ex, path.ey, path.edx, path.edy, color)
    edgesOut +=
      "<g class=\"pele-edge pele-${edge.relationshipType}${classNames(edge.classes.jsReplace(RE_RELATIONSHIP, ""))}\" data-id=\"${escText(edge.id)}\"${if (!over.isNullOrEmpty()) " stroke=\"$over\"" else ""}${style?.line ?: ""}>" +
        "<path d=\"${path.d}\"${if (dashes.isNotEmpty()) " stroke-dasharray=\"$dashes\"" else ""}/>" +
        (if (under != null && !over.isNullOrEmpty()) hollowOn(marks, under as Fill) else marks) +
        "</g>"
    if (label.width > 0) {
      val x = ce.labelX
      val y = ce.labelY
      val semantic = edge.relationshipType == "include" || edge.relationshipType == "extend"
      labelsOut +=
        edgeLabelSvg(
          edge.id,
          label,
          x,
          y,
          (if (semantic) " fill=\"var(--_m)\"" else "") + (style?.text ?: ""),
          icons,
        )
    }
  }

  val svg =
    svgDocument(
      "usecase",
      width,
      height,
      size,
      options,
      model,
      labelSvg(
        title,
        width / 2,
        pad + title.height / 2,
        " class=\"pele-title\" font-weight=\"var(--_tw)\"",
      ) +
        (if (clusters.isNotEmpty()) "<g class=\"pele-clusters\">$clusters</g>" else "") +
        casingsSvg(casings) +
        (if (edgesOut.isNotEmpty())
          "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>"
        else "") +
        (if (labelsOut.isNotEmpty())
          "<g class=\"pele-edge-labels\" font-size=\"${jsStr(small)}\">$labelsOut</g>"
        else "") +
        "<g class=\"pele-nodes\">$nodesOut</g>",
    )

  return Rendered(svg, width, height, ArrayList())
}

// A boundary's title sits in the middle of its top edge. Where edges enter through that edge it
// moves into the stretch between them that is nearest the middle, or the widest if none has room.
private fun titleX(c: CNode, width: Double, crossings: Map<Double, List<Double>>): Double {
  val xs = crossings[jsRound(c.y - c.h / 2) + 0.0]
  if (width == 0.0 || xs == null) return c.x
  val left = c.x - c.w / 2 + 6
  val right = c.x + c.w / 2 - 6
  val cuts =
    xs.filter { x -> x > left && x < right }.sortedWith { a, b -> cmp(a - b) }.toMutableList()
  cuts.add(right)
  val need = width / 2 + 6
  var best = c.x
  var bestOff = Double.POSITIVE_INFINITY
  var widest = 0.0
  var from = left
  for (cut in cuts) {
    if (cut - from >= 2 * need) {
      val x = min(max(c.x, from + need), cut - need)
      if (abs(x - c.x) < bestOff) {
        bestOff = abs(x - c.x)
        best = x
      }
    } else if (bestOff == Double.POSITIVE_INFINITY && cut - from > widest) {
      widest = cut - from
      best = (from + cut) / 2
    }
    from = cut
  }
  return best
}

private fun leftLabel(
  label: Label,
  x: Double,
  cy: Double,
  attrs: String,
  icons: IconResolver?,
): String {
  if (label.lines.size < 2) return labelSvg(label, x + label.width / 2, cy, attrs, icons)
  var out = ""
  for (i in label.lines.indices) {
    out +=
      labelSvg(
        labelLine(label, i),
        x + label.widths[i] / 2,
        cy - label.height / 2 + lineMiddle(label, i),
        attrs,
        icons,
      )
  }
  return out
}
