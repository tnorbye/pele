// Ported from Pele src/diagrams/class/graph.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

// 'rect' | 'classBox' | 'note' | 'interface'
typealias NodeShape = String

// 'aggregation' | 'extension' | 'composition' | 'dependency' | 'lollipop' | 'none'
typealias EndMarker = String

class GraphNode(
  val id: String,
  val label: String,
  val isGroup: Boolean,
  val shape: NodeShape,
  val parentId: String?,
  // Index of the namespace node this one is drawn in, or -1.
  var parent: Int,
  val classNode: ClassNode? = null,
  // Position among the classes, in declaration order. Namespaces and notes have none.
  val colorIndex: Int? = null,
)

class GraphEdge(
  val id: String,
  val start: String,
  val end: String,
  // Indexes into the node list. Ids alone are ambiguous: a class may be named `note0` or `interface0`.
  val source: Int,
  val target: Int,
  val label: String?,
  val arrowTypeStart: EndMarker,
  val arrowTypeEnd: EndMarker,
  val startLabelRight: String,
  val endLabelLeft: String,
  // 'solid' | 'dashed' | 'dotted'
  val pattern: String,
  val classes: String,
)

class ClassGraph(
  val nodes: List<GraphNode>,
  val edges: List<GraphEdge>,
)

private val MARKERS: List<EndMarker> = listOf("aggregation", "extension", "composition", "dependency", "lollipop")

private fun marker(type: RelationEnd): EndMarker = (if (type >= 0) MARKERS.getOrNull(type) else null) ?: "none"

// Resolves a parsed class diagram into the nodes and edges that are drawn. With `hierarchical`
// off, only namespaces the source declares are kept and each is labeled with its full name.
fun buildClassGraph(model: ClassModel, hierarchical: Boolean = true): ClassGraph {
  val nodes = ArrayList<GraphNode>()
  val edges = ArrayList<GraphEdge>()
  val namespaceAt = LinkedHashMap<String, Int>()
  val classAt = LinkedHashMap<String, Int>()
  val interfaceAt = LinkedHashMap<String, Int>()

  val declared = LinkedHashMap<String, String?>()
  fun declaredAncestor(id: String?): String? {
    if (hierarchical || id == null) return id
    val chain = ArrayList<String>()
    var current: String? = id
    var found: String? = null
    while (!current.isNullOrEmpty()) {
      if (declared.containsKey(current)) {
        found = declared[current]
        break
      }
      val ns = model.namespaces[current]
      if (ns == null) break
      if (ns.explicit) {
        found = current
        break
      }
      chain.add(current)
      current = ns.parent
    }
    for (visited in chain) declared[visited] = found
    return found
  }
  fun group(id: String?): Int = if (id == null) -1 else (namespaceAt[id] ?: -1)

  for (ns in model.namespaces.values) {
    if (!hierarchical && !ns.explicit) continue
    namespaceAt[ns.id] = nodes.size
    nodes.add(
      GraphNode(
        id = ns.id,
        label = if (hierarchical) ns.label else ns.id,
        isGroup = true,
        shape = "rect",
        parentId = if (hierarchical) ns.parent else null,
        parent = -1,
      )
    )
  }
  for (node in nodes) node.parent = group(node.parentId)

  var colorIndex = 0
  for (classNode in model.classes.values) {
    val parentId = declaredAncestor(classNode.parent)
    classAt[classNode.id] = nodes.size
    nodes.add(
      GraphNode(
        id = classNode.id,
        label = classNode.label,
        isGroup = false,
        shape = "classBox",
        parentId = parentId,
        parent = group(parentId),
        classNode = classNode,
        colorIndex = colorIndex++,
      )
    )
  }

  for (note in model.notes.values) {
    val parentId = declaredAncestor(note.parent)
    val target = if (note.className == null) null else classAt[note.className]
    if (target != null) {
      edges.add(
        GraphEdge(
          id = "edgeNote${note.index}",
          start = note.id,
          end = nodes[target].id,
          source = nodes.size,
          target = target,
          label = null,
          arrowTypeStart = "none",
          arrowTypeEnd = "none",
          startLabelRight = "",
          endLabelLeft = "",
          pattern = "dotted",
          classes = "relation",
        )
      )
    }
    nodes.add(GraphNode(note.id, note.text, false, "note", parentId, group(parentId)))
  }

  // Mermaid leaves every lollipop interface outside the namespaces. Here it stays beside its class.
  for (item in model.interfaces) {
    val owner = classAt[item.classId]
    interfaceAt[item.id] = nodes.size
    nodes.add(
      GraphNode(
        id = item.id,
        label = item.label,
        isGroup = false,
        shape = "interface",
        parentId = null,
        parent = if (owner == null) -1 else nodes[owner].parent,
      )
    )
  }

  var count = 0
  for (relation in model.relations) {
    count++
    val type1 = relation.relation.type1
    val type2 = relation.relation.type2
    // addRelation swaps in an interface for the lollipop end of a relation with no other marker.
    val startAt = if (type1 == RELATION_TYPE.LOLLIPOP && type2 == RELATION_NONE) interfaceAt else classAt
    val endAt = if (startAt === classAt && type2 == RELATION_TYPE.LOLLIPOP && type1 == RELATION_NONE) interfaceAt else classAt
    edges.add(
      GraphEdge(
        id = "id_${relation.id1}_${relation.id2}_$count",
        start = relation.id1,
        end = relation.id2,
        source = startAt[relation.id1] ?: -1,
        target = endAt[relation.id2] ?: -1,
        label = relation.title,
        arrowTypeStart = marker(type1),
        arrowTypeEnd = marker(type2),
        startLabelRight = if (relation.relationTitle1 == "none") "" else relation.relationTitle1,
        endLabelLeft = if (relation.relationTitle2 == "none") "" else relation.relationTitle2,
        pattern = if (relation.relation.lineType == 1) "dashed" else "solid",
        classes = "relation",
      )
    )
  }

  return ClassGraph(nodes, edges)
}
