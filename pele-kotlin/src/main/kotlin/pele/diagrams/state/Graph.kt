// Ported from Pele src/diagrams/state/graph.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.jsSplit
import pele.js.jsTrim

class StateNode(
  var id: String,
  // rect, rectWithTitle, stateStart, stateEnd, choice, fork, join, divider, roundedWithTitle, note
  // or noteGroup.
  var shape: String?,
  var label: String?,
  var description: List<String>,
  var cssClasses: String,
  var cssCompiledStyles: List<String>,
  var cssStyles: List<String>,
  var type: String?,
  var isGroup: Boolean,
  var parentId: String? = null,
  // The direction inside a composite: its last `direction` statement, or TB.
  var dir: String?,
  var position: String? = null,
  var noteFor: String? = null,
  var colorIndex: Int? = null,
  // PORT: whether `position` is an own key of the node, which Object.assign copies even when
  // undefined.
  val hasPosition: Boolean = false,
)

// Labels are markdown throughout, as in Mermaid.
class StateEdge(
  val id: String,
  val start: String,
  val end: String,
  val label: String,
  val classes: String,
  // 'dashed'
  val pattern: String? = null,
)

class StateGraph(
  val nodes: List<StateNode>,
  val edges: List<StateEdge>,
)

private class Draft(
  var shape: String?,
  // A String or a List<String>.
  var description: Any,
  var cssClasses: String,
  var cssStyles: List<String>,
  var type: String?,
  var dir: String?,
  var colorIndex: Int?,
)

private class Frame(
  val item: StateStmt?,
  val doc: List<Stmt>,
  var at: Int,
  val alt: Boolean,
)

private fun direction(doc: List<Stmt>): String {
  var dir = "TB"
  for (item in doc) if (item is DirectionStmt) dir = item.value
  return dir
}

// PORT: `.length` of a string or an array.
private fun lengthOf(description: Any): Int =
  if (description is String) description.length else (description as List<*>).size

