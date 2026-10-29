// Ported from Pele src/diagrams/agentflow/graph.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import pele.js.jsOrdered
import pele.js.jsTrim
import pele.util.YamlValue

class GraphNode(
  override val id: String,
  var label: String?,
  var labelType: LabelType,
  override var shape: String,
  override val isGroup: Boolean,
  var parentId: String?,
  var cssStyles: MutableList<String>,
  var cssCompiledStyles: MutableList<String>,
  var cssClasses: String,
  var dir: String?,
  var link: String? = null,
  var linkTarget: String? = null,
  var tooltip: String? = null,
  // Palette slot of a container.
  override var colorIndex: Int? = null,
  // What a node is, which picks its palette slot.
  override var kind: String? = null,
  var metadata: Metadata? = null,
) : SlotNode, ShapedNode

class GraphEdge(
  val id: String,
  val isUserDefinedId: Boolean,
  val start: String,
  val end: String,
  val type: String,
  val label: String,
  val labelType: LabelType,
  val thickness: String?,
  val pattern: String?,
  val minlen: Double?,
  val classes: String,
  val arrowTypeStart: String,
  val arrowTypeEnd: String,
  val cssCompiledStyles: List<String>,
  val style: List<String>,
  val animate: YamlValue,
  val animation: YamlValue,
  val curve: String?,
  val metadata: Metadata?,
)

class AgentGraph(val nodes: MutableList<GraphNode>, val edges: MutableList<GraphEdge>)

