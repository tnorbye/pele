// Ported from Pele src/diagrams/usecase/graph.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.js.jsKeys
import pele.js.jsReplaceAll
import pele.js.jsSlice
import pele.js.jsString
import pele.js.jsTrim

class JsonRow(
  // Blank on the second and later values of an array.
  val key: String,
  // The key again, for every row.
  val accessibleKey: String,
  val value: String,
)

class UsecaseGraphNode(
  val id: String,
  val label: String,
  val labelType: LabelType,
  // Mermaid's shape names: 'usecaseActor' | 'usecaseActorHollow' | 'usecaseActorAwesome' |
  // 'usecaseActorIcon'
  // | 'usecaseEllipse' | 'usecaseBusiness' | 'rect' | 'note' | 'usecaseJsonTable' |
  // 'usecaseSystemBoundary'
  val shape: String,
  val isGroup: Boolean,
  val padding: Double,
  val cssClasses: String,
  val cssStyles: List<String>,
  val cssCompiledStyles: List<String>,
  val actorType: ActorType? = null,
  val business: Boolean? = null,
  val icon: String? = null,
  val stereotype: String? = null,
  val parentId: String? = null,
  val noteTarget: String? = null,
  val jsonRows: List<JsonRow>? = null,
  val boundaryType: BoundaryType? = null,
)

class UsecaseGraphEdge(
  val id: String,
  val start: String,
  val end: String,
  // RelationshipType | 'note'
  val relationshipType: String,
  // 'solid' | 'dotted'
  val pattern: String,
  val arrowTypeStart: String,
  val arrowTypeEnd: String,
  var label: String? = null,
  var labelType: LabelType? = null,
  val classes: String,
  val style: List<String>,
  val cssCompiledStyles: List<String>,
  val animate: Boolean,
  val animation: Animation? = null,
  val minlen: Int,
  val explicitId: Boolean,
  // The dotted line from a note to what it is about.
  val internal: Boolean,
)

class UsecaseGraph(
  val nodes: List<UsecaseGraphNode>,
  val edges: List<UsecaseGraphEdge>,
)

private val ACTOR_SHAPES =
  mapOf(
    "normal" to "usecaseActor",
    "hollow" to "usecaseActorHollow",
    "awesome" to "usecaseActorAwesome",
    "icon" to "usecaseActorIcon",
  )

// Start and end markers for each arrow type, in the order of the Arrow enum.
private val MARKERS =
  listOf(
    listOf("none", "arrow_point"),
    listOf("arrow_point", "none"),
    listOf("none", "none"),
    listOf("none", "arrow_circle"),
    listOf("none", "arrow_cross"),
    listOf("arrow_circle", "none"),
    listOf("arrow_cross", "none"),
  )

// PORT: stands for a value `undefined`, which JSON.parse never produces but a missing key reads as.
private object Undefined

private fun scalar(value: Any?): String =
  if (value is String) value
  else if (value == null) "null" else if (value === Undefined) "undefined" else jsString(value)

// One row for each leaf of a JSON object, depth first in the order the keys were written.
// Nesting is kept on a stack of its own, so depth is limited by memory and not by the call stack.
fun flattenJsonRows(json: UsecaseJsonNode): List<JsonRow> =
  flattenJsonRows(json.value, json.propertyOrder)

