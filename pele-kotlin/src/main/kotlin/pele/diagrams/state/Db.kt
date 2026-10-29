// Ported from Pele src/diagrams/state/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsTrim

private class Root(
  val id: String,
  var doc: MutableList<Any>?,
)

private class Pending(val parentId: String, val node: Any, val first: Boolean)

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")
private val RE_SEMI = JsRegExp(";", "g")

// Mermaid's StateDB in its version 2 form, which is what Mermaid uses for both `stateDiagram`
// and `stateDiagram-v2`. The legacy version, which numbers `[*]` states, is not ported.
class StateDb : StateModel, StateBuilder {
  override val type = "state"
  override var rootDoc: MutableList<Any> = ArrayList()
    private set

  override var states = LinkedHashMap<String, StateStmt>()
  override var relations = ArrayList<StateRelation>()
  override var classes = LinkedHashMap<String, StateClassDef>()
  override val links = LinkedHashMap<String, StateLink>()
  var graph: StateGraph = StateGraph(ArrayList(), ArrayList())
  override var title: String? = null
  override var accTitle: String? = null
    private set

  override var accDescr: String? = null
    private set

  private var dividerCount = 0

  // The direction the root document is laid out in: its last `direction` statement.
  override val direction: String
    get() {
      var dir = "TB"
      for (item in rootDoc) if (item is DirectionStmt) dir = item.value
      return dir
    }

  override fun setRootDoc(doc: MutableList<Any>) {
    rootDoc = doc
    translate()
    extract(doc)
  }

  // Only the root document's statements become states, relations and classes here; the
  // statements inside composites are read when the graph is built.
  fun extract(doc: List<Stmt>) {
    states = LinkedHashMap()
    relations = ArrayList()
    classes = LinkedHashMap()
    for (item in doc) {
      if (item is String) continue
      when (item) {
        is StateStmt ->
          addState(item.id.jsTrim(), item.type ?: "default", item.doc, item.description, item.note)
        is RelationStmt -> addRelation(item.state1, item.state2, item.description)
        is ClassDefStmt -> addStyleClass(item.id.jsTrim(), item.classes)
        is StyleStmt -> {
          val styles = item.styleClass.jsSplit(",").map { s -> s.jsReplace(RE_SEMI, "").jsTrim() }
          for (state in named(item.id.jsTrim())) state.styles = ArrayList(styles)
        }
        is ApplyClassStmt -> setCssClass(item.id.jsTrim(), item.styleClass)
        is ClickStmt ->
          addLink(
            if (item.id is String) item.id else (item.id as StateStmt).id,
            item.url,
            item.tooltip,
          )
      }
    }
    graph = buildStateGraph(doc, states, classes)
  }

  // Mermaid's docTranslator: gives each `[*]` an id scoped to its parent, and turns a document
  // split by `--` into one region per part.
  fun translate() {
    val pending = ArrayList<Pending>()
    pending.add(Pending("root", Root("root", rootDoc), true))
    while (pending.size > 0) {
      val entry = pending.removeAt(pending.size - 1)
      val parentId = entry.parentId
      val node = entry.node
      val first = entry.first
      if (node is String) continue
      if (node is RelationStmt) {
        pending.add(Pending(parentId, node.state2, false))
        pending.add(Pending(parentId, node.state1, true))
        continue
      }
      val nodeId: String
      if (node is StateStmt) {
        if (node.id == "[*]") {
          node.id = parentId + (if (first) "_start" else "_end")
          node.start = first
        } else {
          node.id = node.id.jsTrim()
        }
        nodeId = node.id
      } else if (node is Root) {
        nodeId = node.id
      } else {
        continue
      }
      val nodeDoc = if (node is StateStmt) node.doc else (node as Root).doc
      if (nodeDoc == null) continue

      val regions = ArrayList<Any>()
      var current = ArrayList<Any>()
      for (item in nodeDoc) {
        if (item is StateStmt && item.type == "divider" && item.doc == null) {
          regions.add(item.copy(doc = current))
          current = ArrayList()
        } else {
          current.add(item)
        }
      }
      // Mermaid names the region after the last divider with a random id; here it continues the
      // divider numbering. Mermaid also leaves a document that ends with a divider ungrouped and
      // then fails to draw it; here the regions before that divider are kept.
      var finalDoc: MutableList<Any> = nodeDoc
      if (regions.size > 0) {
        if (current.size > 0)
          regions.add(
            StateStmt(id = getDividerId(), type = "divider", doc = current, descriptions = null)
          )
        finalDoc = regions
        if (node is StateStmt) node.doc = regions else (node as Root).doc = regions
      }
      for (i in finalDoc.size - 1 downTo 0) pending.add(Pending(nodeId, finalDoc[i], true))
    }
  }

