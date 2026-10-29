// Ported from Pele src/diagrams/flowchart/graph.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

import pele.js.jsReplace
import pele.js.jsTrim
import pele.util.YamlValue

class GraphNode(
  val id: String,
  var label: String?,
  var labelType: LabelType,
  var shape: String,
  var isGroup: Boolean,
  var parentId: String?,
  var cssStyles: List<String>,
  var cssCompiledStyles: List<String>,
  var cssClasses: String,
  var dir: String?,
  var link: String? = null,
  var linkTarget: String? = null,
  var tooltip: String? = null,
  var icon: String? = null,
  var pos: String? = null,
  var img: String? = null,
  var assetWidth: Double? = null,
  var assetHeight: Double? = null,
  var constraint: String? = null,
  var colorIndex: Int? = null,
  var metadata: MutableMap<String, YamlValue>? = null,
)

class GraphEdge(
  val id: String,
  val isUserDefinedId: Boolean,
  var start: String,
  var end: String,
  var type: String,
  var label: String,
  var labelType: LabelType,
  var thickness: String?,
  var pattern: String?,
  var minlen: Double?,
  var arrowTypeStart: String,
  var arrowTypeEnd: String,
  var cssCompiledStyles: List<String>,
  var style: List<String>,
  var classes: String,
  var animate: Any?,
  var animation: Any?,
  var curve: String?,
)

class FlowGraph(val nodes: MutableList<GraphNode>, val edges: MutableList<GraphEdge>)

private fun shapeOf(vertex: FlowNode): String {
  if (!vertex.img.isNullOrEmpty()) return "imageSquare"
  if (!vertex.icon.isNullOrEmpty()) {
    if (vertex.form == "circle") return "iconCircle"
    if (vertex.form == "square") return "iconSquare"
    if (vertex.form == "rounded") return "iconRounded"
    return "icon"
  }
  return when (vertex.type) {
    "square", null -> "squareRect"
    "round" -> "roundedRect"
    else -> vertex.type!!
  }
}

private fun arrows(type: String?): Pair<String, String> {
  when (type) {
    "arrow_point", "arrow_circle", "arrow_cross" -> return Pair("none", type)
    "double_arrow_point", "double_arrow_circle", "double_arrow_cross" -> {
      val single = type.jsReplace("double_", "")
      return Pair(single, single)
    }
  }
  return Pair("none", "arrow_point")
}

private fun orNull(s: String?): String? = if (s.isNullOrEmpty()) null else s

