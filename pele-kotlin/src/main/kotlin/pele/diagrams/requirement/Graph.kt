// Ported from Pele src/diagrams/requirement/graph.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

class GraphNode(
  val id: String,
  val requirement: Requirement?,
  val element: RequirementElement?,
  val cssStyles: List<String>,
  val cssClasses: String,
)

class GraphEdge(
  val id: String,
  val start: String?,
  val end: String?,
  val type: String,
  val contains: Boolean,
)

class RequirementGraph(
  val nodes: List<GraphNode>,
  val edges: List<GraphEdge>,
  val direction: String,
)

// The nodes and edges to lay out: requirements first, then elements, as Mermaid orders them.
fun buildRequirementGraph(model: RequirementModel): RequirementGraph {
  val nodes = ArrayList<GraphNode>()
  for (requirement in model.requirements.values) {
    nodes.add(
      GraphNode(
        id = requirement.name,
        requirement = requirement,
        element = null,
        cssStyles = requirement.cssStyles,
        cssClasses = requirement.classes.joinToString(" "),
      )
    )
  }
  for (element in model.elements.values) {
    nodes.add(
      GraphNode(
        id = element.name,
        requirement = null,
        element = element,
        cssStyles = element.cssStyles,
        cssClasses = element.classes.joinToString(" "),
      )
    )
  }
  val has = { id: String ->
    if (model.requirements.containsKey(id) || model.elements.containsKey(id)) id else null
  }
  val edges =
    model.relations.mapIndexed { index, relation ->
      GraphEdge(
        id = "${relation.src}-${relation.dst}-$index",
        start = has(relation.src),
        end = has(relation.dst),
        type = relation.type,
        contains = relation.type == Relationships.CONTAINS,
      )
    }
  return RequirementGraph(nodes, edges, model.direction)
}