  fun addState(
    id: String,
    type: String = "default",
    doc: MutableList<Any>? = null,
    descr: Any? = null,
    note: StateNote? = null,
    classes: List<String>? = null,
  ) {
    val trimmedId = id.jsTrim()
    var state = states[trimmedId]
    if (state == null) {
      state =
        StateStmt(
          id = trimmedId,
          type = type,
          descriptions = ArrayList(),
          doc = doc,
          note = note,
          classes = ArrayList(),
          styles = ArrayList(),
          textStyles = ArrayList(),
        )
      states[trimmedId] = state
    } else {
      if (state.doc == null) state.doc = doc
      if (state.type.isNullOrEmpty()) state.type = type
    }
    // Truthy: a non-empty string or any array.
    if (descr != null && !(descr is String && descr.isEmpty())) {
      @Suppress("UNCHECKED_CAST")
      for (text in
        (if (descr is List<*>) descr as List<String> else listOf(descr as String))) addDescription(
        trimmedId,
        text.jsTrim(),
      )
    }
    if (note != null) state.note = note
    if (classes != null) for (name in classes) setCssClass(trimmedId, name.jsTrim())
  }

  fun getState(id: String): StateStmt? = states[id]

  fun addLink(stateId: String, url: String, tooltip: String) {
    links[stateId] = StateLink(url, tooltip)
  }

  fun addRelation(item1: StateStmt, item2: StateStmt, title: String? = null) {
    for (item in listOf(item1, item2)) addState(
      item.id,
      item.type ?: "default",
      item.doc,
      item.description,
      item.note,
      item.classes,
    )
    relations.add(StateRelation(item1.id.jsTrim(), item2.id.jsTrim(), title ?: ""))
  }

  fun addDescription(id: String, descr: String) {
    states[id]
      ?.descriptions
      ?.add(if (descr.startsWith(":")) descr.jsReplace(":", "").jsTrim() else descr)
  }

  override fun getDividerId(): String = "divider-id-${++dividerCount}"

  fun addStyleClass(id: String, styleAttributes: String = "") {
    var found = classes[id]
    if (found == null) {
      found = StateClassDef(id, ArrayList(), ArrayList())
      classes[id] = found
    }
    if (styleAttributes.isEmpty()) return
    for (attrib in styleAttributes.jsSplit(",")) {
      val fixed = attrib.jsReplace(";", "").jsTrim()
      if (attrib.contains("color"))
        found.textStyles.add(fixed.jsReplace("fill", "bgFill").jsReplace("color", "fill"))
      found.styles.add(fixed)
    }
  }

  fun setCssClass(itemIds: String, cssClassName: String) {
    for (state in named(itemIds)) state.classes?.add(cssClassName)
  }

  @JvmName("rootDirection")
  fun getDirection(): String {
    for (item in rootDoc) if (item is DirectionStmt) return item.value
    return "TB"
  }

  // The grammar calls this while it is still building the document, so the statement lands in the
  // previous root document and the direction is read from the parsed `dir` statements instead.
  override fun setDirection(dir: String) {
    for (item in rootDoc) {
      if (item is DirectionStmt) {
        item.value = dir
        return
      }
    }
    rootDoc.add(0, DirectionStmt(dir))
  }

  override fun trimColon(text: String): String =
    if (text.startsWith(":")) text.jsSlice(1).jsTrim() else text.jsTrim()

  override fun setAccTitle(text: String) {
    accTitle = text.jsReplace(RE_LEADING, "")
  }

  override fun setAccDescription(text: String) {
    accDescr = text.jsReplace(RE_INDENT, "\n")
  }

  // The states a comma-separated list of ids names, created where they do not exist yet. As in
  // Mermaid, an id is looked up as written and trimmed only when it has to be created.
  private fun named(ids: String): List<StateStmt> {
    val out = ArrayList<StateStmt>()
    for (id in ids.jsSplit(",")) {
      var state = states[id]
      if (state == null) {
        addState(id)
        state = states[id.jsTrim()]
      }
      if (state != null) out.add(state)
    }
    return out
  }
}
