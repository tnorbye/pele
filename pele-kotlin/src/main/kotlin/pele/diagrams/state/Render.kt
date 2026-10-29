// Ported from Pele src/diagrams/state/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

import pele.Config
import pele.LinkInfo
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.runsAcross
import pele.diagrams.common.turnToFit
import pele.js.JsRegExp
import pele.js.cmp
import pele.js.jsHypot
import pele.js.jsOr
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsStr
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.Dir
import pele.layout.LayeredOptions
import pele.layout.cnode
import pele.layout.compoundLayout
import pele.layout.direction
import pele.svg.EdgePath
import pele.svg.Fill
import pele.svg.ResolvedStyle
import pele.svg.casingsSvg
import pele.svg.classNames
import pele.svg.edgeCasing
import pele.svg.edgeLabelSvg
import pele.svg.endSide
import pele.svg.esc
import pele.svg.escText
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.overFills
import pele.svg.paints
import pele.svg.resolveStyle
import pele.svg.routePath
import pele.svg.setsFill
import pele.svg.sideReach
import pele.svg.svgDocument
import pele.svg.withTitle
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import pele.util.linked
import pele.util.safeUrl
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

private const val NODE_SEP = 40.0
private const val EDGE_SEP = 16.0
private const val RANK_SEP = 44.0
private const val GROUP_PAD = 16.0
private const val REGION_PAD = 10.0
private const val PAD_X = 16.0
private const val PAD_Y = 10.0
private const val LOOP = 26.0
private const val FOLD = 8.0
private const val NOTE_WRAP = 320.0
private const val CORNER = 10.0
private const val BAR = 8.0
private const val SURFACE = " fill=\"var(--_s)\" stroke=\"var(--_b)\""
private const val RULE = " fill=\"none\" stroke=\"var(--_b)\""
private val MERMAID_CLASSES = JsRegExp("""(?:^| )statediagram-(?:state|cluster-alt|cluster|note)(?= |$)""", "g")

private object K {
  const val State = 0
  const val Described = 1
  const val Start = 2
  const val End = 3
  const val Choice = 4
  const val Fork = 5
  const val Join = 6
  const val Note = 7
  const val Composite = 8
  const val Region = 9
  const val Hidden = 10
}

private val KINDS: Map<String?, Int> = mapOf(
  "rectWithTitle" to K.Described,
  "stateStart" to K.Start,
  "stateEnd" to K.End,
  "choice" to K.Choice,
  "fork" to K.Fork,
  "join" to K.Join,
  "note" to K.Note,
  "divider" to K.Hidden,
)

private val CLASSES = listOf(
  "pele-node pele-state",
  "pele-node pele-state",
  "pele-node pele-state-start",
  "pele-node pele-state-end",
  "pele-node pele-state-choice",
  "pele-node pele-state-fork",
  "pele-node pele-state-join",
  "pele-note",
  "pele-cluster pele-state-composite",
)

private class View(
  // Absent for the unseen helpers the layout needs.
  val node: StateNode?,
  val kind: Int,
  var style: ResolvedStyle?,
  var label: Label,
  val lines: MutableList<Label>,
  var w: Double,
  var h: Double,
  var titleH: Double,
)

private fun vertical(dir: Dir?): Boolean = dir == "TB" || dir == "BT"

private fun numberOption(config: Config, key: String, fallback: Double): Double {
  val section = config["state"] as? Map<*, *>
  val value = section?.get(key)
  return if (value is Double && value > 0) min(value, 2000.0) else fallback
}

// Mermaid keeps the quotes of a click statement's strings and strips them when it draws.
private fun unquote(text: String): String {
  var from = 0
  var to = text.length
  while (from < to && text[from].code == 34) from++
  while (to > from && text[to - 1].code == 34) to--
  return text.jsSlice(from, to)
}

private fun clamp(value: Double, min: Double, max: Double): Double = if (value < min) min else if (value > max) max else value