// Mermaid's dataFetcher: walks the documents and resolves them into the nodes and edges that are
// drawn.
fun buildStateGraph(
  rootDoc: List<Stmt>,
  states: Map<String, StateStmt>,
  classes: Map<String, StateClassDef>,
): StateGraph {
  val nodes = ArrayList<StateNode>()
  val edges = ArrayList<StateEdge>()
  val byId = LinkedHashMap<String, StateNode>()
  val drafts = LinkedHashMap<String, Draft>()
  val slots = LinkedHashMap<String, Int?>()
  var counter = 0
  var nextSlot = 0

  fun insert(node: StateNode) {
    // The two literal ids are what DOMPurify leaves of `<<fork>>` and `<<choice>>` in Mermaid; kept
    // for parity.
    if (node.id.isEmpty() || node.id == "</join></fork>" || node.id == "</choice>") return
    if (node.cssClasses.isNotEmpty()) {
      // One copy, however many classes: a state named in a class statement many times has a long
      // list.
      var compiled: ArrayList<String>? = null
      for (name in node.cssClasses.jsSplit(" ")) {
        val def = classes[name] ?: continue
        if (compiled == null) compiled = ArrayList(node.cssCompiledStyles)
        for (style in def.styles) compiled.add(style)
      }
      if (compiled != null) node.cssCompiledStyles = compiled
    }
    val existing = byId[node.id]
    if (existing != null) {
      val parentId = node.parentId ?: existing.parentId
      existing.id = node.id
      existing.shape = node.shape
      existing.label = node.label
      existing.description = node.description
      existing.cssClasses = node.cssClasses
      existing.cssCompiledStyles = node.cssCompiledStyles
      existing.cssStyles = node.cssStyles
      existing.type = node.type
      existing.isGroup = node.isGroup
      existing.dir = node.dir
      existing.colorIndex = node.colorIndex
      if (node.hasPosition) existing.position = node.position
      if (node.noteFor != null) existing.noteFor = node.noteFor
      if (node.parentId != null) existing.parentId = node.parentId
      if (parentId != null) existing.parentId = parentId
    } else {
      byId[node.id] = node
      nodes.add(node)
    }
  }

  fun visit(parent: StateStmt?, item: StateStmt, alt: Boolean) {
    val id = item.id
    if (id == "root") return
    val known = states[id]
    val classStr = known?.classes?.joinToString(" ") ?: ""
    val styles: List<String> = known?.styles ?: ArrayList()

    var draft = drafts[id]
    if (draft == null) {
      var shape: String? = "rect"
      if (item.start == true) shape = "stateStart" else if (item.start == false) shape = "stateEnd"
      if (item.type != "default") shape = item.type
      draft =
        Draft(
          shape = shape,
          description = id,
          cssClasses = "$classStr statediagram-state",
          cssStyles = styles,
          type = null,
          dir = null,
          colorIndex = null,
        )
      drafts[id] = draft
    }

    val itemDescription = item.description
    // Truthy: a non-empty string or any array.
    if (itemDescription != null && !(itemDescription is String && itemDescription.isEmpty())) {
      val current = draft.description
      if (current is List<*>) {
        draft.shape = "rectWithTitle"
        @Suppress("UNCHECKED_CAST")
        draft.description =
          current +
            (if (itemDescription is List<*>) itemDescription as List<String>
            else listOf(itemDescription as String))
      } else if ((current as String).length > 0) {
        draft.shape = "rectWithTitle"
        @Suppress("UNCHECKED_CAST")
        val added: List<String> =
          if (itemDescription is List<*>) itemDescription as List<String>
          else listOf(itemDescription as String)
        draft.description = if (current == id) added.toList() else listOf(current) + added
      } else {
        draft.shape = "rect"
        @Suppress("UNCHECKED_CAST")
        draft.description =
          if (itemDescription is List<*>) (itemDescription as List<String>).toList()
          else itemDescription
      }
    }
    if (lengthOf(draft.description) == 1 && draft.shape == "rectWithTitle") {
      draft.shape = if (draft.type == "group") "roundedWithTitle" else "rect"
    }

    val itemDoc = item.doc
    if (draft.type.isNullOrEmpty() && itemDoc != null) {
      draft.type = "group"
      draft.dir = direction(itemDoc)
      draft.shape = if (item.type == "divider") "divider" else "roundedWithTitle"
      // A container takes the next palette slot unless it is a region, which shares its
      // composite's.
      if (draft.shape == "divider" && parent?.id != null && slots.containsKey(parent.id)) {
        draft.colorIndex = slots[parent.id]
      } else {
        val slot = nextSlot++
        draft.colorIndex = if (classStr.jsTrim() != "" || styles.size > 0) null else slot
      }
      slots[id] = draft.colorIndex
      draft.cssClasses =
        "${draft.cssClasses} statediagram-cluster ${if (alt) "statediagram-cluster-alt" else ""}"
    }

    val full = draft.description
    val node =
      StateNode(
        id = id,
        shape = draft.shape,
        label = if (full is List<*>) (full.getOrNull(0) as String?) else full as String,
        description = if (full is List<*>) full.drop(1).map { it as String } else emptyList(),
        cssClasses = draft.cssClasses,
        cssCompiledStyles = emptyList(),
        cssStyles = draft.cssStyles,
        type = draft.type,
        isGroup = draft.type == "group",
        dir = draft.dir,
        colorIndex = draft.colorIndex,
      )
    if (node.shape == "divider") node.label = ""
    if (parent != null && parent.id != "root") node.parentId = parent.id

    val note = item.note
    if (note == null) {
      insert(node)
      return
    }
    // Mermaid puts each note in a group of its own, named after the state. Pele keeps that
    // group in the model and lays the note out beside its state instead.
    fun aside(asideId: String, shape: String, cssClasses: String, isGroup: Boolean): StateNode =
      StateNode(
        id = asideId,
        shape = shape,
        label = note.text,
        description = emptyList(),
        cssClasses = cssClasses,
        cssCompiledStyles = emptyList(),
        cssStyles = emptyList(),
        type = if (isGroup) "group" else "node",
        isGroup = isGroup,
        dir = null,
        position = note.position,
        hasPosition = true,
      )
    val noteNode = aside("$id----note-${counter++}", "note", "statediagram-note", false)
    noteNode.parentId = "$id----parent"
    noteNode.noteFor = id
    insert(aside(noteNode.parentId!!, "noteGroup", draft.cssClasses, true))
    insert(noteNode)
    insert(node)
    val left = note.position == "left of"
    val from = if (left) noteNode.id else id
    val to = if (left) id else noteNode.id
    edges.add(StateEdge("$from-$to", from, to, "", "transition note-edge", "dashed"))
  }

  val stack = ArrayList<Frame>()
  stack.add(Frame(null, rootDoc, 0, true))
  while (stack.size > 0) {
    val frame = stack[stack.size - 1]
    if (frame.at >= frame.doc.size) {
      stack.removeAt(stack.size - 1)
      continue
    }
    val item = frame.doc[frame.at++]
    if (item is String) continue
    if (item is StateStmt) {
      visit(frame.item, item, frame.alt)
      val doc = item.doc
      if (doc != null) stack.add(Frame(item, doc, 0, !frame.alt))
    } else if (item is RelationStmt) {
      visit(frame.item, item.state1, frame.alt)
      visit(frame.item, item.state2, frame.alt)
      edges.add(
        StateEdge(
          "edge${counter++}",
          item.state1.id,
          item.state2.id,
          item.description ?: "",
          "transition",
        )
      )
    }
  }

  for (node in nodes) {
    if (node.isGroup && node.description.size > 0) {
      throw PeleError(
        "Group nodes can only have label. Remove the additional description for node [${node.id}]",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "state"),
      )
    }
  }
  return StateGraph(nodes, edges)
}
