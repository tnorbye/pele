// Ported from Pele src/diagrams/sankey/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sankey

import pele.svg.Accessible

class SankeyNode(val id: String, val index: Int)

class SankeyLink(val source: SankeyNode, val target: SankeyNode, val value: Double)

interface SankeyModel : Accessible {
  val type: String
  val nodes: List<SankeyNode>
  val links: List<SankeyLink>
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
}

class SankeyDb : SankeyModel {
  override val type = "sankey"
  override val nodes: MutableList<SankeyNode> = ArrayList()
  override val links: MutableList<SankeyLink> = ArrayList()
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null

  private val lookup = LinkedHashMap<String, SankeyNode>()

  fun findOrCreateNode(id: String): SankeyNode {
    var node = lookup[id]
    if (node == null) {
      node = SankeyNode(id, nodes.size)
      lookup[id] = node
      nodes.add(node)
    }
    return node
  }

  fun addLink(source: SankeyNode, target: SankeyNode, value: Double) {
    links.add(SankeyLink(source, target, value))
  }
}
