// Ported from Pele src/diagrams/architecture/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.architecture

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.svg.Accessible

// ArchService | ArchJunction
sealed class ArchNode {
  abstract val id: String
  abstract val type: String
  abstract val `in`: String?
}

class ArchService(
  override val id: String,
  val icon: String? = null,
  val iconText: String? = null,
  val title: String? = null,
  override val `in`: String? = null,
) : ArchNode() {
  override val type = "service"
}

class ArchJunction(
  override val id: String,
  override val `in`: String? = null,
) : ArchNode() {
  override val type = "junction"
}

class ArchGroup(
  val id: String,
  val icon: String? = null,
  val title: String? = null,
  val `in`: String? = null,
)

class ArchEdge(
  val lhsId: String,
  val lhsDir: Side,
  val lhsInto: Boolean? = null,
  val lhsGroup: Boolean? = null,
  val rhsId: String,
  val rhsDir: Side,
  val rhsInto: Boolean? = null,
  val rhsGroup: Boolean? = null,
  val title: String? = null,
)

class ArchLayoutHint(
  // 'row' | 'column'
  val direction: String,
  val members: List<String>,
)

interface ArchitectureModel : Accessible {
  val type: String
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
  val nodes: Map<String, ArchNode>
  val groups: Map<String, ArchGroup>
  val edges: List<ArchEdge>
  val layoutHints: List<ArchLayoutHint>
}

private fun fail(message: String): Nothing {
  throw PeleError(message, PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "architecture"))
}

private val RE_INDENT = JsRegExp("""\n\s+""", "g")
private val RE_LEADING = JsRegExp("""^\s+""", "g")

class ArchitectureDb : ArchitectureModel {
  override val type = "architecture"
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  override val nodes = LinkedHashMap<String, ArchNode>()
  override val groups = LinkedHashMap<String, ArchGroup>()
  override val edges = ArrayList<ArchEdge>()
  override val layoutHints = ArrayList<ArchLayoutHint>()
  // 'node' | 'group'
  private val registered = LinkedHashMap<String, String>()

  private fun register(kind: String, id: String, parent: String?) {
    if (registered.containsKey(id)) {
      fail("The $kind id [$id] is already in use by another ${registered[id]}")
    }
    if (parent != null) {
      if (id == parent) fail("The $kind [$id] cannot be placed within itself")
      if (!registered.containsKey(parent)) {
        fail("The $kind [$id]'s parent does not exist. Please make sure the parent is created before this $kind")
      }
      if (registered[parent] == "node") fail("The $kind [$id]'s parent is not a group")
    }
    registered[id] = if (kind == "group") "group" else "node"
  }

  fun addService(id: String, icon: String?, iconText: String?, title: String?, parent: String?) {
    register("service", id, parent)
    nodes[id] = ArchService(id, icon, iconText, title, parent)
  }

  fun addJunction(id: String, parent: String?) {
    register("junction", id, parent)
    nodes[id] = ArchJunction(id, parent)
  }

  fun addGroup(id: String, icon: String?, title: String?, parent: String?) {
    register("group", id, parent)
    groups[id] = ArchGroup(id, icon, title, parent)
  }

  fun addEdge(edge: ArchEdge) {
    val lhsId = edge.lhsId
    val rhsId = edge.rhsId
    val ends = listOf(Triple("left", lhsId, edge.lhsGroup), Triple("right", rhsId, edge.rhsGroup))
    for ((side, id) in ends) {
      if (!registered.containsKey(id)) {
        fail("The $side-hand id [$id] does not yet exist. Please create the service/group before declaring an edge to it.")
      }
    }
    // Mermaid lets a group id through the check above and then fails with a TypeError.
    for ((side, id) in ends) {
      if (!nodes.containsKey(id)) fail("The $side-hand id [$id] is a group. Add {group} to a service inside it instead.")
    }
    val parent = nodes[lhsId]!!.`in`
    if (parent != null && parent == nodes[rhsId]!!.`in`) {
      for ((side, id, crosses) in ends) {
        if (crosses == true) {
          fail("The $side-hand id [$id] is modified to traverse the group boundary, but the edge does not pass through two groups.")
        }
      }
    }
    edges.add(edge)
  }

  fun addLayoutHint(hint: ArchLayoutHint) {
    if (hint.members.size < 2) fail("An align directive requires at least two members; got ${hint.members.size}")
    val seen = LinkedHashSet<String>()
    for (id in hint.members) {
      if (registered[id] != "node") {
        fail("align ${hint.direction} references [$id], which is not a service or junction")
      }
      if (seen.contains(id)) fail("align ${hint.direction} lists [$id] more than once")
      seen.add(id)
    }
    layoutHints.add(hint)
  }
}

// Mermaid's populateDb: every group, then services, junctions, edges, and alignments.
fun populate(ast: ArchitectureAst, db: ArchitectureDb) {
  val accDescr = ast.accDescr
  if (!accDescr.isNullOrEmpty()) db.accDescr = accDescr.jsReplace(RE_INDENT, "\n")
  val accTitle = ast.accTitle
  if (!accTitle.isNullOrEmpty()) db.accTitle = accTitle.jsReplace(RE_LEADING, "")
  val title = ast.title
  if (!title.isNullOrEmpty()) db.title = title
  for (group in ast.groups) db.addGroup(group.id, group.icon, group.title, group.`in`)
  for (service in ast.services) db.addService(service.id, service.icon, service.iconText, service.title, service.`in`)
  for (junction in ast.junctions) db.addJunction(junction.id, junction.`in`)
  for (edge in ast.edges) {
    db.addEdge(
      ArchEdge(
        lhsId = edge.lhsId,
        lhsDir = edge.lhsDir,
        lhsInto = edge.lhsInto,
        lhsGroup = edge.lhsGroup,
        rhsId = edge.rhsId,
        rhsDir = edge.rhsDir,
        rhsInto = edge.rhsInto,
        rhsGroup = edge.rhsGroup,
        title = edge.title,
      ),
    )
  }
  for (alignment in ast.alignments) db.addLayoutHint(ArchLayoutHint(alignment.direction, ArrayList(alignment.members)))
}