// Resolves a parsed agentflow into the nodes and edges that are drawn: flow membership, collapsed
// flows, shapes reduced to the ones agentflow has, class styles and palette slots. Shape and
// containment problems are reported on the model as diagnostics.
fun buildAgentGraph(db: AgentflowDb, paletteLength: Int, defaultCurve: String? = null): AgentGraph {
  val nodes = ArrayList<GraphNode>()
  val edges = ArrayList<GraphEdge>()
  val subgraphs = db.subgraphs

  val compiled = { classNames: List<String> ->
    var out: List<String> = ArrayList()
    for (name in classNames) {
      val def = db.classes[name]
      if (def != null) out = (out + def.styles).map { s -> s.jsTrim() }
      if (def != null) out = (out + def.textStyles).map { s -> s.jsTrim() }
    }
    out.toMutableList()
  }

  // Everything inside a collapsed flow is hidden, and stands for its outermost collapsed flow.
  // Mermaid walks the whole inside of each collapsed flow, in the order the flows closed, and the
  // last walk to reach a thing wins. A thing has one container, so a later walk can only enter an
  // earlier one through its root: it stops there, and the root's owner is followed afterwards.
  val hidden = LinkedHashSet<String>()
  val owner = HashMap<String, String>()
  val walked = HashSet<String>()
  for (sg in subgraphs) {
    if (sg.metadata?.get("view") != "collapsed" || hidden.contains(sg.id)) continue
    val pending = arrayListOf(sg.id)
    while (pending.size > 0) {
      val members = db.subgraph(pending.removeAt(pending.size - 1))?.nodes
      if (members == null) continue
      for (childId in members) {
        if (childId == sg.id || hidden.contains(childId)) continue
        hidden.add(childId)
        owner[childId] = sg.id
        if (!walked.contains(childId)) pending.add(childId)
      }
    }
    walked.add(sg.id)
  }
  val collapsedInto = HashMap<String, String>()
  for (id in hidden) {
    val path = arrayListOf(id)
    var top = owner[id]!!
    while (true) {
      val known = collapsedInto[top]
      val up = known ?: owner[top]
      if (up == null) break
      if (known == null) path.add(top)
      top = up
      if (known != null) break
    }
    for (p in path) collapsedInto[p] = top
  }

  val position = HashMap<String, Int>()
  subgraphs.forEachIndexed { i, sg -> position[sg.id] = i }

  // Two flows can each name the other. The nesting that would close the loop is dropped.
  val parentOf = HashMap<String, String>()
  val closesLoop = { childId: String, parentId: String, at: Int ->
    // Only a flow handled earlier in this pass can already be above the parent.
    val childAt = position[childId]
    if (childAt == null || childAt < at) {
      false
    } else {
      var result = false
      var current: String? = parentId
      var hops = 0
      while (current != null && hops <= subgraphs.size) {
        if (current == childId) {
          result = true
          break
        }
        current = parentOf[current]
        hops++
      }
      result
    }
  }
  for (i in subgraphs.size - 1 downTo 0) {
    val sg = subgraphs[i]
    if (hidden.contains(sg.id)) continue
    for (id in sg.nodes) {
      if (closesLoop(id, sg.id, i)) {
        db.emitWarning(
          "CONTAINMENT_VIOLATION",
          "Container \"${sg.id}\" cannot contain \"$id\" because \"$id\" already contains it. The nesting that would close the loop is dropped.",
          EmitContext(nodeId = id),
        )
        continue
      }
      parentOf[id] = sg.id
    }
  }

  val byId = HashMap<String, GraphNode>()
  for (i in subgraphs.size - 1 downTo 0) {
    val sg = subgraphs[i]
    if (hidden.contains(sg.id)) continue
    val collapsed = sg.metadata?.get("view") == "collapsed"
    val metadata: Metadata? = if (collapsed) {
      val spread = LinkedHashMap<String, YamlValue>()
      sg.metadata?.let { spread.putAll(it) }
      spread["containerType"] = sg.type
      jsOrdered(spread)
    } else {
      sg.metadata
    }
    val node = GraphNode(
      id = sg.id,
      label = sg.title,
      labelType = sg.labelType,
      parentId = parentOf[sg.id],
      cssStyles = ArrayList(),
      cssCompiledStyles = compiled(sg.classes),
      cssClasses = sg.classes.joinToString(" "),
      shape = if (collapsed) "collapsedGroup" else "flowGroup",
      dir = sg.dir,
      isGroup = !collapsed,
      metadata = metadata,
    )
    nodes.add(node)
    byId[sg.id] = node
  }

  for (vertex in db.nodes.values) {
    if (hidden.contains(vertex.id)) continue
    val existing = byId[vertex.id]
    if (existing != null) {
      existing.cssStyles = vertex.styles
      existing.cssCompiledStyles = compiled(vertex.classes)
      existing.cssClasses = vertex.classes.joinToString(" ")
      continue
    }
    val type = resolveShapeAlias(vertex.type)
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
      tooltip = db.tooltips[vertex.id],
      metadata = vertex.metadata,
      isGroup = false,
      shape = if (type == "square" || type == null) "squareRect" else type,
    )
    nodes.add(node)
    byId[vertex.id] = node
  }

  db.edges.forEachIndexed { index, raw ->
    val start = collapsedInto[raw.start] ?: raw.start
    val end = collapsedInto[raw.end] ?: raw.end
    // An edge between two things inside one collapsed flow has nothing left to connect.
    if (start == end && raw.start != raw.end) return@forEachIndexed
    val styles = ArrayList(db.defaultEdgeStyle ?: emptyList())
    val rawStyle = raw.style
    if (rawStyle != null) styles.addAll(rawStyle)
    val arrow =
      if (raw.type == "arrow_open") "none" else if (raw.type == "arrow_cross" || raw.type == "arrow_point") raw.type!! else "arrow_point"
    val rawId = raw.id
    val interpolate = raw.interpolate
    val defaultInterpolate = db.defaultInterpolate
    edges.add(
      GraphEdge(
        id = if (!rawId.isNullOrEmpty()) rawId else "L_${start}_${end}_$index",
        isUserDefinedId = raw.isUserDefinedId,
        start = start,
        end = end,
        type = raw.type ?: "normal",
        label = raw.text,
        labelType = raw.labelType,
        thickness = raw.stroke,
        pattern = raw.stroke,
        minlen = raw.length,
        classes = "edge-thickness-normal edge-pattern-solid flowchart-link",
        arrowTypeStart = "none",
        arrowTypeEnd = arrow,
        cssCompiledStyles = compiled(raw.classes),
        style = styles,
        animate = raw.animate,
        animation = raw.animation,
        curve = if (!interpolate.isNullOrEmpty()) interpolate else if (!defaultInterpolate.isNullOrEmpty()) defaultInterpolate else defaultCurve,
        metadata = raw.metadata,
      ),
    )
  }

  normaliseNodeShapes(nodes, db)

  // Containers are numbered in the order they were written: a walk of the nesting, parents first.
  // `subgraphs` itself is in the order the blocks closed, with a nested flow before its parent.
  val children = HashMap<String, MutableList<String>>()
  for (sg in subgraphs) {
    val parent = parentOf[sg.id] ?: continue
    val kids = children[parent]
    if (kids != null) kids.add(sg.id)
    else children[parent] = arrayListOf(sg.id)
  }
  val order = LinkedHashMap<String, Int>()
  for (sg in subgraphs) {
    if (parentOf[sg.id] != null) continue
    val pending = arrayListOf(sg.id)
    while (pending.size > 0) {
      val id = pending.removeAt(pending.size - 1)
      if (order.containsKey(id)) continue
      order[id] = order.size
      val kids = children[id]
      if (kids != null) for (i in kids.size - 1 downTo 0) pending.add(kids[i])
    }
  }

  assignColorSlots(
    nodes,
    { id ->
      if (db.connectors.containsKey(id)) {
        "connector"
      } else {
        val vertex = db.nodes[id]
        if (vertex != null) db.vertexKind(vertex) else null
      }
    },
    order,
    paletteLength,
  )

  return AgentGraph(nodes, edges)
}
