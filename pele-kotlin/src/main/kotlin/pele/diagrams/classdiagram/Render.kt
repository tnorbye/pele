// Ported from Pele src/diagrams/class/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.Config
import pele.LinkInfo
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.parseGenericTypes
import pele.diagrams.common.runsAcross
import pele.diagrams.common.tighten
import pele.diagrams.common.turnToFit
import pele.js.JsRegExp
import pele.js.cmp
import pele.js.jsOr
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsStr
import pele.js.jsTrim
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.LayeredOptions
import pele.layout.cnode
import pele.layout.compoundLayout
import pele.layout.direction
import pele.layout.wrapComponents
import pele.svg.Crossings
import pele.svg.EdgePath
import pele.svg.FONT_MONO
import pele.svg.ROUNDED
import pele.svg.ResolvedStyle
import pele.svg.classNames
import pele.svg.clusterTitleX
import pele.svg.edgeLabelSvg
import pele.svg.endSide
import pele.svg.esc
import pele.svg.escText
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.markCrossings
import pele.svg.num
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.routePath
import pele.svg.struckTitle
import pele.svg.svgDocument
import pele.svg.titleCrossed
import pele.svg.withTitle
import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.hasMath
import pele.text.layoutLabel
import pele.util.linked
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val NODE_SEP = 40.0
private const val EDGE_SEP = 16.0
private const val RANK_SEP = 56.0
private const val GROUP_PAD = 20.0
private const val LOOP = 26.0
private const val PAD_X = 12.0
private const val TITLE_PAD = 8.0
private const val SECTION_PAD = 6.0
private const val EMPTY_SECTION = 10.0
private const val MIN_WIDTH = 64.0
private const val FOLD = 8.0
private const val LINE = "var(--_l)"
private val TARGETS = setOf("_self", "_blank", "_parent", "_top")

private val RE_FONT_FAMILY = JsRegExp("""[";]font-family:""")
private val RE_LT = JsRegExp("<", "g")
private val RE_GT = JsRegExp(">", "g")
private val RE_DEFAULT = JsRegExp("""^default\s?""")

private fun numberOption(config: Map<String, Any?>, key: String, fallback: Double): Double {
  val value = config[key]
  return if (value is Double && value > 0) min(value, 2000.0) else fallback
}

private class Line(
  val text: String,
  val width: Double,
  val classifier: String,
)

private class View(
  val node: GraphNode,
  val style: ResolvedStyle,
  val label: Label,
  var w: Double,
  var h: Double,
  var loops: Int,
  // Each is a String or a Label.
  val annotations: List<Any>,
  val attributes: List<Line>,
  val methods: List<Line>,
  val titleH: Double,
  val attributesH: Double,
  val fontSize: Double,
)

private class End(
  val route: MutableList<Double>,
  val at: Int,
  val toward: Double,
  val order: Int,
  val room: Double,
  val side: Int,
  // Whether the text of this end goes on the right of, or below, its line.
  val after: Boolean,
)

private class Card(
  val text: String,
  val math: Label?,
  var x: Double,
  var y: Double,
  val w: Double,
  val h: Double,
  val parent: Int,
)

private val NO_LINES: List<Line> = emptyList()
private val NO_TEXT: List<String> = emptyList()

private fun memberLine(member: ClassMember, measurer: TextMeasurer, size: Double, mono: Boolean): Line {
  val text = decodeEntities(member.getDisplayDetails().displayText).jsTrim()
  val style = (if (member.classifier == "*") Style.Italic else 0) or (if (mono) Style.Mono else 0)
  return Line(text, measurer.width(text, size, style), member.classifier)
}

fun renderClass(db: ClassDb, config: Config, options: RenderOptions): Rendered {
  val fitted = { down: Boolean -> tighten(options) { tight -> draw(db, config, options, down, tight) } }
  return if (runsAcross(db.direction)) turnToFit(options, fitted) else fitted(false)
}

