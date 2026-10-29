// Ported from Pele src/diagrams/er/graph.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.js.jsSplit
import pele.js.jsTrim

class ErGraphNode(
  val id: String,
  val label: String,
  val alias: String,
  val attributes: List<ErAttribute>,
  val isGroup: Boolean,
  val parentId: String?,
  val cssStyles: List<String>,
  val cssCompiledStyles: List<String>,
  val cssClasses: String,
  val dir: String?,
  val labelType: String,
)

class ErGraphEdge(
  val id: String,
  val start: String,
  val end: String,
  val label: String,
  val arrowTypeStart: String,
  val arrowTypeEnd: String,
  // 'solid' | 'dashed'
  val pattern: String,
)

class ErGraph(
  val nodes: List<ErGraphNode>,
  val edges: List<ErGraphEdge>,
)

private val NO_ATTRIBUTES: List<ErAttribute> = emptyList()

// Resolves a parsed ER diagram into what is drawn: subgraphs as groups, entities as nodes with
// their class styles, and relationships as edges with a marker type at each end.
fun buildErGraph(model: ErModel): ErGraph {
  val nodes = ArrayList<ErGraphNode>()
  val edges = ArrayList<ErGraphEdge>()
  val subgraphs = model.subgraphs

  val compiled = { classNames: List<String> ->
    val out = ArrayList<String>()
    for (name in classNames) {
      val def = model.classes[name] ?: continue
      for (style in def.styles) out.add(style.jsTrim())
      for (style in def.textStyles) out.add(style.jsTrim())
    }
    out
  }

  val parentOf = LinkedHashMap<String, String>()
  val subgraphIds = LinkedHashSet<String>()
  for (i in subgraphs.indices.reversed()) {
    val subgraph = subgraphs[i]
    subgraphIds.add(subgraph.id)
    for (id in subgraph.nodes) parentOf[id] = subgraph.id
  }

  for (i in subgraphs.indices.reversed()) {
    val subgraph = subgraphs[i]
    nodes.add(
      ErGraphNode(
        id = subgraph.id,
        label = subgraph.title,
        alias = "",
        attributes = NO_ATTRIBUTES,
        isGroup = true,
        parentId = parentOf[subgraph.id],
        cssStyles = subgraph.cssStyles,
        cssCompiledStyles = compiled(subgraph.classes),
        cssClasses = subgraph.classes.joinToString(" "),
        dir = subgraph.dir,
        labelType = subgraph.labelType,
      ),
    )
  }

  // A name that is also a subgraph id stands for the subgraph. Mermaid drops such an entity and
  // leaves relationships written before the subgraph pointing at nothing; here they follow it.
  val moved = LinkedHashMap<String, String>()
  for ((name, entity) in model.entities) {
    if (subgraphIds.contains(name)) {
      moved[entity.id] = name
      continue
    }
    nodes.add(
      ErGraphNode(
        id = entity.id,
        label = entity.label,
        alias = entity.alias,
        attributes = entity.attributes,
        isGroup = false,
        parentId = parentOf[name],
        cssStyles = entity.cssStyles,
        cssCompiledStyles = compiled(entity.cssClasses.jsSplit(" ")),
        cssClasses = entity.cssClasses,
        dir = null,
        labelType = "markdown",
      ),
    )
  }

  model.relationships.forEachIndexed { index, relationship ->
    edges.add(
      ErGraphEdge(
        id = "id_${relationship.entityA}_${relationship.entityB}_$index",
        start = moved[relationship.entityA] ?: relationship.entityA,
        end = moved[relationship.entityB] ?: relationship.entityB,
        label = relationship.roleA,
        arrowTypeStart = relationship.relSpec.cardB.lowercase(),
        arrowTypeEnd = relationship.relSpec.cardA.lowercase(),
        pattern = if (relationship.relSpec.relType == "IDENTIFYING") "solid" else "dashed",
      ),
    )
  }

  return ErGraph(nodes, edges)
}
