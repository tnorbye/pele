// Ported from Pele src/diagrams/ishikawa/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.ishikawa

private class Level(val level: Int, val node: IshikawaNode)

class IshikawaDb : IshikawaModel {
  override val type = "ishikawa"
  override var title: String? = null
  override var root: IshikawaNode? = null

  // The line of ancestors of the last node, each with its level.
  private var stack: MutableList<Level> = ArrayList()
  private var baseLevel: Int? = null

  // The first line is the effect. Every later line is a cause, nested by its indentation relative
  // to the first cause, so the effect itself may be indented more than the causes.
  fun addNode(rawLevel: Int, text: String) {
    val node = IshikawaNode(text, ArrayList())
    if (this.root == null) {
      this.root = node
      this.stack = mutableListOf(Level(0, node))
      return
    }
    if (this.baseLevel == null) this.baseLevel = rawLevel
    val level = maxOf(rawLevel - this.baseLevel!! + 1, 1)
    val stack = this.stack
    while (stack.size > 1 && stack[stack.size - 1].level >= level) stack.removeAt(stack.size - 1)
    stack[stack.size - 1].node.children.add(node)
    stack.add(Level(level, node))
  }
}