fun flattenJsonRows(
  value: Map<String, Any?>,
  propertyOrder: Map<String, List<String>>,
): List<JsonRow> {
  val rows = ArrayList<JsonRow>()
  val stack = ArrayList<Triple<Any?, String, String>>()
  stack.add(Triple(value, "", ""))
  val children = ArrayList<Triple<Any?, String, String>>()
  while (stack.size > 0) {
    val (current, path, pointer) = stack.removeAt(stack.size - 1)
    children.clear()
    if (current is List<*>) {
      if (current.size == 0) rows.add(JsonRow(path, path, "[]"))
      else if (current.all { item -> item == null || (item !is Map<*, *> && item !is List<*>) }) {
        current.forEachIndexed { i, item ->
          rows.add(JsonRow(if (i == 0) path else "", path, scalar(item)))
        }
      } else
        current.forEachIndexed { i, item -> children.add(Triple(item, "$path[$i]", "$pointer/$i")) }
    } else if (current is Map<*, *>) {
      @Suppress("UNCHECKED_CAST") val obj = current as Map<String, Any?>
      val keys = propertyOrder[pointer] ?: jsKeys(obj)
      if (keys.size == 0) rows.add(JsonRow(path, path, "{}"))
      for (key in keys) {
        children.add(
          Triple(
            if (obj.containsKey(key)) obj[key] else Undefined,
            if (path.isNotEmpty()) "$path.$key" else key,
            "$pointer/${key.jsReplaceAll("~", "~0").jsReplaceAll("/", "~1")}",
          )
        )
      }
    } else rows.add(JsonRow(path, path, scalar(current)))
    for (i in children.size - 1 downTo 0) stack.add(children[i])
  }
  return rows
}

private fun classList(names: List<String?>): String =
  names.filter { !it.isNullOrEmpty() }.joinToString(" ")

private fun markers(relationship: Relationship): List<String> {
  if (relationship.type == "generalization") return listOf("none", "extension")
  if (relationship.type != "association") return MARKERS[Arrow.SOLID_ARROW]
  return MARKERS.getOrNull(relationship.arrowType) ?: MARKERS[Arrow.LINE_SOLID]
}