// `turned` draws a diagram that runs across as one that runs down. `tight` gives up the room
// that keeps a namespace's name clear of an edge coming in beside it.
private fun draw(db: ClassDb, config: Config, options: RenderOptions, turned: Boolean, tight: Boolean): Rendered {
  @Suppress("UNCHECKED_CAST")
  val conf: Map<String, Any?> = ((config["class"] as? Map<*, *>) ?: emptyMap<String, Any?>()) as Map<String, Any?>
  val graph = buildClassGraph(db, conf["hierarchicalNamespaces"] != false)
  val hideEmpty = conf["hideEmptyMembersBox"] == true
  val monospaced = options.monospace?.contains("members") == true
  // Explicit class fonts override monospace.
  val mono = { style: ResolvedStyle -> monospaced && !RE_FONT_FAMILY.test(style.text) }
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val tiny = jsRound(size * 0.8)
  val tinyHeight = jsRound(tiny * 1.4)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  // Keep annotations and cardinalities literal unless math is typeset.
  val mathText = { raw: String, fontSize: Double ->
    if (!raw.contains("$$")) {
      null
    } else {
      val label = layoutLabel(raw, false, measurer, fontSize, 4000.0)
      if (label.lines.any { hasMath(it) }) label else null
    }
  }
  val cardWidth = { raw: String ->
    if (raw.isNotEmpty()) (mathText(raw, tiny)?.width ?: measurer.width(decodeEntities(raw), tiny, 0)) else 0.0
  }
  val rootDir = if (turned) "TB" else direction(db.direction)

  // A class may carry the declarations of many classDefs many times over. Each distinct declaration
  // is checked once, and only the last value of each property that has an effect is resolved.
  val effect = LinkedHashMap<String, String>()
  val fromDefs = LinkedHashMap<String, List<Pair<String, String>>>()
  val kept = LinkedHashMap<String, String>()
  val keep = { decl: String ->
    var prop = effect[decl]
    if (prop == null) {
      val one = resolveStyle(listOf(decl))
      prop = if (one.shape == "" && one.text == "") "" else decl.jsSlice(0, decl.indexOf(':')).jsTrim().lowercase()
      effect[decl] = prop
    }
    if (prop != "") kept[prop] = decl
  }
  val classStyle = { cls: ClassNode ->
    kept.clear()
    var base = fromDefs[cls.cssClasses]
    if (base == null) {
      for (name in cls.cssClasses.jsSplit(" ")) {
        val def = db.styleClasses[name]
        if (def != null) for (s in def.styles) for (part in s.jsSplit(",")) keep(part)
      }
      base = kept.entries.map { Pair(it.key, it.value) }
      fromDefs[cls.cssClasses] = base
    } else {
      for ((prop, decl) in base) kept[prop] = decl
    }
    for (s in cls.styles) keep(s)
    resolveStyle(if (kept.size == 0) NO_TEXT else kept.values.toList())
  }

  val children = IntArray(graph.nodes.size)
  for (node in graph.nodes) if (node.parent >= 0) children[node.parent]++

  // Namespaces that an edge may cross into are made wide enough for the title to sit beside it.
  val crossed = IntArray(graph.nodes.size)
  for (edge in graph.edges) {
    if (edge.source < 0 || edge.target < 0) continue
    val a = graph.nodes[edge.source].parent
    val b = graph.nodes[edge.target].parent
    if (a == b) continue
    var g = a
    while (g >= 0 && crossed[g] == 0) {
      crossed[g] = 1
      g = graph.nodes[g].parent
    }
    g = b
    while (g >= 0 && crossed[g] == 0) {
      crossed[g] = 1
      g = graph.nodes[g].parent
    }
  }

  val views = ArrayList<View>()
  val cnodes = ArrayList<CNode>()

  fun base(node: GraphNode, label: Label, w: Double, h: Double, fontSize: Double): View =
    View(
      node = node,
      style = resolveStyle(NO_TEXT),
      label = label,
      w = w,
      h = h,
      loops = 0,
      annotations = NO_TEXT,
      attributes = NO_LINES,
      methods = NO_LINES,
      titleH = 0.0,
      attributesH = 0.0,
      fontSize = fontSize,
    )

  fun classView(node: GraphNode, cls: ClassNode): View {
    val style = classStyle(cls)
    val fontSize = style.fontSize ?: size
    val memberSize = jsRound(fontSize * 0.875)
    val lineHeight = jsRound(memberSize * 1.5)
    // The generic type is escaped so that `<T>` is not read as a tag.
    val generic = if (cls.type.isNotEmpty()) "&lt;" + parseGenericTypes(cls.type).jsReplace(RE_LT, "&lt;").jsReplace(RE_GT, "&gt;") + "&gt;" else ""
    val label = layoutLabel(cls.label + generic, false, measurer, fontSize, 4000.0, Style.Bold or (if (style.italic) Style.Italic else 0))
    val annotations: List<Any> =
      if (cls.annotations.size > 0) cls.annotations.map { a -> mathText("«${a.jsTrim()}»", tiny) ?: "«${decodeEntities(a).jsTrim()}»" } else NO_TEXT
    val attributes = if (cls.members.size > 0) cls.members.map { m -> memberLine(m, measurer, memberSize, mono(style)) } else NO_LINES
    val methods = if (cls.methods.size > 0) cls.methods.map { m -> memberLine(m, measurer, memberSize, mono(style)) } else NO_LINES
    var inner = label.width
    var annotationsH = 0.0
    for (a in annotations) {
      inner = max(inner, if (a is String) measurer.width(a, tiny, 0) else (a as Label).width)
      annotationsH += if (a is String) tinyHeight else max(tinyHeight, (a as Label).height)
    }
    for (line in attributes) inner = max(inner, line.width)
    for (line in methods) inner = max(inner, line.width)
    val sections = !(hideEmpty && attributes.size == 0 && methods.size == 0)
    val section = { count: Int -> if (!sections) 0.0 else if (count > 0) 2 * SECTION_PAD + count * lineHeight else EMPTY_SECTION }
    val titleH = 2 * TITLE_PAD + annotationsH + max(label.height, jsRound(fontSize * 1.5))
    val attributesH = section(attributes.size)
    return View(
      node = node,
      style = style,
      label = label,
      w = max(inner + 2 * PAD_X, MIN_WIDTH),
      h = titleH + attributesH + section(methods.size),
      loops = 0,
      annotations = annotations,
      attributes = attributes,
      methods = methods,
      titleH = titleH,
      attributesH = attributesH,
      fontSize = fontSize,
    )
  }

  graph.nodes.forEachIndexed { i, node ->
    val view: View
    val c: CNode
    if (node.isGroup) {
      val label = layoutLabel(node.label, false, measurer, size, 4000.0)
      view = base(node, label, 0.0, 0.0, size)
      if (children[i] > 0) {
        c = cnode(0.0, 0.0)
        c.isGroup = true
        c.padX = GROUP_PAD
        c.padTop = if (label.height > 0) label.height + 16 else GROUP_PAD
        c.padBottom = GROUP_PAD
        c.minW = label.width + 2 * GROUP_PAD - 12 + (if (crossed[i] == 1 && !tight) min(label.width + 24, 240.0) else 0.0)
      } else {
        view.w = max(label.width + 2 * GROUP_PAD, 80.0)
        view.h = label.height + 2 * GROUP_PAD
        c = cnode(view.w, view.h)
      }
    } else if (node.classNode != null) {
      view = classView(node, node.classNode)
      c = cnode(view.w, view.h)
    } else if (node.shape == "note") {
      val label = layoutLabel(node.label, true, measurer, small, 260.0)
      view = base(node, label, max(label.width + 2 * PAD_X, 40.0), label.height + 2 * TITLE_PAD, small)
      c = cnode(view.w, view.h)
    } else {
      val label = layoutLabel(node.label, false, measurer, small, 200.0)
      view = base(node, label, max(label.width, 12.0) + 4, max(label.height, 12.0), small)
      c = cnode(view.w, view.h)
    }
    c.parent = node.parent
    c.seq = i.toDouble()
    views.add(view)
    cnodes.add(c)
  }

  val cedges = ArrayList<CEdge>()
  val drawn = ArrayList<GraphEdge>()
  val edgeLabels = ArrayList<Label>()
  val startRoom = ArrayList<Double>()
  val endRoom = ArrayList<Double>()
  // Edges leave one side of a class and arrive at the other, so a class with many of them
  // is widened until they, and the text at their ends, fit side by side.
  val crowd = DoubleArray(views.size * 4)
  val meet = { item: Int, slot: Int, room: Double ->
    crowd[item * 4 + slot]++
    if (room > crowd[item * 4 + slot + 1]) crowd[item * 4 + slot + 1] = room
  }
  for (edge in graph.edges) {
    if (edge.source < 0 || edge.target < 0) continue
    val label = layoutLabel(edge.label, true, measurer, small, 200.0)
    val startW = cardWidth(edge.startLabelRight)
    val endW = cardWidth(edge.endLabelLeft)
    if (edge.source == edge.target) {
      views[edge.source].loops++
      cnodes[edge.source].w += 2 * (LOOP + (if (label.width > 0) label.width + 12 else 0.0))
    } else {
      meet(edge.source, 0, startW)
      meet(edge.target, 2, endW)
    }
    startRoom.add(startW)
    endRoom.add(endW)
    drawn.add(edge)
    edgeLabels.add(label)
    cedges.add(
      CEdge(
        src = edge.source,
        dst = edge.target,
        minlen = 1.0,
        labelW = if (label.width > 0) label.width + 12 else 0.0,
        labelH = if (label.height > 0) label.height + 4 else 0.0,
        route = ArrayList(),
        labelX = 0.0,
        labelY = 0.0,
      )
    )
  }

  if (rootDir == "TB" || rootDir == "BT") {
    for (i in 0 until views.size) {
      if (views[i].node.classNode == null) continue
      var need = 0.0
      var slot = 0
      while (slot < 4) {
        val count = crowd[i * 4 + slot]
        val room = crowd[i * 4 + slot + 1]
        if (count > 1) need = max(need, (count - 1) * (if (room > 0) room + 24 else 14.0) + 24)
        slot += 2
      }
      need = min(need, 480.0)
      if (need > views[i].w) {
        cnodes[i].w += need - views[i].w
        views[i].w = need
      }
    }
  }

  var lettered = false
  var li = 0
  while (li < drawn.size && !lettered) {
    lettered = startRoom[li] > 0 || endRoom[li] > 0
    li++
  }
  // Cardinalities at both ends of a vertical edge need room for their height between the ranks.
  var tallCards = 0.0
  if (rootDir == "TB" || rootDir == "BT") {
    val cardHeight = { raw: String -> if (raw.isNotEmpty()) max(tinyHeight, mathText(raw, tiny)?.height ?: 0.0) else 0.0 }
    for (edge in drawn) tallCards = max(tallCards, cardHeight(edge.startLabelRight) + cardHeight(edge.endLabelLeft) - 2 * tinyHeight)
  }
  val layout = compoundLayout(
    cnodes,
    cedges,
    rootDir,
    LayeredOptions(
      nodeSep = numberOption(conf, "nodeSpacing", NODE_SEP),
      edgeSep = EDGE_SEP,
      rankSep = numberOption(conf, "rankSpacing", if (lettered) RANK_SEP + 16 else RANK_SEP) + tallCards,
      portSep = 20.0,
      apart = true,
    ),
  )

  // Every edge reaches the middle of a side. Where several share a side they are spread along it,
  // in the order of where they come from. An end with text runs straight for the length of the text,
  // which sits beside it on the side the line then bends toward, away from the neighbors that bend
  // the other way. Neighbors are set apart far enough for the text between them.
  val sides = LinkedHashMap<Int, MutableList<End>>()
  val cards = ArrayList<Card>()
  val attach = { ce: CEdge, at: Int, item: Int, room: Double, order: Int ->
    val route = ce.route
    val c = cnodes[item]
    val side = endSide(route, at, c)
    if (side < 0) {
      null
    } else {
      val slack = (c.w - views[item].w) / 2
      if (side == 1) route[at] = route[at] - slack
      else if (side == 3) route[at] = route[at] + slack
      val across = side and 1
      val toward = route[(if (at == 0) 3 else at - 3) + across]
      val bend = toward - route[at + across]
      val end = End(route, at, toward, order, room, side, if (across != 0) bend > 0 else bend >= 0)
      val key = item * 4 + side
      val list = sides[key]
      if (list != null) list.add(end) else sides[key] = arrayListOf(end)
      end
    }
  }
  val starts = ArrayList<End?>()
  val ends = ArrayList<End?>()
  for (i in 0 until cedges.size) {
    val ce = cedges[i]
    val routed = ce.src != ce.dst && ce.route.size >= 6
    starts.add(if (routed) attach(ce, 0, ce.src, startRoom[i], i) else null)
    ends.add(if (routed) attach(ce, ce.route.size - 3, ce.dst, endRoom[i], i) else null)
  }
  // Whether a point lies on the border of one of the namespaces around a class.
  val onBorder = { item: Int, x: Double, y: Double ->
    var found = false
    var g = cnodes[item].parent
    var hops = 0
    while (g >= 0 && hops < 10000) {
      val c = cnodes[g]
      val dx = abs(x - c.x)
      val dy = abs(y - c.y)
      if ((abs(dy - c.h / 2) < 0.5 && dx <= c.w / 2 + 0.5) || (abs(dx - c.w / 2) < 0.5 && dy <= c.h / 2 + 0.5)) {
        found = true
        break
      }
      g = cnodes[g].parent
      hops++
    }
    found
  }
  val gaps = ArrayList<Double>()
  for ((key, list) in sides) {
    if (list.size < 2) continue
    val item = key shr 2
    val across = key and 1
    val length = if (across != 0) views[item].h else views[item].w
    list.sortWith { a, b -> cmp((a.toward - b.toward).jsOr { (a.order - b.order).toDouble() }) }
    var total = 0.0
    for (j in 0 until list.size - 1) {
      val a = list[j]
      val b = list[j + 1]
      var gap = if (across != 0) 20.0 else 24.0
      if (a.room > 0 && a.after) gap += if (across != 0) tinyHeight + 6 else a.room
      if (b.room > 0 && !b.after) gap += if (across != 0) tinyHeight + 6 else b.room
      if (j < gaps.size) gaps[j] = gap else gaps.add(gap)
      total += gap
    }
    val scale = min(1.0, (length - 24) / total)
    if (scale <= 0) continue
    var at = (if (across != 0) cnodes[item].y else cnodes[item].x) - (total * scale) / 2
    for (j in 0 until list.size) {
      val route = list[j].route
      val end = list[j].at
      // An edge that crosses the border of the class's namespace close by runs straight to it:
      // the point where it crosses moves into line with its end.
      val step = if (end == 0) 3 else -3
      val first = end + step
      var border = -1
      var k = first
      while (k >= 0 && k < route.size && abs(route[k + across] - route[first + across]) < 0.5) {
        if (onBorder(item, route[k], route[k + 1])) {
          border = k
          break
        }
        k += step
      }
      if (border >= 0 && abs(route[first + across] - at) <= 24) {
        k = first
        while (k != border + step) {
          route[k + across] = at
          k += step
        }
      }
      route[end + across] = at
      at += (gaps.getOrNull(j) ?: Double.NaN) * scale
    }
  }

  val straighten = { route: MutableList<Double>, end: End?, room: Double, first: Boolean ->
    if (end != null && room > 0) {
      val across = end.side and 1
      val at = if (first) 0 else route.size - 3
      val along = at + 1 - across
      val reach = abs(route[(if (first) 3 else at - 3) + 1 - across] - route[along])
      val length = min(if (across != 0) room + 10 else tinyHeight + 6, reach * 0.35)
      if (length >= 4) {
        val point = doubleArrayOf(route[at], route[at + 1], route[at + 2])
        point[1 - across] += (if (end.side == 0 || end.side == 3) -1 else 1) * length
        route.addAll(if (first) 3 else at, listOf(point[0], point[1], point[2]))
      }
    }
  }
  for (i in 0 until cedges.size) {
    straighten(cedges[i].route, starts[i], startRoom[i], true)
    straighten(cedges[i].route, ends[i], endRoom[i], false)
  }

  var minX = 0.0
  var minY = 0.0
  var maxX = layout.width
  var maxY = layout.height
  val card = { parent: Int, raw: String, width: Double, x: Double, y: Double, side: Int, after: Boolean ->
    if (raw.isNotEmpty()) {
      val math = mathText(raw, tiny)
      val h = if (math != null) max(tinyHeight, math.height) else tinyHeight
      var cx = x
      var cy = y
      if ((side and 1) != 0) {
        cx += (if (side == 3) -1 else 1) * (5 + width / 2)
        cy += (if (after) 1 else -1) * (7 + h / 2)
      } else {
        cx += (if (after) 1 else -1) * (9 + width / 2)
        cy += (if (side == 0) -1 else 1) * (3 + h / 2)
      }
      cards.add(Card(decodeEntities(raw), math, cx, cy, width, h, parent))
      minX = min(minX, cx - width / 2)
      maxX = max(maxX, cx + width / 2)
      minY = min(minY, cy - h / 2)
      maxY = max(maxY, cy + h / 2)
    }
  }
  val loopSeen = LinkedHashMap<Int, Int>()
  val loopAt = IntArray(cedges.size)
  for (i in 0 until cedges.size) {
    val ce = cedges[i]
    val edge = drawn[i]
    val route = ce.route
    if (ce.src == ce.dst) {
      val k = loopSeen[ce.src] ?: 0
      loopSeen[ce.src] = k + 1
      loopAt[i] = k
      val c = cnodes[ce.src]
      val view = views[ce.src]
      val x = c.x + view.w / 2
      val spread = min(view.h / 2 - 4, 8.0 + k * 6)
      card(ce.src, edge.startLabelRight, startRoom[i], x, c.y - spread, 1, false)
      card(ce.dst, edge.endLabelLeft, endRoom[i], x, c.y + spread, 1, true)
      ce.labelX = c.x + view.w / 2 + LOOP + k * 8 + 4 + ce.labelW / 2
      ce.labelY = c.y
    } else if (route.size >= 6) {
      val last = route.size - 3
      val from = starts[i]
      val to = ends[i]
      card(ce.src, edge.startLabelRight, startRoom[i], route[0], route[1], if (from != null) from.side else 2, if (from != null) from.after else true)
      card(ce.dst, edge.endLabelLeft, endRoom[i], route[last], route[last + 1], if (to != null) to.side else 0, if (to != null) to.after else true)
    }
  }

  val available = options.maxWidth
  if (available != null && available > 0 && maxX - minX + 2 * pad > available) {
    val cardNodes = cards.map { item ->
      val c = cnode(item.w, item.h, item.parent)
      c.x = item.x
      c.y = item.y
      c
    }
    val wrapped = wrapComponents(cnodes + cardNodes, cedges, max(1.0, available - 2 * pad), numberOption(conf, "nodeSpacing", NODE_SEP))
    if (wrapped != null) {
      cards.forEachIndexed { i, item ->
        item.x = cardNodes[i].x
        item.y = cardNodes[i].y
      }
      minY = 0.0
      minX = minY
      maxX = wrapped.width
      maxY = wrapped.height
    }
  }

  val ox = pad - minX
  val oy = pad - minY
  val width = ceil(maxX - minX + 2 * pad)
  val height = ceil(maxY - minY + 2 * pad)
  val links = ArrayList<LinkInfo>()

  val crossings: Crossings = HashMap()
  if (graph.nodes.size > 0 && graph.nodes[0].isGroup) {
    for (e in cedges) markCrossings(crossings, e.route)
  }

  fun classSvg(view: View, cls: ClassNode, id: String, at: String): String {
    val w = view.w
    val h = view.h
    val style = view.style
    val left = -w / 2
    val top = -h / 2
    val memberSize = jsRound(view.fontSize * 0.875)
    val lineHeight = jsRound(memberSize * 1.5)
    var body = "<rect x=\"${num(left)}\" y=\"${num(top)}\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"${rounded(style.shape)}/>"
    val ink = inkFill(style)
    val first = top + view.titleH
    val second = first + view.attributesH
    if (h > view.titleH) {
      val stroke = if (!style.stroke.isNullOrEmpty()) " style=\"stroke:${esc(style.stroke)}\"" else ""
      body += "<path class=\"pele-divider\" d=\"M${num(left)},${num(first)}H${num(-left)}M${num(left)},${num(second)}H${num(-left)}\" fill=\"none\" stroke=\"var(--_b)\"$stroke/>"
    }
    var y = top + TITLE_PAD
    for (annotation in view.annotations) {
      if (annotation is String) {
        body += "<text class=\"pele-annotation\" x=\"0\" y=\"${num(y + tinyHeight / 2 + tiny * 0.35)}\" text-anchor=\"middle\" font-size=\"${jsStr(tiny)}\"${ink.ifEmpty { " fill=\"var(--_m)\"" }}>${esc(annotation)}</text>"
        y += tinyHeight
      } else {
        val label = annotation as Label
        val ah = max(tinyHeight, label.height)
        body += labelSvg(label, 0.0, y + ah / 2, " class=\"pele-annotation\" font-size=\"${jsStr(tiny)}\"${ink.ifEmpty { " fill=\"var(--_m)\"" }}", options.icons)
        y += ah
      }
    }
    val titleSize = if (view.fontSize == size) "" else " font-size=\"${num(view.fontSize)}\""
    body += labelSvg(view.label, 0.0, (y + first - TITLE_PAD) / 2, " class=\"pele-label\"$titleSize font-weight=\"var(--_hw)\"$ink${style.text}", options.icons)
    val lines = { list: List<Line>, kind: String, from: Double ->
      var out = ""
      var baseline = from + SECTION_PAD + lineHeight / 2 + memberSize * 0.35
      for (line in list) {
        val mark =
          if (line.classifier == "$") {
            " pele-static\"" + (if (style.text.isNotEmpty()) style.text.jsSlice(0, -1) + "text-decoration:underline\"" else " style=\"text-decoration:underline\"")
          } else if (line.classifier == "*") {
            " pele-abstract\" font-style=\"italic\"${style.text}"
          } else {
            "\"${style.text}"
          }
        out += "<text class=\"pele-member pele-$kind$mark x=\"${num(left + PAD_X)}\" y=\"${num(baseline)}\">${esc(line.text)}</text>"
        baseline += lineHeight
      }
      out
    }
    if (view.attributes.size > 0 || view.methods.size > 0) {
      body += "<g class=\"pele-members\" font-size=\"${jsStr(memberSize)}\"$ink${if (mono(style)) " font-family=\"$FONT_MONO\"" else ""}>${lines(view.attributes, "attribute", first)}${lines(view.methods, "method", second)}</g>"
    }
    if (!cls.tooltip.isNullOrEmpty()) body = "<title>${escText(cls.tooltip!!)}</title>" + body
    if (!cls.link.isNullOrEmpty()) {
      val target = cls.linkTarget
      body = linked(body, cls.link!!, cls.id, links, options, if (!target.isNullOrEmpty() && TARGETS.contains(target)) " target=\"$target\"" else "")
    }
    return "<g class=\"pele-node pele-class${classNames(cls.cssClasses.jsReplace(RE_DEFAULT, ""))}\" data-id=\"$id\"$at>$body</g>"
  }

  // PORT: struckTitle reads parents from CNodes, upstream passes the graph nodes.
  val parentNodes by lazy { graph.nodes.map { cnode(0.0, 0.0, it.parent) } }

  var clusters = ""
  var struckTitles = ""
  var nodesOut = ""
  for (i in 0 until views.size) {
    val view = views[i]
    val c = cnodes[i]
    val node = view.node
    val x = c.x + ox
    val y = c.y + oy
    val id = escText(node.id)
    val w = if (c.isGroup) c.w else view.w
    val h = if (c.isGroup) c.h else view.h
    if (node.isGroup) {
      val titleX = if (c.isGroup) clusterTitleX(c, view.label.width, crossings, GROUP_PAD) else c.x
      val titleY = y - h / 2 + 8 + view.label.height / 2
      val titleAttrs = " class=\"pele-cluster-label\" fill=\"var(--_m)\""
      val struck = c.isGroup && titleCrossed(c, view.label.width, titleX, crossings)
      if (struck) struckTitles += struckTitle(view.label, titleX + ox, titleY, titleAttrs, id, parentNodes, i)
      clusters +=
        "<g class=\"pele-cluster pele-namespace\" data-id=\"$id\">" +
        "<rect x=\"${num(x - w / 2)}\" y=\"${num(y - h / 2)}\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"var(--_a)\" fill-opacity=\"0.5\" stroke=\"var(--_b)\"$ROUNDED/>" +
        (if (struck) "" else labelSvg(view.label, titleX + ox, titleY, titleAttrs)) +
        "</g>"
      continue
    }
    val at = " transform=\"translate(${num(x)},${num(y)})\""
    if (node.classNode != null) {
      nodesOut += classSvg(view, node.classNode, id, at)
    } else if (node.shape == "note") {
      val r = w / 2
      val t = -h / 2
      nodesOut +=
        "<g class=\"pele-node pele-note\" data-id=\"$id\"$at>" +
        "<path d=\"M${num(-r)},${num(t)}H${num(r - FOLD)}L${num(r)},${num(t + FOLD)}V${num(-t)}H${num(-r)}Z\" fill=\"var(--_a)\" stroke=\"var(--_b)\" stroke-linejoin=\"round\"/>" +
        "<path d=\"M${num(r - FOLD)},${num(t)}V${num(t + FOLD)}H${num(r)}\" fill=\"none\" stroke=\"var(--_b)\" stroke-linejoin=\"round\"/>" +
        labelSvg(view.label, 0.0, 0.0, " class=\"pele-label\" font-size=\"${jsStr(small)}\"", options.icons) +
        "</g>"
    } else {
      nodesOut +=
        "<g class=\"pele-node pele-interface\" data-id=\"$id\"$at>" +
        labelSvg(view.label, 0.0, 0.0, " class=\"pele-label\" font-size=\"${jsStr(small)}\"", options.icons) +
        "</g>"
    }
  }

  var edgesOut = ""
  var labelsOut = ""
  for (i in 0 until cedges.size) {
    val ce = cedges[i]
    val edge = drawn[i]
    val label = edgeLabels[i]
    val startType = edge.arrowTypeStart
    val endType = edge.arrowTypeEnd
    val startTrim = classMarkerTrim(startType)
    val endTrim = classMarkerTrim(endType)
    val path: EdgePath
    if (ce.src == ce.dst) {
      val c = cnodes[ce.src]
      val view = views[ce.src]
      val k = loopAt[i]
      val x = c.x + ox + view.w / 2
      val y = c.y + oy
      val spread = min(view.h / 2 - 4, 8.0 + k * 6)
      val reach = LOOP + k * 8
      path = EdgePath(
        d = "M${num(x + startTrim)},${num(y - spread)}C${num(x + reach + startTrim)},${num(y - spread)} ${num(
          x + reach + endTrim
        )},${num(y + spread)} ${num(x + endTrim)},${num(y + spread)}",
        sx = x,
        sy = y - spread,
        sdx = -1.0,
        sdy = 0.0,
        ex = x,
        ey = y + spread,
        edx = -1.0,
        edy = 0.0,
      )
    } else {
      val route = ce.route
      var k = 0
      while (k < route.size) {
        route[k] = route[k] + ox
        route[k + 1] = route[k + 1] + oy
        k += 3
      }
      path = routePath(route, null, startTrim, endTrim)
    }
    val attrs = if (edge.pattern == "dashed") " stroke-dasharray=\"6 4\"" else if (edge.pattern == "dotted") " stroke-dasharray=\"2 4\"" else ""
    edgesOut +=
      "<g class=\"pele-edge${if (edge.pattern == "dotted") " pele-note-edge" else " pele-relation"}\" data-id=\"${escText(edge.id)}\">" +
      "<path d=\"${path.d}\"$attrs/>" +
      classMarker(startType, path.sx, path.sy, path.sdx, path.sdy, LINE) +
      classMarker(endType, path.ex, path.ey, path.edx, path.edy, LINE) +
      "</g>"
    if (label.width > 0) {
      val x = ce.labelX + ox
      val y = ce.labelY + oy
      labelsOut += edgeLabelSvg(edge.id, label, x, y, "", options.icons)
    }
  }

  var cardsOut = ""
  for (item in cards) {
    cardsOut +=
      if (item.math != null) {
        labelSvg(item.math, item.x + ox, item.y + oy, " class=\"pele-cardinality\"", options.icons)
      } else {
        "<text class=\"pele-cardinality\" x=\"${num(item.x + ox)}\" y=\"${num(item.y + oy + tiny * 0.35)}\" text-anchor=\"middle\">${esc(item.text)}</text>"
      }
  }

  val titled = withTitle(
    db.title,
    measurer,
    size,
    pad,
    width,
    height,
    (if (clusters.isNotEmpty()) "<g class=\"pele-clusters\">$clusters</g>" else "") +
      (if (edgesOut.isNotEmpty()) "<g class=\"pele-edges\" fill=\"none\" stroke=\"$LINE\" stroke-linecap=\"round\">$edgesOut</g>" else "") +
      (if (struckTitles.isNotEmpty()) "<g class=\"pele-cluster-titles\">$struckTitles</g>" else "") +
      (if (labelsOut.isNotEmpty()) "<g class=\"pele-edge-labels\" font-size=\"${jsStr(small)}\">$labelsOut</g>" else "") +
      (if (cardsOut.isNotEmpty()) "<g class=\"pele-cardinalities\" font-size=\"${jsStr(tiny)}\" fill=\"var(--_m)\">$cardsOut</g>" else "") +
      "<g class=\"pele-nodes\">$nodesOut</g>",
  )
  val svg = svgDocument("class", titled.width, titled.height, size, options, db, titled.content)

  return Rendered(svg, titled.width, titled.height, links)
}