// Resolves a parsed flowchart into the nodes and edges that are actually drawn:
// group membership, collapsed subgraphs, class styles and edge defaults.
fun buildFlowGraph(model: FlowDb, defaultCurve: String? = null): FlowGraph {
  val nodes = ArrayList<GraphNode>()
  val edges = ArrayList<GraphEdge>()
  val subgraphs = model.subgraphs

  val compiled = { classNames: List<String> ->
    val out = ArrayList<String>()
    for (name in classNames) {
      val def = model.classes[name] ?: continue
      for (s in def.styles) out.add(s.jsTrim())
      for (s in def.textStyles) out.add(s.jsTrim())
    }
    out
  }

  val subgraphParent = HashMap<String, String>()
  for (sg in subgraphs) {
    for (childId in sg.nodes) if (model.subgraph(childId) != null) subgraphParent[childId] = sg.id
  }

  val declarationIndex = HashMap<String, Int>()
  val childrenOf = HashMap<String, MutableList<String>>()
  for (sg in subgraphs) {
    val parent = subgraphParent[sg.id] ?: continue
    val kids = childrenOf[parent]
    if (kids != null) kids.add(sg.id)
    else childrenOf[parent] = arrayListOf(sg.id)
  }
  var nextIndex = 0
  for (sg in subgraphs) {
    if (subgraphParent.containsKey(sg.id)) continue
    val pending = arrayListOf(sg.id)
    while (pending.size > 0) {
      val id = pending.removeAt(pending.size - 1)
      if (declarationIndex.containsKey(id)) continue
      declarationIndex[id] = nextIndex++
      val kids = childrenOf[id]
      if (kids != null) for (i in kids.size - 1 downTo 0) pending.add(kids[i])
    }
  }

  val isCollapsed = { id: String -> model.subgraph(id)?.metadata?.get("view") == "collapsed" }
  var anyCollapsed = false
  for (sg in subgraphs) if (sg.metadata?.get("view") == "collapsed") anyCollapsed = true
  // The outermost collapsed subgraph around each subgraph, resolved once per chain of parents.
  val outermost = HashMap<String, String?>()
  val outermostCollapsed = { id: String ->
    val chain = ArrayList<String>()
    val seen = HashSet<String>()
    var current: String? = id
    while (current != null && !outermost.containsKey(current) && !seen.contains(current)) {
      seen.add(current)
      chain.add(current)
      current = subgraphParent[current]
    }
    var result = if (current != null) outermost[current] else null
    for (i in chain.size - 1 downTo 0) {
      if (result == null && isCollapsed(chain[i])) result = chain[i]
      outermost[chain[i]] = result
    }
    result
  }

  val hidden = HashSet<String>()
  val collapsedInto = HashMap<String, String>()
  for (sg in if (anyCollapsed) subgraphs else emptyList()) {
    val ancestor = outermostCollapsed(sg.id) ?: continue
    if (sg.id != ancestor) {
      hidden.add(sg.id)
      collapsedInto[sg.id] = ancestor
    }
    for (childId in sg.nodes) {
      if (childId == ancestor) continue
      hidden.add(childId)
      collapsedInto[childId] = ancestor
    }
  }

  val parentOf = HashMap<String, String>()
  val groups = HashSet<String>()
  for (i in subgraphs.size - 1 downTo 0) {
    val sg = subgraphs[i]
    if (hidden.contains(sg.id)) continue
    if (sg.nodes.size > 0) groups.add(sg.id)
    for (id in sg.nodes) parentOf[id] = sg.id
  }

  val byId = HashMap<String, GraphNode>()
  for (i in subgraphs.size - 1 downTo 0) {
    val sg = subgraphs[i]
    if (hidden.contains(sg.id)) continue
    val collapsed = sg.metadata?.get("view") == "collapsed"
    val node = GraphNode(
      id = sg.id,
      label = sg.title,
      labelType = sg.labelType,
      parentId = parentOf[sg.id],
      cssStyles = ArrayList(),
      cssCompiledStyles = compiled(sg.classes),
      cssClasses = sg.classes.joinToString(" "),
      shape = if (collapsed) "collapsedGroup" else "rect",
      dir = sg.dir,
      isGroup = !collapsed,
      colorIndex = declarationIndex[sg.id],
    )
    if (!collapsed) node.metadata = sg.metadata
    nodes.add(node)
    byId[sg.id] = node
  }

  for (vertex in model.nodes.values) {
    if (hidden.contains(vertex.id)) continue
    val existing = byId[vertex.id]
    if (existing != null) {
      existing.cssStyles = vertex.styles
      existing.cssCompiledStyles = compiled(vertex.classes)
      existing.cssClasses = vertex.classes.joinToString(" ")
      continue
    }
    val isGroup = groups.contains(vertex.id)
    val node = GraphNode(
      id = vertex.id,
      label = vertex.text,
      labelType = vertex.labelType,
      parentId = parentOf[vertex.id],
      cssStyles = vertex.styles,
      cssCompiledStyles = compiled(listOf("default", "node") + vertex.classes),
      cssClasses = "default " + vertex.classes.joinToString(" "),
      dir = vertex.dir,
      link = vertex.link,
      linkTarget = vertex.linkTarget,
      tooltip = model.tooltips[vertex.id],
      icon = vertex.icon,
      pos = vertex.pos,
      img = vertex.img,
      assetWidth = vertex.assetWidth,
      assetHeight = vertex.assetHeight,
      constraint = vertex.constraint,
      isGroup = isGroup,
      shape = if (isGroup) "rect" else shapeOf(vertex),
    )
    nodes.add(node)
  }

  model.edges.forEachIndexed { index, raw ->
    val start = collapsedInto[raw.start] ?: raw.start
    val end = collapsedInto[raw.end] ?: raw.end
    if (start == end && (collapsedInto.containsKey(raw.start) || collapsedInto.containsKey(raw.end))) return@forEachIndexed

    val (arrowStart, arrowEnd) = arrows(raw.type)
    val styles = ArrayList(model.defaultEdgeStyle ?: emptyList())
    val rawStyle = raw.style
    if (rawStyle != null) styles.addAll(rawStyle)
    val plain = raw.stroke == "invisible" || raw.type == "arrow_open"
    edges.add(
      GraphEdge(
        id = orNull(raw.id) ?: "L_${start}_${end}_$index",
        isUserDefinedId = raw.isUserDefinedId,
        start = start,
        end = end,
        type = raw.type ?: "normal",
        label = raw.text,
        labelType = raw.labelType,
        thickness = raw.stroke,
        pattern = raw.stroke,
        minlen = raw.length,
        classes = if (raw.stroke == "invisible") "" else "edge-thickness-normal edge-pattern-solid flowchart-link",
        arrowTypeStart = if (plain) "none" else arrowStart,
        arrowTypeEnd = if (plain) "none" else arrowEnd,
        cssCompiledStyles = compiled(raw.classes),
        style = styles,
        animate = raw.animate,
        animation = raw.animation,
        curve = orNull(raw.interpolate) ?: orNull(model.defaultInterpolate) ?: defaultCurve,
      ),
    )
  }

  return FlowGraph(nodes, edges)
}