// Resolves a parsed use case diagram into what is drawn: every actor, use case, note, JSON table
// and boundary as a node with its class styles, and every relationship and note connector as an
// edge with its markers. Ports getData from Mermaid's use case DB.
fun buildUsecaseGraph(model: UsecaseModel): UsecaseGraph {
  val nodes = ArrayList<UsecaseGraphNode>()
  val edges = ArrayList<UsecaseGraphEdge>()

  // A later class wins a property but keeps the place the property first took.
  val compiled = { classNames: List<String> ->
    val out = LinkedHashMap<String, String>()
    for (name in listOf("default") + classNames) {
      val definition = model.classDefs[name] ?: continue
      for (raw in definition.styles) {
        val style = raw.jsTrim()
        val colon = style.indexOf(':')
        val property = (if (colon == -1) style else style.jsSlice(0, colon)).jsTrim()
        if (property.isNotEmpty()) out[property] = style
      }
    }
    out.values.toList()
  }

  for (actor in model.actors.values) {
    nodes.add(
      UsecaseGraphNode(
        id = actor.id,
        label = actor.label,
        labelType = actor.labelType,
        shape = ACTOR_SHAPES.getValue(actor.type),
        isGroup = false,
        padding = 10.0,
        cssClasses =
          classList(
            listOf<String?>(
              "default",
              "usecase-actor",
              "usecase-actor-${actor.type}",
              if (actor.business) "usecase-business" else null,
            ) + actor.classes
          ),
        cssStyles = actor.styles.toList(),
        cssCompiledStyles = compiled(actor.classes),
        actorType = actor.type,
        business = actor.business,
        icon = if (actor.icon.isNullOrEmpty()) null else actor.icon,
        stereotype = if (actor.stereotype.isNullOrEmpty()) null else actor.stereotype,
        parentId = if (actor.parentId.isNullOrEmpty()) null else actor.parentId,
      )
    )
  }
  for (useCase in model.useCases.values) {
    val ellipse = useCase.shape == "ellipse"
    nodes.add(
      UsecaseGraphNode(
        id = useCase.id,
        label = useCase.label,
        labelType = useCase.labelType,
        shape =
          if (!ellipse) "rect" else if (useCase.business) "usecaseBusiness" else "usecaseEllipse",
        isGroup = false,
        padding = if (ellipse) 20.0 else 10.0,
        cssClasses =
          classList(
            listOf<String?>(
              "default",
              "usecase-element",
              "usecase-${useCase.shape}",
              if (useCase.business) "usecase-business" else null,
            ) + useCase.classes
          ),
        cssStyles = useCase.styles.toList(),
        cssCompiledStyles = compiled(useCase.classes),
        business = useCase.business,
        stereotype = if (useCase.stereotype.isNullOrEmpty()) null else useCase.stereotype,
        parentId = if (useCase.parentId.isNullOrEmpty()) null else useCase.parentId,
      )
    )
  }
  for (note in model.notes.values) {
    nodes.add(
      UsecaseGraphNode(
        id = note.id,
        label = note.label,
        labelType = note.labelType,
        shape = "note",
        isGroup = false,
        padding = 10.0,
        cssClasses = "default usecase-note",
        cssStyles = listOf(),
        cssCompiledStyles = compiled(listOf()),
        noteTarget = note.target,
      )
    )
  }
  for (json in model.jsonNodes.values) {
    nodes.add(
      UsecaseGraphNode(
        id = json.id,
        label = json.id,
        labelType = "text",
        shape = "usecaseJsonTable",
        isGroup = false,
        padding = 10.0,
        cssClasses = classList(listOf<String?>("default", "usecase-json-table") + json.classes),
        cssStyles = json.styles.toList(),
        cssCompiledStyles = compiled(json.classes),
        jsonRows = flattenJsonRows(json),
      )
    )
  }
  for (boundary in model.systemBoundaries.values) {
    nodes.add(
      UsecaseGraphNode(
        id = boundary.id,
        label = boundary.label,
        labelType = boundary.labelType,
        shape = "usecaseSystemBoundary",
        isGroup = true,
        padding = 20.0,
        cssClasses =
          classList(
            listOf<String?>("default", "system-boundary", "system-boundary-${boundary.type}") +
              boundary.classes
          ),
        cssStyles = boundary.styles.toList(),
        cssCompiledStyles = compiled(boundary.classes),
        boundaryType = boundary.type,
      )
    )
  }

  for (relationship in model.relationships) {
    val semantic = relationship.type == "include" || relationship.type == "extend"
    val (arrowTypeStart, arrowTypeEnd) = markers(relationship)
    val animated = relationship.animate || relationship.animation != null
    val edge =
      UsecaseGraphEdge(
        id = relationship.id,
        start = relationship.source,
        end = relationship.target,
        relationshipType = relationship.type,
        pattern = if (semantic) "dotted" else "solid",
        arrowTypeStart = arrowTypeStart,
        arrowTypeEnd = arrowTypeEnd,
        classes =
          classList(
            listOf<String?>(
              "default",
              "relationship",
              "relationship-${relationship.type}",
            ) +
              relationship.classes +
              listOf(if (animated) "edge-animation-${relationship.animation ?: "fast"}" else null)
          ),
        style = relationship.styles.toList(),
        cssCompiledStyles = compiled(relationship.classes),
        animate = relationship.animate,
        animation = relationship.animation,
        minlen = relationship.minlen,
        explicitId = relationship.explicitId,
        internal = false,
      )
    // Include and extend are labelled with their kind, whatever label the relationship carries.
    if (semantic) {
      edge.label = relationship.type
      edge.labelType = "text"
    } else if (relationship.type == "association") {
      if (!relationship.label.isNullOrEmpty()) edge.label = relationship.label
      if (!relationship.labelType.isNullOrEmpty()) edge.labelType = relationship.labelType
    }
    edges.add(edge)
  }
  for (note in model.notes.values) {
    edges.add(
      UsecaseGraphEdge(
        id = "${note.id}-edge",
        start = note.id,
        end = note.target,
        relationshipType = "note",
        pattern = "dotted",
        arrowTypeStart = "none",
        arrowTypeEnd = "none",
        classes = "default relationship relationship-note",
        style = listOf(),
        cssCompiledStyles = compiled(listOf()),
        animate = false,
        minlen = 1,
        explicitId = false,
        internal = true,
      )
    )
  }
  return UsecaseGraph(nodes, edges)
}