private fun rect(x: Double, y: Double, w: Double, h: Double, rest: String): String =
  "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(w)}\" height=\"${num(h)}\"$rest/>"

private fun group(dir: Dir, padX: Double, padTop: Double, padBottom: Double): CNode {
  val c = cnode(0.0, 0.0)
  c.isGroup = true
  c.dir = dir
  c.padX = padX
  c.padTop = padTop
  c.padBottom = padBottom
  return c
}

fun renderState(db: StateDb, config: Config, options: RenderOptions): Rendered {
  if (!runsAcross(db.direction)) return draw(db, config, options, false)
  return turnToFit(options) { down -> draw(db, config, options, down) }
}

// `turned` draws a diagram that runs across as one that runs down.
private fun draw(db: StateDb, config: Config, options: RenderOptions, turned: Boolean): Rendered {
  val graph = db.graph
  // State diagrams follow the flowchart curve.
  val curve = (config["flowchart"] as? Map<*, *>)?.get("curve")
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val wrapWidth = numberOption(config, "wrappingWidth", 200.0)
  val pad = options.padding ?: 8.0
  val icons = options.icons
  val rootDir = if (turned) "TB" else direction(db.direction)
  val noLabel = layoutLabel(null, false, measurer, size, 0.0)
  val text = { raw: String?, fontSize: Double, wrap: Double, style: Int -> layoutLabel(raw, true, measurer, fontSize, wrap, style) }

  val index = LinkedHashMap<String, Int>()
  val views = ArrayList<View>()
  val cnodes = ArrayList<CNode>()
  val add = { node: StateNode?, kind: Int, c: CNode? ->
    views.add(View(node, kind, null, noLabel, ArrayList(), 0.0, 0.0, 0.0))
    if (c != null) cnodes.add(c)
    views.size - 1
  }
  for (node in graph.nodes) {
    if (node.shape == "noteGroup") continue
    val kind = if (node.isGroup) (if (node.shape == "divider") K.Region else K.Composite) else (KINDS[node.shape] ?: K.State)
    index[node.id] = add(node, kind, null)
  }
  val states = views.size

  val logical = ArrayList<Int>()
  for (i in 0 until states) {
    val parentId = views[i].node!!.parentId
    val parent = if (parentId == null) null else index[parentId]
    logical.add(if (parent != null && parent != i && views[parent].node!!.isGroup) parent else -1)
  }
  // Composites that are redeclared inside each other form a ring with no way in from the root.
  // One member of each ring moves to the root, which leaves a tree.
  run {
    val kids = ArrayList<MutableList<Int>>()
    for (i in 0 until states) kids.add(ArrayList())
    val queue = ArrayList<Int>()
    val placed = IntArray(states)
    for (i in 0 until states) {
      if (logical[i] == -1) {
        placed[i] = 1
        queue.add(i)
      } else {
        kids[logical[i]].add(i)
      }
    }
    var done = 0
    val settle = {
      while (done < queue.size) {
        for (kid in kids[queue[done]]) {
          if (placed[kid] != 0) continue
          placed[kid] = 1
          queue.add(kid)
        }
        done++
      }
    }
    settle()
    val seen = IntArray(states) { -1 }
    for (i in 0 until states) {
      if (placed[i] != 0) continue
      var ring = i
      while (seen[ring] != i) {
        seen[ring] = i
        ring = logical[ring]
      }
      logical[ring] = -1
      placed[ring] = 1
      queue.add(ring)
      settle()
    }
  }
  val owners = IntArray(states) { -1 }
  for (i in 0 until states) {
    val noteFor = views[i].node!!.noteFor
    val owner = if (noteFor == null) null else index[noteFor]
    if (owner == null) continue
    owners[i] = owner
    logical[i] = logical[owner]
  }

  // As in Mermaid, a composite that sets no direction runs top to bottom whatever surrounds it.
  val innerDir = { composite: Int -> direction(views[composite].node!!.dir) }
  // Whether the flow around a state runs top to bottom or bottom to top.
  val across = { i: Int -> vertical(if (logical[i] == -1) rootDir else innerDir(logical[i])) }

  val degree = IntArray(states)
  val noted = IntArray(states)
  // When each state first takes part in a transition. Layout follows this order rather than
  // the order of declaration, so a state declared up front does not become the top of the flow.
  val appear = ArrayList<Double>()
  for (i in 0 until states) appear.add(Double.POSITIVE_INFINITY)
  var appeared = 0
  val counts = IntArray(2 * states)
  for (edge in graph.edges) {
    val src = index[edge.start]
    val dst = index[edge.end]
    if (src == null || dst == null) continue
    if (edge.pattern == "dashed") {
      noted[if (views[src].kind == K.Note) dst else src] = 1
      continue
    }
    degree[src] = max(degree[src], ++counts[2 * src])
    degree[dst] = max(degree[dst], ++counts[2 * dst + 1])
    if (appear[src] == Double.POSITIVE_INFINITY) appear[src] = (appeared++).toDouble()
    if (appear[dst] == Double.POSITIVE_INFINITY) appear[dst] = (appeared++).toDouble()
  }

  for (i in 0 until states) {
    val view = views[i]
    val node = view.node!!
    val style = resolveStyle(if (node.cssStyles.size > 0) node.cssCompiledStyles + node.cssStyles else node.cssCompiledStyles)
    val textStyle = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val fontSize = style.fontSize ?: size
    view.style = style
    var c: CNode? = null
    when (view.kind) {
      K.Composite -> {
        view.label = text(node.label, fontSize, 4000.0, textStyle)
        view.titleH = if (view.label.height > 0) view.label.height + 12 else 0.0
        c = group(innerDir(i), GROUP_PAD, view.titleH + GROUP_PAD, GROUP_PAD)
        c.minW = view.label.width + 2 * GROUP_PAD
      }
      K.Region -> c = group(innerDir(i), REGION_PAD, REGION_PAD, REGION_PAD)
      K.Start -> {
        view.h = 14.0
        view.w = view.h
      }
      K.End -> {
        view.h = 18.0
        view.w = view.h
      }
      K.Choice -> {
        view.h = 30.0
        view.w = view.h
      }
      K.Fork, K.Join -> {
        val length = clamp(40.0 * degree[i] + 16, 72.0, 320.0)
        view.w = if (across(i)) length else BAR
        view.h = if (across(i)) BAR else length
      }
      K.Hidden -> {}
      K.Note -> {
        view.label = text(node.label, small, max(wrapWidth, NOTE_WRAP), 0)
        view.w = view.label.width + 24
        view.h = view.label.height + 16
      }
      else -> {
        view.label = text(node.label, fontSize, wrapWidth, textStyle)
        var width = view.label.width
        var height = view.label.height + 2 * PAD_Y
        if (view.kind == K.Described) {
          height += 14
          for (line in node.description) {
            val label = text(if (line == "") " " else line, small, wrapWidth, textStyle)
            view.lines.add(label)
            if (label.width > width) width = label.width
            height += label.height
          }
        }
        view.w = max(width + 2 * PAD_X, 56.0)
        view.h = height
      }
    }
    if (c == null) c = cnode(view.w, view.h)
    // The transitions on one side of a state's box spread along it, clear of its corners.
    if (view.kind == K.State || view.kind == K.Described) c.span = (if (across(i)) view.w else view.h) - 24
    cnodes.add(c)
  }

  val cedges = ArrayList<CEdge>()
  val edgeLabels = ArrayList<Label>()
  val drawn = ArrayList<StateEdge>()
  // The transitions each state makes to itself: how many, their widest label, and their labels' height.
  val loopCount = IntArray(states)
  val loopWidth = DoubleArray(states)
  val loopHeight = DoubleArray(states)
  // A transition between a composite and a state inside it runs between that state and a point
  // just inside the composite, and is then drawn on to the composite's border.
  val anchors = LinkedHashMap<Int, Int>()
  val encloses = { composite: Int, item: Int ->
    var found = false
    if (cnodes[composite].isGroup) {
      var p = logical[item]
      while (p != -1) {
        if (p == composite) {
          found = true
          break
        }
        p = logical[p]
      }
    }
    found
  }
  val anchor = { composite: Int, first: Boolean ->
    anchors[cedges.size] = composite
    appear.add(if (first) -1.0 else Double.POSITIVE_INFINITY)
    add(null, K.Hidden, cnode(0.0, 0.0, composite))
  }
  for (edge in graph.edges) {
    var src = index[edge.start]
    var dst = index[edge.end]
    if (src == null || dst == null) continue
    val label = text(edge.label, small, wrapWidth, 0)
    val labelW = if (label.width > 0) label.width + 12 else 0.0
    if (src == dst) {
      loopCount[src]++
      loopWidth[src] = max(loopWidth[src], labelW)
      loopHeight[src] += label.height
    } else if (encloses(src, dst)) {
      src = anchor(src, true)
    } else if (encloses(dst, src)) {
      dst = anchor(dst, false)
    }
    drawn.add(edge)
    edgeLabels.add(label)
    cedges.add(CEdge(src, dst, 1.0, labelW, if (label.height > 0) label.height + 4 else 0.0, ArrayList(), 0.0, 0.0))
  }

  // A state with notes, or a composite with self-transitions, sits in an unseen group of its
  // own: the notes go beside the state, across the flow, without disturbing its transitions.
  for (i in 0 until states) {
    if (owners[i] != -1) continue
    val item = cnodes[i]
    // Loops hang off the right, or below when the flow is horizontal, with their labels beyond them.
    var room = 0.0
    if (loopCount[i] > 0) {
      val reach = LOOP + (loopCount[i] - 1) * 8
      room = if (across(i)) reach + loopWidth[i] else reach * 0.75 + 4 + loopHeight[i]
      if (!item.isGroup) {
        if (across(i)) {
          item.w += 2 * room
          item.h = max(item.h, loopHeight[i])
        } else {
          item.h += 2 * room
          item.w = max(item.w, loopWidth[i])
        }
        room = 0.0
      }
    }
    if (noted[i] == 0 && room == 0.0) {
      item.parent = logical[i]
      continue
    }
    val c = if (across(i)) group("LR", room, 0.0, 0.0) else group("TB", 0.0, room, room)
    c.parent = logical[i]
    appear.add(appear[i])
    cnodes[i].parent = add(null, K.Hidden, c)
  }
  for (i in 0 until states) if (owners[i] != -1) cnodes[i].parent = cnodes[owners[i]].parent

  run {
    // Numbers the tree so that, at every level, initial states come first and the rest follow
    // in the order the transitions introduce them.
    val members = ArrayList<MutableList<Int>>()
    for (i in 0 until cnodes.size) members.add(ArrayList())
    val top = ArrayList<Int>()
    for (i in 0 until cnodes.size) {
      val p = cnodes[i].parent
      if (p >= 0) members[p].add(i) else top.add(i)
    }
    val initial = { i: Int -> if (views[i].kind == K.Start) 0 else 1 }
    val byFlow = Comparator<Int> { a, b ->
      val first = initial(a) - initial(b)
      if (first != 0) {
        first
      } else {
        val x: Double = appear[a]
        val y: Double = appear[b]
        val second = if (x == y) 0 else if (x < y) -1 else 1
        if (second != 0) second else a - b
      }
    }
    var seq = 0
    top.sortWith(byFlow)
    top.reverse()
    val pending = top
    while (pending.size > 0) {
      val i = pending.removeAt(pending.size - 1)
      cnodes[i].seq = (seq++).toDouble()
      val inside = members[i]
      inside.sortWith(byFlow)
      for (k in inside.size - 1 downTo 0) pending.add(inside[k])
    }
  }

  val layout = compoundLayout(
    cnodes,
    cedges,
    rootDir,
    LayeredOptions(
      nodeSep = numberOption(config, "nodeSpacing", NODE_SEP),
      edgeSep = EDGE_SEP,
      rankSep = numberOption(config, "rankSpacing", RANK_SEP),
      portSep = 20.0,
      tracks = curve == "step" || curve == "stepBefore" || curve == "stepAfter",
      straight = curve == "linear",
      headRoom = markerTrim("arrow_point"),
    ),
  )

  val width = ceil(layout.width + 2 * pad)
  val height = ceil(layout.height + 2 * pad)
  val links = ArrayList<LinkInfo>()

  // Route points by rounded y, to find the edges that cross a composite's title bar.
  val crossings = LinkedHashMap<Double, MutableList<Double>>()
  for (e in cedges) {
    val route = e.route
    var k = 3
    while (k < route.size - 3) {
      val key = jsRound(route[k + 1]) + 0.0
      val list = crossings[key]
      if (list != null) list.add(route[k]) else crossings[key] = arrayListOf(route[k])
      k += 3
    }
  }

  val children = ArrayList<MutableList<Int>>()
  for (i in 0 until cnodes.size) children.add(ArrayList())
  val order = ArrayList<Int>()
  for (i in 0 until cnodes.size) {
    val p = cnodes[i].parent
    if (p >= 0) children[p].add(i) else order.add(i)
  }
  val roots = ArrayList(order)
  var q = 0
  while (q < order.size) {
    for (child in children[order[q]]) order.add(child)
    q++
  }

  var clusters = ""
  val fills = ArrayList<Fill>()
  var dividers = ""
  var nodesOut = ""
  var notesOut = ""
  // Draws the dashed lines between the regions among a composite's members.
  fun divide(items: List<Int>, left: Double, top: Double, right: Double, bottom: Double) {
    val regions = items.filter { views[it].kind == K.Region }
    if (regions.size < 2) return
    val lo = { i: Int, x: Boolean -> if (x) cnodes[i].x - cnodes[i].w / 2 else cnodes[i].y - cnodes[i].h / 2 }
    val hi = { i: Int, x: Boolean -> if (x) cnodes[i].x + cnodes[i].w / 2 else cnodes[i].y + cnodes[i].h / 2 }
    for (x in listOf(true, false)) {
      val sorted = regions.sortedWith { a, b -> cmp((lo(a, x) - lo(b, x)).jsOr { (a - b).toDouble() }) }
      var apart = true
      for (k in 1 until sorted.size) if (hi(sorted[k - 1], x) > lo(sorted[k], x) + 0.5) apart = false
      if (!apart) continue
      for (k in 1 until sorted.size) {
        val at = num((hi(sorted[k - 1], x) + lo(sorted[k], x)) / 2 + pad)
        dividers += "<path class=\"pele-divider\" d=\"M${if (x) "$at,${num(top)}V${num(bottom)}" else "${num(left)},${at}H${num(right)}"}\"/>"
      }
      return
    }
    // Regions that sit neither side by side nor in a stack each get an outline.
    for (i in regions) {
      val c = cnodes[i]
      dividers += rect(c.x - c.w / 2 + pad, c.y - c.h / 2 + pad, c.w, c.h, " class=\"pele-divider\" rx=\"${jsStr(CORNER)}\"")
    }
  }
  divide(roots, pad / 2, pad / 2, width - pad / 2, height - pad / 2)

  // Picks where a composite's title sits in its title bar so that edges entering from above do not cross it.
  fun titleX(c: CNode, width: Double): Double {
    val xs = crossings[jsRound(c.y - c.h / 2) + 0.0]
    if (xs == null || width == 0.0) return c.x
    val left = c.x - c.w / 2
    val half = width / 2
    var best = c.x
    var bestGap = -1.0
    for (x in listOf(c.x, left + GROUP_PAD + half, left + c.w - GROUP_PAD - half)) {
      var gap = Double.POSITIVE_INFINITY
      for (cx in xs) if (cx > left && cx < left + c.w) gap = min(gap, abs(cx - x) - half)
      if (gap >= 6) return x
      if (gap > bestGap + 0.5) {
        bestGap = gap
        best = x
      }
    }
    return best
  }

  for (i in order) {
    val view = views[i]
    val node = view.node
    val style = view.style
    if (node == null || style == null || view.kind >= K.Region) continue
    val c = cnodes[i]
    val x = c.x + pad
    val y = c.y + pad
    val w = view.w
    val h = view.h
    val ink = inkFill(style)
    val labelAttrs = " class=\"pele-label\"$ink${style.text}"
    var body: String

    when (view.kind) {
      K.Composite -> {
        val left = x - c.w / 2
        val top = y - c.h / 2
        if (paints(style.shape)) fills.add(Fill(doubleArrayOf(left, top, left + c.w, top + c.h), style.shape))
        body = rect(left, top, c.w, c.h, " rx=\"${jsStr(CORNER)}\" fill=\"var(--_a)\"${if (setsFill(style.shape)) "" else " fill-opacity=\"0.5\""} stroke=\"var(--_b)\"${style.shape}")
        if (view.titleH > 0) {
          body +=
            "<path d=\"M${num(left)},${num(top + view.titleH)}H${num(left + c.w)}\"$RULE${style.line}/>" +
            labelSvg(
              view.label,
              titleX(c, view.label.width) + pad,
              top + view.titleH / 2,
              " class=\"pele-cluster-label\"$ink${style.text}",
              icons,
            )
        }
        divide(children[i], left, top + view.titleH, left + c.w, top + c.h)
      }
      K.Start -> body = "<circle r=\"7\" fill=\"var(--_l)\"${style.shape}/>"
      K.End -> body = "<circle r=\"8.5\" fill=\"var(--_bg)\" stroke=\"var(--_l)\"${style.line}/><circle r=\"5\" fill=\"var(--_l)\"${style.shape}/>"
      K.Choice -> body = "<polygon points=\"0,${num(-h / 2)} ${num(w / 2)},0 0,${num(h / 2)} ${num(-w / 2)},0\"$SURFACE${style.shape}/>"
      K.Fork, K.Join -> body = rect(-w / 2, -h / 2, w, h, " rx=\"2\" fill=\"var(--_l)\"${style.shape}")
      K.Note -> {
        val r = num(w / 2)
        val b = num(h / 2)
        val fold = num(w / 2 - FOLD)
        val corner = num(FOLD - h / 2)
        body =
          "<path d=\"M-$r,-${b}H${fold}L$r,${corner}V${b}H-${r}Z\" fill=\"var(--_bg)\" stroke=\"var(--_b)\"/>" +
          "<path d=\"M$fold,-${b}V${corner}H$r\"$RULE/>" +
          labelSvg(view.label, 0.0, 0.0, " class=\"pele-label\" font-size=\"${jsStr(small)}\" fill=\"var(--_m)\"", icons)
      }
      else -> {
        body = rect(-w / 2, -h / 2, w, h, " rx=\"${num(min(CORNER, h / 2))}\"$SURFACE${style.shape}")
        if (view.kind == K.State) {
          body += labelSvg(view.label, 0.0, 0.0, labelAttrs, icons)
        } else {
          var at = PAD_Y - h / 2 + view.label.height
          body += "<path d=\"M${num(-w / 2)},${num(at + 6)}H${num(w / 2)}\"$RULE${style.line}/>" + labelSvg(view.label, 0.0, at - view.label.height / 2, labelAttrs, icons)
          at += 14
          for (line in view.lines) {
            body += labelSvg(line, 0.0, at + line.height / 2, " class=\"pele-state-description\" font-size=\"${jsStr(small)}\"$ink${style.text}", icons)
            at += line.height
          }
        }
      }
    }

    val link = db.links[node.id]
    if (link != null) {
      // Entity codes are resolved first, so the URL is checked in the form a browser would follow.
      val tooltip = decodeEntities(unquote(link.tooltip))
      if (tooltip.isNotEmpty()) body = "<title>${esc(tooltip)}</title>" + body
      body = linked(body, safeUrl(unquote(link.url)), node.id, links, options, " target=\"_blank\"")
    }
    val open = "<g class=\"${CLASSES[view.kind]}${classNames(node.cssClasses.jsReplace(MERMAID_CLASSES, ""))}\" data-id=\"${escText(node.id)}\""
    if (view.kind == K.Composite) clusters += "$open>$body</g>"
    else if (view.kind == K.Note) notesOut += "$open transform=\"translate(${num(x)},${num(y)})\">$body</g>"
    else nodesOut += "$open transform=\"translate(${num(x)},${num(y)})\">$body</g>"
  }

  // Moves a route end from the layout box onto the drawn shape, and spreads the ends along a bar.
  fun attach(route: MutableList<Double>, at: Int, i: Int) {
    val c = cnodes[i]
    if (c.isGroup) return
    val view = views[i]
    val bar = view.kind == K.Fork || view.kind == K.Join
    val other = if (at == 0) 3 else at - 3
    // 0 is the x axis and 1 the y axis; the end sits on a side that faces along `axis`.
    for (axis in intArrayOf(1, 0)) {
      val extent = if (axis != 0) c.h else c.w
      val drawnExtent = if (axis != 0) view.h else view.w
      val breadth = if (axis != 0) view.w else view.h
      val center = (if (axis != 0) c.y else c.x) + pad
      val cross = (if (axis != 0) c.x else c.y) + pad
      val delta = route[at + axis] - center
      if (abs(abs(delta) - extent / 2) >= 0.5) continue
      route[at + axis] = route[at + axis] - (sign(delta) * (extent - drawnExtent)) / 2
      if (bar && breadth > drawnExtent) {
        route[at + 1 - axis] = clamp(route[other + 1 - axis], cross - breadth / 2 + 6, cross + breadth / 2 - 6)
      }
      return
    }
  }

  val reachOf = sideReach()
  for (i in 0 until cedges.size) {
    val ce = cedges[i]
    if (ce.src == ce.dst || ce.route.size < 6 || drawn[i].pattern == "dashed") continue
    reachOf.add(ce.dst, endSide(ce.route, ce.route.size - 3, cnodes[ce.dst]), "arrow_point")
  }

  var casings = ""
  var edgesOut = ""
  var labelsOut = ""
  val loopSeen = IntArray(states)
  val loopText = DoubleArray(states)
  for (i in 0 until cedges.size) {
    val ce = cedges[i]
    val edge = drawn[i]
    val label = edgeLabels[i]
    val note = edge.pattern == "dashed"
    val endType = if (note) "none" else "arrow_point"
    val path: EdgePath
    if (ce.src == ce.dst) {
      val a = cnodes[ce.src]
      val view = views[ce.src]
      val k = loopSeen[ce.src]++
      // The loop hangs off a side the flow does not use: the right, or below when the flow is horizontal.
      val side = across(ce.src)
      val w = if (a.isGroup) a.w else view.w
      val h = if (a.isGroup) a.h else view.h
      val out = (if (side) w else h) / 2
      val spread = max(min((if (side) h else w) / 2 - 4, 8.0 + k * 6), 2.0)
      val reach = LOOP + k * 8
      val trim = markerTrim(endType) / jsHypot(reach, spread).jsOr { 1.0 }
      // Offsets are given for a loop on the right and turned a quarter for one below.
      val px = { dx: Double, dy: Double -> a.x + pad + (if (side) out + dx else -dy) }
      val py = { dx: Double, dy: Double -> a.y + pad + (if (side) dy else out + dx) }
      val at = { dx: Double, dy: Double -> "${num(px(dx, dy))},${num(py(dx, dy))}" }
      val rawDx = px(-reach, -spread) - px(0.0, 0.0)
      val rawDy = py(-reach, -spread) - py(0.0, 0.0)
      val len = jsHypot(rawDx, rawDy).jsOr { 1.0 }
      path = EdgePath(
        d = "M${at(0.0, -spread)}C${at(reach, -2 * spread)} ${at(reach, 2 * spread)} ${at(reach * trim, spread + spread * trim)}",
        sx = 0.0,
        sy = 0.0,
        sdx = 0.0,
        sdy = 0.0,
        ex = px(0.0, spread),
        ey = py(0.0, spread),
        edx = rawDx / len,
        edy = rawDy / len,
      )
      // Labels stack beyond the outermost loop, in the order of their transitions.
      val furthest = LOOP + (loopCount[ce.src] - 1) * 8
      val below = loopText[ce.src] + label.height / 2
      loopText[ce.src] += label.height
      ce.labelX = if (side) a.x + out + furthest + 4 + ce.labelW / 2 else a.x
      ce.labelY = if (side) a.y - loopHeight[ce.src] / 2 + below else a.y + out + furthest * 0.75 + 4 + below
    } else {
      var route: MutableList<Double> = ArrayList(ce.route)
      val leaves = reachOf.of(ce.src, endSide(route, 0, cnodes[ce.src]))
      val arrives = reachOf.of(ce.dst, endSide(route, route.size - 3, cnodes[ce.dst]))
      var k = 0
      while (k < route.size) {
        route[k] = route[k] + pad
        route[k + 1] = route[k + 1] + pad
        k += 3
      }
      attach(route, 0, ce.src)
      attach(route, route.size - 3, ce.dst)
      val composite = anchors[i]
      if (composite != null && route.size >= 6) {
        val g = cnodes[composite]
        val inward = views[ce.src].kind == K.Hidden
        val at = if (inward) 0 else route.size - 3
        val near = (g.dir == "TB" || g.dir == "LR") == inward
        val point =
          if (vertical(g.dir)) listOf(route[at], g.y + pad + (if (near) views[composite].titleH - g.h / 2 else g.h / 2), 0.0)
          else listOf(g.x + pad + (if (near) -g.w / 2 else g.w / 2), route[at + 1], 1.0)
        route = if (inward) (point + route).toMutableList() else (route + point).toMutableList()
      }
      path = routePath(route, if (curve is String) curve else null, 0.0, markerTrim(endType), leaves, arrives)
    }
    val over = overFills(path.d, doubleArrayOf(path.ex, path.ey), fills, "", path.tokens)
    if (over == "") casings += edgeCasing(path, "none", endType)
    val kind =
      if (note) "pele-note-edge\" stroke=\"${if (over.isNullOrEmpty()) "var(--_b)" else over}\" stroke-dasharray=\"3 4"
      else if (!over.isNullOrEmpty()) "pele-transition\" stroke=\"$over"
      else "pele-transition"
    edgesOut +=
      "<g class=\"pele-edge $kind\" data-id=\"${escText(edge.id)}\">" +
      "<path d=\"${path.d}\"/>" +
      marker(endType, path.ex, path.ey, path.edx, path.edy, if (over.isNullOrEmpty()) "var(--_l)" else over) +
      "</g>"
    if (label.width > 0) {
      val x = ce.labelX + pad
      val y = ce.labelY + pad
      labelsOut += edgeLabelSvg(edge.id, label, x, y, "", icons)
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
      (if (dividers.isNotEmpty()) "<g class=\"pele-dividers\" stroke-dasharray=\"4 4\"$RULE>$dividers</g>" else "") +
      casingsSvg(casings) +
      (if (edgesOut.isNotEmpty()) "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>" else "") +
      (if (labelsOut.isNotEmpty()) "<g class=\"pele-edge-labels\" font-size=\"${jsStr(small)}\">$labelsOut</g>" else "") +
      "<g class=\"pele-nodes\">$nodesOut$notesOut</g>",
  )
  val svg = svgDocument("state", titled.width, titled.height, size, options, db, titled.content)

  return Rendered(svg, titled.width, titled.height, links)
}
