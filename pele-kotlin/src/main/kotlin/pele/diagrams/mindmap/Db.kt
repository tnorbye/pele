// Ported from Pele src/diagrams/mindmap/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.mindmap

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.diagrams.common.Decoration
import pele.diagrams.common.OutlineDb

class MindmapNode(
  val id: Int,
  val nodeId: String,
  val level: Int,
  val descr: String,
  val type: Int,
  val children: MutableList<MindmapNode>,
  val isRoot: Boolean,
  // Index of the first-level branch the node belongs to, wrapping after eleven as in Mermaid.
  var section: Int? = null,
  var `class`: String? = null,
  var icon: String? = null,
)

interface MindmapModel {
  val type: String
  val title: String?
  // In source order, which is depth-first. The first node is the root.
  val nodes: List<MindmapNode>
}

private const val SECTIONS = 11

class MindmapDb : MindmapModel, OutlineDb {
  override val type = "mindmap"
  override var title: String? = null
  override val nodes: MutableList<MindmapNode> = ArrayList()

  override fun getType(start: String, end: String): Int = pele.diagrams.common.getType(start, end)

  private var baseLevel = 0

  // The chain of nodes that can still take children, with strictly increasing levels.
  private val open: MutableList<MindmapNode> = ArrayList()

  override fun addNode(level: Int, id: String, descr: String, type: Int, shapeData: String?) {
    val isRoot = this.nodes.size == 0
    if (isRoot) this.baseLevel = level
    val relative = level - this.baseLevel

    // Mermaid's parent is the last node with a smaller indentation, wherever it is in the tree.
    val open = this.open
    var depth = open.size
    while (depth > 0 && open[depth - 1].level >= relative) depth--
    val parent = open.getOrNull(depth - 1)
    if (parent == null && !isRoot) {
      throw PeleError("There can be only one root. No parent could be found for (\"$descr\")", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "mindmap"))
    }
    val node = MindmapNode(this.nodes.size, id, relative, descr, type, ArrayList(), isRoot)
    if (parent != null) {
      node.section = if (parent.isRoot) parent.children.size % SECTIONS else parent.section
      parent.children.add(node)
    }
    while (open.size > depth) open.removeAt(open.size - 1)
    open.add(node)
    this.nodes.add(node)
  }

  // Mermaid throws a TypeError when a decoration comes before any node. Here it is ignored.
  override fun decorateNode(decoration: Decoration) {
    val node = this.nodes.getOrNull(this.nodes.size - 1) ?: return
    if (!decoration.icon.isNullOrEmpty()) node.icon = decoration.icon
    if (!decoration.`class`.isNullOrEmpty()) node.`class` = decoration.`class`
  }
}
